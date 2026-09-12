package dev.cryolithic.rpp.tree;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.cryolithic.rpp.recipe.IngredientView;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.item.Item;

/**
 * A bounded search that allocates no tree nodes (DESIGN.md §8.6). It answers
 * one question per item: is it craftable unambiguously, ambiguously, not at
 * all, or unknown? The builder asks it for every item node it creates; the
 * GUI asks it for every row it renders.
 *
 * <p>Memoized per (goal, depth budget) pair, keyed per index snapshot. The
 * memo is only consulted at the top level, where the search path is always
 * empty, so the key is sound. Cycle-guarded the same way as DESIGN.md §8.2:
 * a goal that reappears on the search path bottoms out as a raw input, like
 * a leaf. {@code UNKNOWN} on budget exhaustion, and UNKNOWN is never treated
 * as collapsible.</p>
 */
public final class CraftabilityOracle {
    private record Result(Craftability verdict, int depth) {}

    private final RecipeIndex index;
    private final Map<AEKey, Map<Integer, Result>> memo = new HashMap<>();

    public CraftabilityOracle(RecipeIndex index) {
        this.index = Objects.requireNonNull(index);
    }

    /**
     * The verdict for the goal within the depth budget. The budget is the
     * number of recipe levels the search may descend; each level costs one.
     * A goal with no recipe is a LEAF regardless of budget; a goal with
     * recipes and no budget left is UNKNOWN, never LEAF.
     */
    public Craftability check(AEKey goal, int depthBudget) {
        return searchResult(goal, depthBudget).verdict();
    }

    /**
     * The number of recipe levels the search descended to bottom out. -1 when
     * the verdict is {@code UNKNOWN} (no bottom-out was found within budget).
     * The same search as {@link #check}, carrying the depth it already found.
     */
    public int depth(AEKey goal, int depthBudget) {
        Result result = searchResult(goal, depthBudget);
        return result.verdict() == Craftability.UNKNOWN ? -1 : result.depth();
    }

    private Result searchResult(AEKey goal, int depthBudget) {
        Map<Integer, Result> byBudget = memo.get(goal);
        if (byBudget != null) {
            Result cached = byBudget.get(depthBudget);
            if (cached != null) {
                return cached;
            }
        }
        Result result = search(goal, depthBudget, new ArrayList<>(), 0);
        memo.computeIfAbsent(goal, k -> new HashMap<>()).put(depthBudget, result);
        return result;
    }

    private Result search(AEKey goal, int budget, List<AEKey> path, int depth) {
        if (onPath(goal, path)) {
            return new Result(Craftability.LEAF, depth); // cycle: bottoms out as a raw input
        }
        List<RecipeView> recipes = index.recipesFor(goal);
        if (recipes.isEmpty()) {
            return new Result(Craftability.LEAF, depth);
        }
        if (budget <= 0) {
            return new Result(Craftability.UNKNOWN, -1);
        }
        if (recipes.size() > 1) {
            return new Result(Craftability.CRAFTABLE_AMBIGUOUS, depth);
        }
        RecipeView recipe = recipes.get(0);
        if (producesAncestor(recipe, path)) {
            return new Result(Craftability.LEAF, depth); // byproduct cycle: the only path loops
        }
        int deepest = depth;
        for (IngredientView input : recipe.inputs()) {
            if (input.candidates().isEmpty()) {
                return new Result(Craftability.UNKNOWN, -1); // dead end: cannot verify
            }
            if (input.candidates().size() > 1) {
                return new Result(Craftability.CRAFTABLE_AMBIGUOUS, depth);
            }
            Item candidate = input.candidates().get(0);
            List<AEKey> extended = new ArrayList<>(path.size() + 1);
            extended.addAll(path);
            extended.add(goal);
            Result sub = search(AEItemKey.of(candidate), budget - 1, extended, depth + 1);
            if (sub.verdict() == Craftability.UNKNOWN) {
                return new Result(Craftability.UNKNOWN, -1);
            }
            if (sub.verdict() == Craftability.CRAFTABLE_AMBIGUOUS) {
                return new Result(Craftability.CRAFTABLE_AMBIGUOUS, sub.depth());
            }
            deepest = Math.max(deepest, sub.depth());
        }
        return new Result(Craftability.CRAFTABLE_UNAMBIGUOUS, deepest);
    }

    private static boolean onPath(AEKey goal, List<AEKey> path) {
        if (!(goal instanceof AEItemKey goalKey)) {
            return false;
        }
        Item item = goalKey.getItem();
        for (AEKey p : path) {
            if (p instanceof AEItemKey pk && pk.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    private static boolean producesAncestor(RecipeView recipe, List<AEKey> path) {
        if (path.isEmpty()) {
            return false;
        }
        for (GenericStack output : recipe.outputs()) {
            if (!(output.what() instanceof AEItemKey outKey)) {
                continue;
            }
            Item item = outKey.getItem();
            for (AEKey p : path) {
                if (p instanceof AEItemKey pk && pk.getItem() == item) {
                    return true;
                }
            }
        }
        return false;
    }
}
