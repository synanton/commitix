package io.synanton.commitix.content;

import io.synanton.commitix.core.domain.model.Payload;
import io.synanton.commitix.core.port.PayloadSerializer;
import java.util.Arrays;
import java.util.Objects;

/** Test doubles: an inline payload type plus a pass-through serializer. */
final class TestPayloads {

    private TestPayloads() {
    }

    record TestPayload(String contentType, byte[] data) implements Payload {
        TestPayload {
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(data, "data");
        }
    }

    static final class PassThroughSerializer implements PayloadSerializer {
        @Override
        public byte[] serialize(Payload payload) {
            return Arrays.copyOf(((TestPayload) payload).data(), ((TestPayload) payload).data().length);
        }

        @Override
        public Payload deserialize(byte[] bytes, String contentType) {
            return new TestPayload(contentType, Arrays.copyOf(bytes, bytes.length));
        }
    }
}
