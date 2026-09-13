package dev.cryolithic.rpp.inventory;

import appeng.api.crafting.PatternDetailsHelper;
import dev.cryolithic.rpp.block.PatternPrinterBlockEntity;
import dev.cryolithic.rpp.init.RppMenus;
import javax.annotation.Nullable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * Menu of the Recursive Pattern Printer (DESIGN.md §10.1).
 *
 * <p>Exposes all 29 block entity slots to the player: the input pattern
 * slot (encoded patterns only, size 1), the blank pattern slot (blank
 * patterns only) and the 27 output slots. The slot rules are enforced
 * here through {@code mayPlace} / max stack size, so both direct
 * placement and shift-click respect them.</p>
 */
public class PatternPrinterMenu extends AbstractContainerMenu {
    /** Input slot: encoded patterns only, size 1. */
    public static final int SLOT_INPUT = 0;
    /** Blank pattern slot: blank patterns only, stacks. */
    public static final int SLOT_BLANKS = 1;
    /** First output slot. */
    public static final int SLOT_OUTPUT_START = 2;
    /** Total block entity slot count. */
    public static final int SLOT_COUNT = 29;
    /** First player main inventory slot in this menu (player slot 9). */
    public static final int PLAYER_MAIN_START = SLOT_COUNT;
    /** First player hotbar slot in this menu (player slot 0); 27 main slots follow the printer slots. */
    public static final int PLAYER_HOTBAR_START = SLOT_COUNT + 27;

    private static final int OUTPUT_ROWS = 3;
    private static final int SLOTS_PER_ROW = 9;
    /** Null on the client, where the menu is instantiated from the menu type alone. */
    @Nullable
    private final PatternPrinterBlockEntity printer;

    /** The block entity behind this menu, or null on the client. */
    @Nullable
    public PatternPrinterBlockEntity getPrinter() {
        return printer;
    }

    /** Client-side constructor used by the menu type; the block entity is not available there. */
    public PatternPrinterMenu(int containerId, Inventory playerInventory) {
        this(RppMenus.PATTERN_PRINTER.get(), containerId, playerInventory, null);
    }

    public PatternPrinterMenu(MenuType<? extends PatternPrinterMenu> type, int containerId, Inventory playerInventory,
            @Nullable PatternPrinterBlockEntity printer) {
        super(type, containerId);
        this.printer = printer;

        ItemStackHandler inventory = printer != null ? printer.getInventory() : new ItemStackHandler(SLOT_COUNT);

        addSlot(new InputSlot(inventory, SLOT_INPUT, 8, 17));
        addSlot(new BlanksSlot(inventory, SLOT_BLANKS, 80, 17));
        for (int row = 0; row < OUTPUT_ROWS; row++) {
            for (int col = 0; col < SLOTS_PER_ROW; col++) {
                addSlot(new OutputSlot(inventory, SLOT_OUTPUT_START + row * SLOTS_PER_ROW + col, 8 + col * 18, 45 + row * 18));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < SLOTS_PER_ROW; col++) {
                addSlot(new Slot(playerInventory, 9 + col + row * SLOTS_PER_ROW, 8 + col * 18, 132 + row * 18));
            }
        }
        for (int col = 0; col < SLOTS_PER_ROW; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 186));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return printer == null || Container.stillValidBlockEntity(printer, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack moving = slot.getItem();
        ItemStack result = moving.copy();

        if (index < SLOT_COUNT) {
            // Printer slot -> player inventory: main (9-35), then hotbar (0-8).
            if (!this.moveItemStackTo(moving, PLAYER_MAIN_START, PLAYER_HOTBAR_START, false)
                    && !this.moveItemStackTo(moving, PLAYER_HOTBAR_START, this.slots.size(), false)) {
                // Nothing moved: return EMPTY. doClick loops while the return
                // is non-empty and still matches the slot's item, so a
                // non-empty unchanged return spins the server thread.
                return ItemStack.EMPTY;
            }
        } else if (index < SLOT_COUNT + SLOTS_PER_ROW) {
            // Hotbar -> printer: outputs, then blanks, then input.
            if (!this.moveItemStackTo(moving, SLOT_OUTPUT_START, SLOT_COUNT, false)
                    && !this.moveItemStackTo(moving, SLOT_BLANKS, SLOT_BLANKS + 1, false)
                    && !this.moveItemStackTo(moving, SLOT_INPUT, SLOT_INPUT + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            // Main inventory -> printer: input + blanks, then outputs.
            if (!this.moveItemStackTo(moving, SLOT_INPUT, SLOT_OUTPUT_START, false)
                    && !this.moveItemStackTo(moving, SLOT_OUTPUT_START, SLOT_COUNT, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (moving.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.onQuickCraft(moving, result);
        }
        return result;
    }

    /** Input slot: encoded patterns only, size 1. */
    private static final class InputSlot extends SlotItemHandler {
        InputSlot(ItemStackHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty() && PatternDetailsHelper.isEncodedPattern(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
        }
    }

    /** Blank pattern slot: only the AE2 blank pattern item. */
    private static final class BlanksSlot extends SlotItemHandler {
        BlanksSlot(ItemStackHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return PatternPrinterInventory.isBlankPattern(stack);
        }
    }

    /** Output slot: reachable from the player, insert allowed in the menu. */
    private static final class OutputSlot extends SlotItemHandler {
        OutputSlot(ItemStackHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty();
        }
    }
}
