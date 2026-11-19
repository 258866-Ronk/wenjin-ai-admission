package cn.wenjin.admission.ai;

import cn.wenjin.admission.common.Ids;
import cn.wenjin.admission.common.Jsons;
import cn.wenjin.admission.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

@Service
public class AiConfigurationService {
    private static final Logger log = LoggerFactory.getLogger(AiConfigurationService.class);
    private static final String ACTIVE_ID = "active";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final AppProperties properties;
    private final RuntimeLlmGateway gateway;
    private volatile byte[] cachedEncryptionKey;

    public AiConfigurationService(JdbcClient jdbc, AppProperties properties, RuntimeLlmGateway gateway) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.gateway = gateway;
    }

    @PostConstruct
    void restoreSavedConfiguration() {
        stored().ifPresent(saved -> {
            try {
                String apiKey = saved.apiKeyCiphertext() == null ? "" : decrypt(saved.apiKeyCiphertext());
                gateway.configure(saved.toClientConfig(apiKey));
                log.info("Restored AI runtime configuration mode={} provider={} model={}", saved.mode(),
                        saved.provider(), saved.chatModel());
            } catch (Exception exception) {
                log.warn("Saved AI configuration could not be restored; environment configuration remains active category={}",
                        exception.getClass().getSimpleName());
            }
        });
    }

    public ConfigView view() {
        Optional<StoredConfig> saved = stored();
        AiClientConfig current = gateway.current();
        String hint = saved.map(StoredConfig::apiKeyHint).orElseGet(() -> hint(current.apiKey()));
        boolean configured = current.apiKey() != null && !current.apiKey().isBlank();
        return new ConfigView(current.mode(), current.provider(), current.baseUrl(), current.chatModel(),
                current.fastModel(), current.embeddingModel(), current.timeoutSeconds(), current.maxRetries(),
                current.maxOutputTokens(), configured, configured ? "••••" + hint : "", saved.isPresent() ? "FRONTEND" : "ENVIRONMENT",
                saved.map(StoredConfig::updatedAt).orElse(null));
    }

    public synchronized ConfigView update(UpdateRequest request, String userId) {
        AiClientConfig current = gateway.current();
        Optional<StoredConfig> existing = stored();
        String mode = text(request.mode(), current.mode()).toLowerCase();
        if (!Set.of("mock", "openai-compatible").contains(mode)) {
            throw new IllegalArgumentException("运行模式只支持 mock 或 openai-compatible");
        }
        String provider = text(request.provider(), "openai-compatible").toLowerCase();
        if (!"openai-compatible".equals(provider)) {
            throw new IllegalArgumentException("当前版本只支持 OpenAI-compatible 接口");
        }
        String baseUrl = validateBaseUrl(text(request.baseUrl(), current.baseUrl()));
        String chatModel = bounded(request.chatModel(), current.chatModel(), "对话模型", 200);
        String fastModel = bounded(request.fastModel(), current.fastModel(), "快速模型", 200);
        String embeddingModel = bounded(request.embeddingModel(), current.embeddingModel(), "向量模型", 200);
        int timeout = range(request.timeoutSeconds(), current.timeoutSeconds(), 1, 300, "超时时间");
        int retries = range(request.maxRetries(), current.maxRetries(), 0, 5, "重试次数");
        int maxTokens = range(request.maxOutputTokens(), current.maxOutputTokens(), 128, 32768, "最大输出 Token");

        String apiKey;
        if (request.clearApiKey()) {
            apiKey = "";
        } else if (request.apiKey() != null && !request.apiKey().isBlank()) {
            apiKey = request.apiKey().trim();
        } else if (existing.isPresent() && existing.get().apiKeyCiphertext() != null) {
            apiKey = decrypt(existing.get().apiKeyCiphertext());
        } else {
            apiKey = current.apiKey() == null ? "" : current.apiKey();
        }
        if (apiKey.length() > 8000) throw new IllegalArgumentException("API Key 长度超出限制");
        if (!"mock".equals(mode)) {
            if (apiKey.isBlank()) throw new IllegalArgumentException("启用真实模型前必须填写 API Key");
            if (chatModel == null || chatModel.isBlank()) throw new IllegalArgumentException("启用真实模型前必须填写对话模型名称");
        }

        AiClientConfig next = new AiClientConfig(mode, provider, baseUrl, apiKey, chatModel, fastModel,
                embeddingModel, timeout, retries, maxTokens);
        String encrypted = apiKey.isBlank() ? null : encrypt(apiKey);
        Instant now = Instant.now();
        save(next, encrypted, hint(apiKey), maskUser(userId), now);
        gateway.configure(next);
        audit(userId, "AI_CONFIG_UPDATED", Map.of("mode", mode, "provider", provider,
                "baseUrlHost", URI.create(baseUrl).getHost(), "chatModel", chatModel == null ? "" : chatModel));
        return view();
    }

    public TestResult testCurrent() {
        AiClientConfig current = gateway.current();
        long started = System.nanoTime();
        try {
            LlmGateway.LlmResponse response = gateway.chat(LlmGateway.LlmRequest.single(
                    "你是 API 连通性检查助手。", "只回复 OK"));
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            String message = response.degraded() ? "Mock 模式工作正常，未请求外部 API" : "API 连接成功";
            return new TestResult(true, current.mode(), response.model(), latency, message);
        } catch (Exception exception) {
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            log.warn("AI configuration test failed mode={} category={}", current.mode(),
                    exception.getClass().getSimpleName());
            return new TestResult(false, current.mode(), current.chatModel(), latency,
                    "连接失败，请检查 API 地址、API Key、模型名称和账户额度");
        }
    }

    private Optional<StoredConfig> stored() {
        return jdbc.sql("select mode,provider,base_url,chat_model,fast_model,embedding_model,timeout_seconds,max_retries,max_output_tokens,api_key_ciphertext,api_key_hint,updated_at from ai_runtime_config where id=:id")
                .param("id", ACTIVE_ID)
                .query((rs, row) -> new StoredConfig(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8),
                        rs.getInt(9), rs.getString(10), rs.getString(11), rs.getTimestamp(12).toInstant()))
                .optional();
    }

    private void save(AiClientConfig config, String encrypted, String keyHint, String userId, Instant now) {
        int updated = jdbc.sql("update ai_runtime_config set mode=:mode,provider=:provider,base_url=:baseUrl,chat_model=:chatModel,fast_model=:fastModel,embedding_model=:embeddingModel,timeout_seconds=:timeout,max_retries=:retries,max_output_tokens=:tokens,api_key_ciphertext=:ciphertext,api_key_hint=:hint,updated_by=:updatedBy,updated_at=:updatedAt where id=:id")
                .param("mode", config.mode()).param("provider", config.provider()).param("baseUrl", config.baseUrl())
                .param("chatModel", config.chatModel()).param("fastModel", config.fastModel())
                .param("embeddingModel", config.embeddingModel()).param("timeout", config.timeoutSeconds())
                .param("retries", config.maxRetries()).param("tokens", config.maxOutputTokens())
                .param("ciphertext", encrypted).param("hint", keyHint).param("updatedBy", userId)
                .param("updatedAt", java.sql.Timestamp.from(now)).param("id", ACTIVE_ID).update();
        if (updated == 0) {
            jdbc.sql("insert into ai_runtime_config(id,mode,provider,base_url,chat_model,fast_model,embedding_model,timeout_seconds,max_retries,max_output_tokens,api_key_ciphertext,api_key_hint,updated_by,updated_at) values (:id,:mode,:provider,:baseUrl,:chatModel,:fastModel,:embeddingModel,:timeout,:retries,:tokens,:ciphertext,:hint,:updatedBy,:updatedAt)")
                    .param("id", ACTIVE_ID).param("mode", config.mode()).param("provider", config.provider())
                    .param("baseUrl", config.baseUrl()).param("chatModel", config.chatModel())
                    .param("fastModel", config.fastModel()).param("embeddingModel", config.embeddingModel())
                    .param("timeout", config.timeoutSeconds()).param("retries", config.maxRetries())
                    .param("tokens", config.maxOutputTokens()).param("ciphertext", encrypted).param("hint", keyHint)
                .param("updatedBy", userId).param("updatedAt", java.sql.Timestamp.from(now)).update();
        }
    }

    private void audit(String userId, String action, Map<String, Object> details) {
        jdbc.sql("insert into audit_logs(id,user_id_masked,action,resource_type,resource_id,status,details_json,created_at) values (:id,:user,:action,'AI_CONFIG',:resource,'SUCCESS',:details,:now)")
                .param("id", Ids.uuid()).param("user", maskUser(userId)).param("action", action)
                .param("resource", ACTIVE_ID).param("details", Jsons.write(details)).param("now", java.sql.Timestamp.from(Instant.now())).update();
    }

    private String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey(), "AES"), new GCMParameterSpec(128, nonce));
            byte[] payload = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + payload.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(payload, 0, combined, nonce.length, payload.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            throw new IllegalStateException("无法安全保存 API Key", exception);
        }
    }

    private String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length < 29) throw new IllegalArgumentException("invalid ciphertext");
            byte[] nonce = java.util.Arrays.copyOfRange(combined, 0, 12);
            byte[] payload = java.util.Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey(), "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取已保存的 API Key", exception);
        }
    }

    private synchronized byte[] encryptionKey() throws Exception {
        if (cachedEncryptionKey != null) return cachedEncryptionKey.clone();
        String configured = properties.ai().configEncryptionKey();
        if (configured != null && !configured.isBlank()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(configured.trim());
            } catch (IllegalArgumentException ignored) {
                decoded = new byte[0];
            }
            cachedEncryptionKey = decoded.length == 32 ? decoded : MessageDigest.getInstance("SHA-256")
                    .digest(configured.getBytes(StandardCharsets.UTF_8));
            return cachedEncryptionKey.clone();
        }
        Path keyPath = Path.of(properties.ai().configKeyPath()).toAbsolutePath().normalize();
        if (!Files.exists(keyPath)) {
            Path parent = keyPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            byte[] generated = new byte[32];
            RANDOM.nextBytes(generated);
            try {
                Files.writeString(keyPath, Base64.getEncoder().encodeToString(generated), StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.setPosixFilePermissions(keyPath, Set.of(OWNER_READ, OWNER_WRITE));
                } catch (UnsupportedOperationException ignored) {
                    // Windows ACLs are inherited from the private application directory.
                }
            } catch (FileAlreadyExistsException ignored) {
                // Another startup created the key first; read it below.
            }
        }
        byte[] decoded = Base64.getDecoder().decode(Files.readString(keyPath, StandardCharsets.US_ASCII).trim());
        if (decoded.length != 32) throw new IllegalStateException("AI 配置加密密钥长度无效");
        cachedEncryptionKey = decoded;
        return cachedEncryptionKey.clone();
    }

    private static String validateBaseUrl(String raw) {
        try {
            String value = raw == null ? "" : raw.trim();
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean localHttp = "http".equalsIgnoreCase(scheme) && ("localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host) || "::1".equals(host));
            if (!("https".equalsIgnoreCase(scheme) || localHttp) || host == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || value.contains("..")) {
                throw new IllegalArgumentException();
            }
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        } catch (Exception exception) {
            throw new IllegalArgumentException("API 地址必须是有效 HTTPS 地址；本机调试可使用 localhost HTTP");
        }
    }

    private static String bounded(String value, String fallback, String label, int max) {
        String result = value == null ? fallback : value.trim();
        if (result != null && (result.length() > max || result.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException(label + "格式无效");
        }
        return result;
    }

    private static int range(Integer value, int fallback, int min, int max, String label) {
        int result = value == null ? fallback : value;
        if (result < min || result > max) throw new IllegalArgumentException(label + "必须在 " + min + " 到 " + max + " 之间");
        return result;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String hint(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "";
        return apiKey.substring(Math.max(0, apiKey.length() - 4));
    }

    private static String maskUser(String userId) {
        if (userId == null || userId.isBlank()) return "system";
        return userId.substring(0, Math.min(8, userId.length())) + "***";
    }

    public record UpdateRequest(String mode, String provider, String baseUrl, String apiKey, boolean clearApiKey,
                                String chatModel, String fastModel, String embeddingModel, Integer timeoutSeconds,
                                Integer maxRetries, Integer maxOutputTokens) {
    }

    public record ConfigView(String mode, String provider, String baseUrl, String chatModel, String fastModel,
                             String embeddingModel, int timeoutSeconds, int maxRetries, int maxOutputTokens,
                             boolean apiKeyConfigured, String apiKeyMasked, String source, Instant updatedAt) {
    }

    public record TestResult(boolean success, String mode, String model, long latencyMillis, String message) {
    }

    private record StoredConfig(String mode, String provider, String baseUrl, String chatModel, String fastModel,
                                String embeddingModel, int timeoutSeconds, int maxRetries, int maxOutputTokens,
                                String apiKeyCiphertext, String apiKeyHint, Instant updatedAt) {
        AiClientConfig toClientConfig(String apiKey) {
            return new AiClientConfig(mode, provider, baseUrl, apiKey, chatModel, fastModel, embeddingModel,
                    timeoutSeconds, maxRetries, maxOutputTokens);
        }
    }
}
