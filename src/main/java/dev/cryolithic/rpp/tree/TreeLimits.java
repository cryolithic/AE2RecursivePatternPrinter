package dev.cryolithic.rpp.tree;

import dev.cryolithic.rpp.RppConfig;

/**
 * The five caps of DESIGN.md §8.3, all config-backed, all enforced during
 * expansion.
 *
 * @param maxDepth                  item nodes beyond this depth are marked CAPPED, not expanded
 * @param maxRecipesPerItem         keep top N by ranking, append a "+K more" marker
 * @param maxCandidatesPerIngredient keep top N per ingredient node; the node is marked capped
 * @param maxNodesPerExpansion      per-click cap; a breach aborts that one expansion
 * @param maxTotalNodes             session cap; further expansion is refused
 */
public record TreeLimits(
        int maxDepth,
        int maxRecipesPerItem,
        int maxCandidatesPerIngredient,
        int maxNodesPerExpansion,
        int maxTotalNodes) {

    /** §8.3 defaults: 8 / 6 / 8 / 4000 / 100000. */
    public static final TreeLimits DEFAULTS = new TreeLimits(8, 6, 8, 4000, 100_000);

    /** Read the caps from {@code rpp-client.toml}. */
    public static TreeLimits fromConfig() {
        return new TreeLimits(
                RppConfig.maxDepth(),
                RppConfig.maxRecipesPerItem(),
                RppConfig.maxCandidatesPerIngredient(),
                RppConfig.maxNodesPerExpansion(),
                RppConfig.maxTotalNodes());
    }
}
