package dev.cryolithic.rpp.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

/**
 * Structural tests of the recipe tree builder (DESIGN.md §13.1): lazy
 * expansion, cycle detection, caps, forced/collapse, and plan flattening.
 * Runs in a bare JVM on the shared {@link RecipeIndexFixture}.
 */
class RecipeTreeBuilderTest {
    private static final ReversalDetector.TagLookup NO_TAGS = item -> List.of();

    private static RecipeTreeBuilder builder(RecipeIndex index) {
        return new RecipeTreeBuilder(index, TreeLimits.DEFAULTS, NO_TAGS);
    }

    /** The first candidate item of the first recipe of the node. */
    private static ItemNode firstCandidate(ItemNode node) {
        RecipeNode recipe = node.recipes().get(0);
        return recipe.ingredients().get(0).candidates().get(0);
    }

    /** The underlying item of an item key, for identity comparison. */
    private static Item itemOf(AEKey key) {
        return ((AEItemKey) key).getItem();
    }

    @Test
    void linearChainResolvesCollapsesAndFlattensToThreePlanEntries() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item c = f.item("chain_c");
        Item b = f.item("chain_b");
        Item a = f.item("chain_a");
        f.shapeless("chain_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("chain_c_to_b", b, 1, Ingredient.of(c));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 2);

        assertTrue(root.isForced());
        assertTrue(root.selected().get(0));
        ItemNode bNode = firstCandidate(root);
        assertTrue(bNode.isForced());
        ItemNode cNode = firstCandidate(bNode);
        assertTrue(cNode.isLeaf());
        // the whole chain is a forced bottleneck: it collapses
        assertTrue(TreeSelection.isCollapsible(root));
        // flattens to 3 plan entries: two patterns + one raw input
        List<PlanRow> plan = TreeSelection.planRows(root);
        assertEquals(3, plan.size());
        assertEquals("rpp:chain_b_to_a", plan.get(0).recipeId().toString());
        assertEquals("rpp:chain_c_to_b", plan.get(1).recipeId().toString());
        assertTrue(plan.get(2).rawInput());
        assertEquals(c, itemOf(plan.get(2).output()));
    }

    @Test
    void directCycleMarksCycleAtDepthTwoAndTerminates() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("cyc_b");
        Item a = f.item("cyc_a");
        f.shapeless("cyc_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("cyc_a_to_b", b, 1, Ingredient.of(a));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 8);

        ItemNode bNode = firstCandidate(root);
        ItemNode aAgain = firstCandidate(bNode);
        assertEquals(TreeNode.State.CYCLE, aAgain.state());
        assertNull(aAgain.recipes(), "a cycle node is never expanded");
        assertFalse(aAgain.isLeaf());
        // the recipe is still offered; the cycle node stays selectable as a raw input
        assertTrue(root.selected().get(0));
        List<PlanRow> plan = TreeSelection.planRows(root);
        assertEquals(3, plan.size());
        assertTrue(plan.get(2).rawInput());
    }

    @Test
    void indirectFourItemCycleMarksCycleAndTerminates() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("ind_b");
        Item c = f.item("ind_c");
        Item d = f.item("ind_d");
        Item a = f.item("ind_a");
        f.shapeless("ind_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("ind_c_to_b", b, 1, Ingredient.of(c));
        f.shapeless("ind_d_to_c", c, 1, Ingredient.of(d));
        f.shapeless("ind_a_to_d", d, 1, Ingredient.of(a));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 8);

        ItemNode bNode = firstCandidate(root);
        ItemNode cNode = firstCandidate(bNode);
        ItemNode dNode = firstCandidate(cNode);
        ItemNode aAgain = firstCandidate(dNode);
        assertEquals(TreeNode.State.CYCLE, aAgain.state());
        assertNull(aAgain.recipes());
    }

    @Test
    void diamondPlanDedupesSharedLeafToOneEntry() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item d = f.item("dia_d");
        Item c = f.item("dia_c");
        Item b = f.item("dia_b");
        Item a = f.item("dia_a");
        f.shapeless("dia_b_c_to_a", a, 1, Ingredient.of(b), Ingredient.of(c));
        f.shapeless("dia_d_to_b", b, 1, Ingredient.of(d));
        f.shapeless("dia_d_to_c", c, 1, Ingredient.of(d));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 2);

        List<PlanRow> plan = TreeSelection.planRows(root);
        assertEquals(4, plan.size());
        long dRows = plan.stream()
                .filter(PlanRow::rawInput)
                .filter(row -> itemOf(row.output()) == d)
                .count();
        assertEquals(1, dRows, "the shared leaf appears once, not twice");
    }

    @Test
    void fiveHundredRecipesTruncateToMaxWithMoreCount() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item x = f.item("many_x");
        for (int i = 0; i < 500; i++) {
            Item input = f.item("many_in_" + i);
            f.shapeless("many_r" + i, x, 1, Ingredient.of(input));
        }
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(x), 1);

        assertEquals(TreeLimits.DEFAULTS.maxRecipesPerItem(), root.recipes().size());
        assertEquals(500 - TreeLimits.DEFAULTS.maxRecipesPerItem(), builder.moreCount(root));
    }

    @Test
    void sixtyCandidateTagTruncatesAndSetsCapped() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item out = f.item("tagx_out");
        List<Item> members = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            members.add(f.item("tagx_m" + i));
        }
        var tag = f.tag("tagx", members.toArray(Item[]::new));
        f.shapeless("tagx_r", out, 1, Ingredient.of(tag));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(out), 1);

        IngredientNode ingredient = root.recipes().get(0).ingredients().get(0);
        assertEquals(TreeLimits.DEFAULTS.maxCandidatesPerIngredient(), ingredient.candidates().size());
        assertTrue(ingredient.isCapped());
    }

    @Test
    void itemWithNoRecipesIsLeafNotError() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item x = f.item("leaf_x");
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(x), 1);

        assertTrue(root.isLeaf());
        assertEquals(TreeNode.State.EXPANDED, root.state());
        assertNull(root.recipes());
    }

    @Test
    void nodesPerExpansionBreachCapsOneNodeAndLeavesSiblingsIntact() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item big = f.item("cap_big");
        Item small = f.item("cap_small");
        Item rootItem = f.item("cap_root");
        for (int r = 0; r < 10; r++) {
            List<Item> m1 = new ArrayList<>();
            List<Item> m2 = new ArrayList<>();
            List<Item> m3 = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                m1.add(f.item("cap_big_m" + r + "_1_" + i));
                m2.add(f.item("cap_big_m" + r + "_2_" + i));
                m3.add(f.item("cap_big_m" + r + "_3_" + i));
            }
            var t1 = f.tag("cap_big_t" + r + "_1", m1.toArray(Item[]::new));
            var t2 = f.tag("cap_big_t" + r + "_2", m2.toArray(Item[]::new));
            var t3 = f.tag("cap_big_t" + r + "_3", m3.toArray(Item[]::new));
            f.shapeless("cap_big_r" + r, big, 1,
                    Ingredient.of(t1), Ingredient.of(t2), Ingredient.of(t3));
        }
        Item smallInput = f.item("cap_small_in");
        f.shapeless("cap_small_in_to_small", small, 1, Ingredient.of(smallInput));
        f.shapeless("cap_big_to_root", rootItem, 1, Ingredient.of(big));
        f.shapeless("cap_small_to_root", rootItem, 1, Ingredient.of(small));
        RecipeTreeBuilder builder = new RecipeTreeBuilder(f.buildIndex(),
                new TreeLimits(8, 6, 8, 100, 100_000), NO_TAGS);

        ItemNode root = builder.buildRoot(AEItemKey.of(rootItem), 2);

        ItemNode bigNode = null;
        ItemNode smallNode = null;
        for (RecipeNode recipe : root.recipes()) {
            ItemNode candidate = recipe.ingredients().get(0).candidates().get(0);
            if (itemOf(candidate.goal()) == big) {
                bigNode = candidate;
            } else {
                smallNode = candidate;
            }
        }
        assertNotNull(bigNode);
        assertNotNull(smallNode);
        assertEquals(TreeNode.State.CAPPED, bigNode.state());
        assertNull(bigNode.recipes());
        assertEquals(TreeNode.State.COLLAPSED, smallNode.state());
        assertNotNull(smallNode.recipes());
    }

    @Test
    void orderingIsStableAcrossTwoBuilds() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item a = f.item("ord_a");
        Item b1 = f.item("ord_b1");
        Item b2 = f.item("ord_b2");
        Item c1 = f.item("ord_c1");
        Item c2 = f.item("ord_c2");
        f.shapeless("ord_r1", a, 1, Ingredient.of(b1), Ingredient.of(c1));
        f.shapeless("ord_r2", a, 1, Ingredient.of(b2), Ingredient.of(c2));
        f.shapeless("ord_r3", a, 1, Ingredient.of(b1), Ingredient.of(c2));
        RecipeIndex index = f.buildIndex();

        List<String> first = recipeIds(builder(index).buildRoot(AEItemKey.of(a), 1));
        List<String> second = recipeIds(builder(index).buildRoot(AEItemKey.of(a), 1));
        assertEquals(first, second);
    }

    private static List<String> recipeIds(ItemNode root) {
        List<String> ids = new ArrayList<>();
        if (root.recipes() != null) {
            for (RecipeNode recipe : root.recipes()) {
                ids.add(recipe.recipe().id().toString());
            }
        }
        return ids;
    }

    @Test
    void singleCandidateNodeIsForcedAndCollapses() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("forc_b");
        Item a = f.item("forc_a");
        f.shapeless("forc_b_to_a", a, 1, Ingredient.of(b));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 1);

        assertTrue(root.isForced());
        assertTrue(root.selected().get(0));
        assertTrue(TreeSelection.isCollapsible(root));
    }

    @Test
    void fourStackedForcedNodesCollapseIntoOneSubtree() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item e = f.item("st_e");
        Item d = f.item("st_d");
        Item c = f.item("st_c");
        Item b = f.item("st_b");
        Item a = f.item("st_a");
        f.shapeless("st_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("st_c_to_b", b, 1, Ingredient.of(c));
        f.shapeless("st_d_to_c", c, 1, Ingredient.of(d));
        f.shapeless("st_e_to_d", d, 1, Ingredient.of(e));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 4);

        assertTrue(root.isForced());
        assertTrue(firstCandidate(root).isForced());
        assertTrue(firstCandidate(firstCandidate(root)).isForced());
        assertTrue(firstCandidate(firstCandidate(firstCandidate(root))).isForced());
        assertTrue(firstCandidate(firstCandidate(firstCandidate(firstCandidate(root)))).isLeaf());
        assertTrue(TreeSelection.isCollapsible(root), "four stacked forced nodes collapse");
    }

    @Test
    void subtreeWithMultiCandidateNodeNeverCollapses() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b1 = f.item("multi_b1");
        Item b2 = f.item("multi_b2");
        Item b = f.item("multi_b");
        Item a = f.item("multi_a");
        f.shapeless("multi_b1_to_b", b, 1, Ingredient.of(b1));
        f.shapeless("multi_b2_to_b", b, 1, Ingredient.of(b2));
        f.shapeless("multi_b_to_a", a, 1, Ingredient.of(b));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 2);

        assertTrue(root.isForced(), "the root itself has one candidate");
        assertFalse(TreeSelection.isCollapsible(root), "a multi-candidate descendant keeps the branch open");
    }

    @Test
    void untrustedRecipeBlocksCollapse() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("untr_b");
        Item a = f.item("untr_a");
        f.generic("untr_b_to_a", f.type("rpp_untr_type"), () -> f.stack(a), Ingredient.of(b));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 1);

        assertFalse(root.isForced(), "an untrusted (REJECTED) recipe is not a live candidate to auto-select");
        assertEquals(Tier.REJECTED, root.recipes().get(0).tier(), "untrusted extraction is rejected");
        assertFalse(root.recipes().get(0).recipe().trusted());
    }

    @Test
    void forcedChainAutoCollapsesUnexpandedDescendants() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("unexp_b");
        Item a = f.item("unexp_a");
        f.shapeless("unexp_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("unexp_c_to_b", b, 1, Ingredient.of(f.item("unexp_c")));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 1);

        // The oracle proves the whole chain is a forced bottleneck, so the
        // builder materializes and collapses it — including the descendant
        // that was still unexpanded at initialDepth.
        assertEquals(TreeNode.State.COLLAPSED, root.state());
        ItemNode bNode = firstCandidate(root);
        assertEquals(TreeNode.State.COLLAPSED, bNode.state(),
                "the unexpanded forced descendant is auto-expanded and collapsed");
        assertNotNull(bNode.recipes());
        assertTrue(TreeSelection.isCollapsible(root));
        assertEquals(3, TreeSelection.planRows(root).size(),
                "a's recipe, b's recipe, and raw input c");
    }

    @Test
    void depthCapCapsItemNodeAtMaxDepth() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item c = f.item("depth_c");
        Item b = f.item("depth_b");
        Item a = f.item("depth_a");
        f.shapeless("depth_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("depth_c_to_b", b, 1, Ingredient.of(c));
        RecipeTreeBuilder builder = new RecipeTreeBuilder(f.buildIndex(),
                new TreeLimits(2, 6, 8, 4000, 100_000), NO_TAGS);

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 8);

        ItemNode bNode = firstCandidate(root);
        assertEquals(TreeNode.State.EXPANDED, bNode.state());
        ItemNode cNode = firstCandidate(bNode);
        assertEquals(TreeNode.State.CAPPED, cNode.state(), "an item at depth maxDepth is capped, not expanded");
        assertNull(cNode.recipes());
    }

    @Test
    void totalNodeCapRefusesFurtherExpansion() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item c1 = f.item("tot_c1");
        Item c2 = f.item("tot_c2");
        Item b1 = f.item("tot_b1");
        Item b2 = f.item("tot_b2");
        Item a = f.item("tot_a");
        f.shapeless("tot_b1_to_a", a, 1, Ingredient.of(b1));
        f.shapeless("tot_b2_to_a", a, 1, Ingredient.of(b2));
        f.shapeless("tot_c1_to_b1", b1, 1, Ingredient.of(c1));
        f.shapeless("tot_c2_to_b2", b2, 1, Ingredient.of(c2));
        RecipeTreeBuilder builder = new RecipeTreeBuilder(f.buildIndex(),
                new TreeLimits(8, 6, 8, 4000, 10), NO_TAGS);

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 2);

        assertTrue(builder.atTotalNodeCap());
        ItemNode b1Node = firstCandidate(root);
        assertEquals(TreeNode.State.COLLAPSED, b1Node.state());
        ItemNode b2Node = root.recipes().get(1).ingredients().get(0).candidates().get(0);
        assertEquals(TreeNode.State.CAPPED, b2Node.state());
        assertNull(b2Node.recipes());
    }
}
