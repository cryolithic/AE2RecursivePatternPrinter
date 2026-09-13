package dev.cryolithic.rpp.recipe;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/**
 * One input slot of a recipe. Tag-based inputs keep their tag id so the tree
 * can render "eleven candidates for one ingredient" instead of eleven
 * parallel subtrees (DESIGN.md §7.1).
 *
 * @param ingredient the vanilla ingredient
 * @param candidates ordered candidate item list
 * @param tagId      the tag id when the ingredient is tag-based, else null
 */
public record IngredientView(
        Ingredient ingredient,
        List<Item> candidates,
        @Nullable ResourceLocation tagId) {

    public IngredientView(Ingredient ingredient, List<Item> candidates) {
        this(ingredient, candidates, null);
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
}
