package dev.cryolithic.rpp.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cryolithic.rpp.tree.SourceSet;
import dev.cryolithic.rpp.tree.StickyChoices;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * #35: the sticky store is read on the background expansion thread
 * (via {@code get} and {@code SourceSet.recipeIds}, as
 * {@code SourceSelector} does) while the client main thread mutates it on
 * every selection change. The store must tolerate that mix without
 * throwing and must converge to the last write. Runs in a bare JVM.
 */
class StickyChoicesConcurrencyTest {
    private static final int WRITERS = 4;
    private static final int READERS = 2;
    private static final int WRITER_ITERATIONS = 500;

    private static ResourceLocation goal(int i) {
        return ResourceLocation.fromNamespaceAndPath("rpp", "goal" + i);
    }

    private static ResourceLocation recipe(int i) {
        return ResourceLocation.fromNamespaceAndPath("rpp", "recipe" + i);
    }

    @Test
    void concurrentMutationAndReadsConvergeToLastWrite() throws Exception {
        StickyChoices sticky = new StickyChoices();
        List<Thread> writers = new ArrayList<>();
        List<Thread> readers = new ArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean writersDone = new AtomicBoolean(false);

        for (int w = 0; w < WRITERS; w++) {
            final int writer = w;
            writers.add(new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < WRITER_ITERATIONS; i++) {
                        ResourceLocation g = goal(writer * 10 + (i % 10));
                        switch ((i + writer) % 3) {
                            case 0 -> sticky.remember(g, Set.of(recipe(i % 7)));
                            case 1 -> sticky.forget(g);
                            default -> sticky.clearAll();
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (int r = 0; r < READERS; r++) {
            readers.add(new Thread(() -> {
                try {
                    start.await();
                    while (!writersDone.get()) {
                        for (int i = 0; i < 100; i++) {
                            SourceSet set = sticky.get(goal(i % 40));
                            if (set != null) {
                                set.recipeIds().size();
                            }
                            for (SourceSet s : sticky.all().values()) {
                                s.recipeIds().size();
                            }
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        readers.forEach(Thread::start);
        writers.forEach(Thread::start);
        start.countDown();
        for (Thread t : writers) {
            t.join();
        }
        writersDone.set(true);
        for (Thread t : readers) {
            t.join();
        }

        assertTrue(failures.isEmpty(), "no thread may throw: " + failures);

        // Once the writers finish, the main thread is the only actor: the
        // store must reflect exactly the last write.
        sticky.clearAll();
        sticky.remember(goal(1), Set.of(recipe(1), recipe(2)));
        sticky.remember(goal(2), Set.of());
        sticky.forget(goal(3));
        Map<ResourceLocation, SourceSet> expected = new HashMap<>();
        expected.put(goal(1), new SourceSet(goal(1), Set.of(recipe(1), recipe(2))));
        expected.put(goal(2), new SourceSet(goal(2), Set.of()));
        assertEquals(expected, sticky.all(), "the store converges to the last write");
    }
}
