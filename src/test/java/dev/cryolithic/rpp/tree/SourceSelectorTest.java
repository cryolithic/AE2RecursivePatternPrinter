package dev.cryolithic.rpp.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.cryolithic.rpp.recipe.IngredientView;
import dev.cryolithic.rpp.recipe.RecipeAdapter;
import dev.cryolithic.rpp.recipe.RecipeAdapters;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

/**
 * Source tiering and default selection (DESIGN.md §8.4, §13.1). The iron
 * fixture is the canonical case; the rest cover the individual tiering rules.
 * Runs in a bare JVM on the shared {@link RecipeIndexFixture}.
 */
class SourceSelectorTest {
    private static final ReversalDetector.TagLookup NO_TAGS = item -> List.of();
    private static final SourceSelector.SelectionConfig CONFIG = new SourceSelector.SelectionConfig(
            0.95, false, 3, 1, true, List.of("minecraft", "ae2"));

    private static RecipeTreeBuilder builder(RecipeIndex index) {
        return new RecipeTreeBuilder(index, TreeLimits.DEFAULTS, NO_TAGS,
                new SourceSelector(index, TreeLimits.DEFAULTS, CONFIG, null));
    }

    private static RecipeTreeBuilder builder(RecipeIndex index, SourceSelector.SelectionConfig config,
            StickyChoices sticky) {
        return new RecipeTreeBuilder(index, TreeLimits.DEFAULTS, NO_TAGS,
                new SourceSelector(index, TreeLimits.DEFAULTS, config, sticky));
    }

    /** The recipe node under {@code node} with the given id. */
    private static RecipeNode recipeById(ItemNode node, String id) {
        for (RecipeNode recipe : node.recipes()) {
            if (recipe.recipe().id().toString().equals(id)) {
                return recipe;
            }
        }
        throw new AssertionError("no recipe " + id + " under " + node.goal());
    }

    private static Item itemOf(AEKey key) {
        return ((AEItemKey) key).getItem();
    }

    @Test
    void ironFixtureResolvesToThreeDistinctDestinations() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item iron = f.item("iron");
        Item block = f.item("iron_block");
        Item rawIron = f.item("raw_iron");
        Item ore = f.item("iron_ore");
        Item pickaxe = f.item("pickaxe");
        Item stick = f.item("stick");
        Item byproductPrimary = f.item("byproduct_primary");
        Item byproductInput = f.item("byproduct_input");

        // smelt: 1 iron from 1 raw iron (furnace, vanilla). The registered
        // SMELTING instance is what an in-game smelting recipe carries.
        f.machine("iron_smelt", RecipeType.SMELTING, f.stack(iron), Ingredient.of(rawIron));
        // block unpack: 9 iron from 1 block (crafting, vanilla) + forward
        f.shapeless("block_unpack", iron, 9, Ingredient.of(block));
        f.shapeless("iron_to_block", block, 1,
                Ingredient.of(iron), Ingredient.of(iron), Ingredient.of(iron),
                Ingredient.of(iron), Ingredient.of(iron), Ingredient.of(iron),
                Ingredient.of(iron), Ingredient.of(iron), Ingredient.of(iron));
        // ore chain: 4 iron from 1 ore (modded machine)
        f.machine("ore_chain",
                RecipeType.simple(ResourceLocation.fromNamespaceAndPath("mekanism", "ore_washer")),
                f.stack(iron, 4), Ingredient.of(ore));
        // pickaxe melt: 1 iron from 1 pickaxe (crafting) + forward
        f.shapeless("pickaxe_melt", iron, 1, Ingredient.of(pickaxe));
        f.shapeless("iron_to_pickaxe", pickaxe, 1,
                Ingredient.of(iron), Ingredient.of(iron), Ingredient.of(iron),
                Ingredient.of(stick), Ingredient.of(stick));
        // byproduct: primary is byproductPrimary, secondary is iron
        f.byproduct("byproduct_iron", List.of(f.stack(byproductPrimary), f.stack(iron)),
                Ingredient.of(byproductInput));

        RecipeTreeBuilder builder = builder(f.buildIndex());
        ItemNode root = builder.buildRoot(AEItemKey.of(iron), 2);

        // three live candidates: not forced, not collapsible
        assertFalse(root.isForced(), "three live candidates: nothing to auto-select");
        assertFalse(TreeSelection.isCollapsible(root), "a multi-candidate node never collapses");

        // tiers
        assertEquals(Tier.PRIMARY, recipeById(root, "rpp:iron_smelt").tier(), "smelt is the best ordinary path");
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:block_unpack").tier(), "lossless reversal is promoted");
        assertEquals(Tier.REJECTED, recipeById(root, "rpp:pickaxe_melt").tier(), "lossy reversal is recycling");
        assertEquals(Tier.REJECTED, recipeById(root, "rpp:byproduct_iron").tier(), "side product is junk");
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:ore_chain").tier(), "higher yield is promoted");

        // rejection reasons
        assertTrue(recipeById(root, "rpp:pickaxe_melt").rejectionReason().contains("33%"),
                "the recycling reason carries the round-trip percentage");
        assertEquals("produced as a side product", recipeById(root, "rpp:byproduct_iron").rejectionReason());

        // default selection: exactly smelt, block unpack, ore chain
        assertEquals(3, root.selected().cardinality());
        assertTrue(root.selected().get(0), "smelt (PRIMARY)");
        assertTrue(root.selected().get(1), "block unpack (ALTERNATE)");
        assertFalse(root.selected().get(2), "pickaxe melt (REJECTED)");
        assertFalse(root.selected().get(3), "byproduct (REJECTED)");
        assertTrue(root.selected().get(4), "ore chain (ALTERNATE)");

        // three distinct destinations, no collisions
        Destination smelt = recipeById(root, "rpp:iron_smelt").destination();
        Destination blockDest = recipeById(root, "rpp:block_unpack").destination();
        Destination oreDest = recipeById(root, "rpp:ore_chain").destination();
        assertNotEquals(smelt, blockDest);
        assertNotEquals(smelt, oreDest);
        assertNotEquals(blockDest, oreDest);
        for (RecipeNode recipe : root.recipes()) {
            assertFalse(recipe.isCollides(), "distinct destinations do not collide: " + recipe.recipe().id());
        }

        // plan: the three selected iron patterns each carry their own yield
        // (a raw-iron row also appears, because iron_to_block's iron input is a
        // cycle node — selectable as a raw input per §8.2, as in the M3a cycle test)
        List<PlanRow> plan = TreeSelection.planRows(root);
        Map<String, Integer> yields = new HashMap<>();
        for (PlanRow row : plan) {
            if (row.recipeId() != null) {
                yields.put(row.recipeId().toString(), row.yield());
            }
        }
        assertEquals(1, yields.get("rpp:iron_smelt"));
        assertEquals(9, yields.get("rpp:block_unpack"));
        assertEquals(4, yields.get("rpp:ore_chain"));
    }

    @Test
    void nuggetPairScoresOnePointZero() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item nugget = f.item("nugget");
        Item iron = f.item("nug_iron");
        Item stone = f.item("nug_stone");
        // candidate: 1 iron -> 9 nuggets (a lossless reversal of the forward)
        f.shapeless("nug_iron_to_nuggets", nugget, 9, Ingredient.of(iron));
        // forward: 9 nuggets -> 1 iron
        f.shapeless("nug_nuggets_to_iron", iron, 1,
                Ingredient.of(nugget), Ingredient.of(nugget), Ingredient.of(nugget),
                Ingredient.of(nugget), Ingredient.of(nugget), Ingredient.of(nugget),
                Ingredient.of(nugget), Ingredient.of(nugget), Ingredient.of(nugget));
        // ordinary path: 1 stone -> 1 nugget
        f.shapeless("nug_stone_to_nugget", nugget, 1, Ingredient.of(stone));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(nugget), 2);

        assertEquals(Tier.PRIMARY, recipeById(root, "rpp:nug_stone_to_nugget").tier());
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:nug_iron_to_nuggets").tier(),
                "a lossless reversal is promoted to ALTERNATE, never PRIMARY");
        assertEquals(1.0, recipeById(root, "rpp:nug_iron_to_nuggets").roundTripEfficiency(), 1e-9);
    }

    @Test
    void extraInputReversalIsCappedAtAlternateEvenAtHighEfficiency() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item g = f.item("cap_goal");
        Item input = f.item("cap_input");
        Item stone = f.item("cap_stone");
        Item stick = f.item("cap_stick");
        // candidate: 1 input -> 9 g; forward: 1 g + 2 sticks -> 1 input (high efficiency, extra inputs)
        f.shapeless("cap_input_to_g", g, 9, Ingredient.of(input));
        f.shapeless("cap_g_to_input", input, 1, Ingredient.of(g), Ingredient.of(stick), Ingredient.of(stick));
        // ordinary path: 1 stone -> 1 g
        f.shapeless("cap_stone_to_g", g, 1, Ingredient.of(stone));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(g), 2);

        RecipeNode reversal = recipeById(root, "rpp:cap_input_to_g");
        assertEquals(Tier.ALTERNATE, reversal.tier(),
                "a high-efficiency extra-input reversal is capped at ALTERNATE");
        assertTrue(reversal.roundTripEfficiency() >= 0.95, "the reversal is above the threshold");
        assertEquals(Tier.PRIMARY, recipeById(root, "rpp:cap_stone_to_g").tier());
    }

    @Test
    void storageTagFastPathAgreesWithComputedRatio() {
        RecipeIndexFixture items = new RecipeIndexFixture();
        Item ingot = items.item("tag_ingot");
        Item block = items.item("tag_block");
        ReversalDetector.TagLookup tags = item -> {
            if (item == block) {
                return List.of(ResourceLocation.fromNamespaceAndPath("c", "storage_blocks/iron"));
            }
            if (item == ingot) {
                return List.of(ResourceLocation.fromNamespaceAndPath("c", "ingots/iron"));
            }
            return List.of();
        };
        // fast path: tags present, no forward recipe
        RecipeIndexFixture f1 = new RecipeIndexFixture();
        f1.shapeless("tag_block_to_ingot", ingot, 9, Ingredient.of(block));
        RecipeIndex index1 = f1.buildIndex();
        ReversalDetector fast = new ReversalDetector(index1, tags);
        RecipeView fastView = viewFor(index1, ingot, "tag_block_to_ingot");
        assertEquals(1.0, fast.roundTripEfficiency(AEItemKey.of(ingot), fastView), 1e-9,
                "the storage-tag fast path is lossless");

        // computed ratio: no tags, a forward recipe present
        RecipeIndexFixture f2 = new RecipeIndexFixture();
        f2.shapeless("tag_block_to_ingot", ingot, 9, Ingredient.of(block));
        f2.shapeless("tag_ingot_to_block", block, 1,
                Ingredient.of(ingot), Ingredient.of(ingot), Ingredient.of(ingot),
                Ingredient.of(ingot), Ingredient.of(ingot), Ingredient.of(ingot),
                Ingredient.of(ingot), Ingredient.of(ingot), Ingredient.of(ingot));
        RecipeIndex index2 = f2.buildIndex();
        ReversalDetector computed = new ReversalDetector(index2, NO_TAGS);
        RecipeView computedView = viewFor(index2, ingot, "tag_block_to_ingot");
        assertEquals(1.0, computed.roundTripEfficiency(AEItemKey.of(ingot), computedView), 1e-9,
                "the computed ratio agrees with the fast path");
    }


    private static RecipeView viewFor(RecipeIndex index, Item goal, String recipeId) {
        return index.recipesFor(AEItemKey.of(goal)).stream()
                .filter(v -> v.id().toString().equals("rpp:" + recipeId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void namespaceMatchRanksGoalNamespacePrimary() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item gear = f.item("modb", "gear");
        Item x = f.item("ns_x");
        Item y = f.item("ns_y");
        f.shapeless("modb:gear_from_x", gear, 1, Ingredient.of(x));
        f.shapeless("rpp:gear_from_y", gear, 1, Ingredient.of(y));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(gear), 2);

        assertEquals(Tier.PRIMARY, recipeById(root, "modb:gear_from_x").tier(),
                "the goal-namespace recipe wins over a foreign one");
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:gear_from_y").tier());
    }

    @Test
    void preferredModsOverridesNamespaceMatch() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item thing = f.item("rpp", "thing");
        Item x = f.item("pref_x");
        Item y = f.item("pref_y");
        f.shapeless("rpp:thing_from_x", thing, 1, Ingredient.of(x));
        f.shapeless("ae2:thing_from_y", thing, 1, Ingredient.of(y));

        SourceSelector.SelectionConfig config = new SourceSelector.SelectionConfig(
                0.95, false, 3, 1, true, List.of("ae2"));
        ItemNode root = builder(f.buildIndex(), config, null).buildRoot(AEItemKey.of(thing), 2);

        assertEquals(Tier.PRIMARY, recipeById(root, "ae2:thing_from_y").tier(),
                "a preferred-mod recipe overrides a mere namespace match");
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:thing_from_x").tier());
    }

    @Test
    void byproductIsRejected() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item goal = f.item("bp_goal");
        Item primary = f.item("bp_primary");
        Item input = f.item("bp_input");
        f.byproduct("bp_recipe", List.of(f.stack(primary), f.stack(goal)), Ingredient.of(input));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(goal), 2);

        RecipeNode recipe = recipeById(root, "rpp:bp_recipe");
        assertEquals(Tier.REJECTED, recipe.tier());
        assertEquals("produced as a side product", recipe.rejectionReason());
    }

    @Test
    void destinationClassificationMatchesTheEncoder() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item item = f.item("dest_item");
        var stack = new appeng.api.stacks.GenericStack(AEItemKey.of(item), 1);

        assertEquals(Destination.assembler(), SourceSelector.destination(
                new dev.cryolithic.rpp.recipe.RecipeView(id("c"), RecipeType.simple(ResourceLocation.withDefaultNamespace("crafting")),
                        List.of(), List.of(stack), 3, 3, true)),
                "a 3x3 crafting recipe feeds an assembler");
        assertEquals(Destination.machine("minecraft:smelting"), SourceSelector.destination(
                new dev.cryolithic.rpp.recipe.RecipeView(id("s"), RecipeType.simple(ResourceLocation.withDefaultNamespace("smelting")),
                        List.of(), List.of(stack), 0, 0, true)),
                "a furnace recipe is a machine pattern");
        assertEquals(Destination.stonecutter(), SourceSelector.destination(
                new dev.cryolithic.rpp.recipe.RecipeView(id("t"), RecipeType.simple(ResourceLocation.withDefaultNamespace("stonecutting")),
                        List.of(), List.of(stack), 0, 0, true)),
                "a stonecutter recipe is a stonecutting pattern");
    }

    @Test
    void registeredModTypeIsNotVanillaAndHasStableDestination() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item g = f.item("rm_goal");
        Item a = f.item("rm_a");
        Item b = f.item("rm_b");
        RecipeType<?> modType = registeredType("modb", "enriching");
        f.machine("rm_mod", modType, f.stack(g), Ingredient.of(a));
        f.shapeless("rm_craft", g, 1, Ingredient.of(b));

        RecipeIndex index = f.buildIndex();
        // the destination is the registry path: stable across JVM runs,
        // never an identity hash
        assertEquals(Destination.machine("enriching"),
                SourceSelector.destination(viewFor(index, g, "rm_mod")));

        ItemNode root = builder(index).buildRoot(AEItemKey.of(g), 2);
        assertEquals(Tier.PRIMARY, recipeById(root, "rpp:rm_craft").tier(),
                "the vanilla crafting recipe outranks the registered mod type");
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:rm_mod").tier());
    }

    @Test
    void registeredVanillaTypeKeepsVanillaClassification() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item g = f.item("rv_goal");
        Item a = f.item("rv_a");
        Item b = f.item("rv_b");
        // RecipeType.SMELTING is the registered minecraft:smelting instance
        f.machine("rv_smelt", RecipeType.SMELTING, f.stack(g), Ingredient.of(a));
        f.machine("rv_mod", registeredType("modb", "enriching"), f.stack(g), Ingredient.of(b));

        RecipeIndex index = f.buildIndex();
        assertEquals(Destination.machine("smelting"),
                SourceSelector.destination(viewFor(index, g, "rv_smelt")),
                "the registered smelting type keeps its machine destination");

        ItemNode root = builder(index).buildRoot(AEItemKey.of(g), 2);
        assertEquals(Tier.PRIMARY, recipeById(root, "rpp:rv_smelt").tier(),
                "the registered vanilla type keeps the vanilla rank");
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:rv_mod").tier());
    }

    @Test
    void unregisteredIdentityTypeIsNotVanilla() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item g = f.item("iv_goal");
        Item a = f.item("iv_a");
        Item b = f.item("iv_b");
        // a type that implements RecipeType without overriding toString():
        // Object.toString() is an identity hash (e.g. "…Test$1@6f2b958e")
        RecipeType<?> identityType = new RecipeType<>() {
        };
        f.machine("iv_a_mod", registeredType("modb", "enriching"), f.stack(g), Ingredient.of(a));
        f.machine("iv_z_ident", identityType, f.stack(g), Ingredient.of(b));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(g), 2);

        // Neither candidate is vanilla: the identity type must not win the
        // vanilla rank or the +25 score. The tie is broken by recipe id, so
        // "iv_a_mod" becomes PRIMARY — under the old toString heuristic the
        // identity type (no colon) would have been "vanilla" and would have
        // taken the rank outright.
        assertEquals(Tier.PRIMARY, recipeById(root, "rpp:iv_a_mod").tier());
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:iv_z_ident").tier());
    }

    /** Registers a recipe type in {@code BuiltInRegistries.RECIPE_TYPE} (JVM-wide, once). */
    private static RecipeType<?> registeredType(String namespace, String path) {
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(namespace, path);
        if (!BuiltInRegistries.RECIPE_TYPE.containsKey(loc)) {
            ((MappedRegistry<RecipeType<?>>) BuiltInRegistries.RECIPE_TYPE).unfreeze();
            RecipeType<?> type = RecipeType.simple(loc);
            Registry.register(BuiltInRegistries.RECIPE_TYPE, loc, type);
        }
        return BuiltInRegistries.RECIPE_TYPE.get(loc);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("rpp", path);
    }

    @Test
    void destinationDefaultsCheckOneCraftingAndOneFurnace() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item g = f.item("dd_goal");
        Item a = f.item("dd_a");
        Item b = f.item("dd_b");
        Item c = f.item("dd_c");
        f.shapeless("dd_c1", g, 1, Ingredient.of(a));
        f.shapeless("dd_c2", g, 1, Ingredient.of(b));
        f.machine("dd_f1", RecipeType.simple(ResourceLocation.withDefaultNamespace("smelting")), f.stack(g), Ingredient.of(c));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(g), 2);

        assertTrue(root.selected().get(0), "one crafting recipe is auto-checked");
        assertFalse(root.selected().get(1), "the second crafting recipe is capped out of its destination");
        assertTrue(root.selected().get(2), "the furnace recipe is auto-checked");
        assertEquals(2, root.selected().cardinality());
    }

    @Test
    void costlyCollisionFlagsWhenCostsDiffer() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item g = f.item("cc_goal");
        Item a = f.item("cc_a");
        Item block = f.item("cc_block");
        // C1: ordinary crafting (efficiency NaN, depth 0)
        f.shapeless("cc_c1", g, 1, Ingredient.of(a));
        // C2: lossless unpack (efficiency 1.0, depth 1)
        f.shapeless("cc_c2", g, 9, Ingredient.of(block));
        f.shapeless("cc_g_to_block", block, 1,
                Ingredient.of(g), Ingredient.of(g), Ingredient.of(g),
                Ingredient.of(g), Ingredient.of(g), Ingredient.of(g),
                Ingredient.of(g), Ingredient.of(g), Ingredient.of(g));

        RecipeTreeBuilder builder = builder(f.buildIndex());
        ItemNode root = builder.buildRoot(AEItemKey.of(g), 2);
        // ensure both crafting recipes are checked
        for (int i = 0; i < root.recipes().size(); i++) {
            root.select(i);
        }
        TreeSelection.refreshCollisions(root, true);

        RecipeNode c1 = recipeById(root, "rpp:cc_c1");
        RecipeNode c2 = recipeById(root, "rpp:cc_c2");
        assertTrue(c1.isCollides() && c2.isCollides(), "same destination: both collide");
        assertTrue(c1.isCostlyCollision() && c2.isCostlyCollision(),
                "costs differ (one is a reversal): the costly flag is set");
    }

    @Test
    void twoLosslessUnpacksCollideSilently() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item g = f.item("lu_goal");
        Item blockA = f.item("lu_block_a");
        Item blockB = f.item("lu_block_b");
        f.shapeless("lu_u1", g, 9, Ingredient.of(blockA));
        f.shapeless("lu_u1_fwd", blockA, 1,
                Ingredient.of(g), Ingredient.of(g), Ingredient.of(g),
                Ingredient.of(g), Ingredient.of(g), Ingredient.of(g),
                Ingredient.of(g), Ingredient.of(g), Ingredient.of(g));
        f.shapeless("lu_u2", g, 9, Ingredient.of(blockB));
        f.shapeless("lu_u2_fwd", blockB, 1,
                Ingredient.of(g), Ingredient.of(g), Ingredient.of(g),
                Ingredient.of(g), Ingredient.of(g), Ingredient.of(g),
                Ingredient.of(g), Ingredient.of(g), Ingredient.of(g));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(g), 2);
        for (int i = 0; i < root.recipes().size(); i++) {
            root.select(i);
        }
        TreeSelection.refreshCollisions(root, true);

        RecipeNode u1 = recipeById(root, "rpp:lu_u1");
        RecipeNode u2 = recipeById(root, "rpp:lu_u2");
        assertTrue(u1.isCollides() && u2.isCollides(), "same destination: both collide");
        assertFalse(u1.isCostlyCollision() && u2.isCostlyCollision(),
                "two lossless unpacks at the same depth collide silently");
    }

    @Test
    void maxSourcesPerItemCapsAutoButNotManual() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item g = f.item("ms_goal");
        Item a = f.item("ms_a");
        Item b = f.item("ms_b");
        Item c = f.item("ms_c");
        Item d = f.item("ms_d");
        // four candidates in four distinct destinations (so the destination cap never binds)
        f.shapeless("ms_c1", g, 1, Ingredient.of(a)); // ASSEMBLER
        f.machine("ms_c2", RecipeType.simple(ResourceLocation.withDefaultNamespace("smelting")), f.stack(g), Ingredient.of(b)); // MACHINE:smelting
        f.machine("ms_c3", RecipeType.simple(ResourceLocation.withDefaultNamespace("stonecutting")), f.stack(g), Ingredient.of(c)); // STONECUTTER
        f.machine("ms_c4", RecipeType.simple(ResourceLocation.withDefaultNamespace("smithing")), f.stack(g), Ingredient.of(d)); // SMITHING

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(g), 2);

        assertEquals(3, root.selected().cardinality(), "auto-checked is capped at maxSourcesPerItem");
        root.select(3); // manually check the fourth
        assertEquals(4, root.selected().cardinality(), "manual selection is uncapped");
    }

    @Test
    void gappedRecipeBlankSlotIsNotADeadEnd() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item plank = f.item("gapss_plank");
        Item chest = f.item("gapss_chest");
        // sole source of the chest, with a blank center slot
        f.shapedGapped("gapss_chest_from_planks", chest, 1, Map.of('#', Ingredient.of(plank)),
                "###", "# #", "###");

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(chest), 2);

        RecipeNode recipe = recipeById(root, "rpp:gapss_chest_from_planks");
        assertEquals(Tier.PRIMARY, recipe.tier(),
                "a blank grid slot is not an input with no source");
        assertNull(recipe.rejectionReason(),
                "the dead-end rejection must not fire for a blank slot");
        assertTrue(root.selected().get(0), "the sole live candidate is auto-selected");
    }

    @Test
    void primaryIsSubjectToTheDestinationCap() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item ingot = f.item("modb", "ingot");
        Item block = f.item("modb", "ingot_block");
        Item x = f.item("cap39_x");
        // A lossless block-unpack with a goal-namespace match (ranks above
        // the primary) and a modc crafting primary: both feed the assembler
        f.shapeless("modb:ingot_unpack", ingot, 9, Ingredient.of(block));
        f.shapeless("modb:ingot_to_block", block, 1,
                Ingredient.of(ingot), Ingredient.of(ingot), Ingredient.of(ingot),
                Ingredient.of(ingot), Ingredient.of(ingot), Ingredient.of(ingot),
                Ingredient.of(ingot), Ingredient.of(ingot), Ingredient.of(ingot));
        f.shapeless("modc:ingot_from_x", ingot, 1, Ingredient.of(x));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(ingot), 2);

        assertEquals(Tier.ALTERNATE, recipeById(root, "modb:ingot_unpack").tier(),
                "the lossless reversal with a namespace match is promoted");
        assertEquals(Tier.PRIMARY, recipeById(root, "modc:ingot_from_x").tier());

        // Exactly one auto-checked source in the assembler class: the
        // higher-ranked ALTERNATE keeps its default check, the PRIMARY is
        // capped out of its destination class, and the per-item cap holds
        assertEquals(1, root.selected().cardinality(),
                "at most one default-checked source per destination class");
        assertTrue(root.selected().get(0), "the higher-ranked ALTERNATE is auto-checked");
        assertFalse(root.selected().get(1), "the PRIMARY is capped out of its destination class");
    }

    @Test
    void unknownInputRanksDeepestNotShallowest() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item goal = f.item("depth_goal");
        Item shallow = f.item("depth_shallow");
        Item shallowRaw = f.item("depth_shallow_raw");
        // A verified one-level chain: shallow -> raw, raw is a leaf
        f.shapeless("depth_shallow_recipe", goal, 1, Ingredient.of(shallow));
        f.shapeless("depth_shallow_source", shallow, 1, Ingredient.of(shallowRaw));
        // A nine-level chain: one level deeper than the root's budget of
        // eight, so the oracle exhausts its budget and returns UNKNOWN
        Item deep = f.item("depth_deep");
        f.shapeless("depth_deep_recipe", goal, 1, Ingredient.of(deep));
        Item prev = deep;
        for (int i = 1; i <= 9; i++) {
            Item next = f.item("depth_d" + i);
            f.shapeless("depth_r" + i, prev, 1, Ingredient.of(next));
            prev = next;
        }

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(goal), 2);

        assertEquals(Tier.PRIMARY, recipeById(root, "rpp:depth_shallow_recipe").tier(),
                "the shallow verified input outranks the budget-exhausted one");
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:depth_deep_recipe").tier());
        assertEquals(9, recipeById(root, "rpp:depth_deep_recipe").oracleDepth(),
                "UNKNOWN is the maximum depth: budget + 1, never clamped to 0");
        assertEquals(1, recipeById(root, "rpp:depth_shallow_recipe").oracleDepth());
    }

    @Test
    void unknownInputIsNotADeadEnd() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item goal = f.item("deadend_goal");
        Item deep = f.item("deadend_deep");
        f.shapeless("deadend_deep_recipe", goal, 1, Ingredient.of(deep));
        // Nine levels deep: the root's budget of eight runs out one level
        // before the chain bottoms out, so the oracle verdict is UNKNOWN
        Item prev = deep;
        for (int i = 1; i <= 9; i++) {
            Item next = f.item("deadend_d" + i);
            f.shapeless("deadend_r" + i, prev, 1, Ingredient.of(next));
            prev = next;
        }

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(goal), 2);

        RecipeNode recipe = recipeById(root, "rpp:deadend_deep_recipe");
        assertEquals(Tier.PRIMARY, recipe.tier(),
                "budget exhaustion is not 'an item with no source'");
        assertNull(recipe.rejectionReason(),
                "the dead-end rejection must not fire on an UNKNOWN input");
        assertTrue(root.isForced(), "the sole live candidate is forced");
        assertTrue(root.selected().get(0), "the forced candidate is auto-selected");
    }

    @Test
    void inputWithNoCandidatesIsStillADeadEnd() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item goal = f.item("nocand_goal");
        Item leaf = f.item("nocand_leaf"); // no recipe at all: a true leaf
        RecipeType<?> type = RecipeType.simple(ResourceLocation.fromNamespaceAndPath("rpp", "nocand_type"));
        Recipe<?> recipe = new Recipe<>() {
            @Override
            public boolean matches(RecipeInput input, Level level) {
                return false;
            }

            @Override
            public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean canCraftInDimensions(int width, int height) {
                return false;
            }

            @Override
            public ItemStack getResultItem(HolderLookup.Provider registries) {
                return new ItemStack(goal);
            }

            @Override
            public RecipeSerializer<?> getSerializer() {
                throw new UnsupportedOperationException();
            }

            @Override
            public RecipeType<?> getType() {
                return type;
            }
        };
        // The adapter's view carries an ingredient that resolved to no
        // candidates at all: a true dead end, distinct from an UNKNOWN
        // (budget-exhausted) input, which is still a valid source
        RecipeAdapter adapter = new RecipeAdapter() {
            @Override
            public RecipeType<?> type() {
                return type;
            }

            @Override
            public RecipeView view(RecipeHolder<?> holder, HolderLookup.Provider registries) {
                return new RecipeView(
                        ResourceLocation.fromNamespaceAndPath("rpp", "nocand_recipe"),
                        type,
                        List.of(new IngredientView(Ingredient.of(leaf), List.of())),
                        List.of(new GenericStack(AEItemKey.of(goal), 1)),
                        0, 0, true);
            }
        };
        RecipeAdapters.register(adapter);
        RecipeManager manager = new RecipeManager(f.provider());
        manager.replaceRecipes(List.of(new RecipeHolder<>(
                ResourceLocation.fromNamespaceAndPath("rpp", "nocand_recipe"), recipe)));
        RecipeIndex index = RecipeIndex.build(manager, f.provider(), Set.of());

        ItemNode root = builder(index).buildRoot(AEItemKey.of(goal), 2);

        RecipeNode node = recipeById(root, "rpp:nocand_recipe");
        assertEquals(Tier.REJECTED, node.tier(),
                "an input with no candidates at all is a true dead end");
        assertEquals("requires an item with no source", node.rejectionReason());
    }

    @Test
    void yieldPromotionComparesPerItemNotPerSlot() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item goal = f.item("yield_goal");
        Item p = f.item("yield_p");
        Item a = f.item("yield_a");
        Item d = f.item("yield_d");
        Item b1 = f.item("yield_b1");
        Item b2 = f.item("yield_b2");
        Item b3 = f.item("yield_b3");
        // Primary: one item in, one out
        f.shapeless("yield_r1", goal, 1, Ingredient.of(p));
        // Nine items in one slot, nine out: equal per-item yield
        f.shapeless("yield_r2", goal, 9, Ingredient.of(new ItemStack(a, 9)));
        // One item in, eighteen out: a genuinely higher per-item yield
        f.shapeless("yield_r3", goal, 18, Ingredient.of(d));
        // Three slots of three items each, nine out: equal per-item yield
        f.shapeless("yield_r4", goal, 9,
                Ingredient.of(new ItemStack(b1, 3)),
                Ingredient.of(new ItemStack(b2, 3)),
                Ingredient.of(new ItemStack(b3, 3)));
        // One tag slot (three alternatives, one item consumed), three out:
        // the slot consumes one item, so this is a higher per-item yield
        f.shapeless("yield_r5", goal, 3, Ingredient.of(b1, b2, b3));

        SourceSelector.SelectionConfig config = new SourceSelector.SelectionConfig(
                0.95, true, 3, 1, true, List.of("minecraft", "ae2"));
        ItemNode root = builder(f.buildIndex(), config, null).buildRoot(AEItemKey.of(goal), 2);

        assertEquals(Tier.PRIMARY, recipeById(root, "rpp:yield_r1").tier());
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:yield_r3").tier(),
                "a genuinely higher per-item yield is promoted");
        assertEquals(Tier.REJECTED, recipeById(root, "rpp:yield_r2").tier(),
                "nine out of nine items is not above one out of one");
        assertEquals("conservative: not a promoted path", recipeById(root, "rpp:yield_r2").rejectionReason());
        assertEquals(Tier.REJECTED, recipeById(root, "rpp:yield_r4").tier(),
                "nine out of nine items is not above one out of one, spread over three slots");
        assertEquals(Tier.ALTERNATE, recipeById(root, "rpp:yield_r5").tier(),
                "a tag slot consumes one item, so three out of one is a higher per-item yield");
    }
}
