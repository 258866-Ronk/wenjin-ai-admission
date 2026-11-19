package cn.wenjin.admission.ai;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface LlmGateway {
    LlmResponse chat(LlmRequest request);

    Flux<LlmStreamEvent> stream(LlmRequest request);

    <T> T structuredOutput(LlmRequest request, Class<T> outputType);

    record LlmRequest(String systemPrompt, List<Message> messages, String model,
                      double temperature, Integer maxOutputTokens, Map<String, Object> metadata) {
        public LlmRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public static LlmRequest single(String systemPrompt, String userMessage) {
            return new LlmRequest(systemPrompt, List.of(new Message("user", userMessage)), null,
                    0.1, null, Map.of());
        }
    }

    record Message(String role, String content) {
    }

    record LlmResponse(String content, String model, int inputTokens, int outputTokens,
                       long latencyMillis, boolean degraded) {
    }

    record LlmStreamEvent(String type, String content, boolean done) {
    }
}

