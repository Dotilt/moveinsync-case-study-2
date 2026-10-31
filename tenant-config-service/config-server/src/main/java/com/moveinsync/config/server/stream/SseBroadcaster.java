package com.moveinsync.config.server.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moveinsync.config.core.model.VersionedChangeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SseBroadcaster {
    private static final Logger log = LoggerFactory.getLogger(SseBroadcaster.class);

    // Map of clientId -> List of active SseEmitters
    private final Map<String, List<SseEmitter>> clientEmitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SseBroadcaster() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public SseEmitter subscribe(String clientId) {
        String key = clientId != null ? clientId : "GLOBAL";
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // Keep alive

        clientEmitters.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(key, emitter));
        emitter.onTimeout(() -> removeEmitter(key, emitter));
        emitter.onError(e -> removeEmitter(key, emitter));

        log.info("Client '{}' registered SSE connection. Total active: {}",
                key, clientEmitters.getOrDefault(key, List.of()).size());

        try {
            emitter.send(SseEmitter.event().name("INIT").data("Connected to MoveInSync Config Stream"));
        } catch (IOException ignored) {}

        return emitter;
    }

    public void broadcast(VersionedChangeSet changeSet) {
        try {
            String json = objectMapper.writeValueAsString(changeSet);

            // Broadcast to all relevant clients
            clientEmitters.forEach((clientId, emitters) -> {
                // If staged, check if targeted or global
                List<String> stagedOffices = changeSet.getStagedOffices();
                boolean matches = stagedOffices == null || stagedOffices.isEmpty() || true;

                if (matches) {
                    for (SseEmitter emitter : emitters) {
                        try {
                            emitter.send(SseEmitter.event().data(json));
                        } catch (Exception ex) {
                            removeEmitter(clientId, emitter);
                        }
                    }
                }
            });
            log.info("Broadcasted changeset V{} to active SSE subscribers", changeSet.getVersion());
        } catch (Exception e) {
            log.error("Failed to broadcast changeset V{}: {}", changeSet.getVersion(), e.getMessage());
        }
    }

    private void removeEmitter(String clientId, SseEmitter emitter) {
        List<SseEmitter> list = clientEmitters.get(clientId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    public int getActiveConnectionCount() {
        return clientEmitters.values().stream().mapToInt(List::size).sum();
    }
}
