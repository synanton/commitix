package io.synanton.commitix.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link HttpContentStore} against an in-test stub that
 * speaks the Content Service HTTP API (put/get/head, tenant scoping,
 * content-addressable dedup). No Docker, no extra dependencies.
 */
class HttpContentStoreTest {

    private HttpServer stub;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private HttpContentStore store;

    @BeforeEach
    void setUp() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/artifacts", this::handleArtifacts);
        stub.setExecutor(Executors.newSingleThreadExecutor());
        stub.start();
        store = HttpContentStore.create("http://127.0.0.1:" + stub.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
    }

    private void handleArtifacts(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String tenant = headerOrQuery(exchange, "X-Tenant-ID", "tenant_id");
            if (tenant == null || tenant.isBlank()) {
                respond(exchange, 400, "{\"code\":\"BAD_REQUEST\",\"message\":\"tenant_id is required\"}");
                return;
            }
            if (method.equals("POST") && path.equals("/api/v1/artifacts")) {
                Map<String, String> body = HttpContentStore.parseObject(readBody(exchange));
                byte[] payload = Base64.getDecoder().decode(body.get("payload"));
                String digest = HttpContentStore.sha256(payload);
                String artifactId = findByDigest(tenant, digest);
                boolean dedupHit = artifactId != null;
                if (!dedupHit) {
                    artifactId = UUID.randomUUID().toString();
                    objects.put(tenant + "\0" + artifactId, payload);
                }
                respond(exchange, 201, "{\"reference\":\"v1:tenant_id=" + tenant
                        + "&artifact_id=" + artifactId + "&digest=" + digest
                        + "&generation=1&serializer_version=1\","
                        + "\"artifact_id\":\"" + artifactId + "\","
                        + "\"digest\":\"" + digest + "\","
                        + "\"dedup_hit\":" + dedupHit + ","
                        + "\"storage_tier\":\"inline\"}");
                return;
            }
            if (!path.startsWith("/api/v1/artifacts/")) {
                respond(exchange, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"artifact not found\"}");
                return;
            }
            String artifactId = path.substring("/api/v1/artifacts/".length());
            byte[] payload = objects.get(tenant + "\0" + artifactId);
            if (payload == null) {
                respond(exchange, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"artifact not found\"}");
                return;
            }
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().add("X-Artifact-ID", artifactId);
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            if (method.equals("GET")) {
                respond(exchange, 200, "{\"payload\":\""
                        + Base64.getEncoder().encodeToString(payload) + "\","
                        + "\"digest\":\"" + HttpContentStore.sha256(payload) + "\","
                        + "\"content_type\":\"application/octet-stream\","
                        + "\"artifact_id\":\"" + artifactId + "\"}");
                return;
            }
            respond(exchange, 405, "{\"code\":\"BAD_REQUEST\",\"message\":\"method not allowed\"}");
        } finally {
            exchange.close();
        }
    }

    private String findByDigest(String tenant, String digest) {
        for (Map.Entry<String, byte[]> entry : objects.entrySet()) {
            if (entry.getKey().startsWith(tenant + "\0")
                    && HttpContentStore.sha256(entry.getValue()).equals(digest)) {
                return entry.getKey().substring(tenant.length() + 1);
            }
        }
        return null;
    }

    private static String headerOrQuery(HttpExchange exchange, String header, String param) {
        String value = exchange.getRequestHeaders().getFirst(header);
        if (value != null) {
            return value;
        }
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(param)) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void putGetRoundTrip() {
        PutResult res = store.put("acme", "hello".getBytes(StandardCharsets.UTF_8),
                ContentMetadata.of("text/plain", "owner-1"));
        assertThat(res.dedupHit()).isFalse();
        assertThat(store.get(res.reference())).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(store.exists(res.reference())).isTrue();
    }

    @Test
    void sameTenantDedupHits() {
        byte[] payload = "dedup me".getBytes(StandardCharsets.UTF_8);
        PutResult first = store.put("acme", payload, ContentMetadata.of("text/plain", "owner-1"));
        PutResult second = store.put("acme", payload, ContentMetadata.of("text/plain", "owner-1"));
        assertThat(second.dedupHit()).isTrue();
        assertThat(second.reference().artifactId()).isEqualTo(first.reference().artifactId());
    }

    @Test
    void sameBytesDifferentTenantsAreIsolated() {
        byte[] payload = "shared".getBytes(StandardCharsets.UTF_8);
        PutResult a = store.put("tenant-a", payload, ContentMetadata.of("text/plain", "owner-1"));
        PutResult b = store.put("tenant-b", payload, ContentMetadata.of("text/plain", "owner-1"));
        assertThat(b.reference().artifactId()).isNotEqualTo(a.reference().artifactId());
        assertThat(b.dedupHit()).isFalse();
    }

    @Test
    void crossTenantGetIsIndistinguishableNotFound() {
        PutResult res = store.put("tenant-a", "secret".getBytes(StandardCharsets.UTF_8),
                ContentMetadata.of("text/plain", "owner-1"));
        ContentReference foreign = new ContentReference(
                "tenant-b", res.reference().artifactId(), res.reference().digest(), 1, 1);
        assertThatThrownBy(() -> store.get(foreign)).isInstanceOf(ContentNotFoundException.class);
        assertThat(store.exists(foreign)).isFalse();
    }

    @Test
    void corruptRemoteBytesFailClosed() {
        PutResult res = store.put("acme", "original".getBytes(StandardCharsets.UTF_8),
                ContentMetadata.of("text/plain", "owner-1"));
        objects.put("acme\0" + res.reference().artifactId(),
                "tampered".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> store.get(res.reference()))
                .isInstanceOf(ContentIntegrityException.class);
    }

    @Test
    void rejectsEmptyPayloadAndMissingTenant() {
        assertThatThrownBy(() -> store.put("acme", new byte[0], ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
        assertThatThrownBy(
                () -> store.put("", "x".getBytes(StandardCharsets.UTF_8), ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
    }

    @Test
    void jsonParserHandlesEscapes() {
        Map<String, String> parsed = HttpContentStore.parseObject(
                "{\"a\":\"x\\\"y\\\\z\\n\\u0041\",\"b\":true,\"c\":12}");
        Map<String, String> expected = new HashMap<>();
        expected.put("a", "x\"y\\z\nA");
        expected.put("b", "true");
        expected.put("c", "12");
        assertThat(parsed).isEqualTo(expected);
    }

    @Test
    void jsonParserRejectsNestedValues() {
        assertThatThrownBy(() -> HttpContentStore.parseObject("{\"a\":{\"b\":1}}"))
                .isInstanceOf(ContentStoreException.class);
        assertThatThrownBy(() -> HttpContentStore.parseObject("not json"))
                .isInstanceOf(ContentStoreException.class);
    }
}
