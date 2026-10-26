package dev.world.block.entity.drill;

import dev.world.block.entity.AbstractDrillBlockEntity;
import java.util.function.Consumer;

/**
 * Per-tick client effects of a drill that need client-only classes, such as its looping work sound.
 * The client entry point installs the handler, so common code never references client classes and a
 * dedicated server keeps the no-op default.
 */
public final class DrillClientEffects {
    private static Consumer<AbstractDrillBlockEntity> handler = drill -> {};

    private DrillClientEffects() {}

    public static void setHandler(Consumer<AbstractDrillBlockEntity> clientHandler) {
        handler = clientHandler;
    }

    public static void tick(AbstractDrillBlockEntity drill) {
        handler.accept(drill);
    }
}
