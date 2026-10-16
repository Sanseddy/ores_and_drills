package dev.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

/**
 * Explicit escape hatch for operations that intentionally remove a physical deposit block.
 * Unknown miners must use virtual extraction instead of replacing the persistent container.
 */
public final class OreDepositRemovalGuard {
    private static final ThreadLocal<Deque<AuthorizedRemoval>> AUTHORIZED =
            ThreadLocal.withInitial(ArrayDeque::new);

    private OreDepositRemovalGuard() {
    }

    public static boolean isAuthorized(Level level, BlockPos pos) {
        for (AuthorizedRemoval removal : AUTHORIZED.get()) {
            if (removal.level() == level && removal.pos() == pos.asLong()) {
                return true;
            }
        }
        return false;
    }

    public static boolean runAuthorized(Level level, BlockPos pos, BooleanSupplier operation) {
        Deque<AuthorizedRemoval> removals = AUTHORIZED.get();
        removals.push(new AuthorizedRemoval(level, pos.asLong()));
        try {
            return operation.getAsBoolean();
        } finally {
            removals.pop();
            if (removals.isEmpty()) {
                AUTHORIZED.remove();
            }
        }
    }

    private record AuthorizedRemoval(Level level, long pos) {
    }
}
