package com.moveinsync.config.server.repository;

import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.VersionedChangeSet;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Repository
public class InMemoryConfigRepository implements ConfigRepository {
    private final Map<String, SettingDefinition> definitions = new ConcurrentHashMap<>();
    private final List<VersionedChangeSet> changeSets = new CopyOnWriteArrayList<>();
    private final Map<Long, ConfigSnapshot> versionSnapshots = new ConcurrentHashMap<>();
    private final AtomicReference<ConfigSnapshot> latestSnapshot = new AtomicReference<>(ConfigSnapshot.empty());
    private final AtomicLong versionSequence = new AtomicLong(0);

    @Override
    public void saveDefinition(SettingDefinition definition) {
        definitions.put(definition.getKey(), definition);
    }

    @Override
    public Optional<SettingDefinition> getDefinition(String key) {
        return Optional.ofNullable(definitions.get(key));
    }

    @Override
    public Map<String, SettingDefinition> getAllDefinitions() {
        return Map.copyOf(definitions);
    }

    @Override
    public long getLatestVersion() {
        return versionSequence.get();
    }

    @Override
    public long incrementAndGetVersion() {
        return versionSequence.incrementAndGet();
    }

    @Override
    public void appendChangeSet(VersionedChangeSet changeSet) {
        changeSets.add(changeSet);
    }

    @Override
    public List<VersionedChangeSet> getChangeSetsSince(long sinceVersion) {
        List<VersionedChangeSet> result = new ArrayList<>();
        for (VersionedChangeSet cs : changeSets) {
            if (cs.getVersion() > sinceVersion) {
                result.add(cs);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Optional<VersionedChangeSet> getChangeSetByVersion(long version) {
        return changeSets.stream().filter(cs -> cs.getVersion() == version).findFirst();
    }

    @Override
    public void saveSnapshot(ConfigSnapshot snapshot) {
        latestSnapshot.set(snapshot);
        versionSnapshots.put(snapshot.getVersion(), snapshot);
    }

    @Override
    public ConfigSnapshot getLatestSnapshot() {
        return latestSnapshot.get();
    }

    @Override
    public Optional<ConfigSnapshot> getSnapshotAtVersion(long version) {
        return Optional.ofNullable(versionSnapshots.get(version));
    }
}
