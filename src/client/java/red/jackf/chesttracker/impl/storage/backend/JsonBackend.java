package red.jackf.chesttracker.impl.storage.backend;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.util.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.MemoryKeyImpl;
import red.jackf.chesttracker.impl.memory.metadata.Metadata;
import red.jackf.chesttracker.impl.storage.Storage;
import red.jackf.chesttracker.impl.util.Constants;
import red.jackf.chesttracker.impl.util.FileUtil;
import red.jackf.chesttracker.impl.util.Misc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

public class JsonBackend extends FileBasedBackend {
    private static final Logger LOGGER = LogManager.getLogger(ChestTracker.class.getCanonicalName() + "/JSON");

    @Override
    public String extension() {
        return ".json";
    }

    // Tracking active saves
    private final Map<String, CompletableFuture<Boolean>> pendingSavesJson = new ConcurrentHashMap<>();
    private final Map<String, Object> saveLocks = new ConcurrentHashMap<>();

    @Nullable
    @Override
    public MemoryBankImpl load(String id, @Nullable HolderLookup.Provider registries) {
        DynamicOps<JsonElement> ops = registries == null ? JsonOps.INSTANCE : registries.createSerializationContext(JsonOps.INSTANCE);

        Optional<Metadata> metadata = loadMetadata(id);
        if (metadata.isEmpty()) return null;
        Path dataPath = Constants.STORAGE_DIR.resolve(id + extension());
        var result = Misc.time(() -> {
            if (Files.isRegularFile(dataPath)) {
                try {
                    var str = FileUtils.readFileToString(dataPath.toFile(), StandardCharsets.UTF_8);
                    var json = FileUtil.gson().fromJson(str, JsonElement.class);
                    var decoded = MemoryBankImpl.DATA_CODEC.decode(ops, json);
                    if (decoded.isError()) {
                        //noinspection OptionalGetWithoutIsPresent
                        throw new IOException("Invalid Memories JSON: %s".formatted(decoded.error().get().message()));
                    } else {
                        //noinspection OptionalGetWithoutIsPresent
                        return decoded.result().get().getFirst();
                    }
                } catch (JsonParseException | IOException ex) {
                    LOGGER.error("Error loading %s".formatted(dataPath), ex);
                    FileUtil.tryMove(dataPath, dataPath.resolveSibling(dataPath.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return null;
        });
        Map<Identifier, MemoryKeyImpl> data = result.getFirst() == null ? new HashMap<>() : result.getFirst();
        LOGGER.debug("Loaded {} in {}ns", dataPath, result.getSecond());
        return new MemoryBankImpl(metadata.get(), data);
    }

    @Override
    public boolean save(MemoryBankImpl memoryBank, @Nullable HolderLookup.Provider registries) {
        if (ChestTrackerConfig.INSTANCE.instance().storage.AsyncSaving) {
            String id = memoryBank.getId();
            int entriesCount = memoryBank.getMemories().values().stream()
                    .mapToInt(key -> key.getMemories().size())
                    .sum();

            // Taking snapshots of data before transferring it in an async stream (thread safety)
            DynamicOps<JsonElement> ops = registries == null
                    ? JsonOps.INSTANCE
                    : registries.createSerializationContext(JsonOps.INSTANCE);
            var metadataSnapshot = memoryBank.getMetadata().deepCopy();
            var memoriesSnapshot = new HashMap<>(memoryBank.getMemories());
            metadataSnapshot.updateModified();

            LOGGER.debug("Created snapshot for {} ({} entries)", id, entriesCount);

            // Cancel the previous save if it is still in progress.
            CompletableFuture<Boolean> previous = pendingSavesJson.get(id);
            if (previous != null && !previous.isDone()) {
                LOGGER.debug("Previous save for {} still in progress, cancelling...", id);
                previous.cancel(true);
                try {
                    previous.get(5, TimeUnit.SECONDS);
                } catch (CancellationException e) {
                    LOGGER.debug("Previous save for {} was successfully cancelled", id);
                } catch (TimeoutException e) {
                    LOGGER.warn("Previous save cancellation timed out for {}", id);
                } catch (InterruptedException e) {
                    LOGGER.warn("Interrupted while waiting for previous save cancellation", e);
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    LOGGER.warn("Previous save failed with exception", e);
                }
            }

            // Async saving
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                // We get a block for this ID
                Object lock = saveLocks.computeIfAbsent(id, k -> new Object());

                synchronized (lock) {
                    if (Thread.currentThread().isInterrupted()) {
                        LOGGER.debug("Save for {} was cancelled before start", id);
                        return false;
                    }

                    LOGGER.debug("Starting async save for {}", id);

                    // Determine file paths with a unique temporary name
                    Path finalFile = Constants.STORAGE_DIR.resolve(id + extension());
                    long timestamp = System.currentTimeMillis();
                    Path tempFile = Constants.STORAGE_DIR.resolve(id + extension() + ".tmp." + timestamp);
                    Path oldFile = Constants.STORAGE_DIR.resolve(id + extension() + ".old");

                    try {
                        // Check for cancellation
                        if (Thread.currentThread().isInterrupted()) {
                            LOGGER.debug("Save for {} was cancelled", id);
                            return false;
                        }

                        // Encode JSON data
                        LOGGER.debug("Encoding JSON data for {}", id);
                        Optional<JsonElement> memoryJson = MemoryBankImpl.DATA_CODEC
                                .encodeStart(ops, memoriesSnapshot)
                                .resultOrPartial(Util.prefix("Error encoding memories", LOGGER::error));

                        if (memoryJson.isEmpty()) {
                            LOGGER.error("Failed to encode JSON data for {}", id);
                            return false;
                        }

                        // Check for cancellation
                        if (Thread.currentThread().isInterrupted()) {
                            LOGGER.debug("Save for {} was cancelled after encoding", id);
                            return false;
                        }

                        // Save JSON data to a temporary file
                        LOGGER.debug("Saving {} to temporary file: {}", id, tempFile.getFileName());
                        Files.createDirectories(tempFile.getParent());
                        FileUtils.write(
                                tempFile.toFile(),
                                FileUtil.gson().toJson(memoryJson.get()),
                                StandardCharsets.UTF_8
                        );

                        // Check for cancellation before critical section
                        if (Thread.currentThread().isInterrupted()) {
                            LOGGER.debug("Save for {} was cancelled after JSON save", id);
                            Files.deleteIfExists(tempFile);
                            return false;
                        }

                        // Delete the old .old file if it exists
                        if (Files.exists(oldFile)) {
                            LOGGER.debug("Removing previous .old file: {}", oldFile.getFileName());
                            try {
                                Files.delete(oldFile);
                            } catch (IOException e) {
                                LOGGER.warn("Failed to delete old backup file: {}", oldFile.getFileName(), e);
                            }
                        }

                        // If the current file exists, rename it to .old
                        if (Files.exists(finalFile)) {
                            LOGGER.debug("Renaming current file to .old");
                            try {
                                Files.move(finalFile, oldFile, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                LOGGER.error("Failed to rename current file to .old for {}", id, e);
                                Files.deleteIfExists(tempFile);
                                return false;
                            }
                        }

                        // Rename temp file to final
                        LOGGER.debug("Renaming temporary file to final");
                        try {
                            Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException e) {
                            LOGGER.warn("Atomic move not supported, using regular move");
                            Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            LOGGER.error("Failed to rename temporary file to final for {}", id, e);
                            // Trying to restore from .old
                            if (Files.exists(oldFile)) {
                                try {
                                    Files.move(oldFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                                    LOGGER.info("Restored from .old backup");
                                } catch (IOException restoreEx) {
                                    LOGGER.error("Failed to restore from backup!", restoreEx);
                                }
                            }
                            Files.deleteIfExists(tempFile);
                            return false;
                        }

                        // Commit metadata only after the data file has been replaced successfully.
                        if (!saveMetadata(id, metadataSnapshot)) {
                            LOGGER.error("Saved JSON data but failed to save metadata for {}", id);
                            return false;
                        }

                        LOGGER.debug("Successfully completed atomic save for {}", id);

                        // Delete old temporary files (if any remain from previous unsuccessful saves)
                        try {
                            Files.list(Constants.STORAGE_DIR)
                                    .filter(p -> p.getFileName().toString().startsWith(id + extension() + ".tmp."))
                                    .filter(p -> !p.equals(tempFile))
                                    .forEach(p -> {
                                        try {
                                            Files.deleteIfExists(p);
                                            LOGGER.debug("Cleaned up old temporary file: {}", p.getFileName());
                                        } catch (IOException e) {
                                            LOGGER.debug("Could not clean up old temp file: {}", e.getMessage());
                                        }
                                    });
                        } catch (IOException e) {
                            LOGGER.debug("Could not list directory for cleanup: {}", e.getMessage());
                        }

                        return true;

                    } catch (IOException e) {
                        LOGGER.error("IO Exception during atomic save for {}", id, e);
                        try {
                            Files.deleteIfExists(tempFile);
                        } catch (IOException cleanupEx) {
                            LOGGER.warn("Failed to cleanup temporary file", cleanupEx);
                        }
                        return false;
                    }
                }
            }, Util.backgroundExecutor()).exceptionally(ex -> {
                LOGGER.error("Exception during async save for {}", id, ex);
                return false;
            });

            pendingSavesJson.put(id, future);

            future.thenAccept(success -> {
                pendingSavesJson.remove(id, future);
                if (!success) {
                    LOGGER.warn("JSON save failed for {}, data may be incomplete", id);
                    Storage.reportSaveFailure(id);
                } else {
                    LOGGER.debug("JSON successfully saved {} ({} entries)", id, entriesCount);
                }
            });

            return true;
        } else {
            LOGGER.debug("Saving {}", memoryBank.getId());

            DynamicOps<JsonElement> ops = registries == null ? JsonOps.INSTANCE : registries.createSerializationContext(JsonOps.INSTANCE);

            memoryBank.getMetadata().updateModified();
            Path path = Constants.STORAGE_DIR.resolve(memoryBank.getId() + extension());

            try {
                Files.createDirectories(path.getParent());
                Optional<JsonElement> memoryJson = MemoryBankImpl.DATA_CODEC.encodeStart(ops, memoryBank.getMemories())
                        .resultOrPartial(Util.prefix("Error encoding memories", LOGGER::error));
                if (memoryJson.isPresent()) {
                    FileUtils.write(path.toFile(), FileUtil.gson().toJson(memoryJson.get()), StandardCharsets.UTF_8);
                    return saveMetadata(memoryBank.getId(), memoryBank.getMetadata());
                } else {
                    LOGGER.error("Unknown error encoding memories for {}", memoryBank.getId());
                }
            } catch (IOException ex) {
                LOGGER.error("Error saving memories for {}", memoryBank.getId(), ex);
            }

            return false;
        }
    }

    // Waits for all active saves to complete if the game closes/the world
    @Override
    public boolean waitForPendingSaves() {
        if (pendingSavesJson.isEmpty()) {
            LOGGER.debug("No pending JSON saves to wait for");
            return true;
        }
        LOGGER.debug("Waiting for {} pending JSON save(s) to complete...", pendingSavesJson.size());

        CompletableFuture<Boolean>[] saves = pendingSavesJson.values().toArray(new CompletableFuture[0]);
        CompletableFuture<Void> allSaves = CompletableFuture.allOf(saves);

        try {
            // Waiting of 30 seconds in case of game freezing.
            allSaves.get(300, TimeUnit.SECONDS);
            boolean success = Arrays.stream(saves).allMatch(save -> Boolean.TRUE.equals(save.getNow(false)));
            if (success) {
                LOGGER.debug("All pending JSON saves completed");
            } else {
                LOGGER.error("One or more pending JSON saves failed");
            }
            return success;
        } catch (Exception ex) {
            LOGGER.error("Error or timeout waiting for JSON saves to complete", ex);
            return false;
        }
    }
}
