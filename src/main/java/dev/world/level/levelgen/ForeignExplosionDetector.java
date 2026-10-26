package dev.world.level.levelgen;

import net.minecraft.world.level.Explosion;

import java.util.regex.Pattern;

/**
 * Recognises blocks being cleared by another mod's explosion that bypasses vanilla's explosion pipeline, such as
 * nukes that empty their crater with plain {@code setBlock} or {@code destroyBlock} calls. No event covers that,
 * so the call stack is checked instead: a frame in an {@link Explosion} subclass, or in a class or method whose
 * name speaks of an explosion. The stack is walked only when a deposit is about to be removed without
 * authorisation, which is rare.
 */
public final class ForeignExplosionDetector {
    private static final Pattern EXPLOSION_NAME =
            Pattern.compile("explo|nuke|nuclear|detonat|bomb", Pattern.CASE_INSENSITIVE);
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private ForeignExplosionDetector() {
    }

    public static boolean isExplosionInProgress() {
        return WALKER.walk(frames -> frames.anyMatch(ForeignExplosionDetector::isExplosionFrame));
    }

    private static boolean isExplosionFrame(StackWalker.StackFrame frame) {
        Class<?> type = frame.getDeclaringClass();
        if (type == ForeignExplosionDetector.class) {
            return false;
        }
        // The simple name only: a mod's package (modernindustrializationexplosivity) says nothing about the code.
        String className = type.getName();
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        return Explosion.class.isAssignableFrom(type)
                || EXPLOSION_NAME.matcher(simpleName).find()
                || EXPLOSION_NAME.matcher(frame.getMethodName()).find();
    }
}
