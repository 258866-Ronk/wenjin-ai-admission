package cn.wenjin.admission.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Cors cors, Auth auth, Ai ai, Workflow workflow, Importer importer) {
    public record Cors(List<String> allowedOrigins) {
    }

    public record Auth(int sessionHours, boolean secureCookie) {
    }

    public record Ai(String mode, String provider, String baseUrl, String apiKey, String chatModel,
                     String fastModel, String embeddingModel, int timeoutSeconds, int maxRetries,
                     int maxOutputTokens, String configEncryptionKey, String configKeyPath) {
    }

    public record Workflow(int executorThreads, int eventRetention, boolean redisQueueEnabled) {
    }

    public record Importer(String demoPath, String demoSupplementPath, boolean seedEnabled) {
    }
}
