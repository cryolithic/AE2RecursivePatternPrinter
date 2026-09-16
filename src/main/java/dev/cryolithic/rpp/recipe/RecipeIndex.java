package dev.cryolithic.rpp.recipe;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * Immutable {@code Item -> List<RecipeView>} reverse index, built once per
 * recipe reload and never mutated afterwards (DESIGN.md §6.2). Readers hold
 * the old snapshot until an atomic swap, so off-thread tree building needs
 * no locking.
 *
 * <p>The build is a pure function of its arguments: the blacklist and the
 * trust filter are resolved on the calling thread and passed in, and the
 * per-slot candidate item lists are materialized there by
 * {@link #resolveIngredientItems}, so the background build touches neither
 * config state nor vanilla mutable ingredient state.
 *
 * @param byOutput   recipe views bucketed under each distinct output item
 * @param recipeCount number of recipes that extracted successfully
 */
public record RecipeIndex(Map<Item, List<RecipeView>> byOutput, int recipeCount) {

    private static final Logger LOGGER = LogManager.getLogger(RecipeIndex.class);

    /**
     * An adapter that has thrown this many times during one build is
     * disabled for the rest of the build: a broken third-party adapter
     * becomes bounded, diagnosable degradation instead of one stack trace
     * per recipe.
     */
    private static final int MAX_ADAPTER_THROWS = 10;

    /**
     * One resolved ingredient slot: the candidate items (tag members or the
     * single concrete item) and the slot's consumption (max stack count among
     * the members). Resolved on the main thread so the background build never
     * calls {@code Ingredient.getItems()} (DESIGN.md §9).
     */
    public record SlotItems(List<Item> items, int slotCount) {
    }

    /**
     * Convenience for callers on the main thread (the unit tests): resolves
     * the ingredient data on the calling thread and runs the build with the
     * trust filter off. Production callers must use
     * {@link #resolveIngredientItems} plus the five-argument build, so that
     * {@code Ingredient.getItems()} is never called from the background
     * build thread.
     *
     * @param blacklistedTypes recipe type names to skip, full ids
     *        ({@code "minecraft:crafting"}) or short names ({@code "crafting"})
     */
    public static RecipeIndex build(RecipeManager rm, HolderLookup.Provider registries,
            Collection<? extends String> blacklistedTypes) {
        return build(rm, registries, blacklistedTypes, resolveIngredientItems(rm, blacklistedTypes), false);
    }

    /**
     * The index build proper: a pure function of its arguments. It reads no
     * config and calls no {@code Ingredient.getItems()} — the blacklist and
     * the trust filter are resolved on the calling thread, and the per-slot
     * candidate item lists are materialized there by
     * {@link #resolveIngredientItems}. Safe to run on a background thread;
     * callers swap the result in atomically.
     *
     * <p>ATM10 is roughly 40-60k recipes; this is tens of milliseconds.
     * Recipe types listed in {@code blacklistedTypes} are skipped, and when
     * {@code requireTrusted} is set, untrusted (heuristically-extracted)
     * recipes are skipped too (DESIGN.md §6.4, §12).
     *
     * @param blacklistedTypes recipe type names to skip, full ids
     *        ({@code "minecraft:crafting"}) or short names ({@code "crafting"})
     * @param ingredientItems per-slot candidate items keyed by recipe id,
     *        resolved on the calling thread; a recipe missing from the map
     *        (its ingredients could not be resolved) is counted as an
     *        extraction failure
     * @param requireTrusted when true, untrusted recipes (no adapter, not a
     *        vanilla crafting recipe) are skipped entirely
     */
    public static RecipeIndex build(RecipeManager rm, HolderLookup.Provider registries,
            Collection<? extends String> blacklistedTypes,
            Map<ResourceLocation, List<SlotItems>> ingredientItems,
            boolean requireTrusted) {
        Map<Item, List<RecipeView>> byOutput = new HashMap<>();
        Map<RecipeType<?>, Integer> adapterThrows = new HashMap<>();
        int recipeCount = 0;
        int failures = 0;
        int skipped = 0;
        int untrusted = 0;
        int custom = 0;
        for (RecipeHolder<?> holder : rm.getRecipes()) {
            if (isBlacklisted(holder.value().getType(), blacklistedTypes)) {
                skipped++;
                continue;
            }
            // §6.3: CustomRecipe and other special recipes are skipped, not
            // counted as extraction failures.
            if (holder.value() instanceof CustomRecipe) {
                custom++;
                continue;
            }
            RecipeView view = extract(holder, registries, ingredientItems, adapterThrows);
            if (view == null) {
                failures++;
                continue;
            }
            if (requireTrusted && !view.trusted()) {
                untrusted++;
                continue;
            }
            recipeCount++;
            // Bucket each view once per distinct output item: a recipe with
            // two outputs of the same item must not appear twice under it.
            Set<Item> bucketed = new HashSet<>();
            for (GenericStack output : view.outputs()) {
                if (output.what() instanceof AEItemKey itemKey
                        && bucketed.add(itemKey.getItem())) {
                    byOutput.computeIfAbsent(itemKey.getItem(), k -> new ArrayList<>()).add(view);
                }
            }
        }

        Map<Item, List<RecipeView>> frozen = new HashMap<>(byOutput.size());
        byOutput.forEach((item, views) -> frozen.put(item, List.copyOf(views)));
        LOGGER.info("rpp recipe index: {} recipes indexed, {} extraction failures, {} blacklisted, {} untrusted, {} custom",
                recipeCount, failures, skipped, untrusted, custom);
        // §6.2: the index is immutable — readers hold the snapshot across the
        // atomic swap, so the outer map must not be mutable either.
        return new RecipeIndex(Map.copyOf(frozen), recipeCount);
    }

    /**
     * Main-thread pass over the recipe manager that materializes the
     * candidate item list for every ingredient slot of every non-blacklisted
     * recipe.
     *
     * <p>Must run on the calling (main) thread: {@code Ingredient.getItems()}
     * lazily populates a non-volatile field and, for NeoForge custom
     * ingredients, runs third-party code with no thread-safety contract.
     * Calling it from the background build thread can race a concurrent
     * {@code /reload} or live crafting against the same shared ingredient.
     * Warming it here is idempotent and makes later concurrent reads safe.
     *
     * @return per-slot candidate item lists keyed by recipe id; a recipe
     *         whose ingredients could not be resolved (a throwing
     *         third-party ingredient) is absent from the map and is counted
     *         as an extraction failure by the build
     */
    public static Map<ResourceLocation, List<SlotItems>> resolveIngredientItems(
            RecipeManager rm, Collection<? extends String> blacklistedTypes) {
        Map<ResourceLocation, List<SlotItems>> resolved = new HashMap<>();
        for (RecipeHolder<?> holder : rm.getRecipes()) {
            if (isBlacklisted(holder.value().getType(), blacklistedTypes)) {
                continue;
            }
            try {
                List<Ingredient> ingredients = holder.value().getIngredients();
                List<SlotItems> slots = new ArrayList<>(ingredients.size());
                for (Ingredient ingredient : ingredients) {
                    ItemStack[] stacks = ingredient.getItems();
                    List<Item> items = new ArrayList<>(stacks.length);
                    int slotCount = 0;
                    for (ItemStack stack : stacks) {
                        items.add(stack.getItem());
                        slotCount = Math.max(slotCount, stack.getCount());
                    }
                    slots.add(new SlotItems(List.copyOf(items), ingredient.isEmpty() ? 0 : Math.max(1, slotCount)));
                }
                resolved.put(holder.id(), List.copyOf(slots));
            } catch (Exception e) {
                // Leave the recipe out of the map; the build counts it as an
                // extraction failure. One broken ingredient must not take
                // down the whole index.
            }
        }
        return resolved;
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

    /**
     * Extract the view for one recipe. Every path is isolated: a throwing
     * adapter or recipe is counted as an extraction failure by the caller
     * and never aborts the build (DESIGN.md §6.3).
     */
    private static RecipeView extract(RecipeHolder<?> holder, HolderLookup.Provider registries,
            Map<ResourceLocation, List<SlotItems>> ingredientItems,
            Map<RecipeType<?>, Integer> adapterThrows) {
        Recipe<?> recipe = holder.value();
        ResourceLocation id = holder.id();
        RecipeType<?> type = recipe.getType();

        // Path 3: registered adapter (DESIGN.md §6.4). The dispatch is
        // isolated inside the per-recipe try/catch: a throwing adapter is
        // counted as an extraction failure, and one that keeps throwing is
        // disabled for the rest of the build after MAX_ADAPTER_THROWS,
        // falling back to the vanilla paths below.
        RecipeAdapter adapter = RecipeAdapters.forType(type);
        if (adapter != null && adapterThrows.getOrDefault(type, 0) < MAX_ADAPTER_THROWS) {
            try {
                RecipeView view = adapter.view(holder, registries);
                if (view != null) {
                    return view;
                }
            } catch (Exception e) {
                int throwCount = adapterThrows.merge(type, 1, Integer::sum);
                if (throwCount == MAX_ADAPTER_THROWS) {
                    LOGGER.warn("rpp recipe adapter for {} threw {} times; disabling it "
                            + "for the rest of this build", type, throwCount, e);
                }
                return null;
            }
        }

        List<SlotItems> resolved = ingredientItems.get(id);
        if (resolved == null) {
            // The main-thread pass could not resolve this recipe's
            // ingredients; count it as an extraction failure.
            return null;
        }

        // Path 1: CraftingRecipe — trusted, with grid dimensions.
        if (recipe instanceof CraftingRecipe crafting) {
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
                return new RecipeView(id, type, toIngredientViews(crafting.getIngredients(), resolved),
                        List.of(stack(result)), width, height, true);
            } catch (Exception e) {
                return null;
            }
        }

        // Path 2: generic Recipe — untrusted.
        try {
            ItemStack result = recipe.getResultItem(registries);
            if (result.isEmpty()) {
                return null;
            }
            return new RecipeView(id, type, toIngredientViews(recipe.getIngredients(), resolved),
                    List.of(stack(result)), 0, 0, false);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<IngredientView> toIngredientViews(List<Ingredient> ingredients,
            List<SlotItems> resolvedItems) {
        List<IngredientView> views = new ArrayList<>(ingredients.size());
        for (int i = 0; i < ingredients.size(); i++) {
            SlotItems slot = resolvedItems.get(i);
            views.add(new IngredientView(ingredients.get(i), slot.items(), tagId(ingredients.get(i)), slot.slotCount()));
        }
        return List.copyOf(views);
    }

    /**
     * The tag id when the ingredient is tag-based — including combined
     * tag-plus-item ingredients, which are still tag-driven for display
     * and candidate purposes. Null only for purely direct-item ingredients.
     */
    private static ResourceLocation tagId(Ingredient ingredient) {
        for (Ingredient.Value value : ingredient.getValues()) {
            if (value instanceof Ingredient.TagValue tagValue) {
                return tagValue.tag().location();
            }
        }
        return null;
    }

    private static GenericStack stack(ItemStack result) {
        // The pattern must encode the real ratio (DESIGN.md §1.5): a block
        // recipe yields 9, not 1.
        return new GenericStack(AEItemKey.of(result), result.getCount());
    }
}
