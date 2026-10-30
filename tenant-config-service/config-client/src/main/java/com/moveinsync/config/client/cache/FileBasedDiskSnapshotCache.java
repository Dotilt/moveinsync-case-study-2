package com.moveinsync.config.client.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moveinsync.config.client.snapshot.ConfigSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public class FileBasedDiskSnapshotCache implements DiskSnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(FileBasedDiskSnapshotCache.class);
    private final Path cacheFilePath;
    private final ObjectMapper objectMapper;

    public FileBasedDiskSnapshotCache(Path cacheDir, String clientId) {
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            log.error("Failed to create snapshot cache directory: {}", cacheDir, e);
        }
        this.cacheFilePath = cacheDir.resolve("snapshot-" + (clientId != null ? clientId : "global") + ".json");
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public synchronized void save(ConfigSnapshot snapshot) {
        try {
            Path tempFile = Files.createTempFile(cacheFilePath.getParent(), "snapshot-", ".tmp");
            objectMapper.writeValue(tempFile.toFile(), snapshot);
            // Atomic file move
            Files.move(tempFile, cacheFilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Successfully persisted configuration snapshot V{} to disk cache", snapshot.getVersion());
        } catch (IOException e) {
            log.error("Failed to persist snapshot to disk cache: {}", cacheFilePath, e);
        }
    }

    @Override
    public synchronized Optional<ConfigSnapshot> load() {
        if (!Files.exists(cacheFilePath)) {
            log.info("No disk snapshot cache found at {}", cacheFilePath);
            return Optional.empty();
        }
        try {
            ConfigSnapshot snapshot = objectMapper.readValue(cacheFilePath.toFile(), ConfigSnapshot.class);
            log.info("Successfully loaded offline snapshot V{} from disk cache", snapshot.getVersion());
            return Optional.of(snapshot);
        } catch (IOException e) {
            log.error("Failed to load snapshot cache from {}: {}", cacheFilePath, e.getMessage());
            return Optional.empty();
        }
    }

    public Path getCacheFilePath() {
        return cacheFilePath;
    }
}
