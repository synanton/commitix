package io.synanton.commitix.content;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * {@link ContentStore} over the Content Service HTTP API (proposal §4.1, §3.3).
 *
 * <p>Uses only {@code java.net.http} — no Spring, Jackson, S3, or gRPC
 * dependencies. Timeouts follow the proposal: 2s connect, 5s per request.
 * Retries, if any, are bounded by the caller's deadline; this client never
 * extends a deadline on retry. Reads verify the SHA-256 digest and fail
 * closed on mismatch.
 */
@Slf4j
public final class HttpContentStore implements ContentStore {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final @Nullable String apiKey;
    private final Duration requestTimeout;

    private HttpContentStore(Builder builder) {
        this.baseUrl = Objects.requireNonNull(builder.baseUrl, "baseUrl");
        this.apiKey = builder.apiKey;
        this.requestTimeout = Objects.requireNonNull(builder.requestTimeout, "requestTimeout");
        this.httpClient = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder()
                        .connectTimeout(Objects.requireNonNull(builder.connectTimeout, "connectTimeout"))
                        .build();
    }

    /** Creates a client with the proposal timeouts (2s connect, 5s request). */
    public static HttpContentStore create(String baseUrl) {
        return new Builder().baseUrl(baseUrl).build();
    }

    /** Starts building a client with custom timeouts or an injected {@link HttpClient}. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public PutResult put(String tenantId, byte[] payload, ContentMetadata metadata) {
        requireTenant(tenantId);
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(metadata, "metadata");
        if (payload.length == 0) {
            throw new ContentStoreException("payload is empty");
        }
        String body = "{"
                + field("payload", base64(payload)) + ","
                + field("content_type", metadata.contentType()) + ","
                + field("owner_id", metadata.ownerId()) + ","
                + "\"retention_days\":" + metadata.retentionDays() + ","
                + "\"serializer_version\":" + metadata.serializerVersion()
                + "}";
        HttpRequest request = baseRequest("/api/v1/artifacts", tenantId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, "put");
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new ContentStoreException("put failed with status " + response.statusCode());
        }
        Map<String, String> json = parseObject(response.body());
        String reference = required(json, "reference", response);
        String artifactId = required(json, "artifact_id", response);
        String digest = required(json, "digest", response);
        boolean dedupHit = Boolean.parseBoolean(json.getOrDefault("dedup_hit", "false"));
        log.debug("Put tenant={} artifact={} dedupHit={}", tenantId, artifactId, dedupHit);
        return new PutResult(
                new ContentReference(
                        tenantId, artifactId, digest, 1, metadata.serializerVersion()),
                dedupHit);
    }

    @Override
    public byte[] get(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        String path = "/api/v1/artifacts/" + urlEncode(reference.artifactId())
                + "?tenant_id=" + urlEncode(reference.tenantId());
        HttpRequest request = baseRequest(path, reference.tenantId()).GET().build();
        HttpResponse<String> response = send(request, "get");
        if (response.statusCode() == 404) {
            throw new ContentNotFoundException("artifact not found");
        }
        if (response.statusCode() != 200) {
            throw new ContentStoreException("get failed with status " + response.statusCode());
        }
        Map<String, String> json = parseObject(response.body());
        String digest = required(json, "digest", response);
        String payload = required(json, "payload", response);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new ContentIntegrityException("response payload is not valid base64");
        }
        if (!sha256(bytes).equals(digest) || !digest.equals(reference.digest())) {
            throw new ContentIntegrityException(
                    "digest mismatch for artifact " + reference.artifactId());
        }
        return bytes;
    }

    @Override
    public boolean exists(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        String path = "/api/v1/artifacts/" + urlEncode(reference.artifactId())
                + "?tenant_id=" + urlEncode(reference.tenantId());
        HttpRequest request = baseRequest(path, reference.tenantId())
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = send(request, "exists");
        if (response.statusCode() == 404) {
            return false;
        }
        if (response.statusCode() != 200) {
            throw new ContentStoreException("exists failed with status " + response.statusCode());
        }
        return true;
    }

    private HttpRequest.Builder baseRequest(String path, String tenantId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("X-Tenant-ID", tenantId);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-API-Key", apiKey);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request, String operation) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ContentStoreException("Content Service " + operation + " failed", ex);
        }
    }

    private static String required(Map<String, String> json, String field, HttpResponse<String> response) {
        String value = json.get(field);
        if (value == null) {
            throw new ContentStoreException(
                    "Content Service response is missing field '" + field + "': " + response.body());
        }
        return value;
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ContentStoreException("tenant_id is required");
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String field(String name, @Nullable String value) {
        return "\"" + name + "\":" + (value == null ? "null" : "\"" + escape(value) + "\"");
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    static String sha256(byte[] payload) {
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

    /**
     * Parses a flat JSON object of string/boolean/number fields. The Content
     * Service responses consumed here are flat by contract; nested values are
     * rejected rather than silently misread.
     */
    static Map<String, String> parseObject(String raw) {
        String json = raw.trim();
        if (json.length() < 2 || json.charAt(0) != '{' || json.charAt(json.length() - 1) != '}') {
            throw new ContentStoreException("Content Service response is not a JSON object");
        }
        Map<String, String> out = new HashMap<>();
        int i = 1;
        int end = json.length() - 1;
        while (i < end) {
            i = skipWs(json, i, end);
            if (i >= end) {
                break;
            }
            if (json.charAt(i) != '"') {
                throw new ContentStoreException("Content Service response has a non-string key");
            }
            int[] next = new int[1];
            String key = parseString(json, i, end, next);
            i = skipWs(json, next[0], end);
            expect(json, i, end, ':');
            i = skipWs(json, i + 1, end);
            String value;
            if (i < end && json.charAt(i) == '"') {
                value = parseString(json, i, end, next);
                i = next[0];
            } else {
                int start = i;
                while (i < end && json.charAt(i) != ',' && json.charAt(i) != '}') {
                    char c = json.charAt(i);
                    if (c == '{' || c == '[') {
                        throw new ContentStoreException(
                                "Content Service response has an unexpected nested value");
                    }
                    i++;
                }
                value = json.substring(start, i).trim();
            }
            out.put(key, value);
            i = skipWs(json, i, end);
            if (i < end && json.charAt(i) == ',') {
                i++;
            }
        }
        return out;
    }

    private static int skipWs(String json, int i, int end) {
        while (i < end && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void expect(String json, int i, int end, char c) {
        if (i >= end || json.charAt(i) != c) {
            throw new ContentStoreException("Content Service response is malformed");
        }
    }

    private static String parseString(String json, int i, int end, int[] next) {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < end) {
            char c = json.charAt(i);
            if (c == '"') {
                next[0] = i + 1;
                return sb.toString();
            }
            if (c == '\\') {
                i++;
                if (i >= end) {
                    break;
                }
                char e = json.charAt(i);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 >= end) {
                            throw new ContentStoreException(
                                    "Content Service response has a bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                        break;
                    default:
                        throw new ContentStoreException(
                                "Content Service response has a bad escape");
                }
            } else {
                sb.append(c);
            }
            i++;
        }
        throw new ContentStoreException("Content Service response has an unterminated string");
    }

    /** Builder for {@link HttpContentStore}. */
    public static final class Builder {
        private String baseUrl;
        private @Nullable String apiKey;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private @Nullable HttpClient httpClient;

        private Builder() {
        }

        /** Base URL of the Content Service, e.g. {@code http://localhost:8080}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /** Optional API key forwarded as {@code X-API-Key} (reserved for gateway auth). */
        public Builder apiKey(@Nullable String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Connect timeout (default 2s per proposal §3.3). */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            return this;
        }

        /** Per-request timeout (default 5s per proposal §3.3). */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            return this;
        }

        /** Injected client (tests); otherwise built from the connect timeout. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /** Builds the client. */
        public HttpContentStore build() {
            return new HttpContentStore(this);
        }
    }
}
