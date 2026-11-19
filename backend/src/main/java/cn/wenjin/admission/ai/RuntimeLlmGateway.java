package cn.wenjin.admission.ai;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

public class RuntimeLlmGateway implements LlmGateway {
    private final AtomicReference<LlmGateway> delegate = new AtomicReference<>();
    private final AtomicReference<AiClientConfig> current = new AtomicReference<>();

    public RuntimeLlmGateway(AiClientConfig initial) {
        configure(initial);
    }

    public synchronized void configure(AiClientConfig requested) {
        AiClientConfig effective = requested.mock()
                ? new AiClientConfig("mock", requested.provider(), requested.baseUrl(), requested.apiKey(),
                requested.chatModel(), requested.fastModel(), requested.embeddingModel(), requested.timeoutSeconds(),
                requested.maxRetries(), requested.maxOutputTokens())
                : requested;
        LlmGateway next;
        if (effective.mock()) {
            next = new MockLlmGateway();
        } else {
            Duration timeout = Duration.ofSeconds(Math.max(1, effective.timeoutSeconds()));
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(timeout);
            requestFactory.setReadTimeout(timeout);
            next = new SpringAiLlmGateway(effective, RestClient.builder().requestFactory(requestFactory));
        }
        current.set(effective);
        delegate.set(next);
    }

    public AiClientConfig current() {
        return current.get();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        return delegate.get().chat(request);
    }

    @Override
    public Flux<LlmStreamEvent> stream(LlmRequest request) {
        return delegate.get().stream(request);
    }

    @Override
    public <T> T structuredOutput(LlmRequest request, Class<T> outputType) {
        return delegate.get().structuredOutput(request, outputType);
    }
}
