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
 * on demand via {@link #expand(TreeNode)}.
 *
 * <p>Depth counts item nodes only (DESIGN.md §7.2): the root is depth 0 and
 * {@code ItemNode -> RecipeNode -> IngredientNode -> ItemNode} increments by
 * one, so the depth cap means "how many crafting steps deep". An item node
 * at depth {@code maxDepth} is CAPPED, not expanded.</p>
 *
 * <p>Cycle detection mirrors AE2's {@code CraftingTreeNode.notRecursive}
 * (DESIGN.md §8.2/§17.4). A candidate recipe is refused when an ancestor
 * goal appears among its outputs (AE2 would silently refuse it); an
 * ingredient whose item reappears on the ancestor path becomes a CYCLE node,
 * terminal but still selectable as a raw input.</p>
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
     * {@link #expand(TreeNode)}.
     */
    public ItemNode buildRoot(AEKey goal, int initialDepth) {
        ItemNode root = newItemNode(goal, null);
        expandInitial(root, 0, initialDepth);
        return root;
    }

    /** Expands one node on demand. No-op when the node is not UNEXPANDED. */
    public void expand(TreeNode node) {
        if (node instanceof ItemNode item) {
            expandItem(item);
        } else if (node instanceof RecipeNode recipe) {
            expandRecipe(recipe);
        } else if (node instanceof IngredientNode ingredient) {
            expandIngredient(ingredient);
        }
    }

    /** The number of recipes beyond the kept ones, for the "+K more" marker row. */
    public int moreCount(ItemNode node) {
        List<RecipeNode> recipes = node.recipes();
        if (recipes == null) {
            return 0;
        }
        int total = index.recipesFor(node.goal()).size();
        return Math.max(0, total - recipes.size());
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
        List<RecipeView> filtered = new ArrayList<>();
        for (RecipeView view : candidates) {
            if (producesAncestor(view, ancestors)) {
                continue; // AE2 would refuse this pattern in this context
            }
            filtered.add(view);
        }
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
        if (nodeCount > limits.maxNodesPerExpansion()
                || totalNodes + nodeCount > limits.maxTotalNodes()) {
            node.setState(State.CAPPED);
            return;
        }

        List<RecipeNode> recipes = new ArrayList<>(kept.size());
        for (RecipeView view : kept) {
            recipes.add(newRecipeNode(node, view));
        }
        node.setRecipes(recipes);
        selector.assign(node);
        node.setState(State.EXPANDED);
        if (TreeSelection.isForced(node) && node.craftability() == Craftability.CRAFTABLE_UNAMBIGUOUS) {
            autoCollapse(node);
        }
    }

    /**
     * Oracle-driven auto-collapse (DESIGN.md §8.5): the node is forced and
     * the oracle has proven its goal has exactly one way to bottom out
     * within budget, so the whole subtree is a forced bottleneck.
     * Materialize it, and if every descendant is forced, a leaf, or a cycle
     * (and none is untrusted, capped, or {@code UNKNOWN}), collapse it into
     * a summary row. A subtree with a real choice stays open.
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
                    expand(candidate);
                }
            }
        }
        if (TreeSelection.isCollapsible(node)) {
            node.setState(State.COLLAPSED);
        }
    }

    private void expandRecipe(RecipeNode node) {
        // Ingredients are materialized with the parent item node; nothing to do.
        if (node.state() == State.UNEXPANDED && node.ingredients() != null) {
            node.setState(State.EXPANDED);
        }
    }

    private void expandIngredient(IngredientNode node) {
        // Candidates are materialized with the parent item node; nothing to do.
        if (node.state() == State.UNEXPANDED && node.candidates() != null) {
            node.setState(State.EXPANDED);
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
        if (isCycle(node)) {
            node.setState(State.CYCLE);
        } else if (depth >= limits.maxDepth()) {
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
     * The AE2 {@code notRecursive} input half: the item reappears on the
     * ancestor path, so the branch is a cycle. The node is created, marked
     * CYCLE and left unexpanded; it remains selectable as a raw input.
     */
    private static boolean isCycle(ItemNode node) {
        if (!(node.goal() instanceof AEItemKey goalKey)) {
            return false;
        }
        Item item = goalKey.getItem();
        for (TreeNode p = node.parent(); p != null; p = p.parent()) {
            if (p instanceof ItemNode ancestor && ancestor.goal() instanceof AEItemKey ak && ak.getItem() == item) {
                return true;
            }
        }
        return false;
    }
}
