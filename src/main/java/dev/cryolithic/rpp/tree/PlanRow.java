package dev.cryolithic.rpp.tree;

import appeng.api.stacks.AEKey;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * One row of the flattened plan (DESIGN.md §11.2): a selected recipe or a
 * raw input (a leaf or cycle node). Recipe rows become print requests
 * ({@code print.PlanEntry}); raw input rows are supplied from storage and
 * never printed.
 *
 * @param recipeId           the recipe to print; null for a raw input
 * @param output             the claimed output of the row
 * @param selectedCandidates the chosen candidate per input slot, in slot order; empty for a raw input
 * @param rawInput           true when the row is a raw input, not a pattern
 * @param yield              the amount of the parent item node's goal in the row's output; 1 for a raw input
 */
public record PlanRow(
        @Nullable ResourceLocation recipeId,
        AEKey output,
        List<AEKey> selectedCandidates,
        boolean rawInput,
        int yield) {
}
