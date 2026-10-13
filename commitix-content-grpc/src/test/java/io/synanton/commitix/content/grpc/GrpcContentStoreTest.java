package io.synanton.commitix.content.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.synanton.commitix.content.ContentIntegrityException;
import io.synanton.commitix.content.ContentMetadata;
import io.synanton.commitix.content.ContentNotFoundException;
import io.synanton.commitix.content.ContentReference;
import io.synanton.commitix.content.ContentStoreException;
import io.synanton.commitix.content.Digests;
import io.synanton.commitix.content.PutResult;
import io.synanton.content.v1.CapabilitiesRequest;
import io.synanton.content.v1.CapabilitiesResponse;
import io.synanton.content.v1.ContentServiceGrpc;
import io.synanton.content.v1.GetRequest;
import io.synanton.content.v1.GetResponse;
import io.synanton.content.v1.HeadRequest;
import io.synanton.content.v1.HeadResponse;
import io.synanton.content.v1.PutRequest;
import io.synanton.content.v1.PutResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link GrpcContentStore} against an in-process fake
 * speaking {@code content.v1.ContentService} with Go-service semantics:
 * per-tenant dedup, indistinguishable cross-tenant not-found, digest binding.
 */
class GrpcContentStoreTest {

    private io.grpc.Server server;
    private GrpcContentStore store;

    static final class FakeService extends ContentServiceGrpc.ContentServiceImplBase {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public void put(PutRequest request, StreamObserver<PutResponse> observer) {
            if (request.getTenantId().isBlank() || request.getPayload().isEmpty()) {
                observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
                return;
            }
            byte[] payload = request.getPayload().toByteArray();
            String digest = Digests.sha256(payload);
            String artifactId = findByDigest(request.getTenantId(), digest);
            boolean dedupHit = artifactId != null;
            if (!dedupHit) {
                artifactId = UUID.randomUUID().toString();
                objects.put(request.getTenantId() + "\0" + artifactId, payload);
            }
            observer.onNext(PutResponse.newBuilder()
                    .setReference("v1:tenant_id=" + request.getTenantId())
                    .setArtifactId(artifactId)
                    .setDigest(digest)
                    .setDedupHit(dedupHit)
                    .setStorageTier("inline")
                    .build());
            observer.onCompleted();
        }

        @Override
        public void get(GetRequest request, StreamObserver<GetResponse> observer) {
            byte[] payload = objects.get(request.getTenantId() + "\0" + request.getArtifactId());
            if (payload == null) {
                observer.onError(Status.NOT_FOUND.asRuntimeException());
                return;
            }
            observer.onNext(GetResponse.newBuilder()
                    .setPayload(ByteString.copyFrom(payload))
                    .setDigest(Digests.sha256(payload))
                    .setArtifactId(request.getArtifactId())
                    .build());
            observer.onCompleted();
        }

        @Override
        public void head(HeadRequest request, StreamObserver<HeadResponse> observer) {
            boolean exists = objects.containsKey(request.getTenantId() + "\0" + request.getArtifactId());
            if (!exists) {
                observer.onError(Status.NOT_FOUND.asRuntimeException());
                return;
            }
            observer.onNext(HeadResponse.newBuilder()
                    .setExists(true)
                    .setArtifactId(request.getArtifactId())
                    .build());
            observer.onCompleted();
        }

        @Override
        public void capabilities(CapabilitiesRequest request, StreamObserver<CapabilitiesResponse> observer) {
            observer.onNext(CapabilitiesResponse.newBuilder()
                    .setConformanceLevel("CORE")
                    .build());
            observer.onCompleted();
        }

        private String findByDigest(String tenantId, String digest) {
            for (Map.Entry<String, byte[]> entry : objects.entrySet()) {
                if (entry.getKey().startsWith(tenantId + "\0")
                        && Digests.sha256(entry.getValue()).equals(digest)) {
                    return entry.getKey().substring(tenantId.length() + 1);
                }
            }
            return null;
        }

        void tamper(String tenantId, String artifactId, byte[] bytes) {
            objects.put(tenantId + "\0" + artifactId, Arrays.copyOf(bytes, bytes.length));
        }
    }

    private final FakeService fake = new FakeService();

    @BeforeEach
    void setUp() throws IOException {
        String name = "content-it-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(name).addService(fake).build().start();
        store = GrpcContentStore.over(InProcessChannelBuilder.forName(name).build());
    }

    @AfterEach
    void tearDown() {
        server.shutdownNow();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putGetRoundTrip() {
        PutResult res = store.put("acme", bytes("hello"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(res.dedupHit()).isFalse();
        assertThat(store.get(res.reference())).isEqualTo(bytes("hello"));
        assertThat(store.exists(res.reference())).isTrue();
    }

    @Test
    void sameTenantDedupHits() {
        PutResult first = store.put("acme", bytes("dedup me"), ContentMetadata.of("text/plain", "owner-1"));
        PutResult second = store.put("acme", bytes("dedup me"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(second.dedupHit()).isTrue();
        assertThat(second.reference().artifactId()).isEqualTo(first.reference().artifactId());
    }

    @Test
    void sameBytesDifferentTenantsAreIsolated() {
        PutResult a = store.put("tenant-a", bytes("shared"), ContentMetadata.of("text/plain", "owner-1"));
        PutResult b = store.put("tenant-b", bytes("shared"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(b.reference().artifactId()).isNotEqualTo(a.reference().artifactId());
        assertThat(b.dedupHit()).isFalse();
    }

    @Test
    void crossTenantGetIsIndistinguishableNotFound() {
        PutResult res = store.put("tenant-a", bytes("secret"), ContentMetadata.of("text/plain", "owner-1"));
        ContentReference foreign = new ContentReference(
                "tenant-b", res.reference().artifactId(), res.reference().digest(), 1, 1);
        assertThatThrownBy(() -> store.get(foreign)).isInstanceOf(ContentNotFoundException.class);
        assertThat(store.exists(foreign)).isFalse();
    }

    @Test
    void corruptRemoteBytesFailClosed() {
        PutResult res = store.put("acme", bytes("original"), ContentMetadata.of("text/plain", "owner-1"));
        fake.tamper("acme", res.reference().artifactId(), bytes("tampered"));
        assertThatThrownBy(() -> store.get(res.reference()))
                .isInstanceOf(ContentIntegrityException.class);
    }

    @Test
    void rejectsEmptyPayloadAndMissingTenant() {
        assertThatThrownBy(() -> store.put("acme", new byte[0], ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
        assertThatThrownBy(() -> store.put("", bytes("x"), ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
    }
}
