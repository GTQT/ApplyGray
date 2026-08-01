package applygray.integration.ae2;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.recipe.RecipeBinding;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.world.World;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Versioned, world-local hints for exact RecipeMap edges previously observed in an AE2 recursion cycle. */
final class CycleMemoryStore {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_SIGNATURES_PER_ENTRY = 8;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Path, CycleMemoryStore> STORES = new ConcurrentHashMap<>();

    private final Path path;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean dirty;

    private CycleMemoryStore(Path path) {
        this.path = path;
        load();
    }

    static CycleMemoryStore forWorld(World world) {
        if (world == null || world.isRemote || world.getSaveHandler() == null) return null;
        File mapFile = world.getSaveHandler().getMapFileFromName("applygray_recipe_cycle_memory");
        if (mapFile == null || mapFile.getParentFile() == null) return null;
        Path path = mapFile.getParentFile().toPath().resolve("applygray_recipe_cycle_memory.json")
                .toAbsolutePath().normalize();
        return STORES.computeIfAbsent(path, CycleMemoryStore::new);
    }

    static CycleMemoryStore forPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        STORES.remove(normalized);
        return new CycleMemoryStore(normalized);
    }

    synchronized boolean isRemembered(RecipeBinding binding) {
        return binding != null && entries.containsKey(key(binding));
    }

    synchronized boolean record(RecipeBinding binding, String cycleSignature) {
        if (binding == null || cycleSignature == null || cycleSignature.isEmpty()) return false;
        String key = key(binding);
        Entry entry = entries.get(key);
        boolean created = false;
        if (entry == null) {
            entry = Entry.from(binding);
            entries.put(key, entry);
            created = true;
        }
        entry.observations++;
        entry.lastSeenEpochMillis = System.currentTimeMillis();
        if (!entry.cycleSignatures.contains(cycleSignature)) {
            if (entry.cycleSignatures.size() >= MAX_SIGNATURES_PER_ENTRY) {
                entry.cycleSignatures.remove(0);
            }
            entry.cycleSignatures.add(cycleSignature);
        }
        dirty = true;
        return created;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized void flush() {
        if (!dirty) return;
        trimOldestEntries();
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(new StoreFile(new ArrayList<>(entries.values()))),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException failure) {
            ApplyGrayMod.LOGGER.warn("Could not persist RecipeMap cycle memory to {}", path, failure);
        }
    }

    private void load() {
        if (!Files.isRegularFile(path)) return;
        try {
            StoreFile stored = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), StoreFile.class);
            if (stored == null || stored.schemaVersion != SCHEMA_VERSION || stored.entries == null) {
                ApplyGrayMod.LOGGER.warn("Ignored incompatible RecipeMap cycle memory file {}", path);
                return;
            }
            for (Entry entry : stored.entries) {
                if (entry == null || !entry.isValid()) continue;
                entries.put(entry.key(), entry);
            }
            trimOldestEntries();
            ApplyGrayMod.LOGGER.info("Loaded {} versioned RecipeMap cycle hint(s) from {}", entries.size(), path);
        } catch (IOException | JsonParseException failure) {
            ApplyGrayMod.LOGGER.warn("Could not load RecipeMap cycle memory from {}; starting empty", path, failure);
            entries.clear();
        }
    }

    private void trimOldestEntries() {
        if (entries.size() <= MAX_ENTRIES) return;
        List<Entry> oldestFirst = new ArrayList<>(entries.values());
        oldestFirst.sort(Comparator.comparingLong(entry -> entry.lastSeenEpochMillis));
        int removeCount = entries.size() - MAX_ENTRIES;
        for (int index = 0; index < removeCount; index++) {
            entries.remove(oldestFirst.get(index).key());
        }
    }

    private static String key(RecipeBinding binding) {
        return binding.getTargetKey() + '\n' + binding.getRecipeMapId() + '\n' +
                binding.getRecipeFingerprint() + '\n' + binding.getRecipeMapContentVersion() + '\n' +
                binding.getRuleSetVersion() + '\n' + binding.getMachineProfileVersion();
    }

    private static final class StoreFile {

        private int schemaVersion = SCHEMA_VERSION;
        private List<Entry> entries = new ArrayList<>();

        private StoreFile() {
        }

        private StoreFile(List<Entry> entries) {
            this.entries = entries;
        }
    }

    private static final class Entry {

        private String targetKey;
        private String recipeMapId;
        private String recipeFingerprint;
        private String recipeMapContentVersion;
        private String ruleSetVersion;
        private String machineProfileVersion;
        private int observations;
        private long lastSeenEpochMillis;
        private List<String> cycleSignatures = new ArrayList<>();

        private static Entry from(RecipeBinding binding) {
            Entry entry = new Entry();
            entry.targetKey = binding.getTargetKey();
            entry.recipeMapId = binding.getRecipeMapId();
            entry.recipeFingerprint = binding.getRecipeFingerprint();
            entry.recipeMapContentVersion = binding.getRecipeMapContentVersion();
            entry.ruleSetVersion = binding.getRuleSetVersion();
            entry.machineProfileVersion = binding.getMachineProfileVersion();
            return entry;
        }

        private boolean isValid() {
            return hasText(targetKey) && hasText(recipeMapId) && hasText(recipeFingerprint) &&
                    hasText(recipeMapContentVersion) && hasText(ruleSetVersion) && hasText(machineProfileVersion);
        }

        private String key() {
            return targetKey + '\n' + recipeMapId + '\n' + recipeFingerprint + '\n' + recipeMapContentVersion + '\n' +
                    ruleSetVersion + '\n' + machineProfileVersion;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isEmpty();
        }
    }
}
