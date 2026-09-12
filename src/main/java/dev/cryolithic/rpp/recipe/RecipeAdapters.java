package dev.cryolithic.rpp.recipe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.Nullable;

/**
 * Static registry of {@link RecipeAdapter}s, keyed by recipe type.
 * Other mods or our own compat classes register here to describe recipe
 * types that the default extraction cannot handle.
 */
public final class RecipeAdapters {
    private static final Map<RecipeType<?>, RecipeAdapter> ADAPTERS = new ConcurrentHashMap<>();

    private RecipeAdapters() {
    }

    public static void register(RecipeAdapter adapter) {
        ADAPTERS.put(adapter.type(), adapter);
    }

    public static void unregister(RecipeType<?> type) {
        ADAPTERS.remove(type);
    }

    @Nullable
    public static RecipeAdapter forType(RecipeType<?> type) {
        return ADAPTERS.get(type);
    }
}
