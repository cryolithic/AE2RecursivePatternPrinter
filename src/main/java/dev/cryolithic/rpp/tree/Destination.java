package dev.cryolithic.rpp.tree;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Where a printed pattern physically goes. Fully determined by the encoder
 * chosen at encode time (DESIGN.md §17.7.2). Two selected sources for one
 * item collide when they share a destination class.
 *
 * <p>Modelled as a small class because {@link Kind#MACHINE} carries a
 * machine-type string (e.g. {@code "mekanism"}, {@code "furnace"}).</p>
 */
public final class Destination {
    public enum Kind {
        /** Crafting patterns, feeding molecular assemblers. */
        ASSEMBLER,
        /** Processing patterns, on the provider of that machine. Carries the machine type. */
        MACHINE,
        /** Stonecutting patterns. */
        STONECUTTER,
        /** Smithing-table patterns. */
        SMITHING
    }

    private final Kind kind;
    @Nullable
    private final String machineType;

    private Destination(Kind kind, @Nullable String machineType) {
        this.kind = kind;
        this.machineType = machineType;
    }

    public static Destination assembler() {
        return new Destination(Kind.ASSEMBLER, null);
    }

    public static Destination machine(String machineType) {
        return new Destination(Kind.MACHINE, machineType);
    }

    public static Destination stonecutter() {
        return new Destination(Kind.STONECUTTER, null);
    }

    public static Destination smithing() {
        return new Destination(Kind.SMITHING, null);
    }

    public Kind kind() {
        return kind;
    }

    /** Machine type for {@link Kind#MACHINE}, else null. */
    @Nullable
    public String machineType() {
        return machineType;
    }

    /** e.g. {@code "ASSEMBLER"}, {@code "MACHINE:mekanism"}, {@code "STONECUTTER"}. */
    public String describe() {
        return kind == Kind.MACHINE ? "MACHINE:" + machineType : kind.name();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Destination other)) {
            return false;
        }
        return kind == other.kind && Objects.equals(machineType, other.machineType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, machineType);
    }

    @Override
    public String toString() {
        return describe();
    }
}
