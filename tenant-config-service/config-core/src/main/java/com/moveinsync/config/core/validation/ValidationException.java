package com.moveinsync.config.core.validation;

public class ValidationException extends RuntimeException {
    private final String key;
    private final Object rejectedValue;

    public ValidationException(String key, Object rejectedValue, String message) {
        super(String.format("Validation failed for key '%s' with value '%s': %s", key, rejectedValue, message));
        this.key = key;
        this.rejectedValue = rejectedValue;
    }

    public String getKey() {
        return key;
    }

    public Object getRejectedValue() {
        return rejectedValue;
    }
}
