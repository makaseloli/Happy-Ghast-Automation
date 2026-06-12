package io.github.makaseloli.ghastfsd.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.makaseloli.ghastfsd.content.GhastCouplingRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityRendererSubmitMixin {
    private static final int GHASTFSD_COUPLING_STEPS = 24;
    private static final float GHASTFSD_COUPLING_WIDTH = 0.28F;

    @Inject(method = "submit", at = @At("TAIL"))
    private void ghastfsd$submitCouplingLine(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraState, CallbackInfo callback) {
        if (!(state instanceof GhastCouplingRenderState coupling) || !coupling.ghastfsd$hasCouplingLine()) {
            return;
        }
        Vec3 offset = coupling.ghastfsd$couplingOffset();
        Vec3 delta = coupling.ghastfsd$couplingDelta();
        int startBlockLight = coupling.ghastfsd$couplingStartBlockLight();
        int endBlockLight = coupling.ghastfsd$couplingEndBlockLight();
        int startSkyLight = coupling.ghastfsd$couplingStartSkyLight();
        int endSkyLight = coupling.ghastfsd$couplingEndSkyLight();
        collector.submitCustomGeometry(poseStack, RenderTypes.leash(), (pose, buffer) -> renderCouplingLine(
            pose.pose(),
            buffer,
            offset,
            delta,
            startBlockLight,
            endBlockLight,
            startSkyLight,
            endSkyLight
        ));
    }

    private static void renderCouplingLine(Matrix4fc matrix, VertexConsumer buffer, Vec3 offset, Vec3 delta, int startBlockLight, int endBlockLight, int startSkyLight, int endSkyLight) {
        float dx = (float)delta.x;
        float dy = (float)delta.y;
        float dz = (float)delta.z;
        float horizontalLengthSqr = dx * dx + dz * dz;
        float halfWidth = GHASTFSD_COUPLING_WIDTH * 0.5F;
        float xOffset = 0.0F;
        float zOffset = halfWidth;
        if (horizontalLengthSqr > 1.0E-6F) {
            float scale = (float)(1.0 / Math.sqrt(horizontalLengthSqr)) * halfWidth;
            xOffset = dz * scale;
            zOffset = dx * scale;
        }
        for (int step = 0; step <= GHASTFSD_COUPLING_STEPS; step++) {
            addVertexPair(matrix, buffer, offset, dx, dy, dz, xOffset, zOffset, step, false, startBlockLight, endBlockLight, startSkyLight, endSkyLight);
        }
        for (int step = GHASTFSD_COUPLING_STEPS; step >= 0; step--) {
            addVertexPair(matrix, buffer, offset, dx, dy, dz, xOffset, zOffset, step, true, startBlockLight, endBlockLight, startSkyLight, endSkyLight);
        }
    }

    private static void addVertexPair(Matrix4fc matrix, VertexConsumer buffer, Vec3 offset, float dx, float dy, float dz, float xOffset, float zOffset, int step, boolean reverse, int startBlockLight, int endBlockLight, int startSkyLight, int endSkyLight) {
        float progress = (float)step / GHASTFSD_COUPLING_STEPS;
        int blockLight = (int)(startBlockLight + (endBlockLight - startBlockLight) * progress);
        int skyLight = (int)(startSkyLight + (endSkyLight - startSkyLight) * progress);
        int light = LightCoordsUtil.pack(blockLight, skyLight);
        float shade = ((step & 1) == (reverse ? 1 : 0)) ? 0.78F : 1.0F;
        float red = 0.55F * shade;
        float green = 0.42F * shade;
        float blue = 0.28F * shade;
        float x = (float)offset.x + dx * progress;
        float y = (float)offset.y + dy * progress;
        float z = (float)offset.z + dz * progress;
        buffer.addVertex(matrix, x - xOffset, y, z + zOffset).setColor(red, green, blue, 1.0F).setLight(light);
        buffer.addVertex(matrix, x + xOffset, y + GHASTFSD_COUPLING_WIDTH, z - zOffset).setColor(red, green, blue, 1.0F).setLight(light);
    }
}
