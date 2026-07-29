package red.jackf.chesttracker.impl.memory;

import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.Logger;
import red.jackf.chesttracker.api.memory.MemoryBank;
import red.jackf.chesttracker.api.memory.MemoryBankAccess;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.memory.metadata.Metadata;
import red.jackf.chesttracker.impl.storage.ConnectionSettings;
import red.jackf.chesttracker.impl.storage.GlobalMemoryBankDefaults;
import red.jackf.chesttracker.impl.storage.Storage;
import red.jackf.jackfredlib.client.api.gps.Coordinate;

import java.util.HashMap;
import java.util.Optional;

public class MemoryBankAccessImpl implements MemoryBankAccess {
    public static final MemoryBankAccessImpl INSTANCE = new MemoryBankAccessImpl();
    private static final Logger LOGGER = ChestTracker.getLogger("MemoryBankAccess");
    @Nullable
    private static MemoryBankImpl loaded = null;

    private MemoryBankAccessImpl() {}

    // API

    @Override
    public boolean loadOrCreate(String memoryBankId, String creationName) {
        return loadOrCreate(memoryBankId, creationName, null);
    }

    public boolean loadOrCreate(String memoryBankId, String creationName, @Nullable Metadata defaultMetadata) {
        if (loaded != null && !INSTANCE.unload()) {
            LOGGER.error("Keeping memory bank {} loaded because it could not be saved", loaded.getId());
            return false;
        }
        loaded = Storage.load(memoryBankId).orElseGet(() -> {
            var defaults = defaultMetadata == null ? GlobalMemoryBankDefaults.get() : defaultMetadata;
            var metadata = Metadata.fromDefaults(creationName, defaults, defaultMetadata == null || defaults.usesGlobalDefaults());
            var bank = new MemoryBankImpl(metadata, new HashMap<>());
            bank.setId(memoryBankId);
            return bank;
        });
        if (loaded.getRegistryProvider() == null) {
            loaded.setRegistryProvider(Storage.getCurrentRegistryProvider());
        }
        return INSTANCE.save();
    }

    /**
     * Saves and reloads the current bank against the active registry set.
     *
     * <p>Some multiplayer proxies send another JOIN while keeping the same server address.
     * The old bank must first be saved with its original registry provider, then decoded with
     * the replacement provider before new container contents are added.</p>
     */
    public boolean reloadForRegistryChange() {
        if (loaded == null) return false;

        MemoryBankImpl previous = loaded;
        if (!Storage.save(previous) || !Storage.waitForPendingSaves()) {
            LOGGER.error("Could not save {} before a registry change; keeping the in-memory bank", previous.getId());
            return false;
        }

        Optional<MemoryBankImpl> rebound = Storage.load(previous.getId());
        if (rebound.isEmpty()) {
            LOGGER.error("Could not reload {} after a registry change; keeping the in-memory bank", previous.getId());
            return false;
        }

        loaded = rebound.get();
        return true;
    }

    public boolean unload() {
        if (loaded == null) return false;
        if (!save() || !Storage.waitForPendingSaves()) {
            LOGGER.error("Could not unload memory bank {} because its save failed", loaded.getId());
            return false;
        }
        loaded = null;
        return true;
    }

    @Override
    public Optional<MemoryBank> getLoaded() {
        return Optional.ofNullable(loaded);
    }

    public Optional<MemoryBankImpl> getLoadedInternal() {
        return Optional.ofNullable(loaded);
    }

    public boolean save() {
        if (loaded == null) return false;
        return Storage.save(loaded);
    }

    // Internal

    // Load from a coordinate's ID, checking the override file if necessary.
    public boolean loadWithDefaults(Coordinate coordinate) {
        // not in-game; don't load
        var settings = ConnectionSettings.getOrCreate(coordinate.id());
        var id = settings.memoryBankIdOverride().orElse(coordinate.id());
        return loadOrCreate(id, coordinate.userFriendlyName());
    }
}
