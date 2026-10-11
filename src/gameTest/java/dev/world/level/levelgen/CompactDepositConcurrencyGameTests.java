package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.registry.ModAttachments;
import dev.registry.ModBlockTags;
import dev.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompactDepositConcurrencyGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void blockCallbackCanWaitForNeighbourWorldgen(GameTestHelper helper) throws Exception {
        ServerLevel level=helper.getLevel();
        var plan=fixture(helper,new BlockPos(-12273,80,-12288));
        var index=ConfirmedDepositIndex.get(level);
        helper.assertTrue(index.registerStartingForcedPlan(plan),"Forced plan not registered");
        helper.assertTrue(!index.forcedPlanReady(plan.candidate().depositId()),"Initial attempt was prematurely ready");
        helper.assertTrue(!savedPlanExists(level,index,plan.candidate().depositId()),"Uncommitted attempt was persisted");
        var chunks=plan.slices().keySet().stream().map(ChunkPos::new)
                .sorted(Comparator.comparingLong(ChunkPos::toLong)).toList();
        helper.assertTrue(chunks.size()==2,"Fixture must cross a negative-coordinate chunk boundary");
        var executor=Executors.newSingleThreadExecutor(task -> {
            Thread thread=new Thread(task,"deposit-worldgen-regression");
            thread.setDaemon(true);return thread;
        });
        AtomicInteger callbacks=new AtomicInteger(),writes=new AtomicInteger();
        try {
            for(ChunkPos chunk:chunks) {
                AtomicInteger sliceWrites=new AtomicInteger();
                WorldGenLevel withCallback=proxy(level,(pos,state) -> {
                    if(!state.is(ModBlocks.ORE_DEPOSIT.get()))return true;
                    writes.incrementAndGet();
                    if(sliceWrites.getAndIncrement()!=0)return true;
                    callbacks.incrementAndGet();
                    // Sable's setBlock callback waits for a neighbouring chunk to finish
                    // decoration. That worker needs the index and gravel guard, and may
                    // discover this same pending slice while the first commit is active.
                    executor.submit(() -> {
                        if(index.forcedPlansFor(chunk).stream().noneMatch(p -> p==plan))
                            throw new AssertionError("Pending forced geometry disappeared during commit");
                        if(index.hasSlice(plan.candidate().depositId(),chunk))
                            throw new AssertionError("Incomplete slice was confirmed");
                        if(!DepositWorldgenGuard.blocksGravel(level,pos))
                            throw new AssertionError("In-flight slice lost gravel protection");
                        for(ChunkPos pending:chunks)OreDepositFeature.placePendingForcedSlices(level,pending);
                        OreDepositFeature.placeCompactSlice(level,chunk,plan,true);
                        return null;
                    }).get(3,TimeUnit.SECONDS);
                    return true;
                });
                OreDepositFeature.placeCompactSlice(withCallback,chunk,plan,true);
                if(chunk.equals(chunks.getFirst())) {
                    helper.assertTrue(index.forcedPlanReady(plan.candidate().depositId()),"First commit did not enable continuation");
                    helper.assertTrue(savedPlanExists(level,index,plan.candidate().depositId()),"Committed continuation was not persisted");
                }
            }
        } finally {
            executor.shutdownNow();
        }
        helper.assertTrue(callbacks.get()==2,"Did not exercise both chunk callbacks");
        helper.assertTrue(writes.get()==plan.cells().size(),"Reentrant placement duplicated block writes");
        assertComplete(helper,plan);
        helper.assertTrue(chunks.stream().allMatch(chunk -> index.forcedPlansFor(chunk).isEmpty()),
                "Completed forced plan was not retired");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void failedOrThrowingWriteReleasesSliceForRetry(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();
        var plan=fixture(helper,new BlockPos(-12529,80,-12288));
        var index=ConfirmedDepositIndex.get(level);
        helper.assertTrue(index.registerForcedPlan(plan),"Forced retry plan not registered");
        ChunkPos chunk=new ChunkPos(plan.candidate().center());
        AtomicInteger writes=new AtomicInteger();
        WorldGenLevel failing=proxy(level,(pos,state) -> !state.is(ModBlocks.ORE_DEPOSIT.get()) || writes.incrementAndGet()!=2);
        OreDepositFeature.placeCompactSlice(failing,chunk,plan,true);
        helper.assertTrue(!index.hasSlice(plan.candidate().depositId(),chunk),"Failed write was confirmed");
        for(int i:plan.indices(chunk)) {
            var cell=plan.cells().get(i);BlockPos pos=plan.candidate().center().offset(cell.x(),cell.y(),cell.z());
            helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE),"Failed batch did not roll back");
            helper.assertTrue(level.getChunkAt(pos).getData(ModAttachments.ORE_DEPOSITS).get(pos)==null,
                    "Failed batch left resource metadata");
        }
        WorldGenLevel throwing=proxy(level,(pos,state) -> {throw new IllegalStateException("Simulated block callback failure");});
        try {
            OreDepositFeature.placeCompactSlice(throwing,chunk,plan,true);
            throw new AssertionError("Block callback was not reached after failed write");
        } catch(IllegalStateException expected) {
            helper.assertTrue(expected.getMessage().equals("Simulated block callback failure"),"Unexpected write exception");
        }
        for(long key:plan.slices().keySet())OreDepositFeature.placePendingForcedSlices(level,new ChunkPos(key));
        assertComplete(helper,plan);
        helper.succeed();
    }

    private static OreDepositFeature.CompactPlan fixture(GameTestHelper helper,BlockPos center) {
        long id=SeedMixer.mix(center.asLong()^helper.absolutePos(BlockPos.ZERO).asLong());
        var candidate=new DepositCandidate(id,ResourceLocation.withDefaultNamespace("iron_ore"),OreDepositTier.MEDIUM,
                center,4,1,1,id,null,8,center.getX()>>4,center.getZ()>>4);
        var cells=new ArrayList<CompactDepositGeometry.Cell>();
        for(int x=0;x<4;x++)for(int y=0;y<2;y++)cells.add(new CompactDepositGeometry.Cell(x,y,0,1));
        var target=OreConfiguration.target(new TagMatchTest(ModBlockTags.STONES),Blocks.IRON_ORE.defaultBlockState());
        int[] amounts=new int[]{100,101,102,103,104,105,106,107};
        for(var cell:cells) {
            BlockPos pos=center.offset(cell.x(),cell.y(),cell.z());
            helper.getLevel().getChunkAt(pos).getData(ModAttachments.ORE_DEPOSITS).remove(pos);
            helper.getLevel().setBlock(pos,Blocks.STONE.defaultBlockState(),Block.UPDATE_NONE);
        }
        return new OreDepositFeature.CompactPlan(candidate,cells,cells.stream().map(ignored -> target).toList(),amounts);
    }

    private static void assertComplete(GameTestHelper helper,OreDepositFeature.CompactPlan plan) {
        int total=0;
        for(var cell:plan.cells()) {
            BlockPos pos=plan.candidate().center().offset(cell.x(),cell.y(),cell.z());
            var entry=helper.getLevel().getChunkAt(pos).getData(ModAttachments.ORE_DEPOSITS).get(pos);
            helper.assertTrue(entry!=null,"Retry/reentrant callback lost a resource entry");
            total+=entry.remainingOre();
        }
        helper.assertTrue(total==plan.totalOre(),"Retry/reentrant callback changed reserve");
        var index=ConfirmedDepositIndex.get(helper.getLevel());
        var record=index.recordsWithin(plan.candidate().center(),1,null).stream()
                .filter(value -> value.depositId()==plan.candidate().depositId()).findFirst().orElseThrow();
        helper.assertTrue(record.placedBlocks()==plan.cells().size(),"Retry/reentrant callback duplicated confirmation");
    }

    private static boolean savedPlanExists(ServerLevel level,ConfirmedDepositIndex index,long id) {
        var plans=index.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess())
                .getList("ForcedCompactPlans",net.minecraft.nbt.Tag.TAG_COMPOUND);
        for(int i=0;i<plans.size();i++)if(plans.getCompound(i).getLong("Id")==id)return true;
        return false;
    }

    @FunctionalInterface
    private interface BeforeWrite {
        boolean accept(BlockPos pos,net.minecraft.world.level.block.state.BlockState state) throws Exception;
    }

    private static WorldGenLevel proxy(ServerLevel level,BeforeWrite beforeWrite) {
        return (WorldGenLevel)Proxy.newProxyInstance(CompactDepositConcurrencyGameTests.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class},(proxy,method,args) -> {
                    if(method.getName().equals("setBlock") && !beforeWrite.accept((BlockPos)args[0],
                            (net.minecraft.world.level.block.state.BlockState)args[1]))return false;
                    try{return method.invoke(level,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                });
    }
}
