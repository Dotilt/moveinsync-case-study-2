package com.moveinsync.config.client.sync;

import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.core.model.VersionedChangeSet;

import java.io.Closeable;
import java.util.List;
import java.util.function.Consumer;

public interface ConfigSyncClient extends Closeable {
    long fetchLatestVersion(String clientId) throws Exception;
    ConfigSnapshot fetchFullSnapshot(String clientId) throws Exception;
    List<VersionedChangeSet> fetchDelta(String clientId, long sinceVersion) throws Exception;
    void startStream(String clientId, Consumer<VersionedChangeSet> onEvent, Consumer<Throwable> onError);
}
