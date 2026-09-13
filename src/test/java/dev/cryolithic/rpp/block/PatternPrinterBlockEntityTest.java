package dev.cryolithic.rpp.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.cryolithic.rpp.init.RppBlockEntities;
import dev.cryolithic.rpp.init.RppBlocks;
import dev.cryolithic.rpp.net.PrintRequestPayload;
import dev.cryolithic.rpp.net.PrintResultPayload;
import dev.cryolithic.rpp.recipe.RecipeIndexFixture;
import java.lang.reflect.Constructor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.profiling.metrics.MetricCategory;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests of the staleness check in
 * {@link PatternPrinterBlockEntity#runPrint} (GitHub #66). Boots the vanilla
 * registries, binds the mod's deferred block and block entity registrations
 * into the built-in registries, and drives {@code runPrint} on a real block
 * entity.
 */
class PatternPrinterBlockEntityTest {
    private static final String STALENESS_REASON = "input pattern changed";

    private static RecipeIndexFixture fixture;
    private static PatternPrinterBlockEntity printer;

    @BeforeAll
    static void setUp() throws Exception {
        RecipeIndexFixture.boot();
        fixture = new RecipeIndexFixture();

        // Bind the mod's deferred registrations into the built-in registries.
        // In a bare JVM the mod bus never fires, so the register events are
        // posted manually.
        ((MappedRegistry<Block>) BuiltInRegistries.BLOCK).unfreeze();
        ((MappedRegistry<BlockEntityType<?>>) BuiltInRegistries.BLOCK_ENTITY_TYPE).unfreeze();
        IEventBus bus = BusBuilder.builder().build();
        bus.start();
        RppBlocks.BLOCKS.register(bus);
        RppBlockEntities.BLOCK_ENTITIES.register(bus);
        // RegisterEvent's constructor is package-private in NeoForge;
        // construct it reflectively — the only reflection in this test.
        Constructor<RegisterEvent> eventCtor = RegisterEvent.class
                .getDeclaredConstructor(ResourceKey.class, Registry.class);
        eventCtor.setAccessible(true);
        bus.post(eventCtor.newInstance(Registries.BLOCK, BuiltInRegistries.BLOCK));
        bus.post(eventCtor.newInstance(Registries.BLOCK_ENTITY_TYPE, BuiltInRegistries.BLOCK_ENTITY_TYPE));

        BlockState state = RppBlocks.PATTERN_PRINTER.get().defaultBlockState();
        printer = new PatternPrinterBlockEntity(BlockPos.ZERO, state);
        printer.setLevel(new TestLevel(fixture.manager(),
                registryAccess(), new TestLevelData()));
    }

    /**
     * The built-in registries plus a stub {@code damage_type} registry,
     * which the {@link Level} constructor's {@code DamageSources} requires.
     */
    private static RegistryAccess registryAccess() throws Exception {
        java.util.List<Registry<?>> registries = new java.util.ArrayList<>();
        BuiltInRegistries.REGISTRY.forEach(registry -> registries.add(registry));
        MappedRegistry<DamageType> damageTypes =
                new MappedRegistry<>(Registries.DAMAGE_TYPE, com.mojang.serialization.Lifecycle.stable());
        // DamageSources' constructor resolves every vanilla damage type by key,
        // so register a stub for each (the print path never deals damage).
        for (java.lang.reflect.Field field : DamageTypes.class.getFields()) {
            if (field.getType() == ResourceKey.class) {
                @SuppressWarnings("unchecked")
                ResourceKey<DamageType> key = (ResourceKey<DamageType>) field.get(null);
                Registry.register(damageTypes, key, new DamageType(key.location().getPath(), DamageScaling.ALWAYS, 0.0F));
            }
        }
        registries.add(damageTypes);
        return new RegistryAccess.ImmutableRegistryAccess(registries);
    }

    /**
     * An equivalent pattern — a distinct instance of the same item with the
     * same components — must pass the staleness check. Before the fix,
     * {@code .equals} (identity) rejected every print here with
     * "input pattern changed".
     */
    @Test
    void equivalentPatternPassesStalenessCheck() {
        Item pattern = fixture.item("staleness_pattern");
        printer.getInventory().setStackInSlot(PatternPrinterBlockEntity.SLOT_INPUT, new ItemStack(pattern));
        PrintResultPayload result = printer.runPrint(
                new PrintRequestPayload(new ItemStack(pattern), List.of()));

        // The staleness gate must not reject an equivalent stack. Past the
        // gate the job proceeds to plan validation, which fails in a bare
        // JVM on the unloaded config ("internal error") — either way it is
        // not a staleness rejection.
        assertNotEquals(STALENESS_REASON, result.reason());
    }

    /** A changed pattern (different item) must still be rejected. */
    @Test
    void changedPatternIsRejected() {
        Item pattern = fixture.item("staleness_pattern_a");
        Item other = fixture.item("staleness_pattern_b");
        printer.getInventory().setStackInSlot(PatternPrinterBlockEntity.SLOT_INPUT, new ItemStack(pattern));
        PrintResultPayload result = printer.runPrint(
                new PrintRequestPayload(new ItemStack(other), List.of()));

        assertEquals(STALENESS_REASON, result.reason());
    }

    /** Same item but different components: the echo is not the same pattern. */
    @Test
    void changedComponentsAreRejected() {
        Item pattern = fixture.item("staleness_pattern_c");
        DataComponentType<CustomData> customData = (DataComponentType<CustomData>) (DataComponentType<?>) BuiltInRegistries
                .DATA_COMPONENT_TYPE.get(ResourceKey.create(Registries.DATA_COMPONENT_TYPE,
                        ResourceLocation.withDefaultNamespace("custom_data")));
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", 1);
        CompoundTag otherTag = new CompoundTag();
        otherTag.putInt("x", 2);
        ItemStack inSlot = new ItemStack(pattern);
        inSlot.set(customData, CustomData.of(tag));
        printer.getInventory().setStackInSlot(PatternPrinterBlockEntity.SLOT_INPUT, inSlot);
        PrintRequestPayload request = new PrintRequestPayload(new ItemStack(pattern), List.of());
        request.inputPattern().set(customData, CustomData.of(otherTag));
        PrintResultPayload result = printer.runPrint(request);

        assertEquals(STALENESS_REASON, result.reason());
    }

    /**
     * Minimal {@link Level} for the bare JVM: only the recipe manager and
     * registry access are used by {@code runPrint}; everything else is a
     * no-op stub.
     */
    private static final class TestLevel extends Level {
        private final RecipeManager recipeManager;
        private final RegistryAccess registryAccess;
        private final WritableLevelData levelData;

        private static final ProfilerFiller NO_OP_PROFILER = new NoOpProfilerFiller();

        TestLevel(RecipeManager recipeManager, RegistryAccess registryAccess, WritableLevelData levelData) {
            super(levelData,
                    ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("rpp", "test")),
                    registryAccess,
                    new DirectHolder<>(overworldDimensionType()),
                    () -> NO_OP_PROFILER,
                    false, false, 0L, 10);
            this.recipeManager = recipeManager;
            this.registryAccess = registryAccess;
            this.levelData = levelData;
        }

        @Override
        public void blockEntityChanged(BlockPos pos) {
        }

        @Override
        public void updateNeighbourForOutputSignal(BlockPos pos, net.minecraft.world.level.block.Block block) {
        }

        /** The vanilla overworld dimension type, as a bare holder (no registry needed). */
        private static DimensionType overworldDimensionType() {
            return new DimensionType(java.util.OptionalLong.empty(), true, false, false, true, 1.0,
                    true, true, -64, 384, 192, null,
                    ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 0.0F, null);
        }

        @Override
        public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        }

        @Override
        public void playSeededSound(Player player, double x, double y, double z, Holder<SoundEvent> sound,
                SoundSource source, float volume, float pitch, long seed) {
        }

        @Override
        public void playSeededSound(Player player, Entity entity, Holder<SoundEvent> sound, SoundSource source,
                float volume, float pitch, long seed) {
        }

        @Override
        public String gatherChunkSourceStats() {
            return "";
        }

        @Override
        public Entity getEntity(int id) {
            return null;
        }

        @Override
        public java.util.List<? extends Player> players() {
            return java.util.List.of();
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            return FeatureFlagSet.of();
        }

        @Override
        public Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public float getShade(net.minecraft.core.Direction side, boolean tinted) {
            return 0F;
        }

        @Override
        public TickRateManager tickRateManager() {
            return null;
        }

        @Override
        public MapItemSavedData getMapData(MapId id) {
            return null;
        }

        @Override
        public void setMapData(MapId id, MapItemSavedData data) {
        }

        @Override
        public MapId getFreeMapId() {
            return null;
        }

        @Override
        public void destroyBlockProgress(int id, BlockPos pos, int max) {
        }

        @Override
        public Scoreboard getScoreboard() {
            return null;
        }

        @Override
        public RecipeManager getRecipeManager() {
            return recipeManager;
        }

        @Override
        protected LevelEntityGetter<Entity> getEntities() {
            return null;
        }

        @Override
        public PotionBrewing potionBrewing() {
            return null;
        }

        @Override
        public void setDayTimeFraction(float fraction) {
        }

        @Override
        public float getDayTimeFraction() {
            return 0;
        }

        @Override
        public float getDayTimePerTick() {
            return 0;
        }

        @Override
        public void setDayTimePerTick(float ticksPerDay) {
        }

        @Override
        public long nextSubTickCount() {
            return 0;
        }

        @Override
        public LevelTickAccess<Block> getBlockTicks() {
            return null;
        }

        @Override
        public LevelTickAccess<Fluid> getFluidTicks() {
            return null;
        }

        @Override
        public LevelData getLevelData() {
            return levelData;
        }

        @Override
        public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
            return new DifficultyInstance(Difficulty.PEACEFUL, 0L, 0L, 0F);
        }

        @Override
        public MinecraftServer getServer() {
            return null;
        }

        @Override
        public ChunkSource getChunkSource() {
            return null;
        }

        @Override
        public RandomSource getRandom() {
            return RandomSource.create();
        }

        @Override
        public void playSound(Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume,
                float pitch) {
        }

        @Override
        public void addParticle(ParticleOptions options, double x, double y, double z, double velocityX,
                double velocityY, double velocityZ) {
        }

        @Override
        public void levelEvent(Player player, int type, BlockPos pos, int data) {
        }

        @Override
        public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
        }
    }

    /** Minimal {@link WritableLevelData}; never read by the print path. */
    private static final class TestLevelData implements WritableLevelData {
        @Override
        public BlockPos getSpawnPos() {
            return BlockPos.ZERO;
        }

        @Override
        public float getSpawnAngle() {
            return 0;
        }

        @Override
        public long getGameTime() {
            return 0;
        }

        @Override
        public long getDayTime() {
            return 0;
        }

        @Override
        public boolean isThundering() {
            return false;
        }

        @Override
        public boolean isRaining() {
            return false;
        }

        @Override
        public void setRaining(boolean raining) {
        }

        @Override
        public boolean isHardcore() {
            return false;
        }

        @Override
        public GameRules getGameRules() {
            return null;
        }

        @Override
        public Difficulty getDifficulty() {
            return Difficulty.PEACEFUL;
        }

        @Override
        public boolean isDifficultyLocked() {
            return false;
        }

        @Override
        public void setSpawn(BlockPos pos, float angle) {
        }
    }

    /** No-op {@link ProfilerFiller}; the print path never profiles. */
    private static final class NoOpProfilerFiller implements ProfilerFiller {
        @Override
        public void startTick() {
        }

        @Override
        public void endTick() {
        }

        @Override
        public void push(String name) {
        }

        @Override
        public void push(java.util.function.Supplier<String> name) {
        }

        @Override
        public void pop() {
        }

        @Override
        public void popPush(String name) {
        }

        @Override
        public void popPush(java.util.function.Supplier<String> name) {
        }

        @Override
        public void markForCharting(MetricCategory category) {
        }

        @Override
        public void incrementCounter(String name, int amount) {
        }


        @Override
        public void incrementCounter(java.util.function.Supplier<String> name, int amount) {
        }
    }
    /** A bare {@link Holder} that carries a value without a registry. */
    private static final class DirectHolder<T> implements Holder<T> {
        private final T value;

        private DirectHolder(T value) {
            this.value = value;
        }

        @Override
        public T value() {
            return value;
        }

        @Override
        public boolean isBound() {
            return true;
        }

        @Override
        public boolean is(ResourceLocation name) {
            return false;
        }

        @Override
        public boolean is(ResourceKey<T> key) {
            return false;
        }

        @Override
        public boolean is(java.util.function.Predicate<ResourceKey<T>> predicate) {
            return false;
        }

        @Override
        public boolean is(net.minecraft.tags.TagKey<T> tag) {
            return false;
        }

        @Override
        public boolean is(Holder<T> holder) {
            return this == holder;
        }

        @Override
        public java.util.stream.Stream<net.minecraft.tags.TagKey<T>> tags() {
            return java.util.stream.Stream.empty();
        }

        @Override
        public com.mojang.datafixers.util.Either<ResourceKey<T>, T> unwrap() {
            return com.mojang.datafixers.util.Either.right(value);
        }

        @Override
        public java.util.Optional<ResourceKey<T>> unwrapKey() {
            return java.util.Optional.empty();
        }

        @Override
        public Kind kind() {
            return Kind.DIRECT;
        }

        @Override
        public boolean canSerializeIn(HolderOwner<T> owner) {
            return false;
        }
    }
}
