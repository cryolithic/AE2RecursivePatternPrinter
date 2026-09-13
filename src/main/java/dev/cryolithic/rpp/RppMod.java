package dev.cryolithic.rpp;

import dev.cryolithic.rpp.init.RppBlockEntities;
import dev.cryolithic.rpp.init.RppBlocks;
import dev.cryolithic.rpp.init.RppItems;
import dev.cryolithic.rpp.init.RppMenus;
import dev.cryolithic.rpp.init.RppNetwork;
import dev.cryolithic.rpp.recipe.RecipeIndexBootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Mod entrypoint. Binds every {@code DeferredRegister} and the config specs.
 *
 * <p>This class is done: later milestones add entries to the {@code init/}
 * classes, never to this one.</p>
 */
@Mod(RppMod.MOD_ID)
public final class RppMod {
    public static final String MOD_ID = "rpp";

    public RppMod(IEventBus modBus, ModContainer container) {
        RppBlocks.BLOCKS.register(modBus);
        RppItems.ITEMS.register(modBus);
        RppItems.CREATIVE_MODE_TABS.register(modBus);
        RppBlockEntities.BLOCK_ENTITIES.register(modBus);
        RppMenus.MENUS.register(modBus);
        RppNetwork.register(modBus);
        RecipeIndexBootstrap.init(modBus);

        container.registerConfig(ModConfig.Type.CLIENT, RppConfig.CLIENT_SPEC);
        container.registerConfig(ModConfig.Type.COMMON, RppConfig.COMMON_SPEC);
    }
}
