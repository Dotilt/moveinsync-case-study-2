package com.moveinsync.config.test.failure;

import com.moveinsync.config.client.MoveInSyncConfigClient;
import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.client.sync.ConfigSyncClient;
import com.moveinsync.config.core.model.DataType;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.VersionedChangeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class KillTheServerTest {

    @Test
    @DisplayName("Kill-the-Server Test: Reads never fail during outage; state preserved and healed on return")
    void testKillTheServerScenario(@TempDir Path tempDir) {
        FailableSyncClient mockServer = new FailableSyncClient();

        // 1. Client starts up, connects to server, and receives initial configs
        MoveInSyncConfigClient client = MoveInSyncConfigClient.builder("Google")
                .officeId("Bengaluru")
                .diskCacheDir(tempDir)
                .syncClient(mockServer)
                .build();

        client.registerDefinitions(Map.of(
                "booking.cutoff.minutes", SettingDefinition.builder("booking.cutoff.minutes", DataType.DURATION)
                        .globalDefault(60L)
                        .build()
        ));

        // Initial setting is 60 minutes
        assertThat(client.getLong("booking.cutoff.minutes")).isEqualTo(60L);

        // Operator updates Bengaluru cutoff to 30 mins
        ScopeKey blrOffice = ScopeKey.office("Google", "Bengaluru");
        VersionedChangeSet update1 = new VersionedChangeSet(
                1L,
                "operator",
                "tighten cutoff",
                System.currentTimeMillis(),
                null,
                List.of(new VersionedChangeSet.ScopedUpdate(blrOffice, "booking.cutoff.minutes", 30L))
        );
        mockServer.publishChange(update1);
        assertThat(client.getLong("booking.cutoff.minutes")).isEqualTo(30L);

        // 2. SIMULATE DISASTER: The Central Config Server is completely KILLED!
        System.out.println("[KILL-THE-SERVER] Simulating central config server process crash...");
        mockServer.killServer();

        // 3. Downstream services continue answering reads uninterrupted with ZERO exceptions
        for (int i = 0; i < 1000; i++) {
            Long cutoff = client.getLong("booking.cutoff.minutes");
            assertThat(cutoff).isEqualTo(30L); // Still serving fast in-memory snapshot
        }

        // 4. Cold-start test during server downtime: Another microservice instance boots up
        MoveInSyncConfigClient offlineClient = MoveInSyncConfigClient.builder("Google")
                .officeId("Bengaluru")
                .diskCacheDir(tempDir)
                .syncClient(mockServer) // Server is dead!
                .build();

        // Must load transparently from disk cache with version 1 and cutoff 30L
        assertThat(offlineClient.getCurrentVersion()).isEqualTo(1L);
        assertThat(offlineClient.getLong("booking.cutoff.minutes")).isEqualTo(30L);

        // 5. SERVER RESTARTS: Server returns and pushes new update V2
        System.out.println("[KILL-THE-SERVER] Central config server recovered. Pushing new changes...");
        mockServer.restartServer();

        VersionedChangeSet update2 = new VersionedChangeSet(
                2L,
                "admin",
                "update after server reboot",
                System.currentTimeMillis(),
                null,
                List.of(new VersionedChangeSet.ScopedUpdate(blrOffice, "booking.cutoff.minutes", 20L))
        );
        mockServer.publishChange(update2);

        // Client detects server return and updates to V2 within bounded staleness
        client.triggerManualReconcile();
        assertThat(client.getCurrentVersion()).isEqualTo(2L);
        assertThat(client.getLong("booking.cutoff.minutes")).isEqualTo(20L);

        client.close();
        offlineClient.close();
    }

    static class FailableSyncClient implements ConfigSyncClient {
        private final AtomicBoolean isAlive = new AtomicBoolean(true);
        private final AtomicLong serverVersion = new AtomicLong(0);
        private Consumer<VersionedChangeSet> sseConsumer;
        private VersionedChangeSet latestChangeSet;

        public void killServer() {
            isAlive.set(false);
        }

        public void restartServer() {
            isAlive.set(true);
        }

        public void publishChange(VersionedChangeSet changeSet) {
            this.serverVersion.set(changeSet.getVersion());
            this.latestChangeSet = changeSet;
            if (isAlive.get() && sseConsumer != null) {
                sseConsumer.accept(changeSet);
            }
        }

        @Override
        public long fetchLatestVersion(String clientId) throws Exception {
            if (!isAlive.get()) throw new RuntimeException("503 Service Unavailable: Config server down");
            return serverVersion.get();
        }

        @Override
        public ConfigSnapshot fetchFullSnapshot(String clientId) throws Exception {
            if (!isAlive.get()) throw new RuntimeException("503 Service Unavailable");
            return ConfigSnapshot.empty();
        }

        @Override
        public List<VersionedChangeSet> fetchDelta(String clientId, long sinceVersion) throws Exception {
            if (!isAlive.get()) throw new RuntimeException("503 Service Unavailable");
            if (latestChangeSet != null && latestChangeSet.getVersion() > sinceVersion) {
                return List.of(latestChangeSet);
            }
            return Collections.emptyList();
        }

        @Override
        public void startStream(String clientId, Consumer<VersionedChangeSet> onEvent, Consumer<Throwable> onError) {
            this.sseConsumer = onEvent;
        }

        @Override
        public void close() {}
    }
}
