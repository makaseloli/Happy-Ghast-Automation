package io.github.makaseloli.ghastfsd.mixin;

import io.github.makaseloli.ghastfsd.content.GhastControlState;
import io.github.makaseloli.ghastfsd.content.GhastFsdTaskCarrier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HappyGhast.class)
public class HappyGhastControlMixin implements GhastFsdTaskCarrier {
    @Unique
    private boolean ghastfsd$hasTask;
    @Unique
    private String ghastfsd$couplingNext = "";
    @Unique
    private String ghastfsd$couplingPrevious = "";

    @Inject(method = "getControllingPassenger", at = @At("HEAD"), cancellable = true)
    private void ghastfsd$getControllingPassenger(CallbackInfoReturnable<LivingEntity> callback) {
        if (ghastfsd$shouldBlockControl()) {
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "getRiddenInput", at = @At("HEAD"), cancellable = true)
    private void ghastfsd$getRiddenInput(Player player, Vec3 input, CallbackInfoReturnable<Vec3> callback) {
        if (ghastfsd$shouldBlockControl()) {
            callback.setReturnValue(Vec3.ZERO);
        }
    }

    @Inject(method = "getRiddenRotation", at = @At("HEAD"), cancellable = true)
    private void ghastfsd$getRiddenRotation(LivingEntity passenger, CallbackInfoReturnable<Vec2> callback) {
        HappyGhast ghast = (HappyGhast) (Object) this;
        if (ghastfsd$shouldBlockControl()) {
            callback.setReturnValue(new Vec2(ghast.getXRot(), ghast.getYRot()));
        }
    }

    @Inject(method = "tickRidden", at = @At("HEAD"), cancellable = true)
    private void ghastfsd$tickRidden(Player player, Vec3 input, CallbackInfo callback) {
        if (ghastfsd$shouldBlockControl()) {
            callback.cancel();
        }
    }

    @Override
    public boolean ghastfsd$hasSyncedTask() {
        return ghastfsd$hasTask;
    }

    @Override
    public void ghastfsd$setSyncedTask(boolean hasTask) {
        ghastfsd$hasTask = hasTask;
    }

    @Override
    public String ghastfsd$syncedCouplingNext() {
        return ghastfsd$couplingNext;
    }

    @Override
    public void ghastfsd$setSyncedCouplingNext(String nextUuid) {
        ghastfsd$couplingNext = nextUuid == null ? "" : nextUuid;
    }

    @Override
    public String ghastfsd$syncedCouplingPrevious() {
        return ghastfsd$couplingPrevious;
    }

    @Override
    public void ghastfsd$setSyncedCouplingPrevious(String previousUuid) {
        ghastfsd$couplingPrevious = previousUuid == null ? "" : previousUuid;
    }

    @Unique
    private boolean ghastfsd$shouldBlockControl() {
        HappyGhast ghast = (HappyGhast) (Object) this;
        return GhastControlState.shouldBlockControl(ghast);
    }
}
