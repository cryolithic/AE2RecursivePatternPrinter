# Repository Guidelines

## Project Overview

**ae2RecursivePatternPrinter** is a NeoForge mod that extends [Applied Energistics 2](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2). It adds a **Recursive Pattern Printer** block:

1. A pattern is placed in the block's input slot.
2. A GUI shows a **recipe tree** of every recipe that can produce the pattern's output, recursively for each ingredient.
3. Branches that already have a valid craft are **collapsed by default** but expandable, so the user can pick alternate paths.
4. A **Print** button encodes the selected patterns from blank patterns and stores the results in the block's inventory.

Recipe trees can be enormous, so tree construction must stay efficient: caching, memoization, cycle detection, and node caps are core requirements, not optimizations.

The repository is currently empty; this document describes the target architecture and the conventions to follow when scaffolding.

## Architecture & Data Flow

```
pattern in input slot
  → EncodedPattern (AE2 API)
  → RecipeTreeBuilder: item → all matching recipes (AE2 recipe manager), recurse on inputs
  → Tree model: nodes = items, edges = recipes; collapsed/expanded + selected state
  → GUI: tree widget (expand/collapse, select), print button
  → PrintJob: consumes blank patterns, encodes selected patterns, outputs to block inventory
```

- **Block & inventory**: `Block` + `BlockEntity` with a `Container` (input pattern slot, blank patterns, output patterns). State is NBT-persisted and synced to the client via update tags.
- **Recipe tree**: a pure, GUI-independent data structure so it can be built, cached, and unit-tested without a Minecraft runtime.
- **GUI**: AE2-style container + screen; tree widget with expand/collapse and per-branch selection; the print button starts the job.
- **Print job**: consumes blank patterns one at a time, writes the selected `EncodedPattern`s, places finished patterns in the output slot, and respects inventory capacity.

## Key Directories

Standard NeoForge mod layout (create from the MDK):

| Path | Purpose |
|---|---|
| `src/main/java/<group>/rpp/` | Mod code: entrypoint, block, block entity, tree, GUI |
| `src/main/resources/META-INF/neoforge.mods.toml` | Mod metadata + AE2 dependency |
| `src/main/resources/assets/rpp/` | lang, models, textures, blockstates |
| `src/main/resources/data/rpp/` | Block/item JSON, recipes (if any) |
| `build.gradle`, `settings.gradle`, `gradle.properties` | Build config; pin versions in `gradle.properties` |
| `build/libs/` | Built mod jar |

Suggested package layout under `<group>.rpp`: `init/` (DeferredRegisters), `block/`, `inventory/` (block entity + container), `tree/` (builder + model), `gui/`.

## Development Commands

```bash
./gradlew build        # compile + jar (build/libs/)
./gradlew runClient    # dev client with the mod
./gradlew runServer    # dev server
./gradlew test         # unit tests
./gradlew runData      # datagen (if added)
./gradlew clean
```

First run downloads NeoForge + AE2 dependencies; expect a long initial build.

## Code Conventions & Common Patterns

- **Java**: Java 21 (Minecraft 1.21.1); pin it in `gradle.properties`.
- **AE2 integration**: prefer the public `appeng.api.*` API over `appeng.core` internals — internals break between AE2 versions. **There is no `appeng.api.recipes` package and AE2 offers no reverse recipe lookup** — build the `item → recipes` index from the vanilla `RecipeManager` yourself (`DESIGN.md` §6). Pattern encoding goes through `appeng.api.crafting.PatternDetailsHelper`; exact signatures are recorded in `DESIGN.md` §10.3.
- **Registration**: register blocks/items/menus via `DeferredRegister`; one register per type, bound in the mod constructor.
- **Block entity**: save/load in `saveAdditional`/`load`; sync to the client via `getUpdateTag`/`getUpdatePacket`; never hold item stacks in fields without NBT persistence.
- **Recipe lookups are expensive**: cache `item → recipes` results and invalidate on recipe reload; never build the full tree inside `tick()`.
- **Tree efficiency**: memoize per-item recipe lists; track visited items on the current path for cycle detection (recursive recipes exist); cap depth and per-node recipe count (e.g. top N + a "more" marker); collapse any branch whose subtree is fully craftable.
- **Error handling**: log via `LogManager.getLogger()`; fail print jobs gracefully (log + cancel) instead of throwing across block-entity boundaries.
- **Threading**: Minecraft/NeoForge is single-threaded for world access. Build heavy trees off-thread from a recipe-data snapshot, or lazily per GUI frame; publish results back on the main thread.
- **Naming**: `PascalCase` classes, `camelCase` members. Pick one mod id when scaffolding (`rpp` or `recursive_pattern_printer`) and use it consistently in packages, resource folders, and the toml.

## Important Files

Files to create when scaffolding, in entry-point-first order:

- `build.gradle` / `settings.gradle` — NeoForge ModDev plugin, AE2 dependency
- `src/main/resources/META-INF/neoforge.mods.toml` — mod id, loader, dependency on `ae2`
- `<...>/rpp/RppMod.java` — `@Mod` entrypoint, wires registers
- `<...>/rpp/init/` — block, item, and menu-type registrations
- `<...>/rpp/tree/RecipeTreeBuilder.java` — the core algorithm; keep it pure and testable
- `<...>/rpp/gui/` — container + screen for the tree

## Runtime/Tooling Preferences

Versions are pinned to match **All The Mods 10 v5.3**, verified against the live instance at
`~/.local/share/PrismLauncher/instances/All the Mods 10 - ATM10/`. See `DESIGN.md` §3.

- **Minecraft**: 1.21.1
- **NeoForge**: 21.1.217 — Prism pack data, `mmc-pack.json` and `latest.log` all agree. 217 is a hard floor, not a preference: the pack shipped 21.1.215 and crashed because a bundled mod requires 217 or above. Ignore the stale 21.1.215 in `flame/manifest.json` (CurseForge import metadata).
- **Applied Energistics 2**: 19.2.17
- **JEI**: 19.25.1.332 (optional compile-time dependency only)
- **JDK**: 21 compile target. JDK 21.0.12 is already installed at `/usr/lib/jvm/java-21-openjdk`; nothing to install. `java` on `PATH` is SDKMAN's 25.0.4, so declare `toolchain { languageVersion = JavaLanguageVersion.of(21) }` explicitly — Gradle auto-detection finds the system JDK 21.
- **Build**: Gradle 8.x via the wrapper (`./gradlew`); use the NeoForge ModDev plugin (`net.neoforged.moddev`), not legacy NeoGradle.
- **Dependencies**: NeoForge maven `https://maven.neoforged.net/releases`; AE2 from the CurseForge maven (`https://cursemaven.com`) or Modrinth maven — pin `ae2=19.2.17` in `gradle.properties`.
- No extra runtime requirements; the mod runs in a standard NeoForge client/server.

`~/ATM-10/` holds configs only, no `mods/`. Read the PrismLauncher instance for jar versions.

Target environment: the mod must load inside ATM10 alongside its other AE2 addons — AdvancedAE 1.6.7, AE2WTLib 19.3.0, ExtendedAE 2.2.25, MEGA Cells 4.10.1, AE2 Crafting Tweaks, AE2 Import/Export Card, AE2 JEI Integration, AE2 Network Analyzer — so keep the public API surface compatible with AE2 19.2.17. Note `recursiveae2patternprovider` 1.0.7/1.0.8 are present but disabled (see `DESIGN.md` §3.3).

## Testing & QA

- **Unit tests**: JUnit 5 under `src/test/java`. The recipe tree builder is the prime target (pure logic: memoization, cycle handling, collapse rules).
- **In-game QA** (primary): `./gradlew runClient` → place the block → insert a pattern with several ingredients → verify the tree renders, collapse/expand works, and Print produces patterns in the inventory.
- **Edge cases to verify**: recursive recipes (A → B → A), items with hundreds of recipes (performance), items with no recipes (leaf nodes), inventory full during print.
- No coverage requirement yet; keep the tree builder dependency-free so it is unit-testable without a Minecraft runtime.
