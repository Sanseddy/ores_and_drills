package dev.world.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.OresAndDrillsMod;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds block-local hitboxes from a GeckoLib Bedrock geometry file.
 *
 * <p>A Minecraft {@link VoxelShape} can only contain axis-aligned boxes. Rotated cubes are therefore
 * divided into one-model-pixel cells and each cell is conservatively wrapped in an AABB. Every resulting
 * box is clipped to one physical block cell before it is added. Consequently selecting a multiblock part
 * only outlines the geometry contained in that part's 16x16x16 space.</p>
 */
final class DrillHitboxModel {
    private static final float PIXELS_PER_BLOCK = 16.0F;
    private static final double FLAT_CUBE_COLLISION_THICKNESS = 1.0D / PIXELS_PER_BLOCK;
    private static final float EPSILON = 1.0E-5F;
    private static final int MAX_SUBDIVISIONS_PER_AXIS = 64;

    private DrillHitboxModel() {
    }

    static LoadedHitboxes loadOrFullBlocks(
            String resourcePath,
            int size,
            int height,
            int mainOffsetX,
            int mainOffsetY,
            int mainOffsetZ,
            double modelForwardOffset
    ) {
        try {
            return load(resourcePath, size, height, mainOffsetX, mainOffsetY, mainOffsetZ, modelForwardOffset);
        } catch (RuntimeException | IOException exception) {
            OresAndDrillsMod.LOGGER.debug(
                    "Could not build drill hitboxes from {}; falling back to full blocks",
                    resourcePath,
                    exception
            );
            return new LoadedHitboxes(fullBlocks(size, height), new DrillModelCell[size][height][size]);
        }
    }

    static LoadedHitboxes load(
            String resourcePath,
            int size,
            int height,
            int mainOffsetX,
            int mainOffsetY,
            int mainOffsetZ,
            double modelForwardOffset
    ) throws IOException {
        JsonObject root;
        try (InputStream stream = DrillHitboxModel.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Missing geometry resource " + resourcePath);
            }

            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
        }

        JsonArray geometries = requiredArray(root, "minecraft:geometry");
        if (geometries.isEmpty()) {
            throw new IOException("Geometry resource has no models: " + resourcePath);
        }

        JsonArray boneElements = requiredArray(geometries.get(0).getAsJsonObject(), "bones");
        Map<String, BoneDefinition> bones = new LinkedHashMap<>();
        for (JsonElement element : boneElements) {
            JsonObject bone = element.getAsJsonObject();
            String name = requiredString(bone, "name");
            String parent = optionalString(bone, "parent");
            bones.put(name, new BoneDefinition(
                    name,
                    parent,
                    readVector(bone, "pivot"),
                    readVector(bone, "rotation"),
                    readCubes(bone)
            ));
        }

        PixelCell[][][] cells = new PixelCell[size][height][size];
        DrillModelCell.Builder[][][] exactBuilders = new DrillModelCell.Builder[size][height][size];
        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetY = 0; offsetY < height; offsetY++) {
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    cells[offsetX][offsetY][offsetZ] = new PixelCell();
                    exactBuilders[offsetX][offsetY][offsetZ] = DrillModelCell.builder();
                }
            }
        }

        // This matches the NORTH renderer: centre the GeckoLib origin in the main block,
        // move it forward to the multiblock centre, then apply the model's 180-degree yaw.
        Matrix4f modelTransform = new Matrix4f()
                .translate(0.5F, 0.0F, 0.5F - (float) modelForwardOffset)
                .rotateY((float) Math.PI);

        Map<String, Matrix4f> resolvedBones = new HashMap<>();
        for (BoneDefinition bone : bones.values()) {
            Matrix4f boneTransform = resolveBoneTransform(bone, bones, resolvedBones, new ArrayList<>());
            for (CubeDefinition cube : bone.cubes()) {
                addCube(
                        new Matrix4f(modelTransform).mul(boneTransform).mul(cubeTransform(cube)),
                        cube,
                        cells,
                        exactBuilders,
                        size,
                        height,
                        mainOffsetX,
                        mainOffsetY,
                        mainOffsetZ
                );
            }
        }

        VoxelShape[][][] result = new VoxelShape[size][height][size];
        DrillModelCell[][][] exactCells = new DrillModelCell[size][height][size];
        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetY = 0; offsetY < height; offsetY++) {
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    result[offsetX][offsetY][offsetZ] = cells[offsetX][offsetY][offsetZ].buildShape();
                    exactCells[offsetX][offsetY][offsetZ] = result[offsetX][offsetY][offsetZ].isEmpty()
                            ? DrillModelCell.EMPTY
                            : exactBuilders[offsetX][offsetY][offsetZ].build();
                }
            }
        }
        return new LoadedHitboxes(result, exactCells);
    }

    static VoxelShape rotateY(VoxelShape northShape, Direction facing) {
        if (northShape.isEmpty() || facing == Direction.NORTH) {
            return northShape;
        }
        if (facing.getAxis().isVertical()) {
            throw new IllegalArgumentException("Drill hitboxes only support horizontal facings: " + facing);
        }

        VoxelShape[] rotated = {Shapes.empty()};
        northShape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            int quarterTurns = switch (facing) {
                case EAST -> 1;
                case SOUTH -> 2;
                case WEST -> 3;
                default -> throw new IllegalArgumentException("Unexpected facing " + facing);
            };
            DrillHitboxCellMath.Box box = DrillHitboxCellMath.rotateY(
                    new DrillHitboxCellMath.Box(minX, minY, minZ, maxX, maxY, maxZ),
                    quarterTurns
            );
            rotated[0] = Shapes.or(rotated[0], Shapes.box(
                    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()
            ));
        });
        return rotated[0].optimize();
    }

    private static List<CubeDefinition> readCubes(JsonObject bone) throws IOException {
        JsonArray elements = optionalArray(bone, "cubes");
        if (elements == null) {
            return List.of();
        }

        List<CubeDefinition> cubes = new ArrayList<>(elements.size());
        for (JsonElement element : elements) {
            JsonObject cube = element.getAsJsonObject();
            cubes.add(new CubeDefinition(
                    requiredVector(cube, "origin"),
                    requiredVector(cube, "size"),
                    readVector(cube, "pivot"),
                    readVector(cube, "rotation"),
                    optionalFloat(cube, "inflate", 0.0F)
            ));
        }
        return List.copyOf(cubes);
    }

    private static Matrix4f resolveBoneTransform(
            BoneDefinition bone,
            Map<String, BoneDefinition> bones,
            Map<String, Matrix4f> resolved,
            List<String> resolving
    ) throws IOException {
        Matrix4f cached = resolved.get(bone.name());
        if (cached != null) {
            return new Matrix4f(cached);
        }
        if (resolving.contains(bone.name())) {
            throw new IOException("Bone parent cycle: " + String.join(" -> ", resolving) + " -> " + bone.name());
        }

        resolving.add(bone.name());
        Matrix4f transform = new Matrix4f();
        if (bone.parent() != null) {
            BoneDefinition parent = bones.get(bone.parent());
            if (parent == null) {
                throw new IOException("Unknown parent bone " + bone.parent() + " for " + bone.name());
            }
            transform.set(resolveBoneTransform(parent, bones, resolved, resolving));
        }
        transform.mul(pivotRotationTransform(bone.pivot(), bone.rotation()));
        resolving.removeLast();
        resolved.put(bone.name(), new Matrix4f(transform));
        return transform;
    }

    private static Matrix4f cubeTransform(CubeDefinition cube) {
        return pivotRotationTransform(cube.pivot(), cube.rotation());
    }

    /** Matches GeckoLib's Bedrock-to-Java axis conversion and Z/Y/X rotation order. */
    private static Matrix4f pivotRotationTransform(Vector3f rawPivot, Vector3f rawRotationDegrees) {
        float pivotX = -rawPivot.x / PIXELS_PER_BLOCK;
        float pivotY = rawPivot.y / PIXELS_PER_BLOCK;
        float pivotZ = rawPivot.z / PIXELS_PER_BLOCK;
        float rotationX = (float) Math.toRadians(-rawRotationDegrees.x);
        float rotationY = (float) Math.toRadians(-rawRotationDegrees.y);
        float rotationZ = (float) Math.toRadians(rawRotationDegrees.z);

        return new Matrix4f()
                .translate(pivotX, pivotY, pivotZ)
                .rotateZ(rotationZ)
                .rotateY(rotationY)
                .rotateX(rotationX)
                .translate(-pivotX, -pivotY, -pivotZ);
    }

    private static void addCube(
            Matrix4f transform,
            CubeDefinition cube,
            PixelCell[][][] cells,
            DrillModelCell.Builder[][][] exactBuilders,
            int size,
            int height,
            int mainOffsetX,
            int mainOffsetY,
            int mainOffsetZ
    ) {
        Vector3f rawOrigin = cube.origin();
        Vector3f rawSize = cube.size();
        float inflate = cube.inflate() / PIXELS_PER_BLOCK;

        // GeckoLib mirrors the Bedrock X axis while baking cube vertices.
        float minX = -(rawOrigin.x + rawSize.x) / PIXELS_PER_BLOCK - inflate;
        float maxX = -rawOrigin.x / PIXELS_PER_BLOCK + inflate;
        float minY = rawOrigin.y / PIXELS_PER_BLOCK - inflate;
        float maxY = (rawOrigin.y + rawSize.y) / PIXELS_PER_BLOCK + inflate;
        float minZ = rawOrigin.z / PIXELS_PER_BLOCK - inflate;
        float maxZ = (rawOrigin.z + rawSize.z) / PIXELS_PER_BLOCK + inflate;

        addExactCube(
                transform,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                exactBuilders,
                size,
                height,
                mainOffsetX,
                mainOffsetY,
                mainOffsetZ
        );

        // Bedrock models allow cubes with a zero-sized axis. They still render and can be selected,
        // but a VoxelShape needs volume to collide with entities. Give only those flat cubes one
        // model pixel of collision thickness while preserving their exact visual/selection surface.
        DrillHitboxCellMath.Box collisionBox = DrillHitboxCellMath.expandFlatAxes(
                new DrillHitboxCellMath.Box(minX, minY, minZ, maxX, maxY, maxZ),
                FLAT_CUBE_COLLISION_THICKNESS
        );
        minX = (float) collisionBox.minX();
        minY = (float) collisionBox.minY();
        minZ = (float) collisionBox.minZ();
        maxX = (float) collisionBox.maxX();
        maxY = (float) collisionBox.maxY();
        maxZ = (float) collisionBox.maxZ();

        int divisionsX = subdivisions(minX, maxX, transform);
        int divisionsY = subdivisions(minY, maxY, transform);
        int divisionsZ = subdivisions(minZ, maxZ, transform);

        for (int partX = 0; partX < divisionsX; partX++) {
            float partMinX = lerp(minX, maxX, (float) partX / divisionsX);
            float partMaxX = lerp(minX, maxX, (float) (partX + 1) / divisionsX);
            for (int partY = 0; partY < divisionsY; partY++) {
                float partMinY = lerp(minY, maxY, (float) partY / divisionsY);
                float partMaxY = lerp(minY, maxY, (float) (partY + 1) / divisionsY);
                for (int partZ = 0; partZ < divisionsZ; partZ++) {
                    float partMinZ = lerp(minZ, maxZ, (float) partZ / divisionsZ);
                    float partMaxZ = lerp(minZ, maxZ, (float) (partZ + 1) / divisionsZ);
                    AABB transformed = transformAabb(
                            transform,
                            partMinX, partMinY, partMinZ,
                            partMaxX, partMaxY, partMaxZ
                    );
                    addClippedToCells(
                            transformed,
                            cells,
                            size,
                            height,
                            mainOffsetX,
                            mainOffsetY,
                            mainOffsetZ
                    );
                }
            }
        }
    }

    private static int subdivisions(float minimum, float maximum, Matrix4f transform) {
        if (isAxisAligned(transform)) {
            return 1;
        }
        return Math.clamp((int) Math.ceil((maximum - minimum) * PIXELS_PER_BLOCK), 1, MAX_SUBDIVISIONS_PER_AXIS);
    }

    private static boolean isAxisAligned(Matrix4f matrix) {
        float[][] values = {
                {matrix.m00(), matrix.m01(), matrix.m02()},
                {matrix.m10(), matrix.m11(), matrix.m12()},
                {matrix.m20(), matrix.m21(), matrix.m22()}
        };

        for (int row = 0; row < 3; row++) {
            int nonZero = 0;
            for (int column = 0; column < 3; column++) {
                float absolute = Math.abs(values[row][column]);
                if (absolute > EPSILON) {
                    if (Math.abs(absolute - 1.0F) > EPSILON) {
                        return false;
                    }
                    nonZero++;
                }
            }
            if (nonZero != 1) {
                return false;
            }
        }
        return true;
    }

    private static AABB transformAabb(
            Matrix4f transform,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ
    ) {
        double transformedMinX = Double.POSITIVE_INFINITY;
        double transformedMinY = Double.POSITIVE_INFINITY;
        double transformedMinZ = Double.POSITIVE_INFINITY;
        double transformedMaxX = Double.NEGATIVE_INFINITY;
        double transformedMaxY = Double.NEGATIVE_INFINITY;
        double transformedMaxZ = Double.NEGATIVE_INFINITY;

        for (int corner = 0; corner < 8; corner++) {
            Vector3f point = new Vector3f(
                    (corner & 1) == 0 ? minX : maxX,
                    (corner & 2) == 0 ? minY : maxY,
                    (corner & 4) == 0 ? minZ : maxZ
            );
            transform.transformPosition(point);
            transformedMinX = Math.min(transformedMinX, point.x);
            transformedMinY = Math.min(transformedMinY, point.y);
            transformedMinZ = Math.min(transformedMinZ, point.z);
            transformedMaxX = Math.max(transformedMaxX, point.x);
            transformedMaxY = Math.max(transformedMaxY, point.y);
            transformedMaxZ = Math.max(transformedMaxZ, point.z);
        }

        return new AABB(
                transformedMinX, transformedMinY, transformedMinZ,
                transformedMaxX, transformedMaxY, transformedMaxZ
        );
    }

    private static void addExactCube(
            Matrix4f transform,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            DrillModelCell.Builder[][][] builders,
            int size,
            int height,
            int mainOffsetX,
            int mainOffsetY,
            int mainOffsetZ
    ) {
        Vec3[] corners = {
                transformPoint(transform, minX, minY, minZ),
                transformPoint(transform, maxX, minY, minZ),
                transformPoint(transform, maxX, maxY, minZ),
                transformPoint(transform, minX, maxY, minZ),
                transformPoint(transform, minX, minY, maxZ),
                transformPoint(transform, maxX, minY, maxZ),
                transformPoint(transform, maxX, maxY, maxZ),
                transformPoint(transform, minX, maxY, maxZ)
        };
        int[][] faces = {
                {0, 3, 2, 1},
                {4, 5, 6, 7},
                {0, 1, 5, 4},
                {3, 7, 6, 2},
                {0, 4, 7, 3},
                {1, 2, 6, 5}
        };

        for (int[] face : faces) {
            List<Vec3> polygon = List.of(
                    corners[face[0]], corners[face[1]], corners[face[2]], corners[face[3]]
            );
            addFaceToCells(polygon, builders, size, height, mainOffsetX, mainOffsetY, mainOffsetZ);
        }
    }

    private static void addFaceToCells(
            List<Vec3> face,
            DrillModelCell.Builder[][][] builders,
            int size,
            int height,
            int mainOffsetX,
            int mainOffsetY,
            int mainOffsetZ
    ) {
        for (int offsetX = 0; offsetX < size; offsetX++) {
            double cellX = offsetX - mainOffsetX;
            for (int offsetY = 0; offsetY < height; offsetY++) {
                double cellY = offsetY - mainOffsetY;
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    double cellZ = mainOffsetZ - offsetZ;
                    List<Vec3> clipped = clipPolygon(face, 0, cellX, true);
                    clipped = clipPolygon(clipped, 0, cellX + 1.0D, false);
                    clipped = clipPolygon(clipped, 1, cellY, true);
                    clipped = clipPolygon(clipped, 1, cellY + 1.0D, false);
                    clipped = clipPolygon(clipped, 2, cellZ, true);
                    clipped = clipPolygon(clipped, 2, cellZ + 1.0D, false);
                    if (clipped.size() < 3) {
                        continue;
                    }

                    List<Vec3> local = new ArrayList<>(clipped.size());
                    for (Vec3 point : clipped) {
                        local.add(new Vec3(
                                clampUnit(point.x - cellX),
                                clampUnit(point.y - cellY),
                                clampUnit(point.z - cellZ)
                        ));
                    }
                    builders[offsetX][offsetY][offsetZ].addPolygon(local);
                }
            }
        }
    }

    private static List<Vec3> clipPolygon(
            List<Vec3> input,
            int axis,
            double boundary,
            boolean keepGreater
    ) {
        if (input.isEmpty()) {
            return input;
        }

        List<Vec3> output = new ArrayList<>(input.size() + 1);
        Vec3 previous = input.getLast();
        double previousValue = axisValue(previous, axis);
        boolean previousInside = keepGreater
                ? previousValue >= boundary - EPSILON
                : previousValue <= boundary + EPSILON;

        for (Vec3 current : input) {
            double currentValue = axisValue(current, axis);
            boolean currentInside = keepGreater
                    ? currentValue >= boundary - EPSILON
                    : currentValue <= boundary + EPSILON;
            if (currentInside != previousInside) {
                double amount = (boundary - previousValue) / (currentValue - previousValue);
                output.add(previous.lerp(current, Mth.clamp(amount, 0.0D, 1.0D)));
            }
            if (currentInside) {
                output.add(current);
            }
            previous = current;
            previousValue = currentValue;
            previousInside = currentInside;
        }
        return output;
    }

    private static Vec3 transformPoint(Matrix4f transform, float x, float y, float z) {
        Vector3f point = new Vector3f(x, y, z);
        transform.transformPosition(point);
        return new Vec3(point.x, point.y, point.z);
    }

    private static double axisValue(Vec3 point, int axis) {
        return switch (axis) {
            case 0 -> point.x;
            case 1 -> point.y;
            case 2 -> point.z;
            default -> throw new IllegalArgumentException("Invalid axis " + axis);
        };
    }

    private static void addClippedToCells(
            AABB modelBox,
            PixelCell[][][] cells,
            int size,
            int height,
            int mainOffsetX,
            int mainOffsetY,
            int mainOffsetZ
    ) {
        for (int offsetX = 0; offsetX < size; offsetX++) {
            double cellX = offsetX - mainOffsetX;
            for (int offsetY = 0; offsetY < height; offsetY++) {
                double cellY = offsetY - mainOffsetY;
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    // For NORTH, increasing structure Z travels towards negative world Z.
                    double cellZ = mainOffsetZ - offsetZ;
                    DrillHitboxCellMath.Box clipped = DrillHitboxCellMath.clipToCell(
                            new DrillHitboxCellMath.Box(
                                    modelBox.minX, modelBox.minY, modelBox.minZ,
                                    modelBox.maxX, modelBox.maxY, modelBox.maxZ
                            ),
                            cellX,
                            cellY,
                            cellZ
                    );
                    if (clipped == null) {
                        continue;
                    }

                    cells[offsetX][offsetY][offsetZ].fill(clipped);
                }
            }
        }
    }

    private static VoxelShape[][][] fullBlocks(int size, int height) {
        VoxelShape[][][] result = new VoxelShape[size][height][size];
        for (int offsetX = 0; offsetX < size; offsetX++) {
            for (int offsetY = 0; offsetY < height; offsetY++) {
                for (int offsetZ = 0; offsetZ < size; offsetZ++) {
                    result[offsetX][offsetY][offsetZ] = Shapes.block();
                }
            }
        }
        return result;
    }

    private static JsonArray requiredArray(JsonObject object, String name) throws IOException {
        JsonArray array = optionalArray(object, name);
        if (array == null) {
            throw new IOException("Missing array '" + name + "'");
        }
        return array;
    }

    private static JsonArray optionalArray(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        String value = optionalString(object, name);
        if (value == null) {
            throw new IOException("Missing string '" + name + "'");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static Vector3f requiredVector(JsonObject object, String name) throws IOException {
        JsonArray array = optionalArray(object, name);
        if (array == null || array.size() != 3) {
            throw new IOException("Missing three-component vector '" + name + "'");
        }
        return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }

    private static Vector3f readVector(JsonObject object, String name) throws IOException {
        JsonArray array = optionalArray(object, name);
        if (array == null) {
            return new Vector3f();
        }
        if (array.size() != 3) {
            throw new IOException("Vector '" + name + "' must have three components");
        }
        return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }

    private static float optionalFloat(JsonObject object, String name, float fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsFloat();
    }

    private static float lerp(float start, float end, float amount) {
        return start + (end - start) * amount;
    }

    private static double clampUnit(double value) {
        return Math.clamp(value, 0.0D, 1.0D);
    }

    record LoadedHitboxes(VoxelShape[][][] collisionShapes, DrillModelCell[][][] exactCells) {
    }

    private record BoneDefinition(
            String name,
            String parent,
            Vector3f pivot,
            Vector3f rotation,
            List<CubeDefinition> cubes
    ) {
    }

    private record CubeDefinition(
            Vector3f origin,
            Vector3f size,
            Vector3f pivot,
            Vector3f rotation,
            float inflate
    ) {
    }

    /** A fixed 16x16x16 occupancy grid keeps generated shapes compact and startup work bounded. */
    private static final class PixelCell {
        private static final int RESOLUTION = 16;
        private static final int LAYER_SIZE = RESOLUTION * RESOLUTION;
        private final BitSet occupied = new BitSet(RESOLUTION * RESOLUTION * RESOLUTION);

        private void fill(DrillHitboxCellMath.Box box) {
            int minX = lowerPixel(box.minX());
            int minY = lowerPixel(box.minY());
            int minZ = lowerPixel(box.minZ());
            int maxX = upperPixel(box.maxX());
            int maxY = upperPixel(box.maxY());
            int maxZ = upperPixel(box.maxZ());

            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    occupied.set(index(minX, y, z), index(maxX, y, z));
                }
            }
        }

        private VoxelShape buildShape() {
            if (occupied.isEmpty()) {
                return Shapes.empty();
            }

            BitSet remaining = (BitSet) occupied.clone();
            VoxelShape shape = Shapes.empty();
            while (!remaining.isEmpty()) {
                int first = remaining.nextSetBit(0);
                int startX = first % RESOLUTION;
                int startZ = first / RESOLUTION % RESOLUTION;
                int startY = first / LAYER_SIZE;

                int endX = startX + 1;
                while (endX < RESOLUTION && remaining.get(index(endX, startY, startZ))) {
                    endX++;
                }

                int endZ = startZ + 1;
                while (endZ < RESOLUTION && rectangleIsSet(
                        remaining, startX, endX, startY, startY + 1, endZ, endZ + 1
                )) {
                    endZ++;
                }

                int endY = startY + 1;
                while (endY < RESOLUTION && rectangleIsSet(
                        remaining, startX, endX, endY, endY + 1, startZ, endZ
                )) {
                    endY++;
                }

                for (int y = startY; y < endY; y++) {
                    for (int z = startZ; z < endZ; z++) {
                        remaining.clear(index(startX, y, z), index(endX, y, z));
                    }
                }

                shape = Shapes.or(shape, Shapes.box(
                        (double) startX / RESOLUTION,
                        (double) startY / RESOLUTION,
                        (double) startZ / RESOLUTION,
                        (double) endX / RESOLUTION,
                        (double) endY / RESOLUTION,
                        (double) endZ / RESOLUTION
                ));
            }
            return shape.optimize();
        }

        private static boolean rectangleIsSet(
                BitSet bits,
                int minX,
                int maxX,
                int minY,
                int maxY,
                int minZ,
                int maxZ
        ) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    int start = index(minX, y, z);
                    int missing = bits.nextClearBit(start);
                    if (missing < index(maxX, y, z)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static int lowerPixel(double coordinate) {
            return Math.clamp((int) Math.floor(coordinate * RESOLUTION + EPSILON), 0, RESOLUTION - 1);
        }

        private static int upperPixel(double coordinate) {
            return Math.clamp((int) Math.ceil(coordinate * RESOLUTION - EPSILON), 1, RESOLUTION);
        }

        private static int index(int x, int y, int z) {
            return y * LAYER_SIZE + z * RESOLUTION + x;
        }
    }
}
