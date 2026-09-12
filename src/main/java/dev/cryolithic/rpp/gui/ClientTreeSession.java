package dev.cryolithic.rpp.gui;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.cryolithic.rpp.RppConfig;
import dev.cryolithic.rpp.inventory.PatternPrinterMenu;
import dev.cryolithic.rpp.print.PlanEntry;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeIndexHolder;
import dev.cryolithic.rpp.tree.IngredientNode;
import dev.cryolithic.rpp.tree.ItemNode;
import dev.cryolithic.rpp.tree.PlanRow;
import dev.cryolithic.rpp.tree.ReversalDetector;
import dev.cryolithic.rpp.tree.RecipeNode;
import dev.cryolithic.rpp.tree.RecipeTreeBuilder;
import dev.cryolithic.rpp.tree.SourceSelector;
import dev.cryolithic.rpp.tree.StickyChoices;
import dev.cryolithic.rpp.tree.TreeNode;
import dev.cryolithic.rpp.tree.TreeLimits;
import dev.cryolithic.rpp.tree.TreeSelection;

import static dev.cryolithic.rpp.tree.TreeNode.State;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One client-side recipe-tree session (DESIGN.md §8.1, §9, §11.1). Created
 * when the GUI opens: it decodes the input slot's encoded pattern to get the
 * root goal, snapshots the client {@link RecipeIndex} (immutable, generation
 * tagged by object identity), and builds the tree lazily.
 *
 * <p>The root is built on a single-thread background executor, as is every
 * later expansion (one task at a time, cancellable). Results are published to
 * the GUI by queueing onto the client main thread; the render thread is never
 * blocked. A stale result — one whose index snapshot no longer matches the
 * current one after a reload — is discarded on publish and the tree rebuilds
 * from the root (DESIGN.md §9).</p>
 *
 * <p>The tree is never persisted; it is derived state, rebuilt from the input
 * slot on open. Selection state, collision flags and the plan are all derived
 * from the built tree via {@link TreeSelection}.</p>
 */
public final class ClientTreeSession {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientTreeSession.class);

    /** The real tag-registry lookup for the reversal detector (DESIGN.md §8.4.2). */
    private static final ReversalDetector.TagLookup TAG_LOOKUP = item -> {
        List<ResourceLocation> tags = new ArrayList<>();
        var holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
        for (var pair : BuiltInRegistries.ITEM.getTags().toList()) {
            if (pair.getSecond().contains(holder)) {
                tags.add(pair.getFirst().location());
            }
        }
        return tags;
    };

    private final AEKey rootGoal;
    private final TreeLimits limits;
    private final StickyChoices sticky;

    /** The index snapshot this session's tree was built against; volatile for cross-thread visibility. */
    private volatile RecipeIndex index;
    private volatile SourceSelector selector;
    private volatile RecipeTreeBuilder builder;
    /** The root item node; null until the background root build publishes. */
    private volatile ItemNode root;
    /** The node currently being expanded on the background thread, for the spinner. */
    private volatile ItemNode expandingNode;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rpp-tree-build");
        thread.setDaemon(true);
        return thread;
    });
    @Nullable
    private volatile Future<?> pendingRoot;
    @Nullable
    private Consumer<ClientTreeSession> onUpdate;

    private ClientTreeSession(AEKey rootGoal, RecipeIndex index, TreeLimits limits, SourceSelector selector,
            RecipeTreeBuilder builder, StickyChoices sticky) {
        this.rootGoal = rootGoal;
        this.index = index;
        this.limits = limits;
        this.selector = selector;
        this.builder = builder;
        this.sticky = sticky;
    }

    /**
     * Decodes the menu's input slot and starts the session. Returns null when
     * the input slot holds no valid encoded pattern or the recipe index is not
     * ready yet. The root build runs on the background executor; the session is
     * not {@link #isRootReady() ready} until it publishes.
     */
    @Nullable
    public static ClientTreeSession create(PatternPrinterMenu menu, Level level) {
        ItemStack input = menu.getSlot(PatternPrinterMenu.SLOT_INPUT).getItem();
        if (!PatternDetailsHelper.isEncodedPattern(input)) {
            return null;
        }
        IPatternDetails details = PatternDetailsHelper.decodePattern(input, level);
        GenericStack primary = details.getPrimaryOutput();
        if (primary == null || !(primary.what() instanceof AEItemKey goal)) {
            return null;
        }
        RecipeIndex index = RecipeIndexHolder.current();
        if (index.recipeCount() == 0) {
            return null; // index not built yet; the GUI will retry on the next open
        }
        TreeLimits limits = TreeLimits.fromConfig();
        StickyChoices sticky = RppConfig.stickyChoices() ? new StickyChoices(StickyPersistence.INSTANCE) : null;
        SourceSelector selector = new SourceSelector(index, limits, SourceSelector.SelectionConfig.fromConfig(), sticky);
        RecipeTreeBuilder builder = new RecipeTreeBuilder(index, limits, TAG_LOOKUP, selector);
        ClientTreeSession session = new ClientTreeSession(goal, index, limits, selector, builder, sticky);
        session.submitRootBuild();
        return session;
    }

    // --- queries ---

    /** True once the root item node has been built and published. */
    public boolean isRootReady() {
        return root != null;
    }

    /** The root item node, or null while the root build is in flight. */
    @Nullable
    public ItemNode root() {
        return root;
    }

    /** The node whose expansion is running on the background thread, or null. */
    @Nullable
    public ItemNode expandingNode() {
        return expandingNode;
    }

    public RecipeTreeBuilder builder() {
        return builder;
    }

    public SourceSelector selector() {
        return selector;
    }

    /**
     * True when a recipe reload has swapped the index snapshot since this
     * session's tree was built. The tree is then invalid and must rebuild from
     * the root (DESIGN.md §9).
     */
    public boolean isStale() {
        return index != RecipeIndexHolder.current();
    }

    /**
     * Rebuilds the tree from the root if a recipe reload has made it stale and
     * no root build is already in flight. Called from the GUI's tick so a
     * reload is picked up even when the user is idle (DESIGN.md §9).
     */
    public void rebuildIfStale() {
        if (isStale() && pendingRoot == null) {
            rebuildRoot();
        }
    }

    /** The number of patterns the current plan will print (raw-input rows excluded). */
    public int patternCount() {
        ItemNode root = this.root;
        if (root == null) {
            return 0;
        }
        int count = 0;
        for (PlanRow row : TreeSelection.planRows(root)) {
            if (!row.rawInput()) {
                count++;
            }
        }
        return count;
    }

    /** The blank patterns available in the menu's blanks slot. */
    public int blanksCount(PatternPrinterMenu menu) {
        return menu.getSlot(PatternPrinterMenu.SLOT_BLANKS).getItem().getCount();
    }

    // --- expansion ---

    /**
     * Requests a background expansion of an UNEXPANDED item node. No-op when
     * the node is already materialized. The build runs on the single-thread
     * executor; the result is published to the main thread and the update
     * listener is notified (or the tree rebuilds from the root if stale).
     */
    public void requestExpand(ItemNode node) {
        if (node.state() != State.UNEXPANDED) {
            return;
        }
        expandingNode = node;
        RecipeIndex snapshot = this.index;
        executor.submit(() -> {
            try {
                builder.expand(node);
            } catch (Exception e) {
                LOGGER.error("rpp tree expansion failed", e);
                node.setState(State.ERROR);
            }
            Minecraft.getInstance().execute(() -> {
                expandingNode = null;
                if (snapshot != this.index) {
                    // A reload swapped the index mid-build; discard and rebuild.
                    rebuildRoot();
                } else {
                    notifyUpdate();
                }
            });
        });
    }

    // --- selection operations (main thread) ---

    /** Toggles one recipe in or out of the printed set; siblings are unaffected. */
    public void toggleRecipe(ItemNode item, int recipeIndex) {
        if (item.recipes() == null || recipeIndex < 0 || recipeIndex >= item.recipes().size()) {
            return;
        }
        if (item.selected().get(recipeIndex)) {
            item.deselect(recipeIndex);
        } else {
            item.select(recipeIndex);
        }
        item.recipes().get(recipeIndex).setUserOverridden(true);
        rememberItem(item);
        afterSelectionChange(item);
    }

    /** Selects every recipe at the node and every descendant item node. */
    public void selectNode(ItemNode item) {
        TreeSelection.selectAll(item);
        rememberItem(item);
        afterSelectionChange(item);
    }

    /** Deselects every recipe at the node and every descendant item node. */
    public void deselectNode(ItemNode item) {
        TreeSelection.deselectAll(item);
        rememberItem(item);
        afterSelectionChange(item);
    }

    /** Selects every recipe in the whole tree. */
    public void selectAll() {
        ItemNode root = this.root;
        if (root == null) {
            return;
        }
        TreeSelection.selectAll(root);
        afterSelectionChange(root);
    }

    /** Deselects every recipe in the whole tree. */
    public void deselectAll() {
        ItemNode root = this.root;
        if (root == null) {
            return;
        }
        TreeSelection.deselectAll(root);
        afterSelectionChange(root);
    }

    /**
     * Deselects every recipe on the item and marks it "supply as raw input",
     * pruning its subtree from the plan (DESIGN.md §8.5).
     */
    public void markRawInput(ItemNode item) {
        if (item.recipes() == null) {
            return;
        }
        item.selected().clear();
        item.setRawInput(true);
        rememberItem(item);
        afterSelectionChange(item);
    }

    /** Re-applies the tiering defaults to the whole tree, undoing manual edits. */
    public void resetToDefaults() {
        ItemNode root = this.root;
        if (root == null) {
            return;
        }
        TreeSelection.resetToDefaults(root, selector);
        afterSelectionChange(root);
    }

    /** Re-applies the tiering defaults to the node and its descendants, undoing manual edits. */
    public void resetNodeDefaults(ItemNode item) {
        TreeSelection.resetToDefaults(item, selector);
        afterSelectionChange(item);
    }

    /** Refreshes the raw-input mark and collision flags for the node and its descendants. */
    private void afterSelectionChange(ItemNode item) {
        for (ItemNode node : itemNodesUnder(item)) {
            if (node.recipes() != null) {
                node.setRawInput(node.selected().isEmpty());
                TreeSelection.refreshCollisions(node, RppConfig.warnOnCostlyCollisions());
            }
        }
        notifyUpdate();
    }

    /**
     * Remembers the item's current checked set (DESIGN.md §8.7) and applies
     * it to every other node with the same goal, immediately. Called after
     * per-item selection changes; the tree-wide bulk operations
     * ({@link #selectAll} / {@link #deselectAll}) do not remember, because
     * they are a convenience for the current tree, not a per-item decision.
     * A no-op when sticky choices are disabled.
     */
    private void rememberItem(ItemNode item) {
        if (sticky == null || item.recipes() == null) {
            return;
        }
        ItemNode root = this.root;
        if (root == null) {
            return;
        }
        Set<ResourceLocation> ids = new HashSet<>();
        for (int i = 0; i < item.recipes().size(); i++) {
            if (item.selected().get(i)) {
                ids.add(item.recipes().get(i).recipe().id());
            }
        }
        TreeSelection.propagateSelection(root, item, ids, sticky, RppConfig.warnOnCostlyCollisions());
    }

    /** True when the sticky store remembers a choice for this item's goal. */
    public boolean hasStickyChoice(ItemNode item) {
        if (sticky == null) {
            return false;
        }
        ResourceLocation goal = itemLocation(item.goal());
        return goal != null && sticky.get(goal) != null;
    }

    /**
     * Forgets the remembered choice for this item's goal (DESIGN.md §8.7)
     * and falls back to tiering for every node with that goal.
     */
    public void forgetStickyChoice(ItemNode item) {
        if (sticky == null) {
            return;
        }
        ResourceLocation goal = itemLocation(item.goal());
        if (goal == null) {
            return;
        }
        sticky.forget(goal);
        ItemNode root = this.root;
        if (root == null) {
            return;
        }
        for (ItemNode other : itemNodesUnder(root)) {
            if (!sameItem(other.goal(), item.goal()) || other.recipes() == null) {
                continue;
            }
            for (RecipeNode recipe : other.recipes()) {
                recipe.setStickyRestored(false);
                recipe.setUserOverridden(false);
            }
            selector.applyDefaults(other);
        }
        afterSelectionChange(item);
    }

    /**
     * Clears every remembered choice (DESIGN.md §8.7) and falls back to
     * tiering for the nodes that were restored from a sticky set.
     */
    public void clearStickyChoices() {
        if (sticky == null) {
            return;
        }
        ItemNode root = this.root;
        if (root == null) {
            return;
        }
        sticky.clearAll();
        for (ItemNode node : itemNodesUnder(root)) {
            if (node.recipes() == null) {
                continue;
            }
            boolean any = false;
            for (RecipeNode recipe : node.recipes()) {
                if (recipe.isStickyRestored()) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                continue;
            }
            for (RecipeNode recipe : node.recipes()) {
                recipe.setStickyRestored(false);
                recipe.setUserOverridden(false);
            }
            selector.applyDefaults(node);
        }
        afterSelectionChange(root);
    }

    private static @Nullable ResourceLocation itemLocation(AEKey goal) {
        if (!(goal instanceof AEItemKey goalKey)) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(goalKey.getItem());
    }

    private static boolean sameItem(AEKey a, AEKey b) {
        return a instanceof AEItemKey ak && b instanceof AEItemKey bk && ak.getItem() == bk.getItem();
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

    // --- plan ---

    /** The flattened plan rows (selected recipes + raw inputs), deduplicated. */
    public List<PlanRow> plan() {
        ItemNode root = this.root;
        if (root == null) {
            return List.of();
        }
        return TreeSelection.planRows(root);
    }

    /** The plan's print entries (raw-input rows excluded), for the print request. */
    public List<PlanEntry> planEntries() {
        List<PlanEntry> entries = new ArrayList<>();
        for (PlanRow row : plan()) {
            if (row.recipeId() != null) {
                entries.add(new PlanEntry(row.recipeId(), row.output(), row.selectedCandidates()));
            }
        }
        return entries;
    }

    // --- lifecycle ---

    /** Sets the update listener, invoked on the main thread after each build/selection change. */
    public void setOnUpdate(Consumer<ClientTreeSession> listener) {
        this.onUpdate = listener;
    }

    /** Shuts down the background executor; pending work is cancelled. */
    public void close() {
        executor.shutdownNow();
    }

    // --- internals ---

    private void submitRootBuild() {
        RecipeIndex snapshot = this.index;
        pendingRoot = executor.submit(() -> {
            ItemNode newRoot = buildRootSafe();
            Minecraft.getInstance().execute(() -> {
                pendingRoot = null;
                if (snapshot != this.index) {
                    // A reload swapped the index mid-build; discard and rebuild.
                    rebuildRoot();
                    return;
                }
                this.root = newRoot;
                notifyUpdate();
            });
        });
    }

    private ItemNode buildRootSafe() {
        try {
            return builder.buildRoot(rootGoal, RppConfig.initialDepth());
        } catch (Exception e) {
            LOGGER.error("rpp root tree build failed", e);
            return null;
        }
    }

    /** Rebuilds the whole tree from the root against the current index snapshot. */
    private void rebuildRoot() {
        RecipeIndex newIndex = RecipeIndexHolder.current();
        if (newIndex.recipeCount() == 0) {
            return;
        }
        this.index = newIndex;
        this.selector = new SourceSelector(newIndex, limits, SourceSelector.SelectionConfig.fromConfig(), sticky);
        this.builder = new RecipeTreeBuilder(newIndex, limits, TAG_LOOKUP, this.selector);
        this.root = null;
        this.expandingNode = null;
        submitRootBuild();
    }

    private void notifyUpdate() {
        Consumer<ClientTreeSession> listener = this.onUpdate;
        if (listener != null) {
            listener.accept(this);
        }
    }
}
