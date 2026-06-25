package io.github.makaseloli.ghastfsd.content;

import net.minecraft.world.phys.Vec3;

public interface GhastCouplingRenderState {
    boolean ghastfsd$hasCouplingLine();

    Vec3 ghastfsd$couplingOffset();

    Vec3 ghastfsd$couplingDelta();

    int ghastfsd$couplingStartBlockLight();

    int ghastfsd$couplingEndBlockLight();

    int ghastfsd$couplingStartSkyLight();

    int ghastfsd$couplingEndSkyLight();

    void ghastfsd$setCouplingLine(Vec3 offset, Vec3 delta, int startBlockLight, int endBlockLight, int startSkyLight, int endSkyLight);

    void ghastfsd$clearCouplingLine();
}
