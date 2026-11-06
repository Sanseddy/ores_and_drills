package dev.world.geology.station;

import dev.OresAndDrillsMod;
import dev.config.SeismicSurveyConfig;
import dev.registry.ModBlockEntities;
import dev.registry.ModBlocks;
import dev.registry.ModDataComponents;
import dev.registry.ModSounds;
import dev.world.block.entity.drill.EnergyBuffer;
import dev.world.geology.CaveAnalyzer;
import dev.world.geology.GeologySavedData;
import dev.world.geology.NaturalGeology;
import dev.world.geology.RockCatalog;
import dev.world.geology.RockPhysics;
import dev.world.geology.RockProperties;
import dev.world.geology.ScannerUpgrade;
import dev.world.geology.SurveyArchive;
import dev.world.geology.SurveyMode;
import dev.world.geology.SurveyPreset;
import dev.world.geology.SurveyRecord;
import dev.world.geology.SurveySite;
import dev.world.geology.UpgradeModuleItem;
import dev.world.geology.lidar.LidarExtraction;
import dev.world.geology.lidar.LidarMap;
import dev.world.geology.lidar.LidarSweep;
import dev.world.geology.lidar.OrePriors;
import dev.world.geology.lidar.StationLidarData;
import dev.world.geology.ore.OreSignatureTable;
import dev.world.geology.survey.SeismicSurveyService;
import dev.world.geology.survey.SurveyJob;
import dev.world.geology.survey.SurveyRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Main block of the seismic survey station: energy buffer, survey state machine and archive
 * identity. The survey computation itself belongs to {@link SeismicSurveyService}; this block
 * entity paces the impacts, pays for them, sweeps the result into the station's 3D map as the pulse
 * front travels outwards, and stores the result.
 *
 * <p>The seismic front reaches the survey radius when the last impact lands. The cave analyser's
 * front moves at the analyser's own speed, so a slow analyser keeps the station processing after
 * the impacts end. Nothing is folded into the map before the computation behind it is finished, and
 * then at most {@link #CATCH_UP_TICKS} worth of radius per tick, so a late result still spreads out
 * instead of appearing at once.
 */
public final class SeismicStationBlockEntity extends BlockEntity
        implements GeoBlockEntity, UpgradeModuleItem.Upgradeable {
    public static final int START_TICKS = 40;
    public static final int FINISH_TICKS = 30;

    /** One impact cycle; matches the length of the model's "pulse" animation. */
    public static final int PULSE_PERIOD = 30;

    /** Tick of the cycle at which the striker lands in the animation. */
    public static final int IMPACT_TICK = 26;

    private static final int WAVE_TICKS = 10;

    /** Least number of ticks a front needs to sweep its whole radius once data are available. */
    public static final int CATCH_UP_TICKS = 60;

    public static final int STATUS_NONE = 0;
    public static final int STATUS_FINISHED = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_CANCELLED = 3;
    public static final int STATUS_BUSY = 4;
    public static final int STATUS_MOVED = 5;

    /**
     * How far a station carried by a Sable body may drift sideways while it strikes; beyond that
     * the shots no longer share one source point.
     */
    public static final double MOVE_TOLERANCE = 3.0D;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation START = RawAnimation.begin().thenPlayAndHold("start");
    private static final RawAnimation PULSE = RawAnimation.begin().thenLoop("pulse");
    private static final RawAnimation PROCESSING = RawAnimation.begin().thenLoop("processing");
    private static final RawAnimation FINISH = RawAnimation.begin().thenPlayAndHold("finish");
    private static final RawAnimation NO_POWER = RawAnimation.begin().thenPlayAndHold("no_power");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final EnergyBuffer energy;
    private UUID stationId;
    private StationState state = StationState.IDLE;
    private SurveyMode mode = SurveyMode.REGIONAL;
    private int pulseTicks;
    private int requiredTicks;
    private int phaseTicks;
    private int energyPerTick;
    private int radius;
    private double environment = 1.0D;
    private boolean wellTie;
    private int scannerLevel;
    private int analyzerLevel;
    private double caveFront;
    private double oreRevealed;
    private double caveRevealed;
    private LidarSweep sweep;
    private List<BlockPos> geophones = List.of();
    private BlockPos site;
    private int status;
    private int lastSurveyId;
    private int waveTicks;

    public SeismicStationBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.SEISMIC_STATION.get(), pos, blockState);
        int capacity = configuredCapacity();
        this.energy = new EnergyBuffer(capacity, capacity, this::setChanged);
    }

    private static int configuredCapacity() {
        try {
            return SeismicSurveyConfig.STATION_ENERGY_CAPACITY.get();
        } catch (IllegalStateException notLoaded) {
            return 120_000;
        }
    }

    public UUID stationId() {
        if (stationId == null) {
            stationId = UUID.randomUUID();
            setChanged();
        }
        return stationId;
    }

    public StationState state() {
        return state;
    }

    public SurveyMode mode() {
        return mode;
    }

    public IEnergyStorage energyStorage() {
        return energy;
    }

    public int energyStored() {
        return energy.getEnergyStored();
    }

    public int energyCapacity() {
        return energy.getMaxEnergyStored();
    }

    public int pulseTicks() {
        return pulseTicks;
    }

    public int requiredTicks() {
        return requiredTicks;
    }

    public int status() {
        return status;
    }

    public int lastSurveyId() {
        return lastSurveyId;
    }

    public List<BlockPos> geophones() {
        return geophones;
    }

    /**
     * Where the running or last survey measured from: the station itself, or for a station on a
     * Sable body the ground under it when the survey started.
     */
    public BlockPos site() {
        return site == null ? worldPosition : site;
    }

    /** Where the station measures from now: its survey's site while one runs. */
    public BlockPos currentSite() {
        if (state.surveying() || state == StationState.FINISHING || level == null) {
            return site();
        }
        return SurveySite.of(level, worldPosition);
    }

    @Override
    public int scannerLevel() {
        return scannerLevel;
    }

    @Override
    public boolean raiseScannerLevel() {
        if (scannerLevel >= ScannerUpgrade.MAX_LEVEL) {
            return false;
        }
        scannerLevel++;
        changed(true);
        return true;
    }

    @Override
    public int analyzerLevel() {
        return analyzerLevel;
    }

    @Override
    public boolean raiseAnalyzerLevel() {
        if (analyzerLevel >= CaveAnalyzer.MAX_LEVEL) {
            return false;
        }
        analyzerLevel++;
        changed(true);
        return true;
    }

    /** Radius of the cave map of the running or last survey, 0 without an analyser. */
    public int caveRadius() {
        return CaveAnalyzer.radius(analyzerLevel, radius);
    }

    /** How far the seismic pulse has travelled, in blocks. */
    public double pulseFront() {
        if (state == StationState.PROCESSING || state == StationState.FINISHING) {
            return radius;
        }
        if (!state.surveying() || requiredTicks <= 0) {
            return 0.0D;
        }
        return radius * Math.min(1.0D, pulseTicks / (double) requiredTicks);
    }

    /** How far the cave analyser has travelled, in blocks. */
    public double caveFront() {
        return state.surveying() || state == StationState.FINISHING ? caveFront : 0.0D;
    }

    /**
     * Overall progress of the survey, 0–1000: computation, the pulse and the analyser, and the part
     * of the map already revealed.
     */
    public int surveyProgress() {
        if (state == StationState.FINISHING) {
            return 1000;
        }
        if (!state.surveying()) {
            return 0;
        }
        double compute = computeProgress() / 1000.0D;
        double pulse = radius <= 0 ? 1.0D : Math.min(1.0D, oreRevealed / radius);
        int caves = caveRadius();
        double analyser = caves <= 0 ? 1.0D : Math.min(1.0D, caveRevealed / caves);
        return (int) Math.round(1000.0D * (0.3D * compute + 0.45D * pulse + 0.25D * analyser));
    }

    /** The station's 3D map of the dimension it stands in. */
    public LidarMap lidarMap() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return StationLidarData.get(serverLevel.getServer(), stationId())
                .map(serverLevel.dimension().location());
    }

    /**
     * Forgets the 3D map of the station's dimension; viewers resynchronise from an empty map. A
     * running survey is still writing into the map, so it is refused until the survey ends.
     */
    public boolean clearLidarMap() {
        LidarMap map = lidarMap();
        if (map == null || state.surveying()) {
            return false;
        }
        map.clear();
        return true;
    }

    /** Largest regional radius at the installed level. */
    public int regionalRadius() {
        return SeismicSurveyConfig.radius(SurveyMode.REGIONAL, scannerLevel);
    }

    /** Largest detailed radius at the installed level. */
    public int detailedRadius() {
        return SeismicSurveyConfig.radius(SurveyMode.DETAILED, scannerLevel);
    }

    /** Radius of the running or last survey. */
    public int radius() {
        return radius;
    }

    public SurveyPreset preset(int index) {
        return SurveyPreset.at(index, detailedRadius(), regionalRadius());
    }

    /** Computation progress of the running survey, 0–1000. */
    public int computeProgress() {
        if (!(level instanceof ServerLevel serverLevel) || !state.surveying()) {
            return state == StationState.FINISHING ? 1000 : 0;
        }
        SurveyJob job = SeismicSurveyService.find(serverLevel, stationId());
        return job == null ? 0 : (int) Math.round(job.progress() * 1000.0D);
    }

    /** Starts the survey of preset {@code index}; false when one is already running. */
    public boolean startSurvey(int index) {
        SurveyPreset preset = preset(index);
        return startSurvey(preset.mode(), preset.radius());
    }

    /** Pulsing time of a survey of {@code requested} at {@code requestedRadius}. */
    public int durationTicks(SurveyMode requested, int requestedRadius) {
        return SurveyPreset.durationTicks(
                SeismicSurveyConfig.durationTicks(requested),
                requestedRadius,
                SeismicSurveyConfig.radius(requested, scannerLevel),
                scannerLevel);
    }

    /** Starts a survey of {@code requested} at its largest radius. */
    public boolean startSurvey(SurveyMode requested) {
        return startSurvey(requested, SeismicSurveyConfig.radius(requested, scannerLevel));
    }

    private boolean startSurvey(SurveyMode requested, int requestedRadius) {
        if (!(level instanceof ServerLevel serverLevel) || state.surveying()) {
            status = STATUS_BUSY;
            return false;
        }
        mode = requested;
        radius = requestedRadius;
        site = SurveySite.of(serverLevel, worldPosition);
        geophones = linkedGeophones(serverLevel, site, radius);
        environment = environmentFactor(serverLevel, site);
        wellTie =
                requested == SurveyMode.DETAILED
                        && !GeologySavedData.get(serverLevel)
                                .boreholesWithin(site, radius)
                                .isEmpty();
        requiredTicks = durationTicks(requested, requestedRadius);
        energyPerTick = SeismicSurveyConfig.energyPerTick(requested);
        pulseTicks = 0;
        phaseTicks = 0;
        caveFront = 0.0D;
        oreRevealed = 0.0D;
        caveRevealed = 0.0D;
        sweep = null;
        status = STATUS_NONE;
        state = StationState.STARTING;
        SeismicSurveyService.submit(serverLevel, request(serverLevel));
        serverLevel.playSound(
                null,
                worldPosition,
                ModSounds.SEISMIC_STATION_START.get(),
                SoundSource.BLOCKS,
                1.0F,
                1.0F);
        changed(true);
        return true;
    }

    public void cancelSurvey() {
        cancelSurvey(STATUS_CANCELLED);
    }

    private void cancelSurvey(int reason) {
        if (!(level instanceof ServerLevel serverLevel) || !state.surveying()) {
            return;
        }
        SeismicSurveyService.release(serverLevel, stationId());
        sweep = null;
        status = reason;
        state = StationState.FINISHING;
        phaseTicks = 0;
        changed(true);
    }

    private SurveyRequest request(ServerLevel serverLevel) {
        return new SurveyRequest(
                stationId(),
                serverLevel.dimension().location(),
                serverLevel.getSeed(),
                mode,
                site(),
                radius,
                geophones,
                environment,
                wellTie,
                scannerLevel,
                analyzerLevel);
    }

    /**
     * Registered geophones inside the survey radius, nearest first. Geophones in loaded chunks are
     * checked against the world and dropped from the registry if gone; ones in unloaded chunks are
     * trusted, since only their own block removal unregisters them.
     */
    public static List<BlockPos> linkedGeophones(ServerLevel level, BlockPos station, int radius) {
        GeologySavedData data = GeologySavedData.get(level);
        List<BlockPos> linked = new ArrayList<>();
        for (BlockPos geophone : data.geophonesWithin(station, radius)) {
            if (level.isLoaded(geophone)
                    && !level.getBlockState(geophone).is(ModBlocks.GEOPHONE.get())) {
                data.removeGeophone(geophone);
                continue;
            }
            linked.add(geophone);
        }
        linked.sort(
                Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(station))
                        .thenComparing(pos -> pos));
        int limit = SeismicSurveyConfig.MAX_GEOPHONES.get();
        return List.copyOf(linked.subList(0, Math.min(limit, linked.size())));
    }

    /**
     * Noise multiplier of the site: rain and thunder add ambient noise; soft ground under the
     * striker couples less energy into the rock, which costs signal like extra noise does.
     */
    public static double environmentFactor(ServerLevel level, BlockPos station) {
        double weather =
                level.isThundering() ? 1.6D : level.isRainingAt(station.above(3)) ? 1.25D : 1.0D;
        RockProperties ground = RockPhysics.forBlock(level.getBlockState(station.below()));
        double coupling =
                Math.sqrt(RockCatalog.STONE.impedance() / Math.max(1.0D, ground.impedance()));
        return weather * Math.max(0.85D, Math.min(1.8D, coupling));
    }

    public static void serverTick(
            Level level, BlockPos pos, BlockState blockState, SeismicStationBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (level.getGameTime() % 5L == 0L
                && !ModBlocks.SEISMIC_STATION_STRUCTURE.isComplete(level, pos)) {
            level.destroyBlock(pos, true);
            return;
        }
        be.tickWave(serverLevel);
        // Once the shots are recorded the computation no longer depends on where the station is.
        if ((be.state == StationState.STARTING
                        || be.state == StationState.PULSING
                        || be.state == StationState.NO_POWER)
                && SurveySite.moved(level, pos, be.site(), MOVE_TOLERANCE)) {
            be.cancelSurvey(STATUS_MOVED);
            return;
        }
        switch (be.state) {
            case IDLE -> {}
            case STARTING -> be.tickStarting(serverLevel);
            case PULSING -> be.tickPulsing(serverLevel);
            case PROCESSING -> be.tickProcessing(serverLevel);
            case NO_POWER -> be.tickNoPower(serverLevel);
            case FINISHING -> be.tickFinishing();
        }
    }

    private void tickStarting(ServerLevel serverLevel) {
        if (!pay()) {
            enterNoPower();
            return;
        }
        ensureJob(serverLevel);
        if (++phaseTicks >= START_TICKS) {
            state = StationState.PULSING;
            phaseTicks = 0;
            changed(true);
        } else {
            setChanged();
        }
    }

    private void tickPulsing(ServerLevel serverLevel) {
        SurveyJob job = ensureJob(serverLevel);
        if (pulseTicks >= requiredTicks) {
            state = StationState.PROCESSING;
            changed(true);
            tickProcessing(serverLevel);
            return;
        }
        if (!pay()) {
            enterNoPower();
            return;
        }
        pulseTicks++;
        if (pulseTicks % PULSE_PERIOD == IMPACT_TICK) {
            impact(serverLevel);
        }
        setChanged();
        advanceFronts(serverLevel, job);
    }

    private void tickProcessing(ServerLevel serverLevel) {
        SurveyJob job = ensureJob(serverLevel);
        advanceFronts(serverLevel, job);
    }

    /**
     * Moves the analyser's front, folds what both fronts passed into the map, and finishes the
     * survey once the pulses ended, the computation is done and everything is revealed.
     */
    private void advanceFronts(ServerLevel serverLevel, SurveyJob job) {
        int caves = caveRadius();
        if (caves > 0 && requiredTicks > 0) {
            caveFront =
                    Math.min(
                            caves,
                            caveFront
                                    + caves
                                            * CaveAnalyzer.sweepSpeed(analyzerLevel)
                                            / requiredTicks);
        }
        if (job.failed()) {
            finish(serverLevel, job);
            return;
        }
        if (!job.isDone() || job.result() == null) {
            return;
        }
        if (sweep == null) {
            sweep = newSweep(serverLevel, job);
        }
        oreRevealed =
                Math.min(
                        pulseFront(),
                        oreRevealed + Math.max(1.0D, radius / (double) CATCH_UP_TICKS));
        double sweptCaves = sweep.context().caveRadius();
        caveRevealed =
                Math.min(
                        Math.min(caveFront, sweptCaves),
                        caveRevealed + Math.max(1.0D, sweptCaves / CATCH_UP_TICKS));
        LidarMap map = lidarMap();
        boolean done =
                map != null
                        && sweep.advance(
                                map,
                                oreRevealed >= radius ? Double.MAX_VALUE : oreRevealed,
                                caveRevealed >= sweptCaves ? Double.MAX_VALUE : caveRevealed);
        if (done && pulseTicks >= requiredTicks) {
            finish(serverLevel, job);
        }
    }

    private LidarSweep newSweep(ServerLevel serverLevel, SurveyJob job) {
        SurveyRequest request = job.request();
        SurveyJob.Result result = job.result();
        SurveyJob.Lidar lidar = result.lidar();
        long time = serverLevel.getGameTime();
        LidarSweep.Context context =
                new LidarSweep.Context(
                        site().getX() + 0.5D,
                        site().getZ() + 0.5D,
                        site().getY(),
                        result.minY(),
                        request.radius(),
                        lidar.caveRadius(),
                        request.level(),
                        request.analyzer(),
                        lidar.cell(),
                        request.configHash(),
                        time,
                        SeismicSurveyConfig.staleTicks(),
                        OrePriors.of(
                                OreSignatureTable.forAnalysis(serverLevel.dimension().location())));
        return new LidarSweep(
                lidarMap(),
                context,
                lidar.anomalies(),
                LidarExtraction.targets(result.targets(), time),
                lidar.caveSamples(),
                lidar.caves());
    }

    private void tickNoPower(ServerLevel serverLevel) {
        ensureJob(serverLevel);
        if (energy.hasEnergy(energyPerTick)) {
            state =
                    phaseTicks < START_TICKS && pulseTicks == 0
                            ? StationState.STARTING
                            : StationState.PULSING;
            changed(true);
        }
    }

    private void tickFinishing() {
        if (++phaseTicks >= FINISH_TICKS) {
            state = StationState.IDLE;
            phaseTicks = 0;
            changed(true);
        }
    }

    private boolean pay() {
        if (!energy.hasEnergy(energyPerTick)) {
            return false;
        }
        energy.consume(energyPerTick);
        return true;
    }

    private void enterNoPower() {
        state = StationState.NO_POWER;
        changed(true);
    }

    /**
     * The running survey; after a restart the identical request is submitted again from the saved
     * configuration and, being deterministic, produces the same result.
     */
    private SurveyJob ensureJob(ServerLevel serverLevel) {
        SurveyJob job = SeismicSurveyService.find(serverLevel, stationId());
        if (job == null) {
            job = SeismicSurveyService.submit(serverLevel, request(serverLevel));
        }
        return job;
    }

    private void finish(ServerLevel serverLevel, SurveyJob job) {
        SeismicSurveyService.release(serverLevel, stationId());
        sweep = null;
        if (job.failed() || job.result() == null) {
            OresAndDrillsMod.LOGGER.warn(
                    "Seismic survey at {} failed", worldPosition, job.failure());
            status = STATUS_FAILED;
        } else {
            SurveyRequest request = job.request();
            double[] gx = new double[request.geophones().size()];
            double[] gz = new double[gx.length];
            for (int i = 0; i < gx.length; i++) {
                gx[i] = request.geophones().get(i).getX() + 0.5D;
                gz[i] = request.geophones().get(i).getZ() + 0.5D;
            }
            SurveyJob.Result result = job.result();
            SurveyRecord record =
                    new SurveyRecord(
                            0,
                            serverLevel.dimension().location(),
                            serverLevel.getGameTime(),
                            request.configHash(),
                            request.mode(),
                            request.station().getX() + 0.5D,
                            request.station().getY(),
                            request.station().getZ() + 0.5D,
                            request.radius(),
                            result.minY(),
                            NaturalGeology.Setting.forDimension(
                                    serverLevel.dimension().location().toString()),
                            request.environment(),
                            request.wellTie(),
                            request.noiseSeed(),
                            gx,
                            gz,
                            request.level(),
                            result.map(),
                            result.targets());
            SurveyRecord stored =
                    SurveyArchive.get(serverLevel.getServer(), stationId())
                            .add(record, SeismicSurveyConfig.SAVED_SURVEYS_PER_STATION.get());
            lastSurveyId = stored.id();
            status = STATUS_FINISHED;
            SeismicStationViewers.surveyFinished(serverLevel, this);
        }
        serverLevel.playSound(
                null,
                worldPosition,
                ModSounds.SEISMIC_STATION_FINISH.get(),
                SoundSource.BLOCKS,
                1.0F,
                1.0F);
        state = StationState.FINISHING;
        phaseTicks = 0;
        changed(true);
    }

    private void impact(ServerLevel serverLevel) {
        serverLevel.playSound(
                null,
                worldPosition,
                ModSounds.SEISMIC_STATION_THUMP.get(),
                SoundSource.BLOCKS,
                1.5F,
                0.8F + serverLevel.random.nextFloat() * 0.1F);
        serverLevel.sendParticles(
                ParticleTypes.POOF,
                worldPosition.getX() + 0.5D,
                worldPosition.getY() + 0.2D,
                worldPosition.getZ() + 0.5D,
                8,
                0.6D,
                0.05D,
                0.6D,
                0.02D);
        waveTicks = WAVE_TICKS;
    }

    /** An expanding ring of ground debris after each impact: the surface wave. */
    private void tickWave(ServerLevel serverLevel) {
        if (waveTicks <= 0) {
            return;
        }
        int age = WAVE_TICKS - waveTicks;
        waveTicks--;
        double ring = 2.0D + age * 0.9D;
        BlockState ground = serverLevel.getBlockState(worldPosition.below());
        if (ground.isAir()) {
            return;
        }
        BlockParticleOption particle = new BlockParticleOption(ParticleTypes.BLOCK, ground);
        int points = 10 + age * 2;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D * i / points;
            serverLevel.sendParticles(
                    particle,
                    worldPosition.getX() + 0.5D + Math.cos(angle) * ring,
                    worldPosition.getY() + 0.05D,
                    worldPosition.getZ() + 0.5D + Math.sin(angle) * ring,
                    1,
                    0.05D,
                    0.02D,
                    0.05D,
                    0.05D);
        }
    }

    private void changed(boolean sync) {
        setChanged();
        if (sync && level instanceof ServerLevel serverLevel) {
            serverLevel.getChunkSource().blockChanged(worldPosition);
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(
                new AnimationController<>(
                        this,
                        "station",
                        4,
                        animation ->
                                animation.setAndContinue(
                                        switch (state) {
                                            case IDLE -> IDLE;
                                            case STARTING -> START;
                                            case PULSING -> PULSE;
                                            case PROCESSING -> PROCESSING;
                                            case FINISHING -> FINISH;
                                            case NO_POWER -> NO_POWER;
                                        })));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("StationId")) {
            stationId = tag.getUUID("StationId");
        }
        state = StationState.byId(tag.getInt("State"));
        mode = SurveyMode.byId(tag.getInt("Mode"));
        pulseTicks = tag.getInt("PulseTicks");
        requiredTicks = tag.getInt("RequiredTicks");
        phaseTicks = tag.getInt("PhaseTicks");
        energyPerTick = tag.getInt("EnergyPerTick");
        radius = tag.getInt("Radius");
        environment = tag.contains("Environment") ? tag.getDouble("Environment") : 1.0D;
        wellTie = tag.getBoolean("WellTie");
        // Stations from before multi-level upgrades had one range module: level 1.
        scannerLevel =
                ScannerUpgrade.clamp(
                        tag.contains("ScannerLevel")
                                ? tag.getInt("ScannerLevel")
                                : tag.getBoolean("RangeModule") ? 1 : 0);
        analyzerLevel = CaveAnalyzer.clamp(tag.getInt("AnalyzerLevel"));
        caveFront = tag.getDouble("CaveFront");
        status = tag.getInt("Status");
        lastSurveyId = tag.getInt("LastSurvey");
        site = tag.contains("Site") ? BlockPos.of(tag.getLong("Site")) : null;
        List<BlockPos> loaded = new ArrayList<>();
        for (long packed : tag.getLongArray("Geophones")) {
            loaded.add(BlockPos.of(packed));
        }
        geophones = List.copyOf(loaded);
        if (tag.contains("Energy")) {
            energy.deserializeNBT(registries, tag.get("Energy"));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("StationId", stationId());
        tag.putInt("State", state.ordinal());
        tag.putInt("Mode", mode.ordinal());
        tag.putInt("PulseTicks", pulseTicks);
        tag.putInt("RequiredTicks", requiredTicks);
        tag.putInt("PhaseTicks", phaseTicks);
        tag.putInt("EnergyPerTick", energyPerTick);
        tag.putInt("Radius", radius);
        tag.putDouble("Environment", environment);
        tag.putBoolean("WellTie", wellTie);
        tag.putInt("ScannerLevel", scannerLevel);
        tag.putInt("AnalyzerLevel", analyzerLevel);
        tag.putDouble("CaveFront", caveFront);
        tag.putInt("Status", status);
        tag.putInt("LastSurvey", lastSurveyId);
        if (site != null) {
            tag.putLong("Site", site.asLong());
        }
        tag.putLongArray("Geophones", geophones.stream().mapToLong(BlockPos::asLong).toArray());
        tag.put("Energy", energy.serializeNBT(registries));
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput components) {
        super.applyImplicitComponents(components);
        UUID carried = components.get(ModDataComponents.STATION_ID);
        if (carried != null) {
            stationId = carried;
        }
        Integer carriedLevel = components.get(ModDataComponents.SCANNER_LEVEL);
        if (carriedLevel != null) {
            scannerLevel = ScannerUpgrade.clamp(carriedLevel);
        }
        Integer carriedAnalyzer = components.get(ModDataComponents.ANALYZER_LEVEL);
        if (carriedAnalyzer != null) {
            analyzerLevel = CaveAnalyzer.clamp(carriedAnalyzer);
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(ModDataComponents.STATION_ID.get(), stationId());
        if (scannerLevel > 0) {
            components.set(ModDataComponents.SCANNER_LEVEL.get(), scannerLevel);
        }
        if (analyzerLevel > 0) {
            components.set(ModDataComponents.ANALYZER_LEVEL.get(), analyzerLevel);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        tag.remove("StationId");
        tag.remove("ScannerLevel");
        tag.remove("AnalyzerLevel");
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("State", state.ordinal());
        tag.putInt("Mode", mode.ordinal());
        tag.putInt("ScannerLevel", scannerLevel);
        tag.putInt("AnalyzerLevel", analyzerLevel);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        state = StationState.byId(tag.getInt("State"));
        mode = SurveyMode.byId(tag.getInt("Mode"));
        scannerLevel = ScannerUpgrade.clamp(tag.getInt("ScannerLevel"));
        analyzerLevel = CaveAnalyzer.clamp(tag.getInt("AnalyzerLevel"));
    }

    @Override
    public void onDataPacket(
            net.minecraft.network.Connection connection,
            ClientboundBlockEntityDataPacket packet,
            HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }
}
