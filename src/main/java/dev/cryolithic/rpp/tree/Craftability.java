package dev.cryolithic.rpp.tree;

/**
 * Answer of the {@code CraftabilityOracle} for one item
 * (DESIGN.md §8.6). {@code UNKNOWN} on budget exhaustion, and {@code UNKNOWN}
 * is never treated as collapsible.
 */
public enum Craftability {
    /** Exactly one way to bottom out; the branch is forced. */
    CRAFTABLE_UNAMBIGUOUS,
    /** Several ways to bottom out; a real choice exists. */
    CRAFTABLE_AMBIGUOUS,
    /** No usable recipe; supply as a raw input. */
    LEAF,
    /** Budget exhausted before a verdict. Never collapsible. */
    UNKNOWN
}
