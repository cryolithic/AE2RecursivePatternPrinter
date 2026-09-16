package dev.cryolithic.rpp.tree;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.cryolithic.rpp.recipe.IngredientView;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import static dev.cryolithic.rpp.tree.TreeNode.State;

/**
 * Builds the recipe tree (DESIGN.md §8). Lazy by default: the root is
 * expanded exactly {@code initialDepth} levels and every other node is built
 * on demand via {@link #expandDetached(ItemNode)}.
 *
 * <p>Depth counts item nodes only (DESIGN.md §7.2): the root is depth 0 and
 * {@code ItemNode -> RecipeNode -> IngredientNode -> ItemNode} increments by
 * one, so the depth cap means "how many crafting steps deep". An item node
 * at depth {@code maxDepth} is CAPPED, not expanded.</p>
 *
 * <p>Cycle detection mirrors AE2's {@code CraftingTreeNode.notRecursive}
 * (DESIGN.md §8.2/§17.4). A candidate recipe is refused when an ancestor
 * goal appears among its outputs, or matches the first possible input of
 * any of its inputs (AE2 would silently refuse it). An ingredient whose
 * first possible input reappears on the ancestor path becomes a CYCLE
 * candidate, terminal but still selectable as a raw input; only the first
 * candidate is tested, matching AE2's {@code getPossibleInputs()[0]}
 * indexing, so a non-first candidate that is an ancestor is still offered.</p>
 *
 * <p>All five caps (DESIGN.md §8.3) are enforced during expansion. One
 * builder instance is one tree session: the ids are dense and the node count
 * is tracked across expansions.</p>
 */
public final class RecipeTreeBuilder {
    private final RecipeIndex index;
    private final TreeLimits limits;
    private final CraftabilityOracle oracle;
    private final ReversalDetector reversals;
    private final SourceSelector selector;
    private int nextId;
    private int totalNodes;
    /**
     * The node budget for the current user action (DESIGN.md §8.3): one
     * initial build or one click expansion. Shared across every
     * {@code expandItem} the action triggers (including auto-collapse
     * cascades), so a single action cannot exceed {@code maxNodesPerExpansion}
     * in aggregate. Null outside an action.
     */
    @Nullable
    private ExpansionBudget actionBudget;

    /**
     * @param index  the immutable recipe snapshot
     * @param limits the five caps
     * @param tags   the tag lookup for the reversal detector's storage-tag fast path
     */
    public RecipeTreeBuilder(RecipeIndex index, TreeLimits limits, ReversalDetector.TagLookup tags) {
        this(index, limits, tags,
                new SourceSelector(index, limits, SourceSelector.SelectionConfig.DEFAULTS, null));
    }

    /**
     * @param index    the immutable recipe snapshot
     * @param limits   the five caps
     * @param tags     the tag lookup for the reversal detector's storage-tag fast path
     * @param selector the source tiering and default-selection session
     */
    public RecipeTreeBuilder(RecipeIndex index, TreeLimits limits, ReversalDetector.TagLookup tags,
            SourceSelector selector) {
        this.index = Objects.requireNonNull(index);
        this.limits = Objects.requireNonNull(limits);
        this.oracle = new CraftabilityOracle(index);
        this.reversals = new ReversalDetector(index, tags);
        this.selector = Objects.requireNonNull(selector);
    }

    /**
     * Builds the root item node and expands exactly {@code initialDepth}
     * levels. Every other node starts UNEXPANDED and is built on demand via
     * {@link #expandDetached(ItemNode)}.
     */
    public ItemNode buildRoot(AEKey goal, int initialDepth) {
        ItemNode root = newItemNode(goal, null);
        actionBudget = new ExpansionBudget(limits.maxNodesPerExpansion());
        try {
            expandInitial(root, 0, initialDepth);
        } finally {
            actionBudget = null;
        }
        return root;
    }

    /**
     * Expands an UNEXPANDED item node without touching the live graph
     * (DESIGN.md §9): the subtree is built under a detached twin of the
     * node, and the new children are parent-linked to the live node. The
     * live node's own fields are not written during the build; the result
     * is attached on the client main thread via {@link #publish(Expansion)},
     * so the render thread never observes a half-attached state.
     *
     * <p>The twin mirrors the node (goal, depth position, craftability
     * verdict) and is a build scaffold: it consumes an id but not a node
     * slot, so the session node cap is unaffected. It is discarded after
     * publish.</p>
     */
    public Expansion expandDetached(ItemNode node) {
        if (node.state() != State.UNEXPANDED) {
            throw new IllegalArgumentException("only UNEXPANDED nodes can be detached-expanded");
        }
        ItemNode twin = new ItemNode(node.goal(), nextId++, node.parent());
        twin.setCraftability(node.craftability());
        actionBudget = new ExpansionBudget(limits.maxNodesPerExpansion());
        try {
            expandItem(twin, node);
        } finally {
            actionBudget = null;
        }
        return new Expansion(node, twin);
    }

    /**
     * Attaches a detached expansion to its live node (DESIGN.md §9). Must
     * run on the client main thread, before the update listener is
     * notified: the new children are already parent-linked to the live
     * node, so the attach is a single flip of the live node's own fields,
     * ordered so that the state never reads ahead of the structure. The
     * live node keeps its identity (object, id, pre-existing selection
     * state); only the new nodes come from the detached build.
     */
    public void publish(Expansion expansion) {
        ItemNode live = expansion.live;
        ItemNode twin = expansion.twin;
        live.setRecipes(twin.recipes());
        live.selected().or(twin.selected());
        live.setForced(twin.isForced());
        live.setRawInput(twin.isRawInput());
        live.setLeaf(twin.isLeaf());
        live.setFilteredCount(twin.filteredCount());
        live.setState(twin.state());
    }

    /**
     * The number of recipes beyond the kept ones, for the "+K more"
     * marker row. Counts only recipes that survived cycle filtering but
     * were cut by the per-item cap: recipes AE2 would refuse in this
     * context are not "more", they are unavailable.
     */
    public int moreCount(ItemNode node) {
        List<RecipeNode> recipes = node.recipes();
        if (recipes == null) {
            return 0;
        }
        return Math.max(0, node.filteredCount() - recipes.size());
    }

    /** True when the session cap is reached; the GUI shows a banner. */
    public boolean atTotalNodeCap() {
        return totalNodes >= limits.maxTotalNodes();
    }

    /** The total number of nodes created so far. */
    public int totalNodes() {
        return totalNodes;
    }

    // --- expansion ---

    private void expandInitial(ItemNode node, int depth, int initialDepth) {
        if (depth >= initialDepth) {
            return;
        }
        expandItem(node);
        if (node.state() != State.EXPANDED || node.recipes() == null) {
            return; // leaf, cycle or capped: nothing below
        }
        for (RecipeNode recipe : node.recipes()) {
            if (recipe.ingredients() == null) {
                continue;
            }
            for (IngredientNode ingredient : recipe.ingredients()) {
                if (ingredient.candidates() == null) {
                    continue;
                }
                for (ItemNode candidate : ingredient.candidates()) {
                    expandInitial(candidate, depth + 1, initialDepth);
                }
            }
        }
    }

    private void expandItem(ItemNode node) {
        expandItem(node, node);
    }

    /**
     * Expands {@code node}; the new recipe nodes are parent-linked to
     * {@code attachTo}. In a detached expansion {@code node} is the
     * detached twin (which receives the state writes) and {@code attachTo}
     * is the live node (which receives the children).
     */
    private void expandItem(ItemNode node, ItemNode attachTo) {
        if (node.state() != State.UNEXPANDED) {
            return;
        }
        int depth = depthOf(node);
        if (depth >= limits.maxDepth() || atTotalNodeCap()) {
            node.setState(State.CAPPED);
            return;
        }

        List<RecipeView> candidates = index.recipesFor(node.goal());
        if (candidates.isEmpty()) {
            node.setLeaf(true);
            node.setState(State.EXPANDED);
            return;
        }

        List<AEKey> ancestors = ancestorGoals(node);
        List<AEKey> inputAncestors = ancestorGoalsExcludingRoot(node);
        List<RecipeView> filtered = new ArrayList<>();
        for (RecipeView view : candidates) {
            if (producesAncestor(view, ancestors) || inputsAncestor(view, inputAncestors)) {
                continue; // AE2 would refuse this pattern in this context
            }
            filtered.add(view);
        }
        node.setFilteredCount(filtered.size());
        if (filtered.isEmpty()) {
            node.setLeaf(true);
            node.setState(State.EXPANDED);
            return;
        }
        // Rank by §8.4.4, then keep the top N by that ordering (DESIGN.md §8.3).
        List<RecipeView> kept = selector.rank(node, filtered);
        if (kept.size() > limits.maxRecipesPerItem()) {
            kept = new ArrayList<>(kept.subList(0, limits.maxRecipesPerItem()));
        }

        int nodeCount = 0;
        for (RecipeView view : kept) {
            nodeCount += 1 + view.inputs().size();
            for (IngredientView input : view.inputs()) {
                nodeCount += Math.min(input.candidates().size(), limits.maxCandidatesPerIngredient());
            }
        }
        // Per-action budget (DESIGN.md §8.3): one initial build or one click
        // may not exceed maxNodesPerExpansion in aggregate, even when
        // auto-collapse cascades many expansions. The session cap remains a
        // backstop across actions.
        boolean overBudget = actionBudget != null
                ? nodeCount > actionBudget.remaining
                : nodeCount > limits.maxNodesPerExpansion();
        if (overBudget || totalNodes + nodeCount > limits.maxTotalNodes()) {
            node.setState(State.CAPPED);
            return;
        }

        List<RecipeNode> recipes = new ArrayList<>(kept.size());
        for (RecipeView view : kept) {
            recipes.add(newRecipeNode(attachTo, view));
        }
        if (actionBudget != null) {
            actionBudget.remaining -= nodeCount;
        }
        node.setRecipes(recipes);
        selector.assign(node);
        node.setState(State.EXPANDED);
        Craftability verdict = node.craftability();
        if (TreeSelection.isForced(node)
                && (verdict == Craftability.CRAFTABLE_UNAMBIGUOUS || verdict == Craftability.CRAFTABLE_AMBIGUOUS)) {
            autoCollapse(node);
        }
    }

    /**
     * Oracle-driven auto-collapse (DESIGN.md §8.5): the node is forced and
     * the oracle has proven its goal craftable within budget — unambiguously
     * or via several paths. Ambiguity only ranks the oracle's depth; it does
     * not make the branch unsafe to collapse. Materialize the subtree, and if
     * every descendant is forced, a leaf, or a cycle (and none is untrusted,
     * capped, or {@code UNKNOWN}), collapse it into a summary row. A subtree
     * with a real choice stays open.
     */
    private void autoCollapse(ItemNode node) {
        for (RecipeNode recipe : node.recipes()) {
            if (recipe.ingredients() == null) {
                continue;
            }
            for (IngredientNode ingredient : recipe.ingredients()) {
                if (ingredient.candidates() == null) {
                    continue;
                }
                for (ItemNode candidate : ingredient.candidates()) {
                    expandItem(candidate);
                }
            }
        }
        if (TreeSelection.isCollapsible(node)) {
            node.setState(State.COLLAPSED);
        }
    }

    // --- node creation ---

    private RecipeNode newRecipeNode(ItemNode parent, RecipeView view) {
        RecipeNode recipe = new RecipeNode(view, nextId++, parent);
        totalNodes++;
        recipe.setRoundTripEfficiency(reversals.roundTripEfficiency(parent.goal(), view));
        List<IngredientNode> ingredients = new ArrayList<>(view.inputs().size());
        for (IngredientView input : view.inputs()) {
            ingredients.add(newIngredientNode(recipe, input));
        }
        recipe.setIngredients(ingredients);
        recipe.setState(State.EXPANDED);
        return recipe;
    }

    private IngredientNode newIngredientNode(RecipeNode parent, IngredientView input) {
        IngredientNode ingredient = new IngredientNode(input, nextId++, parent);
        totalNodes++;
        if (input.isEmpty()) {
            // Blank grid slot (Ingredient.EMPTY): a disabled placeholder with
            // no candidates. The placeholder is load-bearing for the
            // client→server candidate→slot mapping: the index of the
            // ingredients() list stays aligned with the index of the
            // recipe's inputs() list. TreeSelection.planRows walks
            // ingredients() in order (skipping empty-candidate nodes) so a
            // plan row's candidate list holds the non-blank slots in slot
            // order, and PrintPlan.validate zips that list against the
            // recipe's inputs() in order, skipping blank slots. Skipping
            // the node would shift every later slot's index.
            ingredient.setCandidates(List.of());
            ingredient.setState(State.EXPANDED);
            return ingredient;
        }
        if (input.candidates().isEmpty()) {
            // A non-blank ingredient with no resolvable candidates: a disabled
            // placeholder with no candidates, matching the blank-slot shape.
            ingredient.setCandidates(List.of());
            ingredient.setState(State.EXPANDED);
            return ingredient;
        }
        // First-candidate cycle check (DESIGN.md §8.2): if the first possible
        // input reappears on the ancestor path, the branch is a cycle. The
        // first candidate is marked CYCLE and remains selectable as a raw
        // input. For a multi-candidate (tag) ingredient the other candidates
        // are not offered, because AE2 would refuse the pattern in this
        // context; a single-candidate ingredient keeps its existing shape.
        Item first = input.candidates().get(0);
        if (isCycle(first, ingredient.parent())) {
            ItemNode firstNode = newItemNode(AEItemKey.of(first), ingredient);
            firstNode.setState(State.CYCLE);
            ingredient.setCandidates(List.of(firstNode));
            ingredient.setSelectedCandidate(0);
            if (input.candidates().size() > 1) {
                ingredient.setState(State.CYCLE);
            } else {
                ingredient.setCapped(false);
                ingredient.setState(State.EXPANDED);
            }
            return ingredient;
        }
        int kept = Math.min(input.candidates().size(), limits.maxCandidatesPerIngredient());
        List<ItemNode> candidates = new ArrayList<>(kept);
        for (int i = 0; i < kept; i++) {
            candidates.add(newItemNode(AEItemKey.of(input.candidates().get(i)), ingredient));
        }
        ingredient.setCandidates(candidates);
        ingredient.setCapped(input.candidates().size() > kept);
        ingredient.setSelectedCandidate(0);
        ingredient.setState(State.EXPANDED);
        return ingredient;
    }

    private ItemNode newItemNode(AEKey goal, @Nullable TreeNode parent) {
        ItemNode node = new ItemNode(goal, nextId++, parent);
        totalNodes++;
        int depth = parent == null ? 0 : depthOf(node);
        node.setCraftability(oracle.check(goal, limits.maxDepth() - depth));
        if (depth >= limits.maxDepth()) {
            node.setState(State.CAPPED);
        } else if (index.recipesFor(goal).isEmpty()) {
            node.setLeaf(true);
            node.setState(State.EXPANDED);
        } else {
            node.setState(State.UNEXPANDED);
        }
        return node;
    }

    // --- structural queries ---

    /** Depth counts item nodes only (DESIGN.md §7.2); the root is depth 0. */
    private static int depthOf(TreeNode node) {
        int depth = 0;
        for (TreeNode p = node.parent(); p != null; p = p.parent()) {
            if (p instanceof ItemNode) {
                depth++;
            }
        }
        return depth;
    }

    /** The strict ancestor goals, nearest first. */
    private static List<AEKey> ancestorGoals(TreeNode node) {
        List<AEKey> chain = new ArrayList<>();
        for (TreeNode p = node.parent(); p != null; p = p.parent()) {
            if (p instanceof ItemNode item) {
                chain.add(item.goal());
            }
        }
        return chain;
    }

    /**
     * The strict ancestor goals excluding the root, nearest first. The root
     * goal is the item being requested; a recipe that inputs it is a benign
     * cycle handled at the ingredient level (a CYCLE candidate selectable as
     * a raw input), not a refusal. An intermediate ancestor, however, is a
     * refusal (DESIGN.md §8.2/§17.4).
     */
    private static List<AEKey> ancestorGoalsExcludingRoot(TreeNode node) {
        List<AEKey> chain = ancestorGoals(node);
        if (!chain.isEmpty()) {
            chain.remove(chain.size() - 1);
        }
        return chain;
    }

    /**
     * The AE2 {@code notRecursive} output half (DESIGN.md §17.4): a pattern
     * whose output is an ancestor goal is refused, because crafting it would
     * produce an item already being crafted above.
     */
    private static boolean producesAncestor(RecipeView view, List<AEKey> ancestors) {
        if (ancestors.isEmpty()) {
            return false;
        }
        for (GenericStack output : view.outputs()) {
            if (!(output.what() instanceof AEItemKey outKey)) {
                continue;
            }
            Item item = outKey.getItem();
            for (AEKey ancestor : ancestors) {
                if (ancestor instanceof AEItemKey ak && ak.getItem() == item) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The AE2 {@code notRecursive} input half (DESIGN.md §17.4): a pattern
     * whose first possible input is an ancestor goal is refused, because
     * crafting it would consume an item already being crafted above.
     * Compared against the first candidate only, matching AE2's
     * {@code getPossibleInputs()[0]} indexing (DESIGN.md §8.2).
     */
    private static boolean inputsAncestor(RecipeView view, List<AEKey> ancestors) {
        if (ancestors.isEmpty()) {
            return false;
        }
        for (IngredientView input : view.inputs()) {
            if (input.isEmpty() || input.candidates().isEmpty()) {
                continue;
            }
            Item first = input.candidates().get(0);
            for (AEKey ancestor : ancestors) {
                if (ancestor instanceof AEItemKey ak && ak.getItem() == first) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The AE2 {@code notRecursive} input half at the ingredient level: the
     * first possible input reappears on the ancestor path (including the
     * item being expanded and the root), so the branch is a cycle. The
     * first candidate node is created, marked CYCLE and left unexpanded; it
     * remains selectable as a raw input. Only the first candidate is tested,
     * matching AE2's {@code getPossibleInputs()[0]} indexing (DESIGN.md
     * §8.2): a non-first candidate that is an ancestor is still offered,
     * because AE2 would allow the pattern.
     */
    private static boolean isCycle(Item item, @Nullable TreeNode parent) {
        for (TreeNode p = parent; p != null; p = p.parent()) {
            if (p instanceof ItemNode ancestor && ancestor.goal() instanceof AEItemKey ak && ak.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    /**
     * The result of a detached expansion (DESIGN.md §9): the live node
     * plus a detached twin holding the built subtree. The twin's children
     * are parent-linked to the live node, so {@link #publish(Expansion)}
     * attaches the subtree with a single flip of the live node's own
     * fields.
     */
    public static final class Expansion {
        private final ItemNode live;
        private final ItemNode twin;

        private Expansion(ItemNode live, ItemNode twin) {
            this.live = live;
            this.twin = twin;
        }

        /** The live node the subtree attaches to; its identity is preserved. */
        public ItemNode node() {
            return live;
        }

        /** The detached twin holding the built subtree, until published. */
        public ItemNode twin() {
            return twin;
        }
    }

    /** A single mutable node budget for one user action (DESIGN.md §8.3). */
    private static final class ExpansionBudget {
        int remaining;

        ExpansionBudget(int remaining) {
            this.remaining = remaining;
        }
    }
}
