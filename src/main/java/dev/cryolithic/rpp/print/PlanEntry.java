package dev.cryolithic.rpp.print;

import appeng.api.stacks.AEKey;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * One row of a print request (DESIGN.md §10.2): the recipe id, the claimed
 * output, and the selected candidate per input slot. The server revalidates
 * every entry against its own recipe manager before encoding anything.
 *
 * @param recipeId           the recipe to print
 * @param output            the claimed output key of that recipe
 * @param selectedCandidates the chosen candidate per input slot, in slot order
 */
public record PlanEntry(
        ResourceLocation recipeId,
        AEKey output,
        List<AEKey> selectedCandidates) {

    /** Wire codec for {@link PrintRequestPayload}; the list is varint-prefixed. */
    public static final StreamCodec<RegistryFriendlyByteBuf, PlanEntry> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, PlanEntry::recipeId,
            AEKey.STREAM_CODEC, PlanEntry::output,
            ByteBufCodecs.<RegistryFriendlyByteBuf, AEKey>list().apply(AEKey.STREAM_CODEC), PlanEntry::selectedCandidates,
            PlanEntry::new);
}
