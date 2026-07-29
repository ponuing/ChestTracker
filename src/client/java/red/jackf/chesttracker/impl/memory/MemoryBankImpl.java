package red.jackf.chesttracker.impl.memory;

import com.mojang.serialization.Codec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.api.ClientBlockSource;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryBank;
import red.jackf.chesttracker.api.memory.MemoryKey;
import red.jackf.chesttracker.api.memory.counting.CountingPredicate;
import red.jackf.chesttracker.api.memory.counting.StackMergeMode;
import red.jackf.chesttracker.api.providers.MemoryLocation;
import red.jackf.chesttracker.api.providers.ProviderUtils;
import red.jackf.chesttracker.impl.memory.key.ManualMode;
import red.jackf.chesttracker.impl.memory.key.OverrideInfo;
import red.jackf.chesttracker.impl.memory.key.SearchContext;
import red.jackf.chesttracker.impl.memory.metadata.Metadata;
import red.jackf.jackfredlib.api.base.codecs.JFLCodecs;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;

import java.util.*;

public class MemoryBankImpl implements MemoryBank {
    public static final Codec<Map<Identifier, MemoryKeyImpl>> DATA_CODEC = JFLCodecs.mutableMap(Codec.unboundedMap(Identifier.CODEC, MemoryKeyImpl.Codecs.MAIN));

    ////////////
    // OBJECT //
    ////////////

    private final Map<Identifier, MemoryKeyImpl> memoryKeys;
    private Metadata metadata;
    private String id;
    private @Nullable HolderLookup.Provider registryProvider;

    public MemoryBankImpl(Metadata metadata, Map<Identifier, MemoryKeyImpl> keys) {
        this.metadata = metadata;
        this.memoryKeys = keys;
        this.memoryKeys.values().forEach(key -> key.setMemoryBank(this));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the registry set that this bank's item stacks were decoded or captured with.
     *
     * <p>Dynamic registry holders are tied to their owning registry set. Multiplayer proxy
     * transfers can replace the client's active registry set before the previous bank is
     * saved, so using the current level's registries would make otherwise valid enchantments,
     * trims, and other registry-backed components impossible to encode.</p>
     */
    public @Nullable HolderLookup.Provider getRegistryProvider() {
        return registryProvider;
    }

    public void setRegistryProvider(@Nullable HolderLookup.Provider registryProvider) {
        this.registryProvider = registryProvider;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    ///////////////////////
    // MEMORY MANAGEMENT //
    ///////////////////////

    /**
     * @return All memories in every key of this bank
     */
    public Map<Identifier, MemoryKeyImpl> getMemories() {
        return memoryKeys;
    }

    /**
     * Remove an entire key from the current memory bank
     *
     * @param key Key to remove
     */
    public void removeKey(Identifier key) {
        this.memoryKeys.remove(key);
    }

    /**
     * Returns a list of ItemStacks within a given key matching the given filter. Not sorted in a guaranteed order.
     *
     * @param key            Memory key to pull from
     * @param filter         Filter that memories must pass to be counted
     * @param stackMergeMode How to merge identical stacks
     * @param unpackNested   Whether to count items within containers, such as Shulker Boxes
     */
    public List<ItemStack> getCounts(Identifier key, CountingPredicate filter, StackMergeMode stackMergeMode, boolean unpackNested) {
        if (this.memoryKeys.containsKey(key)) {
            return this.memoryKeys.get(key).getCounts(filter, stackMergeMode, unpackNested);
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Parse a Where Is It search-request and runs it through a given dimension's memories.
     *
     * @param key     Memory key to run the request through
     * @param request Search request to run on all memories
     * @return A list of search requests consisting of matching memories in this key.
     */
    public List<SearchResult> doSearch(Identifier key, SearchRequest request) {
        if (!this.memoryKeys.containsKey(key)) return Collections.emptyList();

        MemoryKeyImpl memoryKey = this.memoryKeys.get(key);
        final Vec3 startPos = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.position() : null;
        if (startPos == null) return Collections.emptyList();

        return memoryKey.doSearch(new SearchContext(request, startPos, this.metadata));
    }

    /**
     * Returns a list of all memory keys in this bank.
     */
    public Set<Identifier> getKeys() {
        return this.memoryKeys.keySet();
    }

    @Override
    public Set<Identifier> getMemoryKeys() {
        return Set.copyOf(this.memoryKeys.keySet());
    }

    @Override
    public Map<Identifier, MemoryKey> getAllMemories() {
        return Map.copyOf(this.memoryKeys);
    }

    @Override
    public Optional<Memory> getMemory(ClientBlockSource cbs) {
        Optional<MemoryLocation> target = ProviderUtils.getCurrentProvider().flatMap(provider -> provider.getMemoryLocation(cbs));
        if (target.isEmpty()) return Optional.empty();

        return this.getMemory(target.get().memoryKey(), target.get().position());
    }

    @Override
    public Optional<MemoryKey> getKey(Identifier keyId) {
        return Optional.ofNullable(this.memoryKeys.get(keyId));
    }

    public Optional<MemoryKeyImpl> getKeyInternal(Identifier key) {
        return Optional.ofNullable(memoryKeys.get(key));
    }

    public MemoryKeyImpl getOrCreateKeyInternal(Identifier key) {
        return this.memoryKeys.computeIfAbsent(key, ignored -> {
            var newKey = new MemoryKeyImpl();
            newKey.setMemoryBank(this);
            return newKey;
        });
    }

    @Override
    public void addMemory(Identifier keyId, BlockPos location, Memory memory) {
        MemoryKeyImpl key = this.getOrCreateKeyInternal(keyId);

        key.add(location, memory);

        // if we didn't want the memory
        if (key.isEmpty()) {
            this.memoryKeys.remove(keyId);
        }
    }

    @Override
    public void removeMemory(Identifier key, BlockPos pos) {
        MemoryKeyImpl memoryKey = this.memoryKeys.get(key);
        if (memoryKey != null) {
            memoryKey.remove(pos);
            if (memoryKey.isEmpty()) {
                this.memoryKeys.remove(key);
            }
        }
    }

    public void setManualModeOverride(Identifier key, BlockPos pos, ManualMode mode) {
        if (mode == ManualMode.DEFAULT && !this.getKeys().contains(key)) return;

        var keyImpl = this.getOrCreateKeyInternal(key);
        var overrides = keyImpl.overrides();
        if (mode == ManualMode.DEFAULT && !overrides.containsKey(pos)) return;

        var override = overrides.computeIfAbsent(pos, pos1 -> new OverrideInfo());
        override.setManualMode(mode);

        if (!override.shouldKeep()) {
            overrides.remove(pos);
            if (keyImpl.isEmpty()) {
                this.removeKey(key);
            }
        }
    }

    public void setNameOverride(Identifier key, BlockPos pos, @NotNull String name) {
        boolean shouldRemove = name.isBlank();
        if (shouldRemove && !this.getKeys().contains(key)) return;

        MemoryKeyImpl keyImpl = this.getOrCreateKeyInternal(key);
        Map<BlockPos, OverrideInfo> overrides = keyImpl.overrides();
        if (shouldRemove && !overrides.containsKey(pos)) return;

        name = shouldRemove ? null : name.strip();

        OverrideInfo override = overrides.computeIfAbsent(pos, pos1 -> new OverrideInfo());
        override.setCustomName(name);

        // set to keep when adding a custom name
        if (!shouldRemove) {
            var manualMode = this.getMetadata().getFilteringSettings().manualMode;
            if (manualMode) {
                override.setManualMode(ManualMode.REMEMBER);
            } else if (override.getManualMode() == ManualMode.BLOCK) {
                override.setManualMode(ManualMode.DEFAULT);
            }
        }

        if (!override.shouldKeep()) {
            overrides.remove(pos);
            if (keyImpl.isEmpty()) {
                this.removeKey(key);
            }
        }
    }
}
