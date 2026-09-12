package dev.cryolithic.rpp.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cryolithic.rpp.RppConfig;
import dev.cryolithic.rpp.gui.ClientTreeSession;
import dev.cryolithic.rpp.tree.Craftability;
import dev.cryolithic.rpp.tree.IngredientNode;
import dev.cryolithic.rpp.tree.ItemNode;
import dev.cryolithic.rpp.tree.RecipeNode;
import dev.cryolithic.rpp.tree.Tier;
import dev.cryolithic.rpp.tree.TreeNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import static dev.cryolithic.rpp.tree.TreeNode.State;

/**
 * The virtualized recipe-tree pane (DESIGN.md §11.1). A roughly 256 px wide,
 * resizable-height list of {@link TreeRow}s. Only the rows in the viewport are
 * rendered, from a flattened visible-row list that is cached and recomputed on
 * expand, collapse, selection change or filter change — a 2000-row tree
 * scrolls at full frame rate with no per-frame allocation of the whole list.
 *
 * <p>Rows are checkboxes (recipe rows) and disclosure triangles (item rows),
 * with badges, a live hover tooltip, a per-node select/deselect/reset/raw-input
 * menu, a "+K more" marker and a session-cap banner.</p>
 */
public final class TreeWidget {
    private static final int ROW_HEIGHT = 14;
    private static final int INDENT = 12;
    private static final int PADDING = 4;
    private static final int ICON_SIZE = 16;
    private static final int CHECKBOX_SIZE = 12;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int MENU_WIDTH = 128;
    private static final int MENU_OPTION_HEIGHT = 14;

    private static final int PANE_BG = 0xFF161616;
    private static final int PANE_BORDER = 0xFF3A3A3A;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int DIM_COLOR = 0xFF8A8A8A;
    private static final int ACCENT_COLOR = 0xFF7FB2FF;
    private static final int WARN_COLOR = 0xFFFFB84D;
    private static final int REJECT_COLOR = 0xFFE07070;
    private static final int LOSSLESS_COLOR = 0xFF6AB06A;
    private static final int BANNER_BG = 0x66B03030;
    private static final int BANNER_TEXT = 0xFFFFD0D0;
    private static final int MENU_BG = 0xFF202020;
    private static final int MENU_HOVER = 0x40FFFFFF;

    private final int x;
    private final int y;
    private final int width;
    private int height;

    /** The cached flattened visible-row list; rebuilt on change, never per frame. */
    private final List<TreeRow> rows = new ArrayList<>();
    private int scrollOffset;
    private final TreeScrollbar scrollbar;
    @Nullable
    private ClientTreeSession session;
    private int hoverRow = -1;
    private String filter = "";

    @Nullable
    private ItemNode menuNode;
    private int menuX;
    private int menuY;
    private int menuHover = -1;

    public TreeWidget(@Nullable ClientTreeSession session, int x, int y, int width, int height) {
        this.session = session;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.scrollbar = new TreeScrollbar(x + width - SCROLLBAR_WIDTH, y + 1, height - 2);
        rebuildRows();
    }

    /** Updates the session (e.g. when one becomes available after the input slot is filled). */
    public void setSession(@Nullable ClientTreeSession session) {
        this.session = session;
        rebuildRows();
    }

    public int getHeight() {
        return height;
    }

    public void setFilter(String filter) {
        this.filter = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        rebuildRows();
    }

    public String getFilter() {
        return filter;
    }

    /** Recomputes the flattened visible-row list from the current tree and filter. */
    public void rebuildRows() {
        rows.clear();
        ClientTreeSession session = this.session;
        ItemNode root = session != null ? session.root() : null;
        if (root != null) {
            if (filter.isEmpty()) {
                flattenItem(root, 0, null);
            } else {
                flattenItem(root, 0, computeKeepSet(root));
            }
        }
        clampScroll();
    }

    public int rowCount() {
        return rows.size();
    }

    // --- rendering ---

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        graphics.fill(x, y, x + width, y + height, PANE_BG);
        graphics.fill(x, y, x + width, y + 1, PANE_BORDER);
        graphics.fill(x, y + height - 1, x + width, y + height, PANE_BORDER);
        graphics.fill(x, y, x + 1, y + height, PANE_BORDER);
        graphics.fill(x + width - 1, y, x + width, y + height, PANE_BORDER);

        int contentWidth = width - SCROLLBAR_WIDTH;
        int firstRowY = y + 1;
        if (session != null && session.builder().atTotalNodeCap()) {
            graphics.fill(x + 1, firstRowY, x + contentWidth, firstRowY + ROW_HEIGHT, BANNER_BG);
            graphics.drawCenteredString(font, I18n.get("rpp.gui.banner.capped"), x + contentWidth / 2, firstRowY + 3, BANNER_TEXT);
            firstRowY += ROW_HEIGHT;
        }
        int visibleRows = Math.max(1, (y + height - 1 - firstRowY) / ROW_HEIGHT);
        int first = scrollOffset;
        int last = Math.min(rows.size(), scrollOffset + visibleRows);
        for (int i = first; i < last; i++) {
            TreeRow row = rows.get(i);
            int rowY = firstRowY + (i - first) * ROW_HEIGHT;
            boolean hovered = i == hoverRow && isMouseInRowArea(mouseX, mouseY, rowY, contentWidth);
            renderRow(graphics, font, row, rowY, contentWidth, hovered, partialTick);
        }

        if (hoverRow >= 0 && hoverRow < rows.size()) {
            int rowY = firstRowY + (hoverRow - first) * ROW_HEIGHT;
            if (isMouseInRowArea(mouseX, mouseY, rowY, contentWidth)) {
                List<Component> tooltip = tooltipFor(rows.get(hoverRow));
                if (!tooltip.isEmpty()) {
                    graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
                }
            }
        }

        scrollbar.setRange(rows.size(), visibleRows);
        scrollbar.render(graphics, mouseX, mouseY, partialTick);

        if (menuNode != null) {
            renderMenu(graphics, font, mouseX, mouseY);
        }
    }

    private void renderRow(GuiGraphics graphics, Font font, TreeRow row, int rowY, int contentWidth, boolean hovered,
            float partialTick) {
        int indent = PADDING + row.depth() * INDENT;
        if (hovered) {
            graphics.fill(x + 1, rowY, x + contentWidth, rowY + ROW_HEIGHT, ROW_HOVER);
        }
        switch (row.kind()) {
            case ITEM -> renderItemRow(graphics, font, row.item(), indent, rowY, contentWidth, partialTick);
            case RECIPE -> renderRecipeRow(graphics, font, row, indent, rowY, contentWidth);
            case MORE -> renderMoreRow(graphics, font, row, indent, rowY);
        }
    }

    private void renderItemRow(GuiGraphics graphics, Font font, ItemNode item, int indent, int rowY, int contentWidth,
            float partialTick) {
        int textX = x + indent + ICON_SIZE + 16;
        if (isExpandable(item)) {
            if (session.expandingNode() == item) {
                renderSpinner(graphics, x + indent + 2, rowY + 2, partialTick);
            } else if (item.state() == State.EXPANDED) {
                drawTriangle(graphics, x + indent + 2, rowY + 2, true);
            } else {
                drawTriangle(graphics, x + indent + 2, rowY + 2, false);
            }
        }
        if (item.goal() instanceof appeng.api.stacks.AEItemKey key) {
            graphics.renderItem(key.toStack(), x + indent + 10, rowY + 1);
        }
        graphics.drawString(font, itemName(item), textX, rowY + 3, TEXT_COLOR);
        renderItemRight(graphics, font, item, rowY, contentWidth);
    }

    private void renderItemRight(GuiGraphics graphics, Font font, ItemNode item, int rowY, int contentWidth) {
        int rightX = x + contentWidth - 6;
        if (item.state() == State.EXPANDED && item.recipes() != null) {
            int selected = item.selected().cardinality();
            int total = item.recipes().size();
            String count = selected + " of " + total;
            int countWidth = font.width(count);
            int arrowX = rightX;
            rightX -= countWidth + 10;
            drawMenuArrow(graphics, arrowX, rowY + 3);
            graphics.drawString(font, count, rightX, rowY + 3, ACCENT_COLOR);
        } else if (item.state() == State.COLLAPSED && item.recipes() != null) {
            String summary = collapseSummary(item);
            int summaryWidth = font.width(summary);
            int arrowX = rightX;
            rightX -= summaryWidth + 10;
            drawMenuArrow(graphics, arrowX, rowY + 3);
            graphics.drawString(font, summary, rightX, rowY + 3, DIM_COLOR);
        }
        List<String> badges = itemBadges(item);
        if (!badges.isEmpty()) {
            int bx = rightX - 4;
            for (int i = badges.size() - 1; i >= 0; i--) {
                String badge = badges.get(i);
                int w = font.width(badge);
                bx -= w + 2;
                graphics.drawString(font, badge, bx, rowY + 3, badgeColor(badge));
            }
        }
    }

    private void renderRecipeRow(GuiGraphics graphics, Font font, TreeRow row, int indent, int rowY, int contentWidth) {
        RecipeNode recipe = row.recipe();
        ItemNode parent = recipe.parent() instanceof ItemNode item ? item : null;
        boolean selected = parent != null && parent.selected().get(row.recipeIndex());
        int textX = x + indent + ICON_SIZE + CHECKBOX_SIZE + 8;
        int cbX = x + indent + 2;
        int cbY = rowY + 1;
        graphics.fill(cbX, cbY, cbX + CHECKBOX_SIZE, cbY + CHECKBOX_SIZE, selected ? 0xFF5A8A3A : 0xFF2A2A2A);
        graphics.fill(cbX, cbY, cbX + CHECKBOX_SIZE, cbY + 1, selected ? 0xFF8ABF6A : 0xFF4A4A4A);
        if (selected) {
            graphics.fill(cbX + 2, cbY + 4, cbX + 4, cbY + 8, 0xFFFFFFFF);
            graphics.fill(cbX + 4, cbY + 2, cbX + 6, cbY + 10, 0xFFFFFFFF);
            graphics.fill(cbX + 6, cbY + 6, cbX + 9, cbY + 3, 0xFFFFFFFF);
        }
        if (!recipe.recipe().outputs().isEmpty() && recipe.recipe().outputs().get(0).what() instanceof appeng.api.stacks.AEItemKey key) {
            graphics.renderItem(key.toStack(), x + indent + CHECKBOX_SIZE + 4, rowY + 1);
        }
        String name = TreeRow.recipeName(recipe);
        int maxNameWidth = Math.max(0, contentWidth - (textX - x) - 90);
        if (font.width(name) > maxNameWidth) {
            name = font.plainSubstrByWidth(name, maxNameWidth);
        }
        int nameColor = recipe.tier() == Tier.REJECTED ? DIM_COLOR : TEXT_COLOR;
        graphics.drawString(font, name, textX, rowY + 3, nameColor);
        renderRecipeRight(graphics, font, recipe, rowY, contentWidth);
    }

    private void renderRecipeRight(GuiGraphics graphics, Font font, RecipeNode recipe, int rowY, int contentWidth) {
        int rightX = x + contentWidth - 6;
        List<String> badges = recipeBadges(recipe);
        if (!badges.isEmpty()) {
            int bx = rightX;
            for (int i = badges.size() - 1; i >= 0; i--) {
                String badge = badges.get(i);
                int w = font.width(badge);
                bx -= w + 2;
                graphics.drawString(font, badge, bx, rowY + 3, badgeColor(badge));
            }
            rightX = bx - 6;
        }
        String yieldText = "×" + yieldOf(recipe);
        rightX -= font.width(yieldText);
        graphics.drawString(font, yieldText, rightX, rowY + 3, TEXT_COLOR);
        rightX -= 8;
        String dest = recipe.destination().describe();
        rightX -= font.width(dest);
        graphics.drawString(font, dest, rightX, rowY + 3, DIM_COLOR);
    }

    private void renderMoreRow(GuiGraphics graphics, Font font, TreeRow row, int indent, int rowY) {
        graphics.drawString(font, I18n.get("rpp.gui.more", row.moreCount()), x + indent + ICON_SIZE + 8, rowY + 3, DIM_COLOR);
    }

    private void renderMenu(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        String[] options = menuOptions();
        int menuHeight = MENU_OPTION_HEIGHT * options.length + 2;
        menuHover = -1;
        for (int i = 0; i < options.length; i++) {
            int optY = menuY + 1 + i * MENU_OPTION_HEIGHT;
            if (mouseX >= menuX && mouseX < menuX + MENU_WIDTH && mouseY >= optY && mouseY < optY + MENU_OPTION_HEIGHT) {
                menuHover = i;
            }
        }
        graphics.fill(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight, MENU_BG);
        for (int i = 0; i < options.length; i++) {
            int optY = menuY + 1 + i * MENU_OPTION_HEIGHT;
            if (i == menuHover) {
                graphics.fill(menuX, optY, menuX + MENU_WIDTH, optY + MENU_OPTION_HEIGHT, MENU_HOVER);
            }
            graphics.drawString(font, options[i], menuX + 4, optY + 3, TEXT_COLOR);
        }
    }

    private String[] menuOptions() {
        List<String> options = new ArrayList<>(List.of(
                I18n.get("rpp.gui.menu.select_all"),
                I18n.get("rpp.gui.menu.deselect_all"),
                I18n.get("rpp.gui.menu.reset_defaults"),
                I18n.get("rpp.gui.menu.raw_input")));
        if (menuNode != null && session != null && session.hasStickyChoice(menuNode)) {
            options.add(I18n.get("rpp.gui.menu.forget_sticky"));
        }
        return options.toArray(new String[0]);
    }

    // --- interaction ---

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (menuNode != null) {
            int menuHeight = MENU_OPTION_HEIGHT * menuOptions().length + 2;
            if (mouseX >= menuX && mouseX < menuX + MENU_WIDTH && mouseY >= menuY && mouseY < menuY + menuHeight) {
                for (int i = 0; i < menuOptions().length; i++) {
                    int optY = menuY + 1 + i * MENU_OPTION_HEIGHT;
                    if (mouseY >= optY && mouseY < optY + MENU_OPTION_HEIGHT) {
                        performMenuAction(menuNode, i);
                        menuNode = null;
                        return true;
                    }
                }
            }
            menuNode = null;
            return true;
        }
        if (scrollbar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int row = rowAt(mouseX, mouseY);
        if (row < 0) {
            return false;
        }
        TreeRow treeRow = rows.get(row);
        if (treeRow.isMore()) {
            return false;
        }
        int indent = PADDING + treeRow.depth() * INDENT;
        int rowY = bannerTop() + (row - scrollOffset) * ROW_HEIGHT;
        if (treeRow.isItem()) {
            ItemNode item = treeRow.item();
            if (mouseX >= x + indent + 1 && mouseX < x + indent + 10 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                toggleExpand(item);
                return true;
            }
            if (isNodeMenuArrowHit(item, mouseX, mouseY, rowY)) {
                openMenu(item, rowY);
                return true;
            }
            if (isShiftDown() && item.recipes() != null) {
                session.markRawInput(item);
                rebuildRows();
                return true;
            }
            return false;
        }
        if (treeRow.isRecipe()) {
            ItemNode parent = treeRow.recipe().parent() instanceof ItemNode item ? item : null;
            int cbX = x + indent + 2;
            if (parent != null && mouseX >= cbX && mouseX < cbX + CHECKBOX_SIZE && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                session.toggleRecipe(parent, treeRow.recipeIndex());
                rebuildRows();
                return true;
            }
            return false;
        }
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return scrollbar.mouseReleased(mouseX, mouseY, button);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double changeX, double changeY) {
        return scrollbar.mouseDragged(mouseX, mouseY, button, changeX, changeY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height) {
            return false;
        }
        scrollOffset = Math.max(0, scrollOffset - (int) Math.round(amount * 3));
        clampScroll();
        return true;
    }

    public void mouseMoved(double mouseX, double mouseY) {
        hoverRow = rowAt(mouseX, mouseY);
    }

    private boolean isMouseInRowArea(double mouseX, double mouseY, int rowY, int contentWidth) {
        return mouseX >= x && mouseX < x + contentWidth && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
    }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < x || mouseX >= x + width - SCROLLBAR_WIDTH || mouseY < y + 1 || mouseY >= y + height - 1) {
            return -1;
        }
        int firstRowY = bannerTop();
        int index = (int) ((mouseY - firstRowY) / ROW_HEIGHT) + scrollOffset;
        if (index < scrollOffset || index >= rows.size()) {
            return -1;
        }
        return index;
    }

    private void toggleExpand(ItemNode item) {
        if (!isExpandable(item)) {
            return;
        }
        if (item.state() == State.EXPANDED) {
            item.setState(State.COLLAPSED);
        } else if (item.state() == State.COLLAPSED) {
            item.setState(State.EXPANDED);
        } else if (item.state() == State.UNEXPANDED) {
            session.requestExpand(item);
            return; // the session publishes and we rebuild on the update
        }
        rebuildRows();
    }

    private void openMenu(ItemNode item, int rowY) {
        menuNode = item;
        menuX = x + width - MENU_WIDTH - 2;
        menuY = rowY + ROW_HEIGHT;
        int menuHeight = MENU_OPTION_HEIGHT * menuOptions().length + 2;
        if (menuY + menuHeight > y + height) {
            menuY = rowY - menuHeight;
        }
    }

    private void performMenuAction(ItemNode item, int option) {
        switch (option) {
            case 0 -> session.selectNode(item);
            case 1 -> session.deselectNode(item);
            case 2 -> session.resetNodeDefaults(item);
            case 3 -> session.markRawInput(item);
            case 4 -> session.forgetStickyChoice(item);
        }
        rebuildRows();
    }

    private boolean isNodeMenuArrowHit(ItemNode item, double mouseX, double mouseY, int rowY) {
        if ((item.state() != State.EXPANDED && item.state() != State.COLLAPSED) || item.recipes() == null) {
            return false;
        }
        int arrowX = x + width - SCROLLBAR_WIDTH - 6;
        return mouseX >= arrowX - 8 && mouseX < arrowX + 2 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
    }

    private static boolean isShiftDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
    }

    // --- helpers ---

    private int bannerHeight() {
        return (session != null && session.builder().atTotalNodeCap()) ? ROW_HEIGHT : 0;
    }

    private int bannerTop() {
        return y + 1 + bannerHeight();
    }

    private void clampScroll() {
        int visibleRows = Math.max(1, (height - 2 - bannerHeight()) / ROW_HEIGHT);
        int maxOffset = Math.max(0, rows.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxOffset));
    }

    /**
     * Scrolls the pane to the given item node's row, expanding collapsed
     * ancestors so the row is visible. Used when returning from the
     * pre-print review (DESIGN.md §11.2).
     */
    public void focusNode(ItemNode node) {
        for (TreeNode n = node.parent(); n != null; n = n.parent()) {
            if (n instanceof ItemNode item && item.state() == State.COLLAPSED) {
                item.setState(State.EXPANDED);
            }
        }
        rebuildRows();
        int target = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).isItem() && rows.get(i).item() == node) {
                target = i;
                break;
            }
        }
        if (target >= 0) {
            int visibleRows = Math.max(1, (height - 2 - bannerHeight()) / ROW_HEIGHT);
            scrollOffset = Math.max(0, target - visibleRows / 2);
        }
        clampScroll();
    }

    private static boolean isExpandable(ItemNode item) {
        State state = item.state();
        return state == State.EXPANDED || state == State.COLLAPSED || state == State.UNEXPANDED;
    }

    private static String itemName(ItemNode item) {
        if (item.goal() instanceof appeng.api.stacks.AEItemKey key) {
            return key.toStack().getHoverName().getString();
        }
        return "";
    }

    private String collapseSummary(ItemNode item) {
        int[] acc = new int[2]; // [steps, patterns]
        boolean allForced = collectSummary(item, acc);
        if (allForced) {
            return String.format(I18n.get("rpp.gui.summary.collapsed"), acc[0], acc[1], I18n.get("rpp.gui.summary.all_forced"));
        }
        return String.format(I18n.get("rpp.gui.summary.collapsed_partial"), acc[0], acc[1]);
    }

    private boolean collectSummary(ItemNode item, int[] acc) {
        boolean allForced = item.isForced() || item.isLeaf() || item.state() == State.CYCLE;
        if (item.recipes() != null) {
            for (RecipeNode recipe : item.recipes()) {
                acc[0]++;
                if (recipe.tier() != Tier.REJECTED) {
                    acc[1]++;
                }
                if (recipe.ingredients() != null) {
                    for (IngredientNode ing : recipe.ingredients()) {
                        if (ing.candidates() != null) {
                            for (ItemNode cand : ing.candidates()) {
                                if (!collectSummary(cand, acc)) {
                                    allForced = false;
                                }
                            }
                        }
                    }
                }
            }
        }
        return allForced;
    }

    private List<String> itemBadges(ItemNode item) {
        List<String> badges = new ArrayList<>();
        if (item.state() == State.CYCLE) {
            badges.add("↻");
        }
        if (item.craftability() == Craftability.UNKNOWN) {
            badges.add("⚠");
        }
        if (item.recipes() != null) {
            for (RecipeNode recipe : item.recipes()) {
                if (recipe.isStickyRestored()) {
                    badges.add("★");
                    break;
                }
            }
        }
        return badges;
    }

    private List<String> recipeBadges(RecipeNode recipe) {
        List<String> badges = new ArrayList<>();
        if (recipe.isStickyRestored()) {
            badges.add("★");
        }
        double eff = recipe.roundTripEfficiency();
        if (!Double.isNaN(eff) && eff >= RppConfig.minRoundTripEfficiency()) {
            badges.add("⇄");
        }
        if (recipe.tier() == Tier.REJECTED) {
            badges.add("⊘");
        }
        if (!recipe.recipe().trusted()) {
            badges.add("⚠");
        }
        if (recipe.isCostlyCollision()) {
            badges.add("⚑");
        }
        return badges;
    }

    private static int badgeColor(String badge) {
        return switch (badge) {
            case "★" -> ACCENT_COLOR;
            case "⇄" -> LOSSLESS_COLOR;
            case "⊘" -> REJECT_COLOR;
            case "⚠" -> WARN_COLOR;
            case "⚑" -> WARN_COLOR;
            case "↻" -> DIM_COLOR;
            default -> TEXT_COLOR;
        };
    }

    private static int yieldOf(RecipeNode recipe) {
        if (recipe.parent() instanceof ItemNode item && item.goal() instanceof appeng.api.stacks.AEItemKey goalKey) {
            Item goalItem = goalKey.getItem();
            for (var output : recipe.recipe().outputs()) {
                if (output.what() instanceof appeng.api.stacks.AEItemKey outKey && outKey.getItem() == goalItem) {
                    return (int) output.amount();
                }
            }
        }
        return recipe.recipe().outputs().isEmpty() ? 1 : (int) recipe.recipe().outputs().get(0).amount();
    }

    // --- flattening ---

    private void flattenItem(ItemNode item, int depth, @Nullable Set<TreeNode> keep) {
        if (keep != null && !keep.contains(item)) {
            return;
        }
        rows.add(TreeRow.item(item, depth));
        boolean hasRecipes = item.recipes() != null;
        boolean showChildren = keep == null ? item.state() == State.EXPANDED : hasRecipes;
        if (hasRecipes && showChildren) {
            for (int i = 0; i < item.recipes().size(); i++) {
                flattenRecipe(item.recipes().get(i), depth + 1, i, keep);
            }
            int more = session.builder().moreCount(item);
            if (more > 0) {
                rows.add(TreeRow.more(depth + 1, more));
            }
        }
    }

    private void flattenRecipe(RecipeNode recipe, int depth, int index, @Nullable Set<TreeNode> keep) {
        if (keep != null && !keep.contains(recipe)) {
            return;
        }
        rows.add(TreeRow.recipe(recipe, depth, index));
        if (recipe.ingredients() != null) {
            for (IngredientNode ing : recipe.ingredients()) {
                if (ing.candidates() != null) {
                    for (ItemNode cand : ing.candidates()) {
                        flattenItem(cand, depth + 1, keep);
                    }
                }
            }
        }
    }

    private Set<TreeNode> computeKeepSet(ItemNode root) {
        Set<TreeNode> keep = new HashSet<>();
        collectKeep(root, keep);
        return keep;
    }

    private boolean collectKeep(TreeNode node, Set<TreeNode> keep) {
        boolean self = matches(node);
        boolean child = false;
        if (node instanceof ItemNode item) {
            if (item.recipes() != null) {
                for (RecipeNode recipe : item.recipes()) {
                    if (collectKeep(recipe, keep)) {
                        child = true;
                    }
                }
            }
        } else if (node instanceof RecipeNode recipe) {
            if (recipe.ingredients() != null) {
                for (IngredientNode ing : recipe.ingredients()) {
                    if (ing.candidates() != null) {
                        for (ItemNode cand : ing.candidates()) {
                            if (collectKeep(cand, keep)) {
                                child = true;
                            }
                        }
                    }
                }
            }
        }
        if (self || child) {
            keep.add(node);
        }
        return self || child;
    }

    private boolean matches(TreeNode node) {
        if (filter.isEmpty()) {
            return true;
        }
        String name;
        if (node instanceof ItemNode item) {
            name = itemName(item).toLowerCase(Locale.ROOT);
        } else if (node instanceof RecipeNode recipe) {
            name = TreeRow.recipeName(recipe).toLowerCase(Locale.ROOT);
        } else {
            return false;
        }
        return name.contains(filter);
    }

    // --- tooltips ---

    private List<Component> tooltipFor(TreeRow row) {
        List<Component> lines = new ArrayList<>();
        if (row.isItem() && row.item() != null) {
            ItemNode item = row.item();
            lines.add(Component.literal(itemName(item)));
            if (item.craftability() != null) {
                lines.add(colored(I18n.get("rpp.gui.tip.craftability", item.craftability().name()), DIM_COLOR));
            }
            return lines;
        }
        if (row.isRecipe() && row.recipe() != null) {
            RecipeNode recipe = row.recipe();
            var view = recipe.recipe();
            lines.add(colored(view.id().toString(), ACCENT_COLOR));
            lines.add(colored(I18n.get("rpp.gui.tip.type", view.type().toString()), DIM_COLOR));
            lines.add(colored(I18n.get("rpp.gui.tip.yield", yieldOf(recipe)), DIM_COLOR));
            lines.add(colored(I18n.get("rpp.gui.tip.destination", recipe.destination().describe()), DIM_COLOR));
            if (recipe.rejectionReason() != null) {
                lines.add(colored(I18n.get("rpp.gui.tip.rejected", recipe.rejectionReason()), REJECT_COLOR));
            }
            if (view.inputs() != null) {
                for (var input : view.inputs()) {
                    String inputName = input.candidates().isEmpty() ? "?" : I18n.get(input.candidates().get(0).getDescriptionId());
                    lines.add(colored("  " + inputName, TEXT_COLOR));
                }
            }
            return lines;
        }
        return lines;
    }

    private static Component colored(String text, int color) {
        return Component.literal(text).withStyle(style -> style.withColor(TextColor.fromRgb(color)));
    }

    // --- drawing primitives ---

    private void drawTriangle(GuiGraphics graphics, int x, int y, boolean expanded) {
        int color = ACCENT_COLOR;
        if (expanded) {
            graphics.fill(x, y + 1, x + 7, y + 2, color);
            graphics.fill(x + 1, y + 3, x + 6, y + 4, color);
            graphics.fill(x + 2, y + 5, x + 5, y + 6, color);
            graphics.fill(x + 3, y + 7, x + 4, y + 8, color);
        } else {
            graphics.fill(x + 1, y + 1, x + 2, y + 8, color);
            graphics.fill(x + 2, y + 2, x + 3, y + 7, color);
            graphics.fill(x + 3, y + 3, x + 4, y + 6, color);
            graphics.fill(x + 4, y + 4, x + 5, y + 5, color);
        }
    }

    private void drawMenuArrow(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y + 2, x + 6, y + 3, ACCENT_COLOR);
        graphics.fill(x + 1, y + 4, x + 5, y + 5, ACCENT_COLOR);
        graphics.fill(x + 2, y + 6, x + 4, y + 7, ACCENT_COLOR);
    }

    private void renderSpinner(GuiGraphics graphics, int x, int y, float partialTick) {
        int phase = (int) (partialTick * 10) % 4;
        for (int i = 0; i < 4; i++) {
            int alpha = (i == phase) ? 0xFF : 0x55;
            int dx = (i % 2) * 4;
            int dy = (i / 2) * 4;
            graphics.fill(x + dx, y + dy, x + dx + 2, y + dy + 2, alpha | (ACCENT_COLOR & 0x00FFFFFF));
        }
    }
}
