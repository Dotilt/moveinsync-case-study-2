package com.moveinsync.config.client.sync;

import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.client.snapshot.SnapshotManager;
import com.moveinsync.config.core.model.VersionedChangeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConfigSynchronizer implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(ConfigSynchronizer.class);

    private final String clientId;
    private final SnapshotManager snapshotManager;
    private final ConfigSyncClient syncClient;
    private final BackoffStrategy backoffStrategy;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final long reconciliationIntervalSeconds;

    public ConfigSynchronizer(
            String clientId,
            SnapshotManager snapshotManager,
            ConfigSyncClient syncClient,
            BackoffStrategy backoffStrategy,
            long reconciliationIntervalSeconds) {
        this.clientId = clientId;
        this.snapshotManager = snapshotManager;
        this.syncClient = syncClient;
        this.backoffStrategy = backoffStrategy != null ? backoffStrategy : BackoffStrategy.defaultStrategy();
        this.reconciliationIntervalSeconds = reconciliationIntervalSeconds > 0 ? reconciliationIntervalSeconds : 3;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "MoveInSync-SyncThread");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (isRunning.compareAndSet(false, true)) {
            log.info("Starting ConfigSynchronizer for client '{}'", clientId);
            // 1. Initial snapshot / reconciliation
            performReconciliation();

            // 2. Start persistent push stream with auto-reconnect
            connectStreamWithRetry();

            // 3. Periodic bounded-staleness reconciliation loop (<= 5s)
            scheduler.scheduleWithFixedDelay(
                    this::safeReconcile,
                    reconciliationIntervalSeconds,
                    reconciliationIntervalSeconds,
                    TimeUnit.SECONDS
            );
        }
    }

    private void connectStreamWithRetry() {
        if (!isRunning.get()) return;

        log.debug("Connecting SSE push stream for client '{}'", clientId);
        syncClient.startStream(
                clientId,
                changeSet -> {
                    backoffStrategy.reset();
                    log.debug("Received live push changeset V{}", changeSet.getVersion());
                    snapshotManager.applyChangeSet(changeSet);
                },
                error -> {
                    if (!isRunning.get()) return;
                    long delay = backoffStrategy.nextDelayMillis();
                    log.warn("SSE stream disconnected ({}), reconnecting in {} ms with jitter...",
                            error.getMessage(), delay);
                    scheduler.schedule(this::connectStreamWithRetry, delay, TimeUnit.MILLISECONDS);
                }
        );
    }

    private void safeReconcile() {
        try {
            performReconciliation();
        } catch (Exception e) {
            log.warn("Reconciliation cycle encountered error: {}", e.getMessage());
        }
    }

    public synchronized void performReconciliation() {
        try {
            long currentVersion = snapshotManager.getVersion();
            long remoteVersion = syncClient.fetchLatestVersion(clientId);

            if (remoteVersion > currentVersion) {
                log.info("Reconciliation triggered: local V{}, remote V{}. Fetching delta...",
                        currentVersion, remoteVersion);

                List<VersionedChangeSet> deltas = syncClient.fetchDelta(clientId, currentVersion);
                if (deltas != null && !deltas.isEmpty()) {
                    for (VersionedChangeSet cs : deltas) {
                        snapshotManager.applyChangeSet(cs);
                    }
                } else {
                    ConfigSnapshot full = syncClient.fetchFullSnapshot(clientId);
                    if (full != null && full.getVersion() > currentVersion) {
                        snapshotManager.applyFullSnapshot(full);
                    }
                }
                log.info("Reconciliation complete. Snapshot is now at V{}", snapshotManager.getVersion());
            }
        } catch (Exception e) {
            log.debug("Unable to reconcile with config server: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (isRunning.compareAndSet(true, false)) {
            log.info("Shutting down ConfigSynchronizer");
            scheduler.shutdownNow();
            try {
                syncClient.close();
            } catch (Exception ignored) {}
        }
    }
}
