package dev.world.level.levelgen;

import dev.registry.ModBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Predicate;

/** Horizontal cave-wall detection shared by world generation and validation. */
public final class CaveWallDetector {
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private CaveWallDetector() {
    }

    public static boolean isOpenCaveSpace(BlockState state) {
        // Includes vanilla air, cave_air and void_air. Fluids and arbitrary replaceable blocks are
        // deliberately not treated as cave space by the core wall contract.
        return state.isAir();
    }

    public static boolean isCaveWall(LevelAccessor level, BlockPos pos) {
        return findAnchorAt(level, pos, null, 1, 1).isPresent();
    }

    public static int measureWallDepth(
            LevelAccessor level,
            BlockPos wallPos,
            Direction inwardDirection,
            int maximumDepth
    ) {
        return measureWallDepth(level, wallPos, inwardDirection, maximumDepth,
                state -> state.is(ModBlockTags.STONES));
    }

    public static int measureWallDepth(
            LevelAccessor level,
            BlockPos wallPos,
            Direction inwardDirection,
            int maximumDepth,
            Predicate<BlockState> hostPredicate
    ) {
        if (inwardDirection == null || inwardDirection.getAxis().isVertical() || maximumDepth <= 0) {
            return 0;
        }
        int depth = 0;
        for (; depth < maximumDepth; depth++) {
            BlockState state = level.getBlockState(wallPos.relative(inwardDirection, depth));
            if (!hostPredicate.test(state) || !state.getFluidState().isEmpty()) {
                break;
            }
        }
        return depth;
    }

    public static Optional<CaveWallAnchor> findAnchorAt(
            LevelAccessor level,
            BlockPos pos,
            @Nullable Direction preferredExposedFace,
            int minimumDepth,
            int maximumDepth
    ) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(ModBlockTags.STONES) || !state.getFluidState().isEmpty()) {
            return Optional.empty();
        }

        if (isUsableExposedFace(level, pos, preferredExposedFace, minimumDepth, maximumDepth)) {
            return Optional.of(new CaveWallAnchor(pos, preferredExposedFace));
        }
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            if (direction == preferredExposedFace) {
                continue;
            }
            if (isUsableExposedFace(level, pos, direction, minimumDepth, maximumDepth)) {
                return Optional.of(new CaveWallAnchor(pos, direction));
            }
        }
        return Optional.empty();
    }

    public static Optional<CaveWallAnchor> findNearest(
            LevelAccessor level,
            BlockPos origin,
            int horizontalRadius,
            int verticalRadius,
            int maximumChecks,
            int minimumDepth,
            int maximumDepth,
            @Nullable Direction preferredExposedFace,
            Predicate<BlockPos> positionAllowed
    ) {
        return findNearest(
                level, origin, horizontalRadius, verticalRadius, maximumChecks,
                minimumDepth, maximumDepth, preferredExposedFace, positionAllowed, null
        );
    }

    public static Optional<CaveWallAnchor> findNearest(
            LevelAccessor level,
            BlockPos origin,
            int horizontalRadius,
            int verticalRadius,
            int maximumChecks,
            int minimumDepth,
            int maximumDepth,
            @Nullable Direction preferredExposedFace,
            Predicate<BlockPos> positionAllowed,
            @Nullable Predicate<BlockState> hostPredicate
    ) {
        int safeHorizontalRadius = Math.max(0, horizontalRadius);
        int safeVerticalRadius = Math.max(0, verticalRadius);
        int safeMaximumChecks = Math.max(1, maximumChecks);
        int maximumDistance = safeHorizontalRadius + safeVerticalRadius;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int checks = 0;

        for (int distance = 0; distance <= maximumDistance; distance++) {
            int minDy = -Math.min(safeVerticalRadius, distance);
            int maxDy = Math.min(safeVerticalRadius, distance);
            for (int dy = minDy; dy <= maxDy; dy++) {
                int horizontalDistance = distance - Math.abs(dy);
                if (horizontalDistance > safeHorizontalRadius) {
                    continue;
                }
                int minDx = -Math.min(safeHorizontalRadius, horizontalDistance);
                int maxDx = Math.min(safeHorizontalRadius, horizontalDistance);
                for (int dx = minDx; dx <= maxDx; dx++) {
                    int dz = horizontalDistance - Math.abs(dx);
                    mutable.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    Optional<CaveWallAnchor> found = checkPosition(
                            level, mutable, preferredExposedFace, minimumDepth, maximumDepth,
                            positionAllowed, hostPredicate, checks++, safeMaximumChecks
                    );
                    if (found.isPresent()) {
                        return found;
                    }
                    if (checks >= safeMaximumChecks) {
                        return Optional.empty();
                    }
                    if (dz != 0) {
                        mutable.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() - dz);
                        found = checkPosition(
                                level, mutable, preferredExposedFace, minimumDepth, maximumDepth,
                                positionAllowed, hostPredicate, checks++, safeMaximumChecks
                        );
                        if (found.isPresent()) {
                            return found;
                        }
                        if (checks >= safeMaximumChecks) {
                            return Optional.empty();
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static boolean isExposedToCave(LevelAccessor level, Iterable<BlockPos> positions) {
        for (BlockPos pos : positions) {
            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                if (isOpenCaveSpace(level.getBlockState(pos.relative(direction)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Optional<CaveWallAnchor> checkPosition(
            LevelAccessor level,
            BlockPos pos,
            @Nullable Direction preferredExposedFace,
            int minimumDepth,
            int maximumDepth,
            Predicate<BlockPos> positionAllowed,
            @Nullable Predicate<BlockState> hostPredicate,
            int checks,
            int maximumChecks
    ) {
        if (checks >= maximumChecks
                || pos.getY() < level.getMinBuildHeight()
                || pos.getY() >= level.getMaxBuildHeight()
                || !positionAllowed.test(pos)) {
            return Optional.empty();
        }
        if (hostPredicate == null) {
            return findAnchorAt(level, pos, preferredExposedFace, minimumDepth, maximumDepth);
        }
        BlockState state = level.getBlockState(pos);
        if (!hostPredicate.test(state) || !state.getFluidState().isEmpty()) {
            return Optional.empty();
        }
        if (isUsableExposedFace(
                level, pos, preferredExposedFace, minimumDepth, maximumDepth, hostPredicate
        )) {
            return Optional.of(new CaveWallAnchor(pos, preferredExposedFace));
        }
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            if (direction != preferredExposedFace && isUsableExposedFace(
                    level, pos, direction, minimumDepth, maximumDepth, hostPredicate
            )) {
                return Optional.of(new CaveWallAnchor(pos, direction));
            }
        }
        return Optional.empty();
    }

    private static boolean isUsableExposedFace(
            LevelAccessor level,
            BlockPos pos,
            @Nullable Direction exposedFace,
            int minimumDepth,
            int maximumDepth
    ) {
        return isUsableExposedFace(
                level, pos, exposedFace, minimumDepth, maximumDepth,
                state -> state.is(ModBlockTags.STONES)
        );
    }

    private static boolean isUsableExposedFace(
            LevelAccessor level,
            BlockPos pos,
            @Nullable Direction exposedFace,
            int minimumDepth,
            int maximumDepth,
            Predicate<BlockState> hostPredicate
    ) {
        if (exposedFace == null || exposedFace.getAxis().isVertical()
                || !isOpenCaveSpace(level.getBlockState(pos.relative(exposedFace)))) {
            return false;
        }
        return measureWallDepth(
                level, pos, exposedFace.getOpposite(), Math.max(minimumDepth, maximumDepth), hostPredicate
        ) >= Math.max(1, minimumDepth);
    }
}
