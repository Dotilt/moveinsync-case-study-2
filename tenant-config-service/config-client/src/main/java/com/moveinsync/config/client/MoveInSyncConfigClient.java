package com.moveinsync.config.client;

import com.moveinsync.config.client.cache.DiskSnapshotCache;
import com.moveinsync.config.client.cache.FileBasedDiskSnapshotCache;
import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.client.snapshot.SnapshotManager;
import com.moveinsync.config.client.sync.BackoffStrategy;
import com.moveinsync.config.client.sync.ConfigSyncClient;
import com.moveinsync.config.client.sync.ConfigSynchronizer;
import com.moveinsync.config.client.sync.HttpConfigSyncClient;
import com.moveinsync.config.core.model.ExplainResult;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.TimeWindow;
import com.moveinsync.config.core.model.VersionedChangeSet;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public class MoveInSyncConfigClient implements Closeable {
    private final String clientId;
    private final String officeId;
    private final String defaultShiftId;
    private final SnapshotManager snapshotManager;
    private final ConfigSynchronizer synchronizer;

    public MoveInSyncConfigClient(
            String clientId,
            String officeId,
            String defaultShiftId,
            DiskSnapshotCache diskCache,
            ConfigSyncClient syncClient,
            long reconciliationIntervalSeconds) {
        this.clientId = clientId;
        this.officeId = officeId;
        this.defaultShiftId = defaultShiftId;
        this.snapshotManager = new SnapshotManager(diskCache);

        if (syncClient != null) {
            this.synchronizer = new ConfigSynchronizer(
                    clientId,
                    snapshotManager,
                    syncClient,
                    BackoffStrategy.defaultStrategy(),
                    reconciliationIntervalSeconds
            );
            this.synchronizer.start();
        } else {
            this.synchronizer = null;
        }
    }

    public static Builder builder(String clientId) {
        return new Builder(clientId);
    }

    public SnapshotManager getSnapshotManager() {
        return snapshotManager;
    }

    public long getCurrentVersion() {
        return snapshotManager.getVersion();
    }

    public ConfigSnapshot getSnapshot() {
        return snapshotManager.getSnapshot();
    }

    // Direct microsecond read with contextual default scope
    public Object get(String key) {
        return get(key, resolveDefaultScopeKey());
    }

    public Object get(String key, ScopeKey scope) {
        return snapshotManager.getSnapshot().resolve(key, scope);
    }

    public Long getLong(String key) {
        return getLong(key, resolveDefaultScopeKey());
    }

    public Long getLong(String key, ScopeKey scope) {
        Object val = get(key, scope);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(val.toString());
    }

    public String getString(String key) {
        return getString(key, resolveDefaultScopeKey());
    }

    public String getString(String key, ScopeKey scope) {
        Object val = get(key, scope);
        return val != null ? val.toString() : null;
    }

    public TimeWindow getTimeWindow(String key) {
        return getTimeWindow(key, resolveDefaultScopeKey());
    }

    public TimeWindow getTimeWindow(String key, ScopeKey scope) {
        Object val = get(key, scope);
        if (val instanceof TimeWindow) return (TimeWindow) val;
        if (val instanceof String) {
            String[] parts = ((String) val).split("-");
            return new TimeWindow(parts[0].trim(), parts[1].trim());
        }
        return null;
    }

    public ExplainResult explain(String key) {
        return explain(key, resolveDefaultScopeKey());
    }

    public ExplainResult explain(String key, ScopeKey scope) {
        return snapshotManager.getSnapshot().explain(key, scope);
    }

    public boolean applyChangeSet(VersionedChangeSet changeSet) {
        return snapshotManager.applyChangeSet(changeSet);
    }

    public boolean applySnapshot(ConfigSnapshot snapshot) {
        return snapshotManager.applyFullSnapshot(snapshot);
    }

    public void registerDefinitions(Map<String, SettingDefinition> definitions) {
        snapshotManager.registerDefinitions(definitions);
    }

    public void triggerManualReconcile() {
        if (synchronizer != null) {
            synchronizer.performReconciliation();
        }
    }

    @Override
    public void close() {
        if (synchronizer != null) {
            synchronizer.close();
        }
    }

    private ScopeKey resolveDefaultScopeKey() {
        if (defaultShiftId != null && officeId != null && clientId != null) {
            return ScopeKey.shift(clientId, officeId, defaultShiftId);
        }
        if (officeId != null && clientId != null) {
            return ScopeKey.office(clientId, officeId);
        }
        if (clientId != null) {
            return ScopeKey.client(clientId);
        }
        return ScopeKey.global();
    }

    public static class Builder {
        private final String clientId;
        private String officeId;
        private String defaultShiftId;
        private Path diskCacheDir = Path.of(System.getProperty("user.home"), ".moveinsync", "cache");
        private DiskSnapshotCache customCache;
        private String serverUrl;
        private ConfigSyncClient customSyncClient;
        private long reconciliationIntervalSeconds = 3;

        public Builder(String clientId) {
            this.clientId = Objects.requireNonNull(clientId, "clientId cannot be null");
        }

        public Builder officeId(String officeId) {
            this.officeId = officeId;
            return this;
        }

        public Builder defaultShiftId(String defaultShiftId) {
            this.defaultShiftId = defaultShiftId;
            return this;
        }

        public Builder diskCacheDir(Path diskCacheDir) {
            this.diskCacheDir = diskCacheDir;
            return this;
        }

        public Builder customCache(DiskSnapshotCache customCache) {
            this.customCache = customCache;
            return this;
        }

        public Builder serverUrl(String serverUrl) {
            this.serverUrl = serverUrl;
            return this;
        }

        public Builder syncClient(ConfigSyncClient syncClient) {
            this.customSyncClient = syncClient;
            return this;
        }

        public Builder reconciliationIntervalSeconds(long seconds) {
            this.reconciliationIntervalSeconds = seconds;
            return this;
        }

        public MoveInSyncConfigClient build() {
            DiskSnapshotCache cache = customCache != null
                    ? customCache
                    : (diskCacheDir != null ? new FileBasedDiskSnapshotCache(diskCacheDir, clientId) : null);

            ConfigSyncClient sync = customSyncClient != null
                    ? customSyncClient
                    : (serverUrl != null ? new HttpConfigSyncClient(serverUrl) : null);

            return new MoveInSyncConfigClient(clientId, officeId, defaultShiftId, cache, sync, reconciliationIntervalSeconds);
        }
    }
}
