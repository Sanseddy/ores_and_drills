package dev.world.level.levelgen;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Standalone, reproducible visual/performance review; no Minecraft runtime or JUnit needed. */
public final class CompactDepositReview {
    private static volatile long sink;
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        java.lang.reflect.Method noiseCount;
        try { noiseCount=CompactDepositGeometry.class.getDeclaredMethod("noiseEvaluations"); } catch(NoSuchMethodException absent) { noiseCount=null; }
        StringBuilder csv = new StringBuilder("tier,seed,width_x,width_z,columns,blocks,center_y_range,max_center_step,components,units,min_block_units,max_block_units,max_resource_share,center_mean_weight,edge_mean_weight,noise_evaluations,shape\n");
        StringBuilder summary = new StringBuilder();
        for (boolean large : new boolean[]{false, true}) {
            Map<String,Integer> signatures = new HashMap<>(), canonical = new HashMap<>();
            BufferedImage[] atlases = new BufferedImage[3];
            Graphics2D[] graphics = new Graphics2D[3];
            for (int p = 0; p < 3; p++) {
                atlases[p] = new BufferedImage(1700, 1700, BufferedImage.TYPE_INT_RGB);
                graphics[p] = atlases[p].createGraphics(); graphics[p].setColor(new Color(20,26,36));
                graphics[p].fillRect(0,0,1700,1700); graphics[p].setFont(new Font(Font.MONOSPACED,Font.PLAIN,11));
            }
            for (int seed = 0; seed < 100; seed++) {
                CompactDepositGeometry g = new CompactDepositGeometry(seed, large, 1);
                Set<String> footprint = new TreeSet<>();
                for (var cell : g.cells()) footprint.add(cell.x()+":"+cell.z());
                String shape = signature(footprint,0);
                signatures.merge(shape,1,Integer::sum);
                canonical.merge(java.util.stream.IntStream.range(0,8).mapToObj(t -> signature(footprint,t)).min(String::compareTo).orElseThrow(),1,Integer::sum);
                int total = CompactDepositGeometry.totalOre(seed, large, g.cells().size(),1);
                int[] amounts = DepositTierMath.distributeOre(total,g.cells().stream().mapToDouble(CompactDepositGeometry.Cell::weight).toArray(),5000);
                int minX=99,maxX=-99,minZ=99,maxZ=-99;
                double minY=99,maxY=-99,maxStep=0,centerWeight=0,edgeWeight=0;
                int centerCount=0,edgeCount=0;
                for (var c : g.cells()) {
                    minX=Math.min(minX,c.x());maxX=Math.max(maxX,c.x()); minZ=Math.min(minZ,c.z());maxZ=Math.max(maxZ,c.z());
                    var column=g.column(c.x(),c.z()); minY=Math.min(minY,column.center());maxY=Math.max(maxY,column.center());
                    for (int[] d : new int[][]{{1,0},{0,1}}) {
                        var next=g.column(c.x()+d[0],c.z()+d[1]);
                        if(next.horizontal()>0) maxStep=Math.max(maxStep,Math.abs(column.center()-next.center()));
                    }
                    if(Math.hypot(c.x(),c.z())<=1){centerWeight+=c.weight();centerCount++;}
                    else {edgeWeight+=c.weight();edgeCount++;}
                    int tx=seed%10*170,ty=seed/10*170;
                    Color color=new Color((int)(65+175*c.weight()),(int)(55+135*c.weight()),45);
                    for(int p=0;p<3;p++) {
                        if(p==1&&c.z()!=0||p==2&&c.x()!=0)continue;
                        int px=tx+78+(p==2?c.z():c.x())*14;
                        int py=ty+85+(p==0?c.z()*14:-c.y()*10);
                        graphics[p].setColor(color); graphics[p].fillRect(px,py,13,p==0?13:9);
                    }
                }
                int components=components(g.cells());
                csv.append(large?"LARGE":"MEDIUM").append(',').append(seed).append(',').append(maxX-minX+1).append(',').append(maxZ-minZ+1).append(',')
                        .append(footprint.size()).append(',').append(g.cells().size()).append(',').append(maxY-minY).append(',').append(maxStep).append(',').append(components).append(',')
                        .append(total).append(',').append(Arrays.stream(amounts).min().orElse(0)).append(',').append(Arrays.stream(amounts).max().orElse(0)).append(',')
                        .append(Arrays.stream(amounts).max().orElse(0)/(double)total).append(',').append(centerWeight/centerCount).append(',').append(edgeWeight/Math.max(1,edgeCount)).append(',')
                        .append(noiseCount==null?-1:noiseCount.invoke(g)).append(',').append(shape).append('\n');
                if(components!=1||Arrays.stream(amounts).sum()!=total)throw new AssertionError("Invalid body/budget "+seed);
                for(int p=0;p<3;p++){
                    graphics[p].setColor(Color.WHITE);graphics[p].drawString((large?"L":"M")+" seed="+seed+" "+new String[]{"XZ","XY z=0","ZY x=0"}[p],seed%10*170+6,seed/10*170+15);
                    graphics[p].drawString(g.cells().size()+" blocks; "+total+" units",seed%10*170+6,seed/10*170+158);
                }
            }
            for(int p=0;p<3;p++){graphics[p].dispose();ImageIO.write(atlases[p],"png",output.resolve((large?"large":"medium")+"-"+new String[]{"xz","xy","zy"}[p]+"-100.png").toFile());}
            summary.append(large?"LARGE":"MEDIUM").append(": exact unique contours=").append(signatures.size()).append("/100; unique up to D4 symmetry/translation=")
                    .append(canonical.size()).append("/100; most repeated=").append(Collections.max(signatures.values())).append('\n');
        }
        Files.writeString(output.resolve("statistics-200.csv"),csv);
        // Same warmup, seed sequence, heap and GC for before/after; report median of five batches.
        for(int i=0;i<3000;i++)sink+=new CompactDepositGeometry(i,(i&1)==0,1).cells().size();
        jdk.jfr.Recording recording=null;
        if(args.length>1){
            recording=new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            recording.disable("jdk.InitialEnvironmentVariable");recording.disable("jdk.InitialSystemProperty");recording.disable("jdk.SystemProcess");
            recording.setMaxSize(16*1024*1024);recording.start();
        }
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long tid=Thread.currentThread().threadId();
        double[] times=new double[5],bytes=new double[5];
        for(int round=0;round<5;round++){
            long allocated=bean.getThreadAllocatedBytes(tid),start=System.nanoTime(),count=0;
            for(int i=0;i<2000;i++)count+=new CompactDepositGeometry(i+round*2000,(i&1)==0,1).cells().size();
            times[round]=(System.nanoTime()-start)/2000.0;bytes[round]=(bean.getThreadAllocatedBytes(tid)-allocated)/2000.0;sink=count;
        }
        Arrays.sort(times);Arrays.sort(bytes);
        if(recording!=null){recording.stop();recording.dump(Path.of(args[1]));recording.close();}
        summary.append(String.format("geometry median %.1f ns/op; thread allocation %.1f bytes/op; checksum %d%n",times[2],bytes[2],sink));
        if(output.getFileName().toString().equals("after")) {
            BoundedLoadingCache<Integer,CompactDepositGeometry> cache=new BoundedLoadingCache<>(256);
            for(int i=0;i<100;i++){int seed=i;cache.get(seed,() -> new CompactDepositGeometry(seed,(seed&1)==0,1));}
            long allocated=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();
            for(int i=0;i<20000;i++)sink+=cache.get(i%100,() -> {throw new AssertionError("Unexpected miss");}).cells().size();
            summary.append(String.format("cached geometry %.1f ns/op; %.1f bytes/op; hits=%d misses=%d entries=%d%n",(System.nanoTime()-start)/20000.0,
                    (bean.getThreadAllocatedBytes(tid)-allocated)/20000.0,cache.hits(),cache.misses(),cache.size()));
        }
        Files.writeString(output.resolve("summary.txt"),summary);System.out.print(summary);
    }
    static String signature(Set<String> footprint,int transform){
        List<int[]> points=new ArrayList<>();int minX=99,minZ=99;
        for(String key:footprint){String[] xy=key.split(":");int x=Integer.parseInt(xy[0]),z=Integer.parseInt(xy[1]);
            if((transform&1)!=0){int swap=x;x=z;z=swap;}if((transform&2)!=0)x=-x;if((transform&4)!=0)z=-z;
            points.add(new int[]{x,z});minX=Math.min(minX,x);minZ=Math.min(minZ,z);}
        List<String> values=new ArrayList<>();for(int[] p:points)values.add((p[0]-minX)+":"+(p[1]-minZ));Collections.sort(values);return String.join(";",values);
    }
    private static int components(List<CompactDepositGeometry.Cell> cells){
        Set<CompactDepositGeometry.Key> pending=new HashSet<>();for(var c:cells)pending.add(c.key());int count=0;
        ArrayDeque<CompactDepositGeometry.Key> queue=new ArrayDeque<>();
        while(!pending.isEmpty()){count++;var first=pending.iterator().next();pending.remove(first);queue.add(first);
            while(!queue.isEmpty()){var c=queue.removeFirst();for(int[] d:new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}){
                var next=new CompactDepositGeometry.Key(c.x()+d[0],c.y()+d[1],c.z()+d[2]);if(pending.remove(next))queue.add(next);}}}
        return count;
    }
}
