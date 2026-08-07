package dev.world.block;

import dev.world.level.levelgen.OreTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class DrillStructure {
    private static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private final int size;
    private final int height;
    private final int mainOffsetX;
    private final int mainOffsetY;
    private final int mainOffsetZ;
    private final Supplier<? extends Block> mainBlock;
    private final Supplier<? extends Block> partBlock;
    private final Supplier<? extends Item> partCloneItem;
    private final IntegerProperty offsetXProperty;
    private final IntegerProperty offsetYProperty;
    private final IntegerProperty offsetZProperty;
    private final VoxelShape[][][] northShapes;
    private final VoxelShape[][][][] structureShapes;
    private final DrillModelCell[][][][] exactCells;

    public DrillStructure(int size, int height, int mainOffsetX, int mainOffsetY, int mainOffsetZ,
                          Supplier<? extends Block> mainBlock,
                          Supplier<? extends Block> partBlock,
                          Supplier<? extends Item> partCloneItem,
                          IntegerProperty offsetXProperty, IntegerProperty offsetYProperty, IntegerProperty offsetZProperty,
                          double modelForwardOffset,
                          String geometryResource) {
        this.size = size;
        this.height = height;
        this.mainOffsetX = mainOffsetX;
        this.mainOffsetY = mainOffsetY;
        this.mainOffsetZ = mainOffsetZ;
        this.mainBlock = mainBlock;
        this.partBlock = partBlock;
        this.partCloneItem = partCloneItem;
        this.offsetXProperty = offsetXProperty;
        this.offsetYProperty = offsetYProperty;
        this.offsetZProperty = offsetZProperty;
        DrillHitboxModel.LoadedHitboxes hitboxes = DrillHitboxModel.loadOrFullBlocks(
                geometryResource,
                size,
                height,
                mainOffsetX,
                mainOffsetY,
                mainOffsetZ,
                modelForwardOffset
        );
        this.northShapes = hitboxes.collisionShapes();
        this.structureShapes = createStructureShapes();
        this.exactCells = createExactCells(hitboxes.exactCells());
    }

    public int size() {
        return size;
    }

    public int height() {
        return height;
    }

    public IntegerProperty offsetXProperty() {
        return offsetXProperty;
    }

    public IntegerProperty offsetYProperty() {
        return offsetYProperty;
    }

    public IntegerProperty offsetZProperty() {
        return offsetZProperty;
    }

    public boolean isMainBlock(BlockState state) {
        return state.is(mainBlock.get());
    }

    public ItemStack cloneItemStack() {
        return new ItemStack(partCloneItem.get());
    }

    public boolean canPlace(Level level, BlockPos origin, Direction facing) {
        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetY = 0; offsetY < height; offsetY++) {
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    BlockPos target = offset(origin, facing, offsetX, offsetY, offsetZ);
                    BlockState state = level.getBlockState(target);

                    if (!target.equals(origin) && !state.canBeReplaced()) {
                        return false;
                    }
                }
            }
        }

        return hasValidSupport(level, origin, facing);
    }

    private boolean hasValidSupport(Level level, BlockPos origin, Direction facing) {
        boolean hasOre = false;

        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                BlockPos supportPos = offset(origin, facing, offsetX, 0, offsetZ).below();
                BlockState supportState = level.getBlockState(supportPos);

                if (supportState.isAir()
                        || !supportState.isCollisionShapeFullBlock(level, supportPos)
                        || !supportState.isFaceSturdy(level, supportPos, Direction.UP)) {
                    return false;
                }

                if (OreTags.isOre(supportState)) {
                    hasOre = true;
                }
            }
        }

        return hasOre;
    }

    public void placeParts(Level level, BlockPos origin, Direction facing) {
        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetY = 0; offsetY < height; offsetY++) {
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    if (isMainOffset(offsetX, offsetY, offsetZ)) {
                        continue;
                    }

                    BlockPos target = offset(origin, facing, offsetX, offsetY, offsetZ);
                    BlockState partState = partBlock.get().defaultBlockState()
                            .setValue(FACING, facing)
                            .setValue(offsetXProperty, offsetX)
                            .setValue(offsetYProperty, offsetY)
                            .setValue(offsetZProperty, offsetZ);
                    level.setBlock(target, partState, Block.UPDATE_ALL);
                }
            }
        }
    }

    public void removeParts(Level level, BlockPos origin, Direction facing) {
        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetY = 0; offsetY < height; offsetY++) {
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    if (isMainOffset(offsetX, offsetY, offsetZ)) {
                        continue;
                    }

                    BlockPos target = offset(origin, facing, offsetX, offsetY, offsetZ);
                    BlockState state = level.getBlockState(target);
                    if (isMatchingPart(state, facing, offsetX, offsetY, offsetZ)) {
                        level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    public boolean isComplete(LevelAccessor level, BlockPos origin, Direction facing) {
        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetY = 0; offsetY < height; offsetY++) {
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    BlockPos target = offset(origin, facing, offsetX, offsetY, offsetZ);
                    BlockState state = level.getBlockState(target);

                    if (isMainOffset(offsetX, offsetY, offsetZ)) {
                        if (!target.equals(origin) || !isMainBlock(state)) {
                            return false;
                        }
                    } else if (!isMatchingPart(state, facing, offsetX, offsetY, offsetZ)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public List<BlockPos> positions(BlockPos origin, Direction facing) {
        List<BlockPos> positions = new ArrayList<>(size * height * size);
        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetY = 0; offsetY < height; offsetY++) {
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    positions.add(offset(origin, facing, offsetX, offsetY, offsetZ));
                }
            }
        }
        return positions;
    }

    public VoxelShape mainCollisionShape(Direction facing) {
        return collisionShapeForOffset(facing, mainOffsetX, mainOffsetY, mainOffsetZ);
    }

    public VoxelShape collisionShapeForOffset(Direction facing, int offsetX, int offsetY, int offsetZ) {
        return structureShapes[facing.ordinal()][offsetX][offsetY][offsetZ];
    }

    public DrillModelCell mainExactCell(Direction facing) {
        return exactCellForOffset(facing, mainOffsetX, mainOffsetY, mainOffsetZ);
    }

    public DrillModelCell exactCellForOffset(Direction facing, int offsetX, int offsetY, int offsetZ) {
        return exactCells[facing.ordinal()][offsetX][offsetY][offsetZ];
    }

    public BlockPos originFromPart(BlockPos partPos, BlockState partState) {
        Direction facing = partState.getValue(FACING);
        int offsetX = partState.getValue(offsetXProperty);
        int offsetY = partState.getValue(offsetYProperty);
        int offsetZ = partState.getValue(offsetZProperty);

        Direction right = facing.getClockWise();
        return partPos
                .relative(right, mainOffsetX - offsetX)
                .above(mainOffsetY - offsetY)
                .relative(facing, mainOffsetZ - offsetZ);
    }

    public BlockPos offset(BlockPos origin, Direction facing, int offsetX, int offsetY, int offsetZ) {
        Direction right = facing.getClockWise();
        return origin
                .relative(right, offsetX - mainOffsetX)
                .above(offsetY - mainOffsetY)
                .relative(facing, offsetZ - mainOffsetZ);
    }

    public AABB bounds(BlockPos origin, Direction facing) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetY = 0; offsetY < height; offsetY++) {
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    BlockPos target = offset(origin, facing, offsetX, offsetY, offsetZ);
                    minX = Math.min(minX, target.getX());
                    minY = Math.min(minY, target.getY());
                    minZ = Math.min(minZ, target.getZ());
                    maxX = Math.max(maxX, target.getX() + 1);
                    maxY = Math.max(maxY, target.getY() + 1);
                    maxZ = Math.max(maxZ, target.getZ() + 1);
                }
            }
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public boolean isWithinInteractionDistance(Player player, BlockPos origin, Direction facing, double maxDistanceSquared) {
        Direction right = facing.getClockWise();
        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetY = 0; offsetY < height; offsetY++) {
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    int x = origin.getX()
                            + right.getStepX() * (offsetX - mainOffsetX)
                            + facing.getStepX() * (offsetZ - mainOffsetZ);
                    int y = origin.getY() + offsetY - mainOffsetY;
                    int z = origin.getZ()
                            + right.getStepZ() * (offsetX - mainOffsetX)
                            + facing.getStepZ() * (offsetZ - mainOffsetZ);
                    if (player.distanceToSqr(x + 0.5D, y + 0.5D, z + 0.5D) <= maxDistanceSquared) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public boolean isMatchingPart(BlockState state, Direction facing, int offsetX, int offsetY, int offsetZ) {
        return state.is(partBlock.get())
                && state.getValue(FACING) == facing
                && state.getValue(offsetXProperty) == offsetX
                && state.getValue(offsetYProperty) == offsetY
                && state.getValue(offsetZProperty) == offsetZ;
    }

    private boolean isMainOffset(int offsetX, int offsetY, int offsetZ) {
        return offsetX == mainOffsetX && offsetY == mainOffsetY && offsetZ == mainOffsetZ;
    }

    private VoxelShape[][][][] createStructureShapes() {
        VoxelShape[][][][] shapes = new VoxelShape[Direction.values().length][size][height][size];

        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int offsetX = 0; offsetX < size; offsetX++) {
                for (int offsetY = 0; offsetY < height; offsetY++) {
                    for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                        shapes[facing.ordinal()][offsetX][offsetY][offsetZ] = createStructureShape(facing, offsetX, offsetY, offsetZ);
                    }
                }
            }
        }

        return shapes;
    }

    private VoxelShape createStructureShape(Direction facing, int currentOffsetX, int currentOffsetY, int currentOffsetZ) {
        return DrillHitboxModel.rotateY(
                northShapes[currentOffsetX][currentOffsetY][currentOffsetZ],
                facing
        );
    }

    private DrillModelCell[][][][] createExactCells(DrillModelCell[][][] northCells) {
        DrillModelCell[][][][] cells = new DrillModelCell[Direction.values().length][size][height][size];
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            int turns = switch (facing) {
                case NORTH -> 0;
                case EAST -> 1;
                case SOUTH -> 2;
                case WEST -> 3;
                default -> throw new IllegalArgumentException("Unexpected facing " + facing);
            };
            for (int offsetX = 0; offsetX < size; offsetX++) {
                for (int offsetY = 0; offsetY < height; offsetY++) {
                    for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                        DrillModelCell northCell = northCells[offsetX][offsetY][offsetZ];
                        cells[facing.ordinal()][offsetX][offsetY][offsetZ] = northCell == null
                                ? null
                                : northCell.rotateY(turns);
                    }
                }
            }
        }
        return cells;
    }
}
