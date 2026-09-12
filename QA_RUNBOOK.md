# In-game QA runbook (DESIGN.md §13.2)

Automatable parts are done and recorded below. The interactive tests need a
human at the keyboard; each entry lists setup, action, and pass criteria.

## Setup

- Interactive client: `./gradlew runClient` (X display `:0` is available).
  Add `-Patm10` to the same command to load the §3.2 coexistence set
  (AdvancedAE, AE2WTLib, ExtendedAE, MEGA Cells, Import/Export Card,
  AE2 JEI Integration, Network Analyzer, Crafting Tweaks + their libraries)
  alongside rpp.
- Dedicated server: `./gradlew runServer` (or with `-Patm10`).
- Getting the block: creative inventory → search "Recursive Pattern Printer",
  or `/give @p rpp:pattern_printer`.
- Making an encoded pattern (the input slot accepts any AE2 encoded pattern):
  place an AE2 Pattern Printer, insert a blank pattern plus the recipe
  ingredients, craft once — the output is an encoded pattern for that recipe.
  Repeat for each pattern you need.
- The rpp block: slot 0 = input encoded pattern (size 1), slot 1 = blank
  patterns, slots 2–28 = printed output. Right-click to open the tree GUI.

## Done (automated, 2026-09-12)

- **Coexistence load test**: `./gradlew runServer -Patm10` — all 17 mods
  loaded (rpp, ae2 19.2.17, guideme, the 8 §3.2 addons, geckolib, glodium,
  balm, jei) and the server started. No registry or mixin conflicts; the only
  warnings were dev-env refmap notes and one optional extendedae mixin target
  (functional storage absent — harmless).
- **Unit suite**: 75/75 green, including the §13.1 iron fixture
  (smelt PRIMARY; block-unpack ALTERNATE checked, yield 9; ore-chain
  ALTERNATE checked; pickaxe-melt REJECTED unchecked; exactly 3 default
  sources; node neither forced nor collapsed).

## Interactive tests

### 1. Deep-item tree render
- Setup: encode a pattern for a deep item (e.g. a machine part with several
  ingredient levels), insert it, open the GUI.
- Pass: first paint under 200 ms; root node shows the goal item; initial
  depth expanded per config.

### 2. 500-recipe item expansion
- Setup: find an item with hundreds of recipes (in ATM10: any common
  component with many mod recipes), expand its node.
- Pass: no frame drop while the spinner runs; after expansion the `+K more`
  cap marker is present if the per-node recipe cap was hit.

### 3. Alternate-branch selection
- Setup: any item with ≥2 sources (iron ingot works).
- Action: uncheck the PRIMARY, check an ALTERNATE.
- Pass: live plan count updates immediately; the deselected branch's subtree
  is pruned from the plan (its unique ingredients drop out).

### 4. Iron acceptance test (the test the prior art fails)
- Setup: encoded pattern with output = iron ingot; 4+ blank patterns.
- Action: open the tree, print with the default selection.
- Pass: exactly 3 patterns printed — smelt (furnace), block-unpack (9
  ingots per block), ore-chain — and NO pickaxe-melt pattern. Verify the
  block-unpack pattern actually produces 9 ingots per block in a network.

### 5. Live network behaviour (§16 — the open risk)
- Setup: put all three iron patterns in a network (Pattern Provider or
  Pattern Terminal) with a crafting interface; storage holds iron BLOCKS only,
  no raw iron.
- Action: request 1 ingot; observe which pattern AE2's crafting uses.
  Then reverse the stock (raw iron only, no blocks) and repeat.
- Pass: none — this is an observation. Record which pattern was used in each
  stock configuration; it is the evidence for the honest-text claim (§17.3)
  that AE2, not rpp, decides at craft time.

### 6. Compression-loop check
- Setup: network with both ingot→block (9→1) and block→ingot (1→9) patterns.
- Action: request 1 block; then 1 ingot.
- Pass: AE2 plans and completes (or fails gracefully) without looping.
  §17.4 says it cannot loop; this is a regression guard.

### 7. Tie instability (§17.7.1)
- Setup: two crafting patterns for the same item in the same assembler
  provider (e.g. two identical-looking recipes from different mods).
- Action: request the item, note which pattern is used; restart the world;
  repeat 3–5 times.
- Pass: none — observation. Record the distribution. Non-determinism across
  restarts confirms the collision warning the GUI shows (⚑ badge).

### 8. Destination batching
- Setup: a plan spanning ≥3 destinations — e.g. one crafting recipe, one
  furnace recipe, one stonecutter recipe (hand-pick them).
- Action: print.
- Pass: the output inventory groups patterns in the order ASSEMBLER, then
  MACHINE:* (alphabetical by machine type), STONECUTTER, SMITHING — so each
  batch can go to one provider type.

### 9. preferredMods shift
- Setup: set `preferredMods` in the rpp config to a dominant pack mod
  namespace (e.g. `mekanism`), reload config.
- Action: open a tree with items that have recipes in that mod.
- Pass: PRIMARY shifts to the preferred mod's recipes wherever they exist,
  across the whole tree.

### 10. Select-all on a deep item
- Action: toolbar [✔ all] on a deep item's tree.
- Pass: pattern count equals the number of selectable patterns; GUI stays
  responsive (no hang, no frame drop).

### 11. Deselect-all + hand-pick three
- Action: [✘ all], then hand-check exactly 3 recipes; print.
- Pass: exactly 3 patterns in the output inventory, matching the 3 checked.

### 12. Print with fewer blanks than patterns
- Setup: 3-pattern plan, 2 blank patterns in slot 1.
- Action: print.
- Pass: 2 patterns printed, 1 reported skipped with an accurate count; no
  duplicate or lost blanks (slot 1 ends with exactly 0 blanks).

### 13. Print with a full output inventory
- Setup: fill all 27 output slots with items.
- Action: print.
- Pass: clean stop with a report; no blank is consumed for a pattern that
  could not be placed.

### 14. /reload with the GUI open
- Action: open the tree GUI, run `/reload`.
- Pass: the tree rebuilds from the root; no stale nodes (no recipes from
  before the reload); no crash.

### 15. recursiveae2patternprovider coexistence
- Setup: in the ATM10 instance, rename
  `recursiveae2patternprovider-1.0.8.jar.disabled` → `.jar` (or add the jar
  to the `-Patm10` set for the dev env).
- Action: load both mods.
- Pass: either they coexist (no mod-id/registry/mixin/config collision —
  expected, per §3.3 prior-art review) or the failure mode is recorded as the
  reason it was disabled in the pack.

### 16. Dedicated server + client
- Setup: `./gradlew runServer` in one terminal; `./gradlew runClient` in
  another; join the server from the client (localhost).
- Action: place the block, run a print.
- Pass: the server-side validation path runs for real (staleness check,
  plan validation, encoding) and the printed patterns land in the inventory.

## Config reference (for tests 9, and general)

All keys in `RppConfig` (DESIGN.md §12): `initialDepth`, `maxDepth`,
`maxRecipesPerItem`, `maxTotalNodes`, `maxNodesPerExpansion`,
`maxCandidatesPerIngredient`, `maxSourcesPerItem`, `maxSourcesPerDestination`,
`maxPrintBatch`, `allowSubstitutions`, `alwaysReviewBeforePrint`,
`groupPrintByDestination`, `stickyChoices`, `requireTrustedRecipes`,
`alternatesConservative`, `minRoundTripEfficiency`, `warnOnCostlyCollisions`,
`preferredMods`, `blacklistedRecipeTypes`.
