package dev.registry;

import dev.world.block.AbstractDrillBlock;
import dev.world.block.AbstractDrillPartBlock;
import dev.world.block.entity.AbstractDrillBlockEntity;
import dev.world.block.entity.AbstractEnergyDrillBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

public final class ModCapabilities {
    private ModCapabilities() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.BURNER_MINING_DRILL.get(), (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.ELECTRIC_MINING_DRILL.get(), (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.ADVANCED_MINING_DRILL.get(), (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.ULTIMATE_MINING_DRILL.get(), (be, side) -> be.getItemHandler());

        event.registerBlock(
                Capabilities.ItemHandler.BLOCK,
                ModCapabilities::findItemHandler,
                ModBlocks.BURNER_MINING_DRILL_PART.get(),
                ModBlocks.ELECTRIC_MINING_DRILL_PART.get(),
                ModBlocks.ADVANCED_MINING_DRILL_PART.get(),
                ModBlocks.ULTIMATE_MINING_DRILL_PART.get()
        );

        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.ELECTRIC_MINING_DRILL.get(), (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.ADVANCED_MINING_DRILL.get(), (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.ULTIMATE_MINING_DRILL.get(), (be, side) -> be.getEnergyStorage());

        event.registerBlock(
                Capabilities.EnergyStorage.BLOCK,
                ModCapabilities::findEnergyStorage,
                ModBlocks.ELECTRIC_MINING_DRILL_PART.get(),
                ModBlocks.ADVANCED_MINING_DRILL_PART.get(),
                ModBlocks.ULTIMATE_MINING_DRILL_PART.get()
        );

        event.registerBlock(
                Capabilities.FluidHandler.BLOCK,
                ModCapabilities::findFluidHandler,
                ModBlocks.ADVANCED_MINING_DRILL_PART.get(),
                ModBlocks.ULTIMATE_MINING_DRILL_PART.get()
        );
    }

    private static BlockEntity originBlockEntity(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof AbstractDrillPartBlock part) {
            return level.getBlockEntity(part.structure().originFromPart(pos, state));
        }

        return null;
    }

    private static IItemHandler findItemHandler(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity, Direction side) {
        BlockEntity origin = originBlockEntity(level, pos, state);
        return origin instanceof AbstractDrillBlockEntity drillBlockEntity ? drillBlockEntity.getItemHandler() : null;
    }

    private static IEnergyStorage findEnergyStorage(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity, Direction side) {
        BlockEntity origin = originBlockEntity(level, pos, state);
        return origin instanceof AbstractEnergyDrillBlockEntity drillBlockEntity ? drillBlockEntity.getEnergyStorage() : null;
    }

    private static IFluidHandler findFluidHandler(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity, Direction side) {
        if (!(state.getBlock() instanceof AbstractDrillPartBlock part)) {
            return null;
        }

        if (!isFluidPort(part, state, side)) {
            return null;
        }
        BlockEntity origin = level.getBlockEntity(part.structure().originFromPart(pos, state));
        return origin instanceof AbstractEnergyDrillBlockEntity drillBlockEntity ? drillBlockEntity.getFluidHandler() : null;
    }

    private static boolean isFluidPort(AbstractDrillPartBlock part, BlockState state, Direction side) {
        int size = part.structure().size();
        int center = part.structure().size() / 2;
        int offsetX = state.getValue(part.structure().offsetXProperty());
        int offsetY = state.getValue(part.structure().offsetYProperty());
        int offsetZ = state.getValue(part.structure().offsetZProperty());
        if (offsetY != 0 || offsetZ != center) {
            return false;
        }

        Direction right = state.getValue(AbstractDrillBlock.FACING).getClockWise();
        if (offsetX == 0) {
            return side == null || side == right.getOpposite();
        }

        if (offsetX == size - 1) {
            return side == null || side == right;
        }

        return false;
    }
}
