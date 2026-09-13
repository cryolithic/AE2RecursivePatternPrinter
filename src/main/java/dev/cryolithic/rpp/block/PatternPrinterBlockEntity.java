package dev.cryolithic.rpp.block;

import dev.cryolithic.rpp.RppConfig;
import dev.cryolithic.rpp.init.RppBlockEntities;
import dev.cryolithic.rpp.init.RppMenus;
import dev.cryolithic.rpp.inventory.PatternPrinterInventory;
import dev.cryolithic.rpp.inventory.PatternPrinterMenu;
import dev.cryolithic.rpp.net.PrintRequestPayload;
import dev.cryolithic.rpp.net.PrintResultPayload;
import dev.cryolithic.rpp.print.PlanEntry;
import dev.cryolithic.rpp.print.PatternEncoder;
import dev.cryolithic.rpp.print.PrintPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * Block entity of the Recursive Pattern Printer (DESIGN.md §10.1).
 *
 * <p>Backed by one 29-slot {@link ItemStackHandler}: slot 0 holds the input
 * encoded pattern, slot 1 the blank patterns, slots 2-28 the output
 * patterns. The whole handler is persisted in {@code saveAdditional} /
 * {@code loadAdditional} and synced to the client via
 * {@code getUpdateTag} / {@code getUpdatePacket}. The recipe tree is never
 * persisted — it is derived state, rebuilt from the input slot on open.</p>
 *
 * <p>The block entity does nothing on its own: no ticker, no autonomous
 * updates. It only exposes the menu (server side), the side-aware
 * capability view used by automation, and the server-side print entry
 * point {@link #runPrint}.</p>
 */
public class PatternPrinterBlockEntity extends BlockEntity implements MenuProvider {
    /** Input slot: one encoded pattern, reachable only via the menu. */
    public static final int SLOT_INPUT = 0;
    /** Blank pattern slot: accepts only AE2 blank patterns, stacks. */
    public static final int SLOT_BLANKS = 1;
    /** First output slot: insert-blocked from outside, extract-allowed. */
    public static final int SLOT_OUTPUT_START = 2;
    /** Total slot count: input + blanks + 27 outputs. */
    public static final int SLOT_COUNT = 29;

    private static final String INVENTORY_TAG = "inventory";

    private static final Logger LOGGER = LogManager.getLogger(PatternPrinterBlockEntity.class);

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            // Sync is menu-only: no client code reads this block entity's
            // inventory (the GUI gets its slots from the server-synced
            // menu, and the tree is rebuilt from the server's input slot on
            // open), so a stale client-side copy is never observable.
            // setChanged() covers persistence; getUpdatePacket covers the
            // initial client-side copy on chunk load / block placement.
            setChanged();
        }
    };

    private final PatternPrinterInventory capabilityInventory = new PatternPrinterInventory(inventory);

    public PatternPrinterBlockEntity(BlockPos pos, BlockState state) {
        super(RppBlockEntities.PATTERN_PRINTER.get(), pos, state);
    }

    /** The raw handler, used by the menu. */
    public ItemStackHandler getInventory() {
        return inventory;
    }

    /** The side-aware capability view, exposed as {@code Capabilities.ItemHandler.BLOCK}. */
    public PatternPrinterInventory getCapabilityInventory() {
        return capabilityInventory;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(INVENTORY_TAG, inventory.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CompoundTag inventoryTag = tag.getCompound(INVENTORY_TAG);
        if (inventoryTag != null) {
            inventory.deserializeNBT(registries, inventoryTag);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new PatternPrinterMenu(RppMenus.PATTERN_PRINTER.get(), containerId, playerInventory, this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("menu.rpp.pattern_printer");
    }

    /**
     * Runs a print job (DESIGN.md §10.2). Server side, main thread.
     *
     * <p>Rejects when the input slot no longer matches the echoed pattern or
     * when {@link PrintPlan#validate} rejects the plan — rejections consume
     * nothing. Otherwise prints {@code min(blanks, entries, free output
     * slots)} patterns in the plan's print order, consuming one blank per
     * pattern and stopping on the first encode failure. Partial success is a
     * success. Never throws: failures are logged and reported through the
     * result.</p>
     */
    public PrintResultPayload runPrint(PrintRequestPayload request) {
        try {
            // ItemStack declares no equals override (components replaced NBT
            // in 1.20.5), so .equals is identity and would reject every print:
            // the slot's stack and the packet-decoded echo are distinct
            // instances. matches() compares item + components + count; the
            // input slot is size 1, so both stacks carry exactly one pattern
            // and the count comparison is a no-op in practice.
            if (!ItemStack.matches(inventory.getStackInSlot(SLOT_INPUT), request.inputPattern())) {
                return new PrintResultPayload(0, request.entries().size(), "input pattern changed");
            }

            PrintPlan.Result plan = PrintPlan.validate(
                    getLevel().getRecipeManager(),
                    getLevel().registryAccess(),
                    request.inputPattern(),
                    request.entries(),
                    RppConfig.maxPrintBatch(),
                    RppConfig.groupPrintByDestination(),
                    getLevel());
            if (!plan.accepted()) {
                return new PrintResultPayload(0, request.entries().size(), plan.rejectionReason());
            }

            int blanks = inventory.getStackInSlot(SLOT_BLANKS).getCount();
            int toPrint = Math.min(Math.min(blanks, plan.orderedEntries().size()), freeOutputSlots());

            int printed = 0;
            @Nullable String reason = null;
            for (PlanEntry entry : plan.orderedEntries().subList(0, toPrint)) {
                ItemStack pattern = PatternEncoder.encode(
                        plan.viewOf(entry),
                        entry.selectedCandidates(),
                        getLevel().getRecipeManager(),
                        getLevel().registryAccess(),
                        RppConfig.allowSubstitutions(),
                        RppConfig.allowFluidSubstitutions());
                if (pattern.isEmpty()) {
                    reason = "encoding failed for " + entry.recipeId();
                    break;
                }
                int slot = firstFreeOutputSlot();
                if (slot < 0) {
                    reason = "no free output slot";
                    break;
                }
                inventory.extractItem(SLOT_BLANKS, 1, false);
                inventory.insertItem(slot, pattern, false);
                printed++;
            }
            return new PrintResultPayload(printed, plan.orderedEntries().size() - printed, reason);
        } catch (Exception e) {
            LOGGER.error("rpp print job failed", e);
            return new PrintResultPayload(0, request.entries().size(), "internal error");
        }
    }

    /** The number of empty output slots. */
    private int freeOutputSlots() {
        int free = 0;
        for (int slot = SLOT_OUTPUT_START; slot < SLOT_COUNT; slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** The first empty output slot, or -1 when all are occupied. */
    private int firstFreeOutputSlot() {
        for (int slot = SLOT_OUTPUT_START; slot < SLOT_COUNT; slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }
}
