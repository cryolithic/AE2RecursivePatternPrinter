package dev.cryolithic.rpp.tree;

import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * A sticky source set: the recipes a player already chose for one goal item
 * (DESIGN.md §8.7). Persisted per player client-side, keyed by goal item,
 * and ranked at weight 1 in source ranking.
 *
 * @param goalItem  the goal item the set applies to
 * @param recipeIds the chosen recipe ids
 */
public record SourceSet(ResourceLocation goalItem, Set<ResourceLocation> recipeIds) {
}
