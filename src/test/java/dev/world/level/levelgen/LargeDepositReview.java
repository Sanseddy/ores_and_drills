package dev.world.level.levelgen;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Reproducible 100+100 sample audit, showing the envelope separately from physical resource
 * entries.
 */
public final class LargeDepositReview {
    private static volatile long sink;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        StringBuilder csv =
                new StringBuilder(
                        "tier,seed,diameter,width_x,width_z,xz_area,envelope_blocks,ore_blocks,density,min_thickness,max_thickness,y_range,max_step,ore_components,units,min_q,max_q,chunks,noise_calls,geometry_ns,selection_ns,allocated_bytes\n");
        StringBuilder models = new StringBuilder("const models=[\n");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().threadId();
        for (int i = 0; i < 80; i++) {
            var g = new CompactDepositGeometry(i, (i & 1) == 0, 1);
            sink += g.cells().size();
        }
        for (boolean large : new boolean[] {false, true}) {
            BufferedImage[] atlases = new BufferedImage[3];
            Graphics2D[] pens = new Graphics2D[3];
            for (int p = 0; p < 3; p++) {
                atlases[p] = new BufferedImage(2200, 2200, BufferedImage.TYPE_INT_RGB);
                pens[p] = atlases[p].createGraphics();
                pens[p].setColor(new Color(20, 26, 36));
                pens[p].fillRect(0, 0, 2200, 2200);
                pens[p].setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            }
            for (int seed = 0; seed < 100; seed++) {
                long allocated = bean.getThreadAllocatedBytes(tid), start = System.nanoTime();
                var g = new CompactDepositGeometry(seed, large, 1);
                long shapeNanos = System.nanoTime() - start;
                int q = CompactDepositGeometry.totalOre(seed, large, 0, 1);
                start = System.nanoTime();
                var ore =
                        CompactDepositGeometry.selectOreCells(
                                g.cells(), CompactDepositGeometry.oreBlockBudget(q));
                long selectNanos = System.nanoTime() - start,
                        bytes = bean.getThreadAllocatedBytes(tid) - allocated;
                if (ore.isEmpty())
                    throw new AssertionError("Insufficient default reserve: " + seed);
                if (!ore.equals(g.cells()))
                    throw new AssertionError("Default reserve left holes in the body: " + seed);
                if (seed == 0)
                    writeSpatial(
                            output.resolve((large ? "large" : "medium") + "-3d.png"),
                            g,
                            ore,
                            large);
                int[] amounts =
                        DepositTierMath.distributeOre(
                                q,
                                ore.stream()
                                        .mapToDouble(CompactDepositGeometry.Cell::weight)
                                        .toArray(),
                                CompactDepositGeometry.maximumBlockReserve(q, ore.size()));
                int[] b = bounds(g.cells()), ob = bounds(ore);
                int diameter = Math.max(b[1] - b[0] + 1, b[3] - b[2] + 1);
                if (diameter < (large ? 40 : 20)
                        || diameter > (large ? 60 : 30)
                        || diameter != Math.max(ob[1] - ob[0] + 1, ob[3] - ob[2] + 1))
                    throw new AssertionError("Diameter");
                if (CompactDepositGeometry.connected(ore, ore.size()).size() != ore.size()
                        || Arrays.stream(amounts).sum() != q)
                    throw new AssertionError("Connectivity/budget");
                Set<Long> chunks = new HashSet<>();
                for (var c : ore)
                    chunks.add(
                            ((long) Math.floorDiv(c.x(), 16) << 32)
                                    ^ (Math.floorDiv(c.z(), 16) & 0xffffffffL));
                int area = 0;
                double minY = 99, maxY = -99, minT = 99, maxT = -99, maxStep = 0;
                models.append("{tier:'")
                        .append(large ? "LARGE" : "MEDIUM")
                        .append("',seed:")
                        .append(seed)
                        .append(",q:")
                        .append(q)
                        .append(",diameter:")
                        .append(diameter)
                        .append(",surface:[");
                for (int x = b[0]; x <= b[1]; x++)
                    for (int z = b[2]; z <= b[3]; z++) {
                        var col = g.column(x, z);
                        if (col.horizontal() <= 0) continue;
                        area++;
                        minY = Math.min(minY, col.center());
                        maxY = Math.max(maxY, col.center());
                        minT = Math.min(minT, col.thickness());
                        maxT = Math.max(maxT, col.thickness());
                        for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
                            var n = g.column(x + d[0], z + d[1]);
                            if (n.horizontal() > 0)
                                maxStep = Math.max(maxStep, Math.abs(col.center() - n.center()));
                        }
                        models.append(x)
                                .append(',')
                                .append(String.format("%.2f", col.top()))
                                .append(',')
                                .append(z)
                                .append(',');
                        models.append(x)
                                .append(',')
                                .append(String.format("%.2f", col.bottom()))
                                .append(',')
                                .append(z)
                                .append(',');
                    }
                models.append("],ore:[");
                for (var c : ore)
                    models.append(c.x())
                            .append(',')
                            .append(c.y())
                            .append(',')
                            .append(c.z())
                            .append(',');
                models.append("]},\n");
                csv.append(large ? "LARGE" : "MEDIUM")
                        .append(',')
                        .append(seed)
                        .append(',')
                        .append(diameter)
                        .append(',')
                        .append(b[1] - b[0] + 1)
                        .append(',')
                        .append(b[3] - b[2] + 1)
                        .append(',')
                        .append(area)
                        .append(',')
                        .append(g.cells().size())
                        .append(',')
                        .append(ore.size())
                        .append(',')
                        .append(ore.size() / (double) g.cells().size())
                        .append(',')
                        .append(minT)
                        .append(',')
                        .append(maxT)
                        .append(',')
                        .append(maxY - minY)
                        .append(',')
                        .append(maxStep)
                        .append(",1,")
                        .append(q)
                        .append(',')
                        .append(Arrays.stream(amounts).min().orElseThrow())
                        .append(',')
                        .append(Arrays.stream(amounts).max().orElseThrow())
                        .append(',')
                        .append(chunks.size())
                        .append(',')
                        .append(g.noiseEvaluations())
                        .append(',')
                        .append(shapeNanos)
                        .append(',')
                        .append(selectNanos)
                        .append(',')
                        .append(bytes)
                        .append('\n');
                int tx = seed % 10 * 220, ty = seed / 10 * 220;
                for (var c : g.cells()) draw(pens, c, tx, ty, false);
                for (var c : ore) draw(pens, c, tx, ty, true);
                for (int p = 0; p < 3; p++) {
                    pens[p].setColor(Color.WHITE);
                    pens[p].drawString(
                            (large ? "L" : "M")
                                    + " #"
                                    + seed
                                    + " "
                                    + new String[] {"XZ", "XY z=0", "ZY x=0"}[p]
                                    + " D="
                                    + diameter,
                            tx + 6,
                            ty + 14);
                    pens[p].drawString(
                            ore.size() + " ore / " + g.cells().size() + " envelope",
                            tx + 6,
                            ty + 200);
                    pens[p].drawString(q + " units", tx + 6, ty + 214);
                }
            }
            for (int p = 0; p < 3; p++) {
                pens[p].dispose();
                ImageIO.write(
                        atlases[p],
                        "png",
                        output.resolve(
                                        (large ? "large" : "medium")
                                                + "-"
                                                + new String[] {"xz", "xy", "zy"}[p]
                                                + "-100.png")
                                .toFile());
            }
        }
        models.append("];\n");
        Files.writeString(output.resolve("models.js"), models);
        Files.writeString(output.resolve("statistics-200.csv"), csv);
        // Median of repeated equal batches; do not treat this geometry microbenchmark as server
        // TPS.
        double[] times = new double[5], allocations = new double[5];
        for (int round = 0; round < 5; round++) {
            long allocated = bean.getThreadAllocatedBytes(tid), start = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                var g = new CompactDepositGeometry(i, (i & 1) == 0, 1);
                int q = CompactDepositGeometry.totalOre(i, (i & 1) == 0, 0, 1);
                sink +=
                        CompactDepositGeometry.selectOreCells(
                                        g.cells(), CompactDepositGeometry.oreBlockBudget(q))
                                .size();
            }
            times[round] = (System.nanoTime() - start) / 200.0;
            allocations[round] = (bean.getThreadAllocatedBytes(tid) - allocated) / 200.0;
        }
        Arrays.sort(times);
        Arrays.sort(allocations);
        Files.writeString(
                output.resolve("performance.txt"),
                String.format(
                        "Geometry + full-body selection: median %.3f ms/op; %.0f allocated"
                                + " bytes/op; checksum=%d%n",
                        times[2] / 1e6, allocations[2], sink));
        System.out.printf(
                "100 MEDIUM + 100 LARGE: %.3f ms geometry+selection, %.0f B/op%n",
                times[2] / 1e6, allocations[2]);
    }

    private static void draw(
            Graphics2D[] pens, CompactDepositGeometry.Cell c, int tx, int ty, boolean ore) {
        Color color =
                ore
                        ? new Color(
                                (int) (170 + 80 * c.weight()), (int) (110 + 105 * c.weight()), 45)
                        : new Color(43, 75, 90);
        for (int p = 0; p < 3; p++) {
            if (p == 1 && c.z() != 0 || p == 2 && c.x() != 0) continue;
            int x = tx + 109 + (p == 2 ? c.z() : c.x()) * 3,
                    y = ty + 105 + (p == 0 ? c.z() * 3 : -c.y() * 6);
            pens[p].setColor(color);
            pens[p].fillRect(x, y, 3, p == 0 ? 3 : 6);
        }
    }

    private static int[] bounds(List<CompactDepositGeometry.Cell> cells) {
        return new int[] {
            cells.stream().mapToInt(CompactDepositGeometry.Cell::x).min().orElseThrow(),
            cells.stream().mapToInt(CompactDepositGeometry.Cell::x).max().orElseThrow(),
            cells.stream().mapToInt(CompactDepositGeometry.Cell::z).min().orElseThrow(),
            cells.stream().mapToInt(CompactDepositGeometry.Cell::z).max().orElseThrow()
        };
    }

    private static void writeSpatial(
            Path path,
            CompactDepositGeometry geometry,
            List<CompactDepositGeometry.Cell> ore,
            boolean large)
            throws Exception {
        BufferedImage image = new BufferedImage(1100, 650, BufferedImage.TYPE_INT_RGB);
        Graphics2D pen = image.createGraphics();
        pen.setColor(new Color(20, 26, 36));
        pen.fillRect(0, 0, 1100, 650);
        pen.setColor(Color.WHITE);
        pen.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        pen.drawString(
                (large ? "LARGE" : "MEDIUM") + " seed 0 | 3D | blue: envelope; gold: physical ore",
                24,
                32);
        List<double[]> points = new ArrayList<>();
        for (int x = -36; x <= 36; x++)
            for (int z = -36; z <= 36; z++) {
                var column = geometry.column(x, z);
                if (column.horizontal() <= 0) continue;
                points.add(new double[] {x, column.top(), z, 0});
                points.add(new double[] {x, column.bottom(), z, 0});
            }
        for (var c : ore) points.add(new double[] {c.x(), c.y(), c.z(), 1});
        points.sort(Comparator.comparingDouble(p -> p[0] + p[2] + p[1]));
        for (double[] p : points) {
            int x = (int) (550 + (p[0] - p[2]) * 7),
                    y = (int) (330 + (p[0] + p[2]) * 3.2 - p[1] * 7);
            pen.setColor(p[3] > 0 ? new Color(235, 179, 64) : new Color(70, 140, 170, 65));
            pen.fillRect(x - 3, y - 3, p[3] > 0 ? 7 : 5, p[3] > 0 ? 7 : 5);
        }
        pen.dispose();
        ImageIO.write(image, "png", path.toFile());
    }
}
