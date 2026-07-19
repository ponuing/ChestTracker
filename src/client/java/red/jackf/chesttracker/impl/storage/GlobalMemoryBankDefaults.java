package red.jackf.chesttracker.impl.storage;

import red.jackf.chesttracker.impl.memory.metadata.Metadata;
import red.jackf.chesttracker.impl.util.Constants;
import red.jackf.chesttracker.impl.util.FileUtil;

import java.nio.file.Path;

public final class GlobalMemoryBankDefaults {
    private static final Path PATH = Constants.STORAGE_DIR.resolve("memory_bank_defaults.dat");

    private static Metadata defaults = Metadata.blank();

    private GlobalMemoryBankDefaults() {
    }

    public static void load() {
        defaults = FileUtil.loadFromNbt(Metadata.CODEC, PATH, null).orElseGet(Metadata::blank);
    }

    public static void save() {
        FileUtil.saveToNbt(defaults, Metadata.CODEC, PATH, null);
    }

    public static Metadata get() {
        return defaults.copyAsDefaults();
    }

    public static void set(Metadata metadata) {
        defaults = metadata.copyAsDefaults();
        save();
    }
}