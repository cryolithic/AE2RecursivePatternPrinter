package dev.cryolithic.rpp.init;

import dev.cryolithic.rpp.RppMod;
import dev.cryolithic.rpp.block.PatternPrinterBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block registrations. */
public final class RppBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RppMod.MOD_ID);

    /** The Recursive Pattern Printer block (DESIGN.md §10.1). */
    public static final DeferredBlock<PatternPrinterBlock> PATTERN_PRINTER = BLOCKS.registerBlock("pattern_printer",
            PatternPrinterBlock::new,
            BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.STONE));

    private RppBlocks() {
    }
}
