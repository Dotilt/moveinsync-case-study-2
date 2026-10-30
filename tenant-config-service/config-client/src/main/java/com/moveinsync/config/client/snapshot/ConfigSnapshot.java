package com.moveinsync.config.client.snapshot;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.moveinsync.config.core.model.ExplainResult;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.ScopeLevel;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ConfigSnapshot {
    private final long version;
    private final long timestamp;
    private final Map<String, SettingDefinition> definitions;
    // Map of ScopeKey string path -> (Map of SettingKey -> SettingValue)
    private final Map<String, Map<String, SettingValue>> scopedValues;

    @JsonCreator
    public ConfigSnapshot(
            @JsonProperty("version") long version,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("definitions") Map<String, SettingDefinition> definitions,
            @JsonProperty("scopedValues") Map<String, Map<String, SettingValue>> scopedValues) {
        this.version = version;
        this.timestamp = timestamp;
        this.definitions = definitions != null ? Collections.unmodifiableMap(new HashMap<>(definitions)) : Collections.emptyMap();
        this.scopedValues = scopedValues != null ? Collections.unmodifiableMap(deepCopy(scopedValues)) : Collections.emptyMap();
    }

    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(0L, System.currentTimeMillis(), Collections.emptyMap(), Collections.emptyMap());
    }

    public long getVersion() {
        return version;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Map<String, SettingDefinition> getDefinitions() {
        return definitions;
    }

    public Map<String, Map<String, SettingValue>> getScopedValues() {
        return scopedValues;
    }

    public Object resolve(String key, ScopeKey requestedScope) {
        ExplainResult explanation = explain(key, requestedScope);
        return explanation.getResolvedValue();
    }

    public ExplainResult explain(String key, ScopeKey requestedScope) {
        SettingDefinition def = definitions.get(key);
        List<ExplainResult.EvaluationStep> steps = new ArrayList<>();
        List<ScopeKey> hierarchyLevels = buildEvaluationHierarchy(requestedScope);

        for (ScopeKey levelKey : hierarchyLevels) {
            Map<String, SettingValue> levelMap = scopedValues.get(levelKey.toPath());
            SettingValue sv = levelMap != null ? levelMap.get(key) : null;

            if (sv != null) {
                steps.add(new ExplainResult.EvaluationStep(
                        levelKey.getLevel(),
                        levelKey,
                        true,
                        sv.getValue(),
                        "Match found at " + levelKey.getLevel() + " level (Version " + sv.getVersion() + ")"
                ));
                return new ExplainResult(key, sv.getValue(), levelKey.getLevel(), levelKey, steps);
            } else {
                steps.add(new ExplainResult.EvaluationStep(
                        levelKey.getLevel(),
                        levelKey,
                        false,
                        null,
                        "No override at " + levelKey.getLevel() + " level (" + levelKey.toPath() + ")"
                ));
            }
        }

        Object defaultVal = def != null ? def.getGlobalDefault() : null;
        steps.add(new ExplainResult.EvaluationStep(
                ScopeLevel.GLOBAL,
                ScopeKey.global(),
                defaultVal != null,
                defaultVal,
                "Fell back to system global schema default"
        ));

        return new ExplainResult(key, defaultVal, ScopeLevel.GLOBAL, ScopeKey.global(), steps);
    }

    private static List<ScopeKey> buildEvaluationHierarchy(ScopeKey requestedScope) {
        List<ScopeKey> list = new ArrayList<>();
        if (requestedScope == null) {
            list.add(ScopeKey.global());
            return list;
        }

        if (requestedScope.getShiftId() != null && requestedScope.getClientId() != null && requestedScope.getOfficeId() != null) {
            list.add(ScopeKey.shift(requestedScope.getClientId(), requestedScope.getOfficeId(), requestedScope.getShiftId()));
        }
        if (requestedScope.getOfficeId() != null && requestedScope.getClientId() != null) {
            list.add(ScopeKey.office(requestedScope.getClientId(), requestedScope.getOfficeId()));
        }
        if (requestedScope.getClientId() != null) {
            list.add(ScopeKey.client(requestedScope.getClientId()));
        }
        list.add(ScopeKey.global());
        return list;
    }

    private static Map<String, Map<String, SettingValue>> deepCopy(Map<String, Map<String, SettingValue>> source) {
        Map<String, Map<String, SettingValue>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, SettingValue>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return copy;
    }
}
