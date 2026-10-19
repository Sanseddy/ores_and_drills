package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.registry.ModAttachments;
import dev.registry.ModBlockTags;
import dev.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompactDepositGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void chunkSlicesKeepBlocksReservesAndIdentity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (boolean large : new boolean[]{false, true}) {
            List<Integer> forward = fixture(helper, level, large, false);
            List<Integer> reverse = fixture(helper, level, large, true);
            helper.assertTrue(forward.equals(reverse), "Chunk order changed the stored resource distribution");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void overlappingPlansChooseTheSameWinnerBeforeAnyWrites(GameTestHelper helper) {
        for(boolean reverse:new boolean[]{false,true}) {
            BlockPos center=new BlockPos(reverse?-832:-512,70,-512);
            long base=SeedMixer.mix(helper.absolutePos(BlockPos.ZERO).asLong() ^ center.asLong()) & ~1L;
            OreDepositFeature.CompactPlan winner=plan(19,false,center,base);
            OreDepositFeature.CompactPlan loser=plan(27,false,center.offset(2,0,0),base|1L);
            prepare(helper.getLevel(),winner);prepare(helper.getLevel(),loser);
            helper.assertTrue(OreDepositFeature.compactPlanWinsAgainst(winner,loser),"Wrong priority winner");
            helper.assertTrue(!OreDepositFeature.compactPlanWinsAgainst(loser,winner),"Both conflicting plans accepted");
            Set<ChunkPos> chunks=new HashSet<>();
            for(var p:List.of(winner,loser))for(var c:p.cells())chunks.add(new ChunkPos(p.candidate().center().offset(c.x(),c.y(),c.z())));
            List<ChunkPos> order=chunks.stream().sorted(Comparator.comparingLong(ChunkPos::toLong)).toList();
            if(reverse)order=order.reversed();
            for(var chunk:order)for(var p:reverse?List.of(loser,winner):List.of(winner,loser)) {
                if(OreDepositFeature.compactPlanWinsAgainst(p,p==winner?loser:winner))OreDepositFeature.placeCompactSlice(helper.getLevel(),chunk,p,true);
            }
            int units=0;
            for(var c:winner.cells()) {
                BlockPos pos=center.offset(c.x(),c.y(),c.z());
                var entry=helper.getLevel().getChunkAt(pos).getData(ModAttachments.ORE_DEPOSITS).get(pos);
                helper.assertTrue(entry!=null,"Conflict cut the winning patch"); units+=entry.remainingOre();
            }
            helper.assertTrue(units==java.util.Arrays.stream(winner.amounts()).sum(),"Conflict lost reserve");
            helper.assertTrue(ConfirmedDepositIndex.get(helper.getLevel()).recordsWithin(center,16,null).stream()
                    .noneMatch(record -> record.depositId()==loser.candidate().depositId()),"Loser was confirmed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void forcedLedgerAndMultiChunkPersistenceStayCompatible(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();
        for(boolean large:new boolean[]{false,true}) {
            BlockPos origin=new BlockPos(-2048,large?110:70,large?-2304:-2048);
            long id=SeedMixer.mix(origin.asLong()^helper.absolutePos(BlockPos.ZERO).asLong());
            var plan=plan(14267,large,origin,id);prepare(level,plan);
            var index=ConfirmedDepositIndex.get(level);
            String key="large-review:"+id;
            var ledger=DataDrivenDepositLedger.get(level);
            var rejected=ledger.tryReserve(key,1,origin,0);
            helper.assertTrue(rejected!=null,"Could not reserve forced fixture");
            helper.assertTrue(ledger.tryReserve(key,1,origin,0)==null,"Pending slot did not count");rejected.release();
            var reservation=ledger.tryReserve(key,1,origin,0);
            helper.assertTrue(reservation!=null,"Failed attempt leaked reservation");
            helper.assertTrue(index.registerForcedPlan(plan),"Forced plan was already registered");
            List<ChunkPos> chunks=plan.cells().stream().map(c->new ChunkPos(origin.offset(c.x(),c.y(),c.z()))).distinct()
                    .sorted(Comparator.comparingLong(ChunkPos::toLong)).toList();
            helper.assertTrue(chunks.size()>=(large?5:4),"Forced body must span several chunks");
            OreDepositFeature.placePendingForcedSlices(level,chunks.getFirst());reservation.commit();
            helper.assertTrue(index.hasSlice(id,chunks.getFirst()),"First forced slice was not committed");
            CompoundTag saved=index.save(new CompoundTag(),level.registryAccess());
            var restored=ConfirmedDepositIndex.load(saved,level.registryAccess());
            var pending=restored.forcedPlansFor(chunks.getLast());
            helper.assertTrue(pending.size()==1,"Pending continuation lost on restart");
            helper.assertTrue(java.util.Arrays.equals(plan.amounts(),pending.getFirst().amounts()),"Saved plan changed resource budget");
            var reloaded=pending.getFirst();
            for(int i=0;i<plan.cells().size();i++) {
                helper.assertTrue(plan.cells().get(i).key().equals(reloaded.cells().get(i).key()),"Packed forced coordinates changed");
                helper.assertTrue(plan.targets().get(i).state.equals(reloaded.targets().get(i).state),"Packed forced target changed");
            }
            helper.assertTrue(plan.slices().keySet().equals(reloaded.slices().keySet()),"Reload changed the chunk index");
            // A caller owns the returned NBT; mutating it must not corrupt the cached encoding.
            var savedPlans=saved.getList("ForcedCompactPlans",net.minecraft.nbt.Tag.TAG_COMPOUND);
            for(int i=0;i<savedPlans.size();i++)savedPlans.getCompound(i).getByteArray("PackedAmounts")[0]=0;
            var another=ConfirmedDepositIndex.load(index.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
            helper.assertTrue(java.util.Arrays.equals(plan.amounts(),another.forcedPlansFor(chunks.getLast()).getFirst().amounts()),
                    "Cached forced NBT was exposed to mutation");
            for(int i=1;i<chunks.size();i++)OreDepositFeature.placePendingForcedSlices(level,chunks.get(i));
            for(ChunkPos chunk:chunks)helper.assertTrue(index.hasSlice(id,chunk)&&index.forcedPlansFor(chunk).isEmpty(),"Forced continuation was not completed/retired");
            int sum=0;for(var c:plan.cells())sum+=level.getChunkAt(origin.offset(c.x(),c.y(),c.z())).getData(ModAttachments.ORE_DEPOSITS)
                    .get(origin.offset(c.x(),c.y(),c.z())).remainingOre();
            helper.assertTrue(sum==java.util.Arrays.stream(plan.amounts()).sum(),"Forced resource sum changed");
            helper.assertTrue(ledger.placedCount(key)==1&&ledger.tryReserve(key,1,origin,0)==null,"Forced count changed");
        }
        helper.succeed();
    }

    private static OreDepositFeature.CompactPlan plan(long seed,boolean large,BlockPos center,long id) {
        var geometry=new CompactDepositGeometry(seed,large,1);
        var candidate=new DepositCandidate(id,ResourceLocation.withDefaultNamespace("iron_ore"),large?OreDepositTier.LARGE:OreDepositTier.MEDIUM,
                center,geometry.radiusX(),geometry.radiusZ(),geometry.radiusY(),seed,null,geometry.cells().size(),center.getX()>>4,center.getZ()>>4);
        int total=CompactDepositGeometry.totalOre(seed,large,0,1);
        var cells=CompactDepositGeometry.selectOreCells(geometry.cells(),CompactDepositGeometry.oreBlockBudget(total),center.getX(),center.getZ());
        int[] amounts=DepositTierMath.distributeOre(total,cells.stream().mapToDouble(CompactDepositGeometry.Cell::weight).toArray(),CompactDepositGeometry.maximumBlockReserve(total,cells.size()));
        var target=OreConfiguration.target(new TagMatchTest(ModBlockTags.STONES),Blocks.IRON_ORE.defaultBlockState());
        return new OreDepositFeature.CompactPlan(candidate,cells,cells.stream().map(ignored -> target).toList(),amounts);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void realNoiseTerrainPlansAreCachedAndHeightSafe(GameTestHelper helper) throws Exception {
        // GameTest's overworld is flat; its standard Nether uses the real noise generator.
        var dimension=net.minecraft.world.level.Level.NETHER;
        ServerLevel level=helper.getLevel().getServer().getLevel(dimension);
        helper.assertTrue(level!=null,"Missing isolated noise-terrain review dimension");
        helper.assertTrue(level.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator,"Benchmark used flat terrain");
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().threadId();
        List<long[]> profiles=new ArrayList<>();int accepted=0;
        for(int tier:new int[]{OreDepositFeature.TIER_MEDIUM,OreDepositFeature.TIER_LARGE}) for(int i=0;i<12;i++) {
            int x=i-6,z=-12,tile=DepositLayouts.forTier(OreDepositTier.fromIndex(tier).orElseThrow()).cellSizeBlocks();
            var biome=level.getUncachedNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x*tile+tile/2),28,
                    net.minecraft.core.QuartPos.fromBlock(z*tile+tile/2)).unwrapKey().orElseThrow().location();
            var settings=new OreDepositFeature.DataDrivenSettings(List.of(dimension.location().toString()),108,116,
                    OreDepositFeature.PlacementMode.UNDERGROUND,false,1,0,0x524556494557L,1,1,1);
            var target=OreConfiguration.target(new TagMatchTest(ModBlockTags.STONES),Blocks.IRON_ORE.defaultBlockState());
            var config=new OreDepositFeature.Configuration(List.of(target),tier,1,1,1,1,Optional.of(biome),Optional.of(settings));
            long allocated=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();
            var plan=OreDepositFeature.cachedCompactPlan(level,config,x,z);
            long nanos=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(tid)-allocated;
            if(plan.isPresent()) {
                accepted++;var body=plan.get();
                int quantity=java.util.Arrays.stream(body.amounts()).sum();
                helper.assertTrue(java.util.Arrays.stream(body.amounts()).max().orElseThrow()<=CompactDepositGeometry.maximumBlockReserve(quantity,body.cells().size()),"A terrain-filtered block monopolizes the reserve");
                helper.assertTrue(quantity>=(tier==OreDepositFeature.TIER_LARGE?400_000:180_000)
                        && quantity<=(tier==OreDepositFeature.TIER_LARGE?2_000_000:360_000),"Terrain plan reserve outside tier range");
                for(var cell:body.cells())helper.assertTrue(!level.isOutsideBuildHeight(body.candidate().center().offset(cell.x(),cell.y(),cell.z())),"Noise plan escaped world height");
            }
            start=System.nanoTime();
            for(int hit=0;hit<100;hit++)helper.assertTrue(OreDepositFeature.cachedCompactPlan(level,config,x,z)==plan,"Plan cache rebuilt a result");
            long hitNanos=(System.nanoTime()-start)/100;
            profiles.add(new long[]{tier,i,plan.isPresent()?1:0,nanos,bytes,hitNanos});
        }
        helper.assertTrue(accepted>=1,"Most noise-terrain candidates were unexpectedly rejected: "+accepted);
        long[] stats=OreDepositFeature.compactPlanCacheStatistics(level);
        helper.assertTrue(stats[0]<=96&&stats[1]>=2400,"Plan cache missed repeated terrain plans");
        java.nio.file.Path report=java.nio.file.Path.of("../reports/factorio-deposits/terrain-plan-profile.csv");
        java.nio.file.Files.createDirectories(report.getParent());
        StringBuilder csv=new StringBuilder("tier,index,accepted,first_call_ns,thread_allocated_bytes,cached_call_ns\n");
        for(long[] row:profiles)csv.append(row[0]==OreDepositFeature.TIER_MEDIUM?"MEDIUM":"LARGE").append(',').append(row[1]).append(',').append(row[2]).append(',').append(row[3]).append(',').append(row[4]).append(',').append(row[5]).append('\n');
        java.nio.file.Files.writeString(report,csv);
        System.out.printf("Noise-terrain plans: %d/24 accepted, cache entries=%d hits=%d misses=%d%n",accepted,stats[0],stats[1],stats[2]);
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void collisionChecksDoNotBuildTerrainForLowerPriorityCandidates(GameTestHelper helper) {
        ServerLevel level=helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        var winner=plan(14267,false,new BlockPos(-12288,112,-12288),0);
        long[] before=OreDepositFeature.compactPlanCacheStatistics(level);
        long start=System.nanoTime();
        helper.assertTrue(OreDepositFeature.compactPlanWins(level,winner),"Highest-priority remote plan lost arbitration");
        long elapsed=System.nanoTime()-start;
        helper.assertTrue(java.util.Arrays.equals(before,OreDepositFeature.compactPlanCacheStatistics(level)),
                "Collision check built full terrain plans for candidates that cannot win");
        System.out.printf(java.util.Locale.ROOT,"Candidate arbitration: %.3f ms, zero full-plan loads%n",elapsed/1e6);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fluidHostIsPreservedWithoutDiscardingTheChunk(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();
        BlockPos center=new BlockPos(-1536,70,-1536);
        var plan=plan(14267,true,center,SeedMixer.mix(helper.absolutePos(BlockPos.ZERO).asLong()^center.asLong()));
        prepare(level,plan);
        Set<ChunkPos> chunks=new HashSet<>();for(var c:plan.cells())chunks.add(new ChunkPos(center.offset(c.x(),c.y(),c.z())));
        var cut=plan.cells().getLast();BlockPos pos=center.offset(cut.x(),cut.y(),cut.z());
        level.setBlock(pos,Blocks.WATER.defaultBlockState(),Block.UPDATE_NONE);
        for(var chunk:chunks)OreDepositFeature.placeCompactSlice(level,chunk,plan,true);
        helper.assertTrue(level.getBlockState(pos).is(Blocks.WATER),"Ore replaced fluid");
        int sum=0;for(var c:plan.cells()){
            BlockPos cell=center.offset(c.x(),c.y(),c.z());if(cell.equals(pos))continue;
            var entry=level.getChunkAt(cell).getData(ModAttachments.ORE_DEPOSITS).get(cell);
            helper.assertTrue(entry!=null,"A fluid block discarded the rest of its chunk");sum+=entry.remainingOre();}
        helper.assertTrue(sum==java.util.Arrays.stream(plan.amounts()).sum(),"Clipping fluid lost reserve");
        level.setBlock(pos,Blocks.STONE.defaultBlockState(),Block.UPDATE_NONE);
        for(var chunk:chunks)OreDepositFeature.placeCompactSlice(level,chunk,plan,true);
        helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE),"Completed slice replenished a clipped position");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void gravelInTwoChunksDoesNotCutOffTheirEntireSlices(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();
        // Keep the seed/alignment of the reported regression, using the current size balance.
        List<Integer> forward=gravelFixture(helper,level,false);
        List<Integer> reverse=gravelFixture(helper,level,true);
        helper.assertTrue(forward.equals(reverse),"Clipped reserve depends on chunk generation order");
        helper.succeed();
    }

    private static List<Integer> gravelFixture(GameTestHelper helper,ServerLevel level,boolean reverse) {
        int shift=reverse?512:0;
        BlockPos center=new BlockPos(-1376,-56,2584+shift);
        var body=plan(3551993849755948545L,false,center,SeedMixer.mix(center.asLong()^helper.absolutePos(BlockPos.ZERO).asLong()));
        prepare(level,body);
        int expectedBlocks=body.cells().size(),expectedReserve=body.totalOre();
        var affected=body.slices().entrySet().stream()
                .sorted(Comparator.<java.util.Map.Entry<Long,int[]>>comparingInt(e -> e.getValue().length).reversed()
                        .thenComparingLong(java.util.Map.Entry::getKey)).limit(2).map(e -> new ChunkPos(e.getKey())).toList();
        helper.assertTrue(affected.size()==2,"Fixture must cross two chunks");
        Set<BlockPos> gravel=new HashSet<>();
        for(int sample=0;sample<affected.size();sample++) {
            int placed=0;
            for(var c:body.cells()) {
                BlockPos pos=center.offset(c.x(),c.y(),c.z());
                if(new ChunkPos(pos).equals(affected.get(sample))) {
                    level.setBlock(pos,Blocks.GRAVEL.defaultBlockState(),Block.UPDATE_NONE);gravel.add(pos);
                    if(++placed==(sample==0?6:2))break;
                }
            }
            helper.assertTrue(placed==(sample==0?6:2),"Regression fixture did not reach the missing chunk");
        }
        var order=body.cells().stream().map(c->new ChunkPos(center.offset(c.x(),c.y(),c.z()))).distinct()
                .sorted(Comparator.comparingLong(ChunkPos::toLong)).toList();
        if(reverse)order=order.reversed();
        for(var chunk:order)OreDepositFeature.placeCompactSlice(localChunkLevel(helper,level,chunk),chunk,body,true);
        List<Integer> amounts=new ArrayList<>();List<CompactDepositGeometry.Cell> physical=new ArrayList<>();
        for(var c:body.cells()) {
            BlockPos pos=center.offset(c.x(),c.y(),c.z());
            var entry=level.getChunkAt(pos).getData(ModAttachments.ORE_DEPOSITS).get(pos);
            if(gravel.contains(pos)) {
                helper.assertTrue(level.getBlockState(pos).is(Blocks.GRAVEL)&&entry==null,"Gravel was replaced or gained reserve");continue;
            }
            helper.assertTrue(entry!=null,"Gravel cut off an otherwise valid slice at "+pos);
            helper.assertTrue(entry.remainingOre()>=1&&entry.remainingOre()<=CompactDepositGeometry.MAX_UNITS_PER_BLOCK,"Clipping exceeded block capacity");
            amounts.add(entry.remainingOre());physical.add(c);
        }
        helper.assertTrue(physical.size()==expectedBlocks-8,"Unexpected number of clipped cells");
        helper.assertTrue(CompactDepositGeometry.connected(physical,physical.size()).size()==physical.size(),"Clipping disconnected the patch");
        helper.assertTrue(amounts.stream().mapToInt(Integer::intValue).sum()==expectedReserve,"Clipping lost the chunk shares of Q");
        for(var chunk:order) {
            helper.assertTrue(ConfirmedDepositIndex.get(level).hasSlice(body.candidate().depositId(),chunk),"A clipped slice was not confirmed");
            OreDepositFeature.placeCompactSlice(localChunkLevel(helper,level,chunk),chunk,body,true);
        }
        int sum=physical.stream().mapToInt(c->level.getChunkAt(center.offset(c.x(),c.y(),c.z()))
                .getData(ModAttachments.ORE_DEPOSITS).get(center.offset(c.x(),c.y(),c.z())).remainingOre()).sum();
        helper.assertTrue(sum==expectedReserve,"Repeated clipped slices duplicated reserve");
        return amounts;
    }
    private static void prepare(ServerLevel level,OreDepositFeature.CompactPlan plan) {
        var shape=new CompactDepositGeometry(plan.candidate().shapeSeed(),plan.candidate().tier()==OreDepositTier.LARGE,plan.candidate().geometrySize());
        // Finish natural generation of every touched chunk and its neighbours first: decorating a neighbour
        // later writes its own TINY/SMALL deposits into cells this fixture has already cleared.
        shape.cells().stream().map(c -> new ChunkPos(plan.candidate().center().offset(c.x(),c.y(),c.z()))).distinct()
                .forEach(chunk -> {for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)level.getChunk(chunk.x+dx,chunk.z+dz);});
        for(var c:shape.cells()) {
            BlockPos pos=plan.candidate().center().offset(c.x(),c.y(),c.z());
            level.getChunkAt(pos).getData(ModAttachments.ORE_DEPOSITS).remove(pos);
            // A natural deposit block is protected against plain replacement; the fixture clears it explicitly.
            OreDepositRemovalGuard.runAuthorized(level,pos,() -> level.setBlock(pos,Blocks.STONE.defaultBlockState(),Block.UPDATE_NONE));
        }
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void gravelFeatureCannotSpawnInsidePlannedMediumOrLarge(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();
        var gravel=new OreConfiguration(new TagMatchTest(ModBlockTags.STONES),Blocks.GRAVEL.defaultBlockState(),48);
        for(boolean forced:new boolean[]{false,true}) for(boolean large:new boolean[]{false,true}) {
            BlockPos center=new BlockPos(large?-8448:-8192,80,forced?-8704:-8192);
            var plan=plan(14267,large,center,SeedMixer.mix(center.asLong()^helper.absolutePos(BlockPos.ZERO).asLong()));
            prepare(level,plan);
            var index=ConfirmedDepositIndex.get(level);
            if(forced)helper.assertTrue(index.registerForcedPlan(plan),"Could not register gravel protection fixture");
            else plan.cells().stream().map(c -> new ChunkPos(center.offset(c.x(),c.y(),c.z()))).distinct()
                    .forEach(chunk -> DepositWorldgenGuard.protectPlan(level,plan,chunk));
            var middle=plan.cells().stream().max(Comparator.comparingDouble(CompactDepositGeometry.Cell::weight)).orElseThrow();
            BlockPos inside=center.offset(middle.x(),middle.y(),middle.z());
            for(int attempt=0;attempt<8;attempt++)
                net.minecraft.world.level.levelgen.feature.Feature.ORE.place(gravel,level,level.getChunkSource().getGenerator(),
                        net.minecraft.util.RandomSource.create(attempt),inside);
            for(var cell:plan.cells())helper.assertTrue(level.getBlockState(center.offset(cell.x(),cell.y(),cell.z())).is(Blocks.STONE),
                    "OreFeature's direct section write placed gravel inside the planned body");
            BlockPos outside=center.offset(plan.candidate().radiusX()+16,0,0);
            for(BlockPos pos:BlockPos.betweenClosed(outside.offset(-8,-4,-8),outside.offset(8,4,8)))
                level.setBlock(pos,Blocks.STONE.defaultBlockState(),Block.UPDATE_NONE);
            helper.assertTrue(net.minecraft.world.level.levelgen.feature.Feature.ORE.place(gravel,level,level.getChunkSource().getGenerator(),
                    net.minecraft.util.RandomSource.create(0),outside),"Gravel was disabled outside the patch");
            helper.assertTrue(!DepositWorldgenGuard.blocksGravel(level,outside),"Protected the patch's AABB instead of its exact body");
            if(forced)index.removeForcedPlan(plan.candidate().depositId());
        }
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void gravelChecksNeverBuildPlansOrReadTerrain(GameTestHelper helper) {
        ServerLevel server=helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        var readOnly=(net.minecraft.world.level.WorldGenLevel)java.lang.reflect.Proxy.newProxyInstance(
                CompactDepositGameTests.class.getClassLoader(),new Class<?>[]{net.minecraft.world.level.WorldGenLevel.class},
                (proxy,method,args) -> switch(method.getName()) {
                    case "getLevel" -> server;
                    case "isOutsideBuildHeight" -> false;
                    default -> throw new AssertionError("Gravel check read terrain: "+method.getName());
                });
        long[] before=OreDepositFeature.compactPlanCacheStatistics(server);
        long start=System.nanoTime();
        // Many cold chunks reproduce the first-world-generation path, not just warm-cache hits.
        for(int i=0;i<4096;i++)helper.assertTrue(!DepositWorldgenGuard.blocksGravel(readOnly,
                new BlockPos(-1000000-i*16,112,-1000000)),"Unknown patch suppressed gravel");
        long elapsed=System.nanoTime()-start;
        helper.assertTrue(java.util.Arrays.equals(before,OreDepositFeature.compactPlanCacheStatistics(server)),
                "Gravel checks discovered or loaded deposit plans");
        System.out.printf(java.util.Locale.ROOT,"Cold gravel guard: 4096 checks in %.3f ms, zero plan loads%n",elapsed/1e6);
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void lateForcedPlanRecoversBaseRockFromEarlierGravel(GameTestHelper helper) {
        ServerLevel level=helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        BlockPos center=new BlockPos(-8192,112,-8192);
        var plan=plan(14267,false,center,SeedMixer.mix(center.asLong()^helper.absolutePos(BlockPos.ZERO).asLong()));
        prepare(level,plan);
        BlockPos pos=null;
        for(var cell:plan.cells()) {
            BlockPos probe=center.offset(cell.x(),cell.y(),cell.z());
            var column=level.getChunkSource().getGenerator().getBaseColumn(probe.getX(),probe.getZ(),level,level.getChunkSource().randomState());
            if(column.getBlock(probe.getY()).is(Blocks.NETHERRACK)){pos=probe;break;}
        }
        helper.assertTrue(pos!=null,"No base rock for late gravel fixture");
        level.setBlock(pos,Blocks.GRAVEL.defaultBlockState(),Block.UPDATE_NONE);
        BlockPos outside=center.offset(plan.candidate().radiusX()+16,0,0);
        level.setBlock(outside,Blocks.GRAVEL.defaultBlockState(),Block.UPDATE_NONE);
        helper.assertTrue(ConfirmedDepositIndex.get(level).registerForcedPlan(plan),"Could not reserve late forced fixture");
        for(var chunk:plan.cells().stream().map(c->new ChunkPos(center.offset(c.x(),c.y(),c.z()))).distinct().toList())
            OreDepositFeature.placePendingForcedSlices(level,chunk);
        helper.assertTrue(level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get()),"Earlier gravel left a hole in the forced patch");
        helper.assertTrue(level.getBlockState(outside).is(Blocks.GRAVEL),"Restored gravel outside the patch");
        int sum=0;for(var cell:plan.cells()) {
            BlockPos point=center.offset(cell.x(),cell.y(),cell.z());var entry=level.getChunkAt(point).getData(ModAttachments.ORE_DEPOSITS).get(point);
            helper.assertTrue(entry!=null,"Late forced patch lost a cell");sum+=entry.remainingOre();
        }
        helper.assertTrue(sum==java.util.Arrays.stream(plan.amounts()).sum(),"Recovering base rock changed reserve");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void generationSliceNeverReadsOrWritesUnavailableNeighbours(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();BlockPos center=new BlockPos(-4096,80,-4096);
        var plan=plan(41,true,center,SeedMixer.mix(center.asLong()^helper.absolutePos(BlockPos.ZERO).asLong()));
        prepare(level,plan);
        var chunks=plan.cells().stream().map(c->new ChunkPos(center.offset(c.x(),c.y(),c.z()))).distinct().toList();
        for(ChunkPos chunk:chunks) {
            // Simulate a generation region exposing only the receiving chunk's block state.
            var local=localChunkLevel(helper,level,chunk);
            OreDepositFeature.placeCompactSlice(local,chunk,plan,true);
        }
        int quantity=0;for(var c:plan.cells()) {
            BlockPos pos=center.offset(c.x(),c.y(),c.z());var entry=level.getChunkAt(pos).getData(ModAttachments.ORE_DEPOSITS).get(pos);
            helper.assertTrue(entry!=null,"Local-only generation left a seam");quantity+=entry.remainingOre();
        }
        helper.assertTrue(quantity==java.util.Arrays.stream(plan.amounts()).sum(),"Local-only generation lost reserve");helper.succeed();
    }

    private static net.minecraft.world.level.WorldGenLevel localChunkLevel(GameTestHelper helper,ServerLevel level,ChunkPos chunk) {
        return (net.minecraft.world.level.WorldGenLevel)java.lang.reflect.Proxy.newProxyInstance(
                    CompactDepositGameTests.class.getClassLoader(),new Class[]{net.minecraft.world.level.WorldGenLevel.class},
                    (proxy,method,args)->{
                        if(args!=null&&args.length>0&&args[0] instanceof BlockPos pos
                                &&(method.getName().equals("getBlockState")||method.getName().equals("setBlock")))
                            helper.assertTrue(new ChunkPos(pos).equals(chunk),"Accessed unavailable neighbour: "+pos);
                        if(method.getName().equals("getChunk")&&args!=null&&args.length>=2&&args[0] instanceof Integer x&&args[1] instanceof Integer z)
                            helper.assertTrue(x==chunk.x&&z==chunk.z,"Requested a neighbouring chunk");
                        try{return method.invoke(level,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    });
    }

    private static List<Integer> fixture(GameTestHelper helper, ServerLevel level, boolean large, boolean reverse) {
        long seed = 14267;
        // Keep the two tiers in separate chunks: their combined reserves can exceed
        // the chunk attachment capacity even when their Y ranges do not overlap.
        BlockPos center=new BlockPos(large?-6144:-5120,large?100:65,reverse?-3456:-3072);
        ResourceLocation oreId=ResourceLocation.withDefaultNamespace("iron_ore");
        OreDepositTier tier=large?OreDepositTier.LARGE:OreDepositTier.MEDIUM;
        long id=SeedMixer.mix(seed^center.asLong()^helper.absolutePos(BlockPos.ZERO).asLong());
        var plan=plan(seed,large,center,id);
        int total=java.util.Arrays.stream(plan.amounts()).sum();
        // Make a second invocation in the isolated GameTest world start from the same fixture.
        ConfirmedDepositIndex index = ConfirmedDepositIndex.get(level);
        for(var old:index.recordsWithin(center,1,oreId,tier)) {
            for(int i=0;i<old.placedBlocks();i++)index.markBlockDepleted(center,oreId,tier.index());
        }
        prepare(level,plan);
        Set<ChunkPos> touched = new HashSet<>();
        for (CompactDepositGeometry.Cell cell : plan.cells()) {
            BlockPos pos = center.offset(cell.x(), cell.y(), cell.z());
            level.getChunkAt(pos).getData(ModAttachments.ORE_DEPOSITS).remove(pos);
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_NONE);
            touched.add(new ChunkPos(pos));
        }
        List<ChunkPos> order = touched.stream().sorted(Comparator.comparingInt((ChunkPos p) -> p.x)
                .thenComparingInt(p -> p.z)).toList();
        if (reverse) order = order.reversed();
        Set<ChunkPos> generated = new HashSet<>();
        for (ChunkPos chunk : order) {
            OreDepositFeature.placeCompactSlice(level, chunk, plan, true);
            generated.add(chunk);
            for (CompactDepositGeometry.Cell cell : plan.cells()) {
                BlockPos pos = center.offset(cell.x(), cell.y(), cell.z());
                boolean written = level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get());
                helper.assertTrue(written == generated.contains(new ChunkPos(pos)), "A slice wrote another chunk or missed its own block: " + pos);
            }
        }
        List<Integer> actual = new ArrayList<>();
        for (CompactDepositGeometry.Cell cell : plan.cells()) {
            BlockPos pos = center.offset(cell.x(), cell.y(), cell.z());
            OreDepositChunkData.Entry entry = level.getChunkAt(pos).getData(ModAttachments.ORE_DEPOSITS).get(pos);
            helper.assertTrue(entry != null, "Missing chunk attachment at " + pos);
            actual.add(entry.remainingOre());
        }
        helper.assertTrue(actual.stream().mapToInt(Integer::intValue).sum() == total, "Reserve changed while slicing");
        // A repeated feature call must neither replenish blocks nor inflate the confirmed count.
        for (ChunkPos chunk : order) OreDepositFeature.placeCompactSlice(level, chunk, plan, true);
        ConfirmedDeposit record = ConfirmedDepositIndex.get(level).recordsWithin(center, 1, oreId, tier).stream()
                .filter(deposit -> deposit.depositId() == id).findFirst().orElseThrow();
        helper.assertTrue(record.placedBlocks() == plan.cells().size(), "Repeated slices inflated the confirmed block count");
        long sections=plan.cells().stream().mapToLong(c -> net.minecraft.core.SectionPos.asLong(center.offset(c.x(),c.y(),c.z()))).distinct().count();
        helper.assertTrue(record.occupiedSections()==sections,"Confirmed section count missed a chunk boundary");
        BlockPos mined=center.offset(plan.cells().getFirst().x(),plan.cells().getFirst().y(),plan.cells().getFirst().z());
        OreDepositRemovalGuard.runAuthorized(level, mined,
                () -> level.setBlock(mined,Blocks.STONE.defaultBlockState(),Block.UPDATE_NONE)
        );
        // OreDepositBlock.onRemove already clears the resource entry and decrements the index.
        helper.assertTrue(level.getChunkAt(mined).getData(ModAttachments.ORE_DEPOSITS).get(mined)==null,"Removal kept resource metadata");
        helper.assertTrue(index.recordsWithin(center,1,oreId,tier).stream().filter(deposit -> deposit.depositId()==id)
                .findFirst().orElseThrow().placedBlocks()==plan.cells().size()-1,"Depletion decremented the wrong record");
        for(ChunkPos chunk:order)OreDepositFeature.placeCompactSlice(level,chunk,plan,true);
        helper.assertTrue(level.getBlockState(mined).is(Blocks.STONE),"Repeated decoration respawned mined ore");
        var indexCopy=ConfirmedDepositIndex.load(index.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        for(ChunkPos chunk:order)helper.assertTrue(indexCopy.hasSlice(id,chunk),"Completion marker lost on save/load");
        long start=System.nanoTime();
        for(int i=0;i<1000;i++)OreDepositFeature.placeCompactSlice(level,order.getFirst(),plan,true);
        System.out.printf(java.util.Locale.ROOT,"Completed slice fast path: %.1f ns/call%n",(System.nanoTime()-start)/1000.0);
        return actual;
    }
}
