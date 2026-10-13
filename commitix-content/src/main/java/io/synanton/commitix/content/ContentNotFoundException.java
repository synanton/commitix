package io.synanton.commitix.content;

/**
 * Indistinguishable not-found: the artifact is missing <em>or</em> belongs to another
 * tenant. Callers must not branch on the cause.
 */
public class ContentNotFoundException extends ContentStoreException {

    private static final long serialVersionUID = 1L;

    public ContentNotFoundException(String message) {
        super(message);
    }
}
