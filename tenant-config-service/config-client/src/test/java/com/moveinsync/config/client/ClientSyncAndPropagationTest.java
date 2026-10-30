package com.moveinsync.config.client;

import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.client.sync.BackoffStrategy;
import com.moveinsync.config.client.sync.ConfigSyncClient;
import com.moveinsync.config.core.model.DataType;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.VersionedChangeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ClientSyncAndPropagationTest {

    @Test
    @DisplayName("Jittered Backoff: verifies exponential scaling with randomized bounds")
    void testJitteredBackoffStrategy() {
        BackoffStrategy strategy = new BackoffStrategy(100, 1000, 0.5);

        long delay1 = strategy.nextDelayMillis();
        long delay2 = strategy.nextDelayMillis();
        long delay3 = strategy.nextDelayMillis();

        assertThat(delay1).isBetween(50L, 150L);
        assertThat(delay2).isBetween(100L, 300L);
        assertThat(delay3).isBetween(200L, 600L);

        strategy.reset();
        assertThat(strategy.getAttempt()).isEqualTo(0);
    }

    @Test
    @DisplayName("Live Push: delivers streamed changesets directly into snapshot")
    void testLivePushStream(@TempDir Path tempDir) {
        MockSyncClient mockSync = new MockSyncClient();

        MoveInSyncConfigClient client = MoveInSyncConfigClient.builder("Google")
                .diskCacheDir(tempDir)
                .syncClient(mockSync)
                .reconciliationIntervalSeconds(60) // Disable periodic interference
                .build();

        client.registerDefinitions(Map.of(
                "cutoff", SettingDefinition.builder("cutoff", DataType.DURATION).globalDefault(60L).build()
        ));

        // Initial default
        assertThat(client.getLong("cutoff")).isEqualTo(60L);

        // Simulate live push event from server
        VersionedChangeSet pushEvent = new VersionedChangeSet(
                1L,
                "ops",
                "tighten cutoff",
                System.currentTimeMillis(),
                null,
                List.of(new VersionedChangeSet.ScopedUpdate(ScopeKey.client("Google"), "cutoff", 30L))
        );

        mockSync.triggerPushEvent(pushEvent);

        // Instantly available in memory without network fetch
        assertThat(client.getCurrentVersion()).isEqualTo(1L);
        assertThat(client.getLong("cutoff")).isEqualTo(30L);

        client.close();
    }

    @Test
    @DisplayName("Bounded Staleness: periodic reconciliation auto-heals missed push events via delta")
    void testBoundedStalenessHealing(@TempDir Path tempDir) {
        MockSyncClient mockSync = new MockSyncClient();

        MoveInSyncConfigClient client = MoveInSyncConfigClient.builder("Google")
                .diskCacheDir(tempDir)
                .syncClient(mockSync)
                .reconciliationIntervalSeconds(60)
                .build();

        client.registerDefinitions(Map.of(
                "escort.rules", SettingDefinition.builder("escort.rules", DataType.ENUM).globalDefault("OPTIONAL").build()
        ));

        // Push event dropped or missed over network!
        // Server version advances to V2, but client is at V0
        mockSync.serverVersion.set(2L);
        mockSync.deltaQueue.add(new VersionedChangeSet(
                2L,
                "security",
                "mandate escorts",
                System.currentTimeMillis(),
                null,
                List.of(new VersionedChangeSet.ScopedUpdate(ScopeKey.client("Google"), "escort.rules", "MANDATORY"))
        ));

        // Trigger reconciliation check (as happens every 3-5 seconds in background)
        client.triggerManualReconcile();

        // Client detects server version > local version, pulls delta, and heals!
        assertThat(client.getCurrentVersion()).isEqualTo(2L);
        assertThat(client.getString("escort.rules")).isEqualTo("MANDATORY");

        client.close();
    }

    static class MockSyncClient implements ConfigSyncClient {
        final AtomicLong serverVersion = new AtomicLong(0);
        final List<VersionedChangeSet> deltaQueue = new ArrayList<>();
        Consumer<VersionedChangeSet> pushConsumer;

        @Override
        public long fetchLatestVersion(String clientId) {
            return serverVersion.get();
        }

        @Override
        public ConfigSnapshot fetchFullSnapshot(String clientId) {
            return new ConfigSnapshot(serverVersion.get(), System.currentTimeMillis(), Collections.emptyMap(), Collections.emptyMap());
        }

        @Override
        public List<VersionedChangeSet> fetchDelta(String clientId, long sinceVersion) {
            return List.copyOf(deltaQueue);
        }

        @Override
        public void startStream(String clientId, Consumer<VersionedChangeSet> onEvent, Consumer<Throwable> onError) {
            this.pushConsumer = onEvent;
        }

        public void triggerPushEvent(VersionedChangeSet event) {
            serverVersion.set(event.getVersion());
            if (pushConsumer != null) {
                pushConsumer.accept(event);
            }
        }

        @Override
        public void close() {}
    }
}
