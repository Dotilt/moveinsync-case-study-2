package com.moveinsync.config.core.diff;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.moveinsync.config.core.model.ScopeKey;
import java.util.Collections;
import java.util.List;

public final class ConfigDiff {
    private final long fromVersion;
    private final long toVersion;
    private final List<DiffEntry> changes;

    @JsonCreator
    public ConfigDiff(
            @JsonProperty("fromVersion") long fromVersion,
            @JsonProperty("toVersion") long toVersion,
            @JsonProperty("changes") List<DiffEntry> changes) {
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.changes = changes != null ? Collections.unmodifiableList(changes) : Collections.emptyList();
    }

    public long getFromVersion() {
        return fromVersion;
    }

    public long getToVersion() {
        return toVersion;
    }

    public List<DiffEntry> getChanges() {
        return changes;
    }

    public static final class DiffEntry {
        public enum Action {
            ADDED, MODIFIED, REMOVED
        }

        private final ScopeKey scopeKey;
        private final String key;
        private final Object oldValue;
        private final Object newValue;
        private final Action action;

        @JsonCreator
        public DiffEntry(
                @JsonProperty("scopeKey") ScopeKey scopeKey,
                @JsonProperty("key") String key,
                @JsonProperty("oldValue") Object oldValue,
                @JsonProperty("newValue") Object newValue,
                @JsonProperty("action") Action action) {
            this.scopeKey = scopeKey;
            this.key = key;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.action = action;
        }

        public ScopeKey getScopeKey() {
            return scopeKey;
        }

        public String getKey() {
            return key;
        }

        public Object getOldValue() {
            return oldValue;
        }

        public Object getNewValue() {
            return newValue;
        }

        public Action getAction() {
            return action;
        }

        @Override
        public String toString() {
            return action + ": " + scopeKey.toPath() + " -> " + key + " [" + oldValue + " -> " + newValue + "]";
        }
    }
}
