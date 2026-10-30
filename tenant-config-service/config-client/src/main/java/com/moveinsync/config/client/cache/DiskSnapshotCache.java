package com.moveinsync.config.client.cache;

import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import java.util.Optional;

public interface DiskSnapshotCache {
    void save(ConfigSnapshot snapshot);
    Optional<ConfigSnapshot> load();
}
