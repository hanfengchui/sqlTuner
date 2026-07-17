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

    /**
     * MySQL Connector/J 会把 JSON 字符串标量通过 getString() 直接返回为去引号文本，
     * H2 则保留 JSON 引号。产物载荷允许字符串，因此读取时兼容这两种驱动行为；
     * 产物仅用于诊断展示，单条历史载荷异常不能阻断任务领取或 SSE 快照。
     */
    public Object readArtifactPayload(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            Object value = objectMapper.readValue(stored, Object.class);
            if (value instanceof String) {
                String text = (String) value;
                String trimmed = text.trim();
                // H2 可能把整个 JSON 文档再包成字符串标量；只对对象/数组做第二层解包。
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        return objectMapper.readValue(text, Object.class);
                    } catch (Exception ignored) {
                        return text;
                    }
                }
            }
            return value;
        } catch (Exception ignored) {
            return stored;
        }
    }
}
