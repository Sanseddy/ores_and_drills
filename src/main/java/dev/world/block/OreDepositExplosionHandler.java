package dev.world.block;

import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.Iterator;

/**
 * Explosions must deplete an ore deposit the same way mining does — one ore unit per position touched,
 * with the block only actually breaking once its ore runs out. Vanilla explosions destroy every affected
 * block unconditionally, so affected ore_deposit positions are pulled out of the explosion's own block
 * list here and handled through {@link OreDepositData#mineByWorld} instead.
 */
public final class OreDepositExplosionHandler {
    private OreDepositExplosionHandler() {
    }

    public static void onDetonate(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        Iterator<BlockPos> iterator = event.getAffectedBlocks().iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (serverLevel.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())) {
                iterator.remove();
                OreDepositData.mineByWorld(serverLevel, pos);
            }
        }
    }
}
