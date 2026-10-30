package com.moveinsync.config.client;

import com.moveinsync.config.client.cache.FileBasedDiskSnapshotCache;
import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.core.model.DataType;
import com.moveinsync.config.core.model.ExplainResult;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.ScopeLevel;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.model.TimeWindow;
import com.moveinsync.config.core.model.VersionedChangeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClientSdkSnapshotTest {

    @Test
    @DisplayName("SDK: Monotonic version guard strictly rejects out-of-order versions")
    void testMonotonicVersionGuard(@TempDir Path tempDir) {
        MoveInSyncConfigClient client = MoveInSyncConfigClient.builder("Google")
                .diskCacheDir(tempDir)
                .build();

        // 1. Apply initial V40
        boolean v40Applied = client.applySnapshot(new ConfigSnapshot(
                40L,
                System.currentTimeMillis(),
                Map.of("cutoff", SettingDefinition.builder("cutoff", DataType.DURATION).globalDefault(60L).build()),
                Map.of()
        ));
        assertThat(v40Applied).isTrue();
        assertThat(client.getCurrentVersion()).isEqualTo(40L);

        // 2. Advance to V43
        boolean v43Applied = client.applyChangeSet(new VersionedChangeSet(
                43L,
                "admin",
                "update",
                System.currentTimeMillis(),
                null,
                List.of(new VersionedChangeSet.ScopedUpdate(ScopeKey.client("Google"), "cutoff", 45L))
        ));
        assertThat(v43Applied).isTrue();
        assertThat(client.getCurrentVersion()).isEqualTo(43L);
        assertThat(client.getLong("cutoff")).isEqualTo(45L);

        // 3. Out-of-order version: V41 arrives after V43 -> Must be rejected!
        boolean v41Applied = client.applyChangeSet(new VersionedChangeSet(
                41L,
                "admin",
                "stale update",
                System.currentTimeMillis(),
                null,
                List.of(new VersionedChangeSet.ScopedUpdate(ScopeKey.client("Google"), "cutoff", 99L))
        ));
        assertThat(v41Applied).isFalse();
        assertThat(client.getCurrentVersion()).isEqualTo(43L);
        assertThat(client.getLong("cutoff")).isEqualTo(45L); // Unchanged!
    }

    @Test
    @DisplayName("SDK: Atomic changeset updates apply atomically with zero torn reads")
    void testAtomicMultiSettingChangeSet(@TempDir Path tempDir) {
        MoveInSyncConfigClient client = MoveInSyncConfigClient.builder("Google")
                .officeId("Bengaluru")
                .diskCacheDir(tempDir)
                .build();

        client.registerDefinitions(Map.of(
                "window.start", SettingDefinition.builder("window.start", DataType.NUMBER).globalDefault("20:00").build(),
                "window.end", SettingDefinition.builder("window.end", DataType.NUMBER).globalDefault("06:00").build()
        ));

        // Initial check
        assertThat(client.getString("window.start")).isEqualTo("20:00");
        assertThat(client.getString("window.end")).isEqualTo("06:00");

        // Apply atomic changeset modifying both keys together
        ScopeKey blrOffice = ScopeKey.office("Google", "Bengaluru");
        VersionedChangeSet changeSet = new VersionedChangeSet(
                1L,
                "ops-lead",
                "tighten night safety window",
                System.currentTimeMillis(),
                null,
                List.of(
                        new VersionedChangeSet.ScopedUpdate(blrOffice, "window.start", "21:00"),
                        new VersionedChangeSet.ScopedUpdate(blrOffice, "window.end", "05:00")
                )
        );

        boolean success = client.applyChangeSet(changeSet);
        assertThat(success).isTrue();

        // Both values updated atomically
        assertThat(client.getString("window.start")).isEqualTo("21:00");
        assertThat(client.getString("window.end")).isEqualTo("05:00");

        // Other office still sees defaults
        ScopeKey hydOffice = ScopeKey.office("Google", "Hyderabad");
        assertThat(client.getString("window.start", hydOffice)).isEqualTo("20:00");
        assertThat(client.getString("window.end", hydOffice)).isEqualTo("06:00");
    }

    @Test
    @DisplayName("Resilience: Cold-start offline boot loads successfully from local disk cache")
    void testColdStartDiskRecovery(@TempDir Path tempDir) {
        // Step 1: Create client, apply data, which writes to disk cache
        {
            MoveInSyncConfigClient client1 = MoveInSyncConfigClient.builder("Amazon")
                    .diskCacheDir(tempDir)
                    .build();

            client1.registerDefinitions(Map.of(
                    "escort.mandatory", SettingDefinition.builder("escort.mandatory", DataType.ENUM).globalDefault("NO").build()
            ));

            client1.applyChangeSet(new VersionedChangeSet(
                    5L,
                    "security-head",
                    "enable escort",
                    System.currentTimeMillis(),
                    null,
                    List.of(new VersionedChangeSet.ScopedUpdate(ScopeKey.client("Amazon"), "escort.mandatory", "YES"))
            ));

            assertThat(client1.getString("escort.mandatory")).isEqualTo("YES");
            assertThat(client1.getCurrentVersion()).isEqualTo(5L);
        }

        // Step 2: Simulate complete server failure and microservice restart
        // Instantiate a brand new client with the same cache directory
        {
            MoveInSyncConfigClient client2 = MoveInSyncConfigClient.builder("Amazon")
                    .diskCacheDir(tempDir)
                    .build();

            // Should immediately boot from disk snapshot V5
            assertThat(client2.getCurrentVersion()).isEqualTo(5L);
            assertThat(client2.getString("escort.mandatory")).isEqualTo("YES");
        }
    }
}
