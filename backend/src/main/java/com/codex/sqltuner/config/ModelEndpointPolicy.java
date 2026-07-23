package com.codex.sqltuner.config;

import com.codex.sqltuner.common.ApiException;
import com.codex.sqltuner.llm.LlmProperties;
import org.apache.http.conn.DnsResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

@Component
public class ModelEndpointPolicy {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private final boolean productionMode;

    @Autowired
    public ModelEndpointPolicy(LlmProperties properties) {
        this(properties == null || properties.isEndpointPolicyProduction());
    }

    public ModelEndpointPolicy(boolean productionMode) {
        this.productionMode = productionMode;
    }

    public Endpoint normalizeBaseUrl(String provider, String baseUrl) {
        if (!hasText(baseUrl)) {
            throw new ApiException(400, "MODEL_BASE_URL_REQUIRED", "请先填写 OpenAI-compatible Base URL");
        }
        URI input;
        try {
            input = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            throw new ApiException(400, "MODEL_BASE_URL_INVALID", "Base URL 不是合法地址");
        }
        if (hasText(input.getRawUserInfo())) {
            throw new ApiException(400, "MODEL_BASE_URL_FORBIDDEN", "Base URL 不允许包含用户信息");
        }
        if (hasText(input.getRawQuery()) || hasText(input.getRawFragment())) {
            throw new ApiException(400, "MODEL_BASE_URL_INVALID", "Base URL 不允许包含 query 或 fragment");
        }
        String scheme = input.getScheme();
        String host = input.getHost();
        if (!hasText(scheme) || !hasText(host)
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new ApiException(400, "MODEL_BASE_URL_INVALID", "Base URL 仅支持 http 或 https 地址");
        }
        if (productionMode && !"https".equalsIgnoreCase(scheme)) {
            throw new ApiException(400, "MODEL_BASE_URL_FORBIDDEN", "生产环境模型 Base URL 仅允许 HTTPS");
        }
        String normalizedHost = normalizeHost(host);
        if (productionMode) {
            rejectForbiddenHost(normalizedHost);
        }
        String path = normalizePath(input.getRawPath());
        if (path.endsWith(CHAT_COMPLETIONS_PATH)) {
            path = path.substring(0, path.length() - CHAT_COMPLETIONS_PATH.length());
        } else if (path.endsWith("/models")) {
            path = path.substring(0, path.length() - "/models".length());
        }
        String normalizedBaseUrl = buildUri(scheme.toLowerCase(Locale.ROOT), normalizedHost, input.getPort(), path);
        return new Endpoint(
                normalizedBaseUrl,
                appendPath(normalizedBaseUrl, "/models"),
                appendPath(normalizedBaseUrl, CHAT_COMPLETIONS_PATH),
                keyBinding(provider, normalizedHost));
    }

    public String modelsEndpoint(String provider, String baseUrl) {
        return normalizeBaseUrl(provider, baseUrl).getModelsUrl();
    }

    public String chatCompletionsEndpoint(String provider, String baseUrl, String apiKeyBinding) {
        Endpoint endpoint = normalizeBaseUrl(provider, baseUrl);
        requireApiKeyBinding(endpoint, apiKeyBinding);
        return endpoint.getChatCompletionsUrl();
    }

    public void requireApiKeyBinding(Endpoint endpoint, String apiKeyBinding) {
        if (!hasText(apiKeyBinding) || !endpoint.getApiKeyBinding().equals(apiKeyBinding.trim())) {
            throw new ApiException(400, "MODEL_API_KEY_REBIND_REQUIRED", "Base URL 主机变更后必须重新填写模型 API Key");
        }
    }

    public DnsResolver dnsResolver() {
        return host -> {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (productionMode) {
                for (InetAddress address : addresses) {
                    rejectForbiddenAddress(address);
                }
            }
            return addresses;
        };
    }

    private void rejectForbiddenHost(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || normalized.endsWith(".localhost")) {
            throw new ApiException(400, "MODEL_BASE_URL_FORBIDDEN", "生产环境模型 Base URL 不允许指向 localhost");
        }
        try {
            rejectForbiddenAddress(InetAddress.getByName(normalized));
        } catch (UnknownHostException ignored) {
            // 域名在实际连接时仍会通过受控 DNS resolver 重新校验解析结果。
        }
    }

    private void rejectForbiddenAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCarrierGradeNat(bytes)
                || isUniqueLocalIpv6(bytes)) {
            throw new ApiException(400, "MODEL_BASE_URL_FORBIDDEN", "生产环境模型 Base URL 不允许指向本机、内网、链路本地或保留地址");
        }
    }

    private boolean isCarrierGradeNat(byte[] bytes) {
        return bytes.length == 4
                && unsigned(bytes[0]) == 100
                && unsigned(bytes[1]) >= 64
                && unsigned(bytes[1]) <= 127;
    }

    private boolean isUniqueLocalIpv6(byte[] bytes) {
        return bytes.length == 16 && (unsigned(bytes[0]) & 0xfe) == 0xfc;
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    private String normalizeHost(String host) {
        return IDN.toASCII(host.trim().toLowerCase(Locale.ROOT), IDN.USE_STD3_ASCII_RULES);
    }

    private String normalizePath(String rawPath) {
        String path = hasText(rawPath) ? rawPath.trim() : "";
        path = path.replaceAll("/+$", "");
        return path.isEmpty() ? "" : path;
    }

    private String buildUri(String scheme, String host, int port, String path) {
        try {
            return new URI(scheme, null, host, port, path, null, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new ApiException(400, "MODEL_BASE_URL_INVALID", "Base URL 不是合法地址");
        }
    }

    private String appendPath(String baseUrl, String suffix) {
        return baseUrl.replaceAll("/+$", "") + suffix;
    }

    private String keyBinding(String provider, String host) {
        String normalizedProvider = hasText(provider) ? provider.trim().toLowerCase(Locale.ROOT) : "openai-compatible";
        return normalizedProvider + "@" + host;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static class Endpoint {
        private final String baseUrl;
        private final String modelsUrl;
        private final String chatCompletionsUrl;
        private final String apiKeyBinding;

        Endpoint(String baseUrl, String modelsUrl, String chatCompletionsUrl, String apiKeyBinding) {
            this.baseUrl = baseUrl;
            this.modelsUrl = modelsUrl;
            this.chatCompletionsUrl = chatCompletionsUrl;
            this.apiKeyBinding = apiKeyBinding;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public String getModelsUrl() {
            return modelsUrl;
        }

        public String getChatCompletionsUrl() {
            return chatCompletionsUrl;
        }

        public String getApiKeyBinding() {
            return apiKeyBinding;
        }
    }
}
