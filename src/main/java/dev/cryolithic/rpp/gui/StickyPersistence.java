package dev.cryolithic.rpp.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cryolithic.rpp.tree.SourceSet;
import dev.cryolithic.rpp.tree.StickyChoices;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side persistence for the sticky source sets (DESIGN.md §8.7).
 * A single file in the client config directory
 * ({@code config/rpp/sticky_choices.json}) holds one section per player:
 * the player's remembered recipe ids, keyed by goal item.
 *
 * <p>Loaded on client start (mod-bus {@link FMLClientSetupEvent}) and again
 * lazily the first time a tree session opens, in case the client was not
 * ready at event time. Saved on every change, crash-safe: the new content is
 * written to a temp file and moved over the target, so a crash mid-write
 * never corrupts the previous file.</p>
 *
 * <p>The file stores item and recipe ids as plain strings and tolerates
 * stale ids: an id that no longer parses (or no longer exists) is dropped
 * silently on load. Stale recipe ids are dropped again at apply time by the
 * {@code SourceSelector}, so a remembered set that has gone stale falls back
 * to tiering without error.</p>
 *
 * <p>The pure file logic ({@link #parse}, {@link #serialize},
 * {@link #toStore}, {@link #fromStore}) is static and free of Minecraft
 * runtime so it is unit-testable in a bare JVM.</p>
 */
public final class StickyPersistence implements StickyChoices.Persistence {
    private static final Logger LOGGER = LoggerFactory.getLogger(StickyPersistence.class);
    private static final String SUBDIR = "rpp";
    private static final String FILE_NAME = "sticky_choices.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The parsed file: player name → goal item id → recipe ids. */
    private static final Map<String, Map<String, List<String>>> CACHE = new LinkedHashMap<>();
    private static volatile boolean loaded;

    /** The single client-side instance, wired into every {@link StickyChoices}. */
    public static final StickyPersistence INSTANCE = new StickyPersistence();

    private StickyPersistence() {
    }

    /** Registers the client-start load on the mod bus. */
    public static void init(IEventBus modBus) {
        modBus.addListener(StickyPersistence::onClientSetup);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        loadFile();
    }

    // --- StickyChoices.Persistence ---

    @Override
    public Map<ResourceLocation, SourceSet> load() {
        loadFile();
        String player = currentPlayerName();
        if (player == null) {
            return Map.of();
        }
        Map<String, List<String>> section = CACHE.get(player);
        return section == null ? Map.of() : toStore(section);
    }

    @Override
    public void save(Map<ResourceLocation, SourceSet> sets) {
        loadFile();
        String player = currentPlayerName();
        if (player == null) {
            return;
        }
        synchronized (CACHE) {
            CACHE.put(player, fromStore(sets));
        }
        saveFile();
    }

    // --- file I/O ---

    @Nullable
    private static Path configDir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null; // client not constructed yet; retry on the next call
        }
        return mc.gameDirectory.toPath().resolve("config");
    }

    private static synchronized void loadFile() {
        if (loaded) {
            return;
        }
        Path dir = configDir();
        if (dir == null) {
            return; // not ready; the next call retries
        }
        loaded = true; // read at most once per client run
        Path path = dir.resolve(SUBDIR).resolve(FILE_NAME);
        if (!Files.exists(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            synchronized (CACHE) {
                CACHE.clear();
                CACHE.putAll(parse(json));
            }
        } catch (Exception e) {
            LOGGER.warn("rpp could not read sticky choices from {}; starting fresh", path, e);
        }
    }

    private static synchronized void saveFile() {
        Path dir = configDir();
        if (dir == null) {
            return;
        }
        Path subdir = dir.resolve(SUBDIR);
        Path target = subdir.resolve(FILE_NAME);
        try {
            Files.createDirectories(subdir);
            Path tmp = Files.createTempFile(subdir, FILE_NAME, ".tmp");
            try {
                String json;
                synchronized (CACHE) {
                    json = serialize(CACHE);
                }
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            LOGGER.warn("rpp could not save sticky choices to {}", target, e);
        }
    }

    @Nullable
    private static String currentPlayerName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        LocalPlayer player = mc.player;
        return player == null ? null : player.getName().getString();
    }

    // --- pure file logic (testable without a Minecraft runtime) ---

    /**
     * Parses the file into a player → goal → recipe-ids map. Malformed
     * entries (a non-object section, a non-array goal, a non-string id) are
     * skipped silently so a partially corrupted file still loads.
     */
    public static Map<String, Map<String, List<String>>> parse(String json) {
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return result; // unreadable file: start fresh
        }
        for (Map.Entry<String, JsonElement> playerEntry : root.entrySet()) {
            if (!playerEntry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject section = playerEntry.getValue().getAsJsonObject();
            Map<String, List<String>> goals = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> goalEntry : section.entrySet()) {
                if (!goalEntry.getValue().isJsonArray()) {
                    continue;
                }
                List<String> ids = new ArrayList<>();
                for (JsonElement id : goalEntry.getValue().getAsJsonArray()) {
                    if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                        ids.add(id.getAsString());
                    }
                }
                goals.put(goalEntry.getKey(), ids);
            }
            result.put(playerEntry.getKey(), goals);
        }
        return result;
    }

    /** Serializes a player → goal → recipe-ids map to the file's JSON form. */
    public static String serialize(Map<String, Map<String, List<String>>> data) {
        return GSON.toJson(data);
    }

    /**
     * Converts one player's file section to the in-memory store, dropping
     * any goal or recipe id that no longer parses as a registry location
     * (stale after a mod or pack change).
     */
    public static Map<ResourceLocation, SourceSet> toStore(Map<String, List<String>> section) {
        Map<ResourceLocation, SourceSet> store = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : section.entrySet()) {
            ResourceLocation goal = parseLocation(entry.getKey());
            if (goal == null) {
                continue; // stale or malformed goal: drop silently
            }
            Set<ResourceLocation> ids = new LinkedHashSet<>();
            for (String id : entry.getValue()) {
                ResourceLocation location = parseLocation(id);
                if (location != null) {
                    ids.add(location);
                }
            }
            store.put(goal, new SourceSet(goal, ids));
        }
        return store;
    }

    /** Converts the in-memory store to one player's file section. */
    public static Map<String, List<String>> fromStore(Map<ResourceLocation, SourceSet> sets) {
        Map<String, List<String>> section = new LinkedHashMap<>();
        for (SourceSet set : sets.values()) {
            section.put(set.goalItem().toString(),
                    set.recipeIds().stream().map(ResourceLocation::toString).toList());
        }
        return section;
    }

    @Nullable
    private static ResourceLocation parseLocation(String input) {
        try {
            return ResourceLocation.parse(input);
        } catch (Exception e) {
            return null;
        }
    }
}
