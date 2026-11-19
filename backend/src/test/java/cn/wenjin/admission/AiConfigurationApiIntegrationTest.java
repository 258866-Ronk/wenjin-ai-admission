package cn.wenjin.admission;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-configuration-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.data.redis.timeout=100ms",
        "app.ai.config-key-path=target/test-ai-config.key"
})
class AiConfigurationApiIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void adminCanSaveEncryptedRuntimeConfigurationAndTestIt() throws Exception {
        String userCookie = login("demo@wenjin.local", "Demo123!");
        HttpResponse<String> forbidden = request("/api/v1/admin/ai-config", "GET", null,
                Map.of("Cookie", userCookie));
        assertThat(forbidden.statusCode()).isIn(401, 403);

        String adminCookie = login("admin@wenjin.local", "Admin123!");
        JsonNode initial = json.readTree(success("/api/v1/admin/ai-config", "GET", null, adminCookie));
        assertThat(initial.path("mode").asText()).isEqualTo("mock");
        assertThat(initial.has("apiKey")).isFalse();

        Map<String, Object> update = Map.ofEntries(
                Map.entry("mode", "mock"), Map.entry("provider", "openai-compatible"),
                Map.entry("baseUrl", "https://api.openai.com/v1"), Map.entry("apiKey", "sk-test-secret-1234"),
                Map.entry("clearApiKey", false), Map.entry("chatModel", "test-model"),
                Map.entry("fastModel", "test-fast"), Map.entry("embeddingModel", "test-embedding"),
                Map.entry("timeoutSeconds", 12), Map.entry("maxRetries", 1), Map.entry("maxOutputTokens", 1024));
        String savedBody = success("/api/v1/admin/ai-config", "PUT", json.writeValueAsString(update), adminCookie);
        JsonNode saved = json.readTree(savedBody);
        assertThat(saved.path("source").asText()).isEqualTo("FRONTEND");
        assertThat(saved.path("apiKeyConfigured").asBoolean()).isTrue();
        assertThat(saved.path("apiKeyMasked").asText()).endsWith("1234");
        assertThat(savedBody).doesNotContain("sk-test-secret-1234");

        String ciphertext = jdbc.sql("select api_key_ciphertext from ai_runtime_config where id='active'")
                .query(String.class).single();
        assertThat(ciphertext).isNotBlank().doesNotContain("sk-test-secret-1234");

        JsonNode tested = json.readTree(success("/api/v1/admin/ai-config/test", "POST", "{}", adminCookie));
        assertThat(tested.path("success").asBoolean()).isTrue();
        assertThat(tested.path("mode").asText()).isEqualTo("mock");
        assertThat(tested.path("message").asText()).contains("Mock");
    }

    private String login(String email, String password) throws Exception {
        HttpResponse<String> response = request("/api/v1/auth/login", "POST",
                json.writeValueAsString(Map.of("email", email, "password", password)), Map.of());
        assertThat(response.statusCode()).isBetween(200, 299);
        String cookie = response.headers().firstValue("set-cookie").orElseThrow();
        return cookie.substring(0, cookie.indexOf(';'));
    }

    private String success(String path, String method, String body, String cookie) throws Exception {
        HttpResponse<String> response = request(path, method, body, Map.of("Cookie", cookie));
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return response.body();
    }

    private HttpResponse<String> request(String path, String method, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
