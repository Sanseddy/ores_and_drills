package dev.client.drill;

import dev.world.block.entity.AbstractDrillBlockEntity;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** A drill's work noise, looped at its bit while the drill runs and faded out once it stops. */
final class DrillWorkSound extends AbstractTickableSoundInstance {
    private static final int FADE_TICKS = 10;
    private static final float FULL_VOLUME = 1.0F;

    private final AbstractDrillBlockEntity drill;
    private final SoundEvent event;
    private int fade;

    DrillWorkSound(AbstractDrillBlockEntity drill, SoundEvent event) {
        super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.drill = drill;
        this.event = event;
        Vec3 position = drill.bitGroundCenter();
        this.x = position.x;
        this.y = position.y;
        this.z = position.z;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
    }

    boolean isFor(AbstractDrillBlockEntity other, SoundEvent otherEvent) {
        return drill == other && event == otherEvent;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (drill.isRemoved() || drill.workSound() != event) {
            stop();
            return;
        }

        fade = drill.isActive() ? Math.min(FADE_TICKS, fade + 1) : fade - 1;
        if (fade <= 0 && !drill.isActive()) {
            stop();
            return;
        }
        volume = FULL_VOLUME * Math.max(0, fade) / FADE_TICKS;
    }
}
