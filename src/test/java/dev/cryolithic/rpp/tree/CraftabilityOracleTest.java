package dev.cryolithic.rpp.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

/**
 * Tests of the {@link CraftabilityOracle} (DESIGN.md §8.6, §13.1): bounded,
 * node-free, cycle-guarded, memoized, and UNKNOWN on budget exhaustion.
 */
class CraftabilityOracleTest {

    @Test
    void unknownOnBudgetExhaustionNeverLeaf() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("ora_b");
        Item a = f.item("ora_a");
        f.shapeless("ora_b_to_a", a, 1, Ingredient.of(b));

        CraftabilityOracle oracle = new CraftabilityOracle(f.buildIndex());

        assertEquals(Craftability.UNKNOWN, oracle.check(AEItemKey.of(a), 0),
                "a goal with recipes and no budget is UNKNOWN, never LEAF");
    }

    @Test
    void leafRegardlessOfBudget() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item x = f.item("ora_leaf");

        CraftabilityOracle oracle = new CraftabilityOracle(f.buildIndex());

        assertEquals(Craftability.LEAF, oracle.check(AEItemKey.of(x), 0));
        assertEquals(Craftability.LEAF, oracle.check(AEItemKey.of(x), 8));
    }

    @Test
    void unambiguousSingleRecipeChain() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item c = f.item("ora_c");
        Item b = f.item("ora_b");
        Item a = f.item("ora_a");
        f.shapeless("ora_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("ora_c_to_b", b, 1, Ingredient.of(c));

        CraftabilityOracle oracle = new CraftabilityOracle(f.buildIndex());

        assertEquals(Craftability.CRAFTABLE_UNAMBIGUOUS, oracle.check(AEItemKey.of(a), 8));
    }

    @Test
    void ambiguousOnMultipleRecipes() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b1 = f.item("ora_b1");
        Item b2 = f.item("ora_b2");
        Item a = f.item("ora_a2");
        f.shapeless("ora_b1_to_a", a, 1, Ingredient.of(b1));
        f.shapeless("ora_b2_to_a", a, 1, Ingredient.of(b2));

        CraftabilityOracle oracle = new CraftabilityOracle(f.buildIndex());

        assertEquals(Craftability.CRAFTABLE_AMBIGUOUS, oracle.check(AEItemKey.of(a), 8));
    }

    @Test
    void ambiguousOnMultiCandidateInput() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item m1 = f.item("ora_m1");
        Item m2 = f.item("ora_m2");
        Item a = f.item("ora_a3");
        var tag = f.tag("ora_tag", m1, m2);
        f.shapeless("ora_tag_to_a", a, 1, Ingredient.of(tag));

        CraftabilityOracle oracle = new CraftabilityOracle(f.buildIndex());

        assertEquals(Craftability.CRAFTABLE_AMBIGUOUS, oracle.check(AEItemKey.of(a), 8));
    }

    @Test
    void cycleBottomsOutLikeALeaf() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("ora_cyc_b");
        Item a = f.item("ora_cyc_a");
        f.shapeless("ora_b_to_a", a, 1, Ingredient.of(b));
        f.shapeless("ora_a_to_b", b, 1, Ingredient.of(a));

        CraftabilityOracle oracle = new CraftabilityOracle(f.buildIndex());

        // a -> b -> a: the second a bottoms out as a raw input, so the branch
        // has exactly one way to bottom out
        assertEquals(Craftability.CRAFTABLE_UNAMBIGUOUS, oracle.check(AEItemKey.of(a), 8));
    }

    @Test
    void memoizedPerGoalAndBudget() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b = f.item("ora_memo_b");
        Item a = f.item("ora_memo_a");
        f.shapeless("ora_memo_b_to_a", a, 1, Ingredient.of(b));

        CraftabilityOracle oracle = new CraftabilityOracle(f.buildIndex());

        Craftability first = oracle.check(AEItemKey.of(a), 5);
        Craftability second = oracle.check(AEItemKey.of(a), 5);
        assertEquals(first, second);
        // a different budget is a different memo entry
        assertEquals(Craftability.UNKNOWN, oracle.check(AEItemKey.of(a), 0));
        assertEquals(Craftability.CRAFTABLE_UNAMBIGUOUS, oracle.check(AEItemKey.of(a), 5));
    }

    @Test
    void gappedShapedRecipeVerifiesInsteadOfUnknown() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item plank = f.item("oragap_plank");
        Item chest = f.item("oragap_chest");
        // chest-like: 8 planks around a blank center slot
        f.shapedGapped("oragap_chest_from_planks", chest, 1, Map.of('#', Ingredient.of(plank)),
                "###", "# #", "###");

        CraftabilityOracle oracle = new CraftabilityOracle(f.buildIndex());

        assertEquals(Craftability.CRAFTABLE_UNAMBIGUOUS, oracle.check(AEItemKey.of(chest), 8),
                "a blank grid slot imposes no constraint; the gapped recipe verifies");
    }

    @Test
    void ambiguousCraftableGoalIsInCraftableFamily() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item b1 = f.item("fam_b1");
        Item b2 = f.item("fam_b2");
        Item a = f.item("fam_a");
        f.shapeless("fam_b1_to_a", a, 1, Ingredient.of(b1));
        f.shapeless("fam_b2_to_a", a, 1, Ingredient.of(b2));

        CraftabilityOracle oracle = new CraftabilityOracle(f.buildIndex());

        // A goal with two different recipe paths is still craftable: the
        // verdict stays in the CRAFTABLE family. The builder's auto-collapse
        // trigger fires for the whole family, not just the unambiguous half.
        Craftability verdict = oracle.check(AEItemKey.of(a), 8);
        assertTrue(verdict == Craftability.CRAFTABLE_UNAMBIGUOUS || verdict == Craftability.CRAFTABLE_AMBIGUOUS,
                "an ambiguous-but-craftable goal is in the CRAFTABLE family");
        assertEquals(Craftability.CRAFTABLE_AMBIGUOUS, verdict);
    }
}
