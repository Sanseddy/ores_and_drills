package dev.compat.sable;

import dev.OresAndDrillsMod;
import dev.world.block.AbstractDrillBlock;
import dev.world.block.AbstractDrillPartBlock;
import dev.world.block.DrillStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Guards complete multipart drills while Sable copies their real blocks to another level. A normal part
 * removal must still break the controller, so positions are protected only when Sable selected every cell
 * of a valid drill structure in the current moveBlocks call.
 */
public final class SableAssemblyTransfer {
    private static final ThreadLocal<Deque<Batch>> ACTIVE_BATCHES = new ThreadLocal<>();

    private SableAssemblyTransfer() {
    }

    public static void begin(
            ServerLevel sourceLevel,
            ServerLevel destinationLevel,
            Function<BlockPos, BlockPos> positionTransform,
            Function<BlockState, BlockState> stateTransform,
            Iterable<BlockPos> positions
    ) {
        List<BlockPos> selectedPositions = new ArrayList<>();
        Set<Long> selectedPositionKeys = new HashSet<>();
        for (BlockPos pos : positions) {
            BlockPos immutablePos = pos.immutable();
            selectedPositions.add(immutablePos);
            selectedPositionKeys.add(immutablePos.asLong());
        }

        Set<Long> protectedPositions = new HashSet<>();
        List<MovedDrill> movedDrills = new ArrayList<>();
        for (BlockPos pos : selectedPositions) {
            BlockState state = sourceLevel.getBlockState(pos);
            if (!(state.getBlock() instanceof AbstractDrillBlock drillBlock)
                    || !state.hasProperty(AbstractDrillBlock.FACING)) {
                continue;
            }

            Direction facing = state.getValue(AbstractDrillBlock.FACING);
            DrillStructure structure = drillBlock.structure();
            if (!structure.isComplete(sourceLevel, pos, facing)) {
                continue;
            }

            List<BlockPos> structurePositions = structure.positions(pos, facing);
            boolean fullySelected = structurePositions.stream()
                    .allMatch(structurePos -> selectedPositionKeys.contains(structurePos.asLong()));
            if (fullySelected) {
                structurePositions.forEach(structurePos -> protectedPositions.add(structurePos.asLong()));
                BlockState transformedState = stateTransform.apply(state);
                movedDrills.add(new MovedDrill(
                        structure,
                        positionTransform.apply(pos).immutable(),
                        transformedState.getValue(AbstractDrillBlock.FACING)
                ));
            }
        }

        Deque<Batch> batches = ACTIVE_BATCHES.get();
        if (batches == null) {
            batches = new ArrayDeque<>();
            ACTIVE_BATCHES.set(batches);
        }
        batches.push(new Batch(sourceLevel, destinationLevel, protectedPositions, movedDrills));
    }

    public static void finish() {
        Deque<Batch> batches = ACTIVE_BATCHES.get();
        if (batches == null || batches.isEmpty()) {
            return;
        }

        Batch batch = batches.pop();
        for (MovedDrill movedDrill : batch.movedDrills()) {
            if (!movedDrill.structure().isComplete(
                    batch.destinationLevel(),
                    movedDrill.destinationOrigin(),
                    movedDrill.destinationFacing()
            )) {
                OresAndDrillsMod.LOGGER.error(
                        "Sable moved an incomplete drill structure to {} in {}",
                        movedDrill.destinationOrigin(),
                        batch.destinationLevel().dimension().location()
                );
            }
        }
        if (batches.isEmpty()) {
            ACTIVE_BATCHES.remove();
        }
    }

    public static boolean isMoving(Level level, BlockPos pos) {
        Deque<Batch> batches = ACTIVE_BATCHES.get();
        if (batches == null) {
            return false;
        }

        for (Batch batch : batches) {
            if (batch.sourceLevel() == level && batch.protectedPositions().contains(pos.asLong())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Simulated's Physics Assembler discovers blocks through glue. Drill parts are
     * connected by their controller instead, so include every cell as soon as the
     * assembler visits one valid drill cell.
     */
    public static void expandSimulatedQueue(
            Level level,
            BlockPos selectedPos,
            BlockState selectedState,
            java.util.Queue<BlockPos> queue,
            Set<BlockPos> movedBlocks
    ) {
        if (level.isClientSide) {
            return;
        }

        DrillStructure structure;
        BlockPos origin;
        Direction facing;
        if (selectedState.getBlock() instanceof AbstractDrillBlock drillBlock
                && selectedState.hasProperty(AbstractDrillBlock.FACING)) {
            structure = drillBlock.structure();
            origin = selectedPos;
            facing = selectedState.getValue(AbstractDrillBlock.FACING);
        } else if (selectedState.getBlock() instanceof AbstractDrillPartBlock drillPart
                && selectedState.hasProperty(AbstractDrillBlock.FACING)) {
            structure = drillPart.structure();
            origin = structure.originFromPart(selectedPos, selectedState);
            BlockState originState = level.getBlockState(origin);
            if (!(originState.getBlock() instanceof AbstractDrillBlock)
                    || !originState.hasProperty(AbstractDrillBlock.FACING)) {
                return;
            }
            facing = originState.getValue(AbstractDrillBlock.FACING);
        } else {
            return;
        }

        if (structure.isComplete(level, origin, facing)) {
            for (BlockPos structurePos : structure.positions(origin, facing)) {
                if (!movedBlocks.contains(structurePos)) {
                    queue.add(structurePos);
                }
            }
        }
    }

    private record Batch(
            ServerLevel sourceLevel,
            ServerLevel destinationLevel,
            Set<Long> protectedPositions,
            List<MovedDrill> movedDrills
    ) {
    }

    private record MovedDrill(DrillStructure structure, BlockPos destinationOrigin, Direction destinationFacing) {
    }
}
