package dev.cryolithic.rpp.recipe;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
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
 * <p>Each rebuild runs {@link RecipeIndex#build} on the holder's single
 * background thread and swaps the atomic reference; a reload mid-build is
 * handled by the holder discarding the stale result (DESIGN.md §9).
 */
public final class RecipeIndexBootstrap {

    private RecipeIndexBootstrap() {
    }

    /**
     * Register the recipe-reload listeners (server and client) that rebuild
     * the index off-thread and swap the atomic reference.
     */
    public static void init(IEventBus modBus) {
        modBus.addListener(RecipeIndexBootstrap::onAddReloadListener);
        // RecipesUpdatedEvent is posted on the game bus, not the mod bus.
        NeoForge.EVENT_BUS.addListener(RecipeIndexBootstrap::onRecipesUpdated);
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
                RecipeIndexHolder.rebuild(recipeManager, registries);
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
        RecipeIndexHolder.rebuild(event.getRecipeManager(), level.registryAccess());
    }
}
