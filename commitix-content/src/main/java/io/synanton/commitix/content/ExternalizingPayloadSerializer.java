package io.synanton.commitix.content;

import io.synanton.commitix.core.domain.model.Payload;
import io.synanton.commitix.core.port.PayloadSerializer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * {@link PayloadSerializer} decorator that externalizes large payloads to the
 * Content Service (integration proposal §4.3).
 *
 * <p>On {@link #serialize}, the delegate produces the canonical bytes first; the
 * {@link ExternalizationPolicy} then decides inline vs external. External payloads
 * are published via {@link ContentStore#put} <em>before</em> the intent row is
 * persisted, and a compact reference envelope is stored in {@code payload_value}.
 * This is a write-before-reference protocol, not a distributed transaction: if the
 * ambient transaction rolls back, the remote object becomes an orphan reconciled
 * after the grace period. No schema migration is required; envelope bytes flow
 * through the existing {@code payload_value BYTEA NOT NULL} column.
 *
 * <p>On {@link #deserialize}, envelope bytes are resolved through
 * {@link ContentStore#get} and verified; anything else passes straight through
 * to the delegate, so pre-existing inline rows keep working unchanged.
 *
 * <p>Tenant and intent type are ambient: the {@code Payload} SPI carries neither,
 * so both arrive via constructor-injected suppliers. The default intent-type key
 * is the payload class simple name; override it when operations need explicit keys.
 */
@Slf4j
public final class ExternalizingPayloadSerializer implements PayloadSerializer {

    private static final byte[] MAGIC = {'C', 'X', 'E', 'X'};

    private final PayloadSerializer delegate;
    private final ContentStore contentStore;
    private final ExternalizationPolicy policy;
    private final Supplier<String> tenantSupplier;
    private final Function<Payload, String> intentTypeMapper;
    private final int serializerVersion;

    /**
     * Creates the decorator.
     *
     * @param delegate          canonical serializer for inline bytes, must not be null
     * @param contentStore      Content Service client, must not be null
     * @param policy            externalization policy, must not be null
     * @param tenantSupplier    ambient tenant scope, must not be null
     * @param intentTypeMapper  maps a payload to its intent-type key, may be null
     *                          (defaults to the payload class simple name)
     * @param serializerVersion version stamp recorded in references, must be positive
     */
    public ExternalizingPayloadSerializer(
            PayloadSerializer delegate,
            ContentStore contentStore,
            ExternalizationPolicy policy,
            Supplier<String> tenantSupplier,
            @Nullable Function<Payload, String> intentTypeMapper,
            int serializerVersion) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.tenantSupplier = Objects.requireNonNull(tenantSupplier, "tenantSupplier");
        this.intentTypeMapper = intentTypeMapper != null
                ? intentTypeMapper
                : payload -> payload.getClass().getSimpleName();
        if (serializerVersion <= 0) {
            throw new IllegalArgumentException("serializerVersion must be positive");
        }
        this.serializerVersion = serializerVersion;
    }

    @Override
    public byte[] serialize(Payload payload) {
        Objects.requireNonNull(payload, "payload");
        byte[] inline = delegate.serialize(payload);
        String tenantId = tenantSupplier.get();
        String intentType = intentTypeMapper.apply(payload);
        ExternalizationPolicy.Decision decision = policy.decide(tenantId, intentType, inline.length);
        if (decision == ExternalizationPolicy.Decision.INLINE) {
            return inline;
        }
        PutResult result = contentStore.put(
                tenantId,
                inline,
                new ContentMetadata(payload.contentType(), intentType, 90, serializerVersion));
        log.debug("Externalized payload for tenant={} intentType={} artifact={} dedupHit={}",
                tenantId, intentType, result.reference().artifactId(), result.dedupHit());
        return envelop(result.reference().encode());
    }

    @Override
    public Payload deserialize(byte[] bytes, String contentType) {
        Objects.requireNonNull(bytes, "bytes");
        String reference = deenvelop(bytes);
        if (reference == null) {
            return delegate.deserialize(bytes, contentType);
        }
        ContentReference parsed = ContentReference.parse(reference);
        byte[] remote = contentStore.get(parsed);
        return delegate.deserialize(remote, contentType);
    }

    /**
     * Wraps an encoded reference in the envelope stored in {@code payload_value}:
     * magic bytes {@code CXEX}, a big-endian int length, then the UTF-8 reference.
     */
    static byte[] envelop(String encodedReference) {
        byte[] refBytes = encodedReference.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(MAGIC.length + Integer.BYTES + refBytes.length);
        buffer.put(MAGIC);
        buffer.putInt(refBytes.length);
        buffer.put(refBytes);
        return buffer.array();
    }

    /**
     * Unwraps envelope bytes to the encoded reference, or returns null when the
     * bytes are plain inline payload. Malformed envelopes fail closed.
     */
    static @Nullable String deenvelop(byte[] bytes) {
        if (bytes.length < MAGIC.length + Integer.BYTES) {
            return null;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                return null;
            }
        }
        int length = ByteBuffer.wrap(bytes, MAGIC.length, Integer.BYTES).getInt();
        if (length < 0 || bytes.length != MAGIC.length + Integer.BYTES + length) {
            throw new ContentStoreException("malformed content reference envelope");
        }
        return new String(bytes, MAGIC.length + Integer.BYTES, length, StandardCharsets.UTF_8);
    }
}
