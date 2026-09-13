package dev.cryolithic.rpp.print;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.cryolithic.rpp.recipe.IngredientView;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * Encodes a validated plan entry into an AE2 pattern ItemStack
 * (DESIGN.md §10.3).
 *
 * <p>Final signature: {@code encode(RecipeView, List<AEKey>, RecipeManager,
 * HolderLookup.Provider, boolean, boolean)}. The design's
 * {@code (RecipeView, IngredientChoice[], Level)} does not work as written:
 * the four AE2 encoders take no {@code Level}, the plan carries
 * {@code List<AEKey>} candidates rather than an {@code IngredientChoice}
 * type, and the server must re-resolve the typed {@link RecipeHolder} by id
 * at encode time, so the recipe manager and registry provider are passed in.
 * The substitution flags come from the caller (the config defaults
 * {@code allowSubstitutions} and {@code allowFluidSubstitutions} are applied
 * globally to the batch).</p>
 *
 * <p>Routing by recipe shape:</p>
 * <ul>
 *   <li>{@code CraftingRecipe} that fits a 3×3 grid →
 *       {@code encodeCraftingPattern} with the full 9-slot grid (the
 *       pattern top-left aligned, empty slots as {@link ItemStack#EMPTY})
 *       and the primary output stack;</li>
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
    private static final Logger LOGGER = LogManager.getLogger(PatternEncoder.class);

    private PatternEncoder() {
    }

    /**
     * Encodes the recipe described by {@code view} into an AE2 pattern.
     *
     * @param view               the normalized recipe view; its shape picks the encoder branch
     * @param selectedCandidates the chosen candidate per non-blank input slot, in slot order
     *                           (blank grid slots consume no input); used for the processing inputs,
     *                           the crafting grid slots, and the stonecutting/smithing input keys.
     *                           For a recipe that exposes no input slots, the candidates are the
     *                           pattern's inputs.
     * @param recipeManager      the server's recipe manager; the typed holder is re-resolved by id here
     * @param registries         registry provider, for result-item resolution
     * @param allowSubstitutions the batch-wide substitution flag (crafting/stonecutting/smithing only)
     * @param allowFluidSubstitutions the batch-wide fluid-substitution flag (crafting only)
     * @return the encoded pattern, or {@link ItemStack#EMPTY} when it cannot be encoded
     */
    public static ItemStack encode(RecipeView view, List<AEKey> selectedCandidates,
            RecipeManager recipeManager, HolderLookup.Provider registries,
            boolean allowSubstitutions, boolean allowFluidSubstitutions) {
        RecipeHolder<?> holder = recipeManager.byKey(view.id()).orElse(null);
        if (holder == null) {
            LOGGER.warn("rpp print: recipe {} no longer exists; skipping", view.id());
            return ItemStack.EMPTY;
        }
        Recipe<?> recipe = holder.value();
        try {
            if (recipe instanceof CraftingRecipe crafting) {
                return encodeCrafting(crafting, view, selectedCandidates, registries,
                        allowSubstitutions, allowFluidSubstitutions);
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
            List<AEKey> selectedCandidates, HolderLookup.Provider registries,
            boolean allowSubstitutions, boolean allowFluidSubstitutions) {
        int slots = view.gridWidth() > 0 ? view.gridWidth() * view.gridHeight() : view.inputs().size();
        if (slots > 9) {
            LOGGER.warn("rpp print: crafting recipe {} does not fit a 3x3 grid; skipping", view.id());
            return ItemStack.EMPTY;
        }
        // AE2 decodes a crafting pattern from a fixed 3x3 grid
        // (AECraftingPattern.CRAFTING_GRID_SLOTS): a shorter array throws
        // the moment the pattern is decoded, so the grid is always 9 slots.
        ItemStack[] grid = new ItemStack[9];
        Arrays.fill(grid, ItemStack.EMPTY);
        List<Ingredient> ingredients = crafting.getIngredients();
        // Blank grid slots consume no input; the candidate list holds one
        // entry per non-blank slot, in slot order, so the cursor only
        // advances past non-blank slots.
        int[] cursor = {0};
        if (view.gridWidth() > 0) {
            // Shaped: the pattern is top-left aligned in the 3x3 grid, so
            // pattern cell (r, c) lands on grid slot r * 3 + c.
            int width = view.gridWidth();
            for (int i = 0; i < ingredients.size(); i++) {
                grid[(i / width) * 3 + i % width] = slotStack(ingredients.get(i), selectedCandidates, cursor);
            }
        } else {
            // Shapeless: recipe-order ingredients fill the grid row-major.
            for (int i = 0; i < ingredients.size(); i++) {
                grid[i] = slotStack(ingredients.get(i), selectedCandidates, cursor);
            }
        }
        ItemStack output = crafting.getResultItem(registries);
        if (output.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return PatternDetailsHelper.encodeCraftingPattern(
                new RecipeHolder<>(view.id(), crafting), grid, output,
                allowSubstitutions, allowFluidSubstitutions);
    }

    /**
     * The grid stack for the next non-blank slot in recipe order: the
     * chosen candidate when one was selected for that slot, else the
     * slot's first candidate. A blank slot stays {@link ItemStack#EMPTY}
     * no matter what the candidate list holds and does not advance the
     * cursor.
     */
    private static ItemStack slotStack(Ingredient ingredient, List<AEKey> selectedCandidates, int[] cursor) {
        if (ingredient.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int slot = cursor[0]++;
        if (slot < selectedCandidates.size() && selectedCandidates.get(slot) instanceof AEItemKey key) {
            return key.toStack();
        }
        return ingredient.getItems()[0];
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
        // One input per non-blank slot when the recipe exposes slots
        // (blank grid slots consume nothing); an opaque recipe (no slots)
        // takes the selected candidates as its inputs.
        List<IngredientView> slots = view.inputs();
        int inputCount;
        if (slots.isEmpty()) {
            inputCount = selectedCandidates.size();
        } else {
            inputCount = 0;
            for (IngredientView slot : slots) {
                if (!slot.isEmpty()) {
                    inputCount++;
                }
            }
        }
        if (selectedCandidates.size() < inputCount) {
            return ItemStack.EMPTY;
        }
        List<GenericStack> in = new ArrayList<>(inputCount);
        if (slots.isEmpty()) {
            for (AEKey candidate : selectedCandidates) {
                if (candidate == null) {
                    return ItemStack.EMPTY;
                }
                in.add(new GenericStack(candidate, 1));
            }
        } else {
            int ci = 0;
            for (IngredientView slot : slots) {
                if (slot.isEmpty()) {
                    continue;
                }
                AEKey candidate = selectedCandidates.get(ci++);
                if (candidate == null) {
                    return ItemStack.EMPTY;
                }
                in.add(new GenericStack(candidate, 1));
            }
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
