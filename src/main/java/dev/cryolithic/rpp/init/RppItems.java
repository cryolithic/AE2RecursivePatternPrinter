package dev.cryolithic.rpp.init;

import dev.cryolithic.rpp.RppMod;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item registrations. */
public final class RppItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RppMod.MOD_ID);

    /** Block item of the Recursive Pattern Printer. */
    public static final DeferredItem<BlockItem> PATTERN_PRINTER =
            ITEMS.registerSimpleBlockItem("pattern_printer", () -> RppBlocks.PATTERN_PRINTER.get());

    private RppItems() {
    }
}
