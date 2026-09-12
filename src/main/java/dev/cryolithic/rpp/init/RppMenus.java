package dev.cryolithic.rpp.init;

import dev.cryolithic.rpp.RppMod;
import dev.cryolithic.rpp.inventory.PatternPrinterMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Menu type registrations. */
public final class RppMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, RppMod.MOD_ID);

    /** The Recursive Pattern Printer menu (DESIGN.md §10.1). */
    public static final DeferredHolder<MenuType<?>, MenuType<PatternPrinterMenu>> PATTERN_PRINTER =
            MENUS.register("pattern_printer",
                    () -> new MenuType<>(PatternPrinterMenu::new, FeatureFlags.DEFAULT_FLAGS));

    private RppMenus() {
    }
}
