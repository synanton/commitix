package io.synanton.commitix.content;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Decides per payload whether it stays inline or is externalized to the Content
 * Service (integration proposal §4.5).
 *
 * <p>Resolution has two phases. Phase 1 is a veto chain over three flags, evaluated
 * in order: kill switch, per-tenant, per-intent-type. Any {@code disabled} flag
 * terminates with {@link Decision#INLINE}; {@code enabled} and {@code unset} fall
 * through. Phase 2 is the size trigger: payloads of at least {@code thresholdBytes}
 * are externalized, smaller payloads stay inline. Only the threshold can trigger
 * externalization; the flags can only veto it.
 */
public final class ExternalizationPolicy {

    /** Outcome of a policy decision. */
    public enum Decision {
        INLINE,
        EXTERNAL
    }

    private final boolean killSwitch;
    private final Set<String> disabledTenants;
    private final Set<String> disabledIntentTypes;
    private final long thresholdBytes;

    private ExternalizationPolicy(Builder builder) {
        this.killSwitch = builder.killSwitch;
        this.disabledTenants = Collections.unmodifiableSet(builder.disabledTenants);
        this.disabledIntentTypes = Collections.unmodifiableSet(builder.disabledIntentTypes);
        this.thresholdBytes = builder.thresholdBytes;
    }

    /**
     * Resolves the storage decision for one payload.
     *
     * @param tenantId   ambient tenant scope, must not be blank
     * @param intentType intent-type key (see {@link ExternalizingPayloadSerializer}), may be null
     * @param sizeBytes  serialized payload size, must be non-negative
     */
    public Decision decide(String tenantId, String intentType, long sizeBytes) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be non-negative");
        }
        if (killSwitch) {
            return Decision.INLINE;
        }
        if (disabledTenants.contains(tenantId)) {
            return Decision.INLINE;
        }
        if (intentType != null && disabledIntentTypes.contains(intentType)) {
            return Decision.INLINE;
        }
        return sizeBytes >= thresholdBytes ? Decision.EXTERNAL : Decision.INLINE;
    }

    /** Starts building a policy with externalization disabled for every payload. */
    public static Builder disabledByDefault(long thresholdBytes) {
        return new Builder().thresholdBytes(thresholdBytes);
    }

    /** Policy that keeps every payload inline (kill switch engaged). */
    public static ExternalizationPolicy inlineOnly() {
        return new Builder().thresholdBytes(Long.MAX_VALUE).killSwitch(true).build();
    }

    /** Builder for {@link ExternalizationPolicy}. */
    public static final class Builder {
        private boolean killSwitch;
        private Set<String> disabledTenants = Set.of();
        private Set<String> disabledIntentTypes = Set.of();
        private long thresholdBytes = 65536L;

        private Builder() {
        }

        /** Global emergency disable; when engaged every payload stays inline. */
        public Builder killSwitch(boolean killSwitch) {
            this.killSwitch = killSwitch;
            return this;
        }

        /** Tenants vetoed from externalization. */
        public Builder disabledTenants(Set<String> disabledTenants) {
            this.disabledTenants = Set.copyOf(Objects.requireNonNull(disabledTenants));
            return this;
        }

        /** Intent-type keys vetoed from externalization. */
        public Builder disabledIntentTypes(Set<String> disabledIntentTypes) {
            this.disabledIntentTypes = Set.copyOf(Objects.requireNonNull(disabledIntentTypes));
            return this;
        }

        /** Payloads of at least this size are externalized (default 64 KiB). */
        public Builder thresholdBytes(long thresholdBytes) {
            if (thresholdBytes <= 0) {
                throw new IllegalArgumentException("thresholdBytes must be positive");
            }
            this.thresholdBytes = thresholdBytes;
            return this;
        }

        /** Builds the policy. */
        public ExternalizationPolicy build() {
            return new ExternalizationPolicy(this);
        }
    }
}
