package dev.cryolithic.rpp.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

/**
 * SPI for recipe types whose inputs or outputs do not come through the
 * vanilla {@code Recipe} API (fluids, chemicals, special catalysts).
 * Registered via {@link RecipeAdapters}, keyed by recipe type
 * (DESIGN.md §6.4).
 */
public interface RecipeAdapter {
    RecipeType<?> type();

    @Nullable
    RecipeView view(RecipeHolder<?> holder, HolderLookup.Provider registries);
}
