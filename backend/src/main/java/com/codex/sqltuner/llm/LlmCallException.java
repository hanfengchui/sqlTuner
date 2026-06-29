package com.codex.sqltuner.llm;

/**
 * LLM 真实调用失败（网络/超时/鉴权/限流/响应解析失败）时抛出。
 * 区别于"配置性 mock"（provider=mock 或缺 key，正常返回 mock 响应）：
 * 调用失败不应静默回退成 mock 当成功，而应让上层把任务标记为失败并暴露原因。
 */
public class LlmCallException extends RuntimeException {
    private final boolean mockFallback;

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
        this.mockFallback = false;
    }

    public LlmCallException(String message, Throwable cause, boolean mockFallback) {
        super(message, cause);
        this.mockFallback = mockFallback;
    }

    public boolean isMockFallback() {
        return mockFallback;
    }
}
