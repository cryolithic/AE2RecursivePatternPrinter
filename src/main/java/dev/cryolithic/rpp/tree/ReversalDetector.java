package dev.cryolithic.rpp.tree;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.cryolithic.rpp.recipe.IngredientView;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * One-level round-trip detection (DESIGN.md §8.4.2). For a candidate
 * {@code R: I -> n x G} whose input {@code I} is itself producible from the
 * goal by some {@code R': m x G + extras -> k x I}, the round-trip
 * efficiency is {@code (n / k) / (m / 1)}: goal-units out per goal-unit in.
 * At or above {@code minRoundTripEfficiency} the reversal is a lossless
 * storage form; below it, recycling.
 *
 * <p>Detection is one level deep and memoized per (goal, input) pair. The
 * first forward recipe in index order that consumes the goal and produces
 * the input wins, which keeps the result deterministic.</p>
 *
 * <p>Tag lookups go through {@link TagLookup} so the detector runs in a bare
 * JVM: the mod wires the real tag registry, tests stub it.</p>
 */
public final class ReversalDetector {
    /** The storage roles of the reciprocal tag family (DESIGN.md §8.4.2). */
    private static final List<String> STORAGE_ROLES = List.of("storage_blocks", "ingots", "nuggets");

    /** Tag lookup seam; the real code implements it with the tag registry, tests stub it. */
    public interface TagLookup {
        /** All tags the item is in. */
        Collection<ResourceLocation> tagsFor(Item item);
    }

    private record Key(AEKey goal, Item input) {}

    private record Reversal(double efficiency, boolean extraInputs) {}

    private record ParsedTag(String role, String param) {}

    private final RecipeIndex index;
    private final TagLookup tags;
    private final Map<Key, Reversal> memo = new HashMap<>();

    public ReversalDetector(RecipeIndex index, TagLookup tags) {
        this.index = Objects.requireNonNull(index);
        this.tags = Objects.requireNonNull(tags);
    }

    /**
     * The round-trip efficiency of the candidate as a reversal of the goal,
     * or NaN when the candidate is not a one-level reversal. A candidate
     * qualifies when it has exactly one input slot and that input is
     * producible from the goal (consuming the goal, producing the input).
     */
    public double roundTripEfficiency(AEKey goal, RecipeView candidate) {
        if (!(goal instanceof AEItemKey goalKey)) {
            return Double.NaN;
        }
        Item goalItem = goalKey.getItem();
        if (candidate.inputs().size() != 1 || candidate.outputs().isEmpty()) {
            return Double.NaN;
        }
        IngredientView input = candidate.inputs().get(0);
        if (input.candidates().isEmpty()) {
            return Double.NaN;
        }
        Item inputItem = input.candidates().get(0);

        Key key = new Key(goal, inputItem);
        Reversal cached = memo.get(key);
        if (cached == null) {
            cached = compute(goalItem, inputItem, candidate);
            memo.put(key, cached);
        }
        return cached.efficiency();
    }

    /**
     * True when the reversal's forward recipe consumes inputs other than the
     * goal (DESIGN.md §8.4.2: caps the tier at ALTERNATE and strengthens a
     * below-threshold REJECTED). False when the candidate is not a reversal.
     */
    public boolean consumesExtraInputs(AEKey goal, RecipeView candidate) {
        if (!(goal instanceof AEItemKey goalKey)) {
            return false;
        }
        Item goalItem = goalKey.getItem();
        if (candidate.inputs().size() != 1 || candidate.outputs().isEmpty()) {
            return false;
        }
        IngredientView input = candidate.inputs().get(0);
        if (input.candidates().isEmpty()) {
            return false;
        }
        Item inputItem = input.candidates().get(0);

        Key key = new Key(goal, inputItem);
        Reversal cached = memo.get(key);
        if (cached == null) {
            cached = compute(goalItem, inputItem, candidate);
            memo.put(key, cached);
        }
        return !Double.isNaN(cached.efficiency()) && cached.extraInputs();
    }

    private Reversal compute(Item goalItem, Item inputItem, RecipeView candidate) {
        // Storage-tag fast path: reciprocal c:storage_blocks/x <-> c:ingots/x
        // <-> c:nuggets/x pairs are lossless without computing the ratio.
        if (storageTagLossless(inputItem, goalItem)) {
            return new Reversal(1.0, false);
        }
        long n = candidate.outputs().get(0).amount();
        for (RecipeView forward : index.recipesFor(AEItemKey.of(inputItem))) {
            if (forward.outputs().isEmpty()) {
                continue;
            }
            GenericStack out = forward.outputs().get(0);
            if (!(out.what() instanceof AEItemKey outKey) || outKey.getItem() != inputItem) {
                continue;
            }
            long k = out.amount();
            long goalUnits = 0;
            boolean extras = false;
            for (IngredientView fin : forward.inputs()) {
                if (fin.candidates().isEmpty()) {
                    extras = true;
                    continue;
                }
                if (fin.candidates().get(0) == goalItem) {
                    goalUnits++;
                } else {
                    extras = true;
                }
            }
            if (goalUnits == 0) {
                continue;
            }
            return new Reversal((double) n / ((double) k * (double) goalUnits), extras);
        }
        return new Reversal(Double.NaN, false);
    }

    private boolean storageTagLossless(Item input, Item goal) {
        List<ResourceLocation> goalTags = List.copyOf(tags.tagsFor(goal));
        for (ResourceLocation tag : tags.tagsFor(input)) {
            ParsedTag parsed = parse(tag);
            if (parsed == null) {
                continue;
            }
            for (String other : STORAGE_ROLES) {
                if (other.equals(parsed.role())) {
                    continue;
                }
                if (goalTags.contains(ResourceLocation.fromNamespaceAndPath("c", other + "/" + parsed.param()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ParsedTag parse(ResourceLocation tag) {
        if (!"c".equals(tag.getNamespace())) {
            return null;
        }
        String path = tag.getPath();
        int slash = path.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        String role = path.substring(0, slash);
        if (!STORAGE_ROLES.contains(role)) {
            return null;
        }
        return new ParsedTag(role, path.substring(slash + 1));
    }
}
