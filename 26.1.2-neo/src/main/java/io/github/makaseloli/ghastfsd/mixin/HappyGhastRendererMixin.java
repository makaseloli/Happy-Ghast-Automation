package io.github.makaseloli.ghastfsd.mixin;

import io.github.makaseloli.ghastfsd.content.GhastCouplingAttachment;
import io.github.makaseloli.ghastfsd.content.GhastCouplingRenderState;
import io.github.makaseloli.ghastfsd.content.GhastFsdTaskCarrier;
import java.util.UUID;
import net.minecraft.client.renderer.entity.HappyGhastRenderer;
import net.minecraft.client.renderer.entity.state.HappyGhastRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HappyGhastRenderer.class)
public class HappyGhastRendererMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void ghastfsd$extractCouplingLeash(HappyGhast ghast, HappyGhastRenderState state, float partialTicks, CallbackInfo callback) {
        GhastCouplingRenderState couplingState = (GhastCouplingRenderState) state;
        Entity previous = syncedPrevious(ghast);
        if (!(previous instanceof HappyGhast previousGhast) || !previousGhast.isAlive()) {
            couplingState.ghastfsd$clearCouplingLine();
            return;
        }
        Vec3 startPosition = ghast.getPosition(partialTicks);
        Vec3 endPosition = previousGhast.getPosition(partialTicks);
        double startBottomOffset = ghast.getBoundingBox().minY - ghast.getY() + 0.05;
        double endBottomOffset = previousGhast.getBoundingBox().minY - previousGhast.getY() + 0.05;
        Vec3 startOffset = new Vec3(0.0, startBottomOffset, 0.0);
        Vec3 end = endPosition.add(0.0, endBottomOffset, 0.0);
        Vec3 start = startPosition.add(startOffset);
        BlockPos startBlock = BlockPos.containing(start);
        BlockPos endBlock = BlockPos.containing(end);
        couplingState.ghastfsd$setCouplingLine(
            startOffset,
            end.subtract(start),
            ghast.level().getBrightness(LightLayer.BLOCK, startBlock),
            previousGhast.level().getBrightness(LightLayer.BLOCK, endBlock),
            ghast.level().getBrightness(LightLayer.SKY, startBlock),
            previousGhast.level().getBrightness(LightLayer.SKY, endBlock)
        );
    }

    private static Entity syncedPrevious(HappyGhast ghast) {
        if (ghast instanceof GhastFsdTaskCarrier carrier) {
            String value = carrier.ghastfsd$syncedCouplingPrevious();
            if (value != null && !value.isBlank()) {
                try {
                    return ghast.level().getEntity(UUID.fromString(value));
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return GhastCouplingAttachment.previous(ghast).map(uuid -> ghast.level().getEntity(uuid)).orElse(null);
    }
}
