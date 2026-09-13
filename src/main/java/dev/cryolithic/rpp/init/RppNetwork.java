package dev.cryolithic.rpp.init;

import dev.cryolithic.rpp.block.PatternPrinterBlockEntity;
import dev.cryolithic.rpp.gui.PrintResultSink;
import dev.cryolithic.rpp.gui.StickyPersistence;
import dev.cryolithic.rpp.gui.PatternPrinterScreen;
import dev.cryolithic.rpp.inventory.PatternPrinterMenu;
import dev.cryolithic.rpp.net.PrintRequestPayload;
import dev.cryolithic.rpp.net.PrintResultPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/**
 * Payload registration for the print flow (DESIGN.md §10.2) and the GUI
 * screen factory. The C2S request runs the print job on the block entity
 * behind the player's open menu; the S2C result is routed to the open
 * {@link PatternPrinterScreen} (via {@link PrintResultSink}) so the GUI can
 * render it. Play-phase handlers run on the main thread by default, which is
 * what the block-entity access requires.
 *
 * <p>The screen factory and the result sink are client-only; they are
 * referenced only from code paths that run on the client (the mod-bus screen
 * event and the S2C handler), so the server never loads them. The screen
 * event and the sticky-persistence wiring are client-only registrations, so
 * they are only added when the dist is {@code CLIENT} (resolving their
 * event classes would fail on a dedicated server).</p>
 */
public final class RppNetwork {
    private RppNetwork() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RppNetwork::onRegisterPayloadHandlers);
        // RegisterMenuScreensEvent is a client-only mod-bus event and
        // StickyPersistence wires client-only game-bus events; on a
        // dedicated server neither class may even be resolved, so both
        // registrations are client-only.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(RppNetwork::onRegisterMenuScreens);
            // Client-side sticky-choice file load (DESIGN.md §8.7).
            StickyPersistence.init(modBus);
        }
    }

    /** Registers the container screen for the pattern printer menu (client only). */
    private static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(RppMenus.PATTERN_PRINTER.get(), PatternPrinterScreen::new);
    }

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(PrintRequestPayload.TYPE, PrintRequestPayload.STREAM_CODEC, RppNetwork::onPrintRequest)
                .playToClient(PrintResultPayload.TYPE, PrintResultPayload.STREAM_CODEC,
                        (payload, context) -> {
                            PatternPrinterScreen screen = PrintResultSink.get();
                            if (screen != null) {
                                screen.onPrintResult(payload);
                            }
                        });
    }

    /** C2S: run the print job on the printer behind the player's open menu. */
    private static void onPrintRequest(PrintRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        PatternPrinterBlockEntity printer = printerOf(player);
        if (printer == null) {
            return;
        }
        context.reply(printer.runPrint(payload));
    }

    /** The printer behind the player's open menu, or null when none is open. */
    @Nullable
    private static PatternPrinterBlockEntity printerOf(ServerPlayer player) {
        if (player.containerMenu instanceof PatternPrinterMenu menu) {
            return menu.getPrinter();
        }
        return null;
    }
}
