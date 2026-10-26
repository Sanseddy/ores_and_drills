package dev.world.block.entity;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.OresAndDrillsMod;
import dev.drill.AdvancedMiningDrill;
import dev.drill.ElectricMiningDrill;
import dev.recipe.DrillMiningInput;
import dev.recipe.DrillMiningRecipe;
import dev.recipe.DrillMiningRecipes;
import dev.registry.ModBlockEntities;
import dev.registry.ModBlocks;
import dev.registry.ModRecipes;
import dev.world.block.AbstractDrillBlock;
import dev.world.block.MiningDrillTier;
import dev.world.block.entity.drill.DrillBit;
import dev.world.block.entity.drill.OreScanner;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreDepositRemovalGuard;
import dev.world.level.levelgen.OreDepositStonePalette;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DrillMiningRecipeGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void unknownDirectRemovalIsRejectedWithoutLoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        deposit(level, pos, Blocks.IRON_ORE, 3);

        boolean removed = level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(
                !removed
                        && level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())
                        && OreDepositData.remainingOreAt(level, pos) == 3
                        && level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(0.75D))
                                .isEmpty(),
                "Unknown direct removal erased, depleted, or dropped a persistent deposit");

        boolean authorized =
                OreDepositRemovalGuard.runAuthorized(
                        level,
                        pos,
                        () ->
                                level.setBlock(
                                        pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
        helper.assertTrue(
                authorized
                        && level.getBlockState(pos).isAir()
                        && OreDepositData.remainingOreAt(level, pos) == 0,
                "Explicitly authorized removal was blocked or retained deposit metadata");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void jsonNetworkReloadAndRecipePriority(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var wet = DrillMiningRecipes.find(level, Blocks.GOLD_ORE.defaultBlockState());
        helper.assertTrue(
                wet != null && wet.requiresFluid() && wet.fluid().orElseThrow().amount() == 100,
                "Special fluid recipe was not loaded or lost to the generic dry recipe");
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var encoded = DrillMiningRecipe.CODEC.codec().encodeStart(ops, wet).getOrThrow();
        var decoded = DrillMiningRecipe.CODEC.codec().parse(ops, encoded).getOrThrow();
        helper.assertTrue(
                decoded.accepts(Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState())
                        && decoded.minimumDrillTier() == 3,
                "JSON round trip lost block-list inputs or tier");
        var lowTierWet =
                DrillMiningRecipe.CODEC
                        .codec()
                        .parse(
                                ops,
                                JsonParser.parseString(
                                        "{\"input\":[\"minecraft:gold_ore\"],\"fluid\":{\"id\":\"minecraft:water\",\"amount\":100},\"minimum_drill_tier\":1}"))
                        .getOrThrow();
        var highTierWet =
                DrillMiningRecipe.CODEC
                        .codec()
                        .parse(
                                ops,
                                JsonParser.parseString(
                                        "{\"input\":[\"minecraft:gold_ore\"],\"fluid\":{\"id\":\"minecraft:water\",\"amount\":100},\"minimum_drill_tier\":4}"))
                        .getOrThrow();
        var lowTierDry =
                DrillMiningRecipe.CODEC
                        .codec()
                        .parse(ops, JsonParser.parseString("{\"input\":[\"minecraft:gold_ore\"]}"))
                        .getOrThrow();
        helper.assertTrue(
                lowTierWet.minimumDrillTier() == 3
                        && highTierWet.minimumDrillTier() == 4
                        && lowTierDry.minimumDrillTier() == 1,
                "Fluid recipe accepted a drill without tanks");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            var serializer = ModRecipes.DRILL_MINING_SERIALIZER.get();
            serializer.streamCodec().encode(buffer, wet);
            var synced = serializer.streamCodec().decode(buffer);
            helper.assertTrue(
                    synced.requiresFluid()
                            && synced.fluid().orElseThrow().amount() == 100
                            && synced.accepts(Blocks.GOLD_ORE.defaultBlockState()),
                    "Recipe network synchronization changed data");
        } finally {
            buffer.release();
        }
        for (String invalid :
                new String[] {
                    "{\"input\":[\"minecraft:gold_ore\"],\"fluid\":{\"id\":\"minecraft:water\",\"amount\":0}}",
                    "{\"input\":[\"minecraft:gold_ore\"],\"minimum_drill_tier\":0}",
                    "{\"input\":[]}",
                    "{\"input\":[\"minecraft:gold_ore\"],\"fluid\":{\"id\":\"minecraft:water\",\"amount\":-10}}",
                    "{\"input\":[\"minecraft:gold_ore\"],\"fluid\":{\"tag\":\"minecraft:water\",\"amount\":0}}",
                    "{\"input\":[\"minecraft:gold_ore\"],\"fluid\":{\"tag\":\"minecraft:water\",\"amount\":-10}}",
                    "{\"input\":[\"minecraft:gold_ore\"],\"fluid\":{\"id\":\"minecraft:water\",\"tag\":\"minecraft:water\",\"amount\":100}}",
                    "{\"input\":[\"minecraft:gold_ore\"],\"conditions\":[{\"type\":\"ores_and_drills:height\",\"min\":10,\"max\":0}]}",
                    "{\"input\":[\"minecraft:gold_ore\"],\"conditions\":[{\"type\":\"missing:condition\"}]}"
                })
            helper.assertTrue(
                    DrillMiningRecipe.CODEC
                            .codec()
                            .parse(ops, JsonParser.parseString(invalid))
                            .error()
                            .isPresent(),
                    "Invalid mining recipe accepted");
        RecipeManager isolated = new RecipeManager(level.registryAccess());
        isolated.replaceRecipes(
                List.of(new RecipeHolder<>(ResourceLocation.parse("test:wet"), wet)));
        helper.assertTrue(
                DrillMiningRecipes.find(isolated, Blocks.GOLD_ORE.defaultBlockState()) != null,
                "Index failed to find recipe");
        isolated.replaceRecipes(List.of());
        helper.assertTrue(
                DrillMiningRecipes.find(isolated, Blocks.GOLD_ORE.defaultBlockState()) == null,
                "Reload retained removed recipe");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fluidTagsSyncSelectAvailableTankAndConserveResources(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var recipe = DrillMiningRecipes.find(level, Blocks.EMERALD_ORE.defaultBlockState());
        helper.assertTrue(recipe != null && recipe.requiresFluid(), "Tagged recipe did not load");
        var fluid = recipe.fluid().orElseThrow();
        helper.assertTrue(
                fluid.test(new FluidStack(Fluids.WATER, 100))
                        && fluid.test(new FluidStack(Fluids.LAVA, 100))
                        && !fluid.test(new FluidStack(Fluids.FLOWING_LAVA, 100))
                        && !fluid.test(new FluidStack(Fluids.WATER, 99)),
                "Tag predicate or amount check failed");
        helper.assertTrue(
                fluid.displayStacks().size() == 2
                        && fluid.displayStacks().stream()
                                .allMatch(stack -> stack.getAmount() == 100),
                "JEI alternatives lost a tag member or required amount");
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var encoded = DrillMiningRecipe.CODEC.codec().encodeStart(ops, recipe).getOrThrow();
        helper.assertTrue(
                encoded.getAsJsonObject()
                        .getAsJsonObject("fluid")
                        .get("tag")
                        .getAsString()
                        .equals("ores_and_drills:test_mining_fluids"),
                "JSON replaced tag with one concrete fluid");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            var serializer = ModRecipes.DRILL_MINING_SERIALIZER.get();
            serializer.streamCodec().encode(buffer, recipe);
            var synced = serializer.streamCodec().decode(buffer);
            helper.assertTrue(
                    synced.fluid().orElseThrow().test(new FluidStack(Fluids.WATER, 100))
                            && synced.fluid().orElseThrow().test(new FluidStack(Fluids.LAVA, 100)),
                    "Network sync lost tag alternatives");
            helper.assertTrue(
                    DrillMiningRecipe.CODEC
                            .codec()
                            .encodeStart(ops, synced)
                            .getOrThrow()
                            .equals(encoded),
                    "Network synchronization changed tag identity");
        } finally {
            buffer.release();
        }

        BlockPos ore = helper.absolutePos(new BlockPos(1, 1, 1));
        deposit(level, ore, Blocks.EMERALD_ORE, 4);
        TestDrill drill = new TestDrill(ore.above(2), level, 1);
        var targets = List.of(new OreScanner.Target(ore, new ItemStack(Items.EMERALD), 1, 1, 4));
        helper.assertTrue(
                !AbstractDrillBlockEntity.canOutputTargets(drill, targets),
                "Empty tanks allowed tagged-fluid mining");
        drill.getFluidHandler()
                .fill(new FluidStack(Fluids.WATER, 99), IFluidHandler.FluidAction.EXECUTE);
        drill.getFluidHandler()
                .fill(new FluidStack(Fluids.FLOWING_LAVA, 500), IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(
                !AbstractDrillBlockEntity.canOutputTargets(drill, targets)
                        && OreDepositData.remainingOreAt(level, ore) == 4,
                "Wrong fluid or insufficient tag member allowed mining");
        drill.getFluidHandler()
                .drain(new FluidStack(Fluids.FLOWING_LAVA, 500), IFluidHandler.FluidAction.EXECUTE);
        drill.getFluidHandler()
                .fill(new FluidStack(Fluids.LAVA, 200), IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(
                AbstractDrillBlockEntity.canOutputTargets(drill, targets),
                "Insufficient first tank hid a valid second tank");
        AbstractDrillBlockEntity.advanceMining(drill, level, true, targets);
        helper.assertTrue(
                drill.getFluidAmount(0) == 99
                        && drill.getFluidAmount(1) == 100
                        && OreDepositData.remainingOreAt(level, ore) == 3,
                "Tagged cycle spent the wrong tank or reserve");
        AbstractDrillBlockEntity.advanceMining(drill, level, true, targets);
        helper.assertTrue(
                drill.getFluidAmount(0) == 99
                        && drill.getFluidAmount(1) == 0
                        && OreDepositData.remainingOreAt(level, ore) == 2,
                "Second tag member did not work for another cycle");
        helper.assertTrue(
                !AbstractDrillBlockEntity.canOutputTargets(drill, targets),
                "Insufficient fluid did not pause mining");
        drill.getFluidHandler()
                .fill(new FluidStack(Fluids.WATER, 101), IFluidHandler.FluidAction.EXECUTE);
        AbstractDrillBlockEntity.advanceMining(drill, level, true, targets);
        helper.assertTrue(
                drill.getFluidAmount(0) == 100
                        && OreDepositData.remainingOreAt(level, ore) == 1
                        && drill.getInventory().getItem(1).getCount() == 3,
                "Switching tag member lost output or spent extra fluid");

        var emptyTag =
                DrillMiningRecipe.CODEC
                        .codec()
                        .parse(
                                ops,
                                JsonParser.parseString(
                                        """
{"input":["minecraft:emerald_ore"],
 "fluid":{"tag":"ores_and_drills:test_empty_mining_fluids","amount":100}}
"""))
                        .getOrThrow();
        helper.assertTrue(
                emptyTag.requiresFluid()
                        && !drill.hasMiningFluid(emptyTag.fluid().orElseThrow())
                        && emptyTag.fluid().orElseThrow().displayStacks().isEmpty(),
                "Empty fluid tag became a free recipe");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fluidTagLocksRealPickaxeLoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        var player =
                net.neoforged.neoforge.common.util.FakePlayerFactory.get(
                        level,
                        new com.mojang.authlib.GameProfile(
                                java.util.UUID.randomUUID(), "drill-tag-loot-test"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        for (boolean asDeposit : new boolean[] {false, true}) {
            if (asDeposit) deposit(level, pos, Blocks.EMERALD_ORE, 100);
            else level.setBlock(pos, Blocks.EMERALD_ORE.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(
                    DrillMiningRecipes.requiresFluidOutsideDrills(
                            level, DrillMiningRecipes.sourceState(level, pos)),
                    "Tagged recipe failed to lock hand mining");
            player.gameMode.destroyBlock(pos);
            helper.assertTrue(
                    (asDeposit
                                    ? level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())
                                    : level.getBlockState(pos).isAir())
                            && level.getEntitiesOfClass(
                                            ItemEntity.class, new AABB(pos).inflate(0.75D))
                                    .isEmpty(),
                    "Tag recipe allowed pickaxe loot or changed the physical-block policy");
            if (asDeposit) {
                helper.assertTrue(
                        OreDepositData.remainingOreAt(level, pos) == 99,
                        "Tagged deposit lost more than one reserve unit");
                deposit(level, pos, Blocks.EMERALD_ORE, 2);
                for (int remaining = 1; remaining >= 0; remaining--) {
                    player.gameMode.destroyBlock(pos);
                    helper.assertTrue(
                            OreDepositData.remainingOreAt(level, pos) == remaining
                                    && level.getBlockState(pos)
                                            .is(
                                                    remaining > 0
                                                            ? ModBlocks.ORE_DEPOSIT.get()
                                                            : ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())
                                    && level.getEntitiesOfClass(
                                                    ItemEntity.class, new AABB(pos).inflate(0.75D))
                                            .isEmpty(),
                            "Tagged deposit failed gradual depletion without loot");
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void wetCyclePausesResumesAndChargesOnceWhileDepletingEveryUnit(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ore = helper.absolutePos(new BlockPos(1, 1, 1));
        deposit(level, ore, Blocks.GOLD_ORE, 5);
        TestDrill drill = new TestDrill(ore.above(2), level, 3);
        helper.assertTrue(
                OreScanner.scan(
                                level,
                                ore.above(2),
                                Direction.NORTH,
                                ModBlocks.ADVANCED_STRUCTURE,
                                AdvancedMiningDrill.TIER)
                        .stream()
                        .anyMatch(
                                target ->
                                        target.pos().equals(ore)
                                                && target.result().is(Items.RAW_GOLD)
                                                && target.requiredFluid() != null
                                                && target.requiredFluid().displayStacks().stream()
                                                        .anyMatch(
                                                                fluid ->
                                                                        fluid.is(Fluids.WATER)
                                                                                && fluid.getAmount()
                                                                                        == 100)),
                "Scanner did not expose the underlying ore recipe and its fluid requirement");
        var targets = List.of(new OreScanner.Target(ore, new ItemStack(Items.RAW_GOLD), 1, 1, 5));
        helper.assertTrue(
                !AbstractDrillBlockEntity.canOutputTargets(drill, targets),
                "Dry tank allowed wet mining");
        drill.getFluidHandler()
                .fill(new FluidStack(Fluids.LAVA, 500), IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(
                !AbstractDrillBlockEntity.canOutputTargets(drill, targets),
                "Wrong fluid allowed mining");
        drill.getFluidHandler().drain(500, IFluidHandler.FluidAction.EXECUTE);
        drill.getFluidHandler()
                .fill(new FluidStack(Fluids.WATER, 99), IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(
                !AbstractDrillBlockEntity.canOutputTargets(drill, targets)
                        && OreDepositData.remainingOreAt(level, ore) == 5,
                "Insufficient fluid depleted reserve");
        drill.getFluidHandler()
                .fill(new FluidStack(Fluids.WATER, 101), IFluidHandler.FluidAction.EXECUTE);
        drill.getInventory().setItem(1, new ItemStack(Items.RAW_IRON, 64));
        helper.assertTrue(
                !AbstractDrillBlockEntity.canOutputTargets(drill, targets)
                        && drill.getFluidAmount(0) == 200,
                "Blocked output consumed fluid");
        drill.getInventory().setItem(1, ItemStack.EMPTY);
        helper.assertTrue(
                AbstractDrillBlockEntity.canOutputTargets(drill, targets),
                "Refilled tank did not resume mining");
        AbstractDrillBlockEntity.advanceMining(drill, level, true, targets);
        helper.assertTrue(
                OreDepositData.remainingOreAt(level, ore) == 2
                        && drill.getFluidAmount(0) == 100
                        && drill.getInventory().getItem(1).getCount() == 3,
                "A three-unit cycle must deplete three reserves but charge fluid once");
        AbstractDrillBlockEntity.advanceMining(drill, level, true, targets);
        helper.assertTrue(
                OreDepositData.remainingOreAt(level, ore) == 0
                        && drill.getFluidAmount(0) == 0
                        && drill.getInventory().getItem(1).getCount() == 5
                        && level.getBlockState(ore).is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get()),
                "Final cycle lost resource or failed to exhaust deposit");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void dryRecipesTierConditionsAndEnergyPause(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ore = helper.absolutePos(new BlockPos(1, 1, 1));
        deposit(level, ore, Blocks.IRON_ORE, 2);
        TestDrill drill = new TestDrill(ore.above(2), level, 1);
        var iron = List.of(new OreScanner.Target(ore, new ItemStack(Items.RAW_IRON), 1, 1, 2));
        helper.assertTrue(
                AbstractDrillBlockEntity.canOutputTargets(drill, iron),
                "Generic ore requires fluid");
        AbstractDrillBlockEntity.advanceMining(drill, level, true, iron);
        helper.assertTrue(
                OreDepositData.remainingOreAt(level, ore) == 1, "Dry mining failed to deplete ore");
        // The deposit still holds one unit; the removal guard rejects plain replacement of a
        // persistent deposit.
        OreDepositRemovalGuard.runAuthorized(
                level,
                ore,
                () ->
                        level.setBlock(
                                ore, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL));
        helper.assertTrue(
                level.getBlockState(ore).is(Blocks.DIAMOND_ORE)
                        && OreDepositData.remainingOreAt(level, ore) == 0,
                "Deposit was not replaced by a plain ore block");
        var diamond = List.of(new OreScanner.Target(ore, new ItemStack(Items.DIAMOND), 1, 1, 1));
        helper.assertTrue(
                !AbstractDrillBlockEntity.canOutputTargets(drill, diamond),
                "Minimum drill tier bypassed");
        var recipe = DrillMiningRecipes.find(level, Blocks.DIAMOND_ORE.defaultBlockState());
        helper.assertTrue(
                recipe.matches(
                        new DrillMiningInput(
                                Blocks.DIAMOND_ORE.defaultBlockState(), ore.above(), ore, 4),
                        level),
                "Valid dimension or tier condition rejected");
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var restricted =
                DrillMiningRecipe.CODEC
                        .codec()
                        .parse(
                                ops,
                                JsonParser.parseString(
                                        """
                                        {"input":["minecraft:diamond_ore"],"conditions":[
                                        {"type":"ores_and_drills:height","min":-50,"max":-20},
                                        {"type":"ores_and_drills:redstone","powered":true}]}
                                        """))
                        .getOrThrow();
        helper.assertTrue(
                !restricted.matches(
                        new DrillMiningInput(
                                Blocks.DIAMOND_ORE.defaultBlockState(), ore.above(), ore, 4),
                        level),
                "Runtime conditions ignored");
        level.setBlock(ore, Blocks.GOLD_ORE.defaultBlockState(), Block.UPDATE_ALL);
        var advanced =
                new AdvancedMiningDrill.BlockEntity(
                        ore.above(), ModBlocks.ADVANCED_MINING_DRILL.get().defaultBlockState());
        advanced.setLevel(level);
        advanced.getEnergyStorage().receiveEnergy(1000, false);
        AdvancedMiningDrill.BlockEntity.tick(
                level, ore.above(), advanced.getBlockState(), advanced);
        helper.assertTrue(
                advanced.getEnergyStored() == 1000
                        && advanced.getMiningProgress() == 0
                        && !advanced.isActive(),
                "Missing fluid still consumed energy or advanced progress");
        var electric =
                new ElectricMiningDrill.BlockEntity(
                        ore.above(), ModBlocks.ELECTRIC_MINING_DRILL.get().defaultBlockState());
        electric.setLevel(level);
        helper.assertTrue(
                !AbstractDrillBlockEntity.canOutputTargets(
                        electric,
                        List.of(
                                new OreScanner.Target(
                                        ore, new ItemStack(Items.RAW_GOLD), 1, 1, 1))),
                "Tankless drill bypassed wet recipe");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void manualMiningDepletesLockedDepositAndSuppressesOrdinaryOreLoot(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        for (Block ore : new Block[] {Blocks.GOLD_ORE, Blocks.IRON_ORE}) {
            var drops = new ArrayList<ItemEntity>();
            drops.add(
                    new ItemEntity(
                            level,
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            new ItemStack(
                                    ore == Blocks.GOLD_ORE ? Items.RAW_GOLD : Items.RAW_IRON)));
            var event =
                    new BlockDropsEvent(
                            level,
                            pos,
                            ore.defaultBlockState(),
                            null,
                            drops,
                            player,
                            player.getMainHandItem());
            NeoForge.EVENT_BUS.post(event);
            helper.assertTrue(
                    ore == Blocks.GOLD_ORE
                            ? drops.isEmpty() && event.getDroppedExperience() == 0
                            : drops.size() == 1,
                    "Manual drop policy did not distinguish fluid and dry recipes");
        }
        deposit(level, pos, Blocks.GOLD_ORE, 100);
        var state = level.getBlockState(pos);
        state.getBlock().playerWillDestroy(level, pos, state, player);
        boolean removed =
                state.onDestroyedByPlayer(level, pos, player, true, state.getFluidState());
        helper.assertTrue(
                !removed
                        && level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())
                        && OreDepositData.remainingOreAt(level, pos) == 99,
                "Pickaxe removed a fluid-locked deposit or depleted more than one unit");
        helper.assertTrue(
                level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(0.5D)).isEmpty(),
                "Locked deposit yielded hand-mining loot");
        helper.assertTrue(
                OreDepositData.mineByPlayer(level, pos, level.getBlockState(pos), player)
                        && level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())
                        && OreDepositData.remainingOreAt(level, pos) == 98
                        && level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(0.5D))
                                .isEmpty(),
                "Area-mining tool bypassed the fluid-locked deposit policy");
        deposit(level, pos, Blocks.GOLD_ORE, 2);
        for (int remaining = 1; remaining >= 0; remaining--) {
            helper.assertTrue(
                    OreDepositData.mineByPlayer(level, pos, level.getBlockState(pos), player)
                            && OreDepositData.remainingOreAt(level, pos) == remaining
                            && level.getBlockState(pos)
                                    .is(
                                            remaining > 0
                                                    ? ModBlocks.ORE_DEPOSIT.get()
                                                    : ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())
                            && level.getEntitiesOfClass(
                                            ItemEntity.class, new AABB(pos).inflate(0.5D))
                                    .isEmpty(),
                    "Area-mining tool failed gradual depletion without loot");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void survivalPickaxeActuallyBreaksWetOreWithoutLoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        var player =
                net.neoforged.neoforge.common.util.FakePlayerFactory.get(
                        level,
                        new com.mojang.authlib.GameProfile(
                                java.util.UUID.randomUUID(), "drill-loot-test"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        for (boolean asDeposit : new boolean[] {false, true}) {
            if (asDeposit) deposit(level, pos, Blocks.GOLD_ORE, 100);
            else level.setBlock(pos, Blocks.GOLD_ORE.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(
                    DrillMiningRecipes.requiresFluidOutsideDrills(
                            level, DrillMiningRecipes.sourceState(level, pos)),
                    "Wet recipe not active before real pickaxe destruction");
            player.gameMode.destroyBlock(pos);
            helper.assertTrue(
                    asDeposit
                            ? level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())
                            : level.getBlockState(pos).isAir(),
                    "Survival pickaxe changed the physical-block policy");
            helper.assertTrue(
                    level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(0.75D))
                            .isEmpty(),
                    "Real survival breaking produced wet-ore loot");
            if (asDeposit) {
                helper.assertTrue(
                        OreDepositData.remainingOreAt(level, pos) == 99,
                        "Wet deposit lost more than one reserve unit");
                deposit(level, pos, Blocks.GOLD_ORE, 2);
                for (int remaining = 1; remaining >= 0; remaining--) {
                    player.gameMode.destroyBlock(pos);
                    helper.assertTrue(
                            OreDepositData.remainingOreAt(level, pos) == remaining
                                    && level.getBlockState(pos)
                                            .is(
                                                    remaining > 0
                                                            ? ModBlocks.ORE_DEPOSIT.get()
                                                            : ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())
                                    && level.getEntitiesOfClass(
                                                    ItemEntity.class, new AABB(pos).inflate(0.75D))
                                            .isEmpty(),
                            "Real survival breaking failed gradual depletion without loot");
                }
            }
        }
        level.setBlock(pos, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        player.gameMode.destroyBlock(pos);
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(0.75D));
        helper.assertTrue(
                level.getBlockState(pos).isAir()
                        && drops.stream().anyMatch(drop -> drop.getItem().is(Items.RAW_IRON)),
                "Dry ore lost normal pickaxe loot or the test did not exercise actual harvesting");
        drops.forEach(ItemEntity::discard);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fluidPortsSupportBothDirectionsAndPersistDrainedAmounts(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            level.setBlock(
                    origin,
                    ModBlocks.ADVANCED_MINING_DRILL
                            .get()
                            .defaultBlockState()
                            .setValue(AbstractDrillBlock.FACING, facing),
                    Block.UPDATE_ALL);
            ModBlocks.ADVANCED_STRUCTURE.placeParts(level, origin, facing);
            var be = (AbstractEnergyDrillBlockEntity) level.getBlockEntity(origin);
            for (int x : new int[] {0, 4}) {
                BlockPos port = ModBlocks.ADVANCED_STRUCTURE.offset(origin, facing, x, 0, 2);
                Direction external =
                        x == 0 ? facing.getClockWise().getOpposite() : facing.getClockWise();
                var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, port, external);
                helper.assertTrue(
                        handler != null
                                && level.getCapability(
                                                Capabilities.FluidHandler.BLOCK,
                                                port,
                                                external.getOpposite())
                                        == null,
                        "Fluid port exposed the wrong face");
                helper.assertTrue(
                        handler.fill(
                                                new FluidStack(Fluids.WATER, 500),
                                                IFluidHandler.FluidAction.SIMULATE)
                                        == 500
                                && be.getFluidAmount(0) == 0,
                        "Simulated input changed tank");
                handler.fill(new FluidStack(Fluids.WATER, 500), IFluidHandler.FluidAction.EXECUTE);
                helper.assertTrue(
                        handler.drain(
                                        new FluidStack(Fluids.LAVA, 100),
                                        IFluidHandler.FluidAction.EXECUTE)
                                .isEmpty(),
                        "Output drained the wrong fluid");
                helper.assertTrue(
                        handler.drain(100, IFluidHandler.FluidAction.SIMULATE).getAmount() == 100
                                && be.getFluidAmount(0) == 500,
                        "Simulated output changed tank");
                handler.drain(100, IFluidHandler.FluidAction.EXECUTE);
                var saved = be.saveWithoutMetadata(level.registryAccess());
                var restored = new AdvancedMiningDrill.BlockEntity(origin, be.getBlockState());
                restored.loadWithComponents(saved, level.registryAccess());
                helper.assertTrue(
                        restored.getFluidAmount(0) == 400, "Output drain was not persisted");
                var sink = new net.neoforged.neoforge.fluids.capability.templates.FluidTank(1000);
                helper.assertTrue(
                        FluidUtil.tryFluidTransfer(sink, handler, 400, true).getAmount() == 400
                                && be.getFluidAmount(0) == 0
                                && sink.getFluidAmount() == 400,
                        "Pipe transfer lost or duplicated fluid");
            }
            ModBlocks.ADVANCED_STRUCTURE.removeParts(level, origin, facing);
            level.removeBlock(origin, false);
        }
        helper.succeed();
    }

    private static void deposit(ServerLevel level, BlockPos pos, Block ore, int amount) {
        level.setBlock(pos, ModBlocks.ORE_DEPOSIT.get().defaultBlockState(), Block.UPDATE_ALL);
        OreDepositData.set(
                level,
                pos,
                OreDepositStonePalette.indexOf(level, Blocks.STONE),
                OreDepositOrePalette.indexOf(level, ore),
                amount,
                0,
                0,
                2);
    }

    private static final class TestDrill extends AbstractEnergyDrillBlockEntity {
        TestDrill(BlockPos pos, ServerLevel level, int units) {
            super(
                    ModBlockEntities.ADVANCED_MINING_DRILL.get(),
                    pos,
                    ModBlocks.ADVANCED_MINING_DRILL.get().defaultBlockState(),
                    new MiningDrillTier(5, 1, units, new ItemStack(Items.DIAMOND_PICKAXE), 4),
                    AdvancedMiningDrill.ENERGY,
                    ModBlocks.ADVANCED_STRUCTURE,
                    0,
                    10000);
            setLevel(level);
        }

        @Override
        protected DrillBit drillBit() {
            return AdvancedMiningDrill.DRILL_BIT;
        }
    }
}
