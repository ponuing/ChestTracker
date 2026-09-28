package red.jackf.chesttracker.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import red.jackf.chesttracker.impl.rendering.NameRenderer;

import java.util.Optional;
import java.util.OptionalDouble;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderLevelEnd(
            GraphicsResourceAllocator resourceAllocator,
            boolean renderOutline,
            CameraRenderState cameraRenderState,
            GpuBufferSlice terrainFog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            boolean consistentDepthRequired,
            CallbackInfo ci) {

        // Planning the tags
        NameRenderer.scheduleLabels();

        // Rendering the labels
        if (NameRenderer.hasScheduledLabels()) {
            Minecraft minecraft = Minecraft.getInstance();
            Camera cam = minecraft.gameRenderer.mainCamera();
            RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();

            RenderPass renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(
                            () -> "ChestTracker",
                            mainTarget.getColorTextureView(),
                            Optional.empty(),
                            mainTarget.getDepthTextureView(),
                            OptionalDouble.of(0.0)
                    );
            try {
                RenderSystem.bindDefaultUniforms(renderPass);
                NameRenderer.renderWorld(cam, renderPass);
            } finally {
                renderPass.close();
            }

            // has to happen after the pass is closed, the buffer pools create a fence here
            NameRenderer.endFrame();

            NameRenderer.clearScheduledLabels();
        }
    }
}
