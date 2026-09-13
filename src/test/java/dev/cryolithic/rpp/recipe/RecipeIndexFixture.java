package dev.cryolithic.rpp.recipe;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.EncodedCraftingPattern;
import appeng.crafting.pattern.EncodedProcessingPattern;
import appeng.crafting.pattern.EncodedSmithingTablePattern;
import appeng.crafting.pattern.EncodedStonecuttingPattern;
import com.mojang.serialization.MapCodec;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.LoadingModList;
import org.apache.logging.log4j.LogManager;

/**
 * Shared test substrate for the recipe and tree milestones: builds a
 * {@link RecipeIndex} (and the underlying {@link RecipeView}s) from
 * hand-crafted data with no Minecraft runtime.
 *
 * <p>Boots just enough of vanilla to run in a bare JVM
 * ({@link #boot()}, idempotent, once per JVM):
 * <ol>
 *   <li>{@code LoadingModList.of(empty)} — {@code FeatureFlags.<clinit>}
 *       (pulled in by {@code Item.Properties}) asks FML for the mod list to
 *       load modded feature flags; without an FML boot it NPEs.</li>
 *   <li>{@code SharedConstants.tryDetectVersion()} —
 *       {@code DataFixers.<clinit>} (pulled in by the bootstrap) requires the
 *       game version; detection falls back to the built-in 1.21.1.</li>
 *   <li>{@code Bootstrap.bootStrap()} — {@code BuiltInRegistries.<clinit>}
 *       refuses to run before the vanilla bootstrap. This is the
 *       dedicated-server entry point: no world, no client. It needs
 *       {@code assets/minecraft/lang/en_us.json} on the classpath (provided
 *       by the test resources).</li>
 *   <li>Unfreeze {@code BuiltInRegistries.ITEM} so test items can be
 *       registered after the bootstrap froze the registries.</li>
 * </ol>
 *
 * <p>Test items are registered into {@code BuiltInRegistries.ITEM} under the
 * namespace {@code rpp}; item names must be unique per JVM because the item
 * registry is a JVM-wide singleton. The recipe list and recipe manager are
 * per-fixture-instance, so each test gets a fresh {@code new
 * RecipeIndexFixture()} and its own index.
 */
public final class RecipeIndexFixture {
    private static boolean booted;

    private final List<RecipeHolder<?>> recipes = new ArrayList<>();

    /**
     * Shared capture of the test JVM's {@code System.err}. log4j2's
     * console appender binds to the {@code System.err} stream reference at
     * the moment its configuration is first loaded, so this redirect must
     * happen before log4j2 initializes — i.e. before any
     * {@code RecipeIndex.build} call in any test class. This fixture's
     * class initializer runs before the first {@code buildIndex()} of every
     * test (each test constructs the fixture first), which makes the
     * binding deterministic regardless of test-class order.
     */
    private static final ByteArrayOutputStream CONSOLE = new ByteArrayOutputStream();

    static {
        System.setErr(new PrintStream(CONSOLE, true, StandardCharsets.UTF_8));
        // Force log4j2 to load its configuration now (log4j2-test.xml: root
        // INFO + console appender) so the appender binds to CONSOLE.
        LogManager.getLogger("rpp.console-warmup")
                .log(org.apache.logging.log4j.Level.INFO, "rpp test console warmup");
    }

    /** Resets the shared console capture. */
    public static void resetConsole() {
        CONSOLE.reset();
    }

    /** Returns the lines captured on {@code System.err} since the last reset. */
    public static List<String> consoleLines() {
        return CONSOLE.toString(StandardCharsets.UTF_8).lines().toList();
    }
    private RecipeManager manager;

    /** Boots the vanilla registries once per JVM; idempotent. */
    public static void boot() {
        if (booted) {
            return;
        }
        booted = true;
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ((MappedRegistry<Item>) BuiltInRegistries.ITEM).unfreeze();
        registerTestPatternDecoder();
    }

    private static boolean testDecoderRegistered;

    /**
     * Registers a bare-JVM pattern decoder. AE2's own decoder
     * (auto-registered when {@code PatternDetailsHelper} loads) requires a
     * non-null {@link Level}; a bare JVM has none. This decoder handles
     * the null-Level case by reading the encoded pattern's data component
     * directly — test-only internal access, consistent with the other
     * tests that read {@code Encoded*Pattern} observables. Production code
     * never sees it: on a real server the Level is non-null and AE2's
     * decoder answers first.
     */
    private static void registerTestPatternDecoder() {
        if (testDecoderRegistered) {
            return;
        }
        testDecoderRegistered = true;
        PatternDetailsHelper.registerDecoder(new IPatternDetailsDecoder() {
            @Override
            public boolean isEncodedPattern(ItemStack stack) {
                return stack.get(AEComponents.ENCODED_CRAFTING_PATTERN) != null
                        || stack.get(AEComponents.ENCODED_PROCESSING_PATTERN) != null
                        || stack.get(AEComponents.ENCODED_STONECUTTING_PATTERN) != null
                        || stack.get(AEComponents.ENCODED_SMITHING_TABLE_PATTERN) != null;
            }

            @Override
            public IPatternDetails decodePattern(AEItemKey key, Level level) {
                if (key.get(AEComponents.ENCODED_CRAFTING_PATTERN) instanceof EncodedCraftingPattern c
                        && !c.result().isEmpty()) {
                    return outputDetails(c.result());
                }
                if (key.get(AEComponents.ENCODED_PROCESSING_PATTERN) instanceof EncodedProcessingPattern p
                        && !p.sparseOutputs().isEmpty()) {
                    return outputDetails(p.sparseOutputs().get(0));
                }
                if (key.get(AEComponents.ENCODED_STONECUTTING_PATTERN) instanceof EncodedStonecuttingPattern s
                        && !s.output().isEmpty()) {
                    return outputDetails(s.output());
                }
                if (key.get(AEComponents.ENCODED_SMITHING_TABLE_PATTERN) instanceof EncodedSmithingTablePattern s
                        && !s.resultItem().isEmpty()) {
                    return outputDetails(s.resultItem());
                }
                return null;
            }
        });
    }

    private static IPatternDetails outputDetails(ItemStack result) {
        return outputDetails(new GenericStack(AEItemKey.of(result), result.getCount()));
    }

    private static IPatternDetails outputDetails(GenericStack primary) {
        return new IPatternDetails() {
            @Override
            public AEItemKey getDefinition() {
                throw new UnsupportedOperationException("test stub");
            }

            @Override
            public IInput[] getInputs() {
                throw new UnsupportedOperationException("test stub");
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(primary);
            }
        };
    }

    /** Registers and returns a test item named {@code rpp:<name>}. */
    public Item item(String name) {
        boot();
        return Registry.register(BuiltInRegistries.ITEM, "rpp:" + name, new Item(new Item.Properties()));
    }

    /** Registers and returns a test item named {@code <namespace>:<name>}. */
    public Item item(String namespace, String name) {
        boot();
        return Registry.register(BuiltInRegistries.ITEM, namespace + ":" + name, new Item(new Item.Properties()));
    }

    public ItemStack stack(Item item) {
        return new ItemStack(item);
    }

    public ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    /**
     * Creates a tag with the given members (the candidate list) and returns
     * its key. The tag is bound into the item registry so
     * {@code Ingredient.of(tag)} resolves the members.
     */
    public TagKey<Item> tag(String name, Item... members) {
        boot();
        TagKey<Item> key = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("rpp", name));
        List<Holder<Item>> holders = new ArrayList<>();
        for (Item member : members) {
            holders.add(BuiltInRegistries.ITEM.getHolderOrThrow(
                    ResourceKey.create(BuiltInRegistries.ITEM.key(), BuiltInRegistries.ITEM.getKey(member))));
        }
        BuiltInRegistries.ITEM.bindTags(Map.of(key, List.copyOf(holders)));
        return key;
    }

    /** An unregistered (modded-style) recipe type for generic recipes. */
    public RecipeType<?> type(String name) {
        boot();
        return RecipeType.simple(ResourceLocation.fromNamespaceAndPath("rpp", name));
    }

    /** A shaped recipe; {@code pattern} maps pattern chars to ingredients, one row per line. */
    public RecipeHolder<?> shaped(String name, Item output, int count, Map<Character, Ingredient> pattern, String... rows) {
        boot();
        ShapedRecipe recipe = new ShapedRecipe(name, CraftingBookCategory.EQUIPMENT,
                ShapedRecipePattern.of(pattern, rows), new ItemStack(output, count), true);
        return add(new RecipeHolder<>(id(name), recipe));
    }

    /**
     * A shaped recipe with a genuine interior blank slot: the rows express
     * the blank slot as a space (e.g. "###", "# #", "###" is chest-like).
     * {@code ShapedRecipePattern.of} fills a space with
     * {@code Ingredient.EMPTY}, so the extracted view carries a
     * zero-candidate slot — the case the plain {@code shaped} helper
     * cannot express, because its pattern map never holds a blank entry.
     */
    public RecipeHolder<?> shapedGapped(String name, Item output, int count, Map<Character, Ingredient> pattern, String... rows) {
        RecipeHolder<?> holder = shaped(name, output, count, pattern, rows);
        boolean blank = false;
        for (Ingredient ingredient : ((ShapedRecipe) holder.value()).getIngredients()) {
            if (ingredient.isEmpty()) {
                blank = true;
                break;
            }
        }
        if (!blank) {
            throw new IllegalArgumentException("shapedGapped " + name + " has no blank slot");
        }
        return holder;
    }

    /** A shapeless recipe. */
    public RecipeHolder<?> shapeless(String name, Item output, int count, Ingredient... inputs) {
        boot();
        ShapelessRecipe recipe = new ShapelessRecipe(name, CraftingBookCategory.EQUIPMENT,
                new ItemStack(output, count), NonNullList.of(Ingredient.EMPTY, inputs));
        return add(new RecipeHolder<>(id(name), recipe));
    }

    /**
     * A generic (non-crafting) recipe under a custom type. The {@code result}
     * supplier may return an empty stack or throw, to exercise the extraction
     * failure path.
     */
    public RecipeHolder<?> generic(String name, RecipeType<?> type, Supplier<ItemStack> result, Ingredient... inputs) {
        boot();
        List<Ingredient> list = List.of(inputs);
        Recipe<?> recipe = new Recipe<>() {
            @Override
            public boolean matches(RecipeInput input, Level level) {
                return false;
            }

            @Override
            public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean canCraftInDimensions(int width, int height) {
                return false;
            }

            @Override
            public ItemStack getResultItem(HolderLookup.Provider registries) {
                return result.get();
            }

            @Override
            public RecipeSerializer<?> getSerializer() {
                return Serializer.INSTANCE;
            }

            @Override
            public RecipeType<?> getType() {
                return type;
            }

            @Override
            public NonNullList<Ingredient> getIngredients() {
                NonNullList<Ingredient> ingredients = NonNullList.create();
                for (Ingredient ingredient : list) {
                    ingredients.add(ingredient);
                }
                return ingredients;
            }
        };
        return add(new RecipeHolder<>(id(name), recipe));
    }

    /**
     * A multi-output recipe whose primary output is NOT the goal: the goal
     * is a secondary output, so the index buckets it under the goal but
     * {@code outputs[0]} is a different item (a byproduct). Uses a
     * {@link RecipeAdapter} on a unique per-recipe type so extraction keeps
     * all outputs.
     */
    public RecipeHolder<?> byproduct(String recipeId, List<ItemStack> outputs, Ingredient... inputs) {
        boot();
        RecipeType<?> type = RecipeType.simple(ResourceLocation.fromNamespaceAndPath("rpp", recipeId + "_type"));
        List<Ingredient> list = List.of(inputs);
        Recipe<?> recipe = new Recipe<>() {
            @Override
            public boolean matches(RecipeInput input, Level level) {
                return false;
            }

            @Override
            public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean canCraftInDimensions(int width, int height) {
                return false;
            }

            @Override
            public ItemStack getResultItem(HolderLookup.Provider registries) {
                return outputs.isEmpty() ? ItemStack.EMPTY : outputs.get(0);
            }

            @Override
            public RecipeSerializer<?> getSerializer() {
                return Serializer.INSTANCE;
            }

            @Override
            public RecipeType<?> getType() {
                return type;
            }
        };
        RecipeAdapter adapter = new RecipeAdapter() {
            @Override
            public RecipeType<?> type() {
                return type;
            }

            @Override
            public RecipeView view(RecipeHolder<?> holder, HolderLookup.Provider registries) {
                List<IngredientView> views = new ArrayList<>();
                for (Ingredient ingredient : list) {
                    List<Item> candidates = new ArrayList<>();
                    for (ItemStack stack : ingredient.getItems()) {
                        candidates.add(stack.getItem());
                    }
                    views.add(new IngredientView(ingredient, candidates));
                }
                List<GenericStack> stacks = new ArrayList<>();
                for (ItemStack stack : outputs) {
                    stacks.add(new GenericStack(AEItemKey.of(stack.getItem()), stack.getCount()));
                }
                return new RecipeView(id(recipeId), type, views, stacks, 0, 0, true);
            }
        };
        RecipeAdapters.register(adapter);
        return add(new RecipeHolder<>(id(recipeId), recipe));
    }

    /**
     * A trusted single-output machine recipe under a custom type (e.g. a
     * furnace or a modded processing machine), extracted via a
     * {@link RecipeAdapter} so the type is preserved.
     */
    public RecipeHolder<?> machine(String recipeId, RecipeType<?> type, ItemStack output, Ingredient... inputs) {
        boot();
        RecipeType<?> t = type;
        List<Ingredient> list = List.of(inputs);
        Recipe<?> recipe = new Recipe<>() {
            @Override
            public boolean matches(RecipeInput input, Level level) {
                return false;
            }

            @Override
            public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean canCraftInDimensions(int width, int height) {
                return false;
            }

            @Override
            public ItemStack getResultItem(HolderLookup.Provider registries) {
                return output;
            }

            @Override
            public RecipeSerializer<?> getSerializer() {
                return Serializer.INSTANCE;
            }

            @Override
            public RecipeType<?> getType() {
                return t;
            }
        };
        RecipeAdapter adapter = new RecipeAdapter() {
            @Override
            public RecipeType<?> type() {
                return t;
            }

            @Override
            public RecipeView view(RecipeHolder<?> holder, HolderLookup.Provider registries) {
                List<IngredientView> views = new ArrayList<>();
                for (Ingredient ingredient : list) {
                    List<Item> candidates = new ArrayList<>();
                    for (ItemStack stack : ingredient.getItems()) {
                        candidates.add(stack.getItem());
                    }
                    views.add(new IngredientView(ingredient, candidates));
                }
                return new RecipeView(id(recipeId), t, views,
                        List.of(new GenericStack(AEItemKey.of(output.getItem()), output.getCount())), 0, 0, true);
            }
        };
        RecipeAdapters.register(adapter);
        return add(new RecipeHolder<>(id(recipeId), recipe));
    }

    /** The shared recipe manager for this fixture, holding all added recipes. */
    public RecipeManager manager() {
        boot();
        if (manager == null) {
            manager = new RecipeManager(provider());
        }
        manager.replaceRecipes(recipes);
        return manager;
    }

    /** The registry provider built from the item registry. */
    public HolderLookup.Provider provider() {
        boot();
        return HolderLookup.Provider.create(Stream.of(BuiltInRegistries.ITEM.asLookup()));
    }

    /** All recipes added so far, in addition order. */
    public List<RecipeHolder<?>> recipes() {
        return List.copyOf(recipes);
    }

    /** Builds the index over all recipes added so far. */
    public RecipeIndex buildIndex() {
        return buildIndex(Set.of());
    }

    /** Builds the index with an explicit blacklist (see {@link RecipeIndex#build}). */
    public RecipeIndex buildIndex(Collection<String> blacklistedTypes) {
        return RecipeIndex.build(manager(), provider(), blacklistedTypes);
    }

    /** Builds the index with an explicit blacklist and trust filter (see {@link RecipeIndex#build}). */
    public RecipeIndex buildIndex(Collection<String> blacklistedTypes, boolean requireTrusted) {
        return RecipeIndex.build(manager(), provider(), blacklistedTypes,
                RecipeIndex.resolveIngredientItems(manager(), blacklistedTypes), requireTrusted);
    }

    private RecipeHolder<?> add(RecipeHolder<?> holder) {
        recipes.add(holder);
        return holder;
    }

    private static ResourceLocation id(String name) {
        if (name.indexOf(':') >= 0) {
            return parse(name);
        }
        return ResourceLocation.fromNamespaceAndPath("rpp", name);
    }

    private static ResourceLocation parse(String fullId) {
        int colon = fullId.indexOf(':');
        return ResourceLocation.fromNamespaceAndPath(fullId.substring(0, colon), fullId.substring(colon + 1));
    }

    /** Shared no-op serializer; the fixture's generic recipes are never (de)serialized. */
    private static final class Serializer implements RecipeSerializer<Recipe<?>> {
        static final Serializer INSTANCE = new Serializer();

        @Override
        public MapCodec<Recipe<?>> codec() {
            throw new UnsupportedOperationException("fixture recipes are never serialized");
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> streamCodec() {
            throw new UnsupportedOperationException("fixture recipes are never serialized");
        }
    }
}
