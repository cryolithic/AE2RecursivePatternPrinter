package dev.cryolithic.rpp.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import appeng.api.stacks.AEItemKey;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.InMemoryCommentedFormat;
import dev.cryolithic.rpp.RppConfig;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import dev.cryolithic.rpp.recipe.RecipeIndexHolder;
import dev.cryolithic.rpp.tree.IngredientNode;
import dev.cryolithic.rpp.tree.ItemNode;
import dev.cryolithic.rpp.tree.ReversalDetector;
import dev.cryolithic.rpp.tree.RecipeNode;
import dev.cryolithic.rpp.tree.RecipeTreeBuilder;
import dev.cryolithic.rpp.tree.SourceSelector;
import dev.cryolithic.rpp.tree.TreeNode;
import dev.cryolithic.rpp.tree.TreeLimits;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the root-build deduplication of {@link ClientTreeSession}
 * (DESIGN.md §9, issue #45): when a recipe reload lands while an expansion
 * is in flight, the tick's {@code rebuildIfStale} queues a root build, and
 * the stale expansion's publish must not queue a second one — a second build
 * on the same builder starts with inflated node counters and can publish a
 * wrongly capped tree even though the cap was never reached.
 *
 * <p>Runs in a bare JVM. Three seams make the real session reachable: the
 * config specs are preloaded with an empty in-memory config so every
 * {@code RppConfig} accessor returns its default deterministically (the test
 * never reads a config value itself); the main-thread hop is injected so the
 * publish callback runs on the test/executor thread; and the constructor is
 * package-private. The reload-mid-expansion window is held open by blocking
 * the expansion's publish callback between the background build and its
 * publish, exactly where the reload + tick rebuild land in production.</p>
 */
class ClientTreeSessionDedupTest {
    private static final ReversalDetector.TagLookup NO_TAGS = item -> List.of();

    static {
        // Force the shared console capture's static initializer
        // (RecipeIndexFixture) to run before this test's @BeforeAll can
        // initialize log4j2 via ModConfigSpec's logger (acceptConfig warns
        // when it corrects an empty config). The fixture redirects System.err
        // and forces log4j2 to bind to that redirected stream; if log4j2
        // binds to the original System.err first, the recipe-index console
        // assertions in other test classes silently capture nothing.
        RecipeIndexFixture.resetConsole();
    }

    @BeforeAll
    static void preloadConfig() throws Exception {
        // ModConfigSpec values read the loaded config on first access; in a
        // bare JVM no config is ever loaded. IConfigSpec.ILoadedConfig is
        // sealed with a single permitted implementation — FML's
        // package-private LoadedConfig record — so the test instantiates it
        // reflectively and attaches it to both specs directly, bypassing
        // acceptConfig (whose save() NPEs on the null ModConfig). The
        // in-memory config stays empty: getRaw() falls back to each
        // spec's default supplier for missing keys, so every RppConfig
        // accessor returns its default deterministically. The test never
        // reads a config value itself.
        IConfigSpec.ILoadedConfig loaded = inMemoryLoadedConfig();
        setLoadedConfig(RppConfig.CLIENT_SPEC, loaded);
        setLoadedConfig(RppConfig.COMMON_SPEC, loaded);
    }

    private static IConfigSpec.ILoadedConfig inMemoryLoadedConfig() throws Exception {
        CommentedConfig config = InMemoryCommentedFormat.defaultInstance().createConfig();
        // LoadedConfig is package-private in net.neoforged.fml.config;
        // look it up by name and instantiate reflectively (test-only).
        Class<?> loadedConfigClass = Class.forName("net.neoforged.fml.config.LoadedConfig");
        Constructor<?> ctor = loadedConfigClass.getDeclaredConstructor(
                CommentedConfig.class, Path.class, ModConfig.class);
        ctor.setAccessible(true);
        return (IConfigSpec.ILoadedConfig) ctor.newInstance(config, null, null);
    }

    private static void setLoadedConfig(ModConfigSpec spec, IConfigSpec.ILoadedConfig loaded) throws Exception {
        Field field = ModConfigSpec.class.getDeclaredField("loadedConfig");
        field.setAccessible(true);
        field.set(spec, loaded);
    }

    @Test
    void staleExpansionPublishDoesNotQueueSecondRootBuild() throws Exception {
        // V1: the index the session's tree was built against.
        RecipeIndexFixture f1 = new RecipeIndexFixture();
        // The goal item is shared between generations, as in production: a
        // recipe reload changes the recipe set, not the goal.
        Item a = f1.item("dedup_a");
        Item b = f1.item("dedup_b");
        Item c = f1.item("dedup_c");
        Item x = f1.item("dedup_x");
        f1.shapeless("dedup_v1_b_to_a", a, 1, Ingredient.of(b));
        f1.shapeless("dedup_v1_c_to_b", b, 1, Ingredient.of(c));
        // a second path for a: a real choice, so the root is not forced and
        // the oracle does not auto-collapse it (DESIGN.md §8.5). That keeps
        // the b candidate UNEXPANDED so requestExpand(b) has work to do.
        f1.shapeless("dedup_v1_x_to_a", a, 1, Ingredient.of(x));
        RecipeIndex v1 = f1.buildIndex();

        // V2: the reload target — same goal, different source chain —
        // published to the holder off-thread.
        RecipeIndexFixture f2 = new RecipeIndexFixture();
        Item d = f2.item("dedup_d");
        Item e = f2.item("dedup_e");
        f2.shapeless("dedup_v2_d_to_a", a, 1, Ingredient.of(d));
        f2.shapeless("dedup_v2_e_to_d", d, 1, Ingredient.of(e));
        RecipeIndexHolder.rebuild(f2.manager(), f2.provider(), Set.of(),
                RecipeIndex.resolveIngredientItems(f2.manager(), Set.of()), false);
        awaitTrue(() -> RecipeIndexHolder.current().recipeCount() > 0, "holder swap to V2");

        // A small session cap: one root build (7 nodes) fits, a second build
        // on the same builder does not — that is what makes the double build
        // publish a CAPPED tree.
        TreeLimits limits = new TreeLimits(8, 6, 8, 4000, 10);
        SourceSelector selector = new SourceSelector(v1, limits, SourceSelector.SelectionConfig.DEFAULTS, null);
        RecipeTreeBuilder builder = new RecipeTreeBuilder(v1, limits, NO_TAGS, selector);
        ClientTreeSession session = new ClientTreeSession(AEItemKey.of(a), v1, limits, selector, builder, null);
        try {
            // The session's V1 tree, one level deep: the root has a real
            // choice (two paths to a), so it is not forced and the oracle
            // does not auto-collapse it (DESIGN.md §8.5). The b candidate
            // stays UNEXPANDED, so requestExpand(b) has work to do.
            ItemNode v1Root = builder.buildRoot(AEItemKey.of(a), 1);
            ItemNode bNode = findCandidate(v1Root, b);
            assertTrue(bNode.state() == TreeNode.State.UNEXPANDED);

            // The expansion builds, then its publish callback blocks until the
            // reload + tick rebuild have landed — the reload-mid-expansion
            // window.
            CountDownLatch expansionBuilt = new CountDownLatch(1);
            CountDownLatch releasePublish = new CountDownLatch(1);
            CountDownLatch stalePublished = new CountDownLatch(1);
            session.mainThread = r -> {
                expansionBuilt.countDown();
                try {
                    releasePublish.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                r.run();
                stalePublished.countDown();
            };
            session.requestExpand(bNode);
            assertTrue(expansionBuilt.await(10, TimeUnit.SECONDS), "expansion build completed");

            // The tick's rebuildIfStale queues root build R1 against V2.
            session.rebuildIfStale();
            // Swap the hop before releasing the publish so the swap is
            // happens-before R1's publish (the latches order the rest).
            session.mainThread = Runnable::run;
            // The stale expansion's publish now runs: it must not queue a
            // second root build while R1 is pending.
            releasePublish.countDown();
            assertTrue(stalePublished.await(10, TimeUnit.SECONDS), "stale publish ran");

            awaitTrue(session::isRootReady, "root build published");

            // Exactly one root build ran on the final builder: the published
            // tree is the fresh V2 tree, complete and not capped.
            assertNull(session.expandingNode());
            assertSameItem(a, ((AEItemKey) session.root().goal()).getItem());
            assertFalse(hasCappedNode(session.root()), "the published tree must not be wrongly capped");

            // The discarded expansion's node is not stuck in pendingExpansions:
            // re-requesting it expands it (as a leaf, since V2 has no recipe
            // for b). The latch orders the executor's state write before the assertion.
            CountDownLatch reExpanded = new CountDownLatch(1);
            session.mainThread = r -> {
                r.run();
                reExpanded.countDown();
            };
            session.requestExpand(bNode);
            assertTrue(reExpanded.await(10, TimeUnit.SECONDS), "node re-expanded");
            assertTrue(bNode.state() != TreeNode.State.UNEXPANDED, "the node must not be stuck in pendingExpansions");
        } finally {
            session.close();
        }
    }

    private static void assertSameItem(Item expected, Item actual) {
        assertTrue(expected == actual, "expected " + expected + " but was " + actual);
    }

    /** Finds the first candidate node whose item is {@code item}, searching the tree. */
    private static ItemNode findCandidate(ItemNode node, Item item) {
        if (node.recipes() == null) {
            return null;
        }
        for (RecipeNode recipe : node.recipes()) {
            if (recipe.ingredients() == null) {
                continue;
            }
            for (IngredientNode ingredient : recipe.ingredients()) {
                if (ingredient.candidates() == null) {
                    continue;
                }
                for (ItemNode candidate : ingredient.candidates()) {
                    if (candidate.goal() instanceof AEItemKey key && key.getItem() == item) {
                        return candidate;
                    }
                    ItemNode found = findCandidate(candidate, item);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /** True when any node in the tree is CAPPED. */
    private static boolean hasCappedNode(TreeNode node) {
        if (node.state() == TreeNode.State.CAPPED) {
            return true;
        }
        if (node instanceof ItemNode item) {
            if (item.recipes() != null) {
                for (RecipeNode recipe : item.recipes()) {
                    if (hasCappedNode(recipe)) {
                        return true;
                    }
                }
            }
        } else if (node instanceof RecipeNode recipe) {
            if (recipe.ingredients() != null) {
                for (IngredientNode ingredient : recipe.ingredients()) {
                    if (hasCappedNode(ingredient)) {
                        return true;
                    }
                }
            }
        } else if (node instanceof IngredientNode ingredient) {
            if (ingredient.candidates() != null) {
                for (ItemNode candidate : ingredient.candidates()) {
                    if (hasCappedNode(candidate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("timed out waiting for: " + what);
    }
}
