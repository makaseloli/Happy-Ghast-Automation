package io.github.makaseloli.ghastfsd.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class GhastStationGeometry {
    public static final double APPROACH_RADIUS = 8.0;
    public static final double ARRIVAL_HORIZONTAL_RADIUS = 2.0;
    public static final double ARRIVAL_HEIGHT = 8.0;
    public static final double DOCKING_HORIZONTAL_TOLERANCE = 1.75;
    public static final double DOCKING_VERTICAL_TOLERANCE = 0.75;

    private GhastStationGeometry() {}

    public static AABB arrivalBox(BlockPos station) {
        return new AABB(
            station.getX() - ARRIVAL_HORIZONTAL_RADIUS,
            station.getY(),
            station.getZ() - ARRIVAL_HORIZONTAL_RADIUS,
            station.getX() + 1.0 + ARRIVAL_HORIZONTAL_RADIUS,
            station.getY() + GhastStationBlockEntity.MAX_DOCKING_HEIGHT + ARRIVAL_HEIGHT,
            station.getZ() + 1.0 + ARRIVAL_HORIZONTAL_RADIUS
        );
    }

    public static Vec3 dockingTarget(HappyGhast ghast, BlockPos station, int dockingHeight) {
        double bottomOffset = ghast.getY() - ghast.getBoundingBox().minY;
        return new Vec3(station.getX() + 0.5, station.getY() + dockingHeight + bottomOffset, station.getZ() + 0.5);
    }

    public static boolean isDockedAt(HappyGhast ghast, BlockPos station, Vec3 target, int dockingHeight) {
        if (!arrivalBox(station).intersects(ghast.getBoundingBox())) {
            return false;
        }
        double targetBottom = station.getY() + dockingHeight;
        double bottomError = Math.abs(ghast.getBoundingBox().minY - targetBottom);
        return horizontalDistance(ghast.position(), target) <= DOCKING_HORIZONTAL_TOLERANCE
            && bottomError <= DOCKING_VERTICAL_TOLERANCE;
    }

    public static double horizontalDistance(Vec3 first, Vec3 second) {
        double x = first.x - second.x;
        double z = first.z - second.z;
        return Math.sqrt(x * x + z * z);
    }
}
