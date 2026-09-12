package dev.cryolithic.rpp.gui;

import org.jetbrains.annotations.Nullable;

/**
 * Holds the currently open {@link PatternPrinterScreen}, if any, so the
 * server-to-client print result (delivered on the main thread by the payload
 * handler) can be routed to the screen that sent the request. The reference is
 * set when the screen opens and cleared when it closes.
 *
 * <p>This class is client-only but is referenced from the (common) network
 * handler; the reference is only resolved on the client, where the screen
 * class is present, so the server never loads it.</p>
 */
public final class PrintResultSink {
    @Nullable
    private static PatternPrinterScreen current;

    private PrintResultSink() {
    }

    public static void set(@Nullable PatternPrinterScreen screen) {
        current = screen;
    }

    @Nullable
    public static PatternPrinterScreen get() {
        return current;
    }
}
