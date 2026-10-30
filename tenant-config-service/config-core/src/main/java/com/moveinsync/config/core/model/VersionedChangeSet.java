package com.moveinsync.config.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class VersionedChangeSet {
    private final long version;
    private final String author;
    private final String reason;
    private final long timestamp;
    private final List<String> stagedOffices; // Optional: null or empty means global/all
    private final List<ScopedUpdate> updates;

    @JsonCreator
    public VersionedChangeSet(
            @JsonProperty("version") long version,
            @JsonProperty("author") String author,
            @JsonProperty("reason") String reason,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("stagedOffices") List<String> stagedOffices,
            @JsonProperty("updates") List<ScopedUpdate> updates) {
        this.version = version;
        this.author = Objects.requireNonNull(author, "author cannot be null");
        this.reason = Objects.requireNonNull(reason, "reason cannot be null");
        this.timestamp = timestamp;
        this.stagedOffices = stagedOffices != null ? Collections.unmodifiableList(stagedOffices) : Collections.emptyList();
        this.updates = updates != null ? Collections.unmodifiableList(updates) : Collections.emptyList();
    }

    public long getVersion() {
        return version;
    }

    public String getAuthor() {
        return author;
    }

    public String getReason() {
        return reason;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public List<String> getStagedOffices() {
        return stagedOffices;
    }

    public List<ScopedUpdate> getUpdates() {
        return updates;
    }

    public static final class ScopedUpdate {
        private final ScopeKey scopeKey;
        private final String key;
        private final Object value; // null implies delete / clear override

        @JsonCreator
        public ScopedUpdate(
                @JsonProperty("scopeKey") ScopeKey scopeKey,
                @JsonProperty("key") String key,
                @JsonProperty("value") Object value) {
            this.scopeKey = Objects.requireNonNull(scopeKey, "scopeKey cannot be null");
            this.key = Objects.requireNonNull(key, "key cannot be null");
            this.value = value;
        }

        public ScopeKey getScopeKey() {
            return scopeKey;
        }

        public String getKey() {
            return key;
        }

        public Object getValue() {
            return value;
        }

        public boolean isDelete() {
            return value == null;
        }

        @Override
        public String toString() {
            return "ScopedUpdate{" +
                    "scope=" + scopeKey +
                    ", key='" + key + '\'' +
                    ", value=" + value +
                    '}';
        }
    }
}
