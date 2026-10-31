package com.moveinsync.config.test.benchmark;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class LivePropagation200ClientsTest {

    @Test
    @DisplayName("Scale Benchmark: Live propagation to 200 concurrent simulated clients within <= 5s bounded staleness")
    void testLivePropagationAcross200Clients(@TempDir Path tempDir) throws InterruptedException {
        int clientCount = 200;
        MockCentralBroker broker = new MockCentralBroker();
        List<MoveInSyncConfigClient> clients = new ArrayList<>();
        CountDownLatch propagationLatch = new CountDownLatch(clientCount);
        long[] arrivalTimestamps = new long[clientCount];

        System.out.printf("[SCALE TEST] Spawning %d connected microservice client instances...%n", clientCount);

        for (int i = 0; i < clientCount; i++) {
            final int clientIndex = i;
            String officeId = "Office-" + (i % 10);

            MoveInSyncConfigClient client = MoveInSyncConfigClient.builder("MoveInSync-Fleet")
                    .officeId(officeId)
                    .diskCacheDir(tempDir.resolve("client-" + i))
                    .syncClient(new ClientBrokerAdapter(broker, changeSet -> {
                        arrivalTimestamps[clientIndex] = System.currentTimeMillis();
                        propagationLatch.countDown();
                    }))
                    .reconciliationIntervalSeconds(60) // rely on live push
                    .build();

            client.registerDefinitions(Map.of(
                    "booking.cutoff.minutes", SettingDefinition.builder("booking.cutoff.minutes", DataType.DURATION)
                            .globalDefault(60L)
                            .build()
            ));

            clients.add(client);
        }

        // Verify all 200 clients initially hold default 60L
        for (MoveInSyncConfigClient c : clients) {
            assertThat(c.getLong("booking.cutoff.minutes")).isEqualTo(60L);
        }

        // PUBLISH LIVE CHANGE on Server: Operator changes cutoff to 30 mins
        long publishTimestamp = System.currentTimeMillis();
        VersionedChangeSet publishedUpdate = new VersionedChangeSet(
                1L,
                "fleet-admin",
                "tighten global cutoff to 30 mins",
                publishTimestamp,
                null,
                List.of(new VersionedChangeSet.ScopedUpdate(
                        ScopeKey.global(),
                        "booking.cutoff.minutes",
                        30L
                ))
        );

        System.out.printf("[SCALE TEST] Broadcasting changeset V1 to %d clients...%n", clientCount);
        broker.broadcast(publishedUpdate);

        // Await full propagation
        boolean allReached = propagationLatch.await(5, TimeUnit.SECONDS);
        assertThat(allReached).as("All 200 clients must receive the update within 5 seconds").isTrue();

        // Calculate latency metrics
        long maxDelayMs = 0;
        long totalDelayMs = 0;
        for (int i = 0; i < clientCount; i++) {
            long delay = arrivalTimestamps[i] - publishTimestamp;
            if (delay > maxDelayMs) maxDelayMs = delay;
            totalDelayMs += delay;
            // Verify client snapshot state
            assertThat(clients.get(i).getLong("booking.cutoff.minutes")).isEqualTo(30L);
            assertThat(clients.get(i).getCurrentVersion()).isEqualTo(1L);
        }

        double avgDelayMs = (double) totalDelayMs / clientCount;

        System.out.println("================================================================================");
        System.out.printf("  200 CONCURRENT CLIENTS LIVE PROPAGATION BENCHMARK RESULTS%n");
        System.out.println("================================================================================");
        System.out.printf("  Total Connected Clients : %d%n", clientCount);
        System.out.printf("  Convergence Rate        : 100%% (%d/%d clients)%n", clientCount, clientCount);
        System.out.printf("  Average Push Latency    : %.2f ms%n", avgDelayMs);
        System.out.printf("  Max Propagation Delay   : %d ms (Target: <= 5,000 ms)%n", maxDelayMs);
        System.out.println("================================================================================");

        assertThat(maxDelayMs).isLessThanOrEqualTo(5000L); // Bounded staleness guarantee

        for (MoveInSyncConfigClient c : clients) {
            c.close();
        }
    }

    static class MockCentralBroker {
        private final List<Consumer<VersionedChangeSet>> subscribers = new CopyOnWriteArrayList<>();

        public void subscribe(Consumer<VersionedChangeSet> listener) {
            subscribers.add(listener);
        }

        public void broadcast(VersionedChangeSet changeSet) {
            // Push in parallel to simulated subscribers
            subscribers.parallelStream().forEach(s -> s.accept(changeSet));
        }
    }

    static class ClientBrokerAdapter implements ConfigSyncClient {
        private final MockCentralBroker broker;
        private final Consumer<VersionedChangeSet> onArrivalCallback;

        ClientBrokerAdapter(MockCentralBroker broker, Consumer<VersionedChangeSet> onArrivalCallback) {
            this.broker = broker;
            this.onArrivalCallback = onArrivalCallback;
        }

        @Override
        public long fetchLatestVersion(String clientId) { return 0; }

        @Override
        public ConfigSnapshot fetchFullSnapshot(String clientId) { return ConfigSnapshot.empty(); }

        @Override
        public List<VersionedChangeSet> fetchDelta(String clientId, long sinceVersion) { return Collections.emptyList(); }

        @Override
        public void startStream(String clientId, Consumer<VersionedChangeSet> onEvent, Consumer<Throwable> onError) {
            broker.subscribe(cs -> {
                onEvent.accept(cs);
                if (onArrivalCallback != null) {
                    onArrivalCallback.accept(cs);
                }
            });
        }

        @Override
        public void close() {}
    }
}
