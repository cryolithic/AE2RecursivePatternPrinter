package dev.cryolithic.rpp.tree;

import org.jetbrains.annotations.Nullable;

/**
 * Caches the plan's pattern count so the render path reads it in O(1)
 * (DESIGN.md §9, §11.1). The O(tree) counter runs only when the cache is
 * stale — after {@link #invalidate()} or a root change — so the GUI
 * invalidates on the events that change the count (selection, expand/
 * collapse, rebuild, filter) and the render thread never walks the tree.
 *
 * <p>Writes happen on the client main thread (event handlers, build
 * publishes); reads happen on the render thread. The fields are volatile in
 * the order root, count, dirty: a reader that observes the latest dirty flag
 * observes a consistent triple, and any stale observation simply triggers one
 * extra recompute, never a wrong steady-state value.</p>
 */
public final class PlanCountCache {
    /** The O(tree) counter, run only when the cache is stale. */
    @FunctionalInterface
    public interface Counter {
        int count(ItemNode root);
    }

    private final Counter counter;
    private volatile ItemNode root;
    private volatile int count;
    private volatile boolean dirty = true;

    public PlanCountCache(Counter counter) {
        this.counter = counter;
    }

    /** Marks the cached count stale so the next {@link #count} recomputes. */
    public void invalidate() {
        this.dirty = true;
    }

    /**
     * The pattern count for the root. Returns the cached value when the root
     * is unchanged and the cache is fresh; otherwise runs the counter once.
     */
    public int count(@Nullable ItemNode root) {
        if (!dirty && root == this.root) {
            return count;
        }
        this.root = root;
        int c = root == null ? 0 : counter.count(root);
        this.count = c;
        this.dirty = false;
        return c;
    }
}
