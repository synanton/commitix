package io.synanton.commitix.content.grpc;

import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.synanton.commitix.content.ContentIntegrityException;
import io.synanton.commitix.content.ContentMetadata;
import io.synanton.commitix.content.ContentNotFoundException;
import io.synanton.commitix.content.ContentReference;
import io.synanton.commitix.content.ContentStore;
import io.synanton.commitix.content.ContentStoreException;
import io.synanton.commitix.content.Digests;
import io.synanton.commitix.content.PutResult;
import io.synanton.content.v1.ContentServiceGrpc;
import io.synanton.content.v1.GetRequest;
import io.synanton.content.v1.HeadRequest;
import io.synanton.content.v1.PutRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * {@link ContentStore} over the Content Service gRPC API
 * ({@code content.v1.ContentService}).
 *
 * <p>Every RPC carries an explicit deadline (default 5s per proposal §3.3);
 * retries, if any, are bounded by the caller's deadline — this client never
 * extends a deadline on retry. Reads verify the SHA-256 digest and fail
 * closed on mismatch. Transport is plaintext by default; front with mTLS
 * termination (or extend this client with {@code NettyChannelBuilder} TLS)
 * before exposing it beyond the loopback interface.
 */
@Slf4j
public final class GrpcContentStore implements ContentStore {

    private static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(5);

    private final ContentServiceGrpc.ContentServiceBlockingStub stub;
    private final @Nullable ManagedChannel ownedChannel;
    private final Duration deadline;

    private GrpcContentStore(ContentServiceGrpc.ContentServiceBlockingStub stub,
            @Nullable ManagedChannel ownedChannel, Duration deadline) {
        this.stub = Objects.requireNonNull(stub, "stub");
        this.ownedChannel = ownedChannel;
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    /**
     * Creates a client over the given channel. The caller owns the channel
     * lifecycle; {@link #close()} is a no-op for channel-backed instances.
     */
    public static GrpcContentStore over(Channel channel) {
        return over(channel, DEFAULT_DEADLINE);
    }

    /**
     * Creates a client over the given channel with an explicit RPC deadline.
     */
    public static GrpcContentStore over(Channel channel, Duration deadline) {
        return new GrpcContentStore(
                ContentServiceGrpc.newBlockingStub(channel), null, deadline);
    }

    /**
     * Creates a plaintext client to {@code host:port} and owns its channel;
     * call {@link #close()} to release it.
     */
    public static GrpcContentStore plaintext(String host, int port) {
        return plaintext(host, port, DEFAULT_DEADLINE);
    }

    /**
     * Creates a plaintext client with an explicit RPC deadline.
     */
    public static GrpcContentStore plaintext(String host, int port, Duration deadline) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        return new GrpcContentStore(
                ContentServiceGrpc.newBlockingStub(channel), channel, deadline);
    }

    /** Releases the owned channel, if any; no-op for channel-backed instances. */
    public void close() {
        if (ownedChannel != null && !ownedChannel.isShutdown()) {
            ownedChannel.shutdown();
        }
    }

    @Override
    public PutResult put(String tenantId, byte[] payload, ContentMetadata metadata) {
        requireTenant(tenantId);
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(metadata, "metadata");
        if (payload.length == 0) {
            throw new ContentStoreException("payload is empty");
        }
        PutRequest request = PutRequest.newBuilder()
                .setTenantId(tenantId)
                .setPayload(ByteString.copyFrom(payload))
                .setContentType(nullToEmpty(metadata.contentType()))
                .setOwnerId(metadata.ownerId())
                .setRetentionDays(metadata.retentionDays())
                .setSerializerVersion(metadata.serializerVersion())
                .build();
        try {
            var response = stubWithDeadline().put(request);
            log.debug("Put tenant={} artifact={} dedupHit={}",
                    tenantId, response.getArtifactId(), response.getDedupHit());
            return new PutResult(
                    new ContentReference(tenantId, response.getArtifactId(),
                            response.getDigest(), 1, metadata.serializerVersion()),
                    response.getDedupHit());
        } catch (StatusRuntimeException ex) {
            throw translate("put", ex);
        }
    }

    @Override
    public byte[] get(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        GetRequest request = GetRequest.newBuilder()
                .setTenantId(reference.tenantId())
                .setArtifactId(reference.artifactId())
                .build();
        try {
            var response = stubWithDeadline().get(request);
            byte[] bytes = response.getPayload().toByteArray();
            if (!Digests.sha256(bytes).equals(response.getDigest())
                    || !response.getDigest().equals(reference.digest())) {
                throw new ContentIntegrityException(
                        "digest mismatch for artifact " + reference.artifactId());
            }
            return Arrays.copyOf(bytes, bytes.length);
        } catch (StatusRuntimeException ex) {
            throw translate("get", ex);
        }
    }

    @Override
    public boolean exists(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        HeadRequest request = HeadRequest.newBuilder()
                .setTenantId(reference.tenantId())
                .setArtifactId(reference.artifactId())
                .build();
        try {
            return stubWithDeadline().head(request).getExists();
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return false;
            }
            throw translate("exists", ex);
        }
    }

    private ContentServiceGrpc.ContentServiceBlockingStub stubWithDeadline() {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static ContentStoreException translate(String operation, StatusRuntimeException ex) {
        Status.Code code = ex.getStatus().getCode();
        if (code == Status.Code.NOT_FOUND) {
            return new ContentNotFoundException("artifact not found");
        }
        if (code == Status.Code.DEADLINE_EXCEEDED || code == Status.Code.UNAVAILABLE) {
            return new ContentStoreException(
                    "Content Service " + operation + " unavailable: " + ex.getStatus(), ex);
        }
        return new ContentStoreException(
                "Content Service " + operation + " failed: " + ex.getStatus(), ex);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ContentStoreException("tenant_id is required");
        }
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
