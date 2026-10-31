package com.moveinsync.config.server.controller;

import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.VersionedChangeSet;
import com.moveinsync.config.server.service.ConfigManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/configs")
public class ConfigAdminController {

    private final ConfigManagementService service;

    public ConfigAdminController(ConfigManagementService service) {
        this.service = service;
    }

    @PostMapping("/definitions")
    public ResponseEntity<Map<String, String>> createDefinition(@RequestBody SettingDefinition definition) {
        service.registerDefinition(definition);
        return ResponseEntity.ok(Map.of("message", "Definition created successfully", "key", definition.getKey()));
    }

    @PostMapping("/changeset")
    public ResponseEntity<VersionedChangeSet> commitChangeSet(@RequestBody ChangeSetRequest request) {
        VersionedChangeSet committed = service.commitChangeSet(
                request.getAuthor(),
                request.getReason(),
                request.getStagedOffices(),
                request.getUpdates()
        );
        return ResponseEntity.ok(committed);
    }

    @PostMapping("/rollback")
    public ResponseEntity<ConfigManagementService.RollbackResult> rollback(
            @RequestParam long targetVersion,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String reason) {
        ConfigManagementService.RollbackResult result = service.rollbackToVersion(targetVersion, author, reason);
        return ResponseEntity.ok(result);
    }

    public static class ChangeSetRequest {
        private String author;
        private String reason;
        private List<String> stagedOffices;
        private List<VersionedChangeSet.ScopedUpdate> updates;

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public List<String> getStagedOffices() { return stagedOffices; }
        public void setStagedOffices(List<String> stagedOffices) { this.stagedOffices = stagedOffices; }

        public List<VersionedChangeSet.ScopedUpdate> getUpdates() { return updates; }
        public void setUpdates(List<VersionedChangeSet.ScopedUpdate> updates) { this.updates = updates; }
    }
}
