package red.jackf.chesttracker.impl.storage;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.metadata.Metadata;
import red.jackf.chesttracker.impl.storage.backend.Backend;
import red.jackf.jackfredlib.client.api.toasts.ToastBuilder;
import red.jackf.jackfredlib.client.api.toasts.ToastFormat;
import red.jackf.jackfredlib.client.api.toasts.ToastIcon;
import red.jackf.jackfredlib.client.api.toasts.Toasts;

import java.util.Collection;
import java.util.Optional;

public class Storage {

    //////////////
    // INTERNAL //
    //////////////

    private static final Logger LOGGER = ChestTracker.getLogger("Storage");
    private static final long SAVE_ERROR_TOAST_COOLDOWN_MS = 10_000L;
    private static long lastSaveErrorToast;
    public static Backend backend;

    public static void setBackend(Backend backend) {
        Storage.backend = backend;
    }

    public static void setup() {
        ChestTrackerConfig.INSTANCE.instance().storage.storageBackend.load();

        // storage saving hooks

        // on pause
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof PauseScreen) MemoryBankAccessImpl.INSTANCE.save();
        });
    }

    /////////
    // API //
    /////////

    public static Optional<Metadata> loadMetadata(String id) {
        Optional<MemoryBankImpl> existing = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (existing.isPresent() && id.equals(existing.get().getId()))
            return Optional.of(existing.get().getMetadata().deepCopy());
        LOGGER.debug("Loading {} metadata using {}", id, backend.getClass().getSimpleName());
        return backend.loadMetadata(id);
    }

    public static Collection<String> getAllIds() {
        return backend.getAllIds();
    }

    public static boolean exists(String id) {
        return backend.exists(id);
    }

    public static void delete(String id) {
        backend.delete(id);
    }

    public static Component getBackendLabel(String memoryBankId) {
        return backend.getDescriptionLabel(memoryBankId);
    }

    public static Optional<MemoryBankImpl> load(String id) {
        Optional<MemoryBankImpl> existing = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (existing.isPresent() && id.equals(existing.get().getId()))
            return existing;

        HolderLookup.Provider registries = getCurrentRegistryProvider();

        LOGGER.debug("Loading {} using {}", id, backend.getClass().getSimpleName());
        var loaded = backend.load(id, registries);
        if (loaded == null) return Optional.empty();
        loaded.setId(id);
        loaded.setRegistryProvider(registries);
        return Optional.of(loaded);
    }

    public static boolean save(MemoryBankImpl bank) {
        if (bank == null) {
            LOGGER.warn("Tried to save null Memory Bank");
            return false;
        }

        HolderLookup.Provider registries = bank.getRegistryProvider();
        if (registries == null) {
            registries = getCurrentRegistryProvider();
            bank.setRegistryProvider(registries);
        }

        bank.getMetadata().updateModified();
        boolean success = backend.save(bank, registries);
        if (!success) {
            reportSaveFailure(bank.getId());
        }
        return success;
    }

    public static boolean waitForPendingSaves() {
        return backend.waitForPendingSaves();
    }

    public static @Nullable HolderLookup.Provider getCurrentRegistryProvider() {
        var level = Minecraft.getInstance().level;
        return level == null ? null : level.registryAccess();
    }

    public static synchronized void reportSaveFailure(String id) {
        LOGGER.error("Failed to save memory bank {}", id);

        long now = System.currentTimeMillis();
        if (now - lastSaveErrorToast < SAVE_ERROR_TOAST_COOLDOWN_MS) return;
        lastSaveErrorToast = now;

        Minecraft.getInstance().execute(() ->
                Toasts.INSTANCE.send(ToastBuilder.builder(ToastFormat.DARK, Component.translatable("chesttracker.title"))
                        .withIcon(ToastIcon.modIcon(ChestTracker.ID))
                        .addMessage(Component.translatable("chesttracker.storage.saveFailed", id).withStyle(ChatFormatting.RED))
                        .progressShowsVisibleTime()
                        .build()));
    }

    public static boolean saveMetadata(String id, Metadata metadata) {
        Optional<MemoryBankImpl> existing = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (existing.isPresent() && id.equals(existing.get().getId())) {
            existing.get().setMetadata(metadata);
            return save(existing.get());
        }

        metadata.updateModified();
        return backend.saveMetadata(id, metadata);
    }
}
