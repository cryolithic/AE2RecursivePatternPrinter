# Recursive Pattern Printer — Design Specification

Status: **draft v3**, pre-scaffold. Versions, AE2 API surface and AE2's
pattern-selection behaviour all verified against the live ATM10 instance and the
decompiled 19.2.17 jar on 2026-09-11 (§14, §17). Companion to `AGENTS.md`
(conventions); this document is the architecture of record.

---

## 1. Scope

### 1.1 What it does

A block that takes one **encoded AE2 pattern**, works out every chain of recipes
that could produce that pattern's output, **selects every legitimate way to
obtain each ingredient while rejecting the junk**, shows the result as an
interactive tree where any recipe can be checked or unchecked, and then
**encodes the whole chosen set into blank patterns** in one action.

### 1.2 The actual problem

Recursion is the easy part. Deciding *which* recipes deserve a pattern is the
hard part.

The prior art in the target instance (§3.3) emits patterns for every possible
child recipe. Field result, from the person who ran it: it "frequently tried to
use the wrong source for an item." But the fix is not to narrow it down to one
source per item, because **multiple sources for the same item are often exactly
what you want**.

The canonical case, iron ingot:

| Recipe | Verdict | Why |
|---|---|---|
| Block of Iron → 9 Iron Ingots | **want it** | Lossless unpacking of a storage form. If blocks are in the network, this is the cheapest ingots available |
| Raw Iron → Mekanism ore chain → 4 Iron Ingots | **want it** | Ore multiplication. The whole point of building the chain |
| Raw Iron → smelt → 1 Iron Ingot | **want it** | Fallback when the chain is busy or unbuilt |
| Iron Pickaxe → melt → 1 Iron Ingot | **never** | Lossy recycling. Costs 3 ingots and 2 sticks to make, returns 1 |

The first three should all be printed. The fourth should never be. So the
product is:

> **print every legitimate acquisition path, print no junk, and let the player
> override both directions.**

That reshapes selection from "pick one winner per item" to "**choose a set**".
Three consequences run through the design:

1. **Selection is multi-select** (§7.1, §8.4). An item node holds a *set* of
   chosen recipes, not an index.
2. **Reversal is not disqualifying, loss is.** Block → ingots and pickaxe →
   ingot are both reversals. What separates them is round-trip efficiency
   (§8.4.2), not direction.
3. **Forced choices are silent, real choices are not.** Where only one recipe
   exists there is nothing to ask, so it is selected automatically and the
   branch collapses (§8.5). Attention is spent only where a decision exists.

Getting this wrong permissively reproduces the prior art. Getting it wrong
conservatively loses the ore-multiplication path the player built the base for.

**This premise is verified against AE2's crafting calculator** (§17). Multiple
patterns per item do work, AE2 falls through to the next source when a branch
cannot be satisfied, and it will not loop between packing and unpacking. Two
corrections came out of that reading: AE2 orders sources by **pattern provider
priority**, not by anything we can encode (§17.2), and every extra source makes
AE2's calculation step rather than batch (§17.5).

### 1.3 In scope (v1)

- One block, one block entity, one GUI.
- Reverse recipe lookup over every recipe the recipe manager knows about.
- Interactive tree: expand, collapse, **check or uncheck any recipe**, exclude a
  branch, select-all and deselect-all at any node or tree-wide (§11.1).
- **Multi-select sources** so several acquisition paths for one item are printed
  together (§8.4).
- **Path tiering** that separates lossless alternates from lossy recycling by
  round-trip efficiency (§8.4.2).
- **Sticky source sets** that persist across ingredients and sessions (§8.7).
- **Pre-print review** listing every pattern and its source before blanks are
  consumed (§11.2).
- Batch pattern encoding into blank patterns held by the block.
- Crafting patterns and processing patterns.
- Config caps so a pathological item cannot lock the game.

### 1.4 Out of scope (v1)

- Fluid, gas, chemical or energy inputs beyond what a recipe exposes through the
  vanilla `Ingredient` API. They appear as missing inputs; §6.4 covers this.
- Smart pattern options (substitutions, fluid substitutions) beyond a global
  default toggle.
- Automatically pushing finished patterns into an ME network. Patterns land in
  the block's own output inventory; the player moves them.
- Any awareness of what the player's network can already craft. The tree is
  recipe-space only.
- Multiblock, energy cost, or channel usage. The block is passive.

### 1.5 Non-goals

Not a crafting planner. It does not decide *how many* of anything to make, does
not schedule a craft, and does not care what is currently in storage. It
produces patterns, not plans.

It does read per-recipe **yield**, because a pattern has to encode the real
ratio — Block of Iron produces 9 ingots, not 1 — and because round-trip
efficiency (§8.4.2) is a ratio test. Reading a recipe's own output count is not
quantity planning.

---

## 2. Terminology

| Term | Meaning |
|---|---|
| **Goal** | An `AEKey` we want produced. The root goal is the input pattern's primary output. |
| **Recipe index** | Immutable `Item → List<RecipeHolder<?>>` reverse map, rebuilt on recipe reload. |
| **Item node** | Tree node representing a goal. Children are recipe nodes. |
| **Recipe node** | Tree node representing one way to make the parent goal. Children are ingredient nodes. |
| **Ingredient node** | One input slot of a recipe. Children are the item nodes it accepts. |
| **Choice** | An item node with more than one recipe node, or an ingredient node with more than one candidate. |
| **Source** | A recipe chosen to produce a given goal. An item usually has several (§1.2). |
| **Tier** | `PRIMARY`, `ALTERNATE` or `REJECTED` — decides a candidate's default checkbox state (§8.4.1). |
| **Forced** | An item node with exactly one live candidate. Auto-selected, silent, collapsible (§8.5). |
| **Round-trip efficiency** | Goal material returned per goal material spent, the test that separates block-unpacking from tool-melting (§8.4.2). |
| **Resolved** | A subtree in which every choice has a selection and no node is in error. |
| **Leaf** | An item node with no usable recipe. Treated as a raw input the player supplies. |
| **Plan** | The flattened, deduplicated set of recipe nodes reachable through current selections. This is what gets printed. |

---

## 3. Target versions

Locked to the **live ATM10 instance**, not the pack manifest. Verified by
reading
`~/.local/share/PrismLauncher/instances/All the Mods 10 - ATM10/`.

| Component | Locked version | Source of truth |
|---|---|---|
| Minecraft | **1.21.1** | `mmc-pack.json` |
| NeoForge | **21.1.217** | `mmc-pack.json` |
| Applied Energistics 2 | **19.2.17** | `mods/appliedenergistics2-19.2.17.jar` |
| JEI | **19.25.1.332** | `mods/jei-1.21.1-neoforge-19.25.1.332.jar` |
| Java (compile target) | **21** | Minecraft 1.21.1 requirement |
| Gradle | 8.x via wrapper | — |
| Plugin | `net.neoforged.moddev` | — |

### 3.1 Why 21.1.217

**NeoForge is 21.1.217, with no live ambiguity.** PrismLauncher's pack data,
`mmc-pack.json` and `latest.log` all agree.

The `21.1.215` that appears in `flame/manifest.json` is stale CurseForge import
metadata, not a competing pin, and 217 is a hard floor for this instance rather
than a preference. The crash log from 2026-01-17 shows why:

```
Mod fabric_biome_api_v1 requires neoforge 21.1.217 or above
    Currently, neoforge is 21.1.215
```

The pack shipped 215, failed to load against its own mod set, and was updated to
217. Building against 215 would target a version this instance cannot run. If
the pack is updated later, read `mmc-pack.json`, not the flame manifest.

### 3.2 Compatibility set

These load alongside in the target instance and share AE2's registry surface.
Milestone 7 tests against all of them.

| Mod | Version |
|---|---|
| AdvancedAE | 1.6.7 |
| AE2 Wireless Terminal Library | 19.3.0 |
| ExtendedAE | 2.2.25 |
| MEGA Cells | 4.10.1 |
| AE2 Crafting Tweaks (`ae2ct`) | 1.1.1 |
| AE2 Import/Export Card | 1.4.1 |
| AE2 JEI Integration | 1.2.0 |
| AE2 Network Analyzer | 2.1.3 |

### 3.3 Prior art already in the instance

`mods/recursiveae2patternprovider-1.0.7.jar.disabled` and `-1.0.8.jar.disabled`
are present but **disabled**, and the reason is on record from the person who
ran them: it generates patterns for every possible child recipe, and
"frequently tried to use the wrong source for an item."

That is the single most useful piece of information in this document. It says
the recursion works and the selection does not, which means the engineering risk
is concentrated in §8.4 and §11.2 rather than in the tree walk. §1.2 states the
resulting premise; treat any design change that weakens source ranking or hides
a guessed choice as a regression toward a disabled mod.

Still worth doing before milestone 0: read its actual behaviour to see whether
it offers any selection control this design is duplicating, and confirm the two
mods can coexist if someone re-enables it.

### 3.4 Local toolchain

**No install step needed.** JDK 21 is already present system-wide:

```
/usr/lib/jvm/java-21-openjdk   →  openjdk 21.0.12 2026-07-21 (with javac)
```

One nuance that will bite if ignored: `java` on `PATH` is SDKMAN's
`25.0.4-tem`, so the Gradle daemon runs on 25 by default. Declare the toolchain
explicitly rather than inheriting:

```gradle
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
```

Gradle's auto-detection scans `/usr/lib/jvm` and resolves that to the system
JDK 21, so no Foojay provisioning and no SDKMAN candidate are required. Do not
compile on 25 targeting 21; NeoForge's transformers expect a real 21 toolchain.

### 3.5 Identity

Mod id: **`rpp`**. Java group: **`dev.cryolithic.rpp`**. Both appear in package
names, the resource folders, and `neoforge.mods.toml`; change them before the
first commit or not at all.

AE2 resolves from Modrinth maven first, CurseForge maven (`cursemaven.com`) as
fallback. JEI is a compile-time optional dependency only, and only for §6.5.

## 4. Package layout

```
dev.cryolithic.rpp
├── RppMod.java              @Mod entrypoint, binds DeferredRegisters
├── RppConfig.java           NeoForge config spec
├── init/
│   ├── RppBlocks, RppItems, RppBlockEntities, RppMenus
│   └── RppNetwork          payload registration
├── block/
│   ├── PatternPrinterBlock
│   └── PatternPrinterBlockEntity
├── inventory/
│   ├── PatternPrinterMenu
│   └── PatternPrinterInventory   ItemStackHandler + capability
├── recipe/
│   ├── RecipeIndex          reverse lookup, reload listener
│   ├── RecipeView           normalized (inputs, outputs, id, type) view of any Recipe<?>
│   └── RecipeAdapter        SPI for mods whose recipes do not expose inputs normally
├── tree/
│   ├── TreeNode, ItemNode, RecipeNode, IngredientNode
│   ├── RecipeTreeBuilder    the core algorithm — no Minecraft runtime deps beyond registries
│   ├── SourceSelector      §8.4 tiering: PRIMARY / ALTERNATE / REJECTED
│   ├── ReversalDetector     one-level reversal + round-trip efficiency memo
│   ├── StickyChoices        §8.7 remembered source decisions
│   ├── CraftabilityOracle   bounded "can this bottom out?" memo
│   ├── TreeSelection        selection state + plan flattening
│   └── TreeLimits           depth / breadth / node caps
├── print/
│   ├── PrintPlan            validated, server-side
│   └── PatternEncoder       RecipeView → encoded pattern ItemStack
├── net/
│   ├── PrintRequestPayload  C2S
│   └── PrintResultPayload   S2C
└── gui/
    ├── PatternPrinterScreen
    ├── PrintReviewScreen     §11.2 pre-print source list
    └── widget/TreeWidget, TreeRow, TreeScrollbar
```

`tree/` and `recipe/` must not import anything from `gui/`, `block/` or
`inventory/`. That boundary is what makes §13.1 possible.

---

## 5. Data flow

```
[input slot] encoded pattern ItemStack
      │  PatternDetailsHelper.isEncodedPattern → decodePattern
      ▼
  IPatternDetails ──► getPrimaryOutput()     ─────────┐
                                                      │  root goal
   RecipeIndex (client copy, immutable snapshot)      │
      │                                               ▼
      └────────────► RecipeTreeBuilder ────► tree (lazy, client-side)
                                                      │
             SourceSelector + StickyChoices ──► defaults │
                              player expands / selects │
                                                      ▼
                                              TreeSelection ──► plan
                                                      │
                                       PrintReviewScreen (§11.2)
                                                      │
                                       PrintRequestPayload (recipe ids)
                                                      ▼
                                  server: PrintPlan.validate → PatternEncoder
                                                      ▼
                          consume blank patterns, write output slots, S2C result
```

### 5.1 Why the tree is built on the client

The client already has the full recipe set; the vanilla recipe manager is synced
to it on login and on every `/reload`. Building there means expanding a node is
instant with no round-trip, which matters because the interaction model is
"click around the tree until it looks right".

The server never trusts the result. The print request carries only recipe
identifiers and the tree shape that justified them; the server rebuilds and
validates the plan against its own recipe manager before encoding anything
(§10.2). A malicious client gains nothing it could not get by encoding patterns
by hand, which it is already allowed to do.

---

## 6. Recipe index

### 6.1 The problem AE2 does not solve

AE2 has no reverse recipe lookup, and it has no `appeng.api.recipes` package at
all. The API packages shipped in 19.2.17 are:

```
behaviors  client  components  config  crafting  features  ids
implementations  integrations  inventories  movable  networking
orientation  parts  stacks  storage  upgrades  util
```

`appeng.api.crafting` encodes and decodes patterns; it does not answer "what
makes this item". That map has to be built from the vanilla recipe manager
ourselves. §6.2 is load-bearing, not a fallback.

### 6.2 Construction

Built once per recipe reload, never mutated afterwards.

- **Server**: `AddReloadListenerEvent`, or `ServerStartedEvent` plus a datapack
  reload hook.
- **Client**: `RecipesUpdatedEvent`.

```java
record RecipeIndex(Map<Item, List<RecipeView>> byOutput, int recipeCount) {
    static RecipeIndex build(RecipeManager rm, HolderLookup.Provider registries);
    List<RecipeView> recipesFor(AEKey goal);   // empty for non-item keys in v1
}
```

Build loop: iterate `rm.getRecipes()`, produce a `RecipeView` per holder, bucket
it under each distinct output item. ATM10 is roughly 40–60k recipes; a single
pass with `ArrayList` buckets is tens of milliseconds and a few MB. Do it off
the main thread and swap the reference in atomically; readers hold the old
snapshot until the swap.

The index is immutable. That is what makes off-thread tree building safe with no
locking (§9).

### 6.3 `RecipeView` — normalizing `Recipe<?>`

Every backend collapses to one shape:

```java
record RecipeView(
    ResourceLocation id,
    RecipeType<?> type,
    List<IngredientView> inputs,   // ordered, sparse slots preserved for crafting
    List<GenericStack> outputs,    // outputs[0] is primary
    int gridWidth, int gridHeight, // 0 for non-grid recipes
    boolean trusted)               // false = extracted heuristically, warn in GUI
```

Default extraction, in order of preference:

1. **`CraftingRecipe`** — `getIngredients()` for inputs, with width and height
   from `ShapedRecipe`; shapeless recipes get width 0. Skip
   `CustomRecipe`/special recipes entirely; they have no static result.
2. **`Recipe<?>` generic** — `getResultItem(registries)` for the output,
   `getIngredients()` for inputs, `trusted = false`.
3. **Registered `RecipeAdapter`** — §6.4.

`getResultItem` throws or returns empty for a meaningful minority of modded
recipes. Wrap each recipe's extraction in try/catch, count failures, log a
single summary line at the end of the build rather than one line per recipe.

### 6.4 The fluid problem, honestly

`Recipe#getIngredients()` returns item ingredients. A modded recipe with fluid
inputs, chemical inputs or non-item catalysts will surface through path 2 above
with those inputs **silently absent**. A pattern encoded from it would be wrong.

Mitigation, in v1:

- `RecipeView.trusted == false` for anything not reached by path 1 or a
  registered adapter.
- Untrusted recipe nodes render with a warning marker and are **never
  auto-selected**. The player has to pick them deliberately.
- A config option (`requireTrustedRecipes`, default `false`) hides untrusted
  recipes entirely.
- `RecipeAdapter` is the escape hatch: a small interface other mods or our own
  compat classes implement to describe their recipe type properly. Registered
  via a static registry keyed by `RecipeType<?>`.

```java
interface RecipeAdapter {
    RecipeType<?> type();
    @Nullable RecipeView view(RecipeHolder<?> holder, HolderLookup.Provider registries);
}
```

Ship adapters for whatever ATM10 recipe types turn out to matter in testing.
Do not guess ahead of measurement.

### 6.5 JEI as an optional second source

JEI already solves fluid and catalyst extraction for hundreds of mods. Treat it
as a **later** enhancement, not a v1 dependency:

- Present in the target instance (JEI 19.25.1.332, plus AE2 JEI Integration
  1.2.0), so it is a safe optional dependency there.
- Client-only, so it can only feed the tree, never the server-side validation.
- That asymmetry means a JEI-sourced recipe could pass client display and fail
  server validation. Handle by marking JEI-sourced views and validating them
  server-side against the same recipe id in the vanilla manager.
- Revisit after v1 ships. Do not let it shape the v1 interfaces beyond
  `RecipeAdapter` already being an SPI.

---

## 7. Tree model

### 7.1 Three node kinds

Modelling ingredients as their own node kind is what keeps tag-based inputs
sane. `#c:ingots/copper` accepting eleven different copper ingots is one
ingredient node with eleven candidates, not eleven parallel subtrees.

```java
sealed interface TreeNode permits ItemNode, RecipeNode, IngredientNode {
    int id();                 // dense int, stable for the session
    TreeNode parent();
    State state();            // COLLAPSED, EXPANDED, UNEXPANDED, CYCLE, CAPPED, ERROR
}

final class ItemNode implements TreeNode {
    AEKey goal;
    List<RecipeNode> recipes;     // null until expanded, ranked (8.4)
    BitSet selected;              // MULTI-select: any number of recipes
    boolean forced;               // exactly one candidate — auto-selected, 8.5
    boolean leaf;                 // no usable recipe; supply as raw input
    Craftability craftability;    // from the oracle
}

final class RecipeNode implements TreeNode {
    RecipeView recipe;
    List<IngredientNode> ingredients;
    Tier tier;                    // PRIMARY | ALTERNATE | REJECTED, 8.4.3
    Destination destination;      // ASSEMBLER | MACHINE:<type> | ..., 17.7.2
    boolean collides;             // shares a destination with another selected source
    double score;                 // ranking, 8.4.4
    double roundTripEfficiency;   // NaN if not a reversal, 8.4.2
    boolean userOverridden;       // player moved it across the default
}

final class IngredientNode implements TreeNode {
    IngredientView ingredient;
    List<ItemNode> candidates;    // capped; see 8.3
    int selectedCandidate;
    boolean capped;               // more candidates existed than the cap allows
}
```

When an ingredient node has exactly one candidate, the GUI renders it merged
into that candidate's row. The model keeps it separate; only rendering collapses
it. Keeping the model uniform is worth one extra object per input slot.

### 7.2 Depth semantics

Depth counts **item nodes only**. Root is depth 0. `ItemNode → RecipeNode →
IngredientNode → ItemNode` increments by one. This makes the depth cap mean
"how many crafting steps deep", which is what a player expects.

---

## 8. Tree building

### 8.1 Lazy by default

Never build the whole tree. On open, build the root item node and expand exactly
`initialDepth` levels (default 2). Every other node starts `UNEXPANDED` and is
built when the player clicks it.

Lazy expansion is not an optimization here, it is the thing that makes the
feature possible at all. A fully materialized tree for an ATM10 mid-game item
runs to millions of nodes. The caps in §8.3 are a backstop for pathological
single expansions, not the primary defense.

### 8.2 Cycle detection — mirror AE2 exactly

Cycles are common and legitimate: blocks pack and unpack, ingots melt and cast,
plates press back into ingots.

**The rule must match AE2's `CraftingTreeNode.notRecursive` (§17.4), not a
simpler one.** AE2 refuses a candidate pattern if any ancestor goal on the
current path appears among that pattern's outputs, or matches
`getPossibleInputs()[0]` of any of its inputs. A tree built on a looser rule
offers branches AE2 will silently refuse, which shows up as the mod printing
patterns that never get used.

Reimplement it over `RecipeView` with the same semantics:

- Walk the full ancestor chain, not just the immediate parent.
- Test the candidate's outputs **and** its inputs against every ancestor goal.
- Compare against the first possible input of each ingredient only, matching
  AE2's `[0]` indexing, even though that is a fidelity gap on tag ingredients.
  Diverging here would be worse than copying the gap.

Carry the ancestor goals down the expansion and pop on the way back up. At the
depths involved (≤ 12) a plain `ArrayList` scan beats a `HashSet` and allocates
nothing.

On a hit: create the item node, mark it `CYCLE`, do not expand it, render it
with a loop glyph. It remains selectable as a raw input.

### 8.3 Caps

All in `TreeLimits`, all config-backed, all enforced during expansion.

| Limit | Default | Effect on breach |
|---|---|---|
| `maxDepth` | 8 | Item node marked `CAPPED`, not expanded |
| `maxRecipesPerItem` | 6 | Keep top N by §8.4 ordering, append a "+K more" marker row |
| `maxCandidatesPerIngredient` | 8 | Same, ingredient marked `capped` |
| `maxNodesPerExpansion` | 4000 | Abort that one expansion, mark `CAPPED`, leave rest of tree intact |
| `maxTotalNodes` | 100000 | Refuse further expansion, GUI shows a banner |

`maxNodesPerExpansion` is per-click, not per-session. One bad click degrades one
branch rather than the whole GUI.

### 8.4 Source selection

The subsystem that decides whether the mod is useful or gets disabled like its
predecessor (§1.2). It answers: *of the six recipes that produce iron ingot,
which ones deserve a pattern?* Plural, deliberately.

Two axes, not one. **Tier** decides whether a candidate is worth printing at
all. **Destination** (§17.7.2) decides whether two worthwhile candidates can
coexist usefully, because AE2 can only order patterns that sit in different
providers.

Every candidate lands in one of three tiers. Tier decides the default checkbox
state; the player can move anything across the line.

#### 8.4.1 Tiers

| Tier | Default | Meaning |
|---|---|---|
| **PRIMARY** | checked | Best-ranked ordinary production recipe for this item |
| **ALTERNATE** | checked | A genuinely useful second path: lossless unpacking, ore multiplication, a cheaper variant |
| **REJECTED** | unchecked | Junk. Shown dimmed with a reason, one click from being included anyway |

Nothing is ever hidden. `REJECTED` is a default, not a verdict, because pack
recipes are too varied for any heuristic to be right every time. Every rejection
carries a human-readable reason in its tooltip, so a wrong call is diagnosable
rather than mysterious.

**Defaults are applied per destination class** (§17.7.2). At most one candidate
is checked by default within each class, because AE2 cannot order two patterns
that land in the same kind of provider (§17.7.1). Across classes, multi-select
is the default, which is where the §1.2 iron case lives. So "one crafting
recipe, plus one recipe per machine type" is the shape of a typical default
selection.

#### 8.4.2 Round-trip efficiency, the rule that separates the iron cases

Block of Iron → 9 Ingots and Iron Pickaxe → 1 Ingot are **both** reversals: in
each, the input can itself be produced from the goal. Direction cannot tell them
apart. Loss can.

For a candidate `R: I → n×G`, where `I` is producible from `G` by some recipe
`R': m×G + extras → k×I`:

```
roundTripEfficiency(R) = (n / k) / (m / 1)      // goal-units out per goal-unit in
```

| Case | m | k | n | Efficiency | Tier |
|---|---|---|---|---|---|
| Block of Iron | 9 ingots → 1 block | 1 | 9 | **1.0** | ALTERNATE |
| Iron Pickaxe | 3 ingots → 1 pickaxe | 1 | 1 | **0.33** | REJECTED |
| Iron Nugget | 1 ingot → 9 nuggets | 9 | 1 | **1.0** | ALTERNATE |

The threshold is `minRoundTripEfficiency`, default `0.95`. At or above it the
reversal is a storage or packing form and is a legitimate path. Below it the
recipe destroys material and is recycling.

Two supporting signals sharpen it:

- **Extra consumed inputs.** The pickaxe recipe also eats two sticks, which the
  reversal never returns. Any reversal whose forward recipe consumes inputs
  other than the goal is capped at `ALTERNATE` and, below threshold, is a
  stronger `REJECTED`.
- **Storage tags.** If input and output sit in a reciprocal
  `c:storage_blocks/x` ↔ `c:ingots/x` ↔ `c:nuggets/x` tag pair, treat it as
  lossless without computing the ratio. Cheap fast path, and correct for the
  overwhelming majority of packing recipes.

Detection is one level deep, memoized per `(goal, input)` pair. That depth
catches compression pairs, nugget pairs and tool recycling, which is nearly all
of it.

#### 8.4.3 Other tier assignments

Straight to `REJECTED`, each for a distinct reason:

| Signal | Catches | Reason shown |
|---|---|---|
| **Lossy reversal** | §8.4.2 below threshold | "recycling: returns 33% of material cost" |
| **Byproduct** | Goal is not the recipe's primary output | "produced as a side product" |
| **Untrusted** | Heuristic extraction (§6.4) | "inputs may be incomplete" |
| **Self-referential** | Goal appears in its own input set | "consumes the item it produces" |
| **Dead-end inputs** | Oracle says an input is unobtainable | "requires an item with no source" |

Promoted to `ALTERNATE` rather than left as also-rans:

- Lossless reversals (§8.4.2). The Block of Iron case.
- **Yield above the primary.** A recipe producing more goal per input than the
  `PRIMARY` does, with trusted inputs. This is what catches ore multiplication:
  the Mekanism chain yields 4 ingots where smelting yields 1, so it is never
  merely an also-ran.
- Recipes in a `preferredMods` namespace (§12) that did not win `PRIMARY`.

Everything else that is trusted and not rejected is `ALTERNATE` too, unless
`alternatesConservative` is on, which restricts `ALTERNATE` to the three
promotions above. Some players want every path, some want a tidy network.

#### 8.4.4 Ranking within a tier

Ordering decides which candidate is `PRIMARY` and the display order. Descending
weight:

1. **Sticky choice** (§8.7). The player already answered this.
2. **Namespace match** — recipe namespace equals the goal item's namespace.
3. **Configured `preferredMods` order** (§12). The knob that encodes "I am a
   Mekanism player". The single most effective lever a pack player has.
4. **Vanilla crafting or smelting** — no machine required.
5. **Shallower oracle depth.**
6. **Fewer distinct input items.**
7. **Recipe id, lexicographic** — determinism only. A tree that reshuffles
   between sessions is unusable.

#### 8.4.5 Yield is encoded, not planned

A pattern must carry the real ratio. Block of Iron → **9** ingots, the Mekanism
chain → **4**. Both come straight from `RecipeView.outputs[].amount` into the
`GenericStack` amounts the processing encoder takes (§10.3). This is not
quantity planning (§1.5); it is copying the recipe's own numbers.

### 8.5 Collapse rule, and forced bottlenecks

Collapsing hides a decision, so it is only allowed where no decision exists.

**A node is `forced` when it has exactly one non-`REJECTED` candidate.** There
is nothing to ask, so it is selected automatically and silently. Long stretches
of a chain are forced bottlenecks — one ore washer recipe, one crusher recipe,
one smelt — and making the player confirm each of them is the tedium this mod
exists to remove.

**A subtree collapses when every descendant item node is forced, a leaf, or a
cycle node**, and no descendant is untrusted, capped, or `UNKNOWN` from the
oracle. Collapsed rows summarise: `Copper Gear ▸ 4 steps, 7 patterns, all
forced`.

A node with two or more live candidates stays open, because that is a real
choice about how the network will source the item. That is the Block-of-Iron
versus ore-chain decision, and it is the one thing the player actually wants to
see.

### 8.6 `CraftabilityOracle`

A separate bounded search that allocates no tree nodes. It answers one question
per item:

```java
enum Craftability { CRAFTABLE_UNAMBIGUOUS, CRAFTABLE_AMBIGUOUS, LEAF, UNKNOWN }
Craftability check(AEKey goal, int depthBudget);
```

Memoized in `Map<AEKey, Craftability>`, keyed per index snapshot, per depth
budget bucket. Cycle-guarded the same way as §8.2. `UNKNOWN` on budget
exhaustion, and `UNKNOWN` is never treated as collapsible.

Keeping this separate from the builder is deliberate: the builder allocates and
is called rarely, the oracle is cheap and is called for every row rendered.

---

### 8.7 Sticky source sets

The pack-scale version of the problem: decide how iron ingots are sourced once,
not once per branch and again in every future tree.

```java
record SourceSet(ResourceLocation goalItem, Set<ResourceLocation> recipeIds) {}
```

- **Within a tree**: changing the checked set for an item applies to every other
  node in the tree with the same goal, immediately. Decide iron once.
- **Across trees**: persisted per player client-side, keyed by goal item,
  reapplied on the next build and ranked at weight 1 in §8.4.4.
- **Visible and reversible**: rows restored from a sticky set are marked as
  such, with "forget this choice" on the row and a clear-all in config. A
  remembered wrong choice that cannot be seen or undone is worse than no memory.
- **Invalidated** when a remembered recipe id is absent after a reload, falling
  back to tiering silently.

Client-side convenience only. The server revalidates every recipe id at print
time (§10.2).

## 9. Threading

World access is single-threaded. Tree work is not world access — it reads an
immutable `RecipeIndex` snapshot and the frozen registries, and touches nothing
else. That is the whole reason §4 forbids `tree/` from importing `block/`.

- Index build: background thread, atomic reference swap on completion.
- Expansion: background thread on a single-thread executor, one task at a time,
  cancellable. Result published to the GUI by queueing onto the client executor.
- GUI: shows a spinner on the expanding row. Never blocks the render thread.
- Print: entirely main thread, server side. It is small and touches inventories.

If a reload lands mid-expansion, the in-flight task finishes against the old
snapshot and its result is discarded on publish by comparing snapshot identity.
The GUI rebuilds from the root.

---

## 10. Block, inventory, printing

### 10.1 Block and inventory

`PatternPrinterBlock` — directional, opaque, redstone-inert, no ticker. Right
click opens the menu; the block entity does nothing on its own.

Slots:

| Range | Purpose | Rules |
|---|---|---|
| 0 | Input pattern | `PatternDetailsHelper.isEncodedPattern` must pass, size 1 |
| 1 | Blank patterns | Accepts only AE2 blank pattern, stacks |
| 2–28 | Output patterns | Insert-blocked from outside, extract-allowed |

Get the blank pattern item through `appeng.api.ids.AEItemIds.BLANK_PATTERN`
(verified present in 19.2.17) and `BuiltInRegistries.ITEM`, not through
`appeng.core.definitions.AEItems`. Same result, public API surface.

Backed by one `ItemStackHandler`, exposed as `Capabilities.ItemHandler.BLOCK`
with a side-aware wrapper: insert routes to blanks, extract pulls from outputs
only. Persisted in `saveAdditional` / `loadAdditional`, synced by
`getUpdateTag` / `getUpdatePacket`. The tree is never persisted — it is derived
state, rebuilt from the input slot on open.

### 10.2 Print flow

Client sends `PrintRequestPayload`:

```java
record PrintRequestPayload(
    ItemStack inputPattern,          // echoed back for staleness check
    List<PlanEntry> entries)         // recipe id + output key + selected candidate per ingredient
```

Server, on the main thread:

1. Reject if the input slot no longer matches the echoed stack.
2. Reject if `entries.size() > maxPrintBatch` (default 128).
3. For each entry: look the recipe id up in the **server's** recipe manager,
   rebuild the `RecipeView`, and confirm the claimed output and chosen
   ingredient candidates are actually what that recipe produces and accepts.
   Any mismatch fails the whole job.
4. Confirm the plan is connected — every entry's output is either the root goal
   or an ingredient of another entry. Rejects smuggling in unrelated patterns.
5. Count blanks. `min(available, entries)` patterns get printed. Count free
   output slots the same way.
6. Encode deepest-first so the output inventory reads bottom-up in crafting
   order. Consume one blank per pattern, insert, stop on first failure.
7. Reply with `PrintResultPayload(printed, skipped, reason)`.

Partial success is a success. Print what fits, report the rest. Never throw
across the block entity boundary — log and cancel, per `AGENTS.md`.

### 10.3 `PatternEncoder`

```java
ItemStack encode(RecipeView view, IngredientChoice[] choices, Level level);
```

Signatures below are **verified** against `appliedenergistics2-19.2.17.jar`
(`javap appeng.api.crafting.PatternDetailsHelper`). All four encoders are
static, all return an `ItemStack`.

| Recipe shape | Call |
|---|---|
| `CraftingRecipe`, fits 3×3 | `encodeCraftingPattern(RecipeHolder<CraftingRecipe>, ItemStack[] sparseInputs, ItemStack output, boolean allowSubstitutes, boolean allowFluidSubstitutes)` |
| `StonecutterRecipe` | `encodeStonecuttingPattern(RecipeHolder<StonecutterRecipe>, AEItemKey in, AEItemKey out, boolean allowSubstitutes)` |
| `SmithingRecipe` | `encodeSmithingTablePattern(RecipeHolder<SmithingRecipe>, AEItemKey template, AEItemKey base, AEItemKey addition, AEItemKey out, boolean allowSubstitutes)` |
| everything else | `encodeProcessingPattern(List<GenericStack> inputs, List<GenericStack> outputs)` |

Note the processing encoder takes **`List<GenericStack>`, not an array**, and
takes no substitution flags — substitution is a crafting-pattern concept only.

Stonecutting and smithing get first-class handling because AE2 has dedicated
pattern types for them (`AEItemIds.STONECUTTING_PATTERN`,
`AEItemIds.SMITHING_TABLE_PATTERN`) and routing them through the processing
encoder would produce patterns no AE2 crafting CPU can satisfy.

`RecipeView` therefore carries enough to pick a row: the `RecipeType<?>` decides
the branch, and the server re-resolves the `RecipeHolder` by id at encode time
so the typed holder is available without storing it in the view.

Substitution flags come from two config-backed GUI toggles, applied globally to
the batch. Per-pattern control is a v2 concern.

## 11. GUI

### 11.1 Tree view

AE2-styled container screen, resizable-height tree pane, roughly 256 px wide.
Recipe rows are **checkboxes, not radio buttons** — several sources for one item
is the normal case (§1.2).

```
┌──────────────────────────────────────────────────┐
│ Recursive Pattern Printer        [✔ all] [✘ all] │
│ ┌──────────────────────────────────────────────┐ │
│ │ ▾ ⬛ Iron Ingot                    3 of 5 ▾  │ │ multi-select
│ │   ☑ ⚒ Mekanism: raw iron chain      ×4  ★   │ │ ALTERNATE, yield 4
│ │   ☑ ⚒ Smelt: raw iron               ×1      │ │ PRIMARY
│ │   ☑ ⚒ Unpack: Block of Iron         ×9  ⇄   │ │ ALTERNATE, lossless
│ │   ☐ ⚒ Melt: Iron Pickaxe            ×1  ⊘   │ │ REJECTED, 33% return
│ │   ☐ ⚒ Centrifuge: scrap             ×1  ⊘   │ │ REJECTED, byproduct
│ │ ▸ ⬛ Redstone Alloy         4 steps, forced  │ │ collapsed, no choice
│ │ ▸ ⬛ Copper Ingot                        ↻   │ │ cycle
│ └──────────────────────────────────────────────┘ │
│ [input] [blanks x37]         27 patterns  [Print]│
│ ▓▓▓▓▓ output 3x9 ▓▓▓▓▓                           │
└──────────────────────────────────────────────────┘
```

Badges: `★` sticky, `⇄` lossless reversal, `⊘` rejected with reason on hover,
`⚠` untrusted, `↻` cycle, `×N` yield per craft, `⚑` costly same-destination
collision (§17.7.3).

Each recipe row also names its destination — `assembler`, `furnace`,
`mekanism:enriching` — because that is where the player physically puts the
printed pattern, and it is what makes a collision legible.

Interactions:

- Disclosure triangle expands or collapses. Expand triggers a background build
  if `UNEXPANDED`.
- **Checkbox toggles one recipe** in or out of the printed set. No invalidation
  of siblings; the other checked recipes are unaffected.
- **Node header `3 of 5 ▾`** offers select-all and deselect-all for that item,
  plus "reset to defaults" to undo manual edits back to the tiering.
- **Toolbar `[✔ all] [✘ all]`** applies the same tree-wide. Deselect-all then
  hand-picking is the fastest route for a player who wants a minimal network,
  and select-all is the "just give me everything" escape hatch that makes this
  strictly a superset of the prior art's behaviour.
- Deselecting every recipe on an item marks it "supply as raw input" and prunes
  the subtree. Shift-click the item row does the same in one action.
- Hover any row for recipe id, type, full ingredient list, yield, and the tier
  reason if rejected.
- Live pattern counter beside the blanks count, red when it exceeds blanks.
- Search filters rows by name, keeping ancestors visible.

Rows are virtualized: only visible rows become widgets, from a flattened
visible-row list recomputed on expand, collapse or filter change. A 2000-row
tree must scroll at full frame rate.

### 11.2 Pre-print review

The last line of defence against the §1.2 failure. **Print** does not print. It
opens a flat list of every pattern the plan will produce:

```
Print 27 patterns from 37 blanks                    [Back]  [Confirm]
────────────────────────────────────────────────────────────────────
  ⚠ 1 untrusted   ⇄ 2 reversals   ⚑ 1 collision   ⊘ 0 rejected included
────────────────────────────────────────────────────────────────────
  ⬛ Iron Ingot    ×4  ← mekanism:enriching/iron_chain      ★ sticky
  ⬛ Iron Ingot    ×1  ← minecraft:iron_ingot_from_smelting
  ⬛ Iron Ingot    ×9  ← minecraft:iron_ingot_from_block    ⇄ lossless
  ⬛ Steel Plate   ×1  ← mekanism:rolling/steel_plate
  ⬛ Redstone Alloy×1  ← enderio:alloying/redstone_alloy    ⚠ untrusted
  …
```

Grouped by goal item so all sources for one item sit together, which is how the
multi-select decision is checked. Flagged rows sort first within a group.
Clicking a row jumps back to that tree node. Nothing is consumed until
**Confirm**.

Separate from a tooltip because the observed failure was not "the player picked
wrong", it was "the player never saw what was picked". Twenty-seven sources in a
flat list are scannable in seconds; the same spread across a 200-row tree is
not.

## 12. Config

`rpp-client.toml` for tree caps and display, `rpp-common.toml` for print limits
and trust. Every value in §8.3 is exposed. Additionally:

| Key | Default | Purpose |
|---|---|---|
| `initialDepth` | 2 | Levels expanded on open |
| `requireTrustedRecipes` | false | Hide heuristically-extracted recipes |
| `preferredMods` | `["minecraft", "ae2"]` | Source ranking bias, §8.4.2. **The most useful knob in the mod** — an ordered list of namespaces the player actually builds with |
| `minRoundTripEfficiency` | 0.95 | Reversal at or above this is a lossless storage form and is offered; below it is recycling, §8.4.2 |
| `alternatesConservative` | false | Restrict `ALTERNATE` to lossless reversals, higher-yield recipes and preferred namespaces, §8.4.3 |
| `maxSourcesPerItem` | 3 | Cap on recipes auto-checked for one item (§17.5 — each extra source makes AE2's crafting calculation step rather than batch). Manual selection is uncapped but warns |
| `maxSourcesPerDestination` | 1 | Recipes auto-checked within one destination class (§17.7.3). Raising it means AE2 orders them arbitrarily |
| `warnOnCostlyCollisions` | true | Flag same-destination sources that differ materially in cost, §17.7.3 |
| `groupPrintByDestination` | true | Write patterns to the output inventory in destination batches, §17.7.3 |
| `stickyChoices` | true | Remember source sets across trees, §8.7 |
| `alwaysReviewBeforePrint` | true | Show §11.2 even when nothing is flagged |
| `blacklistedRecipeTypes` | `[]` | Recipe types never indexed |
| `maxPrintBatch` | 128 | Server-side cap per print request |
| `allowSubstitutions` | true | Default for the GUI toggle |

---

## 13. Testing

### 13.1 Unit, JUnit 5, `src/test/java`

The builder, oracle and selection logic take `RecipeIndex` and `TreeLimits` and
return nodes. Nothing in that path needs a Minecraft runtime if the index is fed
from a hand-built fixture. Keep it that way — it is the difference between a
2-second test loop and a 90-second one.

Cases that must exist before the GUI does:

- Linear chain A ← B ← C resolves, collapses, and flattens to 3 plan entries.
- Direct cycle A ← B ← A marks `CYCLE` at depth 2 and terminates.
- Indirect cycle across 4 items, same.
- Diamond: A needs B and C, both need D. D appears twice, plan dedupes to one
  entry for D.
- Item with 500 recipes truncates to `maxRecipesPerItem` with a "more" marker.
- Tag ingredient with 60 candidates truncates and sets `capped`.
- Item with no recipes is a `LEAF`, not an error.
- `maxNodesPerExpansion` breach marks one node `CAPPED` and leaves siblings
  intact.
- Ordering is stable across two builds from the same index.
**The iron fixture** is the canonical test and should be written first. One
goal, five candidates: ore chain ×4, smelt ×1, block unpack ×9, pickaxe melt
×1, byproduct ×1. Assertions:

- Block unpack scores efficiency `1.0` and lands in `ALTERNATE`, checked.
- Pickaxe melt scores `0.33` and lands in `REJECTED`, unchecked.
- Ore chain is promoted to `ALTERNATE` on higher yield, not left an also-ran.
- Smelt is `PRIMARY`.
- Default selection is exactly three recipes; the node is **not** forced and
  does **not** collapse.
- Each printed pattern carries its own yield: 9, 4 and 1.

Then:

- **Nugget pair**: ingot→9 nuggets and 9 nuggets→ingot both score `1.0`.
- **Extra-input reversal**: a forward recipe consuming sticks alongside the goal
  is capped at `ALTERNATE` even at high efficiency.
- **Storage-tag fast path** agrees with the computed ratio on a `c:storage_blocks`
  pair.
- **Forced bottleneck**: single-candidate node is auto-selected, `forced`, and
  collapses.
- **Forced chain**: four stacked forced nodes collapse into one summary row.
- **Namespace match**: goal `modb:gear` ranks the `modb` recipe `PRIMARY`.
- **preferredMods** overrides namespace match when configured.
- **Byproduct**: a recipe whose primary output is not the goal is `REJECTED`.
- **Select-all / deselect-all** at a node and tree-wide produce the expected
  plan sizes, and "reset to defaults" restores the tiering exactly.
- **maxSourcesPerItem** caps auto-checked recipes but not manual ones.
- **Destination defaults**: two crafting recipes plus one furnace recipe for one
  goal auto-check exactly one crafting and one furnace recipe, not both crafting.
- **Costly collision**: manually checking both crafting recipes sets `collides`
  and flags them when their costs differ; two lossless unpack recipes collide
  silently.
- **Destination classification**: a 3×3 recipe maps to `ASSEMBLER`, a furnace
  recipe to `MACHINE:minecraft:smelting`, a stonecutter recipe to `STONECUTTER`,
  matching the encoder chosen in §10.3.
- **The iron fixture resolves to three distinct destinations** and therefore
  auto-checks all three sources.
- **Sticky**: a source set applies to every node with that goal in the tree and
  survives a rebuild; a stale recipe id falls back silently.
- A subtree containing a multi-candidate node never collapses.
- Oracle returns `UNKNOWN`, never `LEAF`, on budget exhaustion.

### 13.2 In-game QA — the real test

`./gradlew runClient`, then against an ATM10-like recipe set:

- Insert a pattern for a deep item. Tree renders under 200 ms to first paint.
- Expand a 500-recipe item. No frame drop, cap marker present.
- Select an alternate branch. Plan count updates, old subtree drops.
- **Iron acceptance test**: print iron ingot patterns. The block-unpack, ore
  chain and smelt patterns are all produced with correct yields; no tool-melt
  pattern is. This is the test the prior art fails (§3.3).
- **Live network behaviour** (§16, the open risk): put all three iron patterns
  in a network with iron blocks but no raw iron in storage, request ingots, and
  observe which pattern AE2 uses. Then reverse the stock and repeat.
- **Compression-loop check**: with both ingot→block and block→ingot patterns in
  a network, request each and confirm AE2 plans without looping (§17.4 says it
  cannot, so this is a regression guard).
- **Tie instability** (§17.7.1): put two crafting patterns for one item in the
  same assembler provider, request the item, note which is used, then restart the
  world and repeat several times. Confirms the non-determinism the collision
  warning describes, and is the evidence to cite if a player disputes it.
- **Destination batching**: print a multi-destination plan and confirm the output
  inventory groups patterns so each batch goes to one provider.
- Set `preferredMods` to the pack's dominant tech mod and confirm `PRIMARY`
  shifts across the whole tree.
- Select-all on a deep item, confirm the pattern count and that nothing hangs.
- Deselect-all, hand-pick three recipes, print, confirm exactly three.
- Print with fewer blanks than patterns. Partial print, accurate report, no
  duplicate or lost blanks.
- Print with a full output inventory. Clean stop, nothing consumed for a pattern
  that could not be placed.
- `/reload` with the GUI open. Tree rebuilds from root, no stale nodes, no crash.
- Load alongside the §3.2 set — AE2WTLib 19.3.0, ExtendedAE 2.2.25, MEGA Cells
  4.10.1, ae2ct, Import/Export Card, AE2 JEI Integration, Network Analyzer. No
  registry or mixin conflict.
- Re-enable `recursiveae2patternprovider` 1.0.8 and load both. Either they
  coexist or we learn why it was disabled (§3.3).
- Dedicated server plus client. Print validation path exercised for real.

---

## 14. API verification — results

Every assumption in this document was checked against the real jar in the target
instance. Results below; **7 of 7 resolved**, no open blockers.

### 14.1 Confirmed

| # | Question | Answer |
|---|---|---|
| 1 | `PatternDetailsHelper` encoders | All four confirmed, signatures in §10.3 |
| 2 | `IPatternDetails` output access | `getOutputs()` returns `List<GenericStack>`; **`getPrimaryOutput()` exists as a default method** — use it for the root goal, no index-0 assumption needed |
| 3 | `AEItemIds.BLANK_PATTERN` | Exists, `public static final ResourceLocation`. So do `CRAFTING_PATTERN`, `PROCESSING_PATTERN`, `STONECUTTING_PATTERN`, `SMITHING_TABLE_PATTERN` |
| 4 | Reverse lookup in `appeng.api.recipes` | **Package does not exist.** Build our own index, §6.2 |
| 7 | AE2 helper that picks crafting-vs-processing | None. §10.3 does the routing |

Other confirmed members worth using:

- `PatternDetailsHelper.isEncodedPattern(ItemStack)` — exactly the input-slot
  validator §10.1 needs.
- `PatternDetailsHelper.decodePattern(ItemStack, Level)` and the `AEItemKey`
  overload.
- `IPatternDetails.IInput` — `getPossibleInputs()` returns `GenericStack[]`,
  plus `getMultiplier()`, `isValid(AEKey, Level)`, `getRemainingKey(AEKey)`.
  This is the shape §7.1's `IngredientNode` should mirror for round-tripping.
- `PatternDetailsHelper.encodedPatternItemBuilder(...)` — registration path if
  we ever want our own pattern type. Not needed in v1.

### 14.2 Still to check at scaffold time

These need a compiling NeoForge 21.1.217 workspace rather than a jar listing.

5. `Recipe#getResultItem` parameter type on 1.21.1 — `HolderLookup.Provider` vs
   `RegistryAccess`.
6. `RecipeManager#getRecipes` return type and whether `RecipeHolder` is still
   the wrapper in 21.1.217.

Both are vanilla/NeoForge surface, both are compile errors if wrong, neither
changes the design.

## 15. Milestones

| # | Deliverable | Done when |
|---|---|---|
| 0 | Scaffold | Toolchain pinned to 21 in `build.gradle` (§3.4 — the JDK is already present), prior art reviewed (§3.3), `./gradlew build` and `runClient` succeed with AE2 19.2.17 loaded |
| 1 | Block + inventory | Block places, GUI opens empty, slots persist across restart, hopper rules correct |
| 2 | Recipe index | Index builds on reload, logs count and failure summary, unit fixture in place |
| 3 | Tree builder + tiering | §13.1 tests pass, iron fixture first. No GUI yet |
| 4 | Tree GUI | Expand, collapse, select, virtualized scrolling, live plan count |
| 5 | Printing | Pre-print review screen, end-to-end print, partial-success handling, server validation |
| 6 | Polish | Config, lang, textures, sticky choices, oracle-driven auto-collapse, search |
| 7 | ATM10 pass | §13.2 in a real pack |

Milestone 3 is the risk, and specifically the ranking half of it — that is
where the prior art failed (§3.3). Do it before the GUI, and write the §13.1
ranking tests first; they are the only cheap way to iterate on heuristics.
§14 is already resolved, so nothing blocks starting at milestone 0.

---

## 16. Known risks

- **Recipe extraction fidelity** (§6.4) is the one that can make the mod produce
  quietly wrong patterns, which is worse than producing none. The trust flag and
  the no-auto-select rule exist specifically to keep a wrong pattern from being
  printed without the player having chosen it.
- **ATM10 recipe count** may push index build past a second. If so, build it
  lazily on first GUI open rather than on reload.
- **AE2 API drift** — 19.2.17 is verified (§14). Any AE2 update means rerunning
  `javap` over `appeng.api.crafting` before trusting §10.3.
- **Multi-select slows AE2's crafting calculation** (§17.5, verified). An item
  with two or more patterns is simulated one craft at a time instead of in one
  batch, re-walking the subtree per craft. It hits hardest on bulk intermediates,
  which are also the best candidates for multiple sources. `maxSourcesPerItem`
  mitigates it; nothing eliminates it. Measure with a thousand-item request in
  milestone 7.
- **The mod cannot make AE2 prefer the cheapest available path** (§17.3). AE2
  takes the highest-priority pattern that does not fail, and priority lives on
  the pattern provider block, not in the pattern. The GUI has to teach that
  rather than imply the printer decides it. Players expecting automatic cost
  optimisation and not getting it is the most likely source of "this mod does
  not work" reports.
- **Priority is unavailable for patterns sharing a provider** (§17.7), which is
  the normal case for crafting patterns in an assembler matrix. Worse, tied
  patterns are ordered by `HashSet` iteration over identity hashes, so the choice
  can change on world reload. The mod's answer is to default to one source per
  destination and to warn only when a collision is costly. This is a real ceiling
  on what the feature can promise, and the in-game text must not oversell past
  it.
- ~~**Compression loops.**~~ **Resolved** (§17.4). AE2's `notRecursive` refuses
  any pattern whose inputs or outputs match an ancestor goal, so it never packs
  ingots in order to unpack them. Shipping both directions is safe. The §13.1
  yield assertions still matter, for pattern correctness rather than for loops.
- **Tier heuristics are reasoned, not measured.** The `0.95` efficiency
  threshold and the promotion rules need tuning against a real pack. The §11.2
  review screen is what makes bad tuning visible instead of silent.
- **Too many alternates.** If `ALTERNATE` fires generously, a network fills with
  marginal patterns and the review screen becomes noise that gets
  click-through-confirmed. `alternatesConservative` and `maxSourcesPerItem`
  exist for this. Watch pattern counts in milestone 7.
- **Prior art overlap** (§3.3) — Recursive AE2 Pattern Provider covers the
  recursion. If it turns out to have usable selection control too, the honest
  outcome is a smaller mod or none.
- **Tag explosion** — items behind a tag accepting 100+ candidates are common in
  large packs. The cap handles it, but the ordering in §8.4 decides whether the
  8 shown are the useful 8. Expect to tune this against a real pack.

---

## 17. How AE2 actually selects patterns — verified

Source of truth: `appeng.crafting.CraftingTreeNode` and
`appeng.me.service.helpers.NetworkCraftingProviders`, decompiled from
`appliedenergistics2-19.2.17.jar` and cross-checked against the upstream
repository. This section answers the question the multi-select design in §1.2
rests on. **The design survives, with two corrections and one new cost.**

### 17.1 Storage is consumed before anything is crafted

`CraftingTreeNode.request` extracts what already exists before it looks at a
single pattern:

```java
for (InputTemplate template : getValidItemTemplates(inv)) {
    long extracted = CraftingCpuHelper.extractTemplates(inv, template, requestedAmount);
    if (extracted > 0) {
        requestedAmount -= extracted;
        if (requestedAmount == 0) return;   // never reaches pattern selection
    }
}
```

So "use the iron ingots I already have" needs no pattern and no help from us.

### 17.2 Patterns are ordered by pattern provider priority, descending

`NetworkCraftingProviders.PatternsForKey`:

```java
this.sortedPatterns = this.patterns.stream()
    .sorted(Comparator.comparingInt(pi -> pi.state.priority).reversed())
    .map(PatternInfo::pattern).distinct().toList();
```

`priority` comes from `provider.getPatternPriority()` — the priority field on the
pattern provider block, which the player sets in its GUI.

**This is the control surface, and it is the single most important consequence
for this mod.** Ordering among several sources for one item is decided by which
pattern provider holds each pattern, not by anything we encode. A pattern is
just an item; it carries no priority of its own.

### 17.3 Selection is first-success, with fallthrough on failure

```java
for (CraftingTreeProcess pro : this.nodes) {
    try {
        while (pro.possible && totalRequestedItems > 0) {
            var child = new ChildCraftingSimulationState(inv);
            pro.request(child, 1);              // one craft at a time
            var available = child.extract(this.what, totalRequestedItems, MODULATE);
            if (available != 0) { child.applyDiff(inv); totalRequestedItems -= available;
                                  if (totalRequestedItems <= 0) return; }
            else { pro.possible = false; }
        }
    } catch (CraftBranchFailure fail) {
        pro.possible = true;                   // abandon this branch, try the next
    }
}
```

There is no scoring, no cost model and no look-ahead at input availability. AE2
takes the highest-priority pattern and drains it, as its own comment says:
*"try as much as possible of one branch before moving to the next one."*

**Greedy and partial, not all-or-nothing.** Each successful craft is committed
to the parent inventory by `applyDiff` and decrements `totalRequestedItems` as
it goes. When the branch eventually throws, everything it already produced
stays; only the remainder moves on. Request 81 ingots with three blocks in
storage and unpacking at top priority, and it yields 27 from the blocks, throws
once they are gone (it cannot craft more, per §17.4), and passes the remaining
54 to the next source.

That is the behaviour multi-select wants: cheap available inputs are consumed
first, then the plan degrades to the next source.

The practical effect is close to what the multi-select design wants, but by a
different mechanism than assumed. A branch fails when its inputs are neither in
storage nor craftable, so an unbuildable path does drop through to the next.
**What it will not do is prefer a cheaper available path over a more expensive
available one.** If the ore-multiplication path is higher priority and its raw
ore is obtainable, it wins, even with a stack of iron blocks sitting unused.

This is a **calculation**-time mechanism. It decides which patterns a plan uses.
The provider round-robin in `CraftingProviderList` is **execution**-time and
distributes the operations of an already-chosen pattern across every provider
holding it. The two never interact: calculation picks the recipe, execution
spreads its work.

So: **order expresses preference, and order is the player's pattern provider
priority.** The mod's job is to print the right set and tell the player how to
rank it, not to pretend it can rank it for them.

### 17.4 AE2 blocks compression loops by itself

`CraftingTreeNode.notRecursive`, consulted for every candidate pattern:

```java
boolean notRecursive(IPatternDetails details) {
    for (var output : details.getOutputs())
        if (this.what.matches(output)) return false;
    for (var input : details.getInputs())
        if (this.what.matches(input.getPossibleInputs()[0])) return false;
    if (this.parent == null) return true;
    return this.parent.notRecursive(details);
}
```

Walking the whole ancestor chain, a pattern is refused if any ancestor goal
appears among its inputs or outputs.

Trace the iron case. Request ingots, take the unpack path, descend to a node for
Block of Iron. The 9-ingots-to-block pattern is then tested and rejected,
because the ancestor goal `iron_ingot` is one of its inputs. **Blocks are only
unpacked if blocks already exist; AE2 never packs ingots in order to unpack
them.**

That removes the compression-loop risk in §16 entirely. Shipping both directions
is safe, which is what §1.2 wanted.

Two consequences for us:

- **Our cycle detection should mirror this rule exactly** (§8.2), rather than the
  simpler path-scoped item check. AE2's is stricter — it tests outputs as well as
  inputs, and only `getPossibleInputs()[0]` of each input. A tree built on a
  looser rule will offer branches AE2 silently refuses, which looks like the mod
  printing patterns that do nothing.
- A pattern refused in one context is still used when the item is requested
  directly, so refusal is never a reason to skip printing it.

### 17.5 The real cost of multi-select

Compare the two paths in `request`. With **one** pattern, AE2 batches:

```java
times = (totalRequestedItems + craftedPerPattern - 1) / craftedPerPattern;
pro.request(inv, times);        // whole job, one simulation
```

With **more than one**, it steps:

```java
pro.request(child, 1);          // one craft, repeated
```

Adding a second pattern for an item converts that node from a single batched
simulation into one simulation per craft operation, each allocating a
`ChildCraftingSimulationState` and re-walking the subtree. Requesting 1000
ingots from a 9-yield pattern goes from one step to roughly 112.

This is a genuine, measurable cost and it lands hardest exactly where §1.2's
example lives: bulk intermediates like ingots and plates, requested in the
thousands, are both the best case for multiple sources and the worst case for
calculation time.

Design response, all already present or cheap to add:

- `maxSourcesPerItem` default drops from 4 to **3**, and the GUI warns above that.
- The §11.2 review screen shows a **per-item source count** so a player can see
  which items carry multiple patterns before printing.
- Document the tradeoff in-game via tooltip rather than hiding it. A player
  whose crafting calculations got slower deserves to know why.

### 17.6 What this means for §1.2

The iron example works, with the mechanism made explicit:

| Path | Printed? | How AE2 reaches it |
|---|---|---|
| Ingots already in storage | no pattern needed | §17.1 |
| Block of Iron → 9 ingots | yes | Used when blocks exist. Never loops (§17.4) |
| Raw Iron → ore chain → 4 ingots | yes | Used when its priority is higher and raw ore is obtainable |
| Raw Iron → smelt → 1 ingot | yes | Fallback when the chain branch fails |
| Iron Pickaxe → 1 ingot | **no** | Rejected by round-trip efficiency (§8.4.2) |

The one thing the mod cannot do is make AE2 choose the cheapest available path.
It can only ensure the right patterns exist and that the junk does not. Ordering
stays with the player, through pattern provider priority — and §17.7 covers the
large case where even that lever is unavailable.

### 17.7 Priority cannot separate patterns inside one provider

Priority is a property of the **pattern provider block**, not of a pattern. Most
crafting patterns live in an assembler matrix, which is a wall of pattern
providers feeding molecular assemblers, all sitting at the default priority. Two
crafting patterns for the same item in that matrix are tied, and priority has no
way to separate them short of physically moving one into a dedicated provider.

So for a large class of cases, §17.2 offers no control at all. What happens
instead is worse than arbitrary.

#### 17.7.1 Tied patterns are ordered non-deterministically

```java
private final Set<PatternInfo> patterns = new HashSet<>();   // PatternsForKey

this.sortedPatterns = this.patterns.stream()
    .sorted(Comparator.comparingInt(pi -> pi.state.priority).reversed())
    ...
```

`PatternInfo` is a record over `(pattern, state)`. `IPatternDetails`
implementations hash by their definition item, so that half is stable — but
`ProviderState` declares no `hashCode`, so it falls back to identity hash, which
differs every JVM run.

The sort is stable, so ties preserve the `HashSet` encounter order, and that
order is a function of those identity hashes. **The relative order of
equal-priority patterns for one item is arbitrary and can change every time the
world loads.**

A related thing players try first and which does not work: the slot order of
patterns inside a provider. `ProviderState` copies
`provider.getAvailablePatterns()` into an ordered list, then `mount` drops every
entry into the `HashSet` above, discarding it.

#### 17.7.2 Destination classes

The mod cannot change any of this, but it can tell the player where a collision
is going to happen, because a pattern's destination is fully determined by the
encoder chosen in §10.3:

| Destination | Patterns | Priority separable? |
|---|---|---|
| `ASSEMBLER` | crafting patterns → providers feeding molecular assemblers | Only by dedicating a provider |
| `MACHINE:<type>` | processing patterns → the provider on that machine | Yes, naturally separate blocks |
| `STONECUTTER`, `SMITHING` | their own pattern types | Yes |

Two selected sources for one item **collide** when they share a destination
class. That is the situation priority cannot fix.

The §1.2 iron example survives this cleanly, which is worth checking explicitly:

| Source | Destination | Collides? |
|---|---|---|
| Block of Iron → 9 ingots | `ASSEMBLER` | — |
| Mekanism ore chain → 4 ingots | `MACHINE:mekanism` | no |
| Smelt raw iron → 1 ingot | `MACHINE:furnace` | no |

Three sources, three destinations, three independently settable priorities. The
design works for the motivating case. It is a second crafting recipe — nuggets
to ingot alongside block to ingot — that collides.

#### 17.7.3 What the mod does about it

1. **Default to one source per destination class per item.** Multi-select across
   classes stays the default, because priority genuinely works there. Within a
   class the best-ranked candidate is checked and the rest are not.
2. **Warn only on costly collisions.** Two lossless unpack recipes in the
   assembler are interchangeable, so arbitrary order is harmless and silence is
   correct. Flag a collision only when the colliding sources differ materially in
   cost, using round-trip efficiency and oracle depth as the proxy. Warning on
   every tie would recreate the click-through-confirm failure in §16.
3. **Say what to do about it.** A flagged collision reads: *both patterns land in
   assembler providers at equal priority, so AE2 picks between them arbitrarily
   and may pick differently after a world reload. To control it, put one in its
   own pattern provider and lower that provider's priority.*
4. **Print grouped by destination.** Patterns are written to the output inventory
   in destination batches, and the review screen (§11.2) labels each batch with
   where it goes. This is the corrected form of the grouped-print idea: the
   grouping that matters is by destination, not by an invented priority tier,
   because destination is what the player physically acts on.
