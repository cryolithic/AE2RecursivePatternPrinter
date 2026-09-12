package dev.cryolithic.rpp.recipe;

import appeng.api.stacks.GenericStack;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Normalized view of any {@link net.minecraft.world.item.crafting.Recipe}
 * (DESIGN.md §6.3). Every backend collapses to this one shape so the tree
 * builder and the encoder never touch {@code Recipe<?>} directly.
 *
 * @param id         recipe id
 * @param type       recipe type, decides the encoder branch at encode time
 * @param inputs     ordered inputs; sparse slots preserved for crafting
 * @param outputs    outputs; {@code outputs[0]} is primary
 * @param gridWidth  crafting grid width; 0 for non-grid recipes
 * @param gridHeight crafting grid height; 0 for non-grid recipes
 * @param trusted    false = extracted heuristically, warn in the GUI and never auto-select
 */
public record RecipeView(
        ResourceLocation id,
        RecipeType<?> type,
        List<IngredientView> inputs,
        List<GenericStack> outputs,
        int gridWidth,
        int gridHeight,
        boolean trusted) {
}
