package cn.wenjin.admission.ai;

import cn.wenjin.admission.common.Jsons;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class SpringAiLlmGateway implements LlmGateway {
    private final AiClientConfig properties;
    private final RestClient client;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public SpringAiLlmGateway(AiClientConfig properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder.baseUrl(trimSlash(properties.baseUrl()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.retry = Retry.of("llm", RetryConfig.custom()
                .maxAttempts(Math.max(1, properties.maxRetries() + 1))
                .intervalFunction(IntervalFunction.ofExponentialBackoff(250, 2.0))
                .build());
        this.circuitBreaker = CircuitBreaker.ofDefaults("llm");
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long started = System.nanoTime();
        Supplier<LlmResponse> call = () -> doChat(request, started);
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    private LlmResponse doChat(LlmRequest request, long started) {
        String model = requestedModel(request);
        List<Map<String, String>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        request.messages().forEach(message -> messages.add(Map.of("role", message.role(), "content", message.content())));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxOutputTokens() == null ? properties.maxOutputTokens() : request.maxOutputTokens());
        String raw = client.post().uri("/chat/completions").body(body).retrieve().body(String.class);
        JsonNode json = Jsons.mapper().readTree(raw);
        String content = json.path("choices").path(0).path("message").path("content").asText();
        int input = json.path("usage").path("prompt_tokens").asInt(0);
        int output = json.path("usage").path("completion_tokens").asInt(0);
        return new LlmResponse(content, json.path("model").asText(model), input, output,
                Duration.ofNanos(System.nanoTime() - started).toMillis(), false);
    }

    @Override
    public Flux<LlmStreamEvent> stream(LlmRequest request) {
        return Flux.defer(() -> {
            LlmResponse response = chat(request);
            List<LlmStreamEvent> events = new ArrayList<>();
            for (String part : response.content().split("(?<=。|！|？|\n)")) {
                if (!part.isEmpty()) events.add(new LlmStreamEvent("token", part, false));
            }
            events.add(new LlmStreamEvent("completed", "", true));
            return Flux.fromIterable(events);
        });
    }

    @Override
    public <T> T structuredOutput(LlmRequest request, Class<T> outputType) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(outputType);
        String schemaInstruction = "\n仅输出符合以下 JSON Schema 的 JSON，不要输出 Markdown：\n" + converter.getFormat();
        LlmRequest enriched = new LlmRequest((request.systemPrompt() == null ? "" : request.systemPrompt()) + schemaInstruction,
                request.messages(), request.model(), 0, request.maxOutputTokens(), request.metadata());
        return converter.convert(chat(enriched).content());
    }

    private String requestedModel(LlmRequest request) {
        String model = request.model();
        if (model == null || model.isBlank()) model = properties.chatModel();
        if (model == null || model.isBlank()) throw new IllegalStateException("AI_CHAT_MODEL is required in non-mock mode");
        return model;
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
