package dev.cryolithic.rpp.tree;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.cryolithic.rpp.RppConfig;
import dev.cryolithic.rpp.recipe.IngredientView;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import static dev.cryolithic.rpp.tree.Tier.ALTERNATE;
import static dev.cryolithic.rpp.tree.Tier.PRIMARY;
import static dev.cryolithic.rpp.tree.Tier.REJECTED;

/**
 * Source tiering and default selection (DESIGN.md §8.4, §8.5). One instance
 * per tree session, like the builder. Ranks the candidates, assigns a tier
 * and a destination to each, and computes the default checkbox state.
 *
 * <p>The ranking is lexicographic over the §8.4.4 weights: sticky source
 * set, goal-namespace match, preferred-mods order, vanilla (no machine),
 * shallower oracle input depth, fewer distinct inputs, then recipe id. The
 * tier is REJECTED for the five junk reasons, ALTERNATE for the three
 * promotions (lossless reversal, higher yield, preferred namespace), and
 * PRIMARY for the best-ranked ordinary production recipe. Reversals never
 * become PRIMARY.</p>
 */
public final class SourceSelector {
    /** The tiering knobs, all config-backed. Tests pass values explicitly. */
    public record SelectionConfig(
            double minRoundTripEfficiency,
            boolean alternatesConservative,
            int maxSourcesPerItem,
            int maxSourcesPerDestination,
            boolean warnOnCostlyCollisions,
            List<String> preferredMods) {
        /** §12 defaults: 0.95 / false / 3 / 1 / true / [minecraft, ae2]. */
        public static final SelectionConfig DEFAULTS =
                new SelectionConfig(0.95, false, 3, 1, true, List.of("minecraft", "ae2"));

        /** Read the knobs from {@code rpp-client.toml} (main code only). */
        public static SelectionConfig fromConfig() {
            return new SelectionConfig(
                    RppConfig.minRoundTripEfficiency(),
                    RppConfig.alternatesConservative(),
                    RppConfig.maxSourcesPerItem(),
                    RppConfig.maxSourcesPerDestination(),
                    RppConfig.warnOnCostlyCollisions(),
                    List.copyOf(RppConfig.preferredMods()));
        }
    }

    private final RecipeIndex index;
    private final TreeLimits limits;
    private final SelectionConfig config;
    private final CraftabilityOracle oracle;
    private final @Nullable StickyChoices sticky;

    public SourceSelector(RecipeIndex index, TreeLimits limits, SelectionConfig config,
            @Nullable StickyChoices sticky) {
        this.index = Objects.requireNonNull(index);
        this.limits = Objects.requireNonNull(limits);
        this.config = Objects.requireNonNull(config);
        this.oracle = new CraftabilityOracle(index);
        this.sticky = sticky;
    }

    /** Rank the candidates for the node, best first. Used before truncation. */
    public List<RecipeView> rank(ItemNode node, List<RecipeView> candidates) {
        List<RecipeView> ranked = new ArrayList<>(candidates);
        ranked.sort(comparator(node));
        return ranked;
    }

    /**
     * Assign tier, destination, score, oracle depth, and rejection reason to
     * the node's recipes, then compute the default selection and the forced
     * flag. Called by the builder after the RecipeNodes are created.
     */
    public void assign(ItemNode node) {
        List<RecipeNode> recipes = node.recipes();
        if (recipes == null) {
            return;
        }
        int budget = limits.maxDepth() - depthOf(node);
        for (RecipeNode r : recipes) {
            r.setDestination(destination(r.recipe()));
            r.setOracleDepth(Math.max(0, inputDepth(node, r.recipe(), budget)));
            r.setScore(scoreOf(node, r, budget));
        }
        assignTiers(node, recipes, budget);
        applyDefaults(node);
    }

    /**
     * Re-apply the default selection to an already-built node, restoring
     * tiering exactly. Used by "reset to defaults".
     */
    public void applyDefaults(ItemNode node) {
        node.selected().clear();
        List<RecipeNode> recipes = node.recipes();
        if (recipes == null) {
            return;
        }

        // 1. Sticky restore: the player's remembered choice wins.
        SourceSet set = stickySet(node.goal());
        if (set != null) {
            if (set.recipeIds().isEmpty()) {
                // a remembered raw-input decision: the player deselected every
                // recipe for this item, so keep it deselected and mark it raw
                node.setForced(false);
                node.setRawInput(true);
                for (RecipeNode r : recipes) {
                    r.setStickyRestored(false);
                }
                TreeSelection.refreshCollisions(node, config.warnOnCostlyCollisions());
                return;
            }
            boolean any = false;
            for (int i = 0; i < recipes.size(); i++) {
                RecipeNode r = recipes.get(i);
                if (set.recipeIds().contains(r.recipe().id())) {
                    node.select(i);
                    r.setStickyRestored(true);
                    any = true;
                } else {
                    r.setStickyRestored(false);
                }
            }
            node.setForced(false);
            if (any) {
                TreeSelection.refreshCollisions(node, config.warnOnCostlyCollisions());
                return;
            }
            // every remembered id is stale: fall through to tiering
        }

        // 2. Forced: exactly one non-REJECTED candidate.
        int nonRejected = 0;
        int nonRejectedIndex = -1;
        for (int i = 0; i < recipes.size(); i++) {
            if (recipes.get(i).tier() != REJECTED) {
                nonRejected++;
                nonRejectedIndex = i;
            }
        }
        if (nonRejected == 1) {
            node.setForced(true);
            node.select(nonRejectedIndex);
            TreeSelection.refreshCollisions(node, config.warnOnCostlyCollisions());
            return;
        }
        node.setForced(false);

        // 3. Tiering defaults with the destination-class and per-item caps.
        int checked = 0;
        Map<Destination, Integer> perDestination = new HashMap<>();
        for (int i = 0; i < recipes.size(); i++) {
            RecipeNode r = recipes.get(i);
            if (r.tier() == REJECTED) {
                continue;
            }
            if (r.tier() == PRIMARY) {
                node.select(i);
                checked++;
                perDestination.merge(r.destination(), 1, Integer::sum);
                continue;
            }
            if (checked >= config.maxSourcesPerItem()) {
                continue;
            }
            int inDestination = perDestination.getOrDefault(r.destination(), 0);
            if (inDestination >= config.maxSourcesPerDestination()) {
                continue;
            }
            node.select(i);
            checked++;
            perDestination.merge(r.destination(), 1, Integer::sum);
        }
        TreeSelection.refreshCollisions(node, config.warnOnCostlyCollisions());
    }

    /** The destination a printed pattern physically goes to (DESIGN.md §17.7.2). */
    public static Destination destination(RecipeView view) {
        String type = view.type().toString();
        // The type location may or may not carry its namespace (vanilla
        // crafting recipes report "crafting" in this mapping), so match on
        // the path segment.
        String path = type.lastIndexOf(':') >= 0 ? type.substring(type.lastIndexOf(':') + 1) : type;
        if ("crafting".equals(path)) {
            return Destination.assembler();
        }
        if ("stonecutting".equals(path)) {
            return Destination.stonecutter();
        }
        if ("smithing".equals(path)) {
            return Destination.smithing();
        }
        return Destination.machine(type);
    }

    // --- tiering ---

    private void assignTiers(ItemNode node, List<RecipeNode> recipes, int budget) {
        // First pass: rejection reasons and reversal classification.
        for (RecipeNode r : recipes) {
            double efficiency = r.roundTripEfficiency();
            boolean reversal = !Double.isNaN(efficiency);
            boolean lossless = reversal && efficiency >= config.minRoundTripEfficiency();

            String reason = null;
            if (reversal && !lossless) {
                reason = "recycling: returns " + (int) Math.round(efficiency * 100) + "% of material cost";
            } else if (isByproduct(node, r)) {
                reason = "produced as a side product";
            } else if (!r.recipe().trusted()) {
                reason = "inputs may be incomplete";
            } else if (isSelfReferential(node, r)) {
                reason = "consumes the item it produces";
            } else if (isDeadEndInput(r, budget)) {
                reason = "requires an item with no source";
            }

            if (reason != null) {
                r.setTier(REJECTED);
                r.setRejectionReason(reason);
            } else if (reversal) {
                // lossless reversal: promoted to ALTERNATE, never PRIMARY
                r.setTier(ALTERNATE);
                r.setRejectionReason(null);
            }
            // else: an ordinary production recipe; tiered in the second pass
        }

        // The primary is the first non-reversal, non-rejected in rank order.
        RecipeNode primary = null;
        for (RecipeNode r : recipes) {
            if (r.tier() == REJECTED || !Double.isNaN(r.roundTripEfficiency())) {
                continue;
            }
            primary = r;
            break;
        }

        // Second pass: PRIMARY / ALTERNATE for the ordinary production recipes.
        for (RecipeNode r : recipes) {
            if (r.tier() == REJECTED || !Double.isNaN(r.roundTripEfficiency())) {
                continue;
            }
            if (r == primary) {
                r.setTier(PRIMARY);
            } else if (yieldAbovePrimary(r, primary) || inPreferredNamespace(r)) {
                r.setTier(ALTERNATE);
            } else if (config.alternatesConservative()) {
                r.setTier(REJECTED);
                r.setRejectionReason("conservative: not a promoted path");
            } else {
                r.setTier(ALTERNATE);
            }
        }
    }

    private boolean isByproduct(ItemNode node, RecipeNode r) {
        if (!(node.goal() instanceof AEItemKey goalKey)) {
            return false;
        }
        Item goalItem = goalKey.getItem();
        if (r.recipe().outputs().isEmpty()) {
            return false;
        }
        return !(r.recipe().outputs().get(0).what() instanceof AEItemKey outKey)
                || outKey.getItem() != goalItem;
    }

    private boolean isSelfReferential(ItemNode node, RecipeNode r) {
        if (!(node.goal() instanceof AEItemKey goalKey)) {
            return false;
        }
        Item goalItem = goalKey.getItem();
        for (IngredientView input : r.recipe().inputs()) {
            for (Item candidate : input.candidates()) {
                if (candidate == goalItem) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDeadEndInput(RecipeNode r, int budget) {
        for (IngredientView input : r.recipe().inputs()) {
            if (input.candidates().isEmpty()) {
                return true; // no candidate at all: cannot verify
            }
            Item candidate = input.candidates().get(0);
            if (oracle.check(AEItemKey.of(candidate), budget) == Craftability.UNKNOWN) {
                return true; // no source within budget; LEAF (raw input) is fine
            }
        }
        return false;
    }

    private boolean yieldAbovePrimary(RecipeNode r, @Nullable RecipeNode primary) {
        if (primary == null || !r.recipe().trusted()) {
            return false;
        }
        return yieldPerInput(r) > yieldPerInput(primary);
    }

    private static double yieldPerInput(RecipeNode r) {
        long output = r.recipe().outputs().isEmpty() ? 0 : r.recipe().outputs().get(0).amount();
        int inputs = Math.max(1, r.recipe().inputs().size());
        return (double) output / inputs;
    }

    private boolean inPreferredNamespace(RecipeNode r) {
        return config.preferredMods().contains(r.recipe().id().getNamespace());
    }

    // --- ranking ---

    private Comparator<RecipeView> comparator(ItemNode node) {
        Set<ResourceLocation> stickyIds = stickyIds(node.goal());
        String goalNamespace = namespaceOf(node.goal());
        int budget = limits.maxDepth() - depthOf(node);
        return (a, b) -> {
            boolean aSticky = stickyIds.contains(a.id());
            boolean bSticky = stickyIds.contains(b.id());
            if (aSticky != bSticky) {
                return aSticky ? -1 : 1;
            }
            // preferredMods overrides namespace match when configured (DESIGN.md §12).
            int aPreferred = preferredRank(a.id().getNamespace());
            int bPreferred = preferredRank(b.id().getNamespace());
            if (aPreferred != bPreferred) {
                return Integer.compare(aPreferred, bPreferred);
            }
            if (goalNamespace != null) {
                boolean aNamespace = a.id().getNamespace().equals(goalNamespace);
                boolean bNamespace = b.id().getNamespace().equals(goalNamespace);
                if (aNamespace != bNamespace) {
                    return aNamespace ? -1 : 1;
                }
            }
            boolean aVanilla = isVanilla(a.type());
            boolean bVanilla = isVanilla(b.type());
            if (aVanilla != bVanilla) {
                return aVanilla ? -1 : 1;
            }
            int aDepth = inputDepth(node, a, budget);
            int bDepth = inputDepth(node, b, budget);
            if (aDepth != bDepth) {
                return Integer.compare(aDepth, bDepth);
            }
            int aInputs = distinctInputs(a);
            int bInputs = distinctInputs(b);
            if (aInputs != bInputs) {
                return Integer.compare(aInputs, bInputs);
            }
            return a.id().toString().compareTo(b.id().toString());
        };
    }

    private int preferredRank(String namespace) {
        List<String> mods = config.preferredMods();
        for (int i = 0; i < mods.size(); i++) {
            if (mods.get(i).equals(namespace)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static boolean isVanilla(net.minecraft.world.item.crafting.RecipeType<?> type) {
        String t = type.toString();
        int colon = t.lastIndexOf(':');
        // A bare path (no namespace) is a vanilla built-in type in this
        // mapping (e.g. "crafting"); otherwise the namespace must be minecraft.
        return colon < 0 || t.substring(0, colon).equals("minecraft");
    }

    private int inputDepth(ItemNode node, RecipeView view, int budget) {
        int max = 0;
        for (IngredientView input : view.inputs()) {
            if (input.candidates().isEmpty()) {
                continue;
            }
            Item candidate = input.candidates().get(0);
            max = Math.max(max, Math.max(0, oracle.depth(AEItemKey.of(candidate), budget)));
        }
        return max;
    }

    private static int distinctInputs(RecipeView view) {
        Set<Item> items = new HashSet<>();
        for (IngredientView input : view.inputs()) {
            items.addAll(input.candidates());
        }
        return items.size();
    }

    private double scoreOf(ItemNode node, RecipeNode r, int budget) {
        double score = 0;
        if (stickyIds(node.goal()).contains(r.recipe().id())) {
            score += 1000;
        }
        String goalNamespace = namespaceOf(node.goal());
        if (goalNamespace != null && r.recipe().id().getNamespace().equals(goalNamespace)) {
            score += 100;
        }
        int preferred = preferredRank(r.recipe().id().getNamespace());
        if (preferred != Integer.MAX_VALUE) {
            score += 50 - preferred;
        }
        if (isVanilla(r.recipe().type())) {
            score += 25;
        }
        score += Math.max(0, 10 - inputDepth(node, r.recipe(), budget));
        score += Math.max(0, 5 - distinctInputs(r.recipe()));
        return score;
    }

    // --- helpers ---

    private @Nullable SourceSet stickySet(AEKey goal) {
        if (sticky == null) {
            return null;
        }
        ResourceLocation id = itemLocation(goal);
        return id == null ? null : sticky.get(id);
    }

    private Set<ResourceLocation> stickyIds(AEKey goal) {
        SourceSet set = stickySet(goal);
        return set == null ? Set.of() : set.recipeIds();
    }

    private static @Nullable String namespaceOf(AEKey goal) {
        ResourceLocation id = itemLocation(goal);
        return id == null ? null : id.getNamespace();
    }

    private static @Nullable ResourceLocation itemLocation(AEKey goal) {
        if (!(goal instanceof AEItemKey goalKey)) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(goalKey.getItem());
    }

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
}
