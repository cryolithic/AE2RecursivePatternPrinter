package dev.cryolithic.rpp.recipe;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.RecipeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the current {@link RecipeIndex} snapshot and rebuilds it off the main
 * thread on recipe reload (DESIGN.md §6.2, §9).
 *
 * <p>Readers hold the old snapshot until the atomic swap, so off-thread tree
 * building needs no locking. If a reload lands mid-build, the in-flight build
 * finishes against the old recipe snapshot and its result is discarded on
 * publish: each rebuild bumps a generation counter, and a build only
 * publishes if no newer rebuild was scheduled after it started
 * (DESIGN.md §9, "comparing snapshot identity").
 */
public final class RecipeIndexHolder {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecipeIndexHolder.class);

    private static final RecipeIndex EMPTY = new RecipeIndex(Map.of(), 0);
    private static final AtomicReference<RecipeIndex> CURRENT = new AtomicReference<>(EMPTY);
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rpp-recipe-index");
        thread.setDaemon(true);
        return thread;
    });

    private RecipeIndexHolder() {
    }

    /** The current index snapshot; empty until the first rebuild publishes. */
    public static RecipeIndex current() {
        return CURRENT.get();
    }

    /**
     * Schedule an off-thread rebuild. The build runs on the holder's single
     * background thread; its result is published only if no newer rebuild was
     * scheduled in the meantime.
     */
    public static void rebuild(RecipeManager recipeManager, HolderLookup.Provider registries) {
        long generation = GENERATION.incrementAndGet();
        EXECUTOR.submit(() -> {
            try {
                RecipeIndex built = RecipeIndex.build(recipeManager, registries);
                if (GENERATION.get() == generation) {
                    CURRENT.set(built);
                }
            } catch (Exception e) {
                LOGGER.error("rpp recipe index rebuild failed", e);
            }
        });
    }
}
