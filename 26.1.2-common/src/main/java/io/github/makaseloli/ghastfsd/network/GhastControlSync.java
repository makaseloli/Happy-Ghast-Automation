package io.github.makaseloli.ghastfsd.network;

import io.github.makaseloli.ghastfsd.content.GhastControlState;
import io.github.makaseloli.ghastfsd.content.GhastCouplingAttachment;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public final class GhastControlSync {
    private static final double SYNC_RANGE_SQR = 4096.0;
    private static final Map<UUID, GhastControlState> LAST_STATES = new HashMap<>();

    private GhastControlSync() {}

    public static void sync(HappyGhast ghast) {
        if (!(ghast.level() instanceof ServerLevel level)) {
            return;
        }
        GhastControlState state = GhastControlState.from(ghast);
        GhastControlState previous = LAST_STATES.get(ghast.getUUID());
        if (!state.active()) {
            if (previous == null || !previous.active()) {
                return;
            }
            LAST_STATES.remove(ghast.getUUID());
        } else {
            LAST_STATES.put(ghast.getUUID(), state);
        }
        GhastFsdPayloads.GhastControlStatePayload payload = GhastFsdPayloads.GhastControlStatePayload.from(state);
        ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(payload);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(ghast) <= SYNC_RANGE_SQR) {
                player.connection.send(packet);
            }
        }
    }

    public static void syncChain(ServerLevel level, HappyGhast ghast) {
        HappyGhast current = GhastCouplingAttachment.chainHead(level, ghast);
        Set<UUID> seen = new HashSet<>();
        while (seen.add(current.getUUID())) {
            sync(current);
            UUID nextId = GhastCouplingAttachment.next(current).orElse(null);
            if (nextId == null) {
                return;
            }
            Entity next = level.getEntity(nextId);
            if (!(next instanceof HappyGhast nextGhast) || !nextGhast.isAlive()) {
                return;
            }
            current = nextGhast;
        }
    }

    public static void syncNearby(ServerLevel level, HappyGhast center) {
        AABB searchBox = center.getBoundingBox().inflate(Math.sqrt(SYNC_RANGE_SQR));
        for (HappyGhast ghast : level.getEntitiesOfClass(HappyGhast.class, searchBox, ghast -> ghast.isAlive() && ghast.distanceToSqr(center) <= SYNC_RANGE_SQR)) {
            sync(ghast);
        }
    }

    public static void apply(Level level, GhastControlState state) {
        Entity entity = level.getEntity(state.entityId());
        if (entity != null) {
            state.apply(entity);
        }
    }
}
