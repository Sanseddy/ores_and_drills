package dev.network;

import dev.client.map.OreMapMarkers;
import dev.registry.ModBlocks;
import dev.world.inventory.AbstractEnergyDrillMenu;
import dev.world.level.levelgen.ConfirmedDeposit;
import dev.world.level.levelgen.ConfirmedDepositIndex;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositFeature;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreDepositStonePalette;
import dev.world.level.levelgen.OreDepositTier;
import dev.world.level.levelgen.OreGenerationSummary;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ModNetworking {
    private static final int DISCOVERY_MAX_VERTICAL_DISTANCE = 12;

    /**
     * Slightly above the client's sight rays so a player walking forward is never rejected at the
     * limit.
     */
    private static final double DISCOVERY_SIGHT_DISTANCE = 68.0D;

    /**
     * Wider than the client's ray fan, leaving room for the server's view direction trailing the
     * client.
     */
    private static final double DISCOVERY_MIN_VIEW_COSINE = Math.cos(Math.toRadians(70.0D));

    private static final DiscoveryThrottle DISCOVERY_THROTTLE = new DiscoveryThrottle();

    private ModNetworking() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("10");
        registrar
                .playToServer(
                        FluidTankClickPayload.TYPE,
                        FluidTankClickPayload.STREAM_CODEC,
                        (payload, context) ->
                                context.enqueueWork(
                                        () -> {
                                            if (context.player()
                                                            instanceof ServerPlayer serverPlayer
                                                    && serverPlayer.containerMenu.containerId
                                                            == payload.containerId()
                                                    && serverPlayer.containerMenu
                                                            instanceof
                                                            AbstractEnergyDrillMenu drillMenu) {
                                                drillMenu.handleFluidTankClick(serverPlayer);
                                            }
                                        }))
                .playToServer(
                        OreDepositDiscoveryRequestPayload.TYPE,
                        OreDepositDiscoveryRequestPayload.STREAM_CODEC,
                        (payload, context) ->
                                context.enqueueWork(
                                        () -> {
                                            if (context.player() instanceof ServerPlayer player) {
                                                discoverDeposit(player, payload.pos());
                                            }
                                        }))
                .playToClient(
                        OreMapWorldIdPayload.TYPE,
                        OreMapWorldIdPayload.STREAM_CODEC,
                        (payload, context) ->
                                context.enqueueWork(
                                        () -> OreMapMarkers.setWorldId(payload.worldId())))
                .playToClient(
                        OreDepositPalettePayload.TYPE,
                        OreDepositPalettePayload.STREAM_CODEC,
                        (payload, context) ->
                                context.enqueueWork(
                                        () -> {
                                            OreDepositStonePalette.applyClientPalette(
                                                    payload.bases());
                                            OreDepositOrePalette.applyClientPalette(
                                                    payload.ores(),
                                                    payload.drops(),
                                                    payload.vanillaLikeGeneration());
                                        }))
                .playToClient(
                        OreGenerationSummaryPayload.TYPE,
                        OreGenerationSummaryPayload.STREAM_CODEC,
                        (payload, context) ->
                                context.enqueueWork(
                                        () ->
                                                OreGenerationSummary.applyClient(
                                                        payload.entries(),
                                                        payload.replacedFeatures())))
                .playToClient(
                        OreDepositRemainingPayload.TYPE,
                        OreDepositRemainingPayload.STREAM_CODEC,
                        (payload, context) ->
                                context.enqueueWork(
                                        () -> {
                                            OreDepositData.applyClientEntry(
                                                    context.player().level(),
                                                    payload.pos(),
                                                    payload.baseIndex(),
                                                    payload.oreIndex(),
                                                    payload.remainingOre(),
                                                    payload.initialOre(),
                                                    payload.initialRichness(),
                                                    payload.richnessReferenceAmount(),
                                                    payload.tier());
                                            OreMapMarkers.markColumnDirty(
                                                    context.player().level().dimension().location(),
                                                    payload.pos());
                                        }))
                .playToClient(
                        DiscoveredOreDepositPayload.TYPE,
                        DiscoveredOreDepositPayload.STREAM_CODEC,
                        (payload, context) ->
                                context.enqueueWork(() -> OreMapMarkers.discover(payload)));
    }

    private static void discoverDeposit(ServerPlayer player, BlockPos pos) {
        var level = player.serverLevel();
        if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())) {
            return;
        }
        if (!isStandingOn(player, pos) && !canSee(player, pos)) {
            return;
        }

        int tierIndex = OreDepositData.tierAt(level, pos);
        OreDepositTier tier = OreDepositTier.fromIndex(tierIndex).orElse(null);
        if (tier == null || tier.index() < OreDepositTier.MEDIUM.index()) {
            return;
        }
        var oreId = OreDepositData.oreBlockIdAt(level, pos);
        if (oreId == null) {
            return;
        }

        ConfirmedDeposit deposit =
                ConfirmedDepositIndex.get(level)
                        .recordsWithin(
                                pos, OreDepositFeature.MAX_DEPOSIT_FOOTPRINT_DIAMETER, oreId, tier)
                        .stream()
                        .filter(
                                candidate ->
                                        Math.abs(candidate.center().getY() - pos.getY())
                                                <= DISCOVERY_MAX_VERTICAL_DISTANCE)
                        .findFirst()
                        .orElse(null);
        if (deposit == null
                || !DISCOVERY_THROTTLE.allow(player, deposit.depositId(), level.getGameTime())) {
            return;
        }

        int color =
                BuiltInRegistries.BLOCK.get(oreId).defaultBlockState().getMapColor(level, pos).col;
        OreDepositData.DepositMapSnapshot snapshot = OreDepositData.mapSnapshot(level, deposit);
        var cells =
                snapshot.cells().stream()
                        .map(
                                cell ->
                                        new DiscoveredOreDepositPayload.Cell(
                                                cell.offsetX(),
                                                cell.offsetZ(),
                                                cell.remainingOre(),
                                                cell.initialOre()))
                        .toList();
        PacketDistributor.sendToPlayer(
                player,
                new DiscoveredOreDepositPayload(
                        deposit.depositId(),
                        level.dimension().location(),
                        deposit.center(),
                        oreId,
                        tier.index(),
                        color,
                        snapshot.remainingOre(),
                        snapshot.initialOre(),
                        cells));
    }

    private static boolean isStandingOn(ServerPlayer player, BlockPos pos) {
        double feetAbove = player.getBoundingBox().minY - (pos.getY() + 1.0D);
        double dx = player.getX() - (pos.getX() + 0.5D);
        double dz = player.getZ() - (pos.getZ() + 0.5D);
        // Allows for the block edge and for the server's position trailing the client by a tick or
        // two.
        return feetAbove > -0.6D && feetAbove < 0.6D && dx * dx + dz * dz <= 1.5D * 1.5D;
    }

    /**
     * The block is in front of the player, within sight distance, and nothing opaque stands in
     * between.
     */
    private static boolean canSee(ServerPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pos);
        Vec3 toTarget = target.subtract(eye);
        double distance = toTarget.length();
        if (distance > DISCOVERY_SIGHT_DISTANCE || distance < 1.0E-3D) {
            return false;
        }
        if (player.getLookAngle().dot(toTarget.scale(1.0D / distance))
                < DISCOVERY_MIN_VIEW_COSINE) {
            return false;
        }
        BlockHitResult hit =
                player.level()
                        .clip(
                                new ClipContext(
                                        eye,
                                        target,
                                        ClipContext.Block.OUTLINE,
                                        ClipContext.Fluid.NONE,
                                        player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(pos)) {
            return true;
        }
        // A ray to the block's center may graze a neighbouring deposit block of the same patch
        // first.
        return hit.getBlockPos().distSqr(pos) <= 3
                && player.level().getBlockState(hit.getBlockPos()).is(ModBlocks.ORE_DEPOSIT.get());
    }

    /** Bounds how often one player can make the server snapshot deposits. */
    private static final class DiscoveryThrottle {
        private static final int MIN_TICKS_BETWEEN_REQUESTS = 2;
        private static final int DEPOSIT_RESEND_TICKS = 60;
        private final Map<ServerPlayer, PlayerState> players = new WeakHashMap<>();

        boolean allow(ServerPlayer player, long depositId, long gameTime) {
            PlayerState state = players.computeIfAbsent(player, ignored -> new PlayerState());
            if (gameTime - state.lastSnapshot < MIN_TICKS_BETWEEN_REQUESTS) {
                return false;
            }
            Long lastSent = state.depositSent.get(depositId);
            if (lastSent != null && gameTime - lastSent < DEPOSIT_RESEND_TICKS) {
                return false;
            }
            if (state.depositSent.size() > 256) {
                state.depositSent
                        .values()
                        .removeIf(sent -> gameTime - sent >= DEPOSIT_RESEND_TICKS);
            }
            state.lastSnapshot = gameTime;
            state.depositSent.put(depositId, gameTime);
            return true;
        }

        private static final class PlayerState {
            private long lastSnapshot = Long.MIN_VALUE / 2;
            private final Map<Long, Long> depositSent = new HashMap<>();
        }
    }
}
