package io.synanton.commitix.content;

import java.util.Objects;

/**
 * Caller-supplied metadata for a {@link ContentStore#put} call.
 *
 * <p>Content Cache composes the {@link ContentReference} from the payload digest
 * plus these fields; adapters never compose references themselves.
 */
public record ContentMetadata(
        String contentType,
        String ownerId,
        int retentionDays,
        int serializerVersion) {

    public ContentMetadata {
        Objects.requireNonNull(ownerId, "ownerId");
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("retentionDays must be positive");
        }
    }

    /** Convenience factory with 90-day retention and serializer version 1. */
    public static ContentMetadata of(String contentType, String ownerId) {
        return new ContentMetadata(contentType, ownerId, 90, 1);
    }
}
