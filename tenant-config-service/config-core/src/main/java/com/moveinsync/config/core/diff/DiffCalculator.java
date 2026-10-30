package com.moveinsync.config.core.diff;

import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.SettingValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class DiffCalculator {

    public static ConfigDiff computeDiff(
            long fromVersion,
            Map<ScopeKey, Map<String, SettingValue>> fromSnapshot,
            long toVersion,
            Map<ScopeKey, Map<String, SettingValue>> toSnapshot) {

        List<ConfigDiff.DiffEntry> entries = new ArrayList<>();
        Set<ScopeKey> allScopes = new HashSet<>();
        if (fromSnapshot != null) allScopes.addAll(fromSnapshot.keySet());
        if (toSnapshot != null) allScopes.addAll(toSnapshot.keySet());

        for (ScopeKey scope : allScopes) {
            Map<String, SettingValue> fromMap = fromSnapshot != null ? fromSnapshot.get(scope) : null;
            Map<String, SettingValue> toMap = toSnapshot != null ? toSnapshot.get(scope) : null;

            Set<String> allKeys = new HashSet<>();
            if (fromMap != null) allKeys.addAll(fromMap.keySet());
            if (toMap != null) allKeys.addAll(toMap.keySet());

            for (String key : allKeys) {
                SettingValue fromVal = fromMap != null ? fromMap.get(key) : null;
                SettingValue toVal = toMap != null ? toMap.get(key) : null;

                if (fromVal == null && toVal != null) {
                    entries.add(new ConfigDiff.DiffEntry(
                            scope, key, null, toVal.getValue(), ConfigDiff.DiffEntry.Action.ADDED));
                } else if (fromVal != null && toVal == null) {
                    entries.add(new ConfigDiff.DiffEntry(
                            scope, key, fromVal.getValue(), null, ConfigDiff.DiffEntry.Action.REMOVED));
                } else if (fromVal != null && toVal != null) {
                    if (!Objects.equals(fromVal.getValue(), toVal.getValue())) {
                        entries.add(new ConfigDiff.DiffEntry(
                                scope, key, fromVal.getValue(), toVal.getValue(), ConfigDiff.DiffEntry.Action.MODIFIED));
                    }
                }
            }
        }

        return new ConfigDiff(fromVersion, toVersion, entries);
    }
}
