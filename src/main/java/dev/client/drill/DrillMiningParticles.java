package dev.client.drill;

import dev.world.block.entity.AbstractDrillBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Debris of the mined ore thrown out in a ring around the bit of a working drill. The bit meets the
 * ground under the machine's body, so the chunks are launched outward and upward hard enough to fly
 * out from under it.
 *
 * <p>Particles are created through the particle engine directly: {@code Level.addParticle}
 * randomises the velocity of block particles so heavily that they only hop in place, hidden under
 * the drill.
 */
public final class DrillMiningParticles {
    private static final double MAX_DISTANCE_SQR = 32.0D * 32.0D;
    private static final double MIN_OUTWARD_SPEED = 0.08D;
    private static final double MAX_OUTWARD_SPEED = 0.2D;

    /**
     * Low hops: with block-particle gravity the debris rises about 0.1–0.35 blocks before falling
     * back.
     */
    private static final double MIN_UPWARD_SPEED = 0.08D;

    private static final double MAX_UPWARD_SPEED = 0.17D;
    private static final int MIN_LIFETIME = 18;
    private static final int MAX_LIFETIME = 30;

    private DrillMiningParticles() {}

    public static void tick(AbstractDrillBlockEntity drill) {
        Block ore = drill.miningParticleBlock();
        if (!drill.isActive() || ore == null || drill.getLevel() == null) {
            return;
        }

        BlockState oreState = ore.defaultBlockState();
        if (oreState.isAir() || oreState.getRenderShape() == RenderShape.INVISIBLE) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Vec3 center = drill.bitGroundCenter();
        if (minecraft.gameRenderer.getMainCamera().getPosition().distanceToSqr(center)
                > MAX_DISTANCE_SQR) {
            return;
        }

        RandomSource random = drill.getLevel().random;
        double expected = drill.debrisPerTick() * particleShare(minecraft);
        int count = (int) expected + (random.nextDouble() < expected - (int) expected ? 1 : 0);

        BlockParticleOption particle = new BlockParticleOption(ParticleTypes.BLOCK, oreState);
        for (int i = 0; i < count; i++) {
            spawn(minecraft, particle, center, drill.bitRadius(), random);
        }
    }

    /**
     * Like the debris of a block being broken, which vanilla shows at every particle setting, the
     * drill's debris is only thinned out by lower settings instead of disappearing on "Minimal".
     */
    private static double particleShare(Minecraft minecraft) {
        ParticleStatus status = minecraft.options.particles().get();
        return switch (status) {
            case ALL -> 1.0D;
            case DECREASED -> 0.6D;
            case MINIMAL -> 0.3D;
        };
    }

    private static void spawn(
            Minecraft minecraft,
            BlockParticleOption option,
            Vec3 center,
            double radius,
            RandomSource random) {
        float angle = random.nextFloat() * Mth.TWO_PI;
        double dirX = Mth.cos(angle);
        double dirZ = Mth.sin(angle);
        double distance = radius * (0.9D + random.nextDouble() * 0.2D);
        Particle particle =
                minecraft.particleEngine.createParticle(
                        option,
                        center.x + dirX * distance,
                        center.y + 0.02D + random.nextDouble() * 0.08D,
                        center.z + dirZ * distance,
                        0.0D,
                        0.0D,
                        0.0D);
        if (particle == null) {
            return;
        }

        double outward = Mth.lerp(random.nextDouble(), MIN_OUTWARD_SPEED, MAX_OUTWARD_SPEED);
        particle.setParticleSpeed(
                dirX * outward,
                Mth.lerp(random.nextDouble(), MIN_UPWARD_SPEED, MAX_UPWARD_SPEED),
                dirZ * outward);
        particle.setLifetime(Mth.nextInt(random, MIN_LIFETIME, MAX_LIFETIME));
        particle.scale(1.3F);
    }
}
