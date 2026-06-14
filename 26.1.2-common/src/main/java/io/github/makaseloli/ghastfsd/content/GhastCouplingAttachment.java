package io.github.makaseloli.ghastfsd.content;

import io.github.makaseloli.ghastfsd.automation.GhastFlightController;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class GhastCouplingAttachment {
    private static final String ROOT = "ghastfsd_coupling";
    private static final String PREVIOUS = "previous";
    private static final String NEXT = "next";
    private static final String AI_DISABLED_BY_COUPLING = "ghastfsd_coupling_ai_disabled";
    private static final int MAX_CHAIN_LENGTH = 6;
    private static final double GAP = 1.5;
    private static final double FOLLOW_SPEED = 0.65;

    private GhastCouplingAttachment() {}

    public static boolean isCoupled(HappyGhast ghast) {
        return previous(ghast).isPresent() || next(ghast).isPresent();
    }

    public static boolean tick(ServerLevel level, HappyGhast ghast) {
        syncCouplingData(ghast);
        Optional<UUID> previousId = previous(ghast);
        Optional<UUID> nextId = next(ghast);
        if (previousId.isEmpty() && nextId.isEmpty()) {
            syncNoAi(ghast, false);
            return false;
        }
        syncNoAi(ghast, true);
        if (previousId.isEmpty()) {
            return true;
        }
        Entity previous = level.getEntity(previousId.get());
        if (!(previous instanceof HappyGhast previousGhast) || !previousGhast.isAlive()) {
            return true;
        }
        GhastFlightController.follow(level, ghast, previousGhast, GAP, FOLLOW_SPEED);
        return true;
    }

    public static boolean canCouple(HappyGhast ghast) {
        return ghast.isAlive() && !ghast.isBaby();
    }

    public static Optional<UUID> previous(HappyGhast ghast) {
        return readUuid(ghast, PREVIOUS);
    }

    public static Optional<UUID> next(HappyGhast ghast) {
        return readUuid(ghast, NEXT);
    }

    public static void syncCouplingData(HappyGhast ghast) {
        if (ghast instanceof GhastFsdTaskCarrier carrier) {
            String next = next(ghast).map(UUID::toString).orElse("");
            String previous = previous(ghast).map(UUID::toString).orElse("");
            if (!next.equals(carrier.ghastfsd$syncedCouplingNext())) {
                carrier.ghastfsd$setSyncedCouplingNext(next);
            }
            if (!previous.equals(carrier.ghastfsd$syncedCouplingPrevious())) {
                carrier.ghastfsd$setSyncedCouplingPrevious(previous);
            }
        }
    }

    public static int chainLength(ServerLevel level, HappyGhast ghast) {
        HappyGhast head = head(level, ghast);
        return chain(level, head).size();
    }

    public static List<HappyGhast> chainMembers(ServerLevel level, HappyGhast ghast) {
        return List.copyOf(chain(level, head(level, ghast)));
    }

    public static HappyGhast chainHead(ServerLevel level, HappyGhast ghast) {
        return head(level, ghast);
    }

    public static boolean hasChainTask(ServerLevel level, HappyGhast ghast) {
        return taskCarrier(level, ghast).isPresent();
    }

    public static Optional<HappyGhast> taskCarrier(ServerLevel level, HappyGhast ghast) {
        for (HappyGhast member : chain(level, head(level, ghast))) {
            if (FsdTaskAttachment.getTask(member).getItem() == GhastFsdContent.FSD_TASK) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    public static void moveTaskToHead(ServerLevel level, HappyGhast ghast) {
        HappyGhast head = head(level, ghast);
        Optional<HappyGhast> carrier = taskCarrier(level, ghast);
        if (carrier.isEmpty() || carrier.get().getUUID().equals(head.getUUID())) {
            return;
        }
        ItemStack task = FsdTaskAttachment.removeTask(carrier.get());
        FsdTaskAttachment.setTask(head, task);
    }

    public static boolean sameChain(ServerLevel level, HappyGhast first, HappyGhast second) {
        UUID secondId = second.getUUID();
        for (HappyGhast member : chain(level, head(level, first))) {
            if (member.getUUID().equals(secondId)) {
                return true;
            }
        }
        return false;
    }

    public static boolean link(ServerLevel level, HappyGhast first, HappyGhast second) {
        if (!canCouple(first) || !canCouple(second) || sameChain(level, first, second)) {
            return false;
        }
        HappyGhast tail = tail(level, first);
        HappyGhast head = head(level, second);
        if (tail.getUUID().equals(head.getUUID()) || next(tail).isPresent() || previous(head).isPresent()) {
            return false;
        }
        int totalLength = chain(level, head(level, first)).size() + chain(level, head).size();
        int taskCount = taskCount(level, first) + taskCount(level, second);
        if (totalLength > MAX_CHAIN_LENGTH || taskCount > 1) {
            return false;
        }
        setNext(tail, head.getUUID());
        setPrevious(head, tail.getUUID());
        moveTaskToHead(level, first);
        syncCouplingData(tail);
        syncCouplingData(head);
        syncNoAi(tail, true);
        syncNoAi(head, true);
        return true;
    }

    public static boolean cutAt(HappyGhast ghast) {
        Optional<UUID> previousId = previous(ghast);
        if (previousId.isPresent() && ghast.level() instanceof ServerLevel level && level.getEntity(previousId.get()) instanceof HappyGhast previousGhast) {
            clearNext(previousGhast);
            clearPrevious(ghast);
            syncCouplingData(previousGhast);
            syncCouplingData(ghast);
            syncNoAi(previousGhast, isCoupled(previousGhast));
            syncNoAi(ghast, isCoupled(ghast));
            return true;
        }
        Optional<UUID> nextId = next(ghast);
        if (nextId.isPresent() && ghast.level() instanceof ServerLevel level && level.getEntity(nextId.get()) instanceof HappyGhast nextGhast) {
            clearNext(ghast);
            clearPrevious(nextGhast);
            syncCouplingData(ghast);
            syncCouplingData(nextGhast);
            syncNoAi(ghast, isCoupled(ghast));
            syncNoAi(nextGhast, isCoupled(nextGhast));
            return true;
        }
        clearCoupling(ghast);
        syncCouplingData(ghast);
        syncNoAi(ghast, false);
        return false;
    }

    private static int taskCount(ServerLevel level, HappyGhast ghast) {
        int count = 0;
        for (HappyGhast member : chain(level, head(level, ghast))) {
            if (FsdTaskAttachment.getTask(member).getItem() == GhastFsdContent.FSD_TASK) {
                count++;
            }
        }
        return count;
    }

    private static List<HappyGhast> chain(ServerLevel level, HappyGhast head) {
        List<HappyGhast> chain = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        HappyGhast current = head;
        while (current != null && seen.add(current.getUUID()) && chain.size() <= MAX_CHAIN_LENGTH) {
            chain.add(current);
            Optional<UUID> nextId = next(current);
            if (nextId.isEmpty()) {
                break;
            }
            Entity next = level.getEntity(nextId.get());
            current = next instanceof HappyGhast nextGhast && nextGhast.isAlive() ? nextGhast : null;
        }
        return chain;
    }

    private static HappyGhast head(ServerLevel level, HappyGhast ghast) {
        HappyGhast current = ghast;
        Set<UUID> seen = new HashSet<>();
        while (seen.add(current.getUUID())) {
            Optional<UUID> previousId = previous(current);
            if (previousId.isEmpty()) {
                return current;
            }
            Entity previous = level.getEntity(previousId.get());
            if (!(previous instanceof HappyGhast previousGhast) || !previousGhast.isAlive()) {
                return current;
            }
            current = previousGhast;
        }
        clearPrevious(current);
        return current;
    }

    private static HappyGhast tail(ServerLevel level, HappyGhast ghast) {
        HappyGhast current = ghast;
        Set<UUID> seen = new HashSet<>();
        while (seen.add(current.getUUID())) {
            Optional<UUID> nextId = next(current);
            if (nextId.isEmpty()) {
                return current;
            }
            Entity next = level.getEntity(nextId.get());
            if (!(next instanceof HappyGhast nextGhast) || !nextGhast.isAlive()) {
                return current;
            }
            current = nextGhast;
        }
        clearNext(current);
        return current;
    }

    private static Optional<UUID> readUuid(HappyGhast ghast, String key) {
        CustomData data = ghast.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return Optional.empty();
        }
        String value = data.copyTag().getCompoundOrEmpty(ROOT).getStringOr(key, "");
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static void setPrevious(HappyGhast ghast, UUID previous) {
        writeUuid(ghast, PREVIOUS, previous);
        syncCouplingData(ghast);
    }

    private static void setNext(HappyGhast ghast, UUID next) {
        writeUuid(ghast, NEXT, next);
        syncCouplingData(ghast);
    }

    private static void clearPrevious(HappyGhast ghast) {
        removeUuid(ghast, PREVIOUS);
        syncCouplingData(ghast);
    }

    private static void clearNext(HappyGhast ghast) {
        removeUuid(ghast, NEXT);
        syncCouplingData(ghast);
    }

    private static void clearCoupling(HappyGhast ghast) {
        CustomData current = ghast.get(DataComponents.CUSTOM_DATA);
        if (current == null) {
            return;
        }
        CompoundTag tag = current.copyTag();
        tag.remove(ROOT);
        ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void writeUuid(HappyGhast ghast, String key, UUID uuid) {
        CustomData current = ghast.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = current == null ? new CompoundTag() : current.copyTag();
        CompoundTag root = tag.getCompoundOrEmpty(ROOT).copy();
        root.putString(key, uuid.toString());
        tag.put(ROOT, root);
        ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void removeUuid(HappyGhast ghast, String key) {
        CustomData current = ghast.get(DataComponents.CUSTOM_DATA);
        if (current == null) {
            return;
        }
        CompoundTag tag = current.copyTag();
        CompoundTag root = tag.getCompoundOrEmpty(ROOT).copy();
        root.remove(key);
        if (root.isEmpty()) {
            tag.remove(ROOT);
        } else {
            tag.put(ROOT, root);
        }
        ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void syncNoAi(HappyGhast ghast, boolean shouldDisable) {
        if (shouldDisable) {
            if (!ghast.isNoAi()) {
                ghast.setNoAi(true);
            }
            CompoundTag tag = ghast.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (tag.getBooleanOr(AI_DISABLED_BY_COUPLING, false)) {
                return;
            }
            tag.putBoolean(AI_DISABLED_BY_COUPLING, true);
            ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            return;
        }

        CustomData data = ghast.get(DataComponents.CUSTOM_DATA);
        boolean disabledByCoupling = data != null && data.copyTag().getBooleanOr(AI_DISABLED_BY_COUPLING, false);
        if (disabledByCoupling) {
            ghast.setNoAi(false);
            CompoundTag tag = data.copyTag();
            tag.remove(AI_DISABLED_BY_COUPLING);
            ghast.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }
}
