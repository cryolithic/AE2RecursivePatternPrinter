package dev.cryolithic.rpp.recipe;

import dev.cryolithic.rpp.RppConfig;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
/**
 * Event-bus wiring for the {@link RecipeIndex} (DESIGN.md §6.2). Registered
 * from the mod entrypoint; both sides feed the same {@link RecipeIndexHolder}.
 *
 * <p>Server: a reload listener added via {@link AddReloadListenerEvent} (mod
 * bus). Mod listeners are appended after the vanilla ones, so the vanilla
 * {@link RecipeManager} listener has already applied the new recipes by the
 * time ours runs. Client: {@link RecipesUpdatedEvent} (game bus) on login and
 * on every {@code /reload}.
 *
 * <p>Each rebuild resolves the config values and the ingredient data on the
 * calling (main) thread, then runs {@link RecipeIndex#build} on the holder's
 * single background thread and swaps the atomic reference; a reload mid-build
 * is handled by the holder discarding the stale result (DESIGN.md §9).
 */
public final class RecipeIndexBootstrap {

    private static final Logger LOGGER = LogManager.getLogger(RecipeIndexBootstrap.class);

    private RecipeIndexBootstrap() {
    }

    /**
     * Register the recipe-reload listeners (server and client) that rebuild
     * the index off-thread and swap the atomic reference.
     */
    public static void init(IEventBus modBus) {
        modBus.addListener(RecipeIndexBootstrap::onAddReloadListener);
        // RecipesUpdatedEvent is a client-only event posted on the game bus,
        // not the mod bus. Registering it (or even resolving its class)
        // would fail on a dedicated server, so the listener is only added
        // on the client; the server rebuilds via the reload listener above.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForge.EVENT_BUS.addListener(RecipeIndexBootstrap::onRecipesUpdated);
        }
    }

    private static void onAddReloadListener(AddReloadListenerEvent event) {
        RecipeManager recipeManager = event.getServerResources().getRecipeManager();
        HolderLookup.Provider registries = event.getRegistryAccess();
        event.addListener(new SimplePreparableReloadListener<Object>() {
            @Override
            protected Object prepare(ResourceManager resources, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Object prepared, ResourceManager resources, ProfilerFiller profiler) {
                scheduleRebuild(recipeManager, registries);
            }
        });
    }

    private static void onRecipesUpdated(RecipesUpdatedEvent event) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            // No world yet; the event fires again once the client is in a world
            // with synced recipes.
            return;
        }
        scheduleRebuild(event.getRecipeManager(), level.registryAccess());
    }

    /**
     * Resolve the config values and the ingredient data on the calling
     * (main) thread, then schedule the background build. The background
     * build must touch no config state (the client spec is never loaded on a
     * dedicated server, so a config read there throws) and no vanilla
     * mutable ingredient state ({@code Ingredient.getItems()} is not
     * thread-safe).
     */
    private static void scheduleRebuild(RecipeManager recipeManager, HolderLookup.Provider registries) {
        try {
            Collection<? extends String> blacklistedTypes = RppConfig.blacklistedRecipeTypes();
            boolean requireTrusted = RppConfig.requireTrustedRecipes();
            Map<ResourceLocation, List<List<Item>>> ingredientItems =
                    RecipeIndex.resolveIngredientItems(recipeManager, blacklistedTypes);
            RecipeIndexHolder.rebuild(recipeManager, registries, blacklistedTypes, ingredientItems,
                    requireTrusted);
        } catch (Exception e) {
            LOGGER.error("rpp recipe index rebuild skipped", e);
        }
    }
}
