package dev.world.geology.station;

import dev.config.SeismicSurveyConfig;
import dev.registry.ModBlocks;
import dev.registry.ModMenuTypes;
import dev.world.geology.GeologySavedData;
import dev.world.geology.SurveyMode;
import dev.world.geology.SurveyPreset;
import dev.world.geology.lidar.LidarMap;
import dev.world.geology.lidar.LidarSync;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * Station control menu. It has no slots: live figures travel as data slots, the 3D map and survey
 * reports as separate payloads requested by the screen. Anyone in reach may watch; only a player
 * who may build here may start or stop a survey.
 */
public final class SeismicStationMenu extends AbstractContainerMenu implements LidarSync.Source {
    /** Buttons {@code BUTTON_PRESET + i} start preset {@code i} of {@link SurveyPreset}. */
    public static final int BUTTON_PRESET = 0;

    public static final int BUTTON_CANCEL = 16;

    /** Clears the station's 3D map of this dimension; the survey archive stays. */
    public static final int BUTTON_CLEAR_MAP = 17;

    public static final int DATA_ENERGY = 0;
    public static final int DATA_CAPACITY = 1;
    public static final int DATA_STATE = 2;
    public static final int DATA_MODE = 3;
    public static final int DATA_PULSES = 4;
    public static final int DATA_REQUIRED = 5;
    public static final int DATA_COMPUTE = 6;
    public static final int DATA_GEOPHONES = 7;
    public static final int DATA_STATUS = 8;
    public static final int DATA_LAST_SURVEY = 9;
    public static final int DATA_REGIONAL_RADIUS = 10;
    public static final int DATA_DETAILED_RADIUS = 11;
    public static final int DATA_REGIONAL_COST = 12;
    public static final int DATA_DETAILED_COST = 13;
    public static final int DATA_LEVEL = 14;
    public static final int DATA_RADIUS = 15;
    public static final int DATA_REGIONAL_TICKS = 16;
    public static final int DATA_DETAILED_TICKS = 17;
    public static final int DATA_ANALYZER = 18;

    /** Distance the seismic pulse has travelled, in blocks. */
    public static final int DATA_FRONT = 19;

    /** Distance the cave analyser has travelled, in blocks. */
    public static final int DATA_CAVE_FRONT = 20;

    public static final int DATA_CAVE_RADIUS = 21;

    /** Overall survey progress, 0–1000. */
    public static final int DATA_PROGRESS = 22;

    /** Age in days after which a map measurement counts as outdated. */
    public static final int DATA_STALE_DAYS = 23;

    public static final int DATA_COUNT = 24;

    private static final Component TITLE =
            Component.translatable("block.ores_and_drills.seismic_survey_station");

    private final ContainerLevelAccess access;
    private final BlockPos pos;
    private final BlockPos site;
    private final ContainerData data;

    /** Client side. */
    public SeismicStationMenu(
            int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(
                containerId,
                ContainerLevelAccess.NULL,
                buffer.readBlockPos(),
                buffer.readBlockPos(),
                new SimpleContainerData(DATA_COUNT));
    }

    private SeismicStationMenu(
            int containerId,
            ContainerLevelAccess access,
            BlockPos pos,
            BlockPos site,
            ContainerData data) {
        super(ModMenuTypes.SEISMIC_STATION.get(), containerId);
        this.access = access;
        this.pos = pos.immutable();
        this.site = site.immutable();
        this.data = data;
        addDataSlots(data);
    }

    public static void open(ServerPlayer player, SeismicStationBlockEntity station) {
        BlockPos origin = station.getBlockPos();
        BlockPos site = station.currentSite();
        ServerLevel level = (ServerLevel) station.getLevel();
        player.openMenu(
                new SimpleMenuProvider(
                        (id, inventory, menuPlayer) ->
                                new SeismicStationMenu(
                                        id,
                                        ContainerLevelAccess.create(level, origin),
                                        origin,
                                        site,
                                        new StationData(station)),
                        TITLE),
                buffer -> {
                    buffer.writeBlockPos(origin);
                    buffer.writeBlockPos(site);
                });
        SeismicStationViewers.sendSurveyList(player, station);
    }

    public BlockPos stationPos() {
        return pos;
    }

    /**
     * Where the station measured from when the screen opened; differs from {@link #stationPos()}
     * for a station on a Sable body, whose block lies in a plot far from the surveyed ground.
     */
    public BlockPos site() {
        return site;
    }

    public int value(int index) {
        return data.get(index);
    }

    @Override
    public BlockPos lidarOwner() {
        return pos;
    }

    @Override
    public LidarMap lidarMap(ServerPlayer player) {
        SeismicStationBlockEntity station = station(player);
        return station == null || !stillValid(player) ? null : station.lidarMap();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        SeismicStationBlockEntity station = station(player);
        if (station == null || player.isSpectator() || !player.mayBuild()) {
            return false;
        }
        if (id == BUTTON_CANCEL) {
            station.cancelSurvey();
            return true;
        }
        if (id == BUTTON_CLEAR_MAP) {
            return station.clearLidarMap();
        }
        if (id >= BUTTON_PRESET && id < BUTTON_PRESET + SurveyPreset.COUNT) {
            station.startSurvey(id - BUTTON_PRESET);
            return true;
        }
        return false;
    }

    /** The station this menu controls, on the server; {@code null} when it no longer exists. */
    public SeismicStationBlockEntity station(Player player) {
        return access.evaluate(
                        (level, origin) ->
                                level.getBlockEntity(origin)
                                                instanceof SeismicStationBlockEntity station
                                        ? station
                                        : null)
                .orElse(null);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return Boolean.TRUE.equals(
                access.evaluate(
                        (level, origin) ->
                                ModBlocks.SEISMIC_STATION_STRUCTURE.isMainBlock(
                                                level.getBlockState(origin))
                                        && player.distanceToSqr(
                                                        origin.getX() + 0.5D,
                                                        origin.getY() + 1.5D,
                                                        origin.getZ() + 0.5D)
                                                <= 10.0D * 10.0D,
                        true));
    }

    private static final class StationData implements ContainerData {
        private final SeismicStationBlockEntity station;
        private int geophoneCache;
        private long geophoneCacheTime = Long.MIN_VALUE;

        private StationData(SeismicStationBlockEntity station) {
            this.station = station;
        }

        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_ENERGY -> station.energyStored();
                case DATA_CAPACITY -> station.energyCapacity();
                case DATA_STATE -> station.state().ordinal();
                case DATA_MODE -> station.mode().ordinal();
                case DATA_PULSES -> station.pulseTicks();
                case DATA_REQUIRED -> station.requiredTicks();
                case DATA_COMPUTE -> station.computeProgress();
                case DATA_GEOPHONES -> geophones();
                case DATA_STATUS -> station.status();
                case DATA_LAST_SURVEY -> station.lastSurveyId();
                case DATA_REGIONAL_RADIUS -> station.regionalRadius();
                case DATA_DETAILED_RADIUS -> station.detailedRadius();
                case DATA_REGIONAL_COST -> SeismicSurveyConfig.energyPerTick(SurveyMode.REGIONAL);
                case DATA_DETAILED_COST -> SeismicSurveyConfig.energyPerTick(SurveyMode.DETAILED);
                case DATA_LEVEL -> station.scannerLevel();
                case DATA_RADIUS -> station.radius();
                case DATA_REGIONAL_TICKS -> SeismicSurveyConfig.durationTicks(SurveyMode.REGIONAL);
                case DATA_DETAILED_TICKS -> SeismicSurveyConfig.durationTicks(SurveyMode.DETAILED);
                case DATA_ANALYZER -> station.analyzerLevel();
                case DATA_FRONT -> (int) Math.round(station.pulseFront());
                case DATA_CAVE_FRONT -> (int) Math.round(station.caveFront());
                case DATA_CAVE_RADIUS -> station.caveRadius();
                case DATA_PROGRESS -> station.surveyProgress();
                case DATA_STALE_DAYS -> SeismicSurveyConfig.LIDAR_STALE_DAYS.get();
                default -> 0;
            };
        }

        /** Geophones within the regional radius, refreshed once a second. */
        private int geophones() {
            if (!(station.getLevel() instanceof ServerLevel level)) {
                return 0;
            }
            long now = level.getGameTime();
            if (now - geophoneCacheTime >= 20L) {
                geophoneCacheTime = now;
                geophoneCache =
                        GeologySavedData.get(level)
                                .geophonesWithin(station.currentSite(), station.regionalRadius())
                                .size();
            }
            return geophoneCache;
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    }
}
