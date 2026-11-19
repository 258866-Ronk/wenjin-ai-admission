package cn.wenjin.admission;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.data.redis.timeout=100ms"
})
class WorkflowApiIntegrationTest {
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void demoScenarioCreatesTraceableReportAndReplayableEvents() throws Exception {
        HttpResponse<String> login = request("/api/v1/auth/login", "POST",
                json.writeValueAsString(Map.of("email", "demo@wenjin.local", "password", "Demo123!")), Map.of());
        assertThat(login.statusCode()).isBetween(200, 299);
        String session = login.headers().firstValue("set-cookie").orElseThrow();
        assertThat(session).contains("WENJIN_SESSION=").contains("HttpOnly");
        Map<String, String> headers = Map.of("Cookie", session.substring(0, session.indexOf(';')));

        String conversation = exchange("/api/v1/conversations", "POST",
                Map.of("title", "集成测试咨询"), headers);
        String conversationId = json.readTree(conversation).get("id").asText();
        Map<String, String> messageHeaders = Map.of("Cookie", headers.get("Cookie"), "Idempotency-Key", "integration-demo-scenario");
        String submitted = exchange("/api/v1/conversations/" + conversationId + "/messages", "POST",
                Map.of("content", "我是河南考生，2025年高考，物理方向，考了590分，位次28000，想学计算机，最好去武汉、杭州或南京，家庭一年学费预算25000元，不接受中外合作。"), messageHeaders);
        String runId = json.readTree(submitted).get("runId").asText();

        JsonNode run = waitForTerminal(runId, headers);
        assertThat(run.path("run").path("status").asText()).isIn("COMPLETED", "DEGRADED");
        String reportId = run.path("state").path("reportId").asText();
        JsonNode report = json.readTree(exchange("/api/v1/reports/" + reportId, "GET", null, headers));
        assertThat(report.path("datasetVersion").asText()).isEqualTo("demo-2025-v1");
        assertThat(report.path("balancedPlan").path("items").size()).isPositive();
        assertThat(report.path("evidence").size()).isPositive();
        assertThat(report.path("compliancePassed").asBoolean()).isTrue();

        Map<String, String> replayHeaders = Map.of("Cookie", headers.get("Cookie"), "Last-Event-ID", "0", "Accept", "text/event-stream");
        HttpResponse<String> replay = request("/api/v1/runs/" + runId + "/events", "GET", null, replayHeaders);
        assertThat(replay.body()).contains("event:run_started").contains("event:report_completed");
    }

    @Test
    void futureExamYearUsesLatestHistoricalAdmissionsWithoutReturningAnEmptyPlan() throws Exception {
        HttpResponse<String> login = request("/api/v1/auth/login", "POST",
                json.writeValueAsString(Map.of("email", "demo@wenjin.local", "password", "Demo123!")), Map.of());
        assertThat(login.statusCode()).isBetween(200, 299);
        String session = login.headers().firstValue("set-cookie").orElseThrow();
        Map<String, String> headers = Map.of("Cookie", session.substring(0, session.indexOf(';')));

        String conversation = exchange("/api/v1/conversations", "POST",
                Map.of("title", "广东历史年份回退测试"), headers);
        String conversationId = json.readTree(conversation).get("id").asText();
        Map<String, String> messageHeaders = Map.of("Cookie", headers.get("Cookie"),
                "Idempotency-Key", "integration-guangdong-2026-fallback");
        String submitted = exchange("/api/v1/conversations/" + conversationId + "/messages", "POST",
                Map.of("content", "我是广东考生，2026年高考，物理方向，考了680分，位次680，填报本科批，想学计算机和人工智能。"),
                messageHeaders);
        String runId = json.readTree(submitted).get("runId").asText();

        JsonNode run = waitForTerminal(runId, headers);
        assertThat(run.path("run").path("status").asText()).isIn("COMPLETED", "DEGRADED");
        JsonNode report = json.readTree(exchange("/api/v1/reports/" + run.path("state").path("reportId").asText(),
                "GET", null, headers));
        assertThat(report.path("dataCoverage").asText())
                .contains("参考数据年份 2025 年")
                .contains("2026 年暂无可用于院校推荐的录取记录")
                .contains("不能视为 2026 年录取线");
        assertThat(report.path("balancedPlan").path("items").size()).isPositive();
        assertThat(report.path("tierSummary").path("safe").asInt()).isPositive();
        for (JsonNode item : report.path("balancedPlan").path("items")) {
            assertThat(item.path("record").path("year").asInt()).isEqualTo(2025);
        }
    }

    @Test
    void fujianCurrentYearProfileReturnsHistoricalLawCandidates() throws Exception {
        HttpResponse<String> login = request("/api/v1/auth/login", "POST",
                json.writeValueAsString(Map.of("email", "demo@wenjin.local", "password", "Demo123!")), Map.of());
        assertThat(login.statusCode()).isBetween(200, 299);
        String session = login.headers().firstValue("set-cookie").orElseThrow();
        Map<String, String> headers = Map.of("Cookie", session.substring(0, session.indexOf(';')));

        String conversation = exchange("/api/v1/conversations", "POST",
                Map.of("title", "福建当前年份推荐测试"), headers);
        String conversationId = json.readTree(conversation).get("id").asText();
        Map<String, String> messageHeaders = Map.of("Cookie", headers.get("Cookie"),
                "Idempotency-Key", "integration-fujian-current-year-fallback");
        String submitted = exchange("/api/v1/conversations/" + conversationId + "/messages", "POST",
                Map.of("content", "我是福建历史类考生，今年考了550分，想学法学，填报本科批。"), messageHeaders);
        String runId = json.readTree(submitted).get("runId").asText();

        JsonNode run = waitForTerminal(runId, headers);
        assertThat(run.path("run").path("status").asText()).isIn("COMPLETED", "DEGRADED");
        JsonNode report = json.readTree(exchange("/api/v1/reports/" + run.path("state").path("reportId").asText(),
                "GET", null, headers));
        assertThat(report.path("profile").path("province").asText()).isEqualTo("福建");
        assertThat(report.path("profile").path("examYear").asInt()).isEqualTo(Year.now().getValue());
        assertThat(report.path("profile").path("rank").isNull()).isTrue();
        assertThat(report.path("dataCoverage").asText()).contains("参考数据年份 2025 年");
        assertThat(report.path("balancedPlan").path("items").size()).isEqualTo(3);
        boolean hasLaw = false;
        for (JsonNode item : report.path("balancedPlan").path("items")) {
            hasLaw |= item.path("record").path("majorName").asText().contains("法学");
        }
        assertThat(hasLaw).isTrue();
        assertThat(report.path("tierSummary").path("challenge").asInt()
                + report.path("tierSummary").path("stable").asInt()
                + report.path("tierSummary").path("safe").asInt()).isEqualTo(3);
    }

    private JsonNode waitForTerminal(String runId, Map<String, String> headers) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(12));
        JsonNode view = null;
        do {
            view = json.readTree(exchange("/api/v1/runs/" + runId, "GET", null, headers));
            if (List.of("COMPLETED", "DEGRADED", "FAILED").contains(view.path("run").path("status").asText())) return view;
            Thread.sleep(100);
        } while (Instant.now().isBefore(deadline));
        return view;
    }

    private String exchange(String path, String method, Object body, Map<String, String> headers) throws Exception {
        HttpResponse<String> response = request(path, method, body == null ? null : json.writeValueAsString(body), headers);
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
