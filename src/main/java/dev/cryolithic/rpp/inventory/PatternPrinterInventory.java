package dev.cryolithic.rpp.inventory;

import appeng.api.ids.AEItemIds;
import dev.cryolithic.rpp.block.PatternPrinterBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Side-aware {@link IItemHandler} view of the printer's inventory, exposed
 * as {@code Capabilities.ItemHandler.BLOCK} (DESIGN.md §10.1).
 *
 * <p>Automation never sees the input slot: insert routes to the blank
 * pattern slot only, extract pulls from the output slots only. The input
 * pattern is reachable exclusively through the menu.</p>
 *
 * <p>The wrapper exposes 28 slots: slot 0 is the blank pattern slot
 * (insert-allowed, extract-blocked), slots 1-27 are the output slots
 * (extract-allowed, insert-blocked).</p>
 */
public final class PatternPrinterInventory implements IItemHandler {
    /** Wrapper slot mapped to the block entity's blank pattern slot. */
    public static final int SLOT_BLANKS = 0;
    /** First wrapper slot mapped to an output slot (block entity slot 2). */
    public static final int SLOT_OUTPUT_START = 1;

    private final ItemStackHandler delegate;

    public PatternPrinterInventory(ItemStackHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public int getSlots() {
        return delegate.getSlots() - 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return delegate.getStackInSlot(blockEntitySlot(slot));
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (slot != SLOT_BLANKS || !isItemValid(slot, stack)) {
            return stack;
        }
        return delegate.insertItem(PatternPrinterBlockEntity.SLOT_BLANKS, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot == SLOT_BLANKS) {
            return ItemStack.EMPTY;
        }
        return delegate.extractItem(blockEntitySlot(slot), amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        // Report the delegate's actual cap so capacity simulation matches what
        // insertItem will accept (the handler uses the default 64).
        return slot == SLOT_BLANKS ? delegate.getSlotLimit(PatternPrinterBlockEntity.SLOT_BLANKS) : 1;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return slot == SLOT_BLANKS && isBlankPattern(stack);
    }

    /** True if the stack is the AE2 blank pattern item. */
    public static boolean isBlankPattern(ItemStack stack) {
        Item blankPattern = BuiltInRegistries.ITEM.get(AEItemIds.BLANK_PATTERN);
        return blankPattern != null && stack.is(blankPattern);
    }

    private int blockEntitySlot(int slot) {
        return slot == SLOT_BLANKS
                ? PatternPrinterBlockEntity.SLOT_BLANKS
                : PatternPrinterBlockEntity.SLOT_OUTPUT_START + slot - SLOT_OUTPUT_START;
    }
}
