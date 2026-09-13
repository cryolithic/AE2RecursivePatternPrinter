package dev.cryolithic.rpp.gui.widget;

import dev.cryolithic.rpp.tree.ItemNode;
import dev.cryolithic.rpp.tree.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * One row of the flattened visible recipe tree (DESIGN.md §11.1). A lightweight
 * data holder: the kind, a reference into the tree, the indentation depth, and
 * (for a {@link Kind#MORE} marker) the count of recipes beyond the cap.
 *
 * <p>Rows are produced by {@link TreeWidget#rebuildRows()} and cached; only the
 * rows currently in the viewport are rendered, so a 2000-row tree scrolls at
 * full frame rate without per-frame allocation of the whole list.</p>
 */
public final class TreeRow {
    /** The three kinds of row rendered in the tree pane. */
    public enum Kind {
        /** An item node: disclosure triangle, item icon, name, count or summary. */
        ITEM,
        /** A recipe candidate: checkbox, icon, name, destination, yield, badges. */
        RECIPE,
        /** A "+K more" marker for recipes beyond the per-item cap. */
        MORE
    }

    private final Kind kind;
    @Nullable
    private final ItemNode item;
    @Nullable
    private final RecipeNode recipe;
    private final int depth;
    private final int moreCount;
    /** The index of the recipe within its parent item's {@code recipes()} list. */
    private final int recipeIndex;

    private TreeRow(Kind kind, @Nullable ItemNode item, @Nullable RecipeNode recipe, int depth, int moreCount,
            int recipeIndex) {
        this.kind = kind;
        this.item = item;
        this.recipe = recipe;
        this.depth = depth;
        this.moreCount = moreCount;
        this.recipeIndex = recipeIndex;
    }

    public static TreeRow item(ItemNode node, int depth) {
        return new TreeRow(Kind.ITEM, node, null, depth, 0, -1);
    }

    public static TreeRow recipe(RecipeNode node, int depth, int recipeIndex) {
        return new TreeRow(Kind.RECIPE, null, node, depth, 0, recipeIndex);
    }

    public static TreeRow more(int depth, int count) {
        return new TreeRow(Kind.MORE, null, null, depth, count, -1);
    }

    public Kind kind() {
        return kind;
    }

    public int depth() {
        return depth;
    }

    public int moreCount() {
        return moreCount;
    }

    public int recipeIndex() {
        return recipeIndex;
    }

    @Nullable
    public ItemNode item() {
        return item;
    }

    @Nullable
    public RecipeNode recipe() {
        return recipe;
    }

    public boolean isItem() {
        return kind == Kind.ITEM;
    }

    public boolean isRecipe() {
        return kind == Kind.RECIPE;
    }

    public boolean isMore() {
        return kind == Kind.MORE;
    }

    /** A pretty form of a recipe id: the path with underscores as spaces, namespaced unless vanilla. */
    public static String recipeName(RecipeNode recipe) {
        ResourceLocation id = recipe.recipe().id();
        String path = id.getPath().replace('_', ' ');
        String ns = id.getNamespace();
        if ("minecraft".equals(ns)) {
            return path;
        }
        return ns + ": " + path;
    }
}
