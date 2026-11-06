package dev.world.geology;

import dev.OresAndDrillsMod;
import dev.compat.sable.SableAssemblyTransfer;
import dev.registry.ModBlocks;
import dev.world.geology.station.SeismicStationStructure;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Moving a seismic station as a whole, as Sable and Simulated's Physics Assembler do: the assembler
 * must collect all 27 cells, the move must not break the station, and a turned or mirrored copy
 * must still be a complete station. Neither mod is needed; their entry points are called directly.
 */
@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StationAssemblyGameTests {
    private static final SeismicStationStructure STATION = ModBlocks.SEISMIC_STATION_STRUCTURE;

    private static BlockPos placeStation(GameTestHelper helper, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(relative);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(origin, ModBlocks.SEISMIC_STATION.get().defaultBlockState(), 3);
        STATION.placeParts(level, origin);
        helper.assertTrue(STATION.isComplete(level, origin), "Station not built");
        return origin;
    }

    private static void removeStation(ServerLevel level, BlockPos origin) {
        STATION.removeParts(level, origin);
        level.setBlock(origin, Blocks.AIR.defaultBlockState(), 3);
    }

    @GameTest(template = "empty")
    public static void physicsAssemblerCollectsTheWholeStation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = placeStation(helper, new BlockPos(2, 40, 2));
        List<BlockPos> cells = STATION.positions(origin);
        for (BlockPos visited : List.of(origin, origin.offset(1, 2, -1), origin.offset(-1, 0, 0))) {
            Queue<BlockPos> queue = new ArrayDeque<>();
            Set<BlockPos> moved = new HashSet<>();
            SableAssemblyTransfer.expandSimulatedQueue(
                    level, visited, level.getBlockState(visited), queue, moved);
            helper.assertTrue(
                    new HashSet<>(queue).containsAll(cells),
                    "Visiting " + visited + " did not queue the whole station");
        }
        removeStation(level, origin);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stationIsProtectedOnlyWhenMovedWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = placeStation(helper, new BlockPos(2, 40, 2));
        List<BlockPos> cells = STATION.positions(origin);
        Function<BlockPos, BlockPos> samePosition = pos -> pos;
        Function<BlockState, BlockState> sameState = state -> state;

        SableAssemblyTransfer.begin(level, level, samePosition, sameState, cells);
        for (BlockPos cell : cells) {
            helper.assertTrue(
                    SableAssemblyTransfer.isMoving(level, cell), "Unprotected cell " + cell);
        }
        SableAssemblyTransfer.finish();

        List<BlockPos> partial = cells.subList(1, cells.size());
        SableAssemblyTransfer.begin(level, level, samePosition, sameState, partial);
        for (BlockPos cell : partial) {
            helper.assertTrue(
                    !SableAssemblyTransfer.isMoving(level, cell),
                    "Half a station must still break: " + cell);
        }
        SableAssemblyTransfer.finish();
        helper.assertTrue(
                !SableAssemblyTransfer.isMoving(level, origin), "Protection outlived the move");

        removeStation(level, origin);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void turnedOrMirroredStationStaysComplete(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = placeStation(helper, new BlockPos(2, 40, 2));
        BlockPos copy = helper.absolutePos(new BlockPos(2, 46, 2));
        for (Rotation rotation : Rotation.values()) {
            place(level, origin, copy, pos -> pos.rotate(rotation), s -> s.rotate(rotation));
            helper.assertTrue(STATION.isComplete(level, copy), "Broken after " + rotation);
            removeStation(level, copy);
        }
        for (Mirror mirror : List.of(Mirror.LEFT_RIGHT, Mirror.FRONT_BACK)) {
            Function<BlockPos, BlockPos> flip =
                    pos ->
                            mirror == Mirror.LEFT_RIGHT
                                    ? new BlockPos(pos.getX(), pos.getY(), -pos.getZ())
                                    : new BlockPos(-pos.getX(), pos.getY(), pos.getZ());
            place(level, origin, copy, flip, s -> s.mirror(mirror));
            helper.assertTrue(STATION.isComplete(level, copy), "Broken after " + mirror);
            removeStation(level, copy);
        }
        removeStation(level, origin);
        helper.succeed();
    }

    /** Copies the station cell by cell, as a structure template or Sable disassembly would. */
    private static void place(
            ServerLevel level,
            BlockPos origin,
            BlockPos copy,
            Function<BlockPos, BlockPos> offset,
            Function<BlockState, BlockState> state) {
        for (BlockPos cell : STATION.positions(origin)) {
            BlockPos target = copy.offset(offset.apply(cell.subtract(origin)));
            level.setBlock(target, state.apply(level.getBlockState(cell)), Block.UPDATE_CLIENTS);
        }
    }
}
