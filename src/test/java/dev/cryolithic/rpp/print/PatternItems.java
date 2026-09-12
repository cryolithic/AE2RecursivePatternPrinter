package dev.cryolithic.rpp.print;

import appeng.api.crafting.IPatternDetails;
import appeng.api.ids.AEItemIds;
import appeng.crafting.pattern.EncodedPatternItem;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Test substrate for the print pipeline: registers AE2's pattern items under
 * their real ids so that {@code PatternDetailsHelper}'s encoders — which build
 * their result stacks from AE2's deferred item definitions — work in a bare
 * JVM. The deferred definitions bind to whatever is registered under the AE2
 * item key, and the registered items are real {@link EncodedPatternItem}s so
 * {@code isEncodedPattern} passes.
 */
final class PatternItems {
    private PatternItems() {
    }

    /** Idempotent; call after {@link RecipeIndexFixture#boot()} (the item registry must be unfrozen). */
    static void register() {
        if (BuiltInRegistries.ITEM.containsKey(AEItemIds.CRAFTING_PATTERN)) {
            return;
        }
        Registry.register(BuiltInRegistries.ITEM, AEItemIds.CRAFTING_PATTERN, patternItem());
        Registry.register(BuiltInRegistries.ITEM, AEItemIds.PROCESSING_PATTERN, patternItem());
        Registry.register(BuiltInRegistries.ITEM, AEItemIds.STONECUTTING_PATTERN, patternItem());
        Registry.register(BuiltInRegistries.ITEM, AEItemIds.SMITHING_TABLE_PATTERN, patternItem());
    }

    private static EncodedPatternItem<IPatternDetails> patternItem() {
        // The decoder is never invoked by the tests: they encode patterns and
        // read the data components, they never decode.
        return new EncodedPatternItem<>(
                new Item.Properties().stacksTo(1),
                (key, level) -> null,
                (stack, level, e, flag) -> null);
    }
}
