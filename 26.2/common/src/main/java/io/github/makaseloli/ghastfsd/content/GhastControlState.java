package io.github.makaseloli.ghastfsd.content;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;

public record GhastControlState(int entityId, boolean hasTask, String couplingNext, String couplingPrevious) {
    private static final int MAX_REACHABLE_CHAIN_LENGTH = 6;

    public static GhastControlState from(HappyGhast ghast) {
        return new GhastControlState(
            ghast.getId(),
            FsdTaskAttachment.hasStoredTask(ghast),
            GhastCouplingAttachment.next(ghast).map(UUID::toString).orElse(""),
            GhastCouplingAttachment.previous(ghast).map(UUID::toString).orElse("")
        );
    }

    public boolean active() {
        return hasTask || !couplingNext.isBlank() || !couplingPrevious.isBlank();
    }

    public void apply(Entity entity) {
        if (entity instanceof GhastFsdTaskCarrier carrier) {
            carrier.ghastfsd$setSyncedTask(hasTask);
            carrier.ghastfsd$setSyncedCouplingNext(couplingNext);
            carrier.ghastfsd$setSyncedCouplingPrevious(couplingPrevious);
        }
    }

    public static boolean shouldBlockControl(HappyGhast ghast) {
        return hasReachableChainTask(ghast) || isCoupledFollower(ghast);
    }

    public static boolean hasReachableChainTask(HappyGhast ghast) {
        HappyGhast head = reachableHead(ghast);
        Set<UUID> seen = new HashSet<>();
        HappyGhast current = head;
        while (current != null && seen.add(current.getUUID()) && seen.size() <= MAX_REACHABLE_CHAIN_LENGTH) {
            if (FsdTaskAttachment.hasTask(current)) {
                return true;
            }
            current = reachableLinkedGhast(current, next(current));
        }
        return false;
    }

    public static boolean isCoupledFollower(HappyGhast ghast) {
        return previous(ghast).isPresent();
    }

    private static HappyGhast reachableHead(HappyGhast ghast) {
        HappyGhast current = ghast;
        Set<UUID> seen = new HashSet<>();
        while (seen.add(current.getUUID()) && seen.size() <= MAX_REACHABLE_CHAIN_LENGTH) {
            HappyGhast previous = reachableLinkedGhast(current, previous(current));
            if (previous == null) {
                return current;
            }
            current = previous;
        }
        return current;
    }

    private static HappyGhast reachableLinkedGhast(HappyGhast ghast, Optional<UUID> id) {
        if (id.isEmpty()) {
            return null;
        }
        Entity entity = ghast.level().getEntity(id.get());
        return entity instanceof HappyGhast linkedGhast && linkedGhast.isAlive() ? linkedGhast : null;
    }

    private static Optional<UUID> previous(HappyGhast ghast) {
        if (ghast instanceof GhastFsdTaskCarrier carrier) {
            return parseUuid(carrier.ghastfsd$syncedCouplingPrevious());
        }
        return GhastCouplingAttachment.previous(ghast);
    }

    private static Optional<UUID> next(HappyGhast ghast) {
        if (ghast instanceof GhastFsdTaskCarrier carrier) {
            return parseUuid(carrier.ghastfsd$syncedCouplingNext());
        }
        return GhastCouplingAttachment.next(ghast);
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
