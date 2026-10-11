package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OreDepositStorageGameTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void chunkStorageKeepsMinedAmountsAndInvalidatesSnapshots(GameTestHelper helper) throws Exception {
        for(int mode=0;mode<3;mode++) {
            OreDepositChunkData data=new OreDepositChunkData();
            for(int i=0;i<1792;i++) {
                int amount=32+i%(mode==0?32:mode==1?512:1792);
                data.put(position(i),1,7,amount,amount+200,0,0,i&3);
            }
            CompoundTag baseline=new CompoundTag();var snapshot=data.snapshot();
            baseline.putIntArray("Keys",snapshot.keys());baseline.putLongArray("Values",snapshot.values());
            CompoundTag saved=data.saveTag();
            helper.assertTrue(saved.contains("ValuePalette")== (mode!=2),"Adaptive value palette chose the wrong representation");
            OreDepositChunkData restored=new OreDepositChunkData();restored.loadTag(binaryRoundTrip(saved));
            for(int i=0;i<1792;i++)helper.assertTrue(data.get(position(i)).equals(restored.get(position(i))),
                    "Chunk save changed an ore amount, initial amount, palette index or tier");
            int before=data.get(position(0)).remainingOre();
            data.put(position(0),1,7,1,before+200,0,0,0);
            OreDepositChunkData after=new OreDepositChunkData();after.loadTag(data.saveTag());
            helper.assertTrue(after.get(position(0)).remainingOre()==1,"Mining reused a stale disk snapshot");
            restored.loadTag(saved);
            helper.assertTrue(restored.get(position(0)).remainingOre()==before,"A later mutation changed a previous save");
            data.remove(position(0));after.loadTag(data.saveTag());
            helper.assertTrue(after.size()==1791&&after.get(position(0))==null,"Removal was not saved");
            System.out.printf(java.util.Locale.ROOT,"Ore chunk NBT mode %d: raw %d -> %d bytes; gzip %d -> %d bytes%n",
                    mode,bytes(baseline,false).length,bytes(saved,false).length,bytes(baseline,true).length,bytes(saved,true).length);
        }
        OreDepositChunkData edges=new OreDepositChunkData();
        for(int y:new int[]{-32768,-256,-1,0,512,32767})edges.put(new BlockPos(-16385,y,-8193),2,5,0,5000,0,0,3);
        OreDepositChunkData restored=new OreDepositChunkData();restored.loadTag(binaryRoundTrip(edges.saveTag()));
        for(int y:new int[]{-32768,-256,-1,0,512,32767})helper.assertTrue(
                edges.get(new BlockPos(-16385,y,-8193)).equals(restored.get(new BlockPos(-16385,y,-8193))),"Signed Y or exhausted ore changed");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void confirmedIndexPackedStorageKeepsAllRecordFields(GameTestHelper helper) throws Exception {
        var registries=helper.getLevel().registryAccess();
        var index=ConfirmedDepositIndex.load(new CompoundTag(),registries);
        List<ConfirmedDeposit> expected=new ArrayList<>();
        Direction[] directions={null,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST};
        for(int i=0;i<5000;i++) {
            var deposit=new ConfirmedDeposit(SeedMixer.mix(i),ResourceLocation.withDefaultNamespace(i%2==0?"iron_ore":"copper_ore"),
                    OreDepositTier.fromIndex(i&3).orElseThrow(),new BlockPos(-10000+i*2,-256+i%768,-2000+i%97),
                    1+i%20000,1+i%12,directions[i%5],i%5!=0);
            index.add(deposit);expected.add(deposit);
        }
        CompoundTag baseline=baselineIndex(expected);
        CompoundTag saved=index.save(new CompoundTag(),registries);
        var loaded=ConfirmedDepositIndex.load(binaryRoundTrip(saved),registries);
        var actual=new java.util.HashSet<>(loaded.recordsWithin(new BlockPos(-5000,0,-2000),20000,null));
        helper.assertTrue(actual.size()==5000&&actual.equals(new java.util.HashSet<>(expected)),"Packed index lost record fields or ore IDs");
        helper.assertTrue(bytes(saved,false).length<bytes(baseline,false).length/2,"Packed index failed to reduce NBT size");
        System.out.printf(java.util.Locale.ROOT,"Ore index NBT, 5000 deposits: raw %d -> %d bytes; gzip %d -> %d bytes%n",
                bytes(baseline,false).length,bytes(saved,false).length,bytes(baseline,true).length,bytes(saved,true).length);
        long[] oldCost=measure(() -> baselineIndex(expected)),newCost=measure(() -> index.save(new CompoundTag(),registries));
        helper.assertTrue(newCost[1]<oldCost[1]/2,"Packed index did not reduce save allocations");
        System.out.printf(java.util.Locale.ROOT,"Ore index NBT preparation, 5000 deposits: %.3f -> %.3f ms; %d -> %d allocated bytes%n",
                oldCost[0]/1e6,newCost[0]/1e6,oldCost[1],newCost[1]);
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void obsoleteOreStorageIsRejectedInsteadOfSilentlyLosingEntries(GameTestHelper helper) {
        CompoundTag old=new CompoundTag();old.putIntArray("Keys",new int[]{0});old.putLongArray("Values",new long[]{1});
        boolean chunkRejected=false,indexRejected=false;
        try {new OreDepositChunkData().loadTag(old);} catch(IllegalArgumentException expected) {chunkRejected=true;}
        try {ConfirmedDepositIndex.load(old,helper.getLevel().registryAccess());} catch(IllegalArgumentException expected) {indexRejected=true;}
        helper.assertTrue(chunkRejected&&indexRejected,"Old storage was accepted or erased silently");
        helper.succeed();
    }

    private static BlockPos position(int i) {return new BlockPos(-16384+(i&15),-256+i/256,-8192+((i>>>4)&15));}
    private static CompoundTag baselineIndex(List<ConfirmedDeposit> deposits) {
        ListTag records=new ListTag();
        for(var deposit:deposits) {
            CompoundTag old=new CompoundTag();old.putLong("Id",deposit.depositId());old.putString("Ore",deposit.oreId().toString());
            old.putInt("Tier",deposit.tier().index());old.putInt("X",deposit.center().getX());old.putInt("Y",deposit.center().getY());old.putInt("Z",deposit.center().getZ());
            old.putInt("PlacedBlocks",deposit.placedBlocks());old.putInt("OccupiedSections",deposit.occupiedSections());
            if(deposit.wallDirection()!=null)old.putInt("WallDirection",deposit.wallDirection().get3DDataValue());
            old.putBoolean("ExposedToCave",deposit.exposedToCave());records.add(old);
        }
        CompoundTag result=new CompoundTag();result.put("Deposits",records);return result;
    }
    private static volatile CompoundTag benchmarkSink;
    private static long[] measure(java.util.function.Supplier<CompoundTag> save) {
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().threadId();
        for(int i=0;i<5;i++)benchmarkSink=save.get();
        long[] time=new long[10],allocated=new long[10];
        for(int i=0;i<time.length;i++) {
            long bytes=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();benchmarkSink=save.get();
            time[i]=System.nanoTime()-start;allocated[i]=bean.getThreadAllocatedBytes(tid)-bytes;
        }
        java.util.Arrays.sort(time);java.util.Arrays.sort(allocated);
        return new long[]{time[time.length/2],allocated[allocated.length/2]};
    }
    private static byte[] bytes(CompoundTag tag,boolean compressed) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(compressed)NbtIo.writeCompressed(tag,out);else NbtIo.write(tag,new DataOutputStream(out));
        return out.toByteArray();
    }
    private static CompoundTag binaryRoundTrip(CompoundTag tag) throws Exception {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes(tag,false))),NbtAccounter.unlimitedHeap());
    }
}
