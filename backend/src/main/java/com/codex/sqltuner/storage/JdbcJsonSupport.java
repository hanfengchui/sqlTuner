package com.codex.sqltuner.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcJsonSupport {
    private final ObjectMapper objectMapper;

    public JdbcJsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            String value = json;
            // H2 的 JSON 列通过 setString 写入时会返回 JSON 字符串标量；MySQL 返回对象文本。
            // 测试兼容层只解包这一层引号，不改变生产 MySQL 的 JSON 对象语义。
            if (value != null && value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                value = objectMapper.readValue(value, String.class);
            }
            return objectMapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }
}
