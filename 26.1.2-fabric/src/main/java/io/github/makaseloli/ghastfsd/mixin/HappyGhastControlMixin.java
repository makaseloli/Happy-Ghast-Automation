package io.github.makaseloli.ghastfsd.mixin;

import io.github.makaseloli.ghastfsd.content.GhastControlState;
import io.github.makaseloli.ghastfsd.content.GhastFsdTaskCarrier;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
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
    private static final EntityDataAccessor<Boolean> GHASTFSD_HAS_TASK = SynchedEntityData.defineId(HappyGhast.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> GHASTFSD_COUPLING_NEXT = SynchedEntityData.defineId(HappyGhast.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> GHASTFSD_COUPLING_PREVIOUS = SynchedEntityData.defineId(HappyGhast.class, EntityDataSerializers.STRING);

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void ghastfsd$defineSynchedData(SynchedEntityData.Builder builder, org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        builder.define(GHASTFSD_HAS_TASK, false);
        builder.define(GHASTFSD_COUPLING_NEXT, "");
        builder.define(GHASTFSD_COUPLING_PREVIOUS, "");
    }

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
        return ((HappyGhast) (Object) this).getEntityData().get(GHASTFSD_HAS_TASK);
    }

    @Override
    public void ghastfsd$setSyncedTask(boolean hasTask) {
        ((HappyGhast) (Object) this).getEntityData().set(GHASTFSD_HAS_TASK, hasTask);
    }

    @Override
    public String ghastfsd$syncedCouplingNext() {
        return ((HappyGhast) (Object) this).getEntityData().get(GHASTFSD_COUPLING_NEXT);
    }

    @Override
    public void ghastfsd$setSyncedCouplingNext(String nextUuid) {
        ((HappyGhast) (Object) this).getEntityData().set(GHASTFSD_COUPLING_NEXT, nextUuid == null ? "" : nextUuid);
    }

    @Override
    public String ghastfsd$syncedCouplingPrevious() {
        return ((HappyGhast) (Object) this).getEntityData().get(GHASTFSD_COUPLING_PREVIOUS);
    }

    @Override
    public void ghastfsd$setSyncedCouplingPrevious(String previousUuid) {
        ((HappyGhast) (Object) this).getEntityData().set(GHASTFSD_COUPLING_PREVIOUS, previousUuid == null ? "" : previousUuid);
    }

    @Unique
    private boolean ghastfsd$shouldBlockControl() {
        HappyGhast ghast = (HappyGhast) (Object) this;
        return GhastControlState.shouldBlockControl(ghast);
    }
}
