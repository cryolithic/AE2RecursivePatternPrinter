package dev.cryolithic.rpp.gui.widget;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A minimal vertical scrollbar for the tree pane (DESIGN.md §11.1). The thumb
 * is proportional to the visible fraction of the row list; clicking the track
 * jumps one page and dragging the thumb scrolls row by row.
 */
public final class TreeScrollbar {
    private static final int WIDTH = 6;
    private static final int TRACK_COLOR = 0xFF2A2A2A;
    private static final int THUMB_COLOR = 0xFF8A8A8A;
    private static final int THUMB_HOVER_COLOR = 0xFFBFBFBF;

    private final int x;
    private int y;
    private int height;
    private int totalRows;
    private int visibleRows;
    private int scrollOffset;
    private boolean dragging;

    public TreeScrollbar(int x, int y, int height) {
        this.x = x;
        this.y = y;
        this.height = height;
    }

    /** Repositions and resizes the track (the pane is resizable in height). */
    public void setViewport(int y, int height) {
        this.y = y;
        this.height = height;
    }

    /** The full row count and how many rows fit in the viewport. */
    public void setRange(int totalRows, int visibleRows) {
        this.totalRows = totalRows;
        this.visibleRows = Math.max(1, visibleRows);
        int maxOffset = Math.max(0, totalRows - visibleRows);
        if (scrollOffset > maxOffset) {
            scrollOffset = maxOffset;
        }
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    public void setScrollOffset(int offset) {
        int maxOffset = Math.max(0, totalRows - visibleRows);
        this.scrollOffset = Math.max(0, Math.min(offset, maxOffset));
    }

    /** True when there is nothing to scroll. */
    public boolean isIdle() {
        return totalRows <= visibleRows;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isOverTrack(mouseX, mouseY)) {
            return false;
        }
        int thumbTop = thumbTop();
        if (mouseY >= thumbTop && mouseY < thumbTop + thumbHeight()) {
            dragging = true;
            return true;
        }
        // Track click: jump one page toward the click.
        int direction = mouseY < thumbTop ? -1 : 1;
        setScrollOffset(scrollOffset + direction * Math.max(1, visibleRows - 1));
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean wasDragging = dragging;
        dragging = false;
        return wasDragging;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double changeX, double changeY) {
        if (!dragging || button != 0) {
            return false;
        }
        int pixelsPerRow = Math.max(1, height / visibleRows);
        setScrollOffset(scrollOffset + (int) Math.round(changeY / pixelsPerRow));
        return true;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (isIdle()) {
            return;
        }
        graphics.fill(x, y, x + WIDTH, y + height, TRACK_COLOR);
        int thumbTop = thumbTop();
        int thumbHeight = thumbHeight();
        boolean hovered = mouseX >= x && mouseX < x + WIDTH && mouseY >= thumbTop && mouseY < thumbTop + thumbHeight;
        graphics.fill(x, thumbTop, x + WIDTH, thumbTop + thumbHeight, hovered ? THUMB_HOVER_COLOR : THUMB_COLOR);
    }

    private boolean isOverTrack(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height;
    }

    private int thumbHeight() {
        if (totalRows <= 0) {
            return 0;
        }
        int h = (int) ((double) height * visibleRows / totalRows);
        return Math.max(12, h);
    }

    private int thumbTop() {
        if (totalRows <= visibleRows) {
            return y;
        }
        int trackHeight = height - thumbHeight();
        int top = y + (int) ((double) scrollOffset / Math.max(1, totalRows - visibleRows) * trackHeight);
        return Math.max(y, Math.min(top, y + trackHeight));
    }
}
