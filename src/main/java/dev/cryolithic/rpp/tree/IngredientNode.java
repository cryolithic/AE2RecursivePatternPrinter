package dev.cryolithic.rpp.tree;

import dev.cryolithic.rpp.recipe.IngredientView;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A "what it takes" node (DESIGN.md §7.1): one input slot of the parent
 * recipe. A tag-based input keeps its candidates here, so eleven copper
 * ingots are one node with eleven candidates, not eleven parallel subtrees.
 */
public final class IngredientNode implements TreeNode {
    private final IngredientView ingredient;
    private final int id;
    @Nullable
    private final TreeNode parent;
    private State state = State.UNEXPANDED;
    @Nullable
    private List<ItemNode> candidates;
    private int selectedCandidate;
    private boolean capped;

    IngredientNode(IngredientView ingredient, int id, @Nullable TreeNode parent) {
        this.ingredient = ingredient;
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

    public IngredientView ingredient() {
        return ingredient;
    }

    /** The candidate items, capped; null until expanded. */
    @Nullable
    public List<ItemNode> candidates() {
        return candidates;
    }

    void setCandidates(List<ItemNode> candidates) {
        this.candidates = candidates;
    }

    /** The chosen candidate, by index into {@link #candidates()}. */
    public int selectedCandidate() {
        return selectedCandidate;
    }

    public void setSelectedCandidate(int selectedCandidate) {
        this.selectedCandidate = selectedCandidate;
    }

    /** True when more candidates existed than the cap allows (DESIGN.md §8.3). */
    public boolean isCapped() {
        return capped;
    }

    void setCapped(boolean capped) {
        this.capped = capped;
    }
}
