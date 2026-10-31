package com.moveinsync.config.server;

import com.moveinsync.config.core.model.DataType;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.TimeWindow;
import com.moveinsync.config.core.model.VersionedChangeSet;
import com.moveinsync.config.core.validation.ValidationException;
import com.moveinsync.config.server.security.SecurityContext;
import com.moveinsync.config.server.security.UserRole;
import com.moveinsync.config.server.service.ConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ServerIntegrationTest {

    @Autowired
    private ConfigManagementService configService;

    @BeforeEach
    void setupSecurity() {
        SecurityContext.setContext(new SecurityContext.RequestContext("SYSTEM", UserRole.ADMIN, "admin-user"));

        // Register default schema
        configService.registerDefinition(SettingDefinition.builder("booking.cutoff.minutes", DataType.DURATION)
                .description("Cutoff time in minutes before cab departure")
                .globalDefault(60L)
                .range(5.0, 360.0)
                .build());

        configService.registerDefinition(SettingDefinition.builder("night.window", DataType.TIME_WINDOW)
                .description("Night safety window")
                .globalDefault(new TimeWindow("20:00", "06:00"))
                .build());
    }

    @Test
    @DisplayName("Pre-Commit Rejection: Invalid cut-off value (-30 mins) is rejected before publishing")
    void testPreCommitValidation() {
        assertThatThrownBy(() -> configService.commitChangeSet(
                "operator",
                "test bad value",
                null,
                List.of(new VersionedChangeSet.ScopedUpdate(
                        ScopeKey.office("Google", "Bengaluru"),
                        "booking.cutoff.minutes",
                        -30L // Invalid!
                ))
        )).isInstanceOf(ValidationException.class)
          .hasMessageContaining("cannot be negative");
    }

    @Test
    @DisplayName("Tenant Isolation: Operator of Tenant A cannot modify Tenant B configurations")
    void testTenantIsolation() {
        // Set Security Context as Operator for Tenant 'Google'
        SecurityContext.setContext(new SecurityContext.RequestContext("Google", UserRole.OPERATOR, "google-op"));

        // Attempt to update Amazon office override
        assertThatThrownBy(() -> configService.commitChangeSet(
                "google-op",
                "malicious update",
                null,
                List.of(new VersionedChangeSet.ScopedUpdate(
                        ScopeKey.office("Amazon", "Bengaluru"),
                        "booking.cutoff.minutes",
                        45L
                ))
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("Tenant isolation violation");
    }

    @Test
    @DisplayName("Instant Rollback with Diff: Reverting to earlier version produces accurate diff and advances version")
    void testInstantRollbackWithDiff() {
        SecurityContext.setContext(new SecurityContext.RequestContext("SYSTEM", UserRole.ADMIN, "admin"));

        long v1 = configService.getLatestVersion();

        // 1. Commit V_next setting Bengaluru cutoff = 30
        ScopeKey blrOffice = ScopeKey.office("Google", "Bengaluru");
        VersionedChangeSet cs = configService.commitChangeSet(
                "admin",
                "set blr cutoff",
                null,
                List.of(new VersionedChangeSet.ScopedUpdate(blrOffice, "booking.cutoff.minutes", 30L))
        );
        long v2 = cs.getVersion();
        assertThat(configService.explain("booking.cutoff.minutes", blrOffice).getResolvedValue()).isEqualTo(30L);

        // 2. Rollback to V1
        ConfigManagementService.RollbackResult rollback = configService.rollbackToVersion(v1, "admin", "reverting to original");

        // Version moves monotonically forward (v3)
        assertThat(rollback.getNewVersion()).isGreaterThan(v2);
        assertThat(rollback.getRolledBackToVersion()).isEqualTo(v1);
        assertThat(rollback.getDiff().getChanges()).isNotEmpty();

        // Active resolved value for Bengaluru is now back to default 60L
        assertThat(configService.explain("booking.cutoff.minutes", blrOffice).getResolvedValue()).isEqualTo(60L);
    }
}
