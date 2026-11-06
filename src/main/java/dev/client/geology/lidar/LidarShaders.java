package dev.client.geology.lidar;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.OresAndDrillsMod;
import java.io.IOException;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

/** The LiDAR point shader, loaded with the game's other core shaders. */
public final class LidarShaders {
    @Nullable private static ShaderInstance point;

    private LidarShaders() {}

    public static void register(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(
                        event.getResourceProvider(),
                        ResourceLocation.fromNamespaceAndPath(
                                OresAndDrillsMod.MOD_ID, "lidar_point"),
                        DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP),
                shader -> point = shader);
    }

    /** Null until resources loaded, or when a resource pack broke the shader. */
    @Nullable
    public static ShaderInstance point() {
        return point;
    }
}
