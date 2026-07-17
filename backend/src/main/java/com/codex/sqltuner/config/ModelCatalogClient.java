package com.codex.sqltuner.config;

public interface ModelCatalogClient {
    ModelCatalogView discover(String baseUrl, String apiKey, int timeoutMs);
}
