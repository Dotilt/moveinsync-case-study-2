package com.moveinsync.config.core.hierarchy;

import com.moveinsync.config.core.model.ExplainResult;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.ScopeLevel;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigTree {
    // Map of ScopeKey -> (Map of SettingKey -> SettingValue)
    private final Map<ScopeKey, Map<String, SettingValue>> tree = new ConcurrentHashMap<>();
    private final Map<String, SettingDefinition> definitions = new ConcurrentHashMap<>();

    public void registerDefinition(SettingDefinition definition) {
        definitions.put(definition.getKey(), definition);
    }

    public SettingDefinition getDefinition(String key) {
        return definitions.get(key);
    }

    public Map<String, SettingDefinition> getAllDefinitions() {
        return Map.copyOf(definitions);
    }

    public void putValue(ScopeKey scopeKey, String settingKey, SettingValue value) {
        tree.computeIfAbsent(scopeKey, k -> new ConcurrentHashMap<>()).put(settingKey, value);
    }

    public void removeValue(ScopeKey scopeKey, String settingKey) {
        Map<String, SettingValue> scopedMap = tree.get(scopeKey);
        if (scopedMap != null) {
            scopedMap.remove(settingKey);
        }
    }

    public SettingValue getDirectValue(ScopeKey scopeKey, String settingKey) {
        Map<String, SettingValue> scopedMap = tree.get(scopeKey);
        return scopedMap != null ? scopedMap.get(settingKey) : null;
    }

    public Map<ScopeKey, Map<String, SettingValue>> getRawSnapshot() {
        Map<ScopeKey, Map<String, SettingValue>> copy = new ConcurrentHashMap<>();
        tree.forEach((scope, map) -> copy.put(scope, new ConcurrentHashMap<>(map)));
        return copy;
    }

    /**
     * Resolves setting using precedence: SHIFT -> OFFICE -> CLIENT -> GLOBAL -> Default
     */
    public Object resolve(String key, ScopeKey requestedScope) {
        ExplainResult explanation = explain(key, requestedScope);
        return explanation.getResolvedValue();
    }

    /**
     * Generates complete audit evaluation trace for resolution.
     */
    public ExplainResult explain(String key, ScopeKey requestedScope) {
        SettingDefinition def = definitions.get(key);
        List<ExplainResult.EvaluationStep> steps = new ArrayList<>();

        List<ScopeKey> hierarchyLevels = buildEvaluationHierarchy(requestedScope);

        for (ScopeKey levelKey : hierarchyLevels) {
            SettingValue sv = getDirectValue(levelKey, key);
            if (sv != null) {
                steps.add(new ExplainResult.EvaluationStep(
                        levelKey.getLevel(),
                        levelKey,
                        true,
                        sv.getValue(),
                        "Match found at " + levelKey.getLevel() + " level (Version " + sv.getVersion() + ")"
                ));

                return new ExplainResult(
                        key,
                        sv.getValue(),
                        levelKey.getLevel(),
                        levelKey,
                        steps
                );
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

        // Global default fallback
        Object defaultVal = def != null ? def.getGlobalDefault() : null;
        steps.add(new ExplainResult.EvaluationStep(
                ScopeLevel.GLOBAL,
                ScopeKey.global(),
                defaultVal != null,
                defaultVal,
                "Fell back to system global schema default"
        ));

        return new ExplainResult(
            key,
            defaultVal,
            ScopeLevel.GLOBAL,
            ScopeKey.global(),
            steps
        );
    }

    private List<ScopeKey> buildEvaluationHierarchy(ScopeKey requestedScope) {
        List<ScopeKey> list = new ArrayList<>();
        if (requestedScope == null) {
            list.add(ScopeKey.global());
            return list;
        }

        // 1. SHIFT (if shiftId present)
        if (requestedScope.getShiftId() != null && requestedScope.getClientId() != null && requestedScope.getOfficeId() != null) {
            list.add(ScopeKey.shift(requestedScope.getClientId(), requestedScope.getOfficeId(), requestedScope.getShiftId()));
        }

        // 2. OFFICE (if officeId present)
        if (requestedScope.getOfficeId() != null && requestedScope.getClientId() != null) {
            list.add(ScopeKey.office(requestedScope.getClientId(), requestedScope.getOfficeId()));
        }

        // 3. CLIENT (if clientId present)
        if (requestedScope.getClientId() != null) {
            list.add(ScopeKey.client(requestedScope.getClientId()));
        }

        // 4. GLOBAL
        list.add(ScopeKey.global());

        return list;
    }
}
