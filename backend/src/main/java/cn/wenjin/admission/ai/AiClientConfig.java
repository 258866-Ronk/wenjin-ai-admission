package cn.wenjin.admission.ai;

public record AiClientConfig(String mode, String provider, String baseUrl, String apiKey,
                             String chatModel, String fastModel, String embeddingModel,
                             int timeoutSeconds, int maxRetries, int maxOutputTokens) {
    public boolean mock() {
        return "mock".equalsIgnoreCase(mode) || apiKey == null || apiKey.isBlank();
    }
}
