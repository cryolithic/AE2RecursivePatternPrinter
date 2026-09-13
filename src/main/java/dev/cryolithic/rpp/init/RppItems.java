package dev.cryolithic.rpp.init;

import dev.cryolithic.rpp.RppMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item registrations. */
public final class RppItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RppMod.MOD_ID);

    /** Block item of the Recursive Pattern Printer. */
    public static final DeferredItem<BlockItem> PATTERN_PRINTER =
            ITEMS.registerSimpleBlockItem("pattern_printer", () -> RppBlocks.PATTERN_PRINTER.get());

    /** Creative tab containing the printer block. */
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RppMod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> PATTERN_PRINTER_TAB =
            CREATIVE_MODE_TABS.register("pattern_printer", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rpp.pattern_printer"))
                    .icon(() -> PATTERN_PRINTER.get().asItem().getDefaultInstance())
                    .displayItems((params, output) -> output.accept(PATTERN_PRINTER.get()))
                    .build());

    private RppItems() {
    }
}
