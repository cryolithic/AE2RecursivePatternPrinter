package dev.cryolithic.rpp.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the write coalescing of {@link StickyPersistence}
 * (issue #59): rapid selection changes within one second produce a single
 * file write, a change after the one-second window produces a second write,
 * and a forced flush (a screen closing) writes immediately even inside the
 * window. The coalescer takes its clock as a parameter, so no Minecraft
 * runtime is required. (The full {@code save()} path needs a Minecraft
 * runtime and is covered by in-game QA; note that referencing the nested
 * {@code WriteCoalescer} does not initialize {@code StickyPersistence}
 * itself, so no log4j2 binding happens here.)
 */
class StickyPersistenceCoalescingTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long TENTH = SECOND / 10;

    /**
     * Drives a coalescer the way {@code StickyPersistence.writeIfDue} does
     * and counts the writes that land.
     */
    private static final class Driver {
        private final StickyPersistence.WriteCoalescer coalescer = new StickyPersistence.WriteCoalescer();
        private long now;
        private int writes;

        void advance(long nanos) {
            now += nanos;
        }

        /** A selection change: mark dirty, then take a non-forced write opportunity. */
        void change() {
            coalescer.markDirty();
            writeIfDue(false);
        }

        /** A client tick: a non-forced write opportunity. */
        void tick() {
            writeIfDue(false);
        }

        /** A screen closing: a forced flush. */
        void flush() {
            writeIfDue(true);
        }

        private void writeIfDue(boolean force) {
            if (coalescer.shouldWrite(force, now)) {
                coalescer.recordWrite(now);
                writes++;
            }
        }
    }

    @Test
    void rapidChangesWithinOneSecondProduceOneWrite() {
        Driver driver = new Driver();
        driver.change();
        assertEquals(1, driver.writes, "the first write never waits");
        for (long offset = TENTH; offset < SECOND; offset += TENTH) {
            driver.advance(TENTH);
            driver.change();
        }
        driver.tick();
        assertEquals(1, driver.writes, "no further write inside the one-second window");
    }

    @Test
    void changeAfterTheWindowProducesASecondWrite() {
        Driver driver = new Driver();
        driver.change();
        driver.advance(SECOND - 1);
        driver.change();
        assertEquals(1, driver.writes, "999 ms after the last write: still inside the window");
        driver.advance(1);
        driver.change();
        assertEquals(2, driver.writes, "exactly one second after the last write: the window has elapsed");
    }

    @Test
    void flushForcesAWriteInsideTheWindow() {
        Driver driver = new Driver();
        driver.change();
        assertEquals(1, driver.writes);
        driver.advance(TENTH);
        driver.change();
        assertEquals(1, driver.writes, "coalesced: the dirty flag stays set");
        driver.flush();
        assertEquals(2, driver.writes, "a screen closing writes immediately");
        driver.tick();
        assertEquals(2, driver.writes, "the flush cleared the dirty flag");
    }

    @Test
    void noWriteWithoutChanges() {
        Driver driver = new Driver();
        driver.tick();
        driver.flush();
        assertEquals(0, driver.writes, "nothing is written while the file is clean");
    }
}
