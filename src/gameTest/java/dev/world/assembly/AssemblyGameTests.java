package dev.world.assembly;

import com.mojang.serialization.JsonOps;
import dev.OresAndDrillsMod;
import dev.registry.ModAssembly;
import dev.registry.ModBlocks;
import dev.registry.ModRecipes;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AssemblyGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void recipeButtonsValidateSelectionAndPreserveInputs(GameTestHelper helper) {
        var table = table(helper);
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(Vec3.atCenterOf(table.getBlockPos()));
        var menu = (AssemblyMenu) table.createMenu(1, player.getInventory(), player);
        var recipes = AssemblyBlockEntity.recipes(helper.getLevel());
        int selected = recipes.indexOf(table.selectedRecipe());
        fill(table.inventory(), table.selectedRecipe().value());
        var inputs =
                java.util.stream.IntStream.range(0, 7)
                        .mapToObj(i -> table.inventory().getItem(i).copy())
                        .toList();
        helper.assertTrue(table.start(), "Fixture did not start");
        helper.assertTrue(
                menu.clickMenuButton(player, AssemblyMenu.BUTTON_SELECT_RECIPE + selected)
                        && table.working(),
                "Reselecting the current recipe cancelled work");
        helper.assertTrue(
                !menu.clickMenuButton(player, -1)
                        && !menu.clickMenuButton(
                                player, AssemblyMenu.BUTTON_SELECT_RECIPE + recipes.size())
                        && table.working(),
                "Invalid selection changed work state");
        int next = (selected + 1) % recipes.size();
        helper.assertTrue(
                menu.clickMenuButton(player, AssemblyMenu.BUTTON_SELECT_RECIPE + next)
                        && table.selectedRecipe().equals(recipes.get(next))
                        && !table.working()
                        && table.progress() == 0,
                "Direct recipe selection did not cancel and switch");
        for (int i = 0; i < inputs.size(); i++) {
            helper.assertTrue(
                    ItemStack.matches(inputs.get(i), table.inventory().getItem(i)),
                    "Recipe selection changed inventory slot " + i);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void allDrillsHaveReachableRecipesAndCorrectWorkstations(GameTestHelper helper) {
        var level = helper.getLevel();
        List<RecipeHolder<AssemblyRecipe>> recipes = AssemblyBlockEntity.recipes(level);
        helper.assertTrue(recipes.size() == 9, "Assembly recipes are missing or duplicated");
        boolean reachedItems = false;
        for (var holder : recipes) {
            boolean block =
                    holder.value().result().getItem() instanceof net.minecraft.world.item.BlockItem;
            helper.assertTrue(!block || !reachedItems, "A block recipe follows an item recipe");
            if (!block) reachedItems = true;
        }
        for (var item :
                List.of(
                        ModBlocks.ELECTRIC_MINING_DRILL_ITEM.get(),
                        ModBlocks.ADVANCED_MINING_DRILL_ITEM.get(),
                        ModBlocks.ULTIMATE_MINING_DRILL_ITEM.get(),
                        ModBlocks.EXPLORATION_DRILL_ITEM.get(),
                        ModBlocks.SEISMIC_STATION_ITEM.get(),
                        ModAssembly.MOTOR.get(),
                        ModAssembly.REINFORCED_HEAD.get(),
                        ModAssembly.MANIFOLD.get(),
                        ModAssembly.CONTROL.get())) {
            helper.assertTrue(
                    recipes.stream().anyMatch(holder -> holder.value().result().is(item)),
                    "No assembly recipe for " + item);
        }
        for (var holder : recipes) {
            for (var part : holder.value().parts()) {
                helper.assertTrue(
                        part.ingredient().getItems().length > 0,
                        "Missing material tag in " + holder.id());
                for (var stack : part.ingredient().getItems()) {
                    helper.assertTrue(
                            !stack.is(ModBlocks.BURNER_MINING_DRILL_ITEM.get())
                                    && !stack.is(ModBlocks.ELECTRIC_MINING_DRILL_ITEM.get())
                                    && !stack.is(ModBlocks.ADVANCED_MINING_DRILL_ITEM.get())
                                    && !stack.is(ModBlocks.ULTIMATE_MINING_DRILL_ITEM.get()),
                            "A built drill must not be consumed by progression");
                }
            }
        }
        var crafting = level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
        helper.assertTrue(
                crafting.stream()
                        .noneMatch(
                                holder ->
                                        holder.value()
                                                .getResultItem(level.registryAccess())
                                                .is(ModBlocks.SEISMIC_STATION_ITEM.get())),
                "Seismic station bypasses component assembly");
        var station =
                recipes.stream()
                        .filter(
                                holder ->
                                        holder.value()
                                                .result()
                                                .is(ModBlocks.SEISMIC_STATION_ITEM.get()))
                        .findFirst()
                        .orElseThrow()
                        .value();
        helper.assertTrue(
                station.duration() == 400 && station.parts().size() == 6,
                "Station must use six component fixtures and twenty seconds");
        var stationParts =
                List.of(
                        ModAssembly.FRAME.get(),
                        ModAssembly.MOTOR.get(),
                        ModAssembly.DRIVE.get(),
                        ModAssembly.MANIFOLD.get(),
                        ModAssembly.CONTROL.get(),
                        Items.ANVIL);
        int[] stationCounts = {4, 2, 2, 1, 1, 1};
        for (int i = 0; i < stationParts.size(); i++) {
            helper.assertTrue(
                    station.parts().get(i).ingredient().test(new ItemStack(stationParts.get(i)))
                            && station.parts().get(i).count() == stationCounts[i],
                    "Wrong station component " + i);
        }
        helper.assertTrue(
                crafting.stream()
                        .anyMatch(
                                holder ->
                                        holder.value()
                                                .getResultItem(level.registryAccess())
                                                .is(ModBlocks.BURNER_MINING_DRILL_ITEM.get())),
                "Starter drill has no vanilla crafting recipe");
        helper.assertTrue(
                crafting.stream()
                        .noneMatch(
                                holder ->
                                        holder.value()
                                                .getResultItem(level.registryAccess())
                                                .is(ModBlocks.EXPLORATION_DRILL_ITEM.get())),
                "Old exploration drill recipe bypasses the assembly table");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void countedTagIngredientsSurviveJsonAndNetwork(GameTestHelper helper) {
        var level = helper.getLevel();
        var original = recipe(helper, "electric_motor").value();
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var encoded = AssemblyRecipe.CODEC.codec().encodeStart(ops, original).getOrThrow();
        var decoded = AssemblyRecipe.CODEC.codec().parse(ops, encoded).getOrThrow();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            var codec = ModRecipes.DRILL_ASSEMBLY_SERIALIZER.get().streamCodec();
            codec.encode(buffer, decoded);
            var synced = codec.decode(buffer);
            helper.assertTrue(
                    AssemblyRecipe.CODEC
                            .codec()
                            .encodeStart(ops, synced)
                            .getOrThrow()
                            .equals(encoded),
                    "Network sync changed recipe data or tags");
            var inventory = new SimpleContainer(7);
            fill(inventory, synced);
            helper.assertTrue(
                    synced.matches(new AssemblyInput(inventory), level), "Valid inputs rejected");
            inventory.getItem(1).shrink(1);
            helper.assertTrue(
                    !synced.matches(new AssemblyInput(inventory), level),
                    "Undercounted copper accepted");
            fill(inventory, synced);
            inventory.setItem(5, new ItemStack(Items.DIRT));
            helper.assertTrue(
                    !synced.matches(new AssemblyInput(inventory), level),
                    "Unused fixture accepted an extra input");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void completionConsumesExactlyOnceAndPreservesSurplus(GameTestHelper helper) {
        var table = table(helper);
        select(table, recipe(helper, "electric_motor"));
        var recipe = table.selectedRecipe().value();
        fill(table.inventory(), recipe);
        for (int i = 0; i < recipe.parts().size(); i++) table.inventory().getItem(i).grow(2);
        helper.assertTrue(
                table.start() && !table.start(), "Concurrent starts did not lock one batch");
        for (int tick = 0; tick < recipe.duration() * 2; tick++) table.tick();
        helper.assertTrue(
                table.inventory().getItem(6).is(ModAssembly.MOTOR.get())
                        && table.inventory().getItem(6).getCount() == 1
                        && !table.working(),
                "Completion duplicated a result or automatically restarted");
        for (int i = 0; i < recipe.parts().size(); i++) {
            helper.assertTrue(
                    table.inventory().getItem(i).getCount() == 2, "Wrong material count consumed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void seismicStationAssemblesFromComponents(GameTestHelper helper) {
        var table = table(helper);
        var station =
                AssemblyBlockEntity.recipes(helper.getLevel()).stream()
                        .filter(
                                holder ->
                                        holder.value()
                                                .result()
                                                .is(ModBlocks.SEISMIC_STATION_ITEM.get()))
                        .findFirst()
                        .orElseThrow();
        select(table, station);
        fill(table.inventory(), station.value());
        helper.assertTrue(table.start(), "Station components did not start assembly");
        for (int i = 0; i < station.value().duration() - 1; i++) table.tick();
        helper.assertTrue(table.inventory().getItem(6).isEmpty(), "Station completed early");
        table.tick();
        helper.assertTrue(
                table.inventory().getItem(6).is(ModBlocks.SEISMIC_STATION_ITEM.get())
                        && table.inventory().getItem(6).getCount() == 1
                        && !table.working(),
                "Station assembly did not produce exactly one station");
        for (int i = 0; i < 6; i++)
            helper.assertTrue(
                    table.inventory().getItem(i).isEmpty(), "Station component was not consumed");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void cancellationChangesAndBlockedOutputNeverConsumeInputs(
            GameTestHelper helper) {
        var table = table(helper);
        select(table, recipe(helper, "electric_motor"));
        var recipe = table.selectedRecipe().value();
        fill(table.inventory(), recipe);
        helper.assertTrue(table.start(), "Cannot start assembly");
        table.tick();
        table.cancel();
        helper.assertTrue(
                table.progress() == 0 && table.inventory().getItem(6).isEmpty(),
                "Cancellation produced output");
        helper.assertTrue(table.canStart(), "Cancellation consumed inputs");
        table.start();
        table.inventory().removeItem(1, 1);
        helper.assertTrue(
                !table.working() && table.progress() == 0, "Changing inputs did not cancel");
        fill(table.inventory(), recipe);
        table.inventory().setItem(6, new ItemStack(Items.DIRT));
        helper.assertTrue(!table.start(), "Wrong output item accepted");
        table.inventory().setItem(6, new ItemStack(ModAssembly.MOTOR.get(), 64));
        helper.assertTrue(!table.start(), "Full output accepted");
        table.inventory().setItem(6, ItemStack.EMPTY);
        helper.assertTrue(table.start(), "Output recovery did not unblock assembly");
        table.select(1);
        helper.assertTrue(
                !table.working()
                        && table.progress() == 0
                        && table.inventory().getItem(0).getCount()
                                == recipe.parts().getFirst().count(),
                "Changing the blueprint consumed or retained a running batch");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void runningBatchSurvivesSaveAndLoad(GameTestHelper helper) {
        var table = table(helper);
        select(table, recipe(helper, "electric_motor"));
        var recipe = table.selectedRecipe().value();
        fill(table.inventory(), recipe);
        table.start();
        for (int i = 0; i < 23; i++) table.tick();
        var saved = table.saveWithFullMetadata(helper.getLevel().registryAccess());
        var restored = new AssemblyBlockEntity(table.getBlockPos(), table.getBlockState());
        restored.setLevel(helper.getLevel());
        restored.loadWithComponents(saved, helper.getLevel().registryAccess());
        helper.assertTrue(
                restored.progress() == 23 && restored.working(), "Save/load lost progress");
        for (int i = 23; i < recipe.duration(); i++) restored.tick();
        helper.assertTrue(
                restored.inventory().getItem(6).is(ModAssembly.MOTOR.get()),
                "Resumed batch did not finish");
        for (int i = 0; i < recipe.parts().size(); i++) {
            helper.assertTrue(
                    restored.inventory().getItem(i).isEmpty(),
                    "Resumed batch consumed wrong inputs");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void breakingTableDropsRecoverablePartsExactlyOnce(GameTestHelper helper) {
        var table = table(helper);
        select(table, recipe(helper, "electric_motor"));
        var recipe = table.selectedRecipe().value();
        fill(table.inventory(), recipe);
        table.start();
        table.tick();
        var pos = table.getBlockPos();
        helper.getLevel().destroyBlock(pos, true);
        var drops =
                helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1));
        int frames =
                drops.stream()
                        .filter(drop -> drop.getItem().is(ModAssembly.DRIVE.get()))
                        .mapToInt(drop -> drop.getItem().getCount())
                        .sum();
        helper.assertTrue(
                frames == 1
                        && drops.stream()
                                .noneMatch(drop -> drop.getItem().is(ModAssembly.MOTOR.get())),
                "Breaking a running table duplicated or consumed a part");
        int tables =
                drops.stream()
                        .filter(drop -> drop.getItem().is(ModAssembly.TABLE_ITEM.get()))
                        .mapToInt(drop -> drop.getItem().getCount())
                        .sum();
        helper.assertTrue(tables == 1, "Assembly table loot is missing or duplicated");
        drops.forEach(ItemEntity::discard);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void itemPlacesBothHalvesInEveryDirectionAndConsumesOneItem(
            GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() - 3);
        for (int yaw : new int[] {0, 90, 180, 270}) {
            level.removeBlock(pos, false);
            for (Direction direction : Direction.Plane.HORIZONTAL)
                level.removeBlock(pos.relative(direction), false);
            player.setYRot(yaw);
            var stack = new ItemStack(ModAssembly.TABLE_ITEM.get(), 2);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var result =
                    ModAssembly.TABLE_ITEM
                            .get()
                            .useOn(
                                    new UseOnContext(
                                            player,
                                            InteractionHand.MAIN_HAND,
                                            new BlockHitResult(
                                                    Vec3.atCenterOf(pos),
                                                    Direction.UP,
                                                    pos,
                                                    false)));
            var state = level.getBlockState(pos);
            helper.assertTrue(
                    result.consumesAction()
                            && state.is(ModAssembly.TABLE.get())
                            && !state.getValue(AssemblyBlock.EXTENSION)
                            && stack.getCount() == 1,
                    "Table item failed or consumed the wrong count for yaw "
                            + yaw
                            + ": result="
                            + result
                            + ", state="
                            + state
                            + ", count="
                            + stack.getCount());
            var other = AssemblyBlock.otherPos(pos, state);
            helper.assertTrue(
                    AssemblyBlock.isComplete(level, pos, state)
                            && level.getBlockEntity(pos) instanceof AssemblyBlockEntity
                            && level.getBlockEntity(other) == null
                            && AssemblyBlock.mainPos(other, level.getBlockState(other)).equals(pos),
                    "Second half did not share the main inventory for yaw " + yaw);
            level.removeBlock(pos, false);
            helper.assertTrue(level.isEmptyBlock(other), "Removing the main half left an orphan");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void blockedSecondHalfRejectsPlacementWithoutSpendingTheItem(
            GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.removeBlock(pos, false);
        level.setBlockAndUpdate(pos.east(), Blocks.STONE.defaultBlockState());
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setYRot(0);
        var stack = new ItemStack(ModAssembly.TABLE_ITEM.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var placementResult =
                ModAssembly.TABLE_ITEM
                        .get()
                        .useOn(
                                new UseOnContext(
                                        player,
                                        InteractionHand.MAIN_HAND,
                                        new BlockHitResult(
                                                Vec3.atCenterOf(pos), Direction.UP, pos, false)));
        helper.assertTrue(
                level.isEmptyBlock(pos)
                        && level.getBlockState(pos.east()).is(Blocks.STONE)
                        && stack.getCount() == 2,
                "Blocked placement overwrote a neighbor or spent an item: main="
                        + level.getBlockState(pos)
                        + ", other="
                        + level.getBlockState(pos.east())
                        + ", count="
                        + stack.getCount()
                        + ", result="
                        + placementResult);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void extensionOpensTheSameMenuAndBreakingItDropsOneTable(GameTestHelper helper) {
        var table = table(helper);
        select(table, recipe(helper, "electric_motor"));
        fill(table.inventory(), table.selectedRecipe().value());
        var level = helper.getLevel();
        var other = AssemblyBlock.otherPos(table.getBlockPos(), table.getBlockState());
        net.minecraft.world.MenuProvider[] opened = new net.minecraft.world.MenuProvider[1];
        var player =
                new net.minecraft.world.entity.player.Player(
                        level,
                        BlockPos.ZERO,
                        0,
                        new com.mojang.authlib.GameProfile(
                                java.util.UUID.randomUUID(), "assembly-menu-test")) {
                    @Override
                    public boolean isSpectator() {
                        return false;
                    }

                    @Override
                    public boolean isCreative() {
                        return false;
                    }

                    @Override
                    public java.util.OptionalInt openMenu(
                            net.minecraft.world.MenuProvider provider) {
                        opened[0] = provider;
                        return java.util.OptionalInt.of(1);
                    }
                };
        player.setPos(other.getX() + 0.5, other.getY(), other.getZ() - 2);
        ModAssembly.TABLE
                .get()
                .useWithoutItem(
                        level.getBlockState(other),
                        level,
                        other,
                        player,
                        new BlockHitResult(Vec3.atCenterOf(other), Direction.NORTH, other, false));
        var menu = table.createMenu(1, player.getInventory(), player);
        helper.assertTrue(
                opened[0] == table && menu.stillValid(player),
                "Secondary half did not open the shared valid menu");
        table.start();
        table.tick();
        level.destroyBlock(other, true);
        helper.assertTrue(
                level.isEmptyBlock(other) && level.isEmptyBlock(table.getBlockPos()),
                "Breaking the secondary half left an orphan");
        var drops =
                level.getEntitiesOfClass(
                        ItemEntity.class,
                        new AABB(table.getBlockPos()).minmax(new AABB(other)).inflate(1));
        helper.assertTrue(
                drops.stream()
                                        .filter(e -> e.getItem().is(ModAssembly.TABLE_ITEM.get()))
                                        .mapToInt(e -> e.getItem().getCount())
                                        .sum()
                                == 1
                        && drops.stream()
                                        .filter(e -> e.getItem().is(ModAssembly.DRIVE.get()))
                                        .mapToInt(e -> e.getItem().getCount())
                                        .sum()
                                == 1,
                "Secondary destruction duplicated table loot or lost the inventory");
        helper.assertTrue(!menu.stillValid(player), "Menu survived destruction of its table");
        drops.forEach(ItemEntity::discard);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void creativeBreakingTheExtensionDoesNotDropAnExtraTable(GameTestHelper helper) {
        var table = table(helper);
        var level = helper.getLevel();
        var other = AssemblyBlock.otherPos(table.getBlockPos(), table.getBlockState());
        var state = level.getBlockState(other);
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        ModAssembly.TABLE.get().playerWillDestroy(level, other, state, player);
        level.removeBlock(other, false);
        var drops =
                level.getEntitiesOfClass(
                        ItemEntity.class,
                        new AABB(table.getBlockPos()).minmax(new AABB(other)).inflate(1));
        helper.assertTrue(
                level.isEmptyBlock(table.getBlockPos())
                        && level.isEmptyBlock(other)
                        && drops.stream()
                                .noneMatch(e -> e.getItem().is(ModAssembly.TABLE_ITEM.get())),
                "Creative secondary destruction left a part or produced a table");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void oldSingleBlockTableExpandsOnlyWhenItsNeighborIsFree(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var oldState =
                ModAssembly.TABLE
                        .get()
                        .defaultBlockState()
                        .setValue(AssemblyBlock.FACING, Direction.NORTH);
        level.setBlockAndUpdate(pos, oldState);
        var table = (AssemblyBlockEntity) level.getBlockEntity(pos);
        select(table, recipe(helper, "electric_motor"));
        fill(table.inventory(), table.selectedRecipe().value());
        var saved = table.saveWithFullMetadata(level.registryAccess());
        saved.remove("AssemblyWidth");
        saved.putInt("Progress", 23);
        saved.putBoolean("Working", true);
        table.loadWithComponents(saved, level.registryAccess());
        level.setBlockAndUpdate(pos.east(), Blocks.STONE.defaultBlockState());
        table.tick();
        helper.assertTrue(
                level.getBlockState(pos.east()).is(Blocks.STONE)
                        && table.progress() == 23
                        && table.working(),
                "Legacy migration overwrote a block or lost progress");
        level.removeBlock(pos.east(), false);
        table.tick();
        helper.assertTrue(
                AssemblyBlock.isComplete(level, pos, table.getBlockState())
                        && table.getBlockState().getValue(AssemblyBlock.FACING) == Direction.EAST
                        && table.progress() == 24
                        && table.inventory().getItem(0).is(ModAssembly.DRIVE.get())
                        && table.saveWithFullMetadata(level.registryAccess())
                                        .getInt("AssemblyWidth")
                                == 2,
                "Legacy expansion did not preserve inventory, front orientation and progress");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aPairAcrossAChunkBoundaryIsRemovedTogether(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(1, 1, 1));
        var pos = new BlockPos((origin.getX() & ~15) + 15, origin.getY() + 5, origin.getZ());
        level.getChunkAt(pos.east());
        var state = ModAssembly.TABLE.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        level.setBlockAndUpdate(pos.east(), state.setValue(AssemblyBlock.EXTENSION, true));
        helper.assertTrue(
                AssemblyBlock.isComplete(level, pos, state), "Cross-chunk pair was not complete");
        level.removeBlock(pos.east(), false);
        helper.assertTrue(
                level.isEmptyBlock(pos) && level.isEmptyBlock(pos.east()),
                "Cross-chunk destruction left a table half");
        level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2))
                .forEach(ItemEntity::discard);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void cancelledPlacementRestoresBothHalvesWithoutDrops(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.removeBlock(pos, false);
        level.removeBlock(pos.east(), false);
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setYRot(0);
        player.setPos(pos.getX(), pos.getY(), pos.getZ() - 3);
        var stack = new ItemStack(ModAssembly.TABLE_ITEM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        int firstSnapshot = level.capturedBlockSnapshots.size();
        boolean wasCapturing = level.captureBlockSnapshots;
        boolean wasRestoring = level.restoringBlockSnapshots;
        try {
            level.captureBlockSnapshots = true;
            var placed =
                    ModAssembly.TABLE_ITEM
                            .get()
                            .useOn(
                                    new UseOnContext(
                                            player,
                                            InteractionHand.MAIN_HAND,
                                            new BlockHitResult(
                                                    Vec3.atCenterOf(pos),
                                                    Direction.UP,
                                                    pos,
                                                    false)));
            helper.assertTrue(
                    placed.consumesAction(), "Snapshot fixture could not place the table");
            var table = (AssemblyBlockEntity) level.getBlockEntity(pos);
            table.inventory().setItem(0, new ItemStack(ModAssembly.DRIVE.get()));
            level.captureBlockSnapshots = false;
            var snapshots =
                    new java.util.ArrayList<>(
                            level.capturedBlockSnapshots.subList(
                                    firstSnapshot, level.capturedBlockSnapshots.size()));
            level.capturedBlockSnapshots
                    .subList(firstSnapshot, level.capturedBlockSnapshots.size())
                    .clear();
            level.restoringBlockSnapshots = true;
            for (int i = snapshots.size() - 1; i >= 0; i--) snapshots.get(i).restore();
            helper.assertTrue(
                    level.isEmptyBlock(pos)
                            && level.isEmptyBlock(pos.east())
                            && level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2))
                                    .isEmpty(),
                    "Cancelled placement left a half or duplicated its table/contents");
        } finally {
            level.captureBlockSnapshots = wasCapturing;
            level.restoringBlockSnapshots = wasRestoring;
            level.capturedBlockSnapshots
                    .subList(firstSnapshot, level.capturedBlockSnapshots.size())
                    .clear();
        }
        helper.succeed();
    }

    private static AssemblyBlockEntity table(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlockAndUpdate(pos, ModAssembly.TABLE.get().defaultBlockState());
        helper.getLevel()
                .setBlockAndUpdate(
                        pos.east(),
                        ModAssembly.TABLE
                                .get()
                                .defaultBlockState()
                                .setValue(AssemblyBlock.EXTENSION, true));
        return (AssemblyBlockEntity) helper.getLevel().getBlockEntity(pos);
    }

    private static RecipeHolder<AssemblyRecipe> recipe(GameTestHelper helper, String name) {
        return AssemblyBlockEntity.recipes(helper.getLevel()).stream()
                .filter(
                        holder ->
                                holder.id()
                                        .equals(
                                                ResourceLocation.fromNamespaceAndPath(
                                                        OresAndDrillsMod.MOD_ID,
                                                        "assembly/" + name)))
                .findFirst()
                .orElseThrow();
    }

    private static void select(AssemblyBlockEntity table, RecipeHolder<AssemblyRecipe> recipe) {
        for (int i = 0;
                i < AssemblyBlockEntity.recipes(table.getLevel()).size()
                        && !table.selectedRecipe().id().equals(recipe.id());
                i++) table.select(1);
    }

    private static void fill(SimpleContainer inventory, AssemblyRecipe recipe) {
        inventory.clearContent();
        for (int i = 0; i < recipe.parts().size(); i++) {
            var part = recipe.parts().get(i);
            inventory.setItem(i, part.ingredient().getItems()[0].copyWithCount(part.count()));
        }
    }
}
