package dev.cryolithic.rpp.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the server-side print plan (DESIGN.md §10.2): validation
 * rejections and print ordering. Runs as plain JUnit on the shared
 * {@link RecipeIndexFixture}; the echoed input patterns are real encoded
 * crafting patterns (see {@link PatternItems}).
 */
class PrintPlanTest {
    private static final int MAX_BATCH = 128;

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

    /** A shapeless crafting recipe holder producing {@code output} from {@code input}. */
    private RecipeHolder<CraftingRecipe> shapeless(String name, Item output, Item input) {
        ShapelessRecipe recipe = new ShapelessRecipe(id(name).toString(), CraftingBookCategory.EQUIPMENT,
                new ItemStack(output, 1), NonNullList.of(Ingredient.EMPTY, Ingredient.of(input)));
        return new RecipeHolder<>(id(name), recipe);
    }

    /** An encoded crafting pattern whose primary output is {@code output}. */
    private ItemStack echoPattern(RecipeHolder<CraftingRecipe> holder, Item output, Item input) {
        return PatternDetailsHelper.encodeCraftingPattern(holder,
                new ItemStack[]{new ItemStack(input, 1)}, new ItemStack(output, 1), false, false);
    }

    private PlanEntry entry(RecipeHolder<?> holder, Item output, Item... candidates) {
        List<AEKey> keys = new ArrayList<>();
        for (Item candidate : candidates) {
            keys.add(AEItemKey.of(candidate));
        }
        return new PlanEntry(holder.id(), AEItemKey.of(output), List.copyOf(keys));
    }

    private List<ResourceLocation> ids(PrintPlan.Result result) {
        return result.orderedEntries().stream().map(PlanEntry::recipeId).toList();
    }

    @Test
    void validConnectedPlanIsAccepted() {
        Item x = item("pp_goal");
        Item b = item("pp_b");
        Item c = item("pp_c");
        RecipeHolder<CraftingRecipe> bToX = shapeless("pp_b_to_x", x, b);
        RecipeHolder<CraftingRecipe> cToB = shapeless("pp_c_to_b", b, c);
        ItemStack echoed = echoPattern(bToX, x, b);

        PrintPlan.Result result = PrintPlan.validate(manager(bToX, cToB), fixture.provider(), echoed,
                List.of(entry(cToB, b, c), entry(bToX, x, b)), MAX_BATCH, false);

        assertTrue(result.accepted());
        assertNull(result.rejectionReason());
        assertEquals(2, result.orderedEntries().size());
        assertNotNull(result.viewOf(entry(cToB, b, c)));
    }

    @Test
    void unknownRecipeIdRejectsTheWholeJob() {
        Item x = item("pp_unknown_x");
        Item b = item("pp_unknown_b");
        RecipeHolder<CraftingRecipe> bToX = shapeless("pp_unknown_b_to_x", x, b);
        ItemStack echoed = echoPattern(bToX, x, b);
        PlanEntry ghost = new PlanEntry(id("pp_no_such_recipe"), AEItemKey.of(x), List.of());

        PrintPlan.Result result = PrintPlan.validate(manager(bToX), fixture.provider(), echoed,
                List.of(entry(bToX, x, b), ghost), MAX_BATCH, false);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("unknown recipe"));
        assertTrue(result.orderedEntries().isEmpty());
    }

    @Test
    void claimedOutputMismatchRejectsTheWholeJob() {
        Item x = item("pp_mismatch_x");
        Item b = item("pp_mismatch_b");
        Item other = item("pp_mismatch_other");
        RecipeHolder<CraftingRecipe> bToX = shapeless("pp_mismatch_b_to_x", x, b);
        ItemStack echoed = echoPattern(bToX, x, b);

        PrintPlan.Result result = PrintPlan.validate(manager(bToX), fixture.provider(), echoed,
                List.of(entry(bToX, other, b)), MAX_BATCH, false);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("claimed output"));
    }

    @Test
    void unconnectedEntryRejectsTheWholeJob() {
        // smuggling: an entry whose output is neither the root goal nor an
        // ingredient of another entry
        Item x = item("pp_smuggle_x");
        Item b = item("pp_smuggle_b");
        Item unrelated = item("pp_smuggle_unrelated");
        RecipeHolder<CraftingRecipe> bToX = shapeless("pp_smuggle_b_to_x", x, b);
        RecipeHolder<CraftingRecipe> cToUnrelated = shapeless("pp_smuggle_c_to_unrelated", unrelated, b);
        ItemStack echoed = echoPattern(bToX, x, b);

        PrintPlan.Result result = PrintPlan.validate(manager(bToX, cToUnrelated), fixture.provider(), echoed,
                List.of(entry(bToX, x, b), entry(cToUnrelated, unrelated, b)), MAX_BATCH, false);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("unconnected"));
    }

    @Test
    void batchOverMaxRejectsTheWholeJob() {
        Item x = item("pp_batch_x");
        Item b = item("pp_batch_b");
        Item c = item("pp_batch_c");
        Item d = item("pp_batch_d");
        RecipeHolder<CraftingRecipe> bToX = shapeless("pp_batch_b_to_x", x, b);
        RecipeHolder<CraftingRecipe> cToB = shapeless("pp_batch_c_to_b", b, c);
        RecipeHolder<CraftingRecipe> dToC = shapeless("pp_batch_d_to_c", c, d);
        ItemStack echoed = echoPattern(bToX, x, b);

        PrintPlan.Result result = PrintPlan.validate(manager(bToX, cToB, dToC), fixture.provider(), echoed,
                List.of(entry(bToX, x, b), entry(cToB, b, c), entry(dToC, c, d)), 2, false);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("exceeds the limit"));
    }

    @Test
    void threeEntryChainOrdersDependencyFirst() {
        Item x = item("pp_chain_x");
        Item b = item("pp_chain_b");
        Item c = item("pp_chain_c");
        Item d = item("pp_chain_d");
        RecipeHolder<CraftingRecipe> bToX = shapeless("pp_chain_b_to_x", x, b);
        RecipeHolder<CraftingRecipe> cToB = shapeless("pp_chain_c_to_b", b, c);
        RecipeHolder<CraftingRecipe> dToC = shapeless("pp_chain_d_to_c", c, d);
        ItemStack echoed = echoPattern(bToX, x, b);

        // submitted deepest-last; the order must come out deepest-first
        PrintPlan.Result result = PrintPlan.validate(manager(bToX, cToB, dToC), fixture.provider(), echoed,
                List.of(entry(bToX, x, b), entry(dToC, c, d), entry(cToB, b, c)), MAX_BATCH, false);

        assertTrue(result.accepted());
        assertEquals(List.of(dToC.id(), cToB.id(), bToX.id()), ids(result));
    }

    @Test
    void destinationGroupingOrdersAssemblerBeforeMachine() {
        Item x = item("pp_group_x");
        Item y = item("pp_group_y");
        Item z = item("pp_group_z");
        RecipeHolder<CraftingRecipe> yToX = shapeless("pp_group_y_to_x", x, y); // ASSEMBLER
        RecipeHolder<?> zToY = fixture.generic("pp_group_z_to_y", fixture.type("pp_group_machine"),
                () -> new ItemStack(y, 1), Ingredient.of(z)); // MACHINE:rpp:pp_group_machine
        ItemStack echoed = echoPattern(yToX, x, y);

        // without grouping: the machine recipe (producer of y) precedes the
        // crafting recipe (consumer of y)
        PrintPlan.Result plain = PrintPlan.validate(manager(yToX, zToY), fixture.provider(), echoed,
                List.of(entry(yToX, x, y), entry(zToY, y, z)), MAX_BATCH, false);
        assertTrue(plain.accepted());
        assertEquals(List.of(zToY.id(), yToX.id()), ids(plain));

        // with grouping: the ASSEMBLER batch precedes the MACHINE batch
        PrintPlan.Result grouped = PrintPlan.validate(manager(yToX, zToY), fixture.provider(), echoed,
                List.of(entry(yToX, x, y), entry(zToY, y, z)), MAX_BATCH, true);
        assertTrue(grouped.accepted());
        assertEquals(List.of(yToX.id(), zToY.id()), ids(grouped));
    }
}
