package io.synanton.commitix.core.domain.model;

/**
 * Identifies the business operation to execute, including a version for forward-compatibility.
 * Versioning matters because an intent created today may execute months later after the
 * application has changed.
 */
public record Operation(String id, String version, String name) {

    public Operation {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("operation id must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("operation version must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("operation name must not be blank");
        }
    }

    /** Creates an Operation where name defaults to the id. */
    public static Operation of(String id, String version) {
        return new Operation(id, version, id);
    }
}
