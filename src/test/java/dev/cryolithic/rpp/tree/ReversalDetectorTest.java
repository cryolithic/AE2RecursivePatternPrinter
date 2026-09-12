package dev.cryolithic.rpp.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

/**
 * Tests of the {@link ReversalDetector} (DESIGN.md §8.4.2): the round-trip
 * efficiency, the extra-input signal, and the storage-tag fast path.
 */
class ReversalDetectorTest {
    private static final ReversalDetector.TagLookup NO_TAGS = item -> List.of();

    private static RecipeView viewFor(RecipeIndex index, Item goal, String recipeId) {
        return index.recipesFor(AEItemKey.of(goal)).stream()
                .filter(v -> v.id().toString().equals("rpp:" + recipeId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void blockPairScoresOnePointZero() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item iron = f.item("rv_iron");
        Item block = f.item("rv_block");
        // candidate: 1 block -> 9 iron
        f.shapeless("rv_block_to_iron", iron, 9, Ingredient.of(block));
        // forward: 9 iron -> 1 block
        f.shapeless("rv_iron_to_block", block, 1,
                Ingredient.of(iron), Ingredient.of(iron), Ingredient.of(iron),
                Ingredient.of(iron), Ingredient.of(iron), Ingredient.of(iron),
                Ingredient.of(iron), Ingredient.of(iron), Ingredient.of(iron));
        RecipeIndex index = f.buildIndex();
        ReversalDetector detector = new ReversalDetector(index, NO_TAGS);

        RecipeView candidate = viewFor(index, iron, "rv_block_to_iron");
        assertEquals(1.0, detector.roundTripEfficiency(AEItemKey.of(iron), candidate), 1e-9);
        assertFalse(detector.consumesExtraInputs(AEItemKey.of(iron), candidate));
    }

    @Test
    void pickaxeMeltScoresOneThirdWithExtraInputs() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item iron = f.item("rv_pick_iron");
        Item pickaxe = f.item("rv_pickaxe");
        Item stick = f.item("rv_stick");
        // candidate: 1 pickaxe -> 1 iron
        f.shapeless("rv_pickaxe_to_iron", iron, 1, Ingredient.of(pickaxe));
        // forward: 3 iron + 2 sticks -> 1 pickaxe
        f.shapeless("rv_iron_to_pickaxe", pickaxe, 1,
                Ingredient.of(iron), Ingredient.of(iron), Ingredient.of(iron),
                Ingredient.of(stick), Ingredient.of(stick));
        RecipeIndex index = f.buildIndex();
        ReversalDetector detector = new ReversalDetector(index, NO_TAGS);

        RecipeView candidate = viewFor(index, iron, "rv_pickaxe_to_iron");
        assertEquals(1.0 / 3.0, detector.roundTripEfficiency(AEItemKey.of(iron), candidate), 1e-9);
        assertTrue(detector.consumesExtraInputs(AEItemKey.of(iron), candidate));
    }

    @Test
    void nuggetPairScoresOnePointZero() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item iron = f.item("rv_nug_iron");
        Item nugget = f.item("rv_nugget");
        // candidate: 1 iron -> 9 nuggets
        f.shapeless("rv_iron_to_nuggets", nugget, 9, Ingredient.of(iron));
        // forward: 9 nuggets -> 1 iron
        f.shapeless("rv_nuggets_to_iron", iron, 1,
                Ingredient.of(nugget), Ingredient.of(nugget), Ingredient.of(nugget),
                Ingredient.of(nugget), Ingredient.of(nugget), Ingredient.of(nugget),
                Ingredient.of(nugget), Ingredient.of(nugget), Ingredient.of(nugget));
        RecipeIndex index = f.buildIndex();
        ReversalDetector detector = new ReversalDetector(index, NO_TAGS);

        RecipeView candidate = viewFor(index, nugget, "rv_iron_to_nuggets");
        assertEquals(1.0, detector.roundTripEfficiency(AEItemKey.of(nugget), candidate), 1e-9);
    }

    @Test
    void notAReversalIsNaN() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item iron = f.item("rv_norev_iron");
        Item block = f.item("rv_norev_block");
        // candidate: 1 block -> 9 iron, but no recipe produces the block from iron
        f.shapeless("rv_norev_block_to_iron", iron, 9, Ingredient.of(block));
        RecipeIndex index = f.buildIndex();
        ReversalDetector detector = new ReversalDetector(index, NO_TAGS);

        RecipeView candidate = viewFor(index, iron, "rv_norev_block_to_iron");
        assertTrue(Double.isNaN(detector.roundTripEfficiency(AEItemKey.of(iron), candidate)));
        assertFalse(detector.consumesExtraInputs(AEItemKey.of(iron), candidate));
    }

    @Test
    void multiInputCandidateIsNotAReversal() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item iron = f.item("rv_multi_iron");
        Item block = f.item("rv_multi_block");
        Item stick = f.item("rv_multi_stick");
        f.shapeless("rv_multi_to_iron", iron, 9, Ingredient.of(block), Ingredient.of(stick));
        RecipeIndex index = f.buildIndex();
        ReversalDetector detector = new ReversalDetector(index, NO_TAGS);

        RecipeView candidate = viewFor(index, iron, "rv_multi_to_iron");
        assertTrue(Double.isNaN(detector.roundTripEfficiency(AEItemKey.of(iron), candidate)));
    }

    @Test
    void storageTagFastPathIsLosslessWithoutAForwardRecipe() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item iron = f.item("rv_tag_iron");
        Item block = f.item("rv_tag_block");
        // candidate: 1 block -> 9 iron; no forward recipe exists
        f.shapeless("rv_tag_block_to_iron", iron, 9, Ingredient.of(block));
        RecipeIndex index = f.buildIndex();

        ReversalDetector.TagLookup tags = item -> {
            if (item == block) {
                return List.of(ResourceLocation.fromNamespaceAndPath("c", "storage_blocks/iron"));
            }
            if (item == iron) {
                return List.of(ResourceLocation.fromNamespaceAndPath("c", "ingots/iron"));
            }
            return List.of();
        };
        ReversalDetector detector = new ReversalDetector(index, tags);

        RecipeView candidate = viewFor(index, iron, "rv_tag_block_to_iron");
        assertEquals(1.0, detector.roundTripEfficiency(AEItemKey.of(iron), candidate), 1e-9);
    }

    @Test
    void storageTagsWithDifferentParametersAreNotLossless() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item iron = f.item("rv_tag2_iron");
        Item block = f.item("rv_tag2_block");
        f.shapeless("rv_tag2_block_to_iron", iron, 9, Ingredient.of(block));
        RecipeIndex index = f.buildIndex();

        ReversalDetector.TagLookup tags = item -> {
            if (item == block) {
                return List.of(ResourceLocation.fromNamespaceAndPath("c", "storage_blocks/iron"));
            }
            if (item == iron) {
                return List.of(ResourceLocation.fromNamespaceAndPath("c", "ingots/copper"));
            }
            return List.of();
        };
        ReversalDetector detector = new ReversalDetector(index, tags);

        RecipeView candidate = viewFor(index, iron, "rv_tag2_block_to_iron");
        assertTrue(Double.isNaN(detector.roundTripEfficiency(AEItemKey.of(iron), candidate)));
    }
}
