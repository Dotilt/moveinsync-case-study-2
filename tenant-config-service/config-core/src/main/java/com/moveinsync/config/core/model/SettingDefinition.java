package com.moveinsync.config.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class SettingDefinition {
    private final String key;
    private final DataType dataType;
    private final String description;
    private final Object globalDefault;
    private final List<String> allowedValues; // For ENUM
    private final Double minValue;            // For NUMBER / DURATION
    private final Double maxValue;            // For NUMBER / DURATION

    @JsonCreator
    public SettingDefinition(
            @JsonProperty("key") String key,
            @JsonProperty("dataType") DataType dataType,
            @JsonProperty("description") String description,
            @JsonProperty("globalDefault") Object globalDefault,
            @JsonProperty("allowedValues") List<String> allowedValues,
            @JsonProperty("minValue") Double minValue,
            @JsonProperty("maxValue") Double maxValue) {
        this.key = Objects.requireNonNull(key, "key cannot be null");
        this.dataType = Objects.requireNonNull(dataType, "dataType cannot be null");
        this.description = description;
        this.globalDefault = Objects.requireNonNull(globalDefault, "globalDefault cannot be null");
        this.allowedValues = allowedValues != null ? Collections.unmodifiableList(allowedValues) : Collections.emptyList();
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public static Builder builder(String key, DataType dataType) {
        return new Builder(key, dataType);
    }

    public String getKey() {
        return key;
    }

    public DataType getDataType() {
        return dataType;
    }

    public String getDescription() {
        return description;
    }

    public Object getGlobalDefault() {
        return globalDefault;
    }

    public List<String> getAllowedValues() {
        return allowedValues;
    }

    public Double getMinValue() {
        return minValue;
    }

    public Double getMaxValue() {
        return maxValue;
    }

    public static class Builder {
        private final String key;
        private final DataType dataType;
        private String description;
        private Object globalDefault;
        private List<String> allowedValues;
        private Double minValue;
        private Double maxValue;

        public Builder(String key, DataType dataType) {
            this.key = key;
            this.dataType = dataType;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder globalDefault(Object globalDefault) {
            this.globalDefault = globalDefault;
            return this;
        }

        public Builder allowedValues(List<String> allowedValues) {
            this.allowedValues = allowedValues;
            return this;
        }

        public Builder range(Double min, Double max) {
            this.minValue = min;
            this.maxValue = max;
            return this;
        }

        public SettingDefinition build() {
            return new SettingDefinition(key, dataType, description, globalDefault, allowedValues, minValue, maxValue);
        }
    }
}
