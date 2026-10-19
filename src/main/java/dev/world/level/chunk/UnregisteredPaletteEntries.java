package dev.world.level.chunk;

import dev.OresAndDrillsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Diagnostics for chunk palette entries that have no network id (see {@code PaletteIdGuardMixin}). */
public final class UnregisteredPaletteEntries {
    private static final Set<Object> REPORTED = ConcurrentHashMap.newKeySet();

    private UnregisteredPaletteEntries() {
    }

    private static final Set<BlockState> TRACED_WRITES = ConcurrentHashMap.newKeySet();

    /** Logs, once per state, the stack that wrote a BlockState without a registry id into a chunk. */
    public static void traceWrite(BlockPos pos, BlockState state) {
        if (Block.BLOCK_STATE_REGISTRY.getId(state) < 0 && TRACED_WRITES.add(state)) {
            OresAndDrillsMod.LOGGER.error(
                    "Unregistered BlockState {} ({}) written at {}; clients would disconnect on this chunk",
                    state, describe(state), pos, new IllegalStateException("Written here")
            );
        }
    }

    /** Logs the entry once with the code path that serialized it, and returns the id sent instead. */
    public static int substitute(Object value) {
        if (REPORTED.add(value)) {
            OresAndDrillsMod.LOGGER.error(
                    "Chunk palette entry {} ({}) has no registry id; it is sent to clients as id 0 so they stay "
                            + "connected. See the earlier 'Unregistered BlockState' error for the code that placed it.",
                    value, value instanceof BlockState state ? describe(state) : value.getClass().getName(),
                    new IllegalStateException("Serialized here")
            );
        }
        return 0;
    }

    private static String describe(BlockState state) {
        Block block = state.getBlock();
        return BuiltInRegistries.BLOCK.getKey(block) + ", " + block.getClass().getName()
                + ", registered state: " + (block.getStateDefinition().getPossibleStates().contains(state)
                        ? "same properties, different instance" : "not in its block's state definition");
    }
}
