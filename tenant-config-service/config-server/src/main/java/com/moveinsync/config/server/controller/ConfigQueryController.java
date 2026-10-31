package com.moveinsync.config.server.controller;

import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.core.model.ExplainResult;
import com.moveinsync.config.core.model.ScopeKey;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.VersionedChangeSet;
import com.moveinsync.config.server.service.ConfigManagementService;
import com.moveinsync.config.server.stream.SseBroadcaster;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/configs")
public class ConfigQueryController {

    private final ConfigManagementService service;
    private final SseBroadcaster broadcaster;

    public ConfigQueryController(ConfigManagementService service, SseBroadcaster broadcaster) {
        this.service = service;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/version")
    public Map<String, Object> getLatestVersion() {
        return Map.of("version", service.getLatestVersion());
    }

    @GetMapping("/snapshot")
    public ConfigSnapshot getSnapshot(@RequestParam(required = false) String clientId) {
        return service.getSnapshot(clientId);
    }

    @GetMapping("/delta")
    public List<VersionedChangeSet> getDelta(
            @RequestParam long sinceVersion,
            @RequestParam(required = false) String clientId) {
        return service.getDelta(sinceVersion, clientId);
    }

    @GetMapping("/definitions")
    public Map<String, SettingDefinition> getDefinitions() {
        return service.getDefinitions();
    }

    @GetMapping("/explain")
    public ExplainResult explain(
            @RequestParam String key,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String officeId,
            @RequestParam(required = false) String shiftId) {

        ScopeKey scope;
        if (shiftId != null && officeId != null && clientId != null) {
            scope = ScopeKey.shift(clientId, officeId, shiftId);
        } else if (officeId != null && clientId != null) {
            scope = ScopeKey.office(clientId, officeId);
        } else if (clientId != null) {
            scope = ScopeKey.client(clientId);
        } else {
            scope = ScopeKey.global();
        }

        return service.explain(key, scope);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamConfigs(@RequestParam(required = false) String clientId) {
        return broadcaster.subscribe(clientId);
    }
}
