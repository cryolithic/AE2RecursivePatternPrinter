package dev.cryolithic.rpp.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.ids.AEItemIds;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bare-JVM tests for the shift-click slot ranges of {@link PatternPrinterMenu}
 * (issue #74: the printer-to-player pass must scan the main inventory
 * (player slots 9-35) and the hotbar (player slots 0-8) — no more, no less).
 *
 * <p>Boots the vanilla registries via {@link RecipeIndexFixture} and
 * constructs the menu with an unowned player inventory
 * ({@code new Inventory(null)}) — no world, no Player. The menu's
 * {@code quickMoveStack} never touches the Player, so the null owner is
 * never dereferenced on the shift-click path.
 */
class PatternPrinterMenuTest {
    private static RecipeIndexFixture fixture;

    @BeforeAll
    static void setUp() {
        RecipeIndexFixture.boot();
        fixture = new RecipeIndexFixture();
        registerBlankPatternItem();
    }

    /**
     * Registers a plain item under the AE2 blank-pattern id so that
     * {@code PatternPrinterInventory.isBlankPattern} (which looks the item up
     * by id) passes in the bare JVM, where AE2's own item is never registered.
     * Idempotent.
     */
    private static void registerBlankPatternItem() {
        if (!BuiltInRegistries.ITEM.containsKey(AEItemIds.BLANK_PATTERN)) {
            Registry.register(BuiltInRegistries.ITEM, AEItemIds.BLANK_PATTERN, new Item(new Item.Properties()));
        }
    }

    /** A stack of the (test-registered) AE2 blank pattern. */
    private static ItemStack blankPattern(int count) {
        return new ItemStack(BuiltInRegistries.ITEM.get(AEItemIds.BLANK_PATTERN), count);
    }
    /**
     * A server-side menu (null block entity) over the given player inventory. The
     * menu type is a local token: its supplier is never invoked because the menu is
     * constructed directly. It is created here (not as a static field) because
     * {@code MenuType}'s class initializer registers vanilla menu types into
     * {@code BuiltInRegistries}, which requires the registries to be booted first.
     */
    private static PatternPrinterMenu menu(Inventory playerInventory) {
        MenuType<PatternPrinterMenu> type = new MenuType<>((id, inv) -> null, FeatureFlagSet.of());
        return new PatternPrinterMenu(type, 0, playerInventory, null);
    }

    @Test
    void shiftClickFromPrinterFillsMainInventoryBeforeHotbar() {
        Inventory playerInventory = new Inventory(null);
        PatternPrinterMenu menu = menu(playerInventory);

        ItemStack blank = fixture.stack(fixture.item("menu_main_first"), 5);
        menu.slots.get(PatternPrinterMenu.SLOT_OUTPUT_START).set(blank);

        ItemStack moved = menu.quickMoveStack(null, PatternPrinterMenu.SLOT_OUTPUT_START);

        assertEquals(5, moved.getCount());
        // First main inventory slot (player slot 9), not the hotbar.
        assertEquals(5, playerInventory.getItem(9).getCount());
        for (int i = 0; i < 9; i++) {
            assertTrue(playerInventory.getItem(i).isEmpty(), "hotbar slot " + i + " must stay empty");
        }
        for (int i = 10; i <= 35; i++) {
            assertTrue(playerInventory.getItem(i).isEmpty(), "main slot " + i + " must stay empty");
        }
        // The printer slot was emptied.
        assertTrue(menu.slots.get(PatternPrinterMenu.SLOT_OUTPUT_START).getItem().isEmpty());
    }

    @Test
    void shiftClickFallsBackToHotbarWhenMainIsFull() {
        Inventory playerInventory = new Inventory(null);
        for (int i = 9; i <= 35; i++) {
            playerInventory.setItem(i, fixture.stack(fixture.item("menu_filler"), 64));
        }
        PatternPrinterMenu menu = menu(playerInventory);

        ItemStack blank = fixture.stack(fixture.item("menu_hotbar_fallback"), 3);
        menu.slots.get(PatternPrinterMenu.SLOT_OUTPUT_START).set(blank);

        ItemStack moved = menu.quickMoveStack(null, PatternPrinterMenu.SLOT_OUTPUT_START);

        assertEquals(3, moved.getCount());
        // First hotbar slot (player slot 0).
        assertEquals(3, playerInventory.getItem(0).getCount());
        for (int i = 1; i < 9; i++) {
            assertTrue(playerInventory.getItem(i).isEmpty(), "hotbar slot " + i + " must stay empty");
        }
        // The main inventory is untouched.
        for (int i = 9; i <= 35; i++) {
            assertEquals(64, playerInventory.getItem(i).getCount(), "main slot " + i + " must keep its filler");
        }
    }

    @Test
    void shiftClickReturnsEmptyWhenPlayerInventoryIsFull() {
        Inventory playerInventory = new Inventory(null);
        for (int i = 0; i < 36; i++) {
            playerInventory.setItem(i, fixture.stack(fixture.item("menu_full"), 64));
        }
        PatternPrinterMenu menu = menu(playerInventory);

        ItemStack blank = fixture.stack(fixture.item("menu_no_room"), 3);
        menu.slots.get(PatternPrinterMenu.SLOT_OUTPUT_START).set(blank);

        ItemStack moved = menu.quickMoveStack(null, PatternPrinterMenu.SLOT_OUTPUT_START);

        // Nothing moved: EMPTY return (a non-empty unchanged return would
        // spin the server thread in doClick), and the item stays put.
        assertSame(ItemStack.EMPTY, moved);
        assertEquals(3, menu.slots.get(PatternPrinterMenu.SLOT_OUTPUT_START).getItem().getCount());
    }

    /**
     * Issue #74 (reopened): the player-to-printer direction. A blank pattern in
     * the top row of the main inventory (menu index {@code SLOT_COUNT} = player
     * slot 9) must take the main-inventory policy (input + blanks first), landing
     * in the blanks slot — not the hotbar policy (outputs first), which scattered
     * it across the output grid.
     */
    @Test
    void shiftClickFromMainInventoryRow1GoesToBlanks() {
        Inventory playerInventory = new Inventory(null);
        PatternPrinterMenu menu = menu(playerInventory);

        // Menu index SLOT_COUNT is the first main-inventory slot (player slot 9).
        playerInventory.setItem(9, blankPattern(5));

        ItemStack moved = menu.quickMoveStack(null, PatternPrinterMenu.SLOT_COUNT);

        assertEquals(5, moved.getCount());
        // Landed in the blanks slot, not scattered across the outputs.
        assertEquals(5, menu.slots.get(PatternPrinterMenu.SLOT_BLANKS).getItem().getCount());
        for (int i = PatternPrinterMenu.SLOT_OUTPUT_START; i < PatternPrinterMenu.SLOT_COUNT; i++) {
            assertTrue(menu.slots.get(i).getItem().isEmpty(), "output slot " + i + " must stay empty");
        }
        // The input slot stays empty (a blank is not an encoded pattern).
        assertTrue(menu.slots.get(PatternPrinterMenu.SLOT_INPUT).getItem().isEmpty());
        // The player slot was emptied.
        assertTrue(playerInventory.getItem(9).isEmpty());
    }
}
