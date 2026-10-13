package io.synanton.commitix.content;

/**
 * Commitix port for optional external payload storage (integration proposal §4.1).
 *
 * <p>Implementations talk to the Content Service; {@code commitix-core} never sees
 * this interface's implementations. References are opaque handles: adapters treat
 * {@link ContentReference} as a tenant-scoped handle and never parse storage
 * internals out of it.
 */
public interface ContentStore {

    /**
     * Publishes an immutable payload. Idempotent by {@code (tenantId, digest)}:
     * republishing identical bytes for the same tenant returns the existing
     * reference with {@code dedupHit}.
     */
    PutResult put(String tenantId, byte[] payload, ContentMetadata metadata);

    /** Retrieves payload bytes after integrity verification. */
    byte[] get(ContentReference reference);

    /** Existence check without fetching bytes. */
    boolean exists(ContentReference reference);
}
