package dev.registry;

import dev.OresAndDrillsMod;
import dev.world.geology.CaveAnalyzer;
import dev.world.geology.ScannerUpgrade;
import dev.world.geology.exploration.BoreholeLog;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(
                    Registries.DATA_COMPONENT_TYPE, OresAndDrillsMod.MOD_ID);

    /** Identity of a seismic station's survey archive; carried by the item when it is broken. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> STATION_ID =
            COMPONENTS.registerComponentType(
                    "station_id",
                    builder ->
                            builder.persistent(UUIDUtil.CODEC)
                                    .networkSynchronized(UUIDUtil.STREAM_CODEC));

    /** Installed scanner upgrade level of a station or exploration drill, kept by its item. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>>
            SCANNER_LEVEL =
                    COMPONENTS.registerComponentType(
                            "scanner_level",
                            builder ->
                                    builder.persistent(
                                                    ExtraCodecs.intRange(
                                                            0, ScannerUpgrade.MAX_LEVEL))
                                            .networkSynchronized(ByteBufCodecs.VAR_INT));

    /** Installed cave analyser level of a station or exploration drill, kept by its item. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>>
            ANALYZER_LEVEL =
                    COMPONENTS.registerComponentType(
                            "analyzer_level",
                            builder ->
                                    builder.persistent(
                                                    ExtraCodecs.intRange(0, CaveAnalyzer.MAX_LEVEL))
                                            .networkSynchronized(ByteBufCodecs.VAR_INT));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BoreholeLog>>
            BOREHOLE_LOG =
                    COMPONENTS.registerComponentType(
                            "borehole_log",
                            builder ->
                                    builder.persistent(BoreholeLog.CODEC)
                                            .networkSynchronized(BoreholeLog.STREAM_CODEC));

    private ModDataComponents() {}
}
