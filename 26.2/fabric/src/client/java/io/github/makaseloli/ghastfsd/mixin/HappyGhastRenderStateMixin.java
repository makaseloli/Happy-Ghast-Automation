package io.github.makaseloli.ghastfsd.mixin;

import io.github.makaseloli.ghastfsd.content.GhastCouplingRenderState;
import net.minecraft.client.renderer.entity.state.HappyGhastRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(HappyGhastRenderState.class)
public class HappyGhastRenderStateMixin implements GhastCouplingRenderState {
    @Unique
    private boolean ghastfsd$hasCouplingLine;
    @Unique
    private Vec3 ghastfsd$couplingOffset = Vec3.ZERO;
    @Unique
    private Vec3 ghastfsd$couplingDelta = Vec3.ZERO;
    @Unique
    private int ghastfsd$couplingStartBlockLight;
    @Unique
    private int ghastfsd$couplingEndBlockLight;
    @Unique
    private int ghastfsd$couplingStartSkyLight;
    @Unique
    private int ghastfsd$couplingEndSkyLight;

    @Override
    public boolean ghastfsd$hasCouplingLine() {
        return ghastfsd$hasCouplingLine;
    }

    @Override
    public Vec3 ghastfsd$couplingOffset() {
        return ghastfsd$couplingOffset;
    }

    @Override
    public Vec3 ghastfsd$couplingDelta() {
        return ghastfsd$couplingDelta;
    }

    @Override
    public int ghastfsd$couplingStartBlockLight() {
        return ghastfsd$couplingStartBlockLight;
    }

    @Override
    public int ghastfsd$couplingEndBlockLight() {
        return ghastfsd$couplingEndBlockLight;
    }

    @Override
    public int ghastfsd$couplingStartSkyLight() {
        return ghastfsd$couplingStartSkyLight;
    }

    @Override
    public int ghastfsd$couplingEndSkyLight() {
        return ghastfsd$couplingEndSkyLight;
    }

    @Override
    public void ghastfsd$setCouplingLine(Vec3 offset, Vec3 delta, int startBlockLight, int endBlockLight, int startSkyLight, int endSkyLight) {
        ghastfsd$hasCouplingLine = true;
        ghastfsd$couplingOffset = offset;
        ghastfsd$couplingDelta = delta;
        ghastfsd$couplingStartBlockLight = startBlockLight;
        ghastfsd$couplingEndBlockLight = endBlockLight;
        ghastfsd$couplingStartSkyLight = startSkyLight;
        ghastfsd$couplingEndSkyLight = endSkyLight;
    }

    @Override
    public void ghastfsd$clearCouplingLine() {
        ghastfsd$hasCouplingLine = false;
        ghastfsd$couplingOffset = Vec3.ZERO;
        ghastfsd$couplingDelta = Vec3.ZERO;
    }
}
