package com.moveinsync.config.server.service;

import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.core.diff.ConfigDiff;
import com.moveinsync.config.core.diff.DiffCalculator;
import com.moveinsync.config.core.model.ExplainResult;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.model.VersionedChangeSet;
import com.moveinsync.config.core.validation.SettingValidator;
import com.moveinsync.config.core.validation.ValidationException;
import com.moveinsync.config.server.repository.ConfigRepository;
import com.moveinsync.config.server.security.SecurityContext;
import com.moveinsync.config.server.security.UserRole;
import com.moveinsync.config.server.stream.SseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ConfigManagementService {
    private static final Logger log = LoggerFactory.getLogger(ConfigManagementService.class);

    private final ConfigRepository repository;
    private final SseBroadcaster broadcaster;

    public ConfigManagementService(ConfigRepository repository, SseBroadcaster broadcaster) {
        this.repository = repository;
        this.broadcaster = broadcaster;
    }

    public synchronized void registerDefinition(SettingDefinition definition) {
        enforceRole(UserRole.ADMIN, "Only ADMIN can register new setting schemas");
        // Validate global default against definition
        SettingValidator.validateAndNormalize(definition, definition.getGlobalDefault());
        repository.saveDefinition(definition);

        // Update definitions in latest snapshot
        ConfigSnapshot current = repository.getLatestSnapshot();
        Map<String, SettingDefinition> updatedDefs = new HashMap<>(current.getDefinitions());
        updatedDefs.put(definition.getKey(), definition);

        ConfigSnapshot next = new ConfigSnapshot(
                current.getVersion(),
                System.currentTimeMillis(),
                updatedDefs,
                current.getScopedValues()
        );
        repository.saveSnapshot(next);
        log.info("Registered setting definition: key='{}', type={}", definition.getKey(), definition.getDataType());
    }

    public Map<String, SettingDefinition> getDefinitions() {
        return repository.getAllDefinitions();
    }

    public synchronized VersionedChangeSet commitChangeSet(
            String author,
            String reason,
            List<String> stagedOffices,
            List<VersionedChangeSet.ScopedUpdate> updates) {

        enforceRole(UserRole.OPERATOR, "Must have OPERATOR or ADMIN role to commit changesets");
        SecurityContext.RequestContext ctx = SecurityContext.getContext();

        // 1. Strict Tenant Isolation Check
        for (VersionedChangeSet.ScopedUpdate update : updates) {
            String clientInUpdate = update.getScopeKey().getClientId();
            if (clientInUpdate != null && ctx.getRole() != UserRole.ADMIN) {
                if (!clientInUpdate.equalsIgnoreCase(ctx.getTenantId())) {
                    throw new SecurityException("Tenant isolation violation: Cannot modify settings for tenant '"
                            + clientInUpdate + "' with tenant credentials '" + ctx.getTenantId() + "'");
                }
            }
        }

        // 2. Pre-Commit Schema Validation
        List<VersionedChangeSet.ScopedUpdate> normalizedUpdates = new ArrayList<>();
        for (VersionedChangeSet.ScopedUpdate update : updates) {
            if (!update.isDelete()) {
                SettingDefinition def = repository.getDefinition(update.getKey())
                        .orElseThrow(() -> new ValidationException(update.getKey(), update.getValue(), "Unknown setting key"));

                Object normalized = SettingValidator.validateAndNormalize(def, update.getValue());
                normalizedUpdates.add(new VersionedChangeSet.ScopedUpdate(update.getScopeKey(), update.getKey(), normalized));
            } else {
                normalizedUpdates.add(update);
            }
        }

        // 3. Increment monotonic version
        long nextVersion = repository.incrementAndGetVersion();
        long now = System.currentTimeMillis();

        VersionedChangeSet changeSet = new VersionedChangeSet(
                nextVersion,
                author != null ? author : ctx.getUserId(),
                reason,
                now,
                stagedOffices,
                normalizedUpdates
        );

        // 4. Construct updated snapshot
        ConfigSnapshot current = repository.getLatestSnapshot();
        Map<String, Map<String, SettingValue>> newScopedValues = new HashMap<>();
        for (Map.Entry<String, Map<String, SettingValue>> entry : current.getScopedValues().entrySet()) {
            newScopedValues.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }

        for (VersionedChangeSet.ScopedUpdate update : normalizedUpdates) {
            String scopePath = update.getScopeKey().toPath();
            Map<String, SettingValue> scopeMap = newScopedValues.computeIfAbsent(scopePath, k -> new HashMap<>());

            if (update.isDelete()) {
                scopeMap.remove(update.getKey());
            } else {
                scopeMap.put(update.getKey(), new SettingValue(update.getValue(), update.getScopeKey(), nextVersion, now));
            }
        }

        ConfigSnapshot newSnapshot = new ConfigSnapshot(
                nextVersion,
                now,
                repository.getAllDefinitions(),
                newScopedValues
        );

        // 5. Persist changeset and snapshot
        repository.appendChangeSet(changeSet);
        repository.saveSnapshot(newSnapshot);

        log.info("Committed changeset V{}: author='{}', reason='{}', updatesCount={}",
                nextVersion, changeSet.getAuthor(), changeSet.getReason(), normalizedUpdates.size());

        // 6. Live broadcast to connected clients
        broadcaster.broadcast(changeSet);

        return changeSet;
    }

    public synchronized RollbackResult rollbackToVersion(long targetVersion, String author, String reason) {
        enforceRole(UserRole.ADMIN, "Only ADMIN can trigger system rollback");

        ConfigSnapshot currentSnapshot = repository.getLatestSnapshot();
        ConfigSnapshot targetSnapshot = repository.getSnapshotAtVersion(targetVersion)
                .orElseThrow(() -> new IllegalArgumentException("Version V" + targetVersion + " does not exist in history"));

        // Compute diff
        ConfigDiff diff = DiffCalculator.computeDiff(
                currentSnapshot.getVersion(),
                convertToCoreMap(currentSnapshot.getScopedValues()),
                targetVersion,
                convertToCoreMap(targetSnapshot.getScopedValues())
        );

        // Convert target state to ScopedUpdates for new monotonically increasing rollback version
        List<VersionedChangeSet.ScopedUpdate> rollbackUpdates = new ArrayList<>();
        targetSnapshot.getScopedValues().forEach((scopePath, map) -> {
            ScopeKey scopeKey = parseScopePath(scopePath);
            map.forEach((key, sv) -> rollbackUpdates.add(new VersionedChangeSet.ScopedUpdate(scopeKey, key, sv.getValue())));
        });

        // Also identify deleted keys to remove them
        currentSnapshot.getScopedValues().forEach((scopePath, map) -> {
            ScopeKey scopeKey = parseScopePath(scopePath);
            Map<String, SettingValue> targetMap = targetSnapshot.getScopedValues().get(scopePath);
            map.forEach((key, sv) -> {
                if (targetMap == null || !targetMap.containsKey(key)) {
                    rollbackUpdates.add(new VersionedChangeSet.ScopedUpdate(scopeKey, key, null));
                }
            });
        });

        VersionedChangeSet rollbackChangeSet = commitChangeSet(
                author != null ? author : "system-admin",
                "Rollback to V" + targetVersion + ": " + (reason != null ? reason : "Reverting configuration"),
                null,
                rollbackUpdates
        );

        return new RollbackResult(rollbackChangeSet.getVersion(), targetVersion, diff);
    }

    public ExplainResult explain(String key, ScopeKey scope) {
        enforceTenantReadAccess(scope.getClientId());
        return repository.getLatestSnapshot().explain(key, scope);
    }

    public ConfigSnapshot getSnapshot(String clientId) {
        enforceTenantReadAccess(clientId);
        return repository.getLatestSnapshot();
    }

    public List<VersionedChangeSet> getDelta(long sinceVersion, String clientId) {
        enforceTenantReadAccess(clientId);
        return repository.getChangeSetsSince(sinceVersion);
    }

    public long getLatestVersion() {
        return repository.getLatestVersion();
    }

    private void enforceRole(UserRole required, String errorMessage) {
        SecurityContext.RequestContext ctx = SecurityContext.getContext();
        if (!ctx.hasRole(required)) {
            throw new SecurityException("Access Denied: " + errorMessage + " (Current role: " + ctx.getRole() + ")");
        }
    }

    private void enforceTenantReadAccess(String requestedTenant) {
        SecurityContext.RequestContext ctx = SecurityContext.getContext();
        if (ctx.getRole() == UserRole.ADMIN) return;
        if (requestedTenant != null && ctx.getTenantId() != null) {
            if (!requestedTenant.equalsIgnoreCase(ctx.getTenantId())) {
                throw new SecurityException("Tenant isolation: Cannot read data belonging to tenant '" + requestedTenant + "'");
            }
        }
    }

    private ScopeKey parseScopePath(String path) {
        if ("GLOBAL".equals(path)) return ScopeKey.global();
        if (path.startsWith("CLIENT:")) {
            String[] parts = path.split("/");
            String client = parts[0].substring(7);
            if (parts.length == 1) return ScopeKey.client(client);
            if (parts.length == 2 && parts[1].startsWith("OFFICE:")) {
                return ScopeKey.office(client, parts[1].substring(7));
            }
            if (parts.length == 3 && parts[1].startsWith("OFFICE:") && parts[2].startsWith("SHIFT:")) {
                return ScopeKey.shift(client, parts[1].substring(7), parts[2].substring(6));
            }
        }
        return ScopeKey.global();
    }

    private Map<ScopeKey, Map<String, SettingValue>> convertToCoreMap(Map<String, Map<String, SettingValue>> source) {
        Map<ScopeKey, Map<String, SettingValue>> map = new HashMap<>();
        source.forEach((path, val) -> map.put(parseScopePath(path), val));
        return map;
    }

    public static class RollbackResult {
        private final long newVersion;
        private final long rolledBackToVersion;
        private final ConfigDiff diff;

        public RollbackResult(long newVersion, long rolledBackToVersion, ConfigDiff diff) {
            this.newVersion = newVersion;
            this.rolledBackToVersion = rolledBackToVersion;
            this.diff = diff;
        }

        public long getNewVersion() {
            return newVersion;
        }

        public long getRolledBackToVersion() {
            return rolledBackToVersion;
        }

        public ConfigDiff getDiff() {
            return diff;
        }
    }
}
