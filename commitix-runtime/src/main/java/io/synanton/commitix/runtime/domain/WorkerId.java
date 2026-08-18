package io.synanton.commitix.runtime.domain;

import java.util.UUID;

/** Generates a unique worker identifier for this process instance. */
public final class WorkerId {

    private WorkerId() {
    }

    /**
     * Returns a stable worker id for the lifetime of this JVM process.
     * Format: {@code <prefix>-<randomSuffix>}, where suffix is the first 8 chars of a UUID.
     */
    public static String generate(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return prefix + "-" + suffix;
    }
}
