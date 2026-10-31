package com.moveinsync.config.server.repository;

import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.model.VersionedChangeSet;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ConfigRepository {
    void saveDefinition(SettingDefinition definition);
    Optional<SettingDefinition> getDefinition(String key);
    Map<String, SettingDefinition> getAllDefinitions();

    long getLatestVersion();
    long incrementAndGetVersion();

    void appendChangeSet(VersionedChangeSet changeSet);
    List<VersionedChangeSet> getChangeSetsSince(long sinceVersion);
    Optional<VersionedChangeSet> getChangeSetByVersion(long version);

    void saveSnapshot(ConfigSnapshot snapshot);
    ConfigSnapshot getLatestSnapshot();
    Optional<ConfigSnapshot> getSnapshotAtVersion(long version);
}
