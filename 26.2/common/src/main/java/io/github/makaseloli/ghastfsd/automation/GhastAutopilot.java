package io.github.makaseloli.ghastfsd.automation;

import io.github.makaseloli.ghastfsd.content.GhastFsdContent;
import io.github.makaseloli.ghastfsd.content.GhastCouplingAttachment;
import io.github.makaseloli.ghastfsd.content.FsdTaskAttachment;
import io.github.makaseloli.ghastfsd.content.FsdTaskNotifier;
import io.github.makaseloli.ghastfsd.network.GhastControlSync;
import io.github.makaseloli.ghastfsd.route.RouteData;
import io.github.makaseloli.ghastfsd.route.RouteInstruction;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

public final class GhastAutopilot {
    private static final String AI_DISABLED_BY_FSD = "ghastfsd_ai_disabled";
    private static final int DISCOVERY_SCAN_INTERVAL_TICKS = 20;
    private static final Set<UUID> ACTIVE_GHASTS = new HashSet<>();

    private GhastAutopilot() {}

    static double autopilotCruiseSpeed() {
        return GhastFlightController.cruiseSpeed();
    }

    public static void initializeAttachedTask(HappyGhast ghast, int focus) {
        ghast.setPersistenceRequired();
        AutopilotState.reset(ghast, focus);
        ACTIVE_GHASTS.add(ghast.getUUID());
        if (ghast.level() instanceof ServerLevel serverLevel) {
            VirtualGhastTracker.remove(serverLevel.getServer(), ghast.getUUID());
        }
    }

    public static void tickServer(MinecraftServer server) {
        VirtualGhastTracker.beginServerTick(server);
        Set<UUID> seen = VirtualGhastTracker.seenSet();
        if (server.getTickCount() % DISCOVERY_SCAN_INTERVAL_TICKS == 0) {
            tickDiscoveryScan(server, seen);
        } else {
            tickActiveGhasts(server, seen);
        }
        VirtualGhastTracker.tickUnloaded(server, seen);
    }

    private static void tickDiscoveryScan(MinecraftServer server, Set<UUID> seen) {
        for (ServerLevel level : server.getAllLevels()) {
            for (HappyGhast ghast : level.getEntities(EntityTypes.HAPPY_GHAST, ghast -> true)) {
                seen.add(ghast.getUUID());
                tickGhast(level, ghast, true);
            }
        }
    }

    private static void tickActiveGhasts(MinecraftServer server, Set<UUID> seen) {
        Set<UUID> candidates = new HashSet<>(ACTIVE_GHASTS);
        candidates.addAll(VirtualGhastTracker.trackedIds(server));
        for (UUID id : candidates) {
            HappyGhast ghast = loadedGhast(server, id);
            if (ghast == null || !(ghast.level() instanceof ServerLevel level)) {
                continue;
            }
            seen.add(id);
            tickGhast(level, ghast, false);
        }
    }

    private static void tickGhast(ServerLevel level, HappyGhast ghast, boolean fullScan) {
        GhastCouplingAttachment.syncCouplingData(ghast);
        if (fullScan) {
            GhastControlSync.sync(ghast);
        }
        migrateBodyTaskToCarrier(level, ghast);
        GhastCouplingAttachment.moveTaskToHead(level, ghast);
        if (fullScan) {
            GhastControlSync.syncChain(level, ghast);
        }
        ItemStack task = FsdTaskAttachment.getTask(ghast);
        if (task.getItem() != GhastFsdContent.FSD_TASK) {
            tickWithoutTask(level, ghast, fullScan);
            return;
        }
        tickTaskCarrier(level, ghast, task);
    }

    private static void migrateBodyTaskToCarrier(ServerLevel level, HappyGhast ghast) {
        ItemStack task = ghast.getItemBySlot(EquipmentSlot.BODY);
        if (task.getItem() != GhastFsdContent.FSD_TASK || GhastCouplingAttachment.hasChainTask(level, ghast)) {
            return;
        }
        HappyGhast carrier = GhastCouplingAttachment.chainHead(level, ghast);
        ghast.setItemSlot(EquipmentSlot.BODY, ItemStack.EMPTY);
        FsdTaskAttachment.setTask(carrier, task);
        GhastControlSync.syncChain(level, carrier);
        initializeAttachedTask(carrier, RouteData.focus(task));
    }

    private static void tickWithoutTask(ServerLevel level, HappyGhast ghast, boolean fullScan) {
        GhastFlightController.clear(ghast);
        GhastStationNavigator.clear(ghast);
        FsdTaskAttachment.syncTaskFlag(ghast, false);
        syncNoAi(ghast, false);
        UUID previousId = GhastCouplingAttachment.previous(ghast).orElse(null);
        boolean coupled = previousId != null || GhastCouplingAttachment.next(ghast).isPresent();
        Vec3 virtualPosition = previousId != null && level.getEntity(previousId) == null
            ? VirtualGhastTracker.followVirtualCoupling(ghast.getUUID())
            : VirtualGhastTracker.unloadedPosition(ghast.getUUID());
        if (virtualPosition != null && virtualPosition.distanceToSqr(ghast.position()) > 16.0) {
            ghast.setPos(virtualPosition);
            ghast.setDeltaMovement(Vec3.ZERO);
        }
        if (coupled && GhastCouplingAttachment.tick(level, ghast)) {
            ghast.setPersistenceRequired();
            ACTIVE_GHASTS.add(ghast.getUUID());
            syncVirtualCoupled(level, ghast);
            return;
        }
        if (virtualPosition != null) {
            VirtualGhastTracker.remove(ghast.getUUID());
        }
        ACTIVE_GHASTS.remove(ghast.getUUID());
        if (fullScan) {
            tickFsdTaskTemptation(level, ghast);
        }
    }

    private static void tickTaskCarrier(ServerLevel level, HappyGhast ghast, ItemStack task) {
        ghast.setPersistenceRequired();
        ACTIVE_GHASTS.add(ghast.getUUID());
        FsdTaskAttachment.syncTaskFlag(ghast, true);
        syncNoAi(ghast, true);
        List<RouteInstruction> route = RouteData.read(task);
        if (route.isEmpty()) {
            GhastFlightController.clear(ghast);
            GhastStationNavigator.clear(ghast);
            VirtualGhastTracker.remove(ghast.getUUID());
            syncVirtualTrain(level, ghast);
            return;
        }

        AutopilotState state = AutopilotState.read(ghast);
        int oldIndex = state.index;
        int oldWaitTicks = state.waitTicks;
        int oldPauseTicks = state.pauseTicks;
        boolean oldDocked = state.docked;
        AutopilotPhase oldPhase = state.phase;
        int oldPhaseTicks = state.phaseTicks;
        boolean restoredFromVirtual = VirtualGhastTracker.restoreUnloaded(ghast, state);
        if (state.index < 0 || state.index >= route.size()) {
            state.index = Math.min(Math.max(0, RouteData.focus(task)), route.size() - 1);
            state.waitTicks = 0;
            state.pauseTicks = 0;
            state.docked = false;
            state.resetNavigation();
        }
        if (state.pauseTicks > 0) {
            state.pauseTicks--;
            state.write(ghast);
            VirtualGhastTracker.syncLoaded(ghast.getUUID(), level.dimension(), ghast.position(), ghastBottomOffset(ghast), GhastSpeedResolver.speed(ghast), ghast.getYRot(), GhastCouplingAttachment.previous(ghast).orElse(null), GhastCouplingAttachment.next(ghast).orElse(null), FsdTaskNotifier.ownerUuid(task), FsdTaskNotifier.groupName(task), state, route, playerPassengerCount(ghast), RouteData.loop(task));
            syncVirtualTrain(level, ghast);
            return;
        }

        RouteInstruction instruction = route.get(state.index);
        boolean taskChanged = switch (instruction.type()) {
            case "fly_to_station" -> GhastStationNavigator.tick(level, ghast, task, state, instruction, route.size(), RouteData.loop(task));
            default -> {
                AutopilotRouteProgress.advance(state, route.size(), RouteData.loop(task));
                yield false;
            }
        };
        if (RouteData.focus(task) != state.index) {
            RouteData.setFocus(task, state.index);
            taskChanged = true;
        }
        if (taskChanged) {
            FsdTaskAttachment.setTask(ghast, task);
            GhastControlSync.sync(ghast);
        }
        if (restoredFromVirtual
            || oldIndex != state.index
            || oldWaitTicks != state.waitTicks
            || oldPauseTicks != state.pauseTicks
            || oldDocked != state.docked
            || oldPhase != state.phase
            || oldPhaseTicks != state.phaseTicks) {
            state.write(ghast);
        }
        VirtualGhastTracker.syncLoaded(ghast.getUUID(), level.dimension(), ghast.position(), ghastBottomOffset(ghast), GhastSpeedResolver.speed(ghast), ghast.getYRot(), GhastCouplingAttachment.previous(ghast).orElse(null), GhastCouplingAttachment.next(ghast).orElse(null), FsdTaskNotifier.ownerUuid(task), FsdTaskNotifier.groupName(task), state, route, playerPassengerCount(ghast), RouteData.loop(task));
        syncVirtualTrain(level, ghast);
    }

    private static double ghastBottomOffset(HappyGhast ghast) {
        return ghast.getY() - ghast.getBoundingBox().minY;
    }

    private static int playerPassengerCount(HappyGhast ghast) {
        return (int) ghast.getPassengers().stream().filter(Player.class::isInstance).count();
    }

    private static HappyGhast loadedGhast(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof HappyGhast ghast && ghast.isAlive()) {
                return ghast;
            }
        }
        return null;
    }

    private static void syncVirtualTrain(ServerLevel level, HappyGhast head) {
        Set<UUID> seen = new HashSet<>();
        HappyGhast current = head;
        while (seen.add(current.getUUID())) {
            GhastCouplingAttachment.syncCouplingData(current);
            GhastControlSync.sync(current);
            UUID nextId = GhastCouplingAttachment.next(current).orElse(null);
            if (nextId == null) {
                return;
            }
            Entity next = level.getEntity(nextId);
            if (!(next instanceof HappyGhast nextGhast) || !nextGhast.isAlive()) {
                return;
            }
            current = nextGhast;
            syncVirtualCoupled(level, current);
        }
    }

    private static void syncVirtualCoupled(ServerLevel level, HappyGhast ghast) {
        VirtualGhastTracker.syncCoupled(
            ghast.getUUID(),
            level.dimension(),
            ghast.position(),
            ghastBottomOffset(ghast),
            GhastSpeedResolver.speed(ghast),
            ghast.getYRot(),
            GhastCouplingAttachment.previous(ghast).orElse(null),
            GhastCouplingAttachment.next(ghast).orElse(null)
        );
    }

    private static void tickFsdTaskTemptation(ServerLevel level, HappyGhast ghast) {
        if (!ghast.isAlive() || ghast.isBaby() || FsdTaskAttachment.hasTask(ghast)) {
            return;
        }
        Player target = null;
        double bestDistance = 16.0 * 16.0;
        for (Player player : level.players()) {
            if (player.isSpectator() || !holdsFsdTask(player)) {
                continue;
            }
            double distance = player.distanceToSqr(ghast);
            if (distance < bestDistance) {
                bestDistance = distance;
                target = player;
            }
        }
        if (target == null) {
            return;
        }
        Vec3 wanted = target.position().add(0.0, 2.5, 0.0);
        ghast.getLookControl().setLookAt(target, 30.0F, 30.0F);
        ghast.getMoveControl().setWantedPosition(wanted.x, wanted.y, wanted.z, 1.25);
        if (bestDistance > 9.0) {
            Vec3 delta = wanted.subtract(ghast.position());
            if (delta.lengthSqr() > 0.0001) {
                ghast.setDeltaMovement(ghast.getDeltaMovement().scale(0.75).add(delta.normalize().scale(0.08)));
                ghast.setNoGravity(true);
                ghast.hurtMarked = true;
            }
        }
    }

    private static boolean holdsFsdTask(Player player) {
        return player.getMainHandItem().getItem() == GhastFsdContent.FSD_TASK
            || player.getOffhandItem().getItem() == GhastFsdContent.FSD_TASK;
    }

    private static void syncNoAi(HappyGhast ghast, boolean shouldDisable) {
        if (shouldDisable) {
            if (!ghast.isNoAi()) {
                ghast.setNoAi(true);
            }
            CompoundTag tag = ghast.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (tag.getBooleanOr(AI_DISABLED_BY_FSD, false)) {
                return;
            }
            tag.putBoolean(AI_DISABLED_BY_FSD, true);
            ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            return;
        }

        CustomData data = ghast.get(DataComponents.CUSTOM_DATA);
        boolean disabledByFsd = data != null && data.copyTag().getBooleanOr(AI_DISABLED_BY_FSD, false);
        if (disabledByFsd) {
            ghast.setNoAi(false);
            CompoundTag tag = data.copyTag();
            tag.remove(AI_DISABLED_BY_FSD);
            ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }
}
