package cn.wenjin.admission.ai;

import cn.wenjin.admission.common.Jsons;
import reactor.core.publisher.Flux;

import java.lang.reflect.Constructor;
import java.util.Arrays;

public class MockLlmGateway implements LlmGateway {
    @Override
    public LlmResponse chat(LlmRequest request) {
        String response = "解释服务当前使用本地 Mock Provider。推荐事实、规则校验和冲稳保分层均由结构化数据与 Java 引擎生成。";
        return new LlmResponse(response, "mock-deterministic", 0, 0, 1, true);
    }

    @Override
    public Flux<LlmStreamEvent> stream(LlmRequest request) {
        return Flux.just(new LlmStreamEvent("token", chat(request).content(), false),
                new LlmStreamEvent("completed", "", true));
    }

    @Override
    public <T> T structuredOutput(LlmRequest request, Class<T> outputType) {
        try {
            Constructor<?> constructor = Arrays.stream(outputType.getDeclaredConstructors())
                    .findFirst().orElseThrow();
            Object[] defaults = Arrays.stream(constructor.getParameterTypes()).map(MockLlmGateway::defaultValue).toArray();
            constructor.setAccessible(true);
            return outputType.cast(constructor.newInstance(defaults));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Mock structured output is not defined for " + outputType.getName(), exception);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == String.class) return "UNKNOWN";
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == double.class || type == Double.class) return 0.0;
        if (java.util.List.class.isAssignableFrom(type)) return java.util.List.of();
        if (java.util.Map.class.isAssignableFrom(type)) return java.util.Map.of();
        return null;
    }
}

