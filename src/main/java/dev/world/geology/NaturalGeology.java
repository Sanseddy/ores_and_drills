package dev.world.geology;

import java.util.ArrayList;
import java.util.List;

/**
 * The deposit-free part of the subsurface: a layered background plus natural heterogeneities that
 * reflect without holding ore. They are a pure function of the world seed and coordinates, so a
 * false anomaly is a real, repeatable structure that every survey of the area sees again, not
 * random speckle; telling it apart from ore takes better measurements, not repetition. Open voids
 * (caves) come from the terrain generator itself, see {@link CavityGrid}.
 */
public final class NaturalGeology {
    /** Side of the square province that owns a handful of natural bodies, in blocks. */
    public static final int PROVINCE_SIZE = 512;

    /** Largest horizontal reach of a natural body, used to find the provinces near an area. */
    public static final int MAXIMUM_REACH = 320;

    /** Vanilla blends stone into deepslate between Y 0 and 8. */
    public static final double DEEPSLATE_TOP = 4.0D;

    private static final long NATURAL_SALT = 0x4E41545552414C47L;

    private NaturalGeology() {}

    public enum Setting {
        OVERWORLD,
        NETHER,
        END,
        GENERIC;

        public static Setting forDimension(String dimensionId) {
            return switch (dimensionId) {
                case "minecraft:overworld" -> OVERWORLD;
                case "minecraft:the_nether" -> NETHER;
                case "minecraft:the_end" -> END;
                default -> GENERIC;
            };
        }

        public static Setting byId(int id) {
            Setting[] settings = values();
            return id >= 0 && id < settings.length ? settings[id] : GENERIC;
        }
    }

    /** Host rock of the layered background at height {@code y}. */
    public static RockProperties host(Setting setting, double y) {
        return switch (setting) {
            case OVERWORLD -> y < DEEPSLATE_TOP ? RockCatalog.DEEPSLATE : RockCatalog.STONE;
            case NETHER -> RockCatalog.NETHERRACK;
            case END -> RockCatalog.END_STONE;
            case GENERIC -> RockCatalog.STONE;
        };
    }

    /** Laterally continuous boundaries of the background, ordered top to bottom. */
    public static List<Horizon> horizons(Setting setting, int minBuildHeight) {
        List<Horizon> horizons = new ArrayList<>(2);
        if (setting == Setting.OVERWORLD) {
            horizons.add(
                    new Horizon(
                            Horizon.Kind.DEEPSLATE_TOP,
                            DEEPSLATE_TOP,
                            SeismicMath.reflectionCoefficient(
                                    RockCatalog.STONE, RockCatalog.DEEPSLATE)));
        }
        if (setting != Setting.END) {
            double bedrockTop = minBuildHeight + 3.0D;
            horizons.add(
                    new Horizon(
                            Horizon.Kind.BEDROCK_TOP,
                            bedrockTop,
                            SeismicMath.reflectionCoefficient(
                                    host(setting, bedrockTop + 1.0D), RockCatalog.BEDROCK)));
        }
        return List.copyOf(horizons);
    }

    /**
     * Natural bodies whose footprint may reach the box [minX, maxX] × [minZ, maxZ]; vertical
     * positions are spread over [minY, maxY]. Ordered by province, then by index, so the result
     * does not depend on the caller.
     */
    public static List<SubsurfaceBody> bodies(
            long worldSeed,
            Setting setting,
            double minX,
            double minZ,
            double maxX,
            double maxZ,
            double minY,
            double maxY) {
        if (setting == Setting.END || maxY - minY < 8.0D) {
            return List.of();
        }
        int firstX = Math.floorDiv((int) Math.floor(minX) - MAXIMUM_REACH, PROVINCE_SIZE);
        int lastX = Math.floorDiv((int) Math.ceil(maxX) + MAXIMUM_REACH, PROVINCE_SIZE);
        int firstZ = Math.floorDiv((int) Math.floor(minZ) - MAXIMUM_REACH, PROVINCE_SIZE);
        int lastZ = Math.floorDiv((int) Math.ceil(maxZ) + MAXIMUM_REACH, PROVINCE_SIZE);
        List<SubsurfaceBody> bodies = new ArrayList<>();
        for (int provinceX = firstX; provinceX <= lastX; provinceX++) {
            for (int provinceZ = firstZ; provinceZ <= lastZ; provinceZ++) {
                addProvince(bodies, worldSeed, setting, provinceX, provinceZ, minY, maxY);
            }
        }
        return List.copyOf(bodies);
    }

    private static void addProvince(
            List<SubsurfaceBody> bodies,
            long worldSeed,
            Setting setting,
            int provinceX,
            int provinceZ,
            double minY,
            double maxY) {
        long province = SeedHash.combine(worldSeed ^ NATURAL_SALT, provinceX, provinceZ);
        int count = (int) Math.floor(SeedHash.unit(province, 1) * 2.6D);
        for (int index = 0; index < count; index++) {
            long key = SeedHash.combine(province, index + 17L);
            double x = (provinceX + SeedHash.unit(key, 2)) * PROVINCE_SIZE;
            double z = (provinceZ + SeedHash.unit(key, 3)) * PROVINCE_SIZE;
            double y = minY + 10.0D + SeedHash.unit(key, 4) * Math.max(1.0D, maxY - minY - 20.0D);
            double angle = SeedHash.unit(key, 5) * Math.PI;
            double roll = SeedHash.unit(key, 6);
            RockProperties host = host(setting, y);
            SubsurfaceBody body;
            if (roll < 0.35D) {
                RockProperties rock =
                        rock(SubsurfaceBody.Kind.DENSE_MASSIF, setting, host, u(key, 7));
                double radius = 50.0D + 80.0D * u(key, 8);
                body =
                        body(
                                SubsurfaceBody.Kind.DENSE_MASSIF,
                                key,
                                x,
                                y,
                                z,
                                radius,
                                radius * (0.6D + 0.4D * u(key, 9)),
                                20.0D + 40.0D * u(key, 10),
                                angle,
                                host,
                                rock);
            } else if (roll < 0.60D) {
                RockProperties rock =
                        rock(SubsurfaceBody.Kind.LOW_VELOCITY_LENS, setting, host, u(key, 7));
                double radius = 20.0D + 40.0D * u(key, 8);
                body =
                        body(
                                SubsurfaceBody.Kind.LOW_VELOCITY_LENS,
                                key,
                                x,
                                y,
                                z,
                                radius,
                                radius * (0.5D + 0.5D * u(key, 9)),
                                6.0D + 14.0D * u(key, 10),
                                angle,
                                host,
                                rock);
            } else if (roll < 0.85D) {
                RockProperties rock =
                        rock(SubsurfaceBody.Kind.FRACTURED_ZONE, setting, host, u(key, 7));
                double radius = 30.0D + 60.0D * u(key, 8);
                body =
                        body(
                                SubsurfaceBody.Kind.FRACTURED_ZONE,
                                key,
                                x,
                                y,
                                z,
                                radius,
                                radius / (2.0D + 2.0D * u(key, 9)),
                                10.0D + 20.0D * u(key, 10),
                                angle,
                                host,
                                rock);
            } else {
                RockProperties rock =
                        rock(SubsurfaceBody.Kind.LITHOLOGIC_CONTACT, setting, host, u(key, 7));
                body =
                        body(
                                SubsurfaceBody.Kind.LITHOLOGIC_CONTACT,
                                key,
                                x,
                                y,
                                z,
                                150.0D + 150.0D * u(key, 8),
                                4.0D + 4.0D * u(key, 9),
                                30.0D + 50.0D * u(key, 10),
                                angle,
                                host,
                                rock);
            }
            bodies.add(body);
        }
    }

    /**
     * Rock of a natural heterogeneity in {@code host}; {@code variation} (0–1) spans the kind's
     * natural range. Shared by the generator of false anomalies and by ore identification, which
     * must weigh exactly these alternatives.
     */
    public static RockProperties rock(
            SubsurfaceBody.Kind kind, Setting setting, RockProperties host, double variation) {
        double v = Math.max(0.0D, Math.min(1.0D, variation));
        return switch (kind) {
                // Dense basic intrusion: much of the volume is diorite-like rock.
            case DENSE_MASSIF -> host.mixedWith(RockCatalog.DIORITE, 0.25D + 0.25D * v);
            case LOW_VELOCITY_LENS ->
                    host.mixedWith(
                            setting == Setting.NETHER ? RockCatalog.SOIL : RockCatalog.TUFF,
                            0.10D + 0.15D * v);
                // Open fractures: a few percent lower velocity and density, much lower Q.
            case FRACTURED_ZONE ->
                    new RockProperties(
                            host.density() * 0.97D,
                            host.velocity() * (0.90D + 0.05D * v),
                            host.quality() * 0.3D);
            case LITHOLOGIC_CONTACT ->
                    host.mixedWith(v < 0.5D ? RockCatalog.GRANITE : RockCatalog.ANDESITE, 0.5D);
            case ORE_BODY, MINERALIZED_HALO ->
                    throw new IllegalArgumentException("Not a natural heterogeneity: " + kind);
        };
    }

    private static SubsurfaceBody body(
            SubsurfaceBody.Kind kind,
            long key,
            double x,
            double y,
            double z,
            double radiusX,
            double radiusZ,
            double thickness,
            double angle,
            RockProperties host,
            RockProperties rock) {
        return new SubsurfaceBody(
                kind,
                key,
                x,
                y,
                z,
                radiusX,
                radiusZ,
                thickness,
                angle,
                SeismicMath.reflectionCoefficient(host, rock),
                host.quality() / rock.quality());
    }

    private static double u(long key, long salt) {
        return SeedHash.unit(key, salt);
    }
}
