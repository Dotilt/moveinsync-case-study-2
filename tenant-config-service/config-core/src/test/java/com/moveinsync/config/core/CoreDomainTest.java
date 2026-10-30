package com.moveinsync.config.core;

import com.moveinsync.config.core.diff.ConfigDiff;
import com.moveinsync.config.core.diff.DiffCalculator;
import com.moveinsync.config.core.hierarchy.ConfigTree;
import com.moveinsync.config.core.model.DataType;
import com.moveinsync.config.core.model.ExplainResult;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.ScopeLevel;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.model.TimeWindow;
import com.moveinsync.config.core.validation.SettingValidator;
import com.moveinsync.config.core.validation.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreDomainTest {

    private ConfigTree configTree;

    @BeforeEach
    void setup() {
        configTree = new ConfigTree();

        // 1. Booking Cutoff (DURATION in minutes)
        configTree.registerDefinition(SettingDefinition.builder("booking.cutoff.minutes", DataType.DURATION)
                .description("Cutoff time in minutes before departure")
                .globalDefault(60L)
                .range(5.0, 720.0)
                .build());

        // 2. Night Safety Window (TIME_WINDOW)
        configTree.registerDefinition(SettingDefinition.builder("night.safety.window", DataType.TIME_WINDOW)
                .description("Window where escort rules apply")
                .globalDefault(new TimeWindow("20:00", "06:00"))
                .build());

        // 3. Allowed Cab Types (ENUM)
        configTree.registerDefinition(SettingDefinition.builder("cab.allowed.type", DataType.ENUM)
                .description("Permitted vehicle type")
                .allowedValues(List.of("SEDAN", "SUV", "HATCHBACK"))
                .globalDefault("SEDAN")
                .build());
    }

    @Test
    @DisplayName("Validation: should accept valid duration and reject negative or out-of-bounds duration")
    void testDurationValidation() {
        SettingDefinition def = configTree.getDefinition("booking.cutoff.minutes");

        Object valid = SettingValidator.validateAndNormalize(def, 45);
        assertThat(valid).isEqualTo(45L);

        // Negative duration rejection
        assertThatThrownBy(() -> SettingValidator.validateAndNormalize(def, -30))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot be negative");

        // Below minimum (min is 5)
        assertThatThrownBy(() -> SettingValidator.validateAndNormalize(def, 2))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("less than minimum");
    }

    @Test
    @DisplayName("Validation: should accept valid time window and reject invalid formats or equal start/end")
    void testTimeWindowValidation() {
        SettingDefinition def = configTree.getDefinition("night.safety.window");

        Object valid = SettingValidator.validateAndNormalize(def, "21:00-05:00");
        assertThat(valid).isInstanceOf(TimeWindow.class);
        assertThat(((TimeWindow) valid).getStart()).isEqualTo("21:00");
        assertThat(((TimeWindow) valid).getEnd()).isEqualTo("05:00");

        // Equal start and end
        assertThatThrownBy(() -> SettingValidator.validateAndNormalize(def, "20:00-20:00"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot be identical");
    }

    @Test
    @DisplayName("Validation: should reject unauthorized enum values")
    void testEnumValidation() {
        SettingDefinition def = configTree.getDefinition("cab.allowed.type");

        Object valid = SettingValidator.validateAndNormalize(def, "suv");
        assertThat(valid).isEqualTo("SUV"); // Normalized to uppercase

        assertThatThrownBy(() -> SettingValidator.validateAndNormalize(def, "MOTORCYCLE"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("is not permitted");
    }

    @Test
    @DisplayName("Hierarchy Resolution: SHIFT > OFFICE > CLIENT > GLOBAL precedence resolution")
    void testHierarchicalPrecedence() {
        String key = "booking.cutoff.minutes";

        // Baseline: No overrides -> resolves to GLOBAL default (60)
        ScopeKey shiftScope = ScopeKey.shift("Google", "Bengaluru", "NightShift");
        assertThat(configTree.resolve(key, shiftScope)).isEqualTo(60L);

        // 1. Set CLIENT override = 50
        configTree.putValue(ScopeKey.client("Google"), key,
                new SettingValue(50L, ScopeKey.client("Google"), 1, System.currentTimeMillis()));
        assertThat(configTree.resolve(key, shiftScope)).isEqualTo(50L);

        // 2. Set OFFICE override = 30
        configTree.putValue(ScopeKey.office("Google", "Bengaluru"), key,
                new SettingValue(30L, ScopeKey.office("Google", "Bengaluru"), 2, System.currentTimeMillis()));
        assertThat(configTree.resolve(key, shiftScope)).isEqualTo(30L);

        // Another office under same client should still get Client level override (50)
        ScopeKey hyderabadScope = ScopeKey.office("Google", "Hyderabad");
        assertThat(configTree.resolve(key, hyderabadScope)).isEqualTo(50L);

        // 3. Set SHIFT override = 20
        configTree.putValue(shiftScope, key,
                new SettingValue(20L, shiftScope, 3, System.currentTimeMillis()));
        assertThat(configTree.resolve(key, shiftScope)).isEqualTo(20L);
    }

    @Test
    @DisplayName("Explain Mode: Full audit trail verifying winning level and evaluation steps")
    void testExplainMode() {
        String key = "booking.cutoff.minutes";
        ScopeKey officeScope = ScopeKey.office("Amazon", "Chennai");

        // Put override at CLIENT level
        configTree.putValue(ScopeKey.client("Amazon"), key,
                new SettingValue(40L, ScopeKey.client("Amazon"), 10, System.currentTimeMillis()));

        ExplainResult result = configTree.explain(key, officeScope);

        assertThat(result.getKey()).isEqualTo(key);
        assertThat(result.getResolvedValue()).isEqualTo(40L);
        assertThat(result.getWinningLevel()).isEqualTo(ScopeLevel.CLIENT);
        assertThat(result.getWinningScope()).isEqualTo(ScopeKey.client("Amazon"));
        assertThat(result.getTrace()).isNotEmpty();
    }

    @Test
    @DisplayName("Diff Engine: Compute modified, added, and removed entries across snapshots")
    void testDiffCalculation() {
        ScopeKey scope = ScopeKey.office("ClientA", "Office1");
        long now = System.currentTimeMillis();

        Map<ScopeKey, Map<String, SettingValue>> snap1 = Map.of(
                scope, Map.of("booking.cutoff.minutes", new SettingValue(60L, scope, 1, now))
        );

        Map<ScopeKey, Map<String, SettingValue>> snap2 = Map.of(
                scope, Map.of(
                        "booking.cutoff.minutes", new SettingValue(30L, scope, 2, now + 1000),
                        "cab.allowed.type", new SettingValue("SUV", scope, 2, now + 1000)
                )
        );

        ConfigDiff diff = DiffCalculator.computeDiff(1, snap1, 2, snap2);
        assertThat(diff.getFromVersion()).isEqualTo(1);
        assertThat(diff.getToVersion()).isEqualTo(2);
        assertThat(diff.getChanges()).hasSize(2);
    }
}
