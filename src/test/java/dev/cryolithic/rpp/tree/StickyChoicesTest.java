package dev.cryolithic.rpp.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import dev.cryolithic.rpp.recipe.RecipeIndex;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

/**
 * Sticky source sets (DESIGN.md §8.7, §13.1): a remembered source set
 * applies to every node with that goal, survives a rebuild, and a stale
 * recipe id falls back silently. Runs in a bare JVM.
 */
class StickyChoicesTest {
    private static final ReversalDetector.TagLookup NO_TAGS = item -> List.of();
    private static final SourceSelector.SelectionConfig CONFIG = new SourceSelector.SelectionConfig(
            0.95, false, 3, 1, true, List.of("minecraft", "ae2"));

    private static ResourceLocation itemLocation(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static RecipeTreeBuilder builder(RecipeIndex index, StickyChoices sticky) {
        return new RecipeTreeBuilder(index, TreeLimits.DEFAULTS, NO_TAGS,
                new SourceSelector(index, TreeLimits.DEFAULTS, CONFIG, sticky));
    }

    /** All item nodes under {@code root} whose goal is {@code goal}. */
    private static List<ItemNode> nodesWithGoal(ItemNode root, Item goal) {
        List<ItemNode> nodes = new ArrayList<>();
        collect(root, goal, nodes);
        return nodes;
    }

    private static void collect(TreeNode node, Item goal, List<ItemNode> out) {
        if (node instanceof ItemNode item) {
            if (item.goal() instanceof AEItemKey key && key.getItem() == goal) {
                out.add(item);
            }
            if (item.recipes() != null) {
                for (RecipeNode recipe : item.recipes()) {
                    collect(recipe, goal, out);
                }
            }
        } else if (node instanceof RecipeNode recipe) {
            if (recipe.ingredients() != null) {
                for (IngredientNode ingredient : recipe.ingredients()) {
                    collect(ingredient, goal, out);
                }
            }
        } else if (node instanceof IngredientNode ingredient) {
            if (ingredient.candidates() != null) {
                for (ItemNode candidate : ingredient.candidates()) {
                    collect(candidate, goal, out);
                }
            }
        }
    }

    @Test
    void sourceSetAppliesToEveryNodeWithThatGoal() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item x = f.item("st_x");
        Item g = f.item("st_g");
        Item w = f.item("st_w");
        Item z = f.item("st_z");
        Item z1 = f.item("st_z1");
        Item z2 = f.item("st_z2");
        // G has two recipes
        f.shapeless("st_g1", g, 1, Ingredient.of(z1));
        f.shapeless("st_g2", g, 1, Ingredient.of(z2));
        // W is made from G, so G appears again under W
        f.shapeless("st_g_to_w", w, 1, Ingredient.of(g));
        // X is made from G and W
        f.shapeless("st_g_w_to_x", x, 1, Ingredient.of(g), Ingredient.of(w));

        StickyChoices sticky = new StickyChoices();
        sticky.remember(itemLocation(g), Set.of(ResourceLocation.fromNamespaceAndPath("rpp", "st_g1")));

        ItemNode root = builder(f.buildIndex(), sticky).buildRoot(AEItemKey.of(x), 8);

        List<ItemNode> gNodes = nodesWithGoal(root, g);
        assertEquals(2, gNodes.size(), "the goal appears in two places in the tree");
        for (ItemNode gNode : gNodes) {
            RecipeNode g1 = recipeById(gNode, "rpp:st_g1");
            RecipeNode g2 = recipeById(gNode, "rpp:st_g2");
            assertTrue(gNode.selected().get(gIndex(gNode, g1)), "the remembered recipe is checked");
            assertFalse(gNode.selected().get(gIndex(gNode, g2)), "the unremembered recipe is unchecked");
            assertTrue(g1.isStickyRestored(), "the restored row is marked");
            assertFalse(g2.isStickyRestored());
        }
    }

    @Test
    void sourceSetSurvivesARebuild() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item g = f.item("rb_g");
        Item z1 = f.item("rb_z1");
        Item z2 = f.item("rb_z2");
        f.shapeless("rb_g1", g, 1, Ingredient.of(z1));
        f.shapeless("rb_g2", g, 1, Ingredient.of(z2));

        StickyChoices sticky = new StickyChoices();
        sticky.remember(itemLocation(g), Set.of(ResourceLocation.fromNamespaceAndPath("rpp", "rb_g1")));

        ItemNode first = builder(f.buildIndex(), sticky).buildRoot(AEItemKey.of(g), 2);
        assertTrue(first.selected().get(gIndex(first, recipeById(first, "rpp:rb_g1"))));

        // a fresh builder, same sticky store: the choice is reapplied
        ItemNode second = builder(f.buildIndex(), sticky).buildRoot(AEItemKey.of(g), 2);
        assertTrue(second.selected().get(gIndex(second, recipeById(second, "rpp:rb_g1"))),
                "the remembered choice is reapplied on the next build");
        assertTrue(recipeById(second, "rpp:rb_g1").isStickyRestored());
    }

    @Test
    void staleRecipeIdFallsBackSilently() {
        RecipeIndexFixture f = new RecipeIndexFixture();
        Item g = f.item("sf_g");
        Item z1 = f.item("sf_z1");
        Item z2 = f.item("sf_z2");
        f.shapeless("sf_g1", g, 1, Ingredient.of(z1));
        f.shapeless("sf_g2", g, 1, Ingredient.of(z2));

        // one valid id + one stale id: the valid one is applied, the stale one dropped
        StickyChoices sticky = new StickyChoices();
        sticky.remember(itemLocation(g), Set.of(
                ResourceLocation.fromNamespaceAndPath("rpp", "sf_g1"),
                ResourceLocation.fromNamespaceAndPath("rpp", "stale")));
        ItemNode root = builder(f.buildIndex(), sticky).buildRoot(AEItemKey.of(g), 2);
        assertTrue(root.selected().get(gIndex(root, recipeById(root, "rpp:sf_g1"))),
                "the valid remembered recipe is applied");
        assertFalse(root.selected().get(gIndex(root, recipeById(root, "rpp:sf_g2"))));

        // a fully-stale set falls back to tiering
        StickyChoices staleOnly = new StickyChoices();
        staleOnly.remember(itemLocation(g), Set.of(ResourceLocation.fromNamespaceAndPath("rpp", "stale")));
        ItemNode fallback = builder(f.buildIndex(), staleOnly).buildRoot(AEItemKey.of(g), 2);
        assertEquals(1, fallback.selected().cardinality(),
                "a fully-stale set falls back to the tiered default (one of two same-dest crafting recipes)");
    }

    @Test
    void forgetAndClearAll() {
        StickyChoices sticky = new StickyChoices();
        ResourceLocation g = ResourceLocation.fromNamespaceAndPath("rpp", "fc_g");
        sticky.remember(g, Set.of(ResourceLocation.fromNamespaceAndPath("rpp", "r1")));
        assertNotNull(sticky.get(g));

        sticky.forget(g);
        assertNull(sticky.get(g), "forget drops one goal's choice");

        sticky.remember(g, Set.of(ResourceLocation.fromNamespaceAndPath("rpp", "r1")));
        sticky.clearAll();
        assertTrue(sticky.all().isEmpty(), "clearAll empties the store");
    }

    private static int gIndex(ItemNode node, RecipeNode recipe) {
        List<RecipeNode> recipes = node.recipes();
        for (int i = 0; i < recipes.size(); i++) {
            if (recipes.get(i) == recipe) {
                return i;
            }
        }
        throw new AssertionError("recipe not under node");
    }

    private static RecipeNode recipeById(ItemNode node, String id) {
        for (RecipeNode recipe : node.recipes()) {
            if (recipe.recipe().id().toString().equals(id)) {
                return recipe;
            }
        }
        throw new AssertionError("no recipe " + id + " under " + node.goal());
    }
}
