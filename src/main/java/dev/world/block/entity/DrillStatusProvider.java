package dev.world.block.entity;

public interface DrillStatusProvider {
    boolean isActive();

    int getMiningProgress();

    int getMiningDuration();
}
