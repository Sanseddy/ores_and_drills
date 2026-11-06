package dev.world.geology.exploration;

import dev.config.SeismicSurveyConfig;
import dev.registry.ModBlockEntities;
import dev.registry.ModBlocks;
import dev.registry.ModDataComponents;
import dev.registry.ModSounds;
import dev.world.block.entity.drill.EnergyBuffer;
import dev.world.geology.Borehole;
import dev.world.geology.CaveAnalyzer;
import dev.world.geology.GeologySavedData;
import dev.world.geology.NaturalGeology;
import dev.world.geology.RockPhysics;
import dev.world.geology.ScannerUpgrade;
import dev.world.geology.SeedHash;
import dev.world.geology.SurveySite;
import dev.world.geology.SurveyTarget;
import dev.world.geology.UpgradeModuleItem;
import dev.world.geology.lidar.LidarMap;
import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreSignatureTable;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositTier;
import dev.world.level.levelgen.OreTags;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Compact core drill. It logs the column under itself block by block without removing anything:
 * rock, voids, fluids and ore, plus ore the logging tool senses within a few blocks of the hole.
 * When it intersects ore it reports the ore, its depth and only the units within a small radius of
 * the hole, never the size of the whole deposit. Under a drill on the ground the column lies in its
 * own chunk; the logging ring reads neighbours only where they are loaded.
 *
 * <p>A drill carried by a Sable body bores the ground under it instead of the plot its block is
 * stored in; the hole is fixed when drilling starts, and moving the body off it pulls the string
 * out and ends the hole.
 *
 * <p>The core is analysed with the same identification model as seismic surveys, at laboratory
 * precision: the analyser's upgrade level decides whether it names the ore or only its class, and
 * how confidently. While drilling it builds a 3D log of the hole, see {@link BoreholeLidar}.
 */
public final class ExplorationDrillBlockEntity extends BlockEntity
        implements UpgradeModuleItem.Upgradeable {
    private static final long ANALYSIS_SALT = 0x434F5245L;

    /** Horizontal radius in which the core estimate counts ore units around the intersection. */
    public static final int ESTIMATE_RADIUS = 4;

    /** Horizontal radius the logging tool senses ore beside the hole. */
    public static final int LOGGING_RADIUS = 3;

    /** How far a carried drill may drift sideways from its hole before the string comes out. */
    public static final double MOVE_TOLERANCE = 1.5D;

    public static final int STATE_IDLE = 0;
    public static final int STATE_DRILLING = 1;
    public static final int STATE_DONE = 2;
    public static final int STATE_NO_POWER = 3;
    public static final int STATE_BLOCKED = 4;

    private final EnergyBuffer energy;
    private final SimpleContainer output =
            new SimpleContainer(1) {
                @Override
                public void setChanged() {
                    super.setChanged();
                    ExplorationDrillBlockEntity.this.setChanged();
                }
            };
    private int state = STATE_IDLE;
    private BlockPos hole;
    private int topY;
    private int cursorY;
    private int progress;
    private final List<BoreholeLog.Interval> intervals = new ArrayList<>();
    private final List<Integer> signals = new ArrayList<>();
    private ResourceLocation ore;
    private int oreTopY;
    private int oreBottomY;
    private boolean oreClosed;
    private boolean industrial;
    private final BoreholeLidar lidar = new BoreholeLidar();

    public ExplorationDrillBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.EXPLORATION_DRILL.get(), pos, blockState);
        int capacity;
        try {
            capacity = SeismicSurveyConfig.DRILL_ENERGY_CAPACITY.get();
        } catch (IllegalStateException notLoaded) {
            capacity = 40_000;
        }
        this.energy = new EnergyBuffer(capacity, capacity, this::setChanged);
        lidar.onChange(this::setChanged);
    }

    /** The 3D log of the current or last borehole. */
    public LidarMap lidarMap() {
        return lidar.map();
    }

    public IEnergyStorage energyStorage() {
        return energy;
    }

    public SimpleContainer output() {
        return output;
    }

    public int state() {
        return state;
    }

    public int energyStored() {
        return energy.getEnergyStored();
    }

    public int energyCapacity() {
        return energy.getMaxEnergyStored();
    }

    public int topY() {
        return topY;
    }

    /**
     * Top of the current or last borehole: under the drill, or under the Sable body carrying it.
     */
    public BlockPos hole() {
        return hole == null ? worldPosition : hole;
    }

    /** Where the drill bores now: its hole while drilling or holding a result. */
    public BlockPos currentHole() {
        if (hole != null || level == null) {
            return hole();
        }
        return SurveySite.of(level, worldPosition);
    }

    public int cursorY() {
        return cursorY;
    }

    @Override
    public int scannerLevel() {
        BlockState current = getBlockState();
        return current.hasProperty(ExplorationDrillBlock.LEVEL)
                ? current.getValue(ExplorationDrillBlock.LEVEL)
                : 0;
    }

    @Override
    public boolean raiseScannerLevel() {
        int current = scannerLevel();
        if (current >= ScannerUpgrade.MAX_LEVEL || level == null) {
            return false;
        }
        level.setBlock(
                worldPosition,
                getBlockState().setValue(ExplorationDrillBlock.LEVEL, current + 1),
                3);
        setChanged();
        return true;
    }

    @Override
    public int analyzerLevel() {
        BlockState current = getBlockState();
        return current.hasProperty(ExplorationDrillBlock.ANALYZER)
                ? current.getValue(ExplorationDrillBlock.ANALYZER)
                : 0;
    }

    @Override
    public boolean raiseAnalyzerLevel() {
        int current = analyzerLevel();
        if (current >= CaveAnalyzer.MAX_LEVEL || level == null) {
            return false;
        }
        level.setBlock(
                worldPosition,
                getBlockState().setValue(ExplorationDrillBlock.ANALYZER, current + 1),
                3);
        setChanged();
        return true;
    }

    public int bottomLimit() {
        if (level == null) {
            return topY;
        }
        return Math.max(level.getMinBuildHeight(), topY - maxDepth());
    }

    private static int maxDepth() {
        try {
            return SeismicSurveyConfig.DRILL_MAX_DEPTH.get();
        } catch (IllegalStateException notLoaded) {
            return 256;
        }
    }

    /** Starts a new borehole; refused while drilling or while the last core is not taken. */
    public boolean start() {
        if (state == STATE_DRILLING || state == STATE_NO_POWER || !output.isEmpty()) {
            if (!output.isEmpty()) {
                state = STATE_BLOCKED;
                sync();
            }
            return false;
        }
        hole = level == null ? worldPosition : SurveySite.of(level, worldPosition);
        topY = hole.getY() - 1;
        cursorY = topY;
        progress = 0;
        intervals.clear();
        signals.clear();
        ore = null;
        oreClosed = false;
        industrial = false;
        lidar.start();
        state = STATE_DRILLING;
        sync();
        return true;
    }

    public void stop() {
        if (state == STATE_DRILLING || state == STATE_NO_POWER) {
            finish((ServerLevel) level);
        }
    }

    public static void serverTick(
            Level level, BlockPos pos, BlockState blockState, ExplorationDrillBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (be.state != STATE_DRILLING && be.state != STATE_NO_POWER) {
            return;
        }
        BlockPos hole = be.hole();
        if (SurveySite.moved(level, pos, hole, MOVE_TOLERANCE)) {
            be.finish(serverLevel);
            return;
        }
        BlockPos sample = new BlockPos(hole.getX(), be.cursorY, hole.getZ());
        // Under a carried drill the column is outside its chunk and must not be loaded for it.
        if (!serverLevel.isLoaded(sample)) {
            return;
        }
        int cost = SeismicSurveyConfig.DRILL_ENERGY_PER_TICK.get();
        if (!be.energy.hasEnergy(cost)) {
            if (be.state != STATE_NO_POWER) {
                be.state = STATE_NO_POWER;
                be.sync();
            }
            return;
        }
        if (be.state == STATE_NO_POWER) {
            be.state = STATE_DRILLING;
            be.sync();
        }
        be.energy.consume(cost);
        if (++be.progress < SeismicSurveyConfig.DRILL_TICKS_PER_BLOCK.get()) {
            be.setChanged();
            return;
        }
        be.progress = 0;
        BlockState core = serverLevel.getBlockState(sample);
        if (core.getDestroySpeed(serverLevel, sample) < 0.0F || be.cursorY <= be.bottomLimit()) {
            be.finish(serverLevel);
            return;
        }
        be.log(serverLevel, sample, core);
        be.effects(serverLevel, core);
        be.cursorY--;
        be.setChanged();
    }

    private void log(ServerLevel serverLevel, BlockPos sample, BlockState core) {
        BoreholeLog.Kind kind;
        ResourceLocation id;
        if (core.is(ModBlocks.ORE_DEPOSIT.get())) {
            id = OreDepositData.oreBlockIdAt(serverLevel, sample);
            kind = id == null ? BoreholeLog.Kind.ROCK : BoreholeLog.Kind.ORE;
            if (id == null) {
                id = BuiltInRegistries.BLOCK.getKey(core.getBlock());
            }
        } else if (core.isAir()) {
            kind = BoreholeLog.Kind.OPEN;
            id = BuiltInRegistries.BLOCK.getKey(core.getBlock());
        } else if (core.getBlock() instanceof LiquidBlock) {
            kind = BoreholeLog.Kind.FLUID;
            id = BuiltInRegistries.FLUID.getKey(core.getFluidState().getType());
        } else {
            kind = OreTags.isOre(core) ? BoreholeLog.Kind.ORE : BoreholeLog.Kind.ROCK;
            id = BuiltInRegistries.BLOCK.getKey(core.getBlock());
        }
        append(sample.getY(), id, kind);

        if (kind == BoreholeLog.Kind.ORE) {
            if (ore == null) {
                ore = id;
                oreTopY = sample.getY();
                oreBottomY = sample.getY();
                industrial =
                        core.is(ModBlocks.ORE_DEPOSIT.get())
                                && OreDepositData.tierAt(serverLevel, sample)
                                        >= OreDepositTier.MEDIUM.index();
                lidar.ore(hole(), ore, oreTopY, oreBottomY, List.of(), serverLevel.getGameTime());
            } else if (!oreClosed && id.equals(ore)) {
                oreBottomY = sample.getY();
                lidar.ore(hole(), ore, oreTopY, oreBottomY, List.of(), serverLevel.getGameTime());
            }
        } else if (ore != null) {
            oreClosed = true;
        }
        lidar.log(
                serverLevel,
                hole(),
                sample,
                core,
                kind,
                id,
                analyzerLevel(),
                serverLevel.getGameTime());
        if (kind != BoreholeLog.Kind.ORE && senses(serverLevel, sample)) {
            if (signals.isEmpty() || signals.getLast() != sample.getY() + 1) {
                if (signals.size() < BoreholeLog.MAXIMUM_SIGNALS) {
                    signals.add(sample.getY());
                }
                lidar.sensed(sample, serverLevel.getGameTime());
            } else {
                signals.set(signals.size() - 1, sample.getY());
            }
        }
    }

    /** Whether ore sits within logging range beside the hole at this depth. */
    private static boolean senses(ServerLevel serverLevel, BlockPos sample) {
        for (int dx = -LOGGING_RADIUS; dx <= LOGGING_RADIUS; dx++) {
            for (int dz = -LOGGING_RADIUS; dz <= LOGGING_RADIUS; dz++) {
                if ((dx == 0 && dz == 0) || dx * dx + dz * dz > LOGGING_RADIUS * LOGGING_RADIUS) {
                    continue;
                }
                BlockPos near = sample.offset(dx, 0, dz);
                if (!serverLevel.isLoaded(near)) {
                    continue;
                }
                BlockState state = serverLevel.getBlockState(near);
                if (state.is(ModBlocks.ORE_DEPOSIT.get()) || OreTags.isOre(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void append(int y, ResourceLocation id, BoreholeLog.Kind kind) {
        if (!intervals.isEmpty()) {
            BoreholeLog.Interval last = intervals.getLast();
            if (last.block().equals(id) && last.kind() == kind && last.bottomY() == y + 1) {
                intervals.set(
                        intervals.size() - 1, new BoreholeLog.Interval(last.topY(), y, id, kind));
                return;
            }
        }
        if (intervals.size() < BoreholeLog.MAXIMUM_INTERVALS) {
            intervals.add(new BoreholeLog.Interval(y, y, id, kind));
        }
    }

    private void effects(ServerLevel serverLevel, BlockState core) {
        if (level.getGameTime() % 4L == 0L && !core.isAir() && core.getFluidState().isEmpty()) {
            serverLevel.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, core),
                    worldPosition.getX() + 0.5D,
                    worldPosition.getY() + 0.1D,
                    worldPosition.getZ() + 0.5D,
                    3,
                    0.2D,
                    0.05D,
                    0.2D,
                    0.05D);
        }
        if (level.getGameTime() % 10L == 0L) {
            serverLevel.playSound(
                    null,
                    worldPosition,
                    ModSounds.EXPLORATION_DRILL_WORK.get(),
                    SoundSource.BLOCKS,
                    0.6F,
                    0.9F + serverLevel.random.nextFloat() * 0.2F);
        }
    }

    private void finish(ServerLevel serverLevel) {
        int localUnits = ore == null ? 0 : localEstimate(serverLevel);
        int analyserLevel = scannerLevel();
        List<BoreholeLog.Analysis> analysis =
                ore == null ? List.of() : analyse(serverLevel, analyserLevel);
        if (ore != null) {
            lidar.ore(
                    hole(),
                    ore,
                    oreTopY,
                    oreBottomY,
                    candidates(analysis),
                    serverLevel.getGameTime());
        }
        lidar.finish(hole(), cursorY + 1, analyzerLevel(), serverLevel.getGameTime());
        BoreholeLog log =
                new BoreholeLog(
                        hole(),
                        cursorY + 1,
                        intervals,
                        Optional.ofNullable(ore),
                        oreTopY,
                        oreBottomY,
                        localUnits,
                        industrial,
                        signals,
                        analyserLevel,
                        analysis);
        ItemStack sample = new ItemStack(ModBlocks.CORE_SAMPLE.get());
        sample.set(ModDataComponents.BOREHOLE_LOG.get(), log);
        output.setItem(0, sample);
        GeologySavedData.get(serverLevel)
                .addBorehole(
                        new Borehole(
                                hole(),
                                cursorY + 1,
                                serverLevel.getGameTime(),
                                ore,
                                oreTopY,
                                oreBottomY,
                                localUnits,
                                industrial));
        serverLevel.playSound(
                null,
                worldPosition,
                ModSounds.SEISMIC_STATION_FINISH.get(),
                SoundSource.BLOCKS,
                0.6F,
                1.4F);
        state = STATE_DONE;
        sync();
    }

    /**
     * Identifies the intersected ore from the core's measured properties. The material is in hand,
     * so natural look-alikes are ruled out; what remains is telling similar ores apart.
     */
    private List<BoreholeLog.Analysis> analyse(ServerLevel serverLevel, int analyserLevel) {
        ResourceLocation dimension = serverLevel.dimension().location();
        NaturalGeology.Setting setting = NaturalGeology.Setting.forDimension(dimension.toString());
        double y = (oreTopY + oreBottomY) * 0.5D;
        double[] truth =
                OreIdentification.expectedOre(
                        NaturalGeology.host(setting, y), RockPhysics.forOre(ore));
        OreIdentification.Precision precision = OreIdentification.Precision.core(analyserLevel);
        OreIdentification.Observation observation =
                OreIdentification.measure(
                        truth,
                        y,
                        true,
                        precision,
                        SeedHash.combine(
                                serverLevel.getSeed() ^ ANALYSIS_SALT, hole().asLong(), oreTopY));
        OreIdentification.Result result =
                OreIdentification.identify(
                        observation,
                        OreSignatureTable.forAnalysis(dimension),
                        height -> NaturalGeology.host(setting, height),
                        setting,
                        precision,
                        false);
        if (!ScannerUpgrade.namesOres(analyserLevel)) {
            result = result.byCategory();
        }
        List<BoreholeLog.Analysis> analysis = new ArrayList<>();
        for (OreIdentification.Candidate candidate :
                result.top(BoreholeLog.MAXIMUM_ANALYSIS, 0.02D).candidates()) {
            analysis.add(
                    new BoreholeLog.Analysis(
                            candidate.oreId() == null ? "" : candidate.oreId(),
                            candidate.category() == null ? -1 : candidate.category().ordinal(),
                            (float) candidate.probability()));
        }
        return analysis;
    }

    /** The core analysis as identification candidates of the map's ore object. */
    private static List<SurveyTarget.Candidate> candidates(List<BoreholeLog.Analysis> analysis) {
        List<SurveyTarget.Candidate> result = new ArrayList<>();
        dev.world.geology.ore.OreTraits.Category[] categories =
                dev.world.geology.ore.OreTraits.Category.values();
        for (BoreholeLog.Analysis entry : analysis) {
            result.add(
                    new SurveyTarget.Candidate(
                            OreIdentification.Source.ORE,
                            entry.category() < 0
                                    ? null
                                    : categories[Math.min(entry.category(), categories.length - 1)],
                            entry.oreId(),
                            entry.probability()));
        }
        return result;
    }

    /**
     * Remaining units of the intersected ore within {@link #ESTIMATE_RADIUS} blocks of the hole,
     * over the intersected interval and two blocks above and below. Only loaded chunks count.
     */
    private int localEstimate(ServerLevel serverLevel) {
        long units = 0L;
        BlockPos hole = hole();
        int top = oreTopY + 2;
        int bottom = oreBottomY - 2;
        for (int dx = -ESTIMATE_RADIUS; dx <= ESTIMATE_RADIUS; dx++) {
            for (int dz = -ESTIMATE_RADIUS; dz <= ESTIMATE_RADIUS; dz++) {
                if (dx * dx + dz * dz > ESTIMATE_RADIUS * ESTIMATE_RADIUS) {
                    continue;
                }
                for (int y = bottom; y <= top; y++) {
                    BlockPos near = new BlockPos(hole.getX() + dx, y, hole.getZ() + dz);
                    if (!serverLevel.isLoaded(near)) {
                        continue;
                    }
                    BlockState state = serverLevel.getBlockState(near);
                    if (state.is(ModBlocks.ORE_DEPOSIT.get())
                            && ore.equals(OreDepositData.oreBlockIdAt(serverLevel, near))) {
                        units += OreDepositData.statsAt(serverLevel, near).remainingOre();
                    } else if (ore.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
                        units++;
                    }
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, units);
    }

    private void sync() {
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            boolean active = state == STATE_DRILLING;
            BlockState current = getBlockState();
            if (current.hasProperty(ExplorationDrillBlock.ACTIVE)
                    && current.getValue(ExplorationDrillBlock.ACTIVE) != active) {
                serverLevel.setBlock(
                        worldPosition, current.setValue(ExplorationDrillBlock.ACTIVE, active), 3);
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        state = tag.getInt("State");
        hole = tag.contains("Hole") ? BlockPos.of(tag.getLong("Hole")) : null;
        topY = tag.getInt("Top");
        cursorY = tag.getInt("Cursor");
        progress = tag.getInt("Progress");
        oreTopY = tag.getInt("OreTop");
        oreBottomY = tag.getInt("OreBottom");
        oreClosed = tag.getBoolean("OreClosed");
        industrial = tag.getBoolean("Industrial");
        ore = tag.contains("Ore") ? ResourceLocation.tryParse(tag.getString("Ore")) : null;
        intervals.clear();
        signals.clear();
        if (tag.contains("Log")) {
            BoreholeLog.CODEC
                    .parse(NbtOps.INSTANCE, tag.get("Log"))
                    .result()
                    .ifPresent(
                            log -> {
                                intervals.addAll(log.intervals());
                                signals.addAll(log.signals());
                            });
        }
        if (tag.contains("Energy")) {
            energy.deserializeNBT(registries, tag.get("Energy"));
        }
        lidar.load(tag.getCompound("Lidar"));
        ContainerHelper.loadAllItems(tag.getCompound("Output"), output.getItems(), registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("State", state);
        if (hole != null) {
            tag.putLong("Hole", hole.asLong());
        }
        tag.putInt("Top", topY);
        tag.putInt("Cursor", cursorY);
        tag.putInt("Progress", progress);
        tag.putInt("OreTop", oreTopY);
        tag.putInt("OreBottom", oreBottomY);
        tag.putBoolean("OreClosed", oreClosed);
        tag.putBoolean("Industrial", industrial);
        if (ore != null) {
            tag.putString("Ore", ore.toString());
        }
        BoreholeLog partial =
                new BoreholeLog(
                        hole(),
                        cursorY,
                        intervals,
                        Optional.empty(),
                        0,
                        0,
                        0,
                        false,
                        signals,
                        0,
                        List.of());
        BoreholeLog.CODEC
                .encodeStart(NbtOps.INSTANCE, partial)
                .result()
                .ifPresent(encoded -> tag.put("Log", encoded));
        tag.put("Energy", energy.serializeNBT(registries));
        tag.put("Lidar", dev.world.geology.lidar.LidarMapStorage.save(lidar.map()));
        CompoundTag items = new CompoundTag();
        ContainerHelper.saveAllItems(items, output.getItems(), registries);
        tag.put("Output", items);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        int current = scannerLevel();
        if (current > 0) {
            components.set(ModDataComponents.SCANNER_LEVEL.get(), current);
        }
        int analyzer = analyzerLevel();
        if (analyzer > 0) {
            components.set(ModDataComponents.ANALYZER_LEVEL.get(), analyzer);
        }
    }
}
