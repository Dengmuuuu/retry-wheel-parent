package com.fastretry.core.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fastretry.core.spi.PayloadSerializer;

public class JacksonPayloadSerializer implements PayloadSerializer {

    private final ObjectMapper mapper;

    /** 使用推荐的默认配置构造 */
    public JacksonPayloadSerializer() {
        this(createDefaultMapper());
    }

    /** 允许外部传入自定义 ObjectMapper */
    public JacksonPayloadSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public <T> T deserialize(String json, TypeReference<T> typeRef) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize payload from JSON", e);
        }
    }

    @Override
    public String serialize(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize payload to JSON", e);
        }
    }

    private static ObjectMapper createDefaultMapper() {
        ObjectMapper m = new ObjectMapper();
        m.enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 反序列化忽略未知字段，增强前后兼容
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // 空字符串 -> null（可选）
        m.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        // 自动发现（如 JDK8 Optional、JSR310 等）
        m.findAndRegisterModules();
        return m;
    }
}
