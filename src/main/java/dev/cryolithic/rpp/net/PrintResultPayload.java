package dev.cryolithic.rpp.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * S2C print result (DESIGN.md §10.2). Partial success is a success:
 * {@code printed} is how many patterns landed in the output slots,
 * {@code skipped} how many did not, and {@code reason} is non-null only
 * when the job was rejected outright or stopped early.
 *
 * @param printed  patterns written to the output slots
 * @param skipped  patterns not printed (rejected job, capacity, or encode failure)
 * @param reason   rejection or stop reason; null on success
 */
public record PrintResultPayload(int printed, int skipped, @Nullable String reason)
        implements CustomPacketPayload {
    // createType(String) would mangle the namespace; parse the namespaced id directly.
    public static final Type<PrintResultPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.parse("rpp:print_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PrintResultPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeVarInt(value.printed());
                buf.writeVarInt(value.skipped());
                if (value.reason() == null) {
                    buf.writeBoolean(false);
                } else {
                    buf.writeBoolean(true);
                    buf.writeUtf(value.reason());
                }
            },
            buf -> new PrintResultPayload(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean() ? buf.readUtf() : null));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
