package dev.cryolithic.rpp.tree;

import appeng.api.stacks.AEKey;
import java.util.BitSet;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A "what to make" node (DESIGN.md §7.1). The goal is the item this node
 * represents; recipes are the ways to make it, in ranked order; selected is
 * the multi-select of which recipes to print.
 */
public final class ItemNode implements TreeNode {
    private final AEKey goal;
    private final int id;
    @Nullable
    private final TreeNode parent;
    private State state = State.UNEXPANDED;
    @Nullable
    private List<RecipeNode> recipes;
    private final BitSet selected = new BitSet();
    private boolean forced;
    private boolean leaf;
    private boolean rawInput;
    @Nullable
    private Craftability craftability;

    ItemNode(AEKey goal, int id, @Nullable TreeNode parent) {
        this.goal = goal;
        this.id = id;
        this.parent = parent;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    @Nullable
    public TreeNode parent() {
        return parent;
    }

    @Override
    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    /** The item this node represents. */
    public AEKey goal() {
        return goal;
    }

    /** The ranked recipe candidates; null until expanded. */
    @Nullable
    public List<RecipeNode> recipes() {
        return recipes;
    }

    void setRecipes(List<RecipeNode> recipes) {
        this.recipes = recipes;
    }

    /** Multi-select of which recipes to print, by index into {@link #recipes()}. */
    public BitSet selected() {
        return selected;
    }

    public void select(int recipeIndex) {
        selected.set(recipeIndex);
    }

    public void deselect(int recipeIndex) {
        selected.clear(recipeIndex);
    }

    /** True when exactly one candidate exists; auto-selected (DESIGN.md §8.5). */
    public boolean isForced() {
        return forced;
    }

    void setForced(boolean forced) {
        this.forced = forced;
    }

    /** True when no usable recipe exists; the item is supplied as a raw input. */
    public boolean isLeaf() {
        return leaf;
    }

    void setLeaf(boolean leaf) {
        this.leaf = leaf;
    }

    /**
     * True when all of the node's recipes are deselected: the item is
     * supplied as a raw input and its subtree is pruned from the plan
     * (DESIGN.md §8.5). The GUI and the selector both set this; it is part
     * of the node's public selection state.
     */
    public boolean isRawInput() {
        return rawInput;
    }

    public void setRawInput(boolean rawInput) {
        this.rawInput = rawInput;
    }

    /** The oracle's verdict for this item; null until the builder asks. */
    @Nullable
    public Craftability craftability() {
        return craftability;
    }

    void setCraftability(Craftability craftability) {
        this.craftability = craftability;
    }
}
