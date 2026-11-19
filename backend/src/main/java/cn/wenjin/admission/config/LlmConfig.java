package cn.wenjin.admission.config;

import cn.wenjin.admission.ai.AiClientConfig;
import cn.wenjin.admission.ai.RuntimeLlmGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {
    @Bean
    RuntimeLlmGateway llmGateway(AppProperties properties) {
        AppProperties.Ai ai = properties.ai();
        return new RuntimeLlmGateway(new AiClientConfig(ai.mode(), ai.provider(), ai.baseUrl(), ai.apiKey(),
                ai.chatModel(), ai.fastModel(), ai.embeddingModel(), ai.timeoutSeconds(), ai.maxRetries(),
                ai.maxOutputTokens()));
    }
}
