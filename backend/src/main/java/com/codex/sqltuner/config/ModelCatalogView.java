package com.codex.sqltuner.config;

import java.util.ArrayList;
import java.util.List;

public class ModelCatalogView {
    private String endpoint;
    private List<String> models = new ArrayList<String>();

    public ModelCatalogView() {
    }

    public ModelCatalogView(String endpoint, List<String> models) {
        this.endpoint = endpoint;
        this.models = models == null ? new ArrayList<String>() : models;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models == null ? new ArrayList<String>() : models;
    }
}
