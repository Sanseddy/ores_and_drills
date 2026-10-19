package dev.world.block;

import dev.recipe.DrillMiningRecipes;
import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.Iterator;

/**
 * Explosions deplete an ore deposit instead of destroying it: each deposit block loses as many ore units as
 * the blast is strong where it stands (see {@link #unitsFor}), and only breaks once its ore runs out.
 * Vanilla explosions destroy every affected block unconditionally, so affected ore_deposit positions are
 * pulled out of the explosion's own block list here and handled through
 * {@link OreDepositData#mineByExplosion} instead. Medium and large deposits are pulled out and left as they
 * are (see {@link OreDepositData#ignoresExplosions}).
 * <p>
 * Ore that needs a mining fluid gives nothing to explosions. Vanilla explosions roll block loot directly
 * instead of firing {@code BlockDropsEvent}, so ordinary fluid-locked ore blocks are also pulled out of the
 * list and destroyed here without their loot.
 */
public final class OreDepositExplosionHandler {
    private OreDepositExplosionHandler() {
    }

    public static void onDetonate(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        Explosion explosion = event.getExplosion();
        if (!(level instanceof ServerLevel serverLevel) || !destroysBlocks(explosion)) {
            return;
        }

        Iterator<BlockPos> iterator = event.getAffectedBlocks().iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            BlockState state = serverLevel.getBlockState(pos);
            if (state.is(ModBlocks.ORE_DEPOSIT.get())) {
                iterator.remove();
                if (!OreDepositData.ignoresExplosions(serverLevel, pos)) {
                    OreDepositData.mineByExplosion(serverLevel, pos, unitsFor(explosion, pos))
                            .forEach(drop -> Block.popResource(serverLevel, pos, drop));
                }
            } else if (!state.isAir() && DrillMiningRecipes.requiresFluidOutsideDrills(serverLevel, state)) {
                iterator.remove();
                // What BlockBehaviour#onExplosionHit does after rolling the loot.
                state.onBlockExploded(serverLevel, pos, explosion);
            }
        }
    }

    /**
     * Ore units a deposit block loses: the blast radius at the centre (TNT 4, creeper 3, charged creeper and
     * end crystal 6), falling off linearly to half of it one radius away and never below one unit.
     */
    static int unitsFor(Explosion explosion, BlockPos pos) {
        double radius = explosion.radius();
        if (radius <= 0.0D) {
            return 1;
        }
        double distance = explosion.center().distanceTo(Vec3.atCenterOf(pos));
        return Math.max(1, (int) Math.round(radius * (1.0D - distance / (2.0D * radius))));
    }

    /** KEEP (mobGriefing off) leaves blocks alone and TRIGGER_BLOCK (wind charges) only presses buttons and the like. */
    private static boolean destroysBlocks(Explosion explosion) {
        Explosion.BlockInteraction interaction = explosion.getBlockInteraction();
        return interaction == Explosion.BlockInteraction.DESTROY
                || interaction == Explosion.BlockInteraction.DESTROY_WITH_DECAY;
    }
}
