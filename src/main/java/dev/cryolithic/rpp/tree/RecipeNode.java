package dev.cryolithic.rpp.tree;

import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A "how to make it" node (DESIGN.md §7.1): one candidate recipe for the
 * parent item node.
 */
public final class RecipeNode implements TreeNode {
    private final RecipeView recipe;
    private final int id;
    @Nullable
    private final TreeNode parent;
    private State state = State.UNEXPANDED;
    @Nullable
    private List<IngredientNode> ingredients;
    private Tier tier = Tier.PRIMARY;
    private Destination destination = Destination.assembler();
    private boolean collides;
    private double roundTripEfficiency = Double.NaN;
    private boolean userOverridden;
    private boolean costlyCollision;
    private boolean stickyRestored;
    private int oracleDepth;
    @Nullable
    private String rejectionReason;

    RecipeNode(RecipeView recipe, int id, @Nullable TreeNode parent) {
        this.recipe = recipe;
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

    public RecipeView recipe() {
        return recipe;
    }

    /** One node per input slot, in slot order; null until expanded. */
    @Nullable
    public List<IngredientNode> ingredients() {
        return ingredients;
    }

    void setIngredients(List<IngredientNode> ingredients) {
        this.ingredients = ingredients;
    }

    /** The tier decides the default checkbox state (DESIGN.md §8.4.1). */
    public Tier tier() {
        return tier;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    /** Where the printed pattern physically goes (DESIGN.md §17.7.2). */
    public Destination destination() {
        return destination;
    }

    public void setDestination(Destination destination) {
        this.destination = destination;
    }

    /** True when another selected source for this item shares the destination. */
    public boolean isCollides() {
        return collides;
    }

    public void setCollides(boolean collides) {
        this.collides = collides;
    }

    /** Round-trip efficiency; NaN when the recipe is not a reversal (DESIGN.md §8.4.2). */
    public double roundTripEfficiency() {
        return roundTripEfficiency;
    }

    public void setRoundTripEfficiency(double roundTripEfficiency) {
        this.roundTripEfficiency = roundTripEfficiency;
    }

    /** True when the player moved the recipe across the tier default. */
    public boolean isUserOverridden() {
        return userOverridden;
    }

    public void setUserOverridden(boolean userOverridden) {
        this.userOverridden = userOverridden;
    }

    /** True when a same-destination collision differs materially in cost (DESIGN.md §17.7.3). */
    public boolean isCostlyCollision() {
        return costlyCollision;
    }

    public void setCostlyCollision(boolean costlyCollision) {
        this.costlyCollision = costlyCollision;
    }

    /** True when the row was restored from a sticky source set (DESIGN.md §8.7). */
    public boolean isStickyRestored() {
        return stickyRestored;
    }

    public void setStickyRestored(boolean stickyRestored) {
        this.stickyRestored = stickyRestored;
    }

    /** The oracle input-depth signal, stored at build time for collision cost comparison. */
    public int oracleDepth() {
        return oracleDepth;
    }

    public void setOracleDepth(int oracleDepth) {
        this.oracleDepth = oracleDepth;
    }

    /** Human-readable rejection reason for a REJECTED tier; null otherwise (DESIGN.md §8.4.3). */
    @Nullable
    public String rejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(@Nullable String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }
}
