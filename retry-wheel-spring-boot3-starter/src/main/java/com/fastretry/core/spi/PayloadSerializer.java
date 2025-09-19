package com.fastretry.core.spi;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * 序列化
 */
public interface PayloadSerializer {


    /** 将对象序列化为 JSON 字符串 */
    <T> T deserialize(String json, TypeReference<T> typeRef);

    /** 反序列化 JSON 为指定泛型类型 */
    String serialize(Object obj);
}
