package dev.world.geology;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * A finished exploration borehole: where it was drilled, how deep, and what the core showed. These
 * are the player's own confirmed facts, so stations may display them.
 *
 * @param ore ore the core intersected, or {@code null} for a dry hole
 * @param localUnits ore units counted within the logging radius around the intersection — an
 *     estimate of the probed part only, never of the whole deposit
 * @param industrial whether the intersected body is a MEDIUM/LARGE deposit
 */
public record Borehole(
        BlockPos top,
        int bottomY,
        long gameTime,
        @Nullable ResourceLocation ore,
        int oreTopY,
        int oreBottomY,
        int localUnits,
        boolean industrial) {
    public Borehole {
        top = top.immutable();
    }

    public boolean found() {
        return ore != null;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Top", top.asLong());
        tag.putInt("Bottom", bottomY);
        tag.putLong("Time", gameTime);
        if (ore != null) {
            tag.putString("Ore", ore.toString());
            tag.putInt("OreTop", oreTopY);
            tag.putInt("OreBottom", oreBottomY);
            tag.putInt("Units", localUnits);
            tag.putBoolean("Industrial", industrial);
        }
        return tag;
    }

    public static Borehole load(CompoundTag tag) {
        ResourceLocation ore =
                tag.contains("Ore") ? ResourceLocation.tryParse(tag.getString("Ore")) : null;
        return new Borehole(
                BlockPos.of(tag.getLong("Top")),
                tag.getInt("Bottom"),
                tag.getLong("Time"),
                ore,
                tag.getInt("OreTop"),
                tag.getInt("OreBottom"),
                tag.getInt("Units"),
                tag.getBoolean("Industrial"));
    }
}
