package dev.cryolithic.rpp.recipe;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * Holds the current {@link RecipeIndex} snapshot and publishes rebuilds that
 * run off the main thread on recipe reload (DESIGN.md §6.2, §9).
 *
 * <p>Readers hold the old snapshot until the atomic swap, so off-thread tree
 * building needs no locking. If a reload lands mid-build, the in-flight build
 * finishes against the old recipe snapshot and its result is discarded on
 * publish: each rebuild bumps a generation counter, and a build only
 * publishes if no newer rebuild was scheduled after it started
 * (DESIGN.md §9, "comparing snapshot identity").
 *
 * <p>The rebuild inputs (blacklist, trust filter, resolved ingredient
 * items) are resolved by the caller on the main thread; the background
 * build touches no config state and no vanilla mutable ingredient state.
 */
public final class RecipeIndexHolder {
    private static final Logger LOGGER = LogManager.getLogger(RecipeIndexHolder.class);

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
     * Schedule an off-thread rebuild from pre-resolved inputs. The caller
     * must have resolved the config values and the ingredient data on the
     * main thread ({@link RecipeIndex#resolveIngredientItems}); the
     * background build is a pure function of these arguments. Its result is
     * published only if no newer rebuild was scheduled in the meantime.
     */
    public static void rebuild(RecipeManager recipeManager, HolderLookup.Provider registries,
            Collection<? extends String> blacklistedTypes,
            Map<ResourceLocation, List<List<Item>>> ingredientItems,
            boolean requireTrusted) {
        long generation = GENERATION.incrementAndGet();
        EXECUTOR.submit(() -> {
            try {
                RecipeIndex built = RecipeIndex.build(recipeManager, registries, blacklistedTypes,
                        ingredientItems, requireTrusted);
                if (GENERATION.get() == generation) {
                    CURRENT.set(built);
                }
            } catch (Exception e) {
                LOGGER.error("rpp recipe index rebuild failed", e);
            }
        });
    }
}
