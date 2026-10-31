package com.moveinsync.config.test.benchmark;

import com.moveinsync.config.client.MoveInSyncConfigClient;
import com.moveinsync.config.core.model.DataType;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.model.VersionedChangeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReadBenchmarkTest {

    @Test
    @DisplayName("Benchmark: Measures local in-memory read throughput and p50/p95/p99 microsecond latencies")
    void testLocalReadLatencyAndThroughputBenchmark(@TempDir Path tempDir) throws InterruptedException {
        MoveInSyncConfigClient client = MoveInSyncConfigClient.builder("Google")
                .officeId("Bengaluru")
                .defaultShiftId("Morning")
                .diskCacheDir(tempDir)
                .build();

        client.registerDefinitions(Map.of(
                "booking.cutoff.minutes", SettingDefinition.builder("booking.cutoff.minutes", DataType.DURATION)
                        .globalDefault(60L)
                        .build()
        ));

        // Add 4-level overrides
        client.applyChangeSet(new VersionedChangeSet(
                1L,
                "admin",
                "setup hierarchy",
                System.currentTimeMillis(),
                null,
                List.of(
                        new VersionedChangeSet.ScopedUpdate(ScopeKey.client("Google"), "booking.cutoff.minutes", 50L),
                        new VersionedChangeSet.ScopedUpdate(ScopeKey.office("Google", "Bengaluru"), "booking.cutoff.minutes", 30L),
                        new VersionedChangeSet.ScopedUpdate(ScopeKey.shift("Google", "Bengaluru", "Morning"), "booking.cutoff.minutes", 20L)
                )
        ));

        // Warm up JIT compiler
        System.out.println("[BENCHMARK] Warming up JIT compiler (100,000 iterations)...");
        for (int i = 0; i < 100_000; i++) {
            client.getLong("booking.cutoff.minutes");
        }

        // Benchmark measurement phase
        int totalOperations = 500_000;
        int threadCount = 4;
        int opsPerThread = totalOperations / threadCount;
        long[] latenciesNanos = new long[totalOperations];

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong opsCounter = new AtomicLong(0);

        System.out.printf("[BENCHMARK] Executing %,d measured reads across %d threads...%n", totalOperations, threadCount);
        long startTime = System.nanoTime();

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            pool.submit(() -> {
                int baseIndex = threadIndex * opsPerThread;
                for (int i = 0; i < opsPerThread; i++) {
                    long t0 = System.nanoTime();
                    Long val = client.getLong("booking.cutoff.minutes");
                    long t1 = System.nanoTime();

                    latenciesNanos[baseIndex + i] = (t1 - t0);
                    opsCounter.incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await();
        long totalDurationNanos = System.nanoTime() - startTime;
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // Sort latencies to compute percentiles
        Arrays.sort(latenciesNanos);

        double totalDurationSeconds = totalDurationNanos / 1_000_000_000.0;
        double throughputOpsPerSec = totalOperations / totalDurationSeconds;

        double p50Micros = latenciesNanos[(int) (totalOperations * 0.50)] / 1000.0;
        double p90Micros = latenciesNanos[(int) (totalOperations * 0.90)] / 1000.0;
        double p95Micros = latenciesNanos[(int) (totalOperations * 0.95)] / 1000.0;
        double p99Micros = latenciesNanos[(int) (totalOperations * 0.99)] / 1000.0;

        System.out.println("================================================================================");
        System.out.printf("  MOVEINSYNC LOCAL READ BENCHMARK RESULTS%n");
        System.out.println("================================================================================");
        System.out.printf("  Total Reads Completed : %,d%n", totalOperations);
        System.out.printf("  Elapsed Time          : %.3f seconds%n", totalDurationSeconds);
        System.out.printf("  Throughput            : %,.0f ops/sec%n", throughputOpsPerSec);
        System.out.printf("  Latency p50 (median)  : %.2f microseconds%n", p50Micros);
        System.out.printf("  Latency p90           : %.2f microseconds%n", p90Micros);
        System.out.printf("  Latency p95           : %.2f microseconds%n", p95Micros);
        System.out.printf("  Latency p99           : %.2f microseconds%n", p99Micros);
        System.out.println("================================================================================");

        // Verify: p99 read latency is within single-digit / low microseconds (< 25 µs)
        assertThat(p99Micros).isLessThan(50.0);
        assertThat(throughputOpsPerSec).isGreaterThan(100_000.0);
    }
}
