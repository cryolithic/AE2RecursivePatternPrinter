package dev.cryolithic.rpp.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.ids.AEItemIds;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.EncodedProcessingPattern;
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

        ItemStack pattern = PatternEncoder.encode(view, List.of(), manager(holder), fixture.provider(), true);

        assertFalse(pattern.isEmpty());
        assertTrue(PatternDetailsHelper.isEncodedPattern(pattern));
        assertEquals(BuiltInRegistries.ITEM.get(AEItemIds.CRAFTING_PATTERN), pattern.getItem());
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
                manager(holder), fixture.provider(), true);

        assertFalse(pattern.isEmpty());
        assertTrue(PatternDetailsHelper.isEncodedPattern(pattern));
        assertEquals(BuiltInRegistries.ITEM.get(AEItemIds.PROCESSING_PATTERN), pattern.getItem());
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

        ItemStack pattern = PatternEncoder.encode(view, List.of(), manager(holder), fixture.provider(), true);

        assertFalse(pattern.isEmpty());
        assertTrue(PatternDetailsHelper.isEncodedPattern(pattern));
        assertEquals(BuiltInRegistries.ITEM.get(AEItemIds.STONECUTTING_PATTERN), pattern.getItem());
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

        ItemStack pattern = PatternEncoder.encode(view, List.of(), manager(holder), fixture.provider(), true);

        assertFalse(pattern.isEmpty());
        assertTrue(PatternDetailsHelper.isEncodedPattern(pattern));
        assertEquals(BuiltInRegistries.ITEM.get(AEItemIds.SMITHING_TABLE_PATTERN), pattern.getItem());
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
                manager(holder), fixture.provider(), true);

        assertFalse(pattern.isEmpty());
        EncodedProcessingPattern details = pattern.get(AEComponents.ENCODED_PROCESSING_PATTERN);
        assertNotNull(details);
        assertEquals(9, details.sparseOutputs().get(0).amount());
    }
}
