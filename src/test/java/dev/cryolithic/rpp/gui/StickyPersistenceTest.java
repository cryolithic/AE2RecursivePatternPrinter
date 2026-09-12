package dev.cryolithic.rpp.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import dev.cryolithic.rpp.tree.SourceSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure file logic of {@link StickyPersistence}
 * (DESIGN.md §8.7): parse/serialize round-trip, tolerance of malformed
 * entries, and dropping of stale ids. No Minecraft runtime is required.
 */
class StickyPersistenceTest {
    static {
        // Force the shared console capture's static initializer
        // (RecipeIndexFixture) to run before this test initializes log4j2 via
        // StickyPersistence's logger. The fixture redirects System.err and
        // forces log4j2 to bind to that redirected stream; if log4j2 binds to
        // the original System.err first, the recipe-index console assertions
        // in other test classes silently capture nothing.
        RecipeIndexFixture.resetConsole();
    }

    private static ResourceLocation loc(String s) {
        int colon = s.indexOf(':');
        return ResourceLocation.fromNamespaceAndPath(s.substring(0, colon), s.substring(colon + 1));
    }

    @Test
    void serializeParseRoundTrips() {
        Map<String, Map<String, List<String>>> data = new LinkedHashMap<>();
        Map<String, List<String>> player = new LinkedHashMap<>();
        player.put("minecraft:iron_ingot", List.of("minecraft:smelt_iron", "mekanism:enriching/iron"));
        player.put("minecraft:redstone", List.of());
        data.put("Steve", player);

        String json = StickyPersistence.serialize(data);
        Map<String, Map<String, List<String>>> parsed = StickyPersistence.parse(json);

        assertEquals(data, parsed);
    }

    @Test
    void parseToleratesMalformedEntries() {
        String json = """
                {
                  "Steve": {
                    "minecraft:iron_ingot": ["minecraft:smelt_iron", 42, null],
                    "minecraft:bad_goal": "not_an_array",
                    "minecraft:redstone": ["minecraft:ore_redstone"]
                  },
                  "Broken": "not_an_object",
                  "Empty": {}
                }
                """;
        Map<String, Map<String, List<String>>> parsed = StickyPersistence.parse(json);

        // The non-object section "Broken" is dropped entirely.
        assertFalse(parsed.containsKey("Broken"));
        // The non-array goal "minecraft:bad_goal" is dropped.
        Map<String, List<String>> steve = parsed.get("Steve");
        assertFalse(steve.containsKey("minecraft:bad_goal"));
        // Non-string ids (42, null) are dropped; the valid id survives.
        assertEquals(List.of("minecraft:smelt_iron"), steve.get("minecraft:iron_ingot"));
        assertEquals(List.of("minecraft:ore_redstone"), steve.get("minecraft:redstone"));
        // An empty section is kept as an empty map.
        assertTrue(parsed.get("Empty").isEmpty());
    }

    @Test
    void parseUnreadableJsonYieldsEmptyMap() {
        assertTrue(StickyPersistence.parse("this is not json").isEmpty());
        assertTrue(StickyPersistence.parse("[1,2,3]").isEmpty()); // not an object
    }

    @Test
    void toStoreDropsStaleAndMalformedIds() {
        Map<String, List<String>> section = new LinkedHashMap<>();
        // A well-formed goal with one valid and one stale recipe id.
        section.put("minecraft:iron_ingot", List.of("minecraft:smelt_iron", "not a location"));
        // A goal that is not a valid registry location (stale after a pack change).
        section.put("Invalid Goal", List.of("minecraft:smelt_iron"));
        // A goal whose every recipe id is stale: the goal survives with an empty set.
        section.put("minecraft:gone", List.of("also not valid"));

        Map<ResourceLocation, SourceSet> store = StickyPersistence.toStore(section);

        // The malformed goal is dropped; the two valid goals remain.
        assertEquals(2, store.size());
        SourceSet iron = store.get(loc("minecraft:iron_ingot"));
        // The stale recipe id is dropped, the valid one kept.
        assertEquals(Set.of(loc("minecraft:smelt_iron")), iron.recipeIds());
        // A goal with only stale recipe ids is kept with an empty set.
        assertTrue(store.get(loc("minecraft:gone")).recipeIds().isEmpty());
    }

    @Test
    void fromStoreToStoreRoundTrips() {
        Map<ResourceLocation, SourceSet> store = new LinkedHashMap<>();
        Set<ResourceLocation> ids = new LinkedHashSet<>(List.of(loc("minecraft:smelt_iron"), loc("mekanism:enriching/iron")));
        store.put(loc("minecraft:iron_ingot"), new SourceSet(loc("minecraft:iron_ingot"), ids));
        store.put(loc("minecraft:redstone"), new SourceSet(loc("minecraft:redstone"), Set.of()));

        Map<String, List<String>> section = StickyPersistence.fromStore(store);
        Map<ResourceLocation, SourceSet> roundTripped = StickyPersistence.toStore(section);

        assertEquals(store, roundTripped);
    }
}
