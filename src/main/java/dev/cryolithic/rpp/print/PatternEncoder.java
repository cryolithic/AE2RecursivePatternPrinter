package dev.cryolithic.rpp.print;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.cryolithic.rpp.recipe.IngredientView;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes a validated plan entry into an AE2 pattern ItemStack
 * (DESIGN.md §10.3).
 *
 * <p>Final signature: {@code encode(RecipeView, List<AEKey>, RecipeManager,
 * HolderLookup.Provider, boolean)}. The design's
 * {@code (RecipeView, IngredientChoice[], Level)} does not work as written:
 * the four AE2 encoders take no {@code Level}, the plan carries
 * {@code List<AEKey>} candidates rather than an {@code IngredientChoice}
 * type, and the server must re-resolve the typed {@link RecipeHolder} by id
 * at encode time, so the recipe manager and registry provider are passed in.
 * The substitution flags come from the caller (the config default
 * {@code allowSubstitutions} is applied globally to the batch).</p>
 *
 * <p>Routing by recipe shape:</p>
 * <ul>
 *   <li>{@code CraftingRecipe} that fits a 3×3 grid →
 *       {@code encodeCraftingPattern} with the sparse input grid (empty
 *       slots as {@link ItemStack#EMPTY}) and the primary output stack;</li>
 *   <li>{@code StonecutterRecipe} → {@code encodeStonecuttingPattern}
 *       (single input, single output);</li>
 *   <li>{@code SmithingRecipe} → {@code encodeSmithingTablePattern}
 *       (template/base/addition from the recipe's three inputs);</li>
 *   <li>everything else → {@code encodeProcessingPattern} with
 *       {@code List<GenericStack>} inputs (one unit per chosen candidate)
 *       and outputs at the recipe's real yield.</li>
 * </ul>
 *
 * <p>When a recipe cannot be encoded (holder no longer present, unknown
 * shape, missing candidates), {@link ItemStack#EMPTY} is returned and the
 * caller counts it as skipped.</p>
 */
public final class PatternEncoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PatternEncoder.class);

    private PatternEncoder() {
    }

    /**
     * Encodes the recipe described by {@code view} into an AE2 pattern.
     *
     * @param view               the normalized recipe view; its shape picks the encoder branch
     * @param selectedCandidates the chosen candidate per input slot, in slot order;
     *                           used for the processing inputs and the stonecutting/smithing input
     *                           keys. For a recipe that exposes no input slots, the candidates are
     *                           the pattern's inputs.
     * @param recipeManager      the server's recipe manager; the typed holder is re-resolved by id here
     * @param registries         registry provider, for result-item resolution
     * @param allowSubstitutions the batch-wide substitution flag (crafting/stonecutting/smithing only)
     * @return the encoded pattern, or {@link ItemStack#EMPTY} when it cannot be encoded
     */
    public static ItemStack encode(RecipeView view, List<AEKey> selectedCandidates,
            RecipeManager recipeManager, HolderLookup.Provider registries, boolean allowSubstitutions) {
        RecipeHolder<?> holder = recipeManager.byKey(view.id()).orElse(null);
        if (holder == null) {
            LOGGER.warn("rpp print: recipe {} no longer exists; skipping", view.id());
            return ItemStack.EMPTY;
        }
        Recipe<?> recipe = holder.value();
        try {
            if (recipe instanceof CraftingRecipe crafting) {
                return encodeCrafting(crafting, view, registries, allowSubstitutions);
            }
            if (recipe instanceof StonecutterRecipe stonecutter) {
                return encodeStonecutting(stonecutter, view, selectedCandidates, registries, allowSubstitutions);
            }
            if (recipe instanceof SmithingRecipe smithing) {
                return encodeSmithing(smithing, view, selectedCandidates, registries, allowSubstitutions);
            }
            return encodeProcessing(view, selectedCandidates);
        } catch (Exception e) {
            LOGGER.warn("rpp print: failed to encode recipe {}", view.id(), e);
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack encodeCrafting(CraftingRecipe crafting, RecipeView view,
            HolderLookup.Provider registries, boolean allowSubstitutions) {
        int slots = view.gridWidth() > 0 ? view.gridWidth() * view.gridHeight() : view.inputs().size();
        if (slots > 9) {
            LOGGER.warn("rpp print: crafting recipe {} does not fit a 3x3 grid; skipping", view.id());
            return ItemStack.EMPTY;
        }
        List<Ingredient> ingredients = crafting.getIngredients();
        ItemStack[] grid = new ItemStack[slots];
        for (int i = 0; i < slots; i++) {
            Ingredient ingredient = i < ingredients.size() ? ingredients.get(i) : Ingredient.EMPTY;
            grid[i] = ingredient.isEmpty() ? ItemStack.EMPTY : ingredient.getItems()[0];
        }
        ItemStack output = crafting.getResultItem(registries);
        if (output.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return PatternDetailsHelper.encodeCraftingPattern(
                new RecipeHolder<>(view.id(), crafting), grid, output, allowSubstitutions, false);
    }

    private static ItemStack encodeStonecutting(StonecutterRecipe stonecutter, RecipeView view,
            List<AEKey> selectedCandidates, HolderLookup.Provider registries, boolean allowSubstitutions) {
        List<Ingredient> ingredients = stonecutter.getIngredients();
        if (ingredients.isEmpty() || ingredients.get(0).isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack output = stonecutter.getResultItem(registries);
        if (output.isEmpty()) {
            return ItemStack.EMPTY;
        }
        AEItemKey input = inputKey(view, selectedCandidates, 0);
        if (input == null) {
            return ItemStack.EMPTY;
        }
        return PatternDetailsHelper.encodeStonecuttingPattern(
                new RecipeHolder<>(view.id(), stonecutter), input, AEItemKey.of(output), allowSubstitutions);
    }

    private static ItemStack encodeSmithing(SmithingRecipe smithing, RecipeView view,
            List<AEKey> selectedCandidates, HolderLookup.Provider registries, boolean allowSubstitutions) {
        if (view.inputs().size() != 3) {
            LOGGER.warn("rpp print: smithing recipe {} does not expose three inputs; skipping", view.id());
            return ItemStack.EMPTY;
        }
        ItemStack output = smithing.getResultItem(registries);
        if (output.isEmpty()) {
            return ItemStack.EMPTY;
        }
        AEItemKey template = inputKey(view, selectedCandidates, 0);
        AEItemKey base = inputKey(view, selectedCandidates, 1);
        AEItemKey addition = inputKey(view, selectedCandidates, 2);
        if (template == null || base == null || addition == null) {
            return ItemStack.EMPTY;
        }
        return PatternDetailsHelper.encodeSmithingTablePattern(
                new RecipeHolder<>(view.id(), smithing), template, base, addition, AEItemKey.of(output),
                allowSubstitutions);
    }

    private static ItemStack encodeProcessing(RecipeView view, List<AEKey> selectedCandidates) {
        if (selectedCandidates.isEmpty() || view.outputs().isEmpty()) {
            return ItemStack.EMPTY;
        }
        // One input per recipe slot when the recipe exposes slots; an
        // opaque recipe (no slots) takes the selected candidates as its
        // inputs.
        List<IngredientView> slots = view.inputs();
        int inputCount = slots.isEmpty() ? selectedCandidates.size() : slots.size();
        if (selectedCandidates.size() < inputCount) {
            return ItemStack.EMPTY;
        }
        List<GenericStack> in = new ArrayList<>(inputCount);
        for (int i = 0; i < inputCount; i++) {
            AEKey candidate = selectedCandidates.get(i);
            if (candidate == null) {
                return ItemStack.EMPTY;
            }
            in.add(new GenericStack(candidate, 1));
        }
        return PatternDetailsHelper.encodeProcessingPattern(in, view.outputs());
    }

    /**
     * The AEItemKey for input slot {@code i}: the chosen candidate when one
     * was selected, else the slot's first candidate.
     */
    @Nullable
    private static AEItemKey inputKey(RecipeView view, List<AEKey> selectedCandidates, int slot) {
        if (slot < selectedCandidates.size() && selectedCandidates.get(slot) instanceof AEItemKey key) {
            return key;
        }
        if (slot < view.inputs().size()) {
            List<Item> candidates = view.inputs().get(slot).candidates();
            if (!candidates.isEmpty()) {
                return AEItemKey.of(candidates.get(0));
            }
        }
        return null;
    }
}
