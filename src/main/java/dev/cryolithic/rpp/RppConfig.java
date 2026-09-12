package dev.cryolithic.rpp;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge config spec for the mod. {@code rpp-client.toml} holds the tree
 * caps and display options, {@code rpp-common.toml} the print limits and
 * trust settings. Every key in DESIGN.md §12 and §8.3 is exposed here with a
 * typed accessor.
 */
public final class RppConfig {
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec COMMON_SPEC;

    // --- client: tree building and display ---
    public static final ModConfigSpec.IntValue INITIAL_DEPTH;
    public static final ModConfigSpec.BooleanValue REQUIRE_TRUSTED_RECIPES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PREFERRED_MODS;
    public static final ModConfigSpec.DoubleValue MIN_ROUND_TRIP_EFFICIENCY;
    public static final ModConfigSpec.BooleanValue ALTERNATES_CONSERVATIVE;
    public static final ModConfigSpec.IntValue MAX_SOURCES_PER_ITEM;
    public static final ModConfigSpec.IntValue MAX_SOURCES_PER_DESTINATION;
    public static final ModConfigSpec.BooleanValue WARN_ON_COSTLY_COLLISIONS;
    public static final ModConfigSpec.BooleanValue GROUP_PRINT_BY_DESTINATION;
    public static final ModConfigSpec.BooleanValue STICKY_CHOICES;
    public static final ModConfigSpec.BooleanValue ALWAYS_REVIEW_BEFORE_PRINT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLACKLISTED_RECIPE_TYPES;

    // --- client: the five §8.3 caps ---
    public static final ModConfigSpec.IntValue MAX_DEPTH;
    public static final ModConfigSpec.IntValue MAX_RECIPES_PER_ITEM;
    public static final ModConfigSpec.IntValue MAX_CANDIDATES_PER_INGREDIENT;
    public static final ModConfigSpec.IntValue MAX_NODES_PER_EXPANSION;
    public static final ModConfigSpec.IntValue MAX_TOTAL_NODES;

    // --- common: print limits and trust ---
    public static final ModConfigSpec.IntValue MAX_PRINT_BATCH;
    public static final ModConfigSpec.BooleanValue ALLOW_SUBSTITUTIONS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Recipe tree: depth, source selection and caps").push("tree");

        INITIAL_DEPTH = builder
                .comment("Levels of the recipe tree expanded when the GUI opens.")
                .defineInRange("initialDepth", 2, 1, 16);

        REQUIRE_TRUSTED_RECIPES = builder
                .comment("Hide heuristically-extracted (untrusted) recipes entirely.")
                .define("requireTrustedRecipes", false);

        PREFERRED_MODS = builder
                .comment("Ordered list of namespaces the player actually builds with; "
                        + "biases source ranking. The most useful knob in the mod.")
                .defineList("preferredMods",
                        List.of("minecraft", "ae2"),
                        () -> "minecraft",
                        o -> o instanceof String);
        MIN_ROUND_TRIP_EFFICIENCY = builder
                .comment("A reversal at or above this round-trip efficiency is a lossless "
                        + "storage form and is offered; below it is recycling.")
                .defineInRange("minRoundTripEfficiency", 0.95, 0.0, 1.0);

        ALTERNATES_CONSERVATIVE = builder
                .comment("Restrict ALTERNATE to lossless reversals, higher-yield recipes "
                        + "and preferred namespaces.")
                .define("alternatesConservative", false);

        MAX_SOURCES_PER_ITEM = builder
                .comment("Cap on recipes auto-checked for one item. Manual selection is "
                        + "uncapped but warns.")
                .defineInRange("maxSourcesPerItem", 3, 1, 16);

        MAX_SOURCES_PER_DESTINATION = builder
                .comment("Recipes auto-checked within one destination class. Raising it "
                        + "means AE2 orders them arbitrarily.")
                .defineInRange("maxSourcesPerDestination", 1, 1, 8);

        WARN_ON_COSTLY_COLLISIONS = builder
                .comment("Flag same-destination sources that differ materially in cost.")
                .define("warnOnCostlyCollisions", true);

        GROUP_PRINT_BY_DESTINATION = builder
                .comment("Write patterns to the output inventory in destination batches.")
                .define("groupPrintByDestination", true);

        STICKY_CHOICES = builder
                .comment("Remember source sets across trees, keyed by goal item.")
                .define("stickyChoices", true);

        ALWAYS_REVIEW_BEFORE_PRINT = builder
                .comment("Show the pre-print review even when nothing is flagged.")
                .define("alwaysReviewBeforePrint", true);
        BLACKLISTED_RECIPE_TYPES = builder
                .comment("Recipe types never indexed, e.g. \"minecraft:crafting\".")
                .defineList("blacklistedRecipeTypes",
                        List.<String>of(),
                        () -> "minecraft",
                        o -> o instanceof String);

        builder.pop();

        builder.comment("Tree caps (DESIGN.md §8.3); enforced during expansion").push("caps");

        MAX_DEPTH = builder
                .comment("Item nodes beyond this depth are marked CAPPED, not expanded.")
                .defineInRange("maxDepth", 8, 1, 32);

        MAX_RECIPES_PER_ITEM = builder
                .comment("Keep top N recipes per item by ranking, append a \"+K more\" marker.")
                .defineInRange("maxRecipesPerItem", 6, 1, 64);

        MAX_CANDIDATES_PER_INGREDIENT = builder
                .comment("Keep top N candidates per ingredient node; the node is marked capped.")
                .defineInRange("maxCandidatesPerIngredient", 8, 1, 64);

        MAX_NODES_PER_EXPANSION = builder
                .comment("Per-click cap; a breach aborts that one expansion and marks it CAPPED.")
                .defineInRange("maxNodesPerExpansion", 4000, 100, 1_000_000);

        MAX_TOTAL_NODES = builder
                .comment("Session cap; further expansion is refused and the GUI shows a banner.")
                .defineInRange("maxTotalNodes", 100_000, 1000, 10_000_000);

        builder.pop();
        CLIENT_SPEC = builder.build();

        ModConfigSpec.Builder common = new ModConfigSpec.Builder();

        common.comment("Print limits and trust").push("print");

        MAX_PRINT_BATCH = common
                .comment("Server-side cap on patterns per print request.")
                .defineInRange("maxPrintBatch", 128, 1, 4096);

        ALLOW_SUBSTITUTIONS = common
                .comment("Default for the GUI substitution toggle, applied globally to a batch.")
                .define("allowSubstitutions", true);

        common.pop();
        COMMON_SPEC = common.build();
    }

    private RppConfig() {
    }

    // --- typed accessors ---

    public static int initialDepth() {
        return INITIAL_DEPTH.get();
    }

    public static boolean requireTrustedRecipes() {
        return REQUIRE_TRUSTED_RECIPES.get();
    }

    public static List<? extends String> preferredMods() {
        return PREFERRED_MODS.get();
    }

    public static double minRoundTripEfficiency() {
        return MIN_ROUND_TRIP_EFFICIENCY.get();
    }

    public static boolean alternatesConservative() {
        return ALTERNATES_CONSERVATIVE.get();
    }

    public static int maxSourcesPerItem() {
        return MAX_SOURCES_PER_ITEM.get();
    }

    public static int maxSourcesPerDestination() {
        return MAX_SOURCES_PER_DESTINATION.get();
    }

    public static boolean warnOnCostlyCollisions() {
        return WARN_ON_COSTLY_COLLISIONS.get();
    }

    public static boolean groupPrintByDestination() {
        return GROUP_PRINT_BY_DESTINATION.get();
    }

    public static boolean stickyChoices() {
        return STICKY_CHOICES.get();
    }

    public static boolean alwaysReviewBeforePrint() {
        return ALWAYS_REVIEW_BEFORE_PRINT.get();
    }

    public static List<? extends String> blacklistedRecipeTypes() {
        return BLACKLISTED_RECIPE_TYPES.get();
    }

    public static int maxDepth() {
        return MAX_DEPTH.get();
    }

    public static int maxRecipesPerItem() {
        return MAX_RECIPES_PER_ITEM.get();
    }

    public static int maxCandidatesPerIngredient() {
        return MAX_CANDIDATES_PER_INGREDIENT.get();
    }

    public static int maxNodesPerExpansion() {
        return MAX_NODES_PER_EXPANSION.get();
    }

    public static int maxTotalNodes() {
        return MAX_TOTAL_NODES.get();
    }

    public static int maxPrintBatch() {
        return MAX_PRINT_BATCH.get();
    }

    public static boolean allowSubstitutions() {
        return ALLOW_SUBSTITUTIONS.get();
    }
}
