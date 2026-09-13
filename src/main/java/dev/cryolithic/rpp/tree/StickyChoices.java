package dev.cryolithic.rpp.tree;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The sticky source sets (DESIGN.md §8.7): the recipes a player already
 * chose for each goal item. In-memory now; a {@link Persistence} seam
 * (milestone 6) will back it with a per-player file.
 *
 * <p>Keyed by goal item registry location. A set is remembered when the
 * player changes the checked recipes for an item, reapplied on the next
 * build (ranked at weight 1), and can be forgotten per item or cleared in
 * full. A stale recipe id (absent from the current index) is dropped
 * silently at apply time, not here.</p>
 *
 * <p>The store is a {@link ConcurrentHashMap}: the background expansion
 * thread reads it through {@code SourceSelector} while the client main
 * thread mutates it on every selection change (DESIGN.md §9).</p>
 */
public final class StickyChoices {
    /**
     * Persistence seam; milestone 6 backs this with a per-player file.
     * Implementations are called on every mutation and on construction.
     */
    public interface Persistence {
        void save(Map<ResourceLocation, SourceSet> sets);

        Map<ResourceLocation, SourceSet> load();
    }

    private final Map<ResourceLocation, SourceSet> sets = new ConcurrentHashMap<>();
    private final Persistence persistence;

    public StickyChoices() {
        this(null);
    }

    public StickyChoices(@Nullable Persistence persistence) {
        this.persistence = persistence;
        if (persistence != null) {
            sets.putAll(persistence.load());
        }
    }

    /** The remembered source set for the goal, or null when none. */
    public @Nullable SourceSet get(ResourceLocation goal) {
        return sets.get(goal);
    }

    /** Remember the chosen recipe ids for the goal. */
    public void remember(ResourceLocation goal, Set<ResourceLocation> recipeIds) {
        sets.put(goal, new SourceSet(goal, Set.copyOf(recipeIds)));
        save();
    }

    /** Forget the choice for one goal ("forget this choice"). */
    public void forget(ResourceLocation goal) {
        sets.remove(goal);
        save();
    }

    /** Clear every remembered set ("clear all"). */
    public void clearAll() {
        sets.clear();
        save();
    }

    /** All remembered sets, for the persistence seam. */
    public Map<ResourceLocation, SourceSet> all() {
        return new HashMap<>(sets);
    }

    private void save() {
        if (persistence != null) {
            persistence.save(sets);
        }
    }
}
