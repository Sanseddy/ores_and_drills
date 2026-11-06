package dev.world.geology.station;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;

/**
 * The 3×3×3 body of a seismic station. The main block sits at the bottom centre; the other 26
 * positions are part blocks that store their offset and point back to it. Offsets are in world
 * axes: the station is symmetric, its facing only turns the model.
 */
public final class SeismicStationStructure {
    public static final int SIZE = 3;
    public static final int MAIN_X = 1;
    public static final int MAIN_Y = 0;
    public static final int MAIN_Z = 1;

    public static final IntegerProperty OFFSET_X = IntegerProperty.create("offset_x", 0, SIZE - 1);
    public static final IntegerProperty OFFSET_Y = IntegerProperty.create("offset_y", 0, SIZE - 1);
    public static final IntegerProperty OFFSET_Z = IntegerProperty.create("offset_z", 0, SIZE - 1);

    private final Supplier<? extends Block> mainBlock;
    private final Supplier<? extends Block> partBlock;

    public SeismicStationStructure(
            Supplier<? extends Block> mainBlock, Supplier<? extends Block> partBlock) {
        this.mainBlock = mainBlock;
        this.partBlock = partBlock;
    }

    public BlockPos offset(BlockPos origin, int x, int y, int z) {
        return origin.offset(x - MAIN_X, y - MAIN_Y, z - MAIN_Z);
    }

    public BlockPos originFromPart(BlockPos part, BlockState state) {
        return part.offset(
                MAIN_X - state.getValue(OFFSET_X),
                MAIN_Y - state.getValue(OFFSET_Y),
                MAIN_Z - state.getValue(OFFSET_Z));
    }

    /**
     * The whole body must be free (the clicked block aside) and stand on nine sturdy blocks: the
     * striker needs firm ground to couple its impact into.
     */
    public boolean canPlace(Level level, BlockPos origin) {
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    BlockPos target = offset(origin, x, y, z);
                    if (target.equals(origin)) {
                        continue;
                    }
                    if (!level.isInWorldBounds(target)
                            || !level.getBlockState(target).canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                BlockPos support = offset(origin, x, 0, z).below();
                BlockState state = level.getBlockState(support);
                if (state.isAir() || !state.isFaceSturdy(level, support, Direction.UP)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void placeParts(Level level, BlockPos origin) {
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    if (isMain(x, y, z)) {
                        continue;
                    }
                    level.setBlock(
                            offset(origin, x, y, z),
                            partBlock
                                    .get()
                                    .defaultBlockState()
                                    .setValue(OFFSET_X, x)
                                    .setValue(OFFSET_Y, y)
                                    .setValue(OFFSET_Z, z),
                            Block.UPDATE_ALL);
                }
            }
        }
    }

    public void removeParts(Level level, BlockPos origin) {
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    if (isMain(x, y, z)) {
                        continue;
                    }
                    BlockPos target = offset(origin, x, y, z);
                    if (isMatchingPart(level.getBlockState(target), x, y, z)) {
                        level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    public boolean isComplete(LevelAccessor level, BlockPos origin) {
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    BlockState state = level.getBlockState(offset(origin, x, y, z));
                    if (isMain(x, y, z)
                            ? !state.is(mainBlock.get())
                            : !isMatchingPart(state, x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public List<BlockPos> positions(BlockPos origin) {
        List<BlockPos> positions = new ArrayList<>(SIZE * SIZE * SIZE);
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    positions.add(offset(origin, x, y, z));
                }
            }
        }
        return positions;
    }

    public AABB bounds(BlockPos origin) {
        BlockPos min = offset(origin, 0, 0, 0);
        return new AABB(
                min.getX(),
                min.getY(),
                min.getZ(),
                min.getX() + SIZE,
                min.getY() + SIZE,
                min.getZ() + SIZE);
    }

    public boolean isMainBlock(BlockState state) {
        return state.is(mainBlock.get());
    }

    public boolean isMatchingPart(BlockState state, int x, int y, int z) {
        return state.is(partBlock.get())
                && state.getValue(OFFSET_X) == x
                && state.getValue(OFFSET_Y) == y
                && state.getValue(OFFSET_Z) == z;
    }

    private static boolean isMain(int x, int y, int z) {
        return x == MAIN_X && y == MAIN_Y && z == MAIN_Z;
    }
}
