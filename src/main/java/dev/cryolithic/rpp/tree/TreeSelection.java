package dev.cryolithic.rpp.tree;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import static dev.cryolithic.rpp.tree.Tier.REJECTED;
import static dev.cryolithic.rpp.tree.TreeNode.State;

/**
 * Selection logic over a built tree (DESIGN.md §8.5, §11.2). The builder
 * allocates, the GUI mutates selection state, and this class answers the
 * questions both of them need.
 */
public final class TreeSelection {
    private TreeSelection() {
    }

    /**
     * True when the node has exactly one live (non-REJECTED) candidate, so
     * there is nothing to ask and it is selected automatically
     * (DESIGN.md §8.5). A node whose only candidate is REJECTED is not
     * forced: there is no live path to auto-select.
     */
    public static boolean isForced(ItemNode node) {
        List<RecipeNode> recipes = node.recipes();
        if (recipes == null) {
            return false;
        }
        int live = 0;
        for (RecipeNode recipe : recipes) {
            if (recipe.tier() != REJECTED) {
                live++;
            }
        }
        return live == 1;
    }

    /**
     * True when the subtree rooted at the node can be collapsed: every
     * descendant item node is forced, a leaf, or a cycle node, and no
     * descendant is untrusted, capped, or {@code UNKNOWN} from the oracle
     * (DESIGN.md §8.5). A node with two or more live candidates stays open,
     * because that is a real choice.
     */
    public static boolean isCollapsible(ItemNode node) {
        State state = node.state();
        if (state == State.CYCLE || (state == State.EXPANDED && node.isLeaf())) {
            return true; // terminal: no decision to hide
        }
        if (state == State.COLLAPSED) {
            return true; // the builder collapsed it: its subtree was all-forced
        }
        if (state != State.EXPANDED) {
            return false; // unexpanded, capped or errored: no verdict
        }
        if (node.craftability() == Craftability.UNKNOWN) {
            return false;
        }
        if (!isForced(node)) {
            return false; // a real choice exists
        }
        for (RecipeNode recipe : node.recipes()) {
            if (!recipe.recipe().trusted()) {
                return false;
            }
            if (recipe.ingredients() == null) {
                return false;
            }
            for (IngredientNode ingredient : recipe.ingredients()) {
                if (ingredient.candidates() == null) {
                    return false;
                }
                for (ItemNode candidate : ingredient.candidates()) {
                    if (!isCollapsible(candidate)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Flattens the selected subtree into plan rows (DESIGN.md §11.2). Walks
     * from the root: for each item node, each selected recipe becomes a row,
     * followed by the rows of that recipe's selected candidates. Leaves and
     * cycle nodes become raw-input rows. The same recipe (by id) and the
     * same raw input (by item) appear once, no matter how many times they
     * are reached.
     */
    public static List<PlanRow> planRows(ItemNode root) {
        List<PlanRow> rows = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        walkItem(root, seen, rows);
        return List.copyOf(rows);
    }

    /**
     * The number of patterns the plan will print (raw-input rows excluded).
     * O(tree) with allocation: call it at event time, not per frame — the
     * GUI caches the result in a {@link PlanCountCache} (DESIGN.md §9, §11.1).
     */
    public static int patternCount(ItemNode root) {
        int count = 0;
        for (PlanRow row : planRows(root)) {
            if (!row.rawInput()) {
                count++;
            }
        }
        return count;
    }

    private static void walkItem(ItemNode node, Set<Object> seen, List<PlanRow> rows) {
        if (node.isLeaf() || node.state() == State.CYCLE || node.isRawInput()) {
            if (node.goal() instanceof AEItemKey goalKey) {
                if (seen.add(goalKey.getItem())) {
                    rows.add(new PlanRow(null, node.goal(), List.of(), true, 1));
                }
            }
            return;
        }
        if (node.recipes() == null
                || (node.state() != State.EXPANDED && node.state() != State.COLLAPSED)) {
            return; // unexpanded, capped or errored: nothing selected below
        }
        for (int i = 0; i < node.recipes().size(); i++) {
            if (!node.selected().get(i)) {
                continue;
            }
            RecipeNode recipe = node.recipes().get(i);
            if (!seen.add(recipe.recipe().id())) {
                continue;
            }
            List<AEKey> candidates = new ArrayList<>();
            if (recipe.ingredients() != null) {
                for (IngredientNode ingredient : recipe.ingredients()) {
                    if (ingredient.candidates() == null || ingredient.candidates().isEmpty()) {
                        continue;
                    }
                    candidates.add(ingredient.candidates().get(ingredient.selectedCandidate()).goal());
                }
            }
            addRow(recipe, candidates, rows);
            if (recipe.ingredients() != null) {
                for (IngredientNode ingredient : recipe.ingredients()) {
                    if (ingredient.candidates() == null || ingredient.candidates().isEmpty()) {
                        continue;
                    }
                    walkItem(ingredient.candidates().get(ingredient.selectedCandidate()), seen, rows);
                }
            }
        }
    }

    private static void addRow(RecipeNode recipe, List<AEKey> candidates, List<PlanRow> rows) {
        GenericStack output = goalOutput(recipe);
        if (output == null) {
            // No output matches the parent's goal (should not happen: the
            // index buckets every recipe under each of its output items);
            // fall back to the primary product.
            output = recipe.recipe().outputs().get(0);
        }
        rows.add(new PlanRow(recipe.recipe().id(), output.what(),
                List.copyOf(candidates), false, (int) output.amount()));
    }

    /** The recipe output matching the parent item node's goal, or null. */
    private static @Nullable GenericStack goalOutput(RecipeNode recipe) {
        if (recipe.parent() instanceof ItemNode item && item.goal() instanceof AEItemKey goalKey) {
            Item goalItem = goalKey.getItem();
            for (GenericStack output : recipe.recipe().outputs()) {
                if (output.what() instanceof AEItemKey outKey && outKey.getItem() == goalItem) {
                    return output;
                }
            }
        }
        return null;
    }

    /**
     * True when any row of the plan carries a pre-print review flag
     * (DESIGN.md §11.2): an untrusted recipe, a reversal, a costly
     * collision, or a force-selected rejected recipe — the same four
     * flags the review screen counts. The print button uses this to open
     * the review even when alwaysReviewBeforePrint is off.
     */
    public static boolean hasFlags(ItemNode root, List<PlanRow> plan, double minRoundTripEfficiency) {
        Map<ResourceLocation, RecipeNode> recipeById = new HashMap<>();
        for (ItemNode item : itemNodesUnder(root)) {
            if (item.recipes() == null) {
                continue;
            }
            for (RecipeNode recipe : item.recipes()) {
                recipeById.putIfAbsent(recipe.recipe().id(), recipe);
            }
        }
        for (PlanRow row : plan) {
            if (row.recipeId() == null) {
                continue; // raw input: supplied from storage, never printed
            }
            RecipeNode recipe = recipeById.get(row.recipeId());
            if (recipe == null) {
                continue;
            }
            if (!recipe.recipe().trusted()) {
                return true;
            }
            double eff = recipe.roundTripEfficiency();
            if (!Double.isNaN(eff) && eff >= minRoundTripEfficiency) {
                return true;
            }
            if (recipe.isCostlyCollision()) {
                return true;
            }
            if (recipe.tier() == REJECTED) {
                return true;
            }
        }
        return false;
    }

    // --- selection operations ---

    /** Select every recipe at the node and every descendant item node. */
    public static void selectAll(ItemNode node) {
        for (ItemNode item : itemNodesUnder(node)) {
            if (item.recipes() == null) {
                continue;
            }
            for (int i = 0; i < item.recipes().size(); i++) {
                item.select(i);
            }
            item.setRawInput(false);
        }
    }

    /** Deselect every recipe at the node and every descendant item node. */
    public static void deselectAll(ItemNode node) {
        for (ItemNode item : itemNodesUnder(node)) {
            if (item.recipes() == null) {
                continue;
            }
            item.selected().clear();
            markRawInputIfEmpty(item);
        }
    }

    /**
     * True when the node's checked recipes exceed the per-item cap. Manual
     * selection is uncapped but flagged for the GUI's warning
     * (DESIGN.md §8.3).
     */
    public static boolean isOverCap(ItemNode node, int maxSourcesPerItem) {
        return node.selected().cardinality() > maxSourcesPerItem;
    }

    /**
     * Re-apply tiering exactly: clear the per-recipe overrides and the
     * raw-input mark, then recompute the default selection for every
     * expanded item node.
     */
    public static void resetToDefaults(ItemNode root, SourceSelector selector) {
        for (ItemNode item : itemNodesUnder(root)) {
            if (item.recipes() == null
                    || (item.state() != State.EXPANDED && item.state() != State.COLLAPSED)) {
                continue;
            }
            for (RecipeNode recipe : item.recipes()) {
                recipe.setUserOverridden(false);
                recipe.setStickyRestored(false);
            }
            item.setRawInput(false);
            selector.applyDefaults(item);
        }
    }

    /**
     * Recompute the collision flags for the node's selected recipes: two
     * selected recipes sharing a destination collide; the costly flag is
     * set only when they differ materially in cost and warnings are on
     * (DESIGN.md §17.7.3).
     */
    public static void refreshCollisions(ItemNode node, boolean warnOnCostlyCollisions) {
        List<RecipeNode> recipes = node.recipes();
        if (recipes == null) {
            return;
        }
        Map<Destination, List<RecipeNode>> byDestination = new HashMap<>();
        for (int i = 0; i < recipes.size(); i++) {
            if (!node.selected().get(i)) {
                continue;
            }
            byDestination.computeIfAbsent(recipes.get(i).destination(), k -> new ArrayList<>())
                    .add(recipes.get(i));
        }
        Set<RecipeNode> colliding = new HashSet<>();
        Map<RecipeNode, Boolean> costly = new HashMap<>();
        for (List<RecipeNode> group : byDestination.values()) {
            if (group.size() < 2) {
                continue;
            }
            boolean flag = warnOnCostlyCollisions && differsMaterially(group);
            for (RecipeNode recipe : group) {
                colliding.add(recipe);
                costly.put(recipe, flag);
            }
        }
        for (RecipeNode recipe : recipes) {
            recipe.setCollides(colliding.contains(recipe));
            recipe.setCostlyCollision(costly.getOrDefault(recipe, false));
        }
    }

    /**
     * Apply a source set within the tree: remember it (when a store is
     * present), set the selection on every node with the same goal, mark
     * the restored rows, and refresh the collisions.
     */
    public static void propagateSelection(ItemNode root, ItemNode node, Set<ResourceLocation> recipeIds,
            @Nullable StickyChoices sticky, boolean warnOnCostlyCollisions) {
        ResourceLocation goal = itemLocation(node.goal());
        if (sticky != null && goal != null) {
            sticky.remember(goal, recipeIds);
        }
        for (ItemNode other : itemNodesUnder(root)) {
            if (!sameItem(other.goal(), node.goal())) {
                continue;
            }
            setSelectionByIds(other, recipeIds);
            markRawInputIfEmpty(other);
            refreshCollisions(other, warnOnCostlyCollisions);
        }
    }

    // --- helpers ---

    private static void markRawInputIfEmpty(ItemNode node) {
        node.setRawInput(node.recipes() != null && node.selected().isEmpty());
    }

    private static void setSelectionByIds(ItemNode node, Set<ResourceLocation> recipeIds) {
        List<RecipeNode> recipes = node.recipes();
        if (recipes == null) {
            return;
        }
        node.selected().clear();
        for (int i = 0; i < recipes.size(); i++) {
            RecipeNode recipe = recipes.get(i);
            if (recipeIds.contains(recipe.recipe().id())) {
                node.select(i);
                recipe.setStickyRestored(true);
            } else {
                recipe.setStickyRestored(false);
            }
        }
    }

    private static boolean sameItem(AEKey a, AEKey b) {
        return a instanceof AEItemKey ak && b instanceof AEItemKey bk && ak.getItem() == bk.getItem();
    }

    private static @Nullable ResourceLocation itemLocation(AEKey goal) {
        if (!(goal instanceof AEItemKey goalKey)) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(goalKey.getItem());
    }

    private static List<ItemNode> itemNodesUnder(ItemNode root) {
        List<ItemNode> nodes = new ArrayList<>();
        collectItemNodes(root, nodes);
        return nodes;
    }

    private static void collectItemNodes(TreeNode node, List<ItemNode> out) {
        if (node instanceof ItemNode item) {
            out.add(item);
            if (item.recipes() != null) {
                for (RecipeNode recipe : item.recipes()) {
                    collectItemNodes(recipe, out);
                }
            }
        } else if (node instanceof RecipeNode recipe) {
            if (recipe.ingredients() != null) {
                for (IngredientNode ingredient : recipe.ingredients()) {
                    collectItemNodes(ingredient, out);
                }
            }
        } else if (node instanceof IngredientNode ingredient) {
            if (ingredient.candidates() != null) {
                for (ItemNode candidate : ingredient.candidates()) {
                    collectItemNodes(candidate, out);
                }
            }
        }
    }

    private static boolean differsMaterially(List<RecipeNode> group) {
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                RecipeNode a = group.get(i);
                RecipeNode b = group.get(j);
                double ea = a.roundTripEfficiency();
                double eb = b.roundTripEfficiency();
                boolean aNan = Double.isNaN(ea);
                boolean bNan = Double.isNaN(eb);
                if (aNan != bNan) {
                    return true; // one is a reversal, the other is not
                }
                if (!aNan && Math.abs(ea - eb) > 0.05) {
                    return true; // efficiencies differ
                }
                // A one-level depth delta is common (a recipe one step
                // deeper) and not material; two or more levels is.
                if (Math.abs(a.oracleDepth() - b.oracleDepth()) >= 2) {
                    return true; // input depths differ materially
                }
            }
        }
        return false;
    }
}
