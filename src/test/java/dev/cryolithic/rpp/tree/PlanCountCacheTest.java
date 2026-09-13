package dev.cryolithic.rpp.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import appeng.api.stacks.AEItemKey;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

/**
 * The render-path pattern-count cache (DESIGN.md §9, §11.1): the O(tree)
 * counter runs once per invalidation, not once per read. Runs in a bare JVM.
 */
class PlanCountCacheTest {
    private static final ReversalDetector.TagLookup NO_TAGS = item -> List.of();
    private static final SourceSelector.SelectionConfig CONFIG = new SourceSelector.SelectionConfig(
            0.95, false, 3, 1, true, List.of("minecraft", "ae2"));

    private static RecipeTreeBuilder builder(RecipeIndex index) {
        return new RecipeTreeBuilder(index, TreeLimits.DEFAULTS, NO_TAGS,
                new SourceSelector(index, TreeLimits.DEFAULTS, CONFIG, null));
    }

    /** A two-recipe tree, select-all: exactly two patterns (raw inputs excluded). */
    private static ItemNode twoRecipeTree(RecipeIndexFixture f, String prefix) {
        Item x = f.item(prefix + "_x");
        Item a = f.item(prefix + "_a");
        Item b = f.item(prefix + "_b");
        f.shapeless(prefix + "_c1", x, 1, Ingredient.of(a));
        f.shapeless(prefix + "_c2", x, 1, Ingredient.of(b));
        ItemNode root = builder(f.buildIndex()).buildRoot(AEItemKey.of(x), 1);
        TreeSelection.selectAll(root);
        return root;
    }

    @Test
    void countIsComputedOnceAndStaysCachedAcrossReads() {
        ItemNode root = twoRecipeTree(new RecipeIndexFixture(), "pcca");
        AtomicInteger computations = new AtomicInteger();
        PlanCountCache cache = new PlanCountCache(r -> {
            computations.incrementAndGet();
            return TreeSelection.patternCount(r);
        });
        for (int i = 0; i < 10; i++) {
            assertEquals(2, cache.count(root), "read " + i);
        }
        assertEquals(1, computations.get(), "the counter runs once, not per read");
    }

    @Test
    void invalidateForcesExactlyOneRecomputation() {
        ItemNode root = twoRecipeTree(new RecipeIndexFixture(), "pccb");
        AtomicInteger computations = new AtomicInteger();
        PlanCountCache cache = new PlanCountCache(r -> {
            computations.incrementAndGet();
            return TreeSelection.patternCount(r);
        });
        int first = cache.count(root);
        assertEquals(2, first);
        assertEquals(1, computations.get());
        cache.invalidate();
        int second = cache.count(root);
        assertEquals(first, second, "value unchanged while the tree is unchanged");
        assertEquals(2, computations.get(), "invalidate forces exactly one recompute");
    }

    @Test
    void selectionChangeIsSeenAfterInvalidation() {
        ItemNode root = twoRecipeTree(new RecipeIndexFixture(), "pccc");
        PlanCountCache cache = new PlanCountCache(TreeSelection::patternCount);
        assertEquals(2, cache.count(root));
        TreeSelection.deselectAll(root);
        assertEquals(2, cache.count(root), "without invalidate the cache keeps its last value");
        cache.invalidate();
        assertEquals(0, cache.count(root), "invalidate picks up the selection change");
    }

    @Test
    void nullRootReturnsZeroWithoutComputing() {
        AtomicInteger computations = new AtomicInteger();
        PlanCountCache cache = new PlanCountCache(r -> {
            computations.incrementAndGet();
            return 7;
        });
        assertEquals(0, cache.count(null));
        assertEquals(0, computations.get(), "a null root never runs the counter");
    }

    @Test
    void aDifferentRootRecomputes() {
        ItemNode rootA = twoRecipeTree(new RecipeIndexFixture(), "pccra");
        ItemNode rootB = twoRecipeTree(new RecipeIndexFixture(), "pccrb");
        AtomicInteger computations = new AtomicInteger();
        PlanCountCache cache = new PlanCountCache(r -> {
            computations.incrementAndGet();
            return TreeSelection.patternCount(r);
        });
        assertEquals(2, cache.count(rootA));
        assertEquals(2, cache.count(rootB));
        assertEquals(2, computations.get(), "a different root invalidates the cache");
    }
}
