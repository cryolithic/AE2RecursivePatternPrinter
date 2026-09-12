package dev.cryolithic.rpp.recipe;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.cryolithic.rpp.RppConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable {@code Item -> List<RecipeView>} reverse index, built once per
 * recipe reload and never mutated afterwards (DESIGN.md §6.2). Readers hold
 * the old snapshot until an atomic swap, so off-thread tree building needs
 * no locking.
 *
 * @param byOutput   recipe views bucketed under each distinct output item
 * @param recipeCount number of recipes that extracted successfully
 */
public record RecipeIndex(Map<Item, List<RecipeView>> byOutput, int recipeCount) {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecipeIndex.class);

    /**
     * Single pass over the recipe manager. ATM10 is roughly 40-60k recipes;
     * this is tens of milliseconds. Callers are expected to run this off the
     * main thread and swap the reference in atomically. Recipe types listed in
     * the {@code blacklistedRecipeTypes} config are skipped.
     */
    public static RecipeIndex build(RecipeManager rm, HolderLookup.Provider registries) {
        return build(rm, registries, RppConfig.blacklistedRecipeTypes());
    }

    /**
     * Same as {@link #build(RecipeManager, HolderLookup.Provider)} but with an
     * explicit blacklist, so the build stays testable without a loaded
     * NeoForge config.
     *
     * @param blacklistedTypes recipe type names to skip, full ids
     *        ({@code "minecraft:crafting"}) or short names ({@code "crafting"})
     */
    public static RecipeIndex build(RecipeManager rm, HolderLookup.Provider registries,
            Collection<? extends String> blacklistedTypes) {
        Map<Item, List<RecipeView>> byOutput = new HashMap<>();
        int recipeCount = 0;
        int failures = 0;
        int skipped = 0;

        for (RecipeHolder<?> holder : rm.getRecipes()) {
            if (isBlacklisted(holder.value().getType(), blacklistedTypes)) {
                skipped++;
                continue;
            }
            RecipeView view = extract(holder, registries);
            if (view == null) {
                failures++;
                continue;
            }
            recipeCount++;
            for (GenericStack output : view.outputs()) {
                if (output.what() instanceof AEItemKey itemKey) {
                    byOutput.computeIfAbsent(itemKey.getItem(), k -> new ArrayList<>()).add(view);
                }
            }
        }

        Map<Item, List<RecipeView>> frozen = new HashMap<>(byOutput.size());
        byOutput.forEach((item, views) -> frozen.put(item, List.copyOf(views)));
        LOGGER.info("rpp recipe index: {} recipes indexed, {} extraction failures, {} blacklisted",
                recipeCount, failures, skipped);
        return new RecipeIndex(frozen, recipeCount);
    }

    /**
     * True when the recipe type is on the blacklist. Matches both the full
     * registry id ({@code "minecraft:crafting"}) and the short name
     * ({@code "crafting"}); unregistered types (modded
     * {@code RecipeType.simple}) match on their location string.
     */
    private static boolean isBlacklisted(RecipeType<?> type, Collection<? extends String> blacklist) {
        if (blacklist.isEmpty()) {
            return false;
        }
        if (blacklist.contains(type.toString())) {
            return true;
        }
        return BuiltInRegistries.RECIPE_TYPE.getResourceKey(type)
                .map(key -> blacklist.contains(key.location().toString()))
                .orElse(false);
    }

    /** Recipes that can produce the goal; empty for non-item keys in v1. */
    public List<RecipeView> recipesFor(AEKey goal) {
        if (goal instanceof AEItemKey itemKey) {
            return byOutput.getOrDefault(itemKey.getItem(), List.of());
        }
        return List.of();
    }

    private static RecipeView extract(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        Recipe<?> recipe = holder.value();
        ResourceLocation id = holder.id();
        RecipeType<?> type = recipe.getType();

        // Path 3: registered adapter (DESIGN.md §6.4).
        RecipeAdapter adapter = RecipeAdapters.forType(type);
        if (adapter != null) {
            RecipeView view = adapter.view(holder, registries);
            if (view != null) {
                return view;
            }
        }

        // Path 1: CraftingRecipe — trusted, with grid dimensions.
        if (recipe instanceof CraftingRecipe crafting) {
            return extractCrafting(crafting, id, type, registries);
        }

        // Path 2: generic Recipe — untrusted.
        try {
            ItemStack result = recipe.getResultItem(registries);
            if (result.isEmpty()) {
                return null;
            }
            return new RecipeView(id, type, toIngredientViews(recipe.getIngredients()),
                    List.of(stack(result)), 0, 0, false);
        } catch (Exception e) {
            return null;
        }
    }

    private static RecipeView extractCrafting(CraftingRecipe crafting, ResourceLocation id,
            RecipeType<?> type, HolderLookup.Provider registries) {
        try {
            ItemStack result = crafting.getResultItem(registries);
            if (result.isEmpty()) {
                return null;
            }
            int width = 0;
            int height = 0;
            if (crafting instanceof ShapedRecipe shaped) {
                width = shaped.getWidth();
                height = shaped.getHeight();
            }
            return new RecipeView(id, type, toIngredientViews(crafting.getIngredients()),
                    List.of(stack(result)), width, height, true);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<IngredientView> toIngredientViews(List<Ingredient> ingredients) {
        List<IngredientView> views = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            List<Item> candidates = new ArrayList<>();
            for (ItemStack stack : ingredient.getItems()) {
                candidates.add(stack.getItem());
            }
            views.add(new IngredientView(ingredient, List.copyOf(candidates), tagId(ingredient)));
        }
        return List.copyOf(views);
    }

    /** The tag id when the ingredient is tag-based, else null. */
    private static ResourceLocation tagId(Ingredient ingredient) {
        Ingredient.Value[] values = ingredient.getValues();
        if (values.length == 1 && values[0] instanceof Ingredient.TagValue tagValue) {
            TagKey<Item> tagKey = tagValue.tag();
            return tagKey.location();
        }
        return null;
    }

    private static GenericStack stack(ItemStack result) {
        // The pattern must encode the real ratio (DESIGN.md §1.5): a block
        // recipe yields 9, not 1.
        return new GenericStack(AEItemKey.of(result), result.getCount());
    }
}
