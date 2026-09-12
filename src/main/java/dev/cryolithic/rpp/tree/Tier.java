package dev.cryolithic.rpp.tree;

/**
 * Tier of a recipe candidate (DESIGN.md §8.4.1). Decides the default
 * checkbox state; the player can move anything across the line.
 */
public enum Tier {
    /** Best-ranked ordinary production recipe for this item. Checked by default. */
    PRIMARY,
    /** A genuinely useful second path: lossless unpacking, ore multiplication, a cheaper variant. Checked by default. */
    ALTERNATE,
    /** Junk. Shown dimmed with a reason, one click from being included anyway. Unchecked by default. */
    REJECTED
}
