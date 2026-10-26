package dev.client.drill;

import dev.world.block.entity.AbstractDrillBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Starts the looping work sound of each running drill near the player. The sound fades itself out when the drill
 * stops or unloads; a sound the engine dropped (out of range, {@code /stopsound}, no free channel) is restarted
 * after a short cooldown so a failing engine is not asked again every tick.
 */
public final class DrillWorkSounds {
    /** Start within a little more than the 24-block {@code attenuation_distance} of the drill sounds. */
    private static final double START_DISTANCE = 32.0D;
    private static final long RETRY_TICKS = 20L;

    private static final Map<AbstractDrillBlockEntity, Playing> PLAYING = new WeakHashMap<>();

    private DrillWorkSounds() {
    }

    public static void tick(AbstractDrillBlockEntity drill) {
        SoundEvent event = drill.workSound();
        if (event == null || !drill.isActive() || drill.getLevel() == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || player.position().distanceToSqr(drill.bitGroundCenter()) > START_DISTANCE * START_DISTANCE) {
            return;
        }

        SoundManager sounds = minecraft.getSoundManager();
        long now = drill.getLevel().getGameTime();
        Playing playing = PLAYING.get(drill);
        if (playing != null && playing.sound().isFor(drill, event) && !playing.sound().isStopped()
                && (sounds.isActive(playing.sound()) || now - playing.startedAt() < RETRY_TICKS)) {
            return;
        }

        DrillWorkSound sound = new DrillWorkSound(drill, event);
        sounds.play(sound);
        PLAYING.put(drill, new Playing(sound, now));
    }

    private record Playing(DrillWorkSound sound, long startedAt) {
    }
}
