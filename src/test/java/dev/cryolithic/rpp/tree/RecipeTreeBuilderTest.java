package dev.cryolithic.rpp.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void tagCycleFirstCandidateIsAncestorMarksCycleAndHidesOthers() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("tgc_b");
        Item c = f.item("tgc_c");
        Item a = f.item("tgc_a");
        var t = f.tag("tgc_t", a, c); // candidates [A, C], A first
        f.shapeless("tgc_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("tgc_b_from_t", b, 1, Ingredient.of(t));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 8);

        ItemNode bNode = firstCandidate(root);
        IngredientNode ingredient = bNode.recipes().get(0).ingredients().get(0);
        // the first candidate (the ancestor) is CYCLE, selectable as a raw input
        assertEquals(TreeNode.State.CYCLE, ingredient.candidates().get(0).state());
        assertEquals(a, itemOf(ingredient.candidates().get(0).goal()));
        // the non-ancestor candidate C is not offered
        assertEquals(1, ingredient.candidates().size(),
                "a tag whose first candidate is an ancestor offers no other candidates");
        // the recipe is still offered; the plan ends in a raw input of the ancestor
        assertTrue(bNode.selected().get(0));
        List<PlanRow> plan = TreeSelection.planRows(root);
        assertEquals(3, plan.size());
        assertTrue(plan.get(2).rawInput());
        assertEquals(a, itemOf(plan.get(2).output()));
    }

    @Test
    void tagCycleFirstCandidateNotAncestorOffersAllCandidates() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("tgn_b");
        Item c = f.item("tgn_c");
        Item a = f.item("tgn_a");
        var t = f.tag("tgn_t", c, a); // candidates [C, A], C first
        f.shapeless("tgn_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("tgn_b_from_t", b, 1, Ingredient.of(t));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 8);

        ItemNode bNode = firstCandidate(root);
        IngredientNode ingredient = bNode.recipes().get(0).ingredients().get(0);
        // C (first) is offered; A (an ancestor, non-first) is also offered, not CYCLE
        assertEquals(2, ingredient.candidates().size());
        assertEquals(c, itemOf(ingredient.candidates().get(0).goal()));
        assertEquals(a, itemOf(ingredient.candidates().get(1).goal()));
        assertNotEquals(TreeNode.State.CYCLE, ingredient.candidates().get(1).state(),
                "AE2 allows the pattern, so the ancestor candidate is not hidden as a cycle");
        assertNotEquals(TreeNode.State.CYCLE, ingredient.state(),
                "the ingredient is not marked CYCLE when its first candidate is not an ancestor");
    }

    @Test
    void recipeWithFirstCandidateIntermediateAncestorIsRefused() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item c = f.item("ref_c");
        Item b = f.item("ref_b");
        Item a = f.item("ref_a");
        f.shapeless("ref_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("ref_c_to_b", b, 1, Ingredient.of(c));
        f.shapeless("ref_b_to_c", c, 1, Ingredient.of(b));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 8);

        ItemNode bNode = firstCandidate(root);
        ItemNode cNode = firstCandidate(bNode);
        // C's only recipe (C from B) is refused: B is an intermediate ancestor,
        // so AE2 would silently refuse the pattern in this context
        assertNull(cNode.recipes(), "a recipe whose first input is an intermediate ancestor is refused");
        assertTrue(cNode.isLeaf());
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
    void requireTrustedRecipesHidesUntrustedRecipesFromTheTree() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("unth_b");
        Item a = f.item("unth_a");
        f.shapeless("unth_craft_b_to_a", a, 1, Ingredient.of(b));
        f.generic("unth_generic_b_to_a", f.type("rpp_unth_type"), () -> f.stack(a), Ingredient.of(b));

        // Flag on: the untrusted recipe never reaches the tree.
        ItemNode root = builder(f.buildIndex(Set.of(), true)).buildRoot(AEItemKey.of(a), 1);
        assertEquals(1, root.recipes().size(), "only the trusted recipe survives the index filter");
        assertTrue(root.recipes().get(0).recipe().trusted());

        // Flag off: both recipes reach the tree; the untrusted one is
        // still rejected (separate behavior, unchanged).
        ItemNode open = builder(f.buildIndex()).buildRoot(AEItemKey.of(a), 1);
        assertEquals(2, open.recipes().size());
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

    @Test
    void gappedShapedRecipeIsPrimaryNotRejectedAndAutoCollapses() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item plank = f.item("gap_plank");
        Item chest = f.item("gap_chest");
        // chest-like: 8 planks around a blank center slot
        f.shapedGapped("gap_chest_from_planks", chest, 1, Map.of('#', Ingredient.of(plank)),
                "###", "# #", "###");
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(chest), 2);

        RecipeNode recipe = root.recipes().get(0);
        assertEquals(Tier.PRIMARY, recipe.tier(),
                "the blank center slot is no source-less input: the sole source is PRIMARY, not REJECTED");
        assertNull(recipe.rejectionReason());
        // the blank slot keeps its position: a disabled placeholder with no candidates
        assertEquals(9, recipe.ingredients().size());
        assertTrue(recipe.ingredients().get(4).ingredient().isEmpty());
        assertTrue(recipe.ingredients().get(4).candidates().isEmpty());
        // the oracle verifies the gapped branch instead of bottoming out UNKNOWN
        assertEquals(Craftability.CRAFTABLE_UNAMBIGUOUS, root.craftability());
        // the whole branch is a forced bottleneck: it auto-collapses
        assertEquals(TreeNode.State.COLLAPSED, root.state());
        assertTrue(TreeSelection.isCollapsible(root));
        // plan: the chest pattern (8 non-blank candidates) plus the raw plank input
        List<PlanRow> plan = TreeSelection.planRows(root);
        assertEquals(2, plan.size());
        assertEquals("rpp:gap_chest_from_planks", plan.get(0).recipeId().toString());
        assertEquals(8, plan.get(0).selectedCandidates().size(), "the blank slot contributes no candidate");
        assertTrue(plan.get(1).rawInput());
    }

    @Test
    void detachedExpansionLeavesLiveGraphUntouchedUntilPublish() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item d = f.item("det_d");
        Item c = f.item("det_c");
        Item b = f.item("det_b");
        Item a = f.item("det_a");
        Item e = f.item("det_e");
        f.shapeless("det_b_to_a", a, 1, Ingredient.of(b));
        // a second recipe for a keeps the root from being forced, so the
        // auto-collapse trigger does not materialize b during buildRoot and
        // b stays UNEXPANDED for the detached expansion under test
        f.shapeless("det_e_to_a", a, 1, Ingredient.of(e));
        // e has a source of its own so its input depth ties b's (1); the
        // recipe-id tie-break then keeps b's recipe first (the PRIMARY)
        f.shapeless("det_f_to_e", e, 1, Ingredient.of(f.item("det_f")));
        f.shapeless("det_c_to_b", b, 1, Ingredient.of(c));
        f.shapeless("det_d_to_b", b, 1, Ingredient.of(d));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 1);
        ItemNode bNode = firstCandidate(root);
        assertEquals(TreeNode.State.UNEXPANDED, bNode.state());
        List<RecipeNode> rootRecipes = root.recipes();
        int nodesBefore = builder.totalNodes();

        RecipeTreeBuilder.Expansion expansion = builder.expandDetached(bNode);

        // the live graph is untouched: the node is still unexpanded, and the
        // build did not count the twin against the session node cap
        assertEquals(TreeNode.State.UNEXPANDED, bNode.state());
        assertNull(bNode.recipes());
        assertFalse(bNode.isForced());
        assertFalse(bNode.isLeaf());
        assertTrue(bNode.selected().isEmpty());
        assertEquals(nodesBefore + 6, builder.totalNodes(),
                "two recipes, two ingredients, two candidates; the twin is a scaffold, not a node");

        // the twin holds the built subtree, parent-linked to the live node
        ItemNode twin = expansion.twin();
        assertNotNull(twin.recipes());
        assertEquals(TreeNode.State.EXPANDED, twin.state());
        assertEquals(2, twin.recipes().size());
        for (RecipeNode recipe : twin.recipes()) {
            assertSame(bNode, recipe.parent(), "the new child is parent-linked to the live node");
        }
        BitSet twinSelection = (BitSet) twin.selected().clone();

        // publish: the live node keeps its identity and receives the subtree
        builder.publish(expansion);
        assertSame(bNode, expansion.node(), "the live node keeps its identity");
        assertNotNull(bNode.recipes());
        assertEquals(TreeNode.State.EXPANDED, bNode.state());
        assertEquals(2, bNode.recipes().size());
        for (RecipeNode recipe : bNode.recipes()) {
            assertSame(bNode, recipe.parent());
        }
        assertEquals(twinSelection, bNode.selected(), "the built default selection survives the publish");
        // the rest of the live graph is unchanged
        assertEquals(TreeNode.State.EXPANDED, root.state());
        assertSame(rootRecipes, root.recipes());
        assertTrue(root.selected().get(0), "the root's pre-existing selection survives the publish");
    }

    @Test
    void detachedExpansionPublishesFilteredCountForMoreMarker() {
        // Issue #81: publish() must copy filteredCount, so the "+K more"
        // marker is computed for click-expanded nodes, not just the initial
        // buildRoot (in-place) path.
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item a = f.item("more_a");
        Item b = f.item("more_b");
        Item e = f.item("more_e");
        // a has two sources so the root is not forced; b and e are candidates
        f.shapeless("more_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("more_e_to_a", a, 1, Ingredient.of(e));
        f.shapeless("more_x_to_e", e, 1, Ingredient.of(f.item("more_x")));
        // b has ten sources; expanding it caps to maxRecipesPerItem (6) and
        // records filteredCount = 10 for the "+4 more" marker
        for (int i = 0; i < 10; i++) {
            f.shapeless("more_b_src" + i, b, 1, Ingredient.of(f.item("more_b_in" + i)));
        }
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 1);
        ItemNode bNode = firstCandidate(root);
        assertEquals(TreeNode.State.UNEXPANDED, bNode.state());

        RecipeTreeBuilder.Expansion expansion = builder.expandDetached(bNode);
        builder.publish(expansion);

        // the live node carries the filtered count, so the marker is computed
        assertEquals(10, bNode.filteredCount());
        assertEquals(6, bNode.recipes().size(), "capped to maxRecipesPerItem");
        assertEquals(10 - TreeLimits.DEFAULTS.maxRecipesPerItem(), builder.moreCount(bNode),
                "the +K more marker is available after a detached publish");
    }

    @Test
    void detachedExpansionAutoCollapsesForcedBottleneck() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item c = f.item("detac_c");
        Item b = f.item("detac_b");
        Item a = f.item("detac_a");
        f.shapeless("detac_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("detac_c_to_b", b, 1, Ingredient.of(c));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 0);
        assertEquals(TreeNode.State.UNEXPANDED, root.state());

        RecipeTreeBuilder.Expansion expansion = builder.expandDetached(root);
        assertEquals(TreeNode.State.COLLAPSED, expansion.twin().state(),
                "the forced bottleneck auto-collapses in the detached build");

        builder.publish(expansion);
        assertEquals(TreeNode.State.COLLAPSED, root.state());
        assertNotNull(root.recipes());
        ItemNode bNode = firstCandidate(root);
        assertEquals(TreeNode.State.COLLAPSED, bNode.state(),
                "the unexpanded forced descendant is auto-expanded and collapsed");
        assertNotNull(bNode.recipes());
        assertEquals(3, TreeSelection.planRows(root).size(),
                "a's recipe, b's recipe, and raw input c");
    }

    @Test
    void expandDetachedRefusesNonUnexpandedNodes() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item c = f.item("refu_c");
        Item b = f.item("refu_b");
        Item b2 = f.item("refu_b2");
        Item a = f.item("refu_a");
        f.shapeless("refu_c_to_b", b, 1, Ingredient.of(c));
        f.shapeless("refu_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("refu_b2_to_a", a, 1, Ingredient.of(b2));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 1);
        assertThrows(IllegalArgumentException.class, () -> builder.expandDetached(root),
                "an already-expanded node cannot be detached-expanded");
        final ItemNode[] holder = new ItemNode[1];
        for (RecipeNode recipe : root.recipes()) {
            ItemNode candidate = recipe.ingredients().get(0).candidates().get(0);
            if (itemOf(candidate.goal()) == b) {
                holder[0] = candidate;
                break;
            }
        }
        assertNotNull(holder[0]);
        ItemNode bNode = holder[0];
        builder.publish(builder.expandDetached(bNode));
        assertThrows(IllegalArgumentException.class, () -> builder.expandDetached(bNode),
                "a published node is expanded, so a second detached expansion is refused");
    }

    @Test
    void forcedNodeWithAmbiguousCraftableGoalAutoCollapses() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item d = f.item("ambc_d");
        Item c = f.item("ambc_c");
        Item b = f.item("ambc_b");
        Item a = f.item("ambc_a");
        var t = f.tag("ambc_t", c, d);
        f.shapeless("ambc_t_to_b", b, 1, Ingredient.of(t));
        f.shapeless("ambc_b_to_a", a, 1, Ingredient.of(b));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 1);

        assertTrue(root.isForced(), "the node itself has one live recipe");
        assertEquals(Craftability.CRAFTABLE_AMBIGUOUS, root.craftability(),
                "the tag input below b makes the goal ambiguous-but-craftable");
        // The whole CRAFTABLE family triggers auto-collapse, not just the
        // unambiguous half: the subtree is materialized and collapsed into a
        // summary row even though the oracle sees several paths.
        assertEquals(TreeNode.State.COLLAPSED, root.state());
        ItemNode bNode = firstCandidate(root);
        assertEquals(TreeNode.State.COLLAPSED, bNode.state(),
                "the forced descendant with a tag input is auto-expanded and collapsed");
        assertNotNull(bNode.recipes());
        assertTrue(TreeSelection.isCollapsible(root));
        List<PlanRow> plan = TreeSelection.planRows(root);
        assertEquals(3, plan.size(), "a's pattern, b's pattern, and the raw tag input");
        assertEquals("rpp:ambc_b_to_a", plan.get(0).recipeId().toString());
        assertEquals("rpp:ambc_t_to_b", plan.get(1).recipeId().toString());
        assertTrue(plan.get(2).rawInput());
        assertEquals(c, itemOf(plan.get(2).output()));
    }

    @Test
    void uncraftableGoalStaysExpanded() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("uncr_b");
        Item a = f.item("uncr_a");
        // the goal's only source is untrusted: no usable recipe, so the goal
        // is uncraftable in the tree even though the oracle (which sees the
        // raw index) calls it craftable
        f.generic("uncr_b_to_a", f.type("rpp_uncr_type"), () -> f.stack(a), Ingredient.of(b));
        RecipeTreeBuilder builder = builder(f.buildIndex());

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 1);

        assertFalse(root.isForced(), "an untrusted recipe is not a live candidate to auto-select");
        assertEquals(Craftability.CRAFTABLE_UNAMBIGUOUS, root.craftability(),
                "the oracle sees the raw index, where the recipe looks craftable");
        // A node without a live path is never auto-collapsed: it stays
        // expanded so the user can see why the goal has no usable source.
        assertEquals(TreeNode.State.EXPANDED, root.state());
        assertNotNull(root.recipes());
        assertEquals(Tier.REJECTED, root.recipes().get(0).tier());
    }

    @Test
    void forcedNodeWithUnknownGoalStaysExpandable() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item d = f.item("unk_d");
        Item c = f.item("unk_c");
        Item b = f.item("unk_b");
        Item a = f.item("unk_a");
        f.shapeless("unk_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("unk_c_to_b", b, 1, Ingredient.of(c));
        f.shapeless("unk_d_to_c", c, 1, Ingredient.of(d));
        RecipeTreeBuilder builder = new RecipeTreeBuilder(f.buildIndex(),
                new TreeLimits(2, 6, 8, 4000, 100_000), NO_TAGS);

        ItemNode root = builder.buildRoot(AEItemKey.of(a), 8);

        ItemNode bNode = firstCandidate(root);
        assertTrue(bNode.isForced(), "b has one live recipe");
        assertEquals(Craftability.UNKNOWN, bNode.craftability(),
                "the depth budget is exhausted before b's branch bottoms out");
        // An UNKNOWN verdict never auto-collapses: the node stays expanded so
        // the user can see where the budget runs out.
        assertEquals(TreeNode.State.EXPANDED, bNode.state());
        ItemNode cNode = firstCandidate(bNode);
        assertEquals(TreeNode.State.CAPPED, cNode.state(), "the budget exhaustion is visible");
    }
}
