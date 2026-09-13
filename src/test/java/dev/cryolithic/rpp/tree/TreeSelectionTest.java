package dev.cryolithic.rpp.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

import org.junit.jupiter.api.Test;

/**
 * Selection state and plan flattening (DESIGN.md §8.5, §11.2, §13.1):
 * select-all / deselect-all, reset-to-defaults, raw-input pruning, the
 * plan-connectedness predicate, and the over-cap flag. Runs in a bare JVM.
 */
class TreeSelectionTest {
    private static final ReversalDetector.TagLookup NO_TAGS = item -> List.of();
    private static final SourceSelector.SelectionConfig CONFIG = new SourceSelector.SelectionConfig(
            0.95, false, 3, 1, true, List.of("minecraft", "ae2"));

    private static RecipeTreeBuilder builder(RecipeIndex index) {
        return new RecipeTreeBuilder(index, TreeLimits.DEFAULTS, NO_TAGS,
                new SourceSelector(index, TreeLimits.DEFAULTS, CONFIG, null));
    }

    private static SourceSelector selector(RecipeIndex index) {
        return new SourceSelector(index, TreeLimits.DEFAULTS, CONFIG, null);
    }

    @Test
    void selectAllAndDeselectAllProduceExpectedPlanSizes() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item x = f.item("sa_x");
        Item a = f.item("sa_a");
        Item b = f.item("sa_b");
        // two crafting recipes for X (same destination), so the default caps one
        f.shapeless("sa_c1", x, 1, Ingredient.of(a));
        f.shapeless("sa_c2", x, 1, Ingredient.of(b));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(x), 1);
        assertEquals(2, TreeSelection.planRows(root).size(), "default: one crafting + its raw input");

        TreeSelection.selectAll(root);
        assertEquals(4, TreeSelection.planRows(root).size(), "select-all: both recipes + both raw inputs");

        TreeSelection.deselectAll(root);
        assertTrue(root.isRawInput(), "deselecting everything marks the item a raw input");
        assertEquals(1, TreeSelection.planRows(root).size(), "deselect-all: one raw-input row, subtree pruned");
    }

    @Test
    void resetToDefaultsRestoresTieringExactly() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item x = f.item("rd_x");
        Item a = f.item("rd_a");
        Item b = f.item("rd_b");
        f.shapeless("rd_c1", x, 1, Ingredient.of(a));
        f.shapeless("rd_c2", x, 1, Ingredient.of(b));

        RecipeTreeBuilder builder = builder(f.buildIndex());
        ItemNode root = builder.buildRoot(AEItemKey.of(x), 1);
        // the default checks c1 (PRIMARY) and caps c2
        assertTrue(root.selected().get(0));
        assertFalse(root.selected().get(1));

        // the player overrides: check c2, uncheck c1
        root.deselect(0);
        root.select(1);
        root.recipes().get(0).setUserOverridden(true);
        root.recipes().get(1).setUserOverridden(true);

        TreeSelection.resetToDefaults(root, selector(f.buildIndex()));
        assertTrue(root.selected().get(0), "reset restores the tiered default (c1)");
        assertFalse(root.selected().get(1), "reset restores the tiered default (c2 capped)");
        assertFalse(root.recipes().get(0).isUserOverridden(), "the override flag is cleared");
        assertFalse(root.recipes().get(1).isUserOverridden());
    }

    @Test
    void deselectAllMarksRawInputAndPrunesSubtree() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item x = f.item("ri_x");
        Item a = f.item("ri_a");
        Item c = f.item("ri_c");
        f.shapeless("ri_a_to_x", x, 1, Ingredient.of(a));
        f.shapeless("ri_c_to_a", a, 1, Ingredient.of(c));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(x), 2);
        assertEquals(3, TreeSelection.planRows(root).size(), "default: two patterns + one raw input");

        TreeSelection.deselectAll(root);
        assertTrue(root.isRawInput());
        assertEquals(1, TreeSelection.planRows(root).size(), "the subtree is pruned: one raw-input row");
    }

    @Test
    void patternCountExcludesRawInputRows() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item x = f.item("pcn_x");
        Item a = f.item("pcn_a");
        Item c = f.item("pcn_c");
        f.shapeless("pcn_a_to_x", x, 1, Ingredient.of(a));
        f.shapeless("pcn_c_to_a", a, 1, Ingredient.of(c));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(x), 2);
        assertEquals(2, TreeSelection.patternCount(root), "default: two patterns, raw inputs excluded");
        TreeSelection.deselectAll(root);
        assertEquals(0, TreeSelection.patternCount(root), "a raw-input row is not a pattern");
    }

    @Test
    void manualSelectionBeyondCapIsAllowedAndFlagged() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item x = f.item("oc_x");
        Item a = f.item("oc_a");
        Item b = f.item("oc_b");
        Item c = f.item("oc_c");
        Item d = f.item("oc_d");
        // four candidates in four distinct destinations (the destination cap never binds)
        f.shapeless("oc_c1", x, 1, Ingredient.of(a));
        f.machine("oc_c2", RecipeType.simple(ResourceLocation.withDefaultNamespace("smelting")), f.stack(x), Ingredient.of(b));
        f.machine("oc_c3", RecipeType.simple(ResourceLocation.withDefaultNamespace("stonecutting")), f.stack(x), Ingredient.of(c));
        f.machine("oc_c4", RecipeType.simple(ResourceLocation.withDefaultNamespace("smithing")), f.stack(x), Ingredient.of(d));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(x), 1);
        assertEquals(3, root.selected().cardinality());
        assertFalse(TreeSelection.isOverCap(root, CONFIG.maxSourcesPerItem()), "the default is at the cap, not over it");

        root.select(3); // manually check the fourth
        assertEquals(4, root.selected().cardinality(), "manual selection is uncapped");
        assertTrue(TreeSelection.isOverCap(root, CONFIG.maxSourcesPerItem()), "over-cap is flagged for the GUI");
    }

    @Test
    void planRowKeysByproductRecipesOnTheGoalOutput() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item goal = f.item("bp2_goal");
        Item primary = f.item("bp2_primary");
        Item input = f.item("bp2_input");
        // outputs[0] is the primary product; the goal is the secondary output
        f.byproduct("bp2_recipe", List.of(f.stack(primary, 8), f.stack(goal, 1)), Ingredient.of(input));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(goal), 1);
        // the byproduct recipe is rejected by default; force-select it
        int i = recipeIndex(root, "rpp:bp2_recipe");
        root.select(i);
        root.recipes().get(i).setUserOverridden(true);

        PlanRow row = planRowOf(root, "rpp:bp2_recipe");
        assertTrue(row.output() instanceof AEItemKey, "the row output is an item key");
        Item outputItem = ((AEItemKey) row.output()).getItem();
        assertEquals(goal, outputItem, "the row is keyed on the goal, not outputs[0]");
        assertNotEquals(primary, outputItem);
        assertEquals(1, row.yield(), "the yield is the goal's amount in the outputs");
    }

    @Test
    void hasFlagsReportsFlaggedPlans() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item x = f.item("hf_x");
        Item a = f.item("hf_a");
        Item primary = f.item("hf_primary");
        f.shapeless("hf_craft", x, 1, Ingredient.of(a));
        f.byproduct("hf_byproduct", List.of(f.stack(primary), f.stack(x)), Ingredient.of(a));

        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(x), 1);
        // default: only the trusted crafting recipe is selected
        List<PlanRow> unflagged = TreeSelection.planRows(root);
        assertFalse(TreeSelection.hasFlags(root, unflagged, 0.95), "a clean plan has no flags");

        // force-select the rejected byproduct: the plan now carries a flag
        int i = recipeIndex(root, "rpp:hf_byproduct");
        root.select(i);
        root.recipes().get(i).setUserOverridden(true);
        List<PlanRow> flagged = TreeSelection.planRows(root);
        assertTrue(TreeSelection.hasFlags(root, flagged, 0.95), "a force-selected rejected recipe is a flag");
    }

    private static int recipeIndex(ItemNode root, String id) {
        List<RecipeNode> recipes = root.recipes();
        for (int i = 0; i < recipes.size(); i++) {
            if (recipes.get(i).recipe().id().toString().equals(id)) {
                return i;
            }
        }
        throw new AssertionError("no recipe " + id + " at root");
    }

    private static PlanRow planRowOf(ItemNode root, String id) {
        for (PlanRow row : TreeSelection.planRows(root)) {
            if (row.recipeId() != null && row.recipeId().toString().equals(id)) {
                return row;
            }
        }
        throw new AssertionError("no plan row for " + id);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("rpp", path);
    }
}
