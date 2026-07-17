package com.codex.sqltuner.tuning.accuracy;

import java.util.ArrayList;
import java.util.List;

public class ValidationOutcome {
    private boolean valid = true;
    private List<String> errors = new ArrayList<String>();

    public boolean isValid() {
        return valid;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void reject(String error) {
        this.valid = false;
        this.errors.add(error);
    }

    public String summary() {
        return errors.isEmpty() ? "" : errors.toString();
    }
}
