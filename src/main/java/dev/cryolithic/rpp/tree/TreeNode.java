package dev.cryolithic.rpp.tree;

import org.jetbrains.annotations.Nullable;

/**
 * A node in the recipe tree (DESIGN.md §7.1). Three kinds: an {@link ItemNode}
 * is what to make, a {@link RecipeNode} is how to make it, and an
 * {@link IngredientNode} is what it takes.
 *
 * <p>The id is dense and stable for the session; the state drives the GUI's
 * expand/collapse rendering.</p>
 */
public sealed interface TreeNode permits ItemNode, RecipeNode, IngredientNode {
    /** Dense int, assigned by the builder, stable for the session. */
    int id();

    /** The parent node, or null for the root. */
    @Nullable
    TreeNode parent();

    State state();

    /** Expand/collapse state of a node (DESIGN.md §7.1). */
    enum State {
        /** Materialized and rendered collapsed; children exist. */
        COLLAPSED,
        /** Materialized; children created. */
        EXPANDED,
        /** Created but children not yet created. */
        UNEXPANDED,
        /** An ancestor goal reappears; terminal, still selectable as a raw input. */
        CYCLE,
        /** A cap was hit; not expanded. */
        CAPPED,
        /** Expansion failed. */
        ERROR
    }
}
