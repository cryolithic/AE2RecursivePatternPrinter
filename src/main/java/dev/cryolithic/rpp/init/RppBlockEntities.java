package dev.cryolithic.rpp.init;

import dev.cryolithic.rpp.RppMod;
import dev.cryolithic.rpp.block.PatternPrinterBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Block entity type registrations.
 *
 * <p>Also registers the {@code Capabilities.ItemHandler.BLOCK} provider for
 * the printer (DESIGN.md §10.1). The listener is declared here with
 * {@code @EventBusSubscriber} rather than in the mod constructor, which is
 * frozen.</p>
 */
@EventBusSubscriber(modid = RppMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class RppBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RppMod.MOD_ID);

    /** The Recursive Pattern Printer block entity, bound to the block. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternPrinterBlockEntity>> PATTERN_PRINTER =
            BLOCK_ENTITIES.register("pattern_printer",
                    () -> BlockEntityType.Builder.of(PatternPrinterBlockEntity::new, RppBlocks.PATTERN_PRINTER.get()).build(null));

    private RppBlockEntities() {
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, PATTERN_PRINTER.get(),
                (printer, side) -> printer.getCapabilityInventory());
    }
}
