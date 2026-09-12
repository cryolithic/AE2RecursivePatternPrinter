# API notes — DESIGN.md §14.2

Verified by `javap` against the mapped NeoForge 21.1.217 artifact
(`build/moddev/artifacts/neoforge-21.1.217.jar`) and the AE2 19.2.17 jar
from Modrinth maven, on 2026-09-11, in this workspace.

## 5. `Recipe#getResultItem` on 1.21.1

```java
public abstract net.minecraft.world.item.ItemStack getResultItem(net.minecraft.core.HolderLookup.Provider);
```

- Parameter type is **`HolderLookup.Provider`** (not `RegistryAccess`).
- Return type is **`ItemStack`** (not `Holder<Item>`). An empty result is
  `ItemStack.EMPTY`; test with `isEmpty()`.

## 6. `RecipeManager#getRecipes` on 1.21.1

```java
public java.util.Collection<net.minecraft.world.item.crafting.RecipeHolder<?>> getRecipes();
```

- Returns **`Collection<RecipeHolder<?>>`** (not a `Map`).
- **`RecipeHolder` is still the wrapper** — a record
  `(ResourceLocation id, T value)` with `id()` and `value()` accessors.

## Other verified signatures (used by the scaffold)

- `Recipe.getIngredients()` → `NonNullList<Ingredient>` (a `List`).
- `Ingredient.getItems()` → `ItemStack[]`; `Ingredient.getValues()` →
  `Ingredient.Value[]`. A tag-based ingredient is
  `Ingredient.TagValue` (record) with `tag()` → `TagKey<Item>`; there is no
  `Ingredient.getTag()` in 1.21.1.
- `ShapedRecipe` exposes `getWidth()` / `getHeight()` (not
  `getRecipeWidth`/`getRecipeHeight`).
- `RecipeType` lives in `net.minecraft.world.item.crafting` (not
  `net.minecraft.world.inventory`).
- `ModConfigSpec` lives in `net.neoforged.neoforge.common` (not `.config`);
  read values via the value object's own `get()`, e.g.
  `RppConfig.INITIAL_DEPTH.get()`.
- `DeferredRegister` provides `createBlocks`/`createItems` convenience
  factories only; block entity types and menu types use
  `DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, modid)` and
  `DeferredRegister.create(Registries.MENU, modid)`.
- AE2: `AEItemKey.of(ItemLike | ItemStack)`, `getItem()`, `toStack()`;
  `GenericStack` is a record `(AEKey what, long amount)` with constructor
  `GenericStack(AEKey, long)`.
- The NeoForge ModDev plugin uses standard Gradle configurations
  (`implementation`, `compileOnly`) — there is no `modImplementation`.

## 7. Print pipeline (milestone 5) — verified 2026-09-12

All four `PatternDetailsHelper` encoders re-verified against
`applied-energistics2-19.2.17.jar` (`javap`): the signatures in DESIGN.md
§10.3 are exactly right, no corrections. Additional findings:

- **Payload registration on 21.1.217**: the old
  `PayloadRegistry.registerClientToServer/registerServerToClient` methods do
  not exist. Use `event.registrar("<version>")` from
  `RegisterPayloadHandlersEvent` with `.playToServer(type, codec, handler)` /
  `.playToClient(...)`. Play-phase handlers run on the main thread by
  default (`HandlerThread.MAIN`), so block-entity access in a C2S handler is
  safe without `.executesOn`.
- **Smithing inputs are not exposed**: `SmithingTransformRecipe` does not
  override `Recipe#getIngredients()` — the default returns an empty list.
  Template/base/addition are package-private fields on
  `SmithingTransformRecipe`, read reflectively by `PrintPlan.viewOf`.
- **Mapping names**: the codec constants class is `ByteBufCodecs` (not
  `StreamCodecs`); `ByteBufCodecs.list()` returns a
  `StreamCodec.CodecOperation` — call `.apply(codec)` to get the list codec.
  `ResourceLocation.STREAM_CODEC` and `ItemStack.STREAM_CODEC` exist as
  advertised.
- **Player accessors**: `Player.containerMenu` is a public field (no
  `getContainerMenu()` accessor in this mapping); there is no
  `ServerPlayer.sendPacket` — send via `player.connection.send(packet)` or
  the payload context's `context.reply(payload)`.
- **AE2 encoders need AE2 items registered**: `PatternDetailsHelper.encode*`
  builds its result stack from `AEItems`' deferred item definitions, which
  bind to whatever is registered under the AE2 item key. In bare-JVM tests
  the pattern items (`AEItemIds.CRAFTING_PATTERN` et al.) must be registered
  in `BuiltInRegistries.ITEM` first (see `PatternItems` in the print tests).
  The encoded pattern's type is readable without a Level via the
  `AEComponents.ENCODED_*_PATTERN` data components.
