package io.synanton.commitix.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.synanton.commitix.content.TestPayloads.PassThroughSerializer;
import io.synanton.commitix.content.TestPayloads.TestPayload;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExternalizingPayloadSerializerTest {

    private FakeContentStore contentStore;
    private ExternalizingPayloadSerializer serializer;

    static final class FakeContentStore implements ContentStore {
        final Map<String, byte[]> objects = new HashMap<>();
        int puts;

        @Override
        public PutResult put(String tenantId, byte[] payload, ContentMetadata metadata) {
            puts++;
            String digest = HttpContentStore.sha256(payload);
            String artifactId = UUID.randomUUID().toString();
            objects.put(tenantId + "\0" + artifactId, Arrays.copyOf(payload, payload.length));
            return new PutResult(
                    new ContentReference(tenantId, artifactId, digest, 1, metadata.serializerVersion()),
                    false);
        }

        @Override
        public byte[] get(ContentReference reference) {
            byte[] bytes = objects.get(reference.tenantId() + "\0" + reference.artifactId());
            if (bytes == null) {
                throw new ContentNotFoundException("artifact not found");
            }
            return Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public boolean exists(ContentReference reference) {
            return objects.containsKey(reference.tenantId() + "\0" + reference.artifactId());
        }
    }

    @BeforeEach
    void setUp() {
        contentStore = new FakeContentStore();
        ExternalizationPolicy policy =
                ExternalizationPolicy.disabledByDefault(16).build();
        serializer = new ExternalizingPayloadSerializer(
                new PassThroughSerializer(), contentStore, policy, () -> "acme", null, 3);
    }

    @Test
    void belowThresholdPassesThroughInline() {
        byte[] stored = serializer.serialize(new TestPayload("text/plain", "tiny".getBytes(StandardCharsets.UTF_8)));
        assertThat(stored).isEqualTo("tiny".getBytes(StandardCharsets.UTF_8));
        assertThat(contentStore.puts).isZero();
        TestPayload back = (TestPayload) serializer.deserialize(stored, "text/plain");
        assertThat(back.data()).isEqualTo("tiny".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aboveThresholdExternalizesAndResolves() {
        byte[] large = "this payload is definitely larger than sixteen bytes".getBytes(StandardCharsets.UTF_8);
        byte[] stored = serializer.serialize(new TestPayload("application/octet-stream", large));
        assertThat(stored).isNotEqualTo(large);
        assertThat(contentStore.puts).isEqualTo(1);
        TestPayload back = (TestPayload) serializer.deserialize(stored, "application/octet-stream");
        assertThat(back.data()).isEqualTo(large);
    }

    @Test
    void legacyInlineRowsStillDeserialize() {
        byte[] legacy = "written before externalization existed".getBytes(StandardCharsets.UTF_8);
        TestPayload back = (TestPayload) serializer.deserialize(legacy, "text/plain");
        assertThat(back.data()).isEqualTo(legacy);
    }

    @Test
    void remoteFailureOnSerializePropagatesAndPersistsNothing() {
        ContentStore failing = new ContentStore() {
            @Override
            public PutResult put(String tenantId, byte[] payload, ContentMetadata metadata) {
                throw new ContentStoreException("Content Service down");
            }

            @Override
            public byte[] get(ContentReference reference) {
                throw new ContentNotFoundException("unreachable");
            }

            @Override
            public boolean exists(ContentReference reference) {
                return false;
            }
        };
        ExternalizingPayloadSerializer strict = new ExternalizingPayloadSerializer(
                new PassThroughSerializer(),
                failing,
                ExternalizationPolicy.disabledByDefault(1).build(),
                () -> "acme",
                null,
                1);
        byte[] large = "large enough to externalize".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> strict.serialize(new TestPayload("text/plain", large)))
                .isInstanceOf(ContentStoreException.class)
                .hasMessageContaining("Content Service down");
    }

    @Test
    void missingRemoteObjectOnDeserializeFailsClosed() {
        byte[] large = "large enough to externalize".getBytes(StandardCharsets.UTF_8);
        byte[] stored = serializer.serialize(new TestPayload("text/plain", large));
        contentStore.objects.clear();
        assertThatThrownBy(() -> serializer.deserialize(stored, "text/plain"))
                .isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    void malformedEnvelopeFailsClosed() {
        byte[] corrupt = {'C', 'X', 'E', 'X', 0, 0, 0, 5, 'x'};
        assertThatThrownBy(() -> serializer.deserialize(corrupt, "text/plain"))
                .isInstanceOf(ContentStoreException.class);
    }

    @Test
    void killSwitchForcesInline() {
        ExternalizingPayloadSerializer inlineOnly = new ExternalizingPayloadSerializer(
                new PassThroughSerializer(),
                contentStore,
                ExternalizationPolicy.inlineOnly(),
                () -> "acme",
                null,
                1);
        byte[] large = "large enough to externalize under normal policy".getBytes(StandardCharsets.UTF_8);
        assertThat(inlineOnly.serialize(new TestPayload("text/plain", large))).isEqualTo(large);
        assertThat(contentStore.puts).isZero();
    }

    @Test
    void customIntentTypeMapperIsHonored() {
        ExternalizingPayloadSerializer mapped = new ExternalizingPayloadSerializer(
                new PassThroughSerializer(),
                contentStore,
                ExternalizationPolicy.disabledByDefault(1).build(),
                () -> "acme",
                payload -> "CustomOperation",
                1);
        byte[] large = "large enough to externalize".getBytes(StandardCharsets.UTF_8);
        byte[] stored = mapped.serialize(new TestPayload("text/plain", large));
        assertThat(contentStore.puts).isEqualTo(1);
        TestPayload back = (TestPayload) mapped.deserialize(stored, "text/plain");
        assertThat(back.data()).isEqualTo(large);
    }
}
