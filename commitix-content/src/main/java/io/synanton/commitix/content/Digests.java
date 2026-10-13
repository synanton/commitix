package io.synanton.commitix.content;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 content digests in {@code sha256:<hex>} form, shared by the HTTP
 * and gRPC Content Service clients. Digests anchor integrity, never
 * authorization: every access is tenant-scoped regardless of the hash.
 */
public final class Digests {

    private Digests() {
    }

    /**
     * Computes the content digest of {@code payload}.
     *
     * @param payload bytes to hash, must not be null
     * @return digest in {@code sha256:<hex>} form, never null
     */
    public static String sha256(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(payload);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
