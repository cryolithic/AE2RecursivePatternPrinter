package dev.cryolithic.rpp.recipe;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/**
 * One input slot of a recipe. Tag-based inputs keep their tag id so the tree
 * can render "eleven candidates for one ingredient" instead of eleven
 * parallel subtrees (DESIGN.md §7.1).
 *
 * <p>{@code slotCount} is the slot's consumption: the maximum stack count
 * among the slot's alternatives. A tag slot with eleven count-1 members
 * consumes one item (slotCount 1), not eleven; a count-9 concrete slot
 * consumes nine. It is resolved on the main thread at index-build time
 * (DESIGN.md §9) so no consumer ever calls {@code Ingredient.getItems()}
 * off-thread.
 *
 * @param ingredient the vanilla ingredient
 * @param candidates ordered candidate item list
 * @param tagId      the tag id when the ingredient is tag-based, else null
 * @param slotCount  the slot's consumption (max stack count among members); 0 for a blank slot
 */
public record IngredientView(
        Ingredient ingredient,
        List<Item> candidates,
        @Nullable ResourceLocation tagId,
        int slotCount) {

    /**
     * Main-thread convenience: resolves the slot count from the ingredient's
     * stacks. Test fixtures and the main-thread resolve use this; the
     * background build uses the four-argument form with a precomputed count.
     */
    public IngredientView(Ingredient ingredient, List<Item> candidates) {
        this(ingredient, candidates, null, slotCountOf(ingredient));
    }

    public IngredientView(Ingredient ingredient, List<Item> candidates, @Nullable ResourceLocation tagId) {
        this(ingredient, candidates, tagId, slotCountOf(ingredient));
    }

    /**
     * True for a blank grid slot ({@code Ingredient.EMPTY}): a shaped
     * recipe pads its grid with empty slots, and such a slot imposes no
     * constraint — it consumes nothing, so no consumer may read its zero
     * candidates as "an input with no source".
     */
    public boolean isEmpty() {
        return ingredient().isEmpty();
    }

    /** The slot's consumption: max stack count among members, 0 for a blank slot. Main-thread only. */
    static int slotCountOf(Ingredient ingredient) {
        if (ingredient.isEmpty()) {
            return 0;
        }
        int max = 0;
        for (ItemStack stack : ingredient.getItems()) {
            max = Math.max(max, stack.getCount());
        }
        return Math.max(1, max);
    }
}
