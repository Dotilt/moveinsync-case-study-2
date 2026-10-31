package com.moveinsync.config.test.concurrency;

import com.moveinsync.config.client.MoveInSyncConfigClient;
import com.moveinsync.config.core.model.DataType;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.VersionedChangeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicChangeConcurrencyTest {

    @Test
    @DisplayName("Concurrency: Readers reading continuously during changesets NEVER see mixed states")
    void testContinuousReadsUnderAtomicChanges(@TempDir Path tempDir) throws InterruptedException {
        MoveInSyncConfigClient client = MoveInSyncConfigClient.builder("MoveInSync-Enterprise")
                .diskCacheDir(tempDir)
                .build();

        client.registerDefinitions(Map.of(
                "night.window.start", SettingDefinition.builder("night.window.start", DataType.NUMBER).globalDefault("20:00").build(),
                "night.window.end", SettingDefinition.builder("night.window.end", DataType.NUMBER).globalDefault("06:00").build()
        ));

        // State A: ("20:00", "06:00")
        // State B: ("22:00", "04:00")
        ScopeKey global = ScopeKey.global();

        int numReaders = 8;
        int numWriters = 2;
        ExecutorService threadPool = Executors.newFixedThreadPool(numReaders + numWriters);

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong totalReads = new AtomicLong(0);
        AtomicLong tornReads = new AtomicLong(0);
        AtomicLong writerChanges = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);

        // 1. Reader threads: Continuously query both keys and assert state integrity
        for (int i = 0; i < numReaders; i++) {
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    while (running.get()) {
                        // Capture snapshot reference once for atomic multi-setting evaluation
                        com.moveinsync.config.client.snapshot.ConfigSnapshot snapshot = client.getSnapshot();
                        String start = snapshot.resolve("night.window.start", global) != null ? snapshot.resolve("night.window.start", global).toString() : null;
                        String end = snapshot.resolve("night.window.end", global) != null ? snapshot.resolve("night.window.end", global).toString() : null;

                        boolean isStateA = "20:00".equals(start) && "06:00".equals(end);
                        boolean isStateB = "22:00".equals(start) && "04:00".equals(end);

                        if (!isStateA && !isStateB) {
                            // Witnessed torn state! (e.g. 20:00 with 04:00 or 22:00 with 06:00)
                            tornReads.incrementAndGet();
                        }
                        totalReads.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        // 2. Writer threads: Rapidly toggle between State A and State B via atomic VersionedChangeSets
        for (int w = 0; w < numWriters; w++) {
            final int writerId = w;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    long version = 1;
                    while (running.get()) {
                        boolean toStateB = (version % 2 != 0);
                        String newStart = toStateB ? "22:00" : "20:00";
                        String newEnd = toStateB ? "04:00" : "06:00";

                        long currentVer = writerChanges.incrementAndGet();
                        VersionedChangeSet cs = new VersionedChangeSet(
                                currentVer,
                                "writer-" + writerId,
                                "toggle atomic window",
                                System.currentTimeMillis(),
                                null,
                                List.of(
                                        new VersionedChangeSet.ScopedUpdate(global, "night.window.start", newStart),
                                        new VersionedChangeSet.ScopedUpdate(global, "night.window.end", newEnd)
                                )
                        );
                        client.applyChangeSet(cs);
                        version++;
                        Thread.yield();
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        // Start all threads concurrently
        startLatch.countDown();

        // Run heavy concurrent read/write test for 2 seconds
        Thread.sleep(2000);
        running.set(false);

        threadPool.shutdown();
        threadPool.awaitTermination(5, TimeUnit.SECONDS);

        System.out.printf("[CONCURRENCY TEST RESULTS] Total reads: %,d | Writer updates: %,d | Torn/mixed reads: %d%n",
                totalReads.get(), writerChanges.get(), tornReads.get());

        // PROVE IT: Assert that across all concurrent reads, ZERO torn states were ever observed
        assertThat(totalReads.get()).isGreaterThan(100_000L);
        assertThat(tornReads.get()).isEqualTo(0L);
    }
}
