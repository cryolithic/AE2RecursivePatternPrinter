package dev.cryolithic.rpp.net;

import dev.cryolithic.rpp.print.PlanEntry;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * C2S print request (DESIGN.md §10.2). The input pattern is echoed back so
 * the server can reject the job when the slot has changed since the client
 * built the plan; the entries carry only recipe ids and selections — the
 * server revalidates everything against its own recipe manager before
 * encoding anything.
 *
 * @param inputPattern the encoded pattern currently in the input slot
 * @param entries      the selected plan entries, in client order
 */
public record PrintRequestPayload(ItemStack inputPattern, List<PlanEntry> entries)
        implements CustomPacketPayload {
    // createType(String) would mangle the namespace (withDefaultNamespace rejects the
    // colon); parse the namespaced id directly for a valid payload type.
    public static final Type<PrintRequestPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.parse("rpp:print_request"));

    /**
     * Hard decode cap on the entry list (DESIGN.md §10.2 step 2). The
     * configurable maxPrintBatch is enforced later in PrintPlan; this cap
     * rejects hostile payloads at decode time, before they are materialized.
     */
    public static final int MAX_ENTRIES = 2048;

    public static final StreamCodec<RegistryFriendlyByteBuf, PrintRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ItemStack.STREAM_CODEC, PrintRequestPayload::inputPattern,
            ByteBufCodecs.<RegistryFriendlyByteBuf, PlanEntry>list(MAX_ENTRIES).apply(PlanEntry.STREAM_CODEC),
            PrintRequestPayload::entries,
            PrintRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
