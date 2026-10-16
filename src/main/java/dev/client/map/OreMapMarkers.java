package dev.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.OresAndDrillsMod;
import dev.network.DiscoveredOreDepositPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-local, per-world discovery store shared by every supported map renderer. */
public final class OreMapMarkers {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Long, OreMapPatch> PATCHES = new LinkedHashMap<>();
    private static String loadedWorld = "";
    private static Path loadedFile;
    private static boolean enabled = true;
    private static int revision;

    private OreMapMarkers() {
    }

    public static void discover(DiscoveredOreDepositPayload payload) {
        ensureWorld();
        DiscoveredOreDeposit marker = new DiscoveredOreDeposit(
                payload.depositId(), payload.dimension(), payload.center(), payload.oreId(),
                payload.tier(), payload.color(), payload.remainingOre(), payload.initialOre(),
                payload.cells().stream().map(cell -> new DiscoveredOreDeposit.OreMapCell(
                        cell.offsetX(), cell.offsetZ(), cell.remainingOre(), cell.initialOre()
                )).toList()
        );
        OreMapPatch previous = PATCHES.put(marker.id(), new OreMapPatch(marker));
        if (previous == null || !marker.equals(previous.deposit())) {
            revision++;
            save();
        }
    }

    /** Visible patches of one dimension; empty while the overlay is switched off. */
    public static List<OreMapPatch> inDimension(ResourceLocation dimension) {
        ensureWorld();
        if (!enabled) {
            return List.of();
        }
        ArrayList<OreMapPatch> result = new ArrayList<>();
        for (OreMapPatch patch : PATCHES.values()) {
            if (patch.dimension().equals(dimension) && !patch.isEmpty()) {
                result.add(patch);
            }
        }
        return result;
    }

    /** Every stored patch regardless of the toggle, for integrations that manage their own overlay objects. */
    public static Collection<OreMapPatch> all() {
        ensureWorld();
        return List.copyOf(PATCHES.values());
    }

    /** The newest version of a patch, or null while it is hidden, exhausted or unknown. */
    public static OreMapPatch visible(long id) {
        ensureWorld();
        OreMapPatch patch = PATCHES.get(id);
        return enabled && patch != null && !patch.isEmpty() ? patch : null;
    }

    /** The visible patch covering this block column, if any. */
    public static OreMapPatch patchAt(ResourceLocation dimension, int blockX, int blockZ) {
        for (OreMapPatch patch : inDimension(dimension)) {
            int dx = blockX - patch.center().getX();
            int dz = blockZ - patch.center().getZ();
            if (Math.abs(dx) <= patch.extent() && Math.abs(dz) <= patch.extent()
                    && patch.cellAt(blockX, blockZ) != null) {
                return patch;
            }
        }
        return null;
    }

    /** True when this column belongs to a patch the server refreshed within the last {@code maxAgeMillis}. */
    static boolean recentlyRefreshed(ResourceLocation dimension, BlockPos pos, long maxAgeMillis) {
        ensureWorld();
        long now = System.currentTimeMillis();
        for (OreMapPatch patch : PATCHES.values()) {
            if (patch.dimension().equals(dimension) && now - patch.receivedAtMillis() < maxAgeMillis
                    && patch.cellAt(pos.getX(), pos.getZ()) != null) {
                return true;
            }
        }
        return false;
    }

    public static boolean isEnabled() {
        ensureWorld();
        return enabled;
    }

    /** Increments whenever the stored patches or the toggle change; map layers rebuild when it moves. */
    public static int revision() {
        ensureWorld();
        return revision;
    }

    public static void toggle() {
        setEnabled(!isEnabled());
    }

    public static void setEnabled(boolean value) {
        ensureWorld();
        if (enabled == value) {
            return;
        }
        enabled = value;
        revision++;
        save();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    enabled ? "message.ores_and_drills.ore_map.enabled" : "message.ores_and_drills.ore_map.disabled"
            ), true);
        }
    }

    private static void ensureWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        String world = worldIdentity(minecraft);
        if (world.equals(loadedWorld)) {
            return;
        }
        loadedWorld = world;
        PATCHES.clear();
        enabled = true;
        revision++;
        loadedFile = minecraft.gameDirectory.toPath()
                .resolve("config")
                .resolve(OresAndDrillsMod.MOD_ID)
                .resolve("ore-map-" + digest(world) + ".json");
        load();
    }

    private static String worldIdentity(Minecraft minecraft) {
        if (minecraft.getSingleplayerServer() != null) {
            // The save folder, unlike the display name, is unique per world.
            return "world:" + minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath().normalize().getFileName();
        }
        if (minecraft.getCurrentServer() != null) {
            return "server:" + minecraft.getCurrentServer().ip;
        }
        return "menu";
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", bytes[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void load() {
        if (loadedFile == null || !Files.isRegularFile(loadedFile)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(loadedFile)).getAsJsonObject();
            enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
            JsonArray markers = root.getAsJsonArray("markers");
            if (markers == null) {
                return;
            }
            for (JsonElement element : markers) {
                JsonObject value = element.getAsJsonObject();
                ResourceLocation dimension = ResourceLocation.tryParse(value.get("dimension").getAsString());
                ResourceLocation ore = ResourceLocation.tryParse(value.get("ore").getAsString());
                if (dimension == null || ore == null) {
                    continue;
                }
                DiscoveredOreDeposit marker = new DiscoveredOreDeposit(
                        value.get("id").getAsLong(), dimension,
                        new BlockPos(value.get("x").getAsInt(), value.get("y").getAsInt(), value.get("z").getAsInt()),
                        ore, value.get("tier").getAsInt(), value.get("color").getAsInt(),
                        value.has("remaining") ? value.get("remaining").getAsLong() : 0L,
                        value.has("initial") ? value.get("initial").getAsLong() : 0L,
                        readCells(value)
                );
                // Loaded markers count as stale so the first look at them asks the server for fresh amounts.
                PATCHES.put(marker.id(), new OreMapPatch(marker, 0L));
            }
        } catch (RuntimeException | IOException exception) {
            OresAndDrillsMod.LOGGER.warn("Could not load discovered ore map markers from {}", loadedFile, exception);
        }
    }

    private static void save() {
        if (loadedFile == null || "menu".equals(loadedWorld)) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("enabled", enabled);
        JsonArray markers = new JsonArray();
        for (OreMapPatch patch : PATCHES.values()) {
            DiscoveredOreDeposit marker = patch.deposit();
            JsonObject value = new JsonObject();
            value.addProperty("id", marker.id());
            value.addProperty("dimension", marker.dimension().toString());
            value.addProperty("x", marker.center().getX());
            value.addProperty("y", marker.center().getY());
            value.addProperty("z", marker.center().getZ());
            value.addProperty("ore", marker.oreId().toString());
            value.addProperty("tier", marker.tier());
            value.addProperty("color", marker.color());
            value.addProperty("remaining", marker.remainingOre());
            value.addProperty("initial", marker.initialOre());
            JsonArray cells = new JsonArray();
            for (DiscoveredOreDeposit.OreMapCell cell : marker.cells()) {
                JsonObject cellValue = new JsonObject();
                cellValue.addProperty("x", cell.offsetX());
                cellValue.addProperty("z", cell.offsetZ());
                cellValue.addProperty("remaining", cell.remainingOre());
                cellValue.addProperty("initial", cell.initialOre());
                cells.add(cellValue);
            }
            value.add("cells", cells);
            markers.add(value);
        }
        root.add("markers", markers);
        try {
            Files.createDirectories(loadedFile.getParent());
            Files.writeString(loadedFile, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            OresAndDrillsMod.LOGGER.warn("Could not save discovered ore map markers to {}", loadedFile, exception);
        }
    }

    private static List<DiscoveredOreDeposit.OreMapCell> readCells(JsonObject value) {
        JsonArray cells = value.getAsJsonArray("cells");
        if (cells == null) {
            return List.of(new DiscoveredOreDeposit.OreMapCell(0, 0, 0, 0));
        }
        ArrayList<DiscoveredOreDeposit.OreMapCell> result = new ArrayList<>(cells.size());
        for (JsonElement element : cells) {
            JsonObject cell = element.getAsJsonObject();
            result.add(new DiscoveredOreDeposit.OreMapCell(
                    cell.get("x").getAsInt(), cell.get("z").getAsInt(),
                    cell.get("remaining").getAsInt(), cell.get("initial").getAsInt()
            ));
        }
        return List.copyOf(result);
    }
}
