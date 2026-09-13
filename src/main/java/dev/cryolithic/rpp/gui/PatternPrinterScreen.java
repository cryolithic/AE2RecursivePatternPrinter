package dev.cryolithic.rpp.gui;

import dev.cryolithic.rpp.RppConfig;
import dev.cryolithic.rpp.gui.widget.TreeWidget;
import dev.cryolithic.rpp.inventory.PatternPrinterMenu;
import dev.cryolithic.rpp.net.PrintRequestPayload;
import dev.cryolithic.rpp.net.PrintResultPayload;
import dev.cryolithic.rpp.print.PlanEntry;
import dev.cryolithic.rpp.tree.ItemNode;
import dev.cryolithic.rpp.tree.PlanCountCache;
import dev.cryolithic.rpp.tree.TreeSelection;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * The Recursive Pattern Printer GUI (DESIGN.md §11.1). An AE2-styled
 * container screen: the recipe tree pane on the left (checkboxes, disclosure
 * triangles, badges, search, per-node menu, live pattern counter) and the
 * container slots (input pattern, blanks, 27 outputs, player inventory) on the
 * right. The print button opens the pre-print review (DESIGN.md §11.2) when
 * {@code alwaysReviewBeforePrint} is set or the plan carries a flag, or
 * sends the {@link PrintRequestPayload} directly; the {@link PrintResultPayload}
 * response is rendered on this screen via {@link PrintResultSink}.
 *
 * <p>The tree is a client-side, never-persisted derived state: a
 * {@link ClientTreeSession} is created on open from the input slot's encoded
 * pattern, built lazily on a background thread, and discarded on close.</p>
 */
public class PatternPrinterScreen extends AbstractContainerScreen<PatternPrinterMenu> {
    private static final int LAYOUT_WIDTH = 450;
    private static final int LAYOUT_HEIGHT = 210;
    private static final int TREE_WIDTH = 256;
    private static final int TREE_HEIGHT = 150;
    private static final int TITLE_Y = 8;
    private static final int SEARCH_Y = 26;
    private static final int TREE_Y = 44;
    private static final int STATUS_Y = 198;
    private static final int CONTAINER_LEFT_OFFSET = 264;
    private static final int CONTAINER_TOP_OFFSET = -9;

    private static final int PANEL_BG = 0xFF1C1C1C;
    private static final int PANEL_BORDER = 0xFF3A3A3A;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int DIM_COLOR = 0xFF8A8A8A;
    private static final int ACCENT_COLOR = 0xFF7FB2FF;
    private static final int OK_COLOR = 0xFF6AB06A;
    private static final int WARN_COLOR = 0xFFFFB84D;
    private static final int ERROR_COLOR = 0xFFE07070;

    @Nullable
    private ClientTreeSession session;
    /** O(1) pattern-count cache for the render path (DESIGN.md §9, §11.1). */
    private final PlanCountCache planCountCache = new PlanCountCache(TreeSelection::patternCount);
    @Nullable
    private TreeWidget treeWidget;
    @Nullable
    private EditBox searchBox;
    @Nullable
    private Button selectAllButton;
    @Nullable
    private Button deselectAllButton;
    @Nullable
    private Button clearStickyButton;
    @Nullable
    private Button printButton;
    @Nullable
    private PrintResultPayload lastResult;
    private int originX;
    private int originY;
    @Nullable
    private PrintReviewScreen reviewScreen;
    /** The tree node to scroll to once the tree pane re-initializes after a review round trip. */
    @Nullable
    private ItemNode focusTarget;
    /** The user-resized tree pane height (DESIGN.md §11.1); restored on re-init. */
    private int treeHeight = TREE_HEIGHT;

    public PatternPrinterScreen(PatternPrinterMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();
        originX = (this.width - LAYOUT_WIDTH) / 2;
        originY = (this.height - LAYOUT_HEIGHT) / 2;
        this.imageWidth = 178;
        this.imageHeight = 210;
        this.leftPos = originX + CONTAINER_LEFT_OFFSET;
        this.topPos = originY + CONTAINER_TOP_OFFSET;

        if (session == null) {
            Level level = this.minecraft.level;
            if (level != null) {
                session = ClientTreeSession.create(this.menu, level);
                if (session != null) {
                    session.setOnUpdate(s -> onSessionUpdate());
                }
            }
        }
        PrintResultSink.set(this);

        int treeX = originX + 8;
        int treeY = originY + TREE_Y;
        treeWidget = new TreeWidget(session, treeX, treeY, TREE_WIDTH, treeHeight);
        treeWidget.setOnRebuild(this::refreshPatternCount);
        treeWidget.setOnHeightChange(h -> treeHeight = h);

        searchBox = new EditBox(this.font, treeX, originY + SEARCH_Y, TREE_WIDTH, 16, Component.translatable("rpp.gui.search"));
        searchBox.setMaxLength(64);
        searchBox.setResponder(text -> treeWidget.setFilter(text));
        searchBox.setBordered(true);

        int buttonY = originY + TITLE_Y;
        int buttonWidth = 40;
        int gap = 4;
        selectAllButton = Button.builder(Component.translatable("rpp.gui.select_all"), b -> onSelectAll())
                .bounds(treeX + TREE_WIDTH - buttonWidth * 3 - gap * 2, buttonY, buttonWidth, 16).build();
        deselectAllButton = Button.builder(Component.translatable("rpp.gui.deselect_all"), b -> onDeselectAll())
                .bounds(treeX + TREE_WIDTH - buttonWidth * 2 - gap, buttonY, buttonWidth, 16).build();
        clearStickyButton = Button.builder(Component.translatable("rpp.gui.clear_sticky"), b -> onClearSticky())
                .bounds(treeX + TREE_WIDTH - buttonWidth, buttonY, buttonWidth, 16).build();

        printButton = Button.builder(Component.translatable("rpp.gui.print"), b -> onPrint())
                .bounds(treeX + TREE_WIDTH - 64, originY + STATUS_Y, 64, 16).build();

        this.addRenderableWidget(searchBox);
        this.addRenderableWidget(selectAllButton);
        this.addRenderableWidget(deselectAllButton);
        this.addRenderableWidget(clearStickyButton);
        this.addRenderableWidget(printButton);
        if (focusTarget != null) {
            treeWidget.focusNode(focusTarget);
            focusTarget = null;
        }
    }


    private void onSessionUpdate() {
        if (treeWidget != null) {
            treeWidget.rebuildRows();
        }
        if (reviewScreen != null) {
            reviewScreen.refresh();
        }
    }

    /**
     * Recomputes the cached pattern count on the main thread, after every
     * event that can change it — selection, expand/collapse, rebuild and
     * filter changes all rebuild the widget's rows, which fires this. The
     * render path then reads the cache in O(1) (DESIGN.md §9, §11.1).
     */
    private void refreshPatternCount() {
        planCountCache.invalidate();
        planCountCache.count(session != null ? session.root() : null);
    }

    /** The cached pattern count; O(1) on the render path. */
    public int patternCount() {
        return planCountCache.count(session != null ? session.root() : null);
    }

    private void onSelectAll() {
        if (session != null) {
            session.selectAll();
            if (treeWidget != null) {
                treeWidget.rebuildRows();
            }
        }
    }

    private void onDeselectAll() {
        if (session != null) {
            session.deselectAll();
            if (treeWidget != null) {
                treeWidget.rebuildRows();
            }
        }
    }

    private void onClearSticky() {
        if (session != null) {
            session.clearStickyChoices();
            if (treeWidget != null) {
                treeWidget.rebuildRows();
            }
        }
    }

    private void onPrint() {
        if (session == null || !session.isRootReady()) {
            return;
        }
        List<PlanEntry> entries = session.planEntries();
        int batchCap = RppConfig.maxPrintBatch();
        if (entries.size() > batchCap) {
            // The server would reject the whole job; say so locally instead of
            // round-tripping a request we already know fails (DESIGN.md §10.2 step 2).
            this.lastResult = new PrintResultPayload(0, entries.size(),
                    I18n.get("rpp.gui.result.batch_cap", batchCap));
            return;
        }
        // A flagged plan is always reviewed (DESIGN.md §11.2, §12): the
        // toggle only decides whether an unflagged plan is reviewed.
        if (RppConfig.alwaysReviewBeforePrint()
                || TreeSelection.hasFlags(session.root(), session.plan(), RppConfig.minRoundTripEfficiency())) {
            PrintReviewScreen review = new PrintReviewScreen(this);
            this.reviewScreen = review;
            this.minecraft.setScreen(review);
            return;
        }
        ItemStack input = this.menu.getSlot(PatternPrinterMenu.SLOT_INPUT).getItem();
        PrintRequestPayload payload = new PrintRequestPayload(input, entries);
        if (this.minecraft.getConnection() != null) {
            this.minecraft.getConnection().send(payload);
        }
    }

    /** Renders the server's print result; called on the main thread by the payload handler. */
    public void onPrintResult(PrintResultPayload result) {
        this.lastResult = result;
    }

    // --- review screen round trip (DESIGN.md §11.2) ---

    /** The tree session, kept alive across the review round trip. */
    @Nullable
    public ClientTreeSession session() {
        return session;
    }

    /** The container menu, so the review screen can read the input pattern. */
    public PatternPrinterMenu menu() {
        return this.menu;
    }

    /** Called by the review screen when it returns to the tree (Back/Confirm/Esc/row jump). */
    public void onReviewClosed() {
        this.reviewScreen = null;
    }

    /** Called by the review screen when the container closed while the review was open. */
    public void onReviewAbandoned() {
        this.reviewScreen = null;
        PrintResultSink.set(null);
        if (session != null) {
            session.close();
            session = null;
        }
    }

    /** Queues a tree node to scroll to once the tree pane re-initializes after the review round trip. */
    public void focusNodeAfterInit(ItemNode node) {
        this.focusTarget = node;
    }

    // --- rendering ---

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // Container panel behind the slots (screen coordinates; the pose is not translated here).
        int panelX = this.leftPos + 4;
        int panelY = this.topPos + 13;
        int panelW = 172;
        int panelH = 196;
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, PANEL_BORDER);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, PANEL_BORDER);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelH, PANEL_BORDER);
        graphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, PANEL_BORDER);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (treeWidget != null) {
            treeWidget.render(graphics, this.font, mouseX, mouseY, partialTick);
        }
        renderTitleAndStatus(graphics, mouseX, mouseY, partialTick);
    }

    private void renderTitleAndStatus(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Title.
        graphics.drawString(this.font, this.title, originX + 8, originY + TITLE_Y + 3, TEXT_COLOR, false);

        // Title tooltip: an honest note about where recipes come from and how
        // sources are ranked (DESIGN.md §17.3).
        int titleWidth = this.font.width(this.title);
        if (mouseX >= originX + 8 && mouseX < originX + 8 + titleWidth
                && mouseY >= originY + TITLE_Y && mouseY < originY + TITLE_Y + 12) {
            graphics.renderComponentTooltip(this.font,
                    List.of(Component.translatable("rpp.gui.title_tip")), mouseX, mouseY);
        }

        if (session == null) {
            // No valid pattern or index not ready: show a message in the tree area.
            // Distinguish the two: an empty input slot asks for a pattern; a
            // pattern that is present but the index is still building says so.
            int treeX = originX + 8;
            int treeY = originY + TREE_Y;
            graphics.fill(treeX, treeY, treeX + TREE_WIDTH, treeY + TREE_HEIGHT, 0xFF161616);
            boolean hasPattern = !this.menu.getSlot(PatternPrinterMenu.SLOT_INPUT).getItem().isEmpty();
            String message = hasPattern ? I18n.get("rpp.gui.index_building") : I18n.get("rpp.gui.no_pattern");
            graphics.drawCenteredString(this.font, message, treeX + TREE_WIDTH / 2, treeY + TREE_HEIGHT / 2, DIM_COLOR);
            return;
        }

        // Status line: live pattern counter beside the blanks count, red when it exceeds blanks.
        int patterns = patternCount();
        int blanks = session.blanksCount(this.menu);
        int statusX = originX + 8;
        int statusY = originY + STATUS_Y + 3;
        String patternText = patterns + " " + I18n.get("rpp.gui.patterns");
        int patternColor = patterns > blanks ? ERROR_COLOR : TEXT_COLOR;
        graphics.drawString(this.font, patternText, statusX, statusY, patternColor, false);
        int patternWidth = this.font.width(patternText);
        String blankText = blanks + " " + I18n.get("rpp.gui.blanks");
        graphics.drawString(this.font, blankText, statusX + patternWidth + 12, statusY, DIM_COLOR, false);

        // Print result, if any.
        if (lastResult != null) {
            String resultText;
            int resultColor;
            if (lastResult.reason() != null) {
                resultText = I18n.get("rpp.gui.result.rejected", lastResult.reason());
                resultColor = ERROR_COLOR;
            } else if (lastResult.skipped() > 0) {
                resultText = I18n.get("rpp.gui.result.partial", lastResult.printed(), lastResult.skipped());
                resultColor = WARN_COLOR;
            } else {
                resultText = I18n.get("rpp.gui.result.ok", lastResult.printed());
                resultColor = OK_COLOR;
            }
            int resultX = originX + 8 + this.font.width(patternText) + 12 + this.font.width(blankText) + 12;
            graphics.drawString(this.font, resultText, resultX, statusY, resultColor, false);
        }
    }

    // --- input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (treeWidget != null && treeWidget.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (searchBox != null && searchBox.mouseClicked(mouseX, mouseY, button)) {
            this.setFocused(searchBox);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (treeWidget != null && treeWidget.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double changeX, double changeY) {
        if (treeWidget != null && treeWidget.mouseDragged(mouseX, mouseY, button, changeX, changeY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, changeX, changeY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount, double horizontalAmount) {
        if (treeWidget != null && treeWidget.mouseScrolled(mouseX, mouseY, amount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount, horizontalAmount);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (treeWidget != null) {
            treeWidget.mouseMoved(mouseX, mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (searchBox != null && searchBox.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (session == null && this.minecraft.level != null) {
            ClientTreeSession newSession = ClientTreeSession.create(this.menu, this.minecraft.level);
            if (newSession != null) {
                session = newSession;
                session.setOnUpdate(s -> onSessionUpdate());
                if (treeWidget != null) {
                    treeWidget.setSession(session);
                }
            }
        } else if (session != null) {
            session.rebuildIfStale();
        }
    }

    @Override
    public void removed() {
        if (reviewScreen != null) {
            // The pre-print review child is taking over; keep the tree session
            // and the result sink alive for the round trip (DESIGN.md §11.2).
        } else {
            PrintResultSink.set(null);
            if (session != null) {
                session.close();
                session = null;
            }
        }
        super.removed();
    }
}
