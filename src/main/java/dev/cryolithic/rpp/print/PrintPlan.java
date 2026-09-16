package dev.cryolithic.rpp.print;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.cryolithic.rpp.recipe.IngredientView;
import dev.cryolithic.rpp.recipe.RecipeAdapter;
import dev.cryolithic.rpp.recipe.RecipeAdapters;
import dev.cryolithic.rpp.recipe.RecipeView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * The validated, server-side print plan (DESIGN.md §10.2). The client sends
 * recipe ids and its selections; the server never trusts them.
 * {@link #validate} rebuilds every entry against the server's own recipe
 * manager and rejects the whole job on any mismatch, then computes the
 * print order: deepest-first (dependencies before their consumers, so the
 * output inventory reads bottom-up in crafting order) and, when the group
 * flag is set, batched by destination.
 */
public final class PrintPlan {
    private static final Logger LOGGER = LogManager.getLogger(PrintPlan.class);

    private PrintPlan() {
    }

    /**
     * The result of a validation pass.
     *
     * @param accepted         true when the whole job may proceed
     * @param rejectionReason  why the job was rejected; null when accepted
     * @param orderedEntries   the print order; empty when rejected
     * @param views            the server-rebuilt views, keyed by recipe id
     */
    public record Result(boolean accepted, @Nullable String rejectionReason,
            List<PlanEntry> orderedEntries, Map<ResourceLocation, RecipeView> views) {
        /** The server-rebuilt view of an entry; non-null for accepted results. */
        public RecipeView viewOf(PlanEntry entry) {
            return views.get(entry.recipeId());
        }
    }

    /**
     * Validates a print request against the server's recipe manager.
     *
     * <p>Rejections: the batch exceeds {@code maxBatch}; the echoed input
     * does not decode to an item output; any recipe id is unknown or no
     * longer extractable; any claimed output or chosen candidate does not
     * match the recipe; any entry's output is neither the root goal nor an
     * ingredient of another entry (smuggling); or the plan contains a
     * recipe cycle that cannot be ordered.</p>
     *
     * @param recipeManager      the server's recipe manager
     * @param registries         registry provider
     * @param echoedInput        the input pattern the client echoed; its primary
     *                           output is the root goal the plan must connect to
     * @param entries            the client's plan entries
     * @param maxBatch           the server-side cap on patterns per request
     * @param groupByDestination batch the print order by destination
     * @param level              the server level; the echoed input pattern is
     *                           decoded through AE2's public pattern-details API
     */
    public static Result validate(RecipeManager recipeManager, HolderLookup.Provider registries,
            ItemStack echoedInput, List<PlanEntry> entries, int maxBatch, boolean groupByDestination,
            Level level) {
        if (entries.size() > maxBatch) {
            return reject("print batch exceeds the limit: " + entries.size() + " > " + maxBatch);
        }
        Item rootGoal = rootGoalItem(echoedInput, level);
        if (rootGoal == null) {
            return reject("input pattern does not decode to an item output");
        }

        Map<ResourceLocation, RecipeView> views = new LinkedHashMap<>();
        Map<ResourceLocation, Recipe<?>> recipes = new LinkedHashMap<>();
        for (PlanEntry entry : entries) {
            RecipeHolder<?> holder = recipeManager.byKey(entry.recipeId()).orElse(null);
            if (holder == null) {
                return reject("unknown recipe: " + entry.recipeId());
            }
            RecipeView view = viewOf(holder, registries);
            if (view == null) {
                return reject("recipe no longer extractable: " + entry.recipeId());
            }
            views.put(entry.recipeId(), view);
            recipes.put(entry.recipeId(), holder.value());

            if (!produces(view, entry.output())) {
                return reject("claimed output does not match recipe " + entry.recipeId());
            }
            List<AEKey> candidates = entry.selectedCandidates();
            List<IngredientView> slots = view.inputs();
            if (slots.isEmpty()) {
                // No encoder can encode a recipe with no input slots:
                // AE2's processing encoder requires at least one input
                // (it throws otherwise) and a zero-input crafting
                // pattern would be a free generator. Reject here rather
                // than accept the plan and fail at encode time, where the
                // entry would silently vanish from the print.
                return reject("entry " + entry.recipeId() + " has no input slots and cannot be encoded");
            } else {
                // Blank grid slots (Ingredient.EMPTY) consume no input, so
                // the client's candidate list holds one entry per
                // non-blank slot, in slot order; the exact-size rule and
                // the ingredient test both skip blanks.
                int nonBlank = 0;
                for (IngredientView slot : slots) {
                    if (!slot.isEmpty()) {
                        nonBlank++;
                    }
                }
                if (candidates.size() != nonBlank) {
                    return reject("entry " + entry.recipeId() + " selects " + candidates.size()
                            + " candidates but the recipe has " + nonBlank + " non-blank input slots");
                }
                int ci = 0;
                for (IngredientView slot : slots) {
                    if (slot.isEmpty()) {
                        continue;
                    }
                    if (!(candidates.get(ci) instanceof AEItemKey itemKey)) {
                        return reject("entry " + entry.recipeId() + " has a non-item candidate");
                    }
                    if (!slot.ingredient().test(itemKey.toStack())) {
                        return reject("chosen candidate not accepted by recipe " + entry.recipeId());
                    }
                    ci++;
                }
            }
        }

        // Connectivity: every entry's output is the root goal or an
        // ingredient of another entry. An entry's own inputs do not count
        // toward its own connectivity: a recipe that consumes its own
        // output is connected only if that output is the root goal or feeds
        // another entry. Closes the loop: a plan that does not produce the
        // goal is a closed ingredient loop, which the ordering step below
        // rejects as a cycle.
        Map<ResourceLocation, Set<Item>> demandedBy = new HashMap<>();
        for (PlanEntry entry : entries) {
            Set<Item> demanded = new HashSet<>();
            for (IngredientView input : views.get(entry.recipeId()).inputs()) {
                demanded.addAll(input.candidates());
            }
            demandedBy.put(entry.recipeId(), demanded);
        }
        for (PlanEntry entry : entries) {
            if (!(entry.output() instanceof AEItemKey output)) {
                return reject("entry " + entry.recipeId() + " has a non-item output");
            }
            Item produced = output.getItem();
            if (produced == rootGoal) {
                continue;
            }
            boolean demandedByAnother = false;
            for (PlanEntry other : entries) {
                if (!other.recipeId().equals(entry.recipeId())
                        && demandedBy.get(other.recipeId()).contains(produced)) {
                    demandedByAnother = true;
                    break;
                }
            }
            if (!demandedByAnother) {
                return reject("unconnected entry: " + entry.recipeId());
            }
        }

        List<PlanEntry> ordered = order(entries, views, recipes, rootGoal, groupByDestination);
        if (ordered == null) {
            return reject("plan contains a recipe cycle");
        }
        return new Result(true, null, List.copyOf(ordered), Map.copyOf(views));
    }

    /**
     * Rebuilds a {@link RecipeView} from a recipe holder — the server-side
     * twin of {@code RecipeIndex.extract}. Smithing recipes expose their
     * three inputs only through the vanilla implementation's fields
     * ({@code Recipe#getIngredients} is empty for smithing on 1.21.1), so
     * they are read reflectively; implementations that hide their inputs
     * return null.
     */
    public static RecipeView viewOf(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        Recipe<?> recipe = holder.value();
        ResourceLocation id = holder.id();
        RecipeType<?> type = recipe.getType();

        RecipeAdapter adapter = RecipeAdapters.forType(type);
        if (adapter != null) {
            RecipeView view = adapter.view(holder, registries);
            if (view != null) {
                return view;
            }
        }

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
                return new RecipeView(id, type, toIngredientViews(crafting.getIngredients()),
                        List.of(stack(result)), width, height, true);
            } catch (Exception e) {
                LOGGER.warn("rpp print: failed to extract crafting recipe {}", id, e);
                return null;
            }
        }

        if (recipe instanceof SmithingRecipe smithing) {
            try {
                ItemStack result = smithing.getResultItem(registries);
                if (result.isEmpty()) {
                    return null;
                }
                List<Ingredient> inputs = smithingInputs(smithing);
                if (inputs == null) {
                    return null;
                }
                return new RecipeView(id, type, toIngredientViews(inputs), List.of(stack(result)), 0, 0, false);
            } catch (Exception e) {
                LOGGER.warn("rpp print: failed to extract smithing recipe {}", id, e);
                return null;
            }
        }

        try {
            ItemStack result = recipe.getResultItem(registries);
            if (result.isEmpty()) {
                return null;
            }
            return new RecipeView(id, type, toIngredientViews(recipe.getIngredients()),
                    List.of(stack(result)), 0, 0, false);
        } catch (Exception e) {
            LOGGER.warn("rpp print: failed to extract recipe {}", id, e);
            return null;
        }
    }

    /**
     * The print order: topological over the plan DAG (a producer precedes
     * every entry that consumes its output), stable in client order for
     * independent entries. When {@code groupByDestination} is set, entries
     * are batched by destination — ASSEMBLER, then MACHINE:* in
     * alphabetical machine-type order, then STONECUTTER, then SMITHING —
     * keeping topological order within each batch.
     *
     * @return the ordered entries, or null when the plan contains a cycle
     */
    @Nullable
    private static List<PlanEntry> order(List<PlanEntry> entries, Map<ResourceLocation, RecipeView> views,
            Map<ResourceLocation, Recipe<?>> recipes, Item rootGoal, boolean groupByDestination) {
        Map<ResourceLocation, List<ResourceLocation>> dependents = new HashMap<>();
        Map<ResourceLocation, Integer> unmet = new HashMap<>();
        for (PlanEntry entry : entries) {
            unmet.put(entry.recipeId(), 0);
        }
        for (PlanEntry consumer : entries) {
            RecipeView view = views.get(consumer.recipeId());
            Set<ResourceLocation> dependencies = new HashSet<>();
            for (IngredientView input : view.inputs()) {
                for (Item candidate : input.candidates()) {
                    for (PlanEntry producer : entries) {
                        if (producer.recipeId().equals(consumer.recipeId())) {
                            // A self-dependency is a real cycle unless the
                            // entry produces the root goal, whose seed is
                            // supplied externally by the user.
                            if (consumer.output() instanceof AEItemKey consumerOut
                                    && consumerOut.getItem() == rootGoal) {
                                continue;
                            }
                        }
                        if (producer.output() instanceof AEItemKey out && out.getItem() == candidate) {
                            dependencies.add(producer.recipeId());
                        }
                    }
                }
            }
            for (ResourceLocation dependency : dependencies) {
                dependents.computeIfAbsent(dependency, k -> new ArrayList<>()).add(consumer.recipeId());
                unmet.merge(consumer.recipeId(), 1, Integer::sum);
            }
        }

        List<PlanEntry> ordered = new ArrayList<>(entries.size());
        Set<ResourceLocation> placed = new HashSet<>();
        boolean progress = true;
        while (ordered.size() < entries.size() && progress) {
            progress = false;
            for (PlanEntry entry : entries) {
                if (placed.contains(entry.recipeId()) || unmet.get(entry.recipeId()) > 0) {
                    continue;
                }
                ordered.add(entry);
                placed.add(entry.recipeId());
                progress = true;
                for (ResourceLocation dependent : dependents.getOrDefault(entry.recipeId(), List.of())) {
                    unmet.merge(dependent, -1, Integer::sum);
                }
            }
        }
        if (ordered.size() < entries.size()) {
            return null;
        }

        if (!groupByDestination) {
            return ordered;
        }

        List<PlanEntry> grouped = new ArrayList<>(entries.size());
        for (int rank = 0; rank < 4; rank++) {
            List<PlanEntry> group = new ArrayList<>();
            for (PlanEntry entry : ordered) {
                if (destinationRank(recipes.get(entry.recipeId()), views.get(entry.recipeId())) == rank) {
                    group.add(entry);
                }
            }
            if (rank == 1) {
                // MACHINE:* batches in alphabetical machine-type order; the
                // stable sort keeps topological order within one type.
                group.sort(Comparator.comparing(e -> machineType(recipes.get(e.recipeId()))));
            }
            grouped.addAll(group);
        }
        return grouped;
    }

    /**
     * The destination class of a recipe, mirroring {@code tree.Destination}
     * (DESIGN.md §17.7.2) without importing it: 0 = ASSEMBLER,
     * 1 = MACHINE:*, 2 = STONECUTTER, 3 = SMITHING. A crafting recipe
     * that does not fit a 3×3 grid encodes as a processing pattern and
     * therefore lands in a MACHINE batch.
     */
    private static int destinationRank(Recipe<?> recipe, RecipeView view) {
        if (recipe instanceof StonecutterRecipe) {
            return 2;
        }
        if (recipe instanceof SmithingRecipe) {
            return 3;
        }
        if (recipe instanceof CraftingRecipe) {
            // A shaped recipe fits a 3x3 crafting grid only if its
            // dimensions are at most 3x3 — measuring area lets a 4x2
            // (area 8) through and collides in the stride mapping.
            // Shapeless recipes have no grid (width 0) and fit if they
            // carry at most nine ingredients.
            int w = view.gridWidth();
            int h = view.gridHeight();
            boolean fits = (w == 0 && view.inputs().size() <= 9) || (w <= 3 && h <= 3);
            return fits ? 0 : 1;
        }
        return 1;
    }

    private static String machineType(Recipe<?> recipe) {
        ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        // The path (not the full key) matches SourceSelector.destination, so
        // the MACHINE batch sort order is consistent with the client display.
        return key != null ? key.getPath() : recipe.getType().toString();
    }

    /**
     * The root goal: the primary output item of the echoed input pattern,
     * decoded through AE2's public pattern-details API
     * ({@code PatternDetailsHelper.decodePattern}). The decoder is
     * registered by AE2 itself when {@code PatternDetailsHelper} loads, so
     * no mod-internal pattern classes are referenced here; the result is
     * read off the public {@link IPatternDetails} interface.
     */
    @Nullable
    private static Item rootGoalItem(ItemStack pattern, Level level) {
        if (!PatternDetailsHelper.isEncodedPattern(pattern)) {
            return null;
        }
        IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, level);
        if (details == null) {
            return null;
        }
        GenericStack primary = details.getPrimaryOutput();
        if (primary == null || !(primary.what() instanceof AEItemKey itemKey)) {
            return null;
        }
        return itemKey.getItem();
    }

    private static boolean produces(RecipeView view, AEKey claimed) {
        if (!(claimed instanceof AEItemKey itemKey)) {
            return false;
        }
        for (GenericStack output : view.outputs()) {
            if (output.what() instanceof AEItemKey out && out.getItem() == itemKey.getItem()) {
                return true;
            }
        }
        return false;
    }

    private static Result reject(String reason) {
        return new Result(false, reason, List.of(), Map.of());
    }

    private static List<IngredientView> toIngredientViews(List<Ingredient> ingredients) {
        List<IngredientView> views = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            List<Item> candidates = new ArrayList<>();
            for (ItemStack stack : ingredient.getItems()) {
                candidates.add(stack.getItem());
            }
            views.add(new IngredientView(ingredient, List.copyOf(candidates)));
        }
        return List.copyOf(views);
    }

    private static GenericStack stack(ItemStack result) {
        // The pattern must encode the real ratio (DESIGN.md §1.5): a block
        // recipe yields 9, not 1.
        return new GenericStack(AEItemKey.of(result), result.getCount());
    }

    /**
     * The template/base/addition inputs of a smithing recipe, in that
     * order. The vanilla API does not expose them, so the vanilla
     * implementation's fields are read reflectively.
     */
    @Nullable
    private static List<Ingredient> smithingInputs(SmithingRecipe smithing) {
        if (smithing instanceof SmithingTransformRecipe transform) {
            try {
                Field template = SmithingTransformRecipe.class.getDeclaredField("template");
                Field base = SmithingTransformRecipe.class.getDeclaredField("base");
                Field addition = SmithingTransformRecipe.class.getDeclaredField("addition");
                template.setAccessible(true);
                base.setAccessible(true);
                addition.setAccessible(true);
                return List.of(
                        (Ingredient) template.get(transform),
                        (Ingredient) base.get(transform),
                        (Ingredient) addition.get(transform));
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("rpp print: could not read smithing recipe inputs", e);
                return null;
            }
        }
        return null;
    }
}
