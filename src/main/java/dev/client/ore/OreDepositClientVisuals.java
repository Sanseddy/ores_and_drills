package dev.client.ore;

import dev.OresAndDrillsMod;
import dev.mixin.RenderChunkRegionAccessor;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

public final class OreDepositClientVisuals {
    /**
     * Sodium replaces vanilla's {@link RenderChunkRegion} with its own world snapshot for threaded chunk meshing.
     * We don't compile against Sodium, so its "level" field is read reflectively rather than via a Mixin accessor.
     */
    private static final String SODIUM_LEVEL_SLICE_CLASS = "net.caffeinemc.mods.sodium.client.world.LevelSlice";
    private static volatile Field sodiumLevelField;
    private static volatile boolean sodiumFieldUnavailable;

    private OreDepositClientVisuals() {
    }

    @Nullable
    public static OreDepositData.Visual visualAt(BlockAndTintGetter level, BlockPos pos) {
        Level concreteLevel = concreteLevel(level);
        return concreteLevel == null ? null : OreDepositData.visualAt(concreteLevel, pos);
    }

    @Nullable
    private static Level concreteLevel(BlockAndTintGetter level) {
        if (level instanceof Level concreteLevel) {
            return concreteLevel;
        }
        if (level instanceof RenderChunkRegion renderChunkRegion) {
            return ((RenderChunkRegionAccessor)renderChunkRegion).factoryExpansion$level();
        }
        return sodiumLevel(level);
    }

    @Nullable
    private static Level sodiumLevel(BlockAndTintGetter level) {
        if (sodiumFieldUnavailable || !SODIUM_LEVEL_SLICE_CLASS.equals(level.getClass().getName())) {
            return null;
        }

        Field field = sodiumLevelField;
        if (field == null) {
            try {
                field = level.getClass().getDeclaredField("level");
                field.setAccessible(true);
                sodiumLevelField = field;
            } catch (ReflectiveOperationException exception) {
                sodiumFieldUnavailable = true;
                OresAndDrillsMod.LOGGER.debug("Ore deposit: could not access Sodium's LevelSlice#level field for rendering", exception);
                return null;
            }
        }

        try {
            Object value = field.get(level);
            return value instanceof Level concreteLevel ? concreteLevel : null;
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
