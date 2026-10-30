package com.moveinsync.config.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public final class SettingValue {
    private final Object value;
    private final ScopeKey scopeKey;
    private final long version;
    private final long timestamp;

    @JsonCreator
    public SettingValue(
            @JsonProperty("value") Object value,
            @JsonProperty("scopeKey") ScopeKey scopeKey,
            @JsonProperty("version") long version,
            @JsonProperty("timestamp") long timestamp) {
        this.value = Objects.requireNonNull(value, "value cannot be null");
        this.scopeKey = Objects.requireNonNull(scopeKey, "scopeKey cannot be null");
        this.version = version;
        this.timestamp = timestamp;
    }

    public Object getValue() {
        return value;
    }

    public ScopeKey getScopeKey() {
        return scopeKey;
    }

    public long getVersion() {
        return version;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SettingValue that = (SettingValue) o;
        return version == that.version &&
                timestamp == that.timestamp &&
                Objects.equals(value, that.value) &&
                Objects.equals(scopeKey, that.scopeKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, scopeKey, version, timestamp);
    }

    @Override
    public String toString() {
        return "SettingValue{" +
                "value=" + value +
                ", scopeKey=" + scopeKey +
                ", version=" + version +
                ", timestamp=" + timestamp +
                '}';
    }
}
