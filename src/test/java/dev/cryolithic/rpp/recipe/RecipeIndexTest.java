package dev.cryolithic.rpp.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
/**
 * Unit tests for the recipe index (DESIGN.md §6). Runs as plain JUnit with
 * no Minecraft runtime: {@link RecipeIndexFixture} boots the vanilla
 * registries in a bare JVM and feeds a real {@code RecipeManager}
 * (constructed and populated via {@code replaceRecipes}) with hand-crafted
 * recipes.
 */
class RecipeIndexTest {
    private RecipeIndexFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new RecipeIndexFixture();
    }

    @Test
    void buildBucketsRecipesUnderEachOutputItem() {
        Item ingot = fixture.item("bucket_ingot");
        Item nugget = fixture.item("bucket_nugget");
        fixture.shaped("bucket_a1", ingot, 1, Map.of('X', Ingredient.of(ingot)), "X");
        fixture.shapeless("bucket_a2", ingot, 2, Ingredient.of(ingot));
        fixture.generic("bucket_b", fixture.type("bucket_b"), () -> fixture.stack(nugget), Ingredient.of(ingot));

        RecipeIndex index = fixture.buildIndex();

        assertEquals(2, index.byOutput().get(ingot).size());
        assertEquals(1, index.byOutput().get(nugget).size());
        assertEquals(3, index.recipeCount());
        assertEquals(2, index.recipesFor(AEItemKey.of(ingot)).size());
        assertEquals(1, index.recipesFor(AEItemKey.of(nugget)).size());
        assertEquals(List.of(), index.recipesFor(AEItemKey.of(fixture.item("bucket_unknown"))));
    }

    @Test
    void shapedRecipeExtractsTrustedWithGridDimensions() {
        Item ingot = fixture.item("shaped_ingot");
        Item stick = fixture.item("shaped_stick");
        fixture.shaped("shaped_test", ingot, 3,
                Map.of('X', Ingredient.of(ingot), 'S', Ingredient.of(stick)),
                "XXX", "XSX");

        RecipeView view = fixture.buildIndex().recipesFor(AEItemKey.of(ingot)).get(0);

        assertEquals(3, view.gridWidth());
        assertEquals(2, view.gridHeight());
        assertSame(RecipeType.CRAFTING, view.type());
        assertEquals("rpp:shaped_test", view.id().toString());
        assertEquals(6, view.inputs().size());
        assertEquals(1, view.outputs().size());
        assertEquals(3, view.outputs().get(0).amount());
        assertNull(view.inputs().get(0).tagId());
    }

    @Test
    void shapelessRecipeExtractsTrustedWithoutGrid() {
        Item ingot = fixture.item("shapeless_ingot");
        fixture.shapeless("shapeless_test", ingot, 1, Ingredient.of(ingot), Ingredient.of(ingot));

        RecipeView view = fixture.buildIndex().recipesFor(AEItemKey.of(ingot)).get(0);

        assertTrue(view.trusted());
        assertEquals(0, view.gridWidth());
        assertEquals(0, view.gridHeight());
        assertEquals(2, view.inputs().size());
    }

    @Test
    void genericRecipeExtractsUntrusted() {
        Item ingot = fixture.item("generic_ingot");
        RecipeType<?> type = fixture.type("generic_type");
        fixture.generic("generic_test", type, () -> fixture.stack(ingot), Ingredient.of(ingot));

        RecipeView view = fixture.buildIndex().recipesFor(AEItemKey.of(ingot)).get(0);

        assertFalse(view.trusted());
        assertEquals(0, view.gridWidth());
        assertEquals(0, view.gridHeight());
        assertSame(type, view.type());
        assertEquals("rpp:generic_test", view.id().toString());
    }

    @Test
    void tagIngredientExposesTagIdAndCandidates() {
        Item a = fixture.item("tag_member_a");
        Item b = fixture.item("tag_member_b");
        Item c = fixture.item("tag_member_c");
        var tag = fixture.tag("members", a, b, c);
        Item ingot = fixture.item("tag_ingot");
        fixture.shapeless("tag_test", ingot, 1, Ingredient.of(tag), Ingredient.of(a));

        RecipeView view = fixture.buildIndex().recipesFor(AEItemKey.of(ingot)).get(0);

        assertEquals(tag.location(), view.inputs().get(0).tagId());
        assertEquals(List.of(a, b, c), view.inputs().get(0).candidates());
        assertNull(view.inputs().get(1).tagId());
        assertEquals(List.of(a), view.inputs().get(1).candidates());
    }

    @Test
    void failingRecipesAreExcludedAndCountedInOneSummaryLine() {
        Item ingot = fixture.item("failing_ingot");
        fixture.generic("failing_empty", fixture.type("failing_empty"), () -> net.minecraft.world.item.ItemStack.EMPTY,
                Ingredient.of(ingot));
        fixture.generic("failing_throwing", fixture.type("failing_throwing"),
                () -> {
                    throw new IllegalStateException("boom");
                },
                Ingredient.of(ingot));
        fixture.shaped("failing_ok", ingot, 1, Map.of('X', Ingredient.of(ingot)), "X");

        // Clear the shared console capture (redirected by the fixture's
        // class initializer, before log4j2 binds its console appender),
        // then build.
        RecipeIndexFixture.resetConsole();
        RecipeIndex index = fixture.buildIndex();

        // Both failing recipes are excluded; only the healthy one is indexed.
        assertEquals(1, index.recipeCount());
        assertEquals(1, index.recipesFor(AEItemKey.of(ingot)).size());
        assertEquals("rpp:failing_ok", index.recipesFor(AEItemKey.of(ingot)).get(0).id().toString());

        // Exactly one summary line per build, with the failure count.
        List<String> summaryLines = RecipeIndexFixture.consoleLines().stream()
                .filter(line -> line.contains("rpp recipe index:"))
                .toList();
        assertEquals(1, summaryLines.size());
        assertTrue(summaryLines.get(0).contains("1 recipes indexed"), summaryLines.get(0));
        assertTrue(summaryLines.get(0).contains("2 extraction failures"), summaryLines.get(0));

    }
    @Test
    void blacklistedTypesAreSkipped() {
        Item ingot = fixture.item("blacklist_ingot");
        Item nugget = fixture.item("blacklist_nugget");
        fixture.shaped("blacklist_craft", ingot, 1, Map.of('X', Ingredient.of(ingot)), "X");
        fixture.generic("blacklist_generic", fixture.type("blacklist_generic"), () -> fixture.stack(nugget),
                Ingredient.of(ingot));

        // Full registry id of the crafting type.
        RecipeIndex byFullId = fixture.buildIndex(List.of("minecraft:crafting"));
        assertEquals(1, byFullId.recipeCount());
        assertEquals(List.of(), byFullId.recipesFor(AEItemKey.of(ingot)));
        assertEquals(1, byFullId.recipesFor(AEItemKey.of(nugget)).size());

        RecipeIndex byShortName = fixture.buildIndex(List.of("crafting"));
        assertEquals(1, byShortName.recipeCount());
        assertEquals(1, byShortName.recipesFor(AEItemKey.of(nugget)).size());

        // Nothing blacklisted: both recipes indexed.
        assertEquals(2, fixture.buildIndex().recipeCount());
    }

    @Test
    void requireTrustedRecipesHidesUntrustedRecipes() {
        Item ingot = fixture.item("trust_ingot");
        Item nugget = fixture.item("trust_nugget");
        fixture.shaped("trust_craft", ingot, 1, Map.of('X', Ingredient.of(ingot)), "X");
        fixture.generic("trust_generic", fixture.type("trust_generic"), () -> fixture.stack(nugget),
                Ingredient.of(ingot));

        // Flag off: both recipes are indexed, untrusted included.
        RecipeIndex open = fixture.buildIndex(Set.of(), false);
        assertEquals(2, open.recipeCount());
        assertEquals(1, open.recipesFor(AEItemKey.of(ingot)).size());
        assertEquals(1, open.recipesFor(AEItemKey.of(nugget)).size());

        // Flag on: the generic (untrusted) recipe never enters the index;
        // the trusted crafting recipe is unaffected.
        RecipeIndex strict = fixture.buildIndex(Set.of(), true);
        assertEquals(1, strict.recipeCount());
        assertEquals(1, strict.recipesFor(AEItemKey.of(ingot)).size());
        assertEquals("rpp:trust_craft", strict.recipesFor(AEItemKey.of(ingot)).get(0).id().toString());
        assertEquals(List.of(), strict.recipesFor(AEItemKey.of(nugget)));
    }

    @Test
    void adapterThrowingOnOneRecipeIsIsolatedAndCounted() {
        Item ingot = fixture.item("adapter_ingot");
        RecipeType<?> type = fixture.type("adapter_type");
        fixture.generic("adapter_good1", type, () -> fixture.stack(ingot), Ingredient.of(ingot));
        fixture.generic("adapter_bad", type, () -> fixture.stack(ingot), Ingredient.of(ingot));
        fixture.generic("adapter_good2", type, () -> fixture.stack(ingot), Ingredient.of(ingot));

        try {
            RecipeAdapters.register(new RecipeAdapter() {
                @Override
                public RecipeType<?> type() {
                    return type;
                }

                @Override
                public RecipeView view(RecipeHolder<?> holder, HolderLookup.Provider registries) {
                    if (holder.id().equals(ResourceLocation.fromNamespaceAndPath("rpp", "adapter_bad"))) {
                        throw new IllegalStateException("adapter boom");
                    }
                    return new RecipeView(holder.id(), type, List.of(),
                            List.of(new GenericStack(AEItemKey.of(ingot), 1)), 0, 0, true);
                }
            });

            RecipeIndexFixture.resetConsole();
            RecipeIndex index = fixture.buildIndex();

            // The build completes: the throwing recipe is excluded and the
            // other two are indexed via the adapter.
            assertEquals(2, index.recipeCount());
            List<String> ids = index.recipesFor(AEItemKey.of(ingot)).stream()
                    .map(view -> view.id().toString())
                    .sorted()
                    .toList();
            assertEquals(List.of("rpp:adapter_good1", "rpp:adapter_good2"), ids);

            List<String> summaryLines = RecipeIndexFixture.consoleLines().stream()
                    .filter(line -> line.contains("rpp recipe index:"))
                    .toList();
            assertEquals(1, summaryLines.size());
            assertTrue(summaryLines.get(0).contains("2 recipes indexed"), summaryLines.get(0));
            assertTrue(summaryLines.get(0).contains("1 extraction failures"), summaryLines.get(0));
        } finally {
            RecipeAdapters.unregister(type);
        }
    }

    @Test
    void adapterDisabledAfterRepeatedThrowsFallsBackToVanillaExtraction() {
        Item ingot = fixture.item("guard_ingot");
        RecipeType<?> type = fixture.type("guard_type");
        for (int i = 1; i <= 12; i++) {
            fixture.generic("guard_" + i, type, () -> fixture.stack(ingot), Ingredient.of(ingot));
        }

        try {
            List<String> calls = new ArrayList<>();
            RecipeAdapters.register(new RecipeAdapter() {
                @Override
                public RecipeType<?> type() {
                    return type;
                }

                @Override
                public RecipeView view(RecipeHolder<?> holder, HolderLookup.Provider registries) {
                    calls.add(holder.id().toString());
                    throw new IllegalStateException("adapter boom");
                }
            });

            RecipeIndexFixture.resetConsole();
            RecipeIndex index = fixture.buildIndex();

            // The adapter is called exactly 10 times, then disabled; the
            // remaining two recipes fall back to the generic (untrusted)
            // path and are still indexed.
            assertEquals(10, calls.size());
            assertEquals(2, index.recipeCount());
            List<String> ids = index.recipesFor(AEItemKey.of(ingot)).stream()
                    .map(view -> view.id().toString())
                    .sorted()
                    .toList();
            assertEquals(List.of("rpp:guard_11", "rpp:guard_12"), ids);

            List<String> summaryLines = RecipeIndexFixture.consoleLines().stream()
                    .filter(line -> line.contains("rpp recipe index:"))
                    .toList();
            assertEquals(1, summaryLines.size());
            assertTrue(summaryLines.get(0).contains("2 recipes indexed"), summaryLines.get(0));
            assertTrue(summaryLines.get(0).contains("10 extraction failures"), summaryLines.get(0));

            // The adapter is disabled exactly once, with its type logged.
            List<String> disableLines = RecipeIndexFixture.consoleLines().stream()
                    .filter(line -> line.contains("rpp:guard_type"))
                    .toList();
            assertEquals(1, disableLines.size());
            assertTrue(disableLines.get(0).contains("disabling"), disableLines.get(0));
        } finally {
            RecipeAdapters.unregister(type);
        }
    }
}
