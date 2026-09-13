package dev.cryolithic.rpp.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.ids.AEItemIds;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.EncodedCraftingPattern;
import appeng.crafting.pattern.EncodedProcessingPattern;
import appeng.crafting.pattern.EncodedSmithingTablePattern;
import appeng.crafting.pattern.EncodedStonecuttingPattern;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pattern encoder (DESIGN.md §10.3): routing by recipe
 * shape and real-yield encoding. Runs as plain JUnit on the shared
 * {@link RecipeIndexFixture}; {@link PatternItems} registers AE2's pattern
 * items so the encoders work in a bare JVM. Stonecutter and smithing
 * recipes are built locally because the fixture only provides crafting and
 * generic recipes.
 */
class PatternEncoderTest {
    private RecipeIndexFixture fixture;

    @BeforeEach
    void setUp() {
        RecipeIndexFixture.boot();
        PatternItems.register();
        fixture = new RecipeIndexFixture();
    }

    /** A recipe manager holding exactly the given holders. */
    private RecipeManager manager(RecipeHolder<?>... holders) {
        RecipeManager manager = new RecipeManager(fixture.provider());
        manager.replaceRecipes(List.of(holders));
        return manager;
    }

    private Item item(String name) {
        return fixture.item(name);
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("rpp", name);
    }

    @Test
    void craftingRecipeEncodesToCraftingPattern() {
        Item output = item("pe_craft_out");
        Item a = item("pe_craft_a");
        Item b = item("pe_craft_b");
        RecipeHolder<?> holder = fixture.shaped("pe_craft", output, 1,
                Map.of('X', Ingredient.of(a), 'Y', Ingredient.of(b)), "XX", "YY");
        RecipeView view = PrintPlan.viewOf(holder, fixture.provider());
        assertNotNull(view);

        ItemStack pattern = PatternEncoder.encode(view, List.of(), manager(holder), fixture.provider(), true, false);

        assertFalse(pattern.isEmpty());
        assertTrue(PatternDetailsHelper.isEncodedPattern(pattern));
        assertEquals(BuiltInRegistries.ITEM.get(AEItemIds.CRAFTING_PATTERN), pattern.getItem());
        // The 2x2 pattern is top-left aligned in the 9-slot grid: pattern
        // cells (0,0), (0,1), (1,0), (1,1) land on grid slots 0, 1, 3, 4.
        EncodedCraftingPattern details = pattern.get(AEComponents.ENCODED_CRAFTING_PATTERN);
        assertNotNull(details);
        List<ItemStack> grid = details.inputs();
        assertEquals(9, grid.size());
        assertEquals(a, grid.get(0).getItem());
        assertEquals(a, grid.get(1).getItem());
        assertTrue(grid.get(2).isEmpty());
        assertEquals(b, grid.get(3).getItem());
        assertEquals(b, grid.get(4).getItem());
        for (int i = 5; i < 9; i++) {
            assertTrue(grid.get(i).isEmpty());
        }
        assertEquals(output, details.result().getItem());
        assertEquals(1, details.result().getCount());
    }

    @Test
    void nonCraftingRecipeEncodesToProcessingPattern() {
        Item output = item("pe_proc_out");
        Item input = item("pe_proc_in");
        RecipeHolder<?> holder = fixture.generic("pe_proc", fixture.type("pe_proc_type"),
                () -> new ItemStack(output, 1), Ingredient.of(input));
        RecipeView view = PrintPlan.viewOf(holder, fixture.provider());
        assertNotNull(view);

        ItemStack pattern = PatternEncoder.encode(view, List.of(AEItemKey.of(input)),
                manager(holder), fixture.provider(), true, false);

        assertFalse(pattern.isEmpty());
        assertTrue(PatternDetailsHelper.isEncodedPattern(pattern));
        assertEquals(BuiltInRegistries.ITEM.get(AEItemIds.PROCESSING_PATTERN), pattern.getItem());
        EncodedProcessingPattern details = pattern.get(AEComponents.ENCODED_PROCESSING_PATTERN);
        assertNotNull(details);
        assertEquals(1, details.sparseInputs().size());
        assertEquals(AEItemKey.of(input), details.sparseInputs().get(0).what());
        assertEquals(1, details.sparseInputs().get(0).amount());
        assertEquals(1, details.sparseOutputs().size());
        assertEquals(AEItemKey.of(output), details.sparseOutputs().get(0).what());
        assertEquals(1, details.sparseOutputs().get(0).amount());
    }

    @Test
    void stonecutterRecipeEncodesToStonecuttingPattern() {
        Item output = item("pe_stone_out");
        Item input = item("pe_stone_in");
        StonecutterRecipe recipe = new StonecutterRecipe(id("pe_stone").toString(),
                Ingredient.of(input), new ItemStack(output, 1));
        RecipeHolder<?> holder = new RecipeHolder<>(id("pe_stone"), recipe);
        RecipeView view = PrintPlan.viewOf(holder, fixture.provider());
        assertNotNull(view);

        ItemStack pattern = PatternEncoder.encode(view, List.of(), manager(holder), fixture.provider(), true, false);

        assertFalse(pattern.isEmpty());
        assertTrue(PatternDetailsHelper.isEncodedPattern(pattern));
        assertEquals(BuiltInRegistries.ITEM.get(AEItemIds.STONECUTTING_PATTERN), pattern.getItem());
        EncodedStonecuttingPattern details = pattern.get(AEComponents.ENCODED_STONECUTTING_PATTERN);
        assertNotNull(details);
        assertEquals(input, details.input().getItem());
        assertEquals(1, details.input().getCount());
        assertEquals(output, details.output().getItem());
        assertEquals(1, details.output().getCount());
    }

    @Test
    void smithingRecipeEncodesToSmithingPattern() {
        Item output = item("pe_smith_out");
        Item template = item("pe_smith_template");
        Item base = item("pe_smith_base");
        Item addition = item("pe_smith_addition");
        SmithingTransformRecipe recipe = new SmithingTransformRecipe(
                Ingredient.of(template), Ingredient.of(base), Ingredient.of(addition), new ItemStack(output, 1));
        RecipeHolder<?> holder = new RecipeHolder<>(id("pe_smith"), recipe);
        RecipeView view = PrintPlan.viewOf(holder, fixture.provider());
        assertNotNull(view);
        // the smithing view exposes the three inputs in template/base/addition order
        assertEquals(3, view.inputs().size());

        ItemStack pattern = PatternEncoder.encode(view, List.of(), manager(holder), fixture.provider(), true, false);

        assertFalse(pattern.isEmpty());
        assertTrue(PatternDetailsHelper.isEncodedPattern(pattern));
        assertEquals(BuiltInRegistries.ITEM.get(AEItemIds.SMITHING_TABLE_PATTERN), pattern.getItem());
        EncodedSmithingTablePattern details = pattern.get(AEComponents.ENCODED_SMITHING_TABLE_PATTERN);
        assertNotNull(details);
        assertEquals(template, details.template().getItem());
        assertEquals(base, details.base().getItem());
        assertEquals(addition, details.addition().getItem());
        assertEquals(output, details.resultItem().getItem());
        assertEquals(1, details.resultItem().getCount());
    }

    @Test
    void processingPatternCarriesTheRealYield() {
        Item output = item("pe_yield_out");
        Item input = item("pe_yield_in");
        RecipeHolder<?> holder = fixture.generic("pe_yield", fixture.type("pe_yield_type"),
                () -> new ItemStack(output, 9), Ingredient.of(input));
        RecipeView view = PrintPlan.viewOf(holder, fixture.provider());
        assertNotNull(view);

        ItemStack pattern = PatternEncoder.encode(view, List.of(AEItemKey.of(input)),
                manager(holder), fixture.provider(), true, false);

        assertFalse(pattern.isEmpty());
        EncodedProcessingPattern details = pattern.get(AEComponents.ENCODED_PROCESSING_PATTERN);
        assertNotNull(details);
        assertEquals(9, details.sparseOutputs().get(0).amount());
        assertEquals(1, details.sparseInputs().size());
        assertEquals(AEItemKey.of(input), details.sparseInputs().get(0).what());
        assertEquals(1, details.sparseInputs().get(0).amount());
    }

    @Test
    void shapelessOneIngredientEncodesToNineSlotGrid() {
        Item output = item("pe_shapeless_out");
        Item input = item("pe_shapeless_in");
        RecipeHolder<?> holder = fixture.shapeless("pe_shapeless", output, 1, Ingredient.of(input));
        RecipeView view = PrintPlan.viewOf(holder, fixture.provider());
        assertNotNull(view);

        ItemStack pattern = PatternEncoder.encode(view, List.of(AEItemKey.of(input)),
                manager(holder), fixture.provider(), true, false);

        // A 1-ingredient shapeless recipe must still encode a full 9-slot
        // grid; a shorter array throws the moment AE2 decodes the pattern.
        assertFalse(pattern.isEmpty());
        assertTrue(PatternDetailsHelper.isEncodedPattern(pattern));
        assertEquals(BuiltInRegistries.ITEM.get(AEItemIds.CRAFTING_PATTERN), pattern.getItem());
        EncodedCraftingPattern details = pattern.get(AEComponents.ENCODED_CRAFTING_PATTERN);
        assertNotNull(details);
        List<ItemStack> grid = details.inputs();
        assertEquals(9, grid.size());
        assertEquals(input, grid.get(0).getItem());
        for (int i = 1; i < 9; i++) {
            assertTrue(grid.get(i).isEmpty());
        }
        assertEquals(output, details.result().getItem());
        assertEquals(1, details.result().getCount());
    }

    @Test
    void selectedCandidateReplacesFirstIngredientItem() {
        Item output = item("pe_tag_out");
        Item oak = item("pe_tag_oak");
        Item birch = item("pe_tag_birch");
        var planks = fixture.tag("pe_planks", oak, birch);
        RecipeHolder<?> holder = fixture.shapeless("pe_tag", output, 1, Ingredient.of(planks));
        RecipeView view = PrintPlan.viewOf(holder, fixture.provider());
        assertNotNull(view);
        // the tag resolves to both members; getItems()[0] is the first registered
        assertEquals(2, view.inputs().get(0).candidates().size());

        // The client picks birch; the grid must encode birch, not oak.
        ItemStack pattern = PatternEncoder.encode(view, List.of(AEItemKey.of(birch)),
                manager(holder), fixture.provider(), true, false);

        assertFalse(pattern.isEmpty());
        EncodedCraftingPattern details = pattern.get(AEComponents.ENCODED_CRAFTING_PATTERN);
        assertNotNull(details);
        assertEquals(birch, details.inputs().get(0).getItem());
    }
    @Test
    void gappedShapedRecipeEncodesChosenCandidatesPerNonBlankSlot() {
        Item output = item("pe_gap_out");
        Item a = item("pe_gap_a");
        Item b = item("pe_gap_b");
        Item c = item("pe_gap_c");
        Item d = item("pe_gap_d");
        Item e = item("pe_gap_e");
        Item f = item("pe_gap_f");
        Item g = item("pe_gap_g");
        Item h = item("pe_gap_h");
        Map<Character, Ingredient> chars = Map.of(
                'A', Ingredient.of(a), 'B', Ingredient.of(b), 'C', Ingredient.of(c), 'D', Ingredient.of(d),
                'E', Ingredient.of(e), 'F', Ingredient.of(f), 'G', Ingredient.of(g), 'H', Ingredient.of(h));
        RecipeHolder<?> holder = fixture.shapedGapped("pe_gap", output, 1, chars, "ABC", "D E", "FGH");
        RecipeView view = PrintPlan.viewOf(holder, fixture.provider());
        assertNotNull(view);

        // One candidate per non-blank slot, in slot order; the blank
        // center slot consumes no candidate.
        ItemStack encoded = PatternEncoder.encode(view,
                List.of(AEItemKey.of(a), AEItemKey.of(b), AEItemKey.of(c), AEItemKey.of(d),
                        AEItemKey.of(e), AEItemKey.of(f), AEItemKey.of(g), AEItemKey.of(h)),
                manager(holder), fixture.provider(), true, false);

        assertFalse(encoded.isEmpty());
        assertTrue(PatternDetailsHelper.isEncodedPattern(encoded));
        EncodedCraftingPattern details = encoded.get(AEComponents.ENCODED_CRAFTING_PATTERN);
        assertNotNull(details);
        List<ItemStack> grid = details.inputs();
        assertEquals(9, grid.size());
        // The 3x3 pattern is top-left aligned; the blank center slot
        // (recipe slot 4) stays EMPTY, and each non-blank slot's chosen
        // candidate lands in its own grid cell.
        assertEquals(a, grid.get(0).getItem());
        assertEquals(b, grid.get(1).getItem());
        assertEquals(c, grid.get(2).getItem());
        assertEquals(d, grid.get(3).getItem());
        assertTrue(grid.get(4).isEmpty());
        assertEquals(e, grid.get(5).getItem());
        assertEquals(f, grid.get(6).getItem());
        assertEquals(g, grid.get(7).getItem());
        assertEquals(h, grid.get(8).getItem());
        assertEquals(output, details.result().getItem());
        assertEquals(1, details.result().getCount());
    }

    @Test
    void fluidSubstitutionFlagIsEncodedOnCraftingPatterns() {
        Item output = item("pe_fluid_out");
        Item a = item("pe_fluid_a");
        RecipeHolder<?> holder = fixture.shapeless("pe_fluid", output, 1, Ingredient.of(a));
        RecipeView view = PrintPlan.viewOf(holder, fixture.provider());
        assertNotNull(view);

        // The flag is stored in the encoded pattern, not just a runtime
        // behavior: the same recipe encodes differently with it on/off.
        ItemStack enabled = PatternEncoder.encode(view, List.of(AEItemKey.of(a)),
                manager(holder), fixture.provider(), true, true);
        EncodedCraftingPattern enabledDetails = enabled.get(AEComponents.ENCODED_CRAFTING_PATTERN);
        assertNotNull(enabledDetails);
        assertTrue(enabledDetails.canSubstitute());
        assertTrue(enabledDetails.canSubstituteFluids());

        ItemStack disabled = PatternEncoder.encode(view, List.of(AEItemKey.of(a)),
                manager(holder), fixture.provider(), true, false);
        EncodedCraftingPattern disabledDetails = disabled.get(AEComponents.ENCODED_CRAFTING_PATTERN);
        assertNotNull(disabledDetails);
        assertTrue(disabledDetails.canSubstitute());
        assertFalse(disabledDetails.canSubstituteFluids());
    }
}
