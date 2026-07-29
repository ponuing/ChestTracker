package red.jackf.chesttracker.impl.storage.backend;

import net.minecraft.core.HolderLookup;
import net.minecraft.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.storage.Storage;
import red.jackf.chesttracker.impl.util.Constants;
import red.jackf.chesttracker.impl.util.FileUtil;
import red.jackf.chesttracker.impl.util.Misc;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class NbtBackend extends FileBasedBackend {
    private static final Logger LOGGER = LogManager.getLogger(ChestTracker.class.getCanonicalName() + "/NBT");

    // Tracking active saves
    private final Map<String, CompletableFuture<Boolean>> pendingSavesNbt = new ConcurrentHashMap<>();
    private final Map<String, Object> saveLocks = new ConcurrentHashMap<>();

    @Override
    public @Nullable MemoryBankImpl load(String id, @Nullable HolderLookup.Provider registries) {
        var meta = loadMetadata(id);
        if (meta.isEmpty()) return null;
        var path = Constants.STORAGE_DIR.resolve(id + extension());
        var result = Misc.time(() -> FileUtil.loadFromNbt(MemoryBankImpl.DATA_CODEC, path, registries));
        if (result.getFirst().isPresent()) {
            LOGGER.debug("Loaded {} in {}ns", path, result.getSecond());
            return new MemoryBankImpl(meta.get(), result.getFirst().get());
        } else {
            return new MemoryBankImpl(meta.get(), new HashMap<>());
        }
    }

    @Override
    public boolean save(MemoryBankImpl memoryBank, @Nullable HolderLookup.Provider registries) {
        if (ChestTrackerConfig.INSTANCE.instance().storage.AsyncSaving) {
            String id = memoryBank.getId();
            int entriesCount = memoryBank.getMemories().values().stream()
                    .mapToInt(key -> key.getMemories().size())
                    .sum();

            // Taking snapshots of data before transferring it in an async stream (thread safety)
            var metadataSnapshot = memoryBank.getMetadata().deepCopy();
            var memoriesSnapshot = new HashMap<>(memoryBank.getMemories());
            metadataSnapshot.updateModified();
            LOGGER.debug("Created snapshot for {} ({} entries)", id, entriesCount);

            // Cancel the previous save if it is still in progress.
            CompletableFuture<Boolean> previous = pendingSavesNbt.get(id);
            if (previous != null && !previous.isDone()) {
                LOGGER.debug("Previous save for {} still in progress, cancelling...", id);
                previous.cancel(true);
                try {
                    previous.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.warn("Previous save cancellation timed out or failed", e);
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

                        // Save NBT data to a temporary file
                        LOGGER.debug("Saving {} to temporary file: {}", id, tempFile.getFileName());
                        boolean saveSuccess = FileUtil.saveToNbt(
                                memoriesSnapshot,
                                MemoryBankImpl.DATA_CODEC,
                                tempFile,
                                registries
                        );

                        if (!saveSuccess) {
                            LOGGER.error("Failed to save NBT data to temporary file for {}", id);
                            Files.deleteIfExists(tempFile);
                            return false;
                        }

                        // Check for cancellation before critical section
                        if (Thread.currentThread().isInterrupted()) {
                            LOGGER.debug("Save for {} was cancelled after NBT save", id);
                            Files.deleteIfExists(tempFile);
                            return false;
                        }

                        // Delete the old .old file if it exists.
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
                            LOGGER.error("Saved NBT data but failed to save metadata for {}", id);
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

            // Save the future for tracking
            pendingSavesNbt.put(id, future);

            // Remove from the map after completion
            future.thenAccept(success -> {
                pendingSavesNbt.remove(id, future);
                if (!success) {
                    LOGGER.warn("Save failed for {}, data may be incomplete", id);
                    Storage.reportSaveFailure(id);
                } else {
                    LOGGER.debug("Successfully saved {} ({} entries)", id, entriesCount);
                }
            });
            return true;
        } else {
            LOGGER.debug("Saving {}", memoryBank.getId());
            memoryBank.getMetadata().updateModified();
            if (!FileUtil.saveToNbt(memoryBank.getMemories(), MemoryBankImpl.DATA_CODEC, Constants.STORAGE_DIR.resolve(memoryBank.getId() + extension()), registries)) {
                return false;
            }
            return saveMetadata(memoryBank.getId(), memoryBank.getMetadata());
        }
    }

    // Waits for all active saves to complete if the game closes/the world is exited
    @Override
    public boolean waitForPendingSaves() {
        if (pendingSavesNbt.isEmpty()) {
            LOGGER.debug("No pending saves to wait for");
            return true;
        }
        LOGGER.debug("Waiting for {} pending save(s) to complete...", pendingSavesNbt.size());

        CompletableFuture<Boolean>[] saves = pendingSavesNbt.values().toArray(new CompletableFuture[0]);
        CompletableFuture<Void> allSaves = CompletableFuture.allOf(saves);

        try {
            // Waiting of 30 seconds in case of game freezing.
            allSaves.get(300, TimeUnit.SECONDS);
            boolean success = Arrays.stream(saves).allMatch(save -> Boolean.TRUE.equals(save.getNow(false)));
            if (success) {
                LOGGER.debug("All pending saves completed");
            } else {
                LOGGER.error("One or more pending saves failed");
            }
            return success;
        } catch (Exception ex) {
            LOGGER.error("Error or timeout waiting for saves to complete", ex);
            return false;
        }
    }

    @Override
    public String extension() {
        return ".nbt";
    }
}
