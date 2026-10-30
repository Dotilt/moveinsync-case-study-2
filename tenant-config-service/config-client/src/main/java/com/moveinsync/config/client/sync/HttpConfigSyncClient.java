package com.moveinsync.config.client.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import com.moveinsync.config.core.model.VersionedChangeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class HttpConfigSyncClient implements ConfigSyncClient {
    private static final Logger log = LoggerFactory.getLogger(HttpConfigSyncClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private CompletableFuture<Void> streamFuture;

    public HttpConfigSyncClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public long fetchLatestVersion(String clientId) throws Exception {
        String url = baseUrl + "/api/v1/configs/version" + (clientId != null ? "?clientId=" + clientId : "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonNode root = objectMapper.readTree(response.body());
            return root.has("version") ? root.get("version").asLong() : 0L;
        }
        throw new RuntimeException("Failed to fetch version: HTTP " + response.statusCode());
    }

    @Override
    public ConfigSnapshot fetchFullSnapshot(String clientId) throws Exception {
        String url = baseUrl + "/api/v1/configs/snapshot" + (clientId != null ? "?clientId=" + clientId : "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return objectMapper.readValue(response.body(), ConfigSnapshot.class);
        }
        throw new RuntimeException("Failed to fetch snapshot: HTTP " + response.statusCode());
    }

    @Override
    public List<VersionedChangeSet> fetchDelta(String clientId, long sinceVersion) throws Exception {
        String url = baseUrl + "/api/v1/configs/delta?sinceVersion=" + sinceVersion
                + (clientId != null ? "&clientId=" + clientId : "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return objectMapper.readValue(response.body(), new TypeReference<List<VersionedChangeSet>>() {});
        }
        return Collections.emptyList();
    }

    @Override
    public void startStream(String clientId, Consumer<VersionedChangeSet> onEvent, Consumer<Throwable> onError) {
        String url = baseUrl + "/api/v1/configs/stream" + (clientId != null ? "?clientId=" + clientId : "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(60))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        streamFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        onError.accept(new RuntimeException("Stream rejected: HTTP " + response.statusCode()));
                        return;
                    }
                    try (InputStream is = response.body();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while (!isClosed.get() && (line = reader.readLine()) != null) {
                            if (line.startsWith("data:")) {
                                String jsonData = line.substring(5).trim();
                                if (!jsonData.isEmpty()) {
                                    try {
                                        VersionedChangeSet changeSet = objectMapper.readValue(jsonData, VersionedChangeSet.class);
                                        onEvent.accept(changeSet);
                                    } catch (Exception ex) {
                                        log.error("Failed to parse SSE payload: {}", jsonData, ex);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        if (!isClosed.get()) {
                            onError.accept(e);
                        }
                    }
                })
                .exceptionally(ex -> {
                    if (!isClosed.get()) {
                        onError.accept(ex);
                    }
                    return null;
                });
    }

    @Override
    public void close() {
        isClosed.set(true);
        if (streamFuture != null) {
            streamFuture.cancel(true);
        }
    }
}
