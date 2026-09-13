package dev.cryolithic.rpp.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
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

    /** A shapeless crafting recipe holder producing {@code output} from two inputs. */
    private RecipeHolder<CraftingRecipe> shapeless2(String name, Item output, Item input1, Item input2) {
        ShapelessRecipe recipe = new ShapelessRecipe(id(name).toString(), CraftingBookCategory.EQUIPMENT,
                new ItemStack(output, 1), NonNullList.of(Ingredient.EMPTY, Ingredient.of(input1), Ingredient.of(input2)));
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
                List.of(entry(cToB, b, c), entry(bToX, x, b)), MAX_BATCH, false, null);

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
                List.of(entry(bToX, x, b), ghost), MAX_BATCH, false, null);

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
                List.of(entry(bToX, other, b)), MAX_BATCH, false, null);

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
                List.of(entry(bToX, x, b), entry(cToUnrelated, unrelated, b)), MAX_BATCH, false, null);

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
                List.of(entry(bToX, x, b), entry(cToB, b, c), entry(dToC, c, d)), 2, false, null);

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
                List.of(entry(bToX, x, b), entry(dToC, c, d), entry(cToB, b, c)), MAX_BATCH, false, null);

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
                List.of(entry(yToX, x, y), entry(zToY, y, z)), MAX_BATCH, false, null);
        assertTrue(plain.accepted());
        assertEquals(List.of(zToY.id(), yToX.id()), ids(plain));

        // with grouping: the ASSEMBLER batch precedes the MACHINE batch
        PrintPlan.Result grouped = PrintPlan.validate(manager(yToX, zToY), fixture.provider(), echoed,
                List.of(entry(yToX, x, y), entry(zToY, y, z)), MAX_BATCH, true, null);
        assertTrue(grouped.accepted());
        assertEquals(List.of(yToX.id(), zToY.id()), ids(grouped));
    }

    @Test
    void zeroInputSlotRecipeWithCandidatesRejectsTheWholeJob() {
        Item x = item("pp_zin_x");
        Item b = item("pp_zin_b");
        RecipeHolder<CraftingRecipe> bToX = shapeless("pp_zin_b_to_x", x, b);
        RecipeHolder<?> gToX = fixture.generic("pp_zin_g_to_x", fixture.type("pp_zin_machine"),
                () -> new ItemStack(x, 1));
        ItemStack echoed = echoPattern(bToX, x, b);

        PrintPlan.Result result = PrintPlan.validate(manager(bToX, gToX), fixture.provider(), echoed,
                List.of(entry(gToX, x, b)), MAX_BATCH, false, null);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("no input slots"));
    }

    @Test
    void zeroInputSlotRecipeWithNoCandidatesRejectsTheWholeJob() {
        // A slotless recipe passes the candidate-size check (empty list,
        // zero non-blank slots) but no encoder can encode it: AE2's
        // processing encoder requires at least one input. Reject at
        // validation rather than accept the plan and drop the entry
        // silently at encode time.
        Item x = item("pp_zinok_x");
        Item b = item("pp_zinok_b");
        RecipeHolder<CraftingRecipe> bToX = shapeless("pp_zinok_b_to_x", x, b);
        RecipeHolder<?> gToX = fixture.generic("pp_zinok_g_to_x", fixture.type("pp_zinok_machine"),
                () -> new ItemStack(x, 1));
        ItemStack echoed = echoPattern(bToX, x, b);

        PrintPlan.Result result = PrintPlan.validate(manager(bToX, gToX), fixture.provider(), echoed,
                List.of(entry(gToX, x)), MAX_BATCH, false, null);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("no input slots"));
    }

    @Test
    void shortCandidateListRejectsTheWholeJob() {
        Item x = item("pp_short_x");
        Item e = item("pp_short_e");
        Item i1 = item("pp_short_i1");
        Item i2 = item("pp_short_i2");
        RecipeHolder<CraftingRecipe> eToX = shapeless("pp_short_e_to_x", x, e);
        RecipeHolder<CraftingRecipe> twoToX = shapeless2("pp_short_two_to_x", x, i1, i2);
        ItemStack echoed = echoPattern(eToX, x, e);

        PrintPlan.Result result = PrintPlan.validate(manager(eToX, twoToX), fixture.provider(), echoed,
                List.of(entry(twoToX, x, i1)), MAX_BATCH, false, null);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("candidates but the recipe has"));
    }

    @Test
    void longCandidateListRejectsTheWholeJob() {
        Item x = item("pp_long_x");
        Item e = item("pp_long_e");
        Item i1 = item("pp_long_i1");
        Item i2 = item("pp_long_i2");
        Item i3 = item("pp_long_i3");
        RecipeHolder<CraftingRecipe> eToX = shapeless("pp_long_e_to_x", x, e);
        RecipeHolder<CraftingRecipe> twoToX = shapeless2("pp_long_two_to_x", x, i1, i2);
        ItemStack echoed = echoPattern(eToX, x, e);

        PrintPlan.Result result = PrintPlan.validate(manager(eToX, twoToX), fixture.provider(), echoed,
                List.of(entry(twoToX, x, i1, i2, i3)), MAX_BATCH, false, null);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("candidates but the recipe has"));
    }

    @Test
    void nonItemCandidateRejectsTheWholeJob() {
        Item x = item("pp_nonitem_x");
        Item b = item("pp_nonitem_b");
        RecipeHolder<CraftingRecipe> bToX = shapeless("pp_nonitem_b_to_x", x, b);
        ItemStack echoed = echoPattern(bToX, x, b);
        AEKey fluid = AEFluidKey.of(BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath("minecraft", "water")));
        PlanEntry fluidEntry = new PlanEntry(bToX.id(), AEItemKey.of(x), List.of(fluid));

        PrintPlan.Result result = PrintPlan.validate(manager(bToX), fixture.provider(), echoed,
                List.of(fluidEntry), MAX_BATCH, false, null);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("non-item candidate"));
    }
    /**
     * A chest-like 3x3 shaped recipe with a blank center slot: eight
     * distinct ingredients around one blank, in slot order a..h.
     */
    private RecipeHolder<?> gappedChest(String name, Item output, Item a, Item b, Item c, Item d,
            Item e, Item f, Item g, Item h) {
        Map<Character, Ingredient> pattern = Map.of(
                'A', Ingredient.of(a), 'B', Ingredient.of(b), 'C', Ingredient.of(c), 'D', Ingredient.of(d),
                'E', Ingredient.of(e), 'F', Ingredient.of(f), 'G', Ingredient.of(g), 'H', Ingredient.of(h));
        return fixture.shapedGapped(name, output, 1, pattern, "ABC", "D E", "FGH");
    }

    @Test
    void gappedRecipeWithOneCandidatePerNonBlankSlotIsAccepted() {
        Item x = item("pp_gap_ok_x");
        Item a = item("pp_gap_ok_a");
        Item b = item("pp_gap_ok_b");
        Item c = item("pp_gap_ok_c");
        Item d = item("pp_gap_ok_d");
        Item e = item("pp_gap_ok_e");
        Item f = item("pp_gap_ok_f");
        Item g = item("pp_gap_ok_g");
        Item h = item("pp_gap_ok_h");
        RecipeHolder<?> gToX = gappedChest("pp_gap_ok", x, a, b, c, d, e, f, g, h);
        RecipeView view = PrintPlan.viewOf(gToX, fixture.provider());
        assertNotNull(view);
        assertEquals(9, view.inputs().size());
        assertEquals(8, view.inputs().stream().filter(s -> !s.isEmpty()).count());
        ItemStack echoed = echoPattern(shapeless("pp_gap_ok_echo", x, a), x, a);

        PrintPlan.Result result = PrintPlan.validate(manager(gToX), fixture.provider(), echoed,
                List.of(entry(gToX, x, a, b, c, d, e, f, g, h)), MAX_BATCH, false, null);

        assertTrue(result.accepted());
        assertNull(result.rejectionReason());
    }

    @Test
    void gappedRecipeWithOneCandidatePerRawSlotRejectsTheWholeJob() {
        // the exact-size rule counts non-blank slots: nine candidates
        // (one per raw grid slot) no longer match the eight real inputs
        Item x = item("pp_gap_long_x");
        Item a = item("pp_gap_long_a");
        Item b = item("pp_gap_long_b");
        Item c = item("pp_gap_long_c");
        Item d = item("pp_gap_long_d");
        Item e = item("pp_gap_long_e");
        Item f = item("pp_gap_long_f");
        Item g = item("pp_gap_long_g");
        Item h = item("pp_gap_long_h");
        Item i = item("pp_gap_long_i");
        RecipeHolder<?> gToX = gappedChest("pp_gap_long", x, a, b, c, d, e, f, g, h);
        ItemStack echoed = echoPattern(shapeless("pp_gap_long_echo", x, a), x, a);

        PrintPlan.Result result = PrintPlan.validate(manager(gToX), fixture.provider(), echoed,
                List.of(entry(gToX, x, a, b, c, d, e, f, g, h, i)), MAX_BATCH, false, null);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("candidates but the recipe has"));
    }

    @Test
    void gappedRecipeWithMisalignedCandidateRejectsTheWholeJob() {
        // g (G's candidate) sent at F's position: the cursor must test
        // each candidate against its own slot's ingredient
        Item x = item("pp_gap_mis_x");
        Item a = item("pp_gap_mis_a");
        Item b = item("pp_gap_mis_b");
        Item c = item("pp_gap_mis_c");
        Item d = item("pp_gap_mis_d");
        Item e = item("pp_gap_mis_e");
        Item f = item("pp_gap_mis_f");
        Item g = item("pp_gap_mis_g");
        Item h = item("pp_gap_mis_h");
        RecipeHolder<?> gToX = gappedChest("pp_gap_mis", x, a, b, c, d, e, f, g, h);
        ItemStack echoed = echoPattern(shapeless("pp_gap_mis_echo", x, a), x, a);

        PrintPlan.Result result = PrintPlan.validate(manager(gToX), fixture.provider(), echoed,
                List.of(entry(gToX, x, a, b, c, d, e, g, f, h)), MAX_BATCH, false, null);

        assertFalse(result.accepted());
        assertTrue(result.rejectionReason().contains("chosen candidate not accepted"));
    }

    @Test
    void selfReferentialEntryNotProducingGoalIsRejected() {
        // A recipe that consumes its own output, where that output is neither
        // the root goal nor an ingredient of another entry: the entry's own
        // input no longer counts toward its connectivity, so it is rejected.
        Item x = item("pp_selfloop_x");
        Item g = item("pp_selfloop_goal");
        RecipeHolder<CraftingRecipe> selfLoop = shapeless("pp_selfloop", x, x); // 1 x -> x
        RecipeHolder<CraftingRecipe> echo = shapeless("pp_selfloop_echo", g, g);
        ItemStack echoed = echoPattern(echo, g, g); // root goal = g, not x

        PrintPlan.Result result = PrintPlan.validate(manager(selfLoop), fixture.provider(), echoed,
                List.of(entry(selfLoop, x, x)), MAX_BATCH, false, null);

        assertFalse(result.accepted());
    }

    @Test
    void twoEntryLoopNotProducingGoalIsRejected() {
        // A closed ingredient loop (a <-> b) that does not produce the root
        // goal: each entry's output feeds the other, so both pass
        // connectivity, but the ordering step rejects the cycle.
        Item a = item("pp_loop_a");
        Item b = item("pp_loop_b");
        Item c = item("pp_loop_goal");
        RecipeHolder<CraftingRecipe> aToB = shapeless("pp_loop_a_to_b", b, a); // 1 a -> b
        RecipeHolder<CraftingRecipe> bToA = shapeless("pp_loop_b_to_a", a, b); // 1 b -> a
        RecipeHolder<CraftingRecipe> echo = shapeless("pp_loop_echo", c, c);
        ItemStack echoed = echoPattern(echo, c, c); // root goal = c, demanded by no entry

        PrintPlan.Result result = PrintPlan.validate(manager(aToB, bToA), fixture.provider(), echoed,
                List.of(entry(aToB, b, a), entry(bToA, a, b)), MAX_BATCH, false, null);

        assertFalse(result.accepted());
    }

    @Test
    void selfReferentialEntryProducingGoalIsAccepted() {
        // A recipe that consumes its own output where that output IS the root
        // goal: the entry is connected (it produces the goal) and its
        // self-dependency is not a cycle, because the goal's seed is supplied
        // externally by the user.
        Item x = item("pp_selfok_x");
        RecipeHolder<CraftingRecipe> selfLoop = shapeless("pp_selfok", x, x); // 1 x -> x
        RecipeHolder<CraftingRecipe> echo = shapeless("pp_selfok_echo", x, x);
        ItemStack echoed = echoPattern(echo, x, x); // root goal = x

        PrintPlan.Result result = PrintPlan.validate(manager(selfLoop), fixture.provider(), echoed,
                List.of(entry(selfLoop, x, x)), MAX_BATCH, false, null);

        assertTrue(result.accepted());
        assertNull(result.rejectionReason());
    }
}
