package com.moveinsync.config.client.snapshot;

import com.moveinsync.config.client.cache.DiskSnapshotCache;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.model.VersionedChangeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class SnapshotManager {
    private static final Logger log = LoggerFactory.getLogger(SnapshotManager.class);

    private final AtomicReference<ConfigSnapshot> currentSnapshot;
    private final DiskSnapshotCache diskCache;

    public SnapshotManager(DiskSnapshotCache diskCache) {
        this.diskCache = diskCache;
        ConfigSnapshot initial = ConfigSnapshot.empty();

        if (diskCache != null) {
            Optional<ConfigSnapshot> cached = diskCache.load();
            if (cached.isPresent()) {
                initial = cached.get();
                log.info("Initialized SnapshotManager from disk cache with version {}", initial.getVersion());
            }
        }

        this.currentSnapshot = new AtomicReference<>(initial);
    }

    public ConfigSnapshot getSnapshot() {
        return currentSnapshot.get();
    }

    public long getVersion() {
        return currentSnapshot.get().getVersion();
    }

    /**
     * Atomically applies a full snapshot (e.g., initial sync or delta update).
     * Enforces strict monotonic versioning: rejects if newVersion <= currentVersion.
     */
    public synchronized boolean applyFullSnapshot(ConfigSnapshot newSnapshot) {
        ConfigSnapshot current = currentSnapshot.get();
        if (newSnapshot.getVersion() <= current.getVersion()) {
            log.warn("Rejected snapshot V{}: current version is already V{}. Versions must strictly move forward.",
                    newSnapshot.getVersion(), current.getVersion());
            return false;
        }

        currentSnapshot.set(newSnapshot);
        log.info("Transitioned snapshot: V{} -> V{}", current.getVersion(), newSnapshot.getVersion());

        if (diskCache != null) {
            diskCache.save(newSnapshot);
        }
        return true;
    }

    /**
     * Atomically applies a VersionedChangeSet containing multiple setting updates.
     * Guaranteed no torn reads: snapshot swap is atomic.
     */
    public synchronized boolean applyChangeSet(VersionedChangeSet changeSet) {
        ConfigSnapshot current = currentSnapshot.get();

        // 1. Monotonic version validation
        if (changeSet.getVersion() <= current.getVersion()) {
            log.warn("Rejected changeset V{}: current version is V{}. Versions must move forward only.",
                    changeSet.getVersion(), current.getVersion());
            return false;
        }

        // 2. Clone existing scoped values
        Map<String, Map<String, SettingValue>> newScopedValues = new HashMap<>();
        for (Map.Entry<String, Map<String, SettingValue>> entry : current.getScopedValues().entrySet()) {
            newScopedValues.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }

        // 3. Apply atomic updates
        long changeSetTimestamp = changeSet.getTimestamp() > 0 ? changeSet.getTimestamp() : System.currentTimeMillis();

        for (VersionedChangeSet.ScopedUpdate update : changeSet.getUpdates()) {
            String scopePath = update.getScopeKey().toPath();
            Map<String, SettingValue> scopeMap = newScopedValues.computeIfAbsent(scopePath, k -> new HashMap<>());

            if (update.isDelete()) {
                scopeMap.remove(update.getKey());
            } else {
                SettingValue val = new SettingValue(
                        update.getValue(),
                        update.getScopeKey(),
                        changeSet.getVersion(),
                        changeSetTimestamp
                );
                scopeMap.put(update.getKey(), val);
            }
        }

        // 4. Construct new immutable snapshot and atomic swap
        ConfigSnapshot nextSnapshot = new ConfigSnapshot(
                changeSet.getVersion(),
                changeSetTimestamp,
                current.getDefinitions(),
                newScopedValues
        );

        currentSnapshot.set(nextSnapshot);
        log.info("Applied changeset V{}: author='{}', reason='{}', updatesCount={}",
                changeSet.getVersion(), changeSet.getAuthor(), changeSet.getReason(), changeSet.getUpdates().size());

        if (diskCache != null) {
            diskCache.save(nextSnapshot);
        }

        return true;
    }

    public synchronized void registerDefinitions(Map<String, SettingDefinition> definitions) {
        ConfigSnapshot current = currentSnapshot.get();
        Map<String, SettingDefinition> newDefs = new HashMap<>(current.getDefinitions());
        newDefs.putAll(definitions);

        ConfigSnapshot updated = new ConfigSnapshot(
                current.getVersion(),
                current.getTimestamp(),
                newDefs,
                current.getScopedValues()
        );
        currentSnapshot.set(updated);
    }
}
