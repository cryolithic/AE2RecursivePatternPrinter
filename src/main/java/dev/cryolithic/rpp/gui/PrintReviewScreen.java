package dev.cryolithic.rpp.gui;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import dev.cryolithic.rpp.RppConfig;
import dev.cryolithic.rpp.gui.widget.TreeScrollbar;
import dev.cryolithic.rpp.inventory.PatternPrinterMenu;
import dev.cryolithic.rpp.net.PrintRequestPayload;
import dev.cryolithic.rpp.print.PlanEntry;
import dev.cryolithic.rpp.tree.IngredientNode;
import dev.cryolithic.rpp.tree.ItemNode;
import dev.cryolithic.rpp.tree.PlanRow;
import dev.cryolithic.rpp.tree.RecipeNode;
import dev.cryolithic.rpp.tree.Tier;
import dev.cryolithic.rpp.tree.TreeNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import static dev.cryolithic.rpp.tree.TreeNode.State;

/**
 * The pre-print review screen (DESIGN.md §11.2). A child screen over the same
 * container as the tree screen: a flat, virtualized list of every pattern the
 * plan will produce, grouped by goal item with flagged rows first within each
 * group (raw inputs, force-selected rejected recipes, collision-flagged
 * recipes). Nothing is consumed until Confirm.
 *
 * <p>Back (and Esc) return to the tree with the selection unchanged; clicking
 * a row jumps back to the tree scrolled to that node; Confirm sends the
 * {@link PrintRequestPayload} and returns to the tree, where the
 * {@link PrintResultSink} renders the server's response. The tree session
 * lives in the parent screen and survives the round trip — this screen only
 * reads the plan, never mutates the tree.</p>
 */
public class PrintReviewScreen extends Screen {
    private static final int PANEL_WIDTH = 480;
    private static final int PANEL_HEIGHT = 264;
    private static final int ROW_HEIGHT = 14;
    private static final int ICON_SIZE = 16;
    private static final int PADDING = 4;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int TITLE_Y = 8;
    private static final int DIVIDER1_Y = 28;
    private static final int COUNT_Y = 32;
    private static final int DIVIDER2_Y = 48;
    private static final int LIST_TOP = 50;
    private static final int PANEL_BOTTOM_INSET = 4;

    private static final int PANEL_BG = 0xFF1C1C1C;
    private static final int PANEL_BORDER = 0xFF3A3A3A;
    private static final int LIST_BG = 0xFF161616;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int DIM_COLOR = 0xFF8A8A8A;
    private static final int ACCENT_COLOR = 0xFF7FB2FF;
    private static final int WARN_COLOR = 0xFFFFB84D;
    private static final int ERROR_COLOR = 0xFFE07070;
    private static final int LOSSLESS_COLOR = 0xFF6AB06A;

    private final PatternPrinterScreen parent;
    private final TreeScrollbar scrollbar;

    private int originX;
    private int originY;
    @Nullable
    private Button backButton;
    @Nullable
    private Button confirmButton;
    private final List<Row> rows = new ArrayList<>();
    private int hoverRow = -1;
    /** True when this screen is closing back to the parent (Back/Confirm/Esc/row jump). */
    private boolean closingToParent;

    private int untrustedCount;
    private int reversalCount;
    private int collisionCount;
    private int rejectedCount;
    private int rawCount;

    public PrintReviewScreen(PatternPrinterScreen parent) {
        super(Component.empty());
        this.parent = parent;
        this.scrollbar = new TreeScrollbar(0, 0, 1);
    }

    // --- lifecycle ---

    @Override
    protected void init() {
        super.init();
        layout();
    }

    /** A plain screen is not re-inited on window resize; re-layout instead. */
    @Override
    public void repositionElements() {
        layout();
    }

    private void layout() {
        originX = (this.width - PANEL_WIDTH) / 2;
        originY = (this.height - PANEL_HEIGHT) / 2;
        if (backButton != null) {
            this.removeWidget(backButton);
        }
        if (confirmButton != null) {
            this.removeWidget(confirmButton);
        }
        int buttonY = originY + TITLE_Y;
        confirmButton = Button.builder(Component.translatable("rpp.gui.review.confirm"), b -> onConfirm())
                .bounds(originX + PANEL_WIDTH - 68, buttonY, 60, 16).build();
        backButton = Button.builder(Component.translatable("rpp.gui.review.back"), b -> onBack())
                .bounds(originX + PANEL_WIDTH - 132, buttonY, 60, 16).build();
        this.addRenderableWidget(backButton);
        this.addRenderableWidget(confirmButton);
        scrollbar.setViewport(originY + LIST_TOP, listHeight());
        rebuildRows();
    }

    @Override
    public void removed() {
        if (closingToParent) {
            parent.onReviewClosed();
        } else {
            // The container closed while the review was open; the parent must
            // release the session and the result sink.
            parent.onReviewAbandoned();
        }
        super.removed();
    }

    /** Esc returns to the tree, same as Back. */
    @Override
    public void onClose() {
        onBack();
    }

    /** Rebuilds the list after a tree update published while the review is open. */
    public void refresh() {
        rebuildRows();
    }

    // --- actions ---

    private void onBack() {
        closingToParent = true;
        Minecraft.getInstance().setScreen(parent);
    }

    private void onConfirm() {
        ClientTreeSession session = parent.session();
        if (session != null && session.isRootReady()) {
            List<PlanEntry> entries = session.planEntries();
            ItemStack input = parent.menu().getSlot(PatternPrinterMenu.SLOT_INPUT).getItem();
            PrintRequestPayload payload = new PrintRequestPayload(input, entries);
            if (Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().getConnection().send(payload);
            }
        }
        closingToParent = true;
        Minecraft.getInstance().setScreen(parent);
    }

    /** Jumps back to the tree scrolled to the row's node; the tree session survives. */
    private void jumpTo(ItemNode node) {
        parent.focusNodeAfterInit(node);
        closingToParent = true;
        Minecraft.getInstance().setScreen(parent);
    }

    // --- list building ---

    private void rebuildRows() {
        rows.clear();
        untrustedCount = 0;
        reversalCount = 0;
        collisionCount = 0;
        rejectedCount = 0;
        rawCount = 0;
        ClientTreeSession session = parent.session();
        ItemNode root = session != null ? session.root() : null;
        if (root == null) {
            syncScroll();
            return;
        }
        Map<ResourceLocation, RecipeNode> recipeById = new HashMap<>();
        Map<Item, ItemNode> rawNodeByGoal = new HashMap<>();
        indexTree(root, recipeById, rawNodeByGoal);

        // Group the plan rows by goal item, first-seen order.
        Map<AEKey, List<PlanRow>> groups = new LinkedHashMap<>();
        for (PlanRow row : session.plan()) {
            groups.computeIfAbsent(row.output(), k -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<AEKey, List<PlanRow>> group : groups.entrySet()) {
            AEKey goal = group.getKey();
            List<PlanRow> groupRows = group.getValue();
            int sources = 0;
            ItemNode headerTarget = null;
            for (PlanRow row : groupRows) {
                if (!row.rawInput()) {
                    sources++;
                    if (headerTarget == null) {
                        headerTarget = itemNodeOf(row.recipeId(), recipeById);
                    }
                }
            }
            if (headerTarget == null && goal instanceof AEItemKey key) {
                headerTarget = rawNodeByGoal.get(key.getItem());
            }
            rows.add(new Row(Kind.HEADER, goal, sources, null, null, headerTarget));
            // Flagged rows first: raw input, force-selected rejected, collision;
            // stable within a rank so the plan order is kept.
            List<PlanRow> sorted = new ArrayList<>(groupRows);
            sorted.sort(Comparator.comparingInt(row -> flagRank(row, recipeById)));
            for (PlanRow row : sorted) {
                if (row.rawInput()) {
                    ItemNode target = goal instanceof AEItemKey key ? rawNodeByGoal.get(key.getItem()) : null;
                    rows.add(new Row(Kind.RAW, goal, 0, row, null, target));
                    rawCount++;
                } else {
                    RecipeNode recipe = row.recipeId() != null ? recipeById.get(row.recipeId()) : null;
                    ItemNode target = itemNodeOf(row.recipeId(), recipeById);
                    rows.add(new Row(Kind.RECIPE, goal, 0, row, recipe, target));
                    if (recipe != null) {
                        if (!recipe.recipe().trusted()) {
                            untrustedCount++;
                        }
                        double eff = recipe.roundTripEfficiency();
                        if (!Double.isNaN(eff) && eff >= RppConfig.minRoundTripEfficiency()) {
                            reversalCount++;
                        }
                        if (recipe.isCostlyCollision()) {
                            collisionCount++;
                        }
                        if (recipe.tier() == Tier.REJECTED) {
                            rejectedCount++;
                        }
                    }
                }
            }
        }
        syncScroll();
    }

    private void syncScroll() {
        scrollbar.setRange(rows.size(), visibleRowCount());
    }

    private static int flagRank(PlanRow row, Map<ResourceLocation, RecipeNode> recipeById) {
        if (row.rawInput()) {
            return 0;
        }
        RecipeNode recipe = row.recipeId() != null ? recipeById.get(row.recipeId()) : null;
        if (recipe != null && recipe.tier() == Tier.REJECTED) {
            return 1;
        }
        if (recipe != null && recipe.isCostlyCollision()) {
            return 2;
        }
        return 3;
    }

    /** The item node owning the recipe, or null when the id is not in the tree. */
    @Nullable
    private static ItemNode itemNodeOf(@Nullable ResourceLocation recipeId, Map<ResourceLocation, RecipeNode> recipeById) {
        if (recipeId == null) {
            return null;
        }
        RecipeNode recipe = recipeById.get(recipeId);
        return recipe != null && recipe.parent() instanceof ItemNode item ? item : null;
    }

    /** Records recipe ids and raw-input nodes by goal item for row lookups. */
    private static void indexTree(TreeNode node, Map<ResourceLocation, RecipeNode> recipeById,
            Map<Item, ItemNode> rawNodeByGoal) {
        if (node instanceof ItemNode item) {
            if ((item.isLeaf() || item.isRawInput() || item.state() == State.CYCLE)
                    && item.goal() instanceof AEItemKey key) {
                rawNodeByGoal.putIfAbsent(key.getItem(), item);
            }
            if (item.recipes() != null) {
                for (RecipeNode recipe : item.recipes()) {
                    recipeById.putIfAbsent(recipe.recipe().id(), recipe);
                    indexTree(recipe, recipeById, rawNodeByGoal);
                }
            }
        } else if (node instanceof RecipeNode recipe) {
            if (recipe.ingredients() != null) {
                for (IngredientNode ingredient : recipe.ingredients()) {
                    if (ingredient.candidates() != null) {
                        for (ItemNode candidate : ingredient.candidates()) {
                            indexTree(candidate, recipeById, rawNodeByGoal);
                        }
                    }
                }
            }
        }
    }

    // --- rendering ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderPanel(graphics, this.font, mouseX, mouseY, partialTick);
    }

    private void renderPanel(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        graphics.fill(originX, originY, originX + PANEL_WIDTH, originY + PANEL_HEIGHT, PANEL_BG);
        graphics.fill(originX, originY, originX + PANEL_WIDTH, originY + 1, PANEL_BORDER);
        graphics.fill(originX, originY + PANEL_HEIGHT - 1, originX + PANEL_WIDTH, originY + PANEL_HEIGHT, PANEL_BORDER);
        graphics.fill(originX, originY, originX + 1, originY + PANEL_HEIGHT, PANEL_BORDER);
        graphics.fill(originX + PANEL_WIDTH - 1, originY, originX + PANEL_WIDTH, originY + PANEL_HEIGHT, PANEL_BORDER);

        // Title: pattern count beside the blanks count, red when the plan exceeds the blanks.
        ClientTreeSession session = parent.session();
        int patterns = parent.patternCount();
        int blanks = session != null ? session.blanksCount(parent.menu()) : 0;
        String title = I18n.get("rpp.gui.review.title", patterns, blanks);
        graphics.drawString(font, title, originX + PADDING + 2, originY + TITLE_Y + 3,
                patterns > blanks ? ERROR_COLOR : TEXT_COLOR, false);

        graphics.fill(originX + 1, originY + DIVIDER1_Y, originX + PANEL_WIDTH - 1, originY + DIVIDER1_Y + 1, PANEL_BORDER);

        // Flag summary line.
        int cx = originX + PADDING + 2;
        cx = drawCountItem(graphics, font, cx, "⚠", untrustedCount,
                "rpp.gui.review.count.untrusted", WARN_COLOR);
        cx += 12;
        cx = drawCountItem(graphics, font, cx, "⇄", reversalCount,
                reversalCount == 1 ? "rpp.gui.review.count.reversal" : "rpp.gui.review.count.reversals",
                LOSSLESS_COLOR);
        cx += 12;
        cx = drawCountItem(graphics, font, cx, "⚑", collisionCount,
                collisionCount == 1 ? "rpp.gui.review.count.collision" : "rpp.gui.review.count.collisions",
                WARN_COLOR);
        cx += 12;
        cx = drawCountItem(graphics, font, cx, "⊘", rejectedCount,
                "rpp.gui.review.count.rejected", ERROR_COLOR);
        cx += 12;
        drawCountItem(graphics, font, cx, null, rawCount,
                rawCount == 1 ? "rpp.gui.review.count.raw" : "rpp.gui.review.count.raws", DIM_COLOR);

        graphics.fill(originX + 1, originY + DIVIDER2_Y, originX + PANEL_WIDTH - 1, originY + DIVIDER2_Y + 1, PANEL_BORDER);

        // Virtualized list: only the rows in the viewport are drawn.
        int listX = originX + 1;
        int listY = originY + LIST_TOP;
        int listW = PANEL_WIDTH - 2 - SCROLLBAR_WIDTH;
        int listH = listHeight();
        graphics.fill(listX, listY, listX + listW, listY + listH, LIST_BG);

        int visibleRows = visibleRowCount();
        int first = scrollbar.getScrollOffset();
        int last = Math.min(rows.size(), first + visibleRows);
        for (int i = first; i < last; i++) {
            int rowY = listY + (i - first) * ROW_HEIGHT;
            boolean hovered = i == hoverRow && isMouseInList(mouseX, mouseY, rowY, listW);
            renderRow(graphics, font, rows.get(i), rowY, listX, listW, hovered);
        }

        if (hoverRow >= 0 && hoverRow < rows.size()) {
            int rowY = listY + (hoverRow - first) * ROW_HEIGHT;
            if (isMouseInList(mouseX, mouseY, rowY, listW)) {
                List<Component> tooltip = tooltipFor(rows.get(hoverRow));
                if (!tooltip.isEmpty()) {
                    graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
                }
            }
        }

        scrollbar.setRange(rows.size(), visibleRows);
        scrollbar.render(graphics, mouseX, mouseY, partialTick);
    }

    private int drawCountItem(GuiGraphics graphics, Font font, int x, @Nullable String symbol, int count,
            String key, int color) {
        String text = I18n.get(key, count);
        int y = originY + COUNT_Y + 3;
        if (symbol != null) {
            graphics.drawString(font, symbol, x, y, color, false);
            x += font.width(symbol) + 2;
        }
        graphics.drawString(font, text, x, y, color, false);
        return x + font.width(text);
    }

    private void renderRow(GuiGraphics graphics, Font font, Row row, int rowY, int listX, int listW, boolean hovered) {
        if (hovered) {
            graphics.fill(listX, rowY, listX + listW, rowY + ROW_HEIGHT, ROW_HOVER);
        }
        int iconX = listX + PADDING;
        int textX = iconX + ICON_SIZE + 4;
        if (row.goalKey() instanceof AEItemKey key) {
            graphics.renderItem(key.toStack(), iconX, rowY + 1);
        }
        String name = row.goalKey() instanceof AEItemKey key ? key.toStack().getHoverName().getString() : "";
        switch (row.kind()) {
            case HEADER -> {
                graphics.drawString(font, name, textX, rowY + 3, TEXT_COLOR);
                String sources = row.sources() == 1
                        ? I18n.get("rpp.gui.review.source")
                        : I18n.get("rpp.gui.review.sources", row.sources());
                graphics.drawString(font, sources, textX + font.width(name) + 8, rowY + 3, DIM_COLOR);
            }
            case RECIPE -> renderRecipeRow(graphics, font, row, textX, rowY, listW);
            case RAW -> {
                String label = "← " + I18n.get("rpp.gui.review.raw_input");
                graphics.drawString(font, label, textX + font.width(name) + 8, rowY + 3, WARN_COLOR);
            }
        }
    }

    private void renderRecipeRow(GuiGraphics graphics, Font font, Row row, int textX, int rowY, int listW) {
        PlanRow plan = row.planRow();
        RecipeNode recipe = row.recipe();
        String name = row.goalKey() instanceof AEItemKey key ? key.toStack().getHoverName().getString() : "";
        String yieldText = "×" + plan.yield();
        String arrow = "← ";

        // Badges, right-aligned.
        List<String> badges = recipe != null ? recipeBadges(recipe) : List.of();
        int badgesStart = originX + 1 + listW - 4;
        int bx = badgesStart;
        for (int i = badges.size() - 1; i >= 0; i--) {
            String badge = badges.get(i);
            int w = font.width(badge);
            bx -= w + 2;
            graphics.drawString(font, badge, bx, rowY + 3, badgeColor(badge));
        }

        int idStart = textX + font.width(name) + 8 + font.width(yieldText) + 8 + font.width(arrow);
        int maxIdWidth = Math.max(0, bx - 8 - idStart);
        String id = recipe != null ? recipe.recipe().id().toString() : "";
        if (font.width(id) > maxIdWidth) {
            id = font.plainSubstrByWidth(id, maxIdWidth);
        }

        int nameColor = recipe != null && recipe.tier() == Tier.REJECTED ? DIM_COLOR : TEXT_COLOR;
        graphics.drawString(font, name, textX, rowY + 3, nameColor, false);
        graphics.drawString(font, yieldText, textX + font.width(name) + 8, rowY + 3, TEXT_COLOR, false);
        graphics.drawString(font, arrow, idStart - font.width(arrow), rowY + 3, DIM_COLOR, false);
        graphics.drawString(font, id, idStart, rowY + 3, DIM_COLOR, false);
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
            case "⚠" -> WARN_COLOR;
            case "⚑" -> WARN_COLOR;
            default -> TEXT_COLOR;
        };
    }

    private List<Component> tooltipFor(Row row) {
        List<Component> lines = new ArrayList<>();
        if (row.kind() == Kind.RECIPE && row.recipe() != null) {
            RecipeNode recipe = row.recipe();
            lines.add(colored(recipe.recipe().id().toString(), ACCENT_COLOR));
            lines.add(colored(I18n.get("rpp.gui.tip.destination", recipe.destination().describe()), DIM_COLOR));
            if (recipe.rejectionReason() != null) {
                lines.add(colored(I18n.get("rpp.gui.tip.rejected", recipe.rejectionReason()), ERROR_COLOR));
            }
            if (!recipe.recipe().trusted()) {
                lines.add(colored(I18n.get("rpp.gui.tip.untrusted"), WARN_COLOR));
            }
        } else if (row.kind() == Kind.RAW) {
            lines.add(colored(I18n.get("rpp.gui.review.raw_input_tip"), DIM_COLOR));
        }
        return lines;
    }

    private static Component colored(String text, int color) {
        return Component.literal(text).withStyle(style -> style.withColor(TextColor.fromRgb(color)));
    }

    // --- geometry and input ---

    private int listHeight() {
        return PANEL_HEIGHT - LIST_TOP - PANEL_BOTTOM_INSET;
    }

    private int visibleRowCount() {
        return Math.max(1, listHeight() / ROW_HEIGHT);
    }

    private boolean isMouseInList(double mouseX, double mouseY, int rowY, int listW) {
        int listX = originX + 1;
        return mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
    }

    private boolean isMouseInListArea(double mouseX, double mouseY) {
        int listX = originX + 1;
        int listY = originY + LIST_TOP;
        int listW = PANEL_WIDTH - 2 - SCROLLBAR_WIDTH;
        int listH = listHeight();
        return mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH;
    }

    private int rowAt(double mouseX, double mouseY) {
        if (!isMouseInListArea(mouseX, mouseY)) {
            return -1;
        }
        int listX = originX + 1;
        int listY = originY + LIST_TOP;
        int listW = PANEL_WIDTH - 2 - SCROLLBAR_WIDTH;
        int index = (int) ((mouseY - listY) / ROW_HEIGHT) + scrollbar.getScrollOffset();
        if (index < scrollbar.getScrollOffset() || index >= rows.size()) {
            return -1;
        }
        return index;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (scrollbar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            Row r = rows.get(row);
            if (r.jumpTarget() != null) {
                jumpTo(r.jumpTarget());
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scrollbar.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double changeX, double changeY) {
        if (scrollbar.mouseDragged(mouseX, mouseY, button, changeX, changeY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, changeX, changeY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount, double horizontalAmount) {
        if (isMouseInListArea(mouseX, mouseY)) {
            scrollbar.setScrollOffset(scrollbar.getScrollOffset() - (int) Math.round(amount * 3));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount, horizontalAmount);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        hoverRow = rowAt(mouseX, mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    // --- row model ---

    private enum Kind {
        /** A group header: goal item icon, name, source count. */
        HEADER,
        /** A selected recipe: icon, name, yield, recipe id, badges. */
        RECIPE,
        /** A raw input: supplied from storage, never printed. */
        RAW
    }

    private record Row(
            Kind kind,
            AEKey goalKey,
            int sources,
            @Nullable PlanRow planRow,
            @Nullable RecipeNode recipe,
            @Nullable ItemNode jumpTarget) {
    }
}
