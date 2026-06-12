package io.github.makaseloli.ghastfsd.content;

import com.mojang.serialization.Codec;
import io.github.makaseloli.ghastfsd.ModUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

public final class FsdTaskNotifier {
    private static final String ROOT = "ghastfsd_task_owner";
    private static final String OWNER_UUID = "uuid";
    private static final String GROUP_NAME = "group_name";
    private static final String MISSING_STATION_NOTIFIED = "missing_station_notified";

    private FsdTaskNotifier() {}

    public static void rememberInstaller(ItemStack task, Player player) {
        CustomData.update(DataComponents.CUSTOM_DATA, task, tag -> {
            CompoundTag root = tag.getCompoundOrEmpty(ROOT);
            root.putString(OWNER_UUID, player.getUUID().toString());
            tag.put(ROOT, root);
        });
    }

    public static String groupName(ItemStack task) {
        CustomData data = task.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return "";
        }
        CompoundTag root = data.copyTag().getCompoundOrEmpty(ROOT);
        String name = root.getStringOr(GROUP_NAME, "");
        return sanitizeGroupName(name);
    }

    public static void setGroupName(ItemStack task, String name) {
        CustomData.update(DataComponents.CUSTOM_DATA, task, tag -> {
            CompoundTag root = tag.getCompoundOrEmpty(ROOT);
            String sanitized = sanitizeGroupName(name);
            if (sanitized.isBlank()) {
                root.remove(GROUP_NAME);
            } else {
                root.putString(GROUP_NAME, sanitized);
            }
            tag.put(ROOT, root);
        });
    }

    public static boolean notifyMissingStation(ServerLevel level, HappyGhast ghast, ItemStack task, String stationName) {
        UUID owner = ownerUuid(task);
        if (owner == null || wasMissingStationNotified(task, stationName)) {
            return false;
        }
        markMissingStationNotified(task, stationName);
        notifyStoppedAt(level.getServer(), owner, notificationName(task), ghast.position());
        return true;
    }

    public static boolean notifyResumed(ServerLevel level, HappyGhast ghast, ItemStack task) {
        UUID owner = ownerUuid(task);
        if (owner == null || !hasMissingStationNotification(task)) {
            return false;
        }
        clearMissingStationNotified(task);
        notifyResumedAt(level.getServer(), owner, notificationName(task), ghast.position());
        return true;
    }

    public static void notifyTaskRemovedBy(ServerLevel level, HappyGhast ghast, ItemStack task, Player remover) {
        UUID owner = ownerUuid(task);
        if (owner == null || owner.equals(remover.getUUID())) {
            return;
        }
        notifyStoppedAt(level.getServer(), owner, notificationName(task), ghast.position());
    }

    public static void notifyGhastKilled(MinecraftServer server, HappyGhast ghast, ItemStack task) {
        UUID owner = ownerUuid(task);
        if (owner != null) {
            notifyStoppedAt(server, owner, notificationName(task), ghast.position());
        }
    }

    public static void notifyStoppedAt(MinecraftServer server, UUID owner, String name, Vec3 position) {
        notifyOwner(
            server,
            owner,
            "message.ghastfsd.notify_task_stopped",
            notificationName(name),
            Integer.toString((int)Math.floor(position.x)),
            Integer.toString((int)Math.floor(position.y)),
            Integer.toString((int)Math.floor(position.z))
        );
    }

    public static void notifyResumedAt(MinecraftServer server, UUID owner, String name, Vec3 position) {
        notifyOwner(
            server,
            owner,
            "message.ghastfsd.notify_task_resumed",
            notificationName(name),
            Integer.toString((int)Math.floor(position.x)),
            Integer.toString((int)Math.floor(position.y)),
            Integer.toString((int)Math.floor(position.z))
        );
    }

    public static void deliverPending(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        Data data = data(level.getServer());
        List<PendingNotification> pending = data.pending.remove(player.getUUID());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        data.setDirty();
        for (PendingNotification notification : pending) {
            player.sendSystemMessage(notification.component());
        }
    }

    private static void notifyOwner(MinecraftServer server, UUID owner, String key, String... args) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        PendingNotification notification = new PendingNotification(key, List.of(args));
        if (player != null) {
            player.sendSystemMessage(notification.component());
            return;
        }
        Data data = data(server);
        data.pending.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(notification);
        data.setDirty();
    }

    public static UUID ownerUuid(ItemStack task) {
        CustomData data = task.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag root = data.copyTag().getCompoundOrEmpty(ROOT);
        String value = root.getStringOr(OWNER_UUID, "");
        if (value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static String notificationName(ItemStack task) {
        String groupName = groupName(task);
        return groupName.isBlank() ? "FSD" : groupName;
    }

    public static String notificationName(String name) {
        String sanitized = sanitizeGroupName(name);
        return sanitized.isBlank() ? "FSD" : sanitized;
    }

    private static boolean wasMissingStationNotified(ItemStack task, String stationName) {
        CustomData data = task.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag root = data.copyTag().getCompoundOrEmpty(ROOT);
        return safeName(stationName).equals(root.getStringOr(MISSING_STATION_NOTIFIED, ""));
    }

    private static boolean hasMissingStationNotification(ItemStack task) {
        CustomData data = task.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag root = data.copyTag().getCompoundOrEmpty(ROOT);
        return !root.getStringOr(MISSING_STATION_NOTIFIED, "").isBlank();
    }

    private static void markMissingStationNotified(ItemStack task, String stationName) {
        CustomData.update(DataComponents.CUSTOM_DATA, task, tag -> {
            CompoundTag root = tag.getCompoundOrEmpty(ROOT);
            root.putString(MISSING_STATION_NOTIFIED, safeName(stationName));
            tag.put(ROOT, root);
        });
    }

    private static void clearMissingStationNotified(ItemStack task) {
        CustomData.update(DataComponents.CUSTOM_DATA, task, tag -> {
            CompoundTag root = tag.getCompoundOrEmpty(ROOT);
            root.remove(MISSING_STATION_NOTIFIED);
            tag.put(ROOT, root);
        });
    }

    private static String safeName(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    public static String sanitizeGroupName(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.length() > 48 ? trimmed.substring(0, 48) : trimmed;
    }

    private static Data data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Data.TYPE);
    }

    private record PendingNotification(String key, List<String> args) {
        Component component() {
            return Component.translatable(key, (Object[])args.toArray(String[]::new));
        }

        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("key", key);
            ListTag list = new ListTag();
            for (String arg : args) {
                CompoundTag entry = new CompoundTag();
                entry.putString("value", arg);
                list.add(entry);
            }
            tag.put("args", list);
            return tag;
        }

        static PendingNotification fromTag(CompoundTag tag) {
            ArrayList<String> args = new ArrayList<>();
            for (net.minecraft.nbt.Tag raw : tag.getListOrEmpty("args")) {
                raw.asCompound().ifPresent(entry -> args.add(entry.getStringOr("value", "")));
            }
            return new PendingNotification(tag.getStringOr("key", ""), args);
        }
    }

    public static final class Data extends SavedData {
        private static final Codec<Data> CODEC = CompoundTag.CODEC.xmap(Data::fromTag, Data::toTag);
        private static final SavedDataType<Data> TYPE = new SavedDataType<>(
            ModUtils.id("task_notifications"),
            Data::new,
            CODEC,
            DataFixTypes.SAVED_DATA_MAP_DATA
        );

        private final Map<UUID, List<PendingNotification>> pending = new HashMap<>();

        private static Data fromTag(CompoundTag tag) {
            Data data = new Data();
            for (net.minecraft.nbt.Tag raw : tag.getListOrEmpty("players")) {
                raw.asCompound().ifPresent(entry -> {
                    UUID owner = uuid(entry.getStringOr("uuid", ""));
                    if (owner == null) {
                        return;
                    }
                    ArrayList<PendingNotification> notifications = new ArrayList<>();
                    for (net.minecraft.nbt.Tag messageRaw : entry.getListOrEmpty("messages")) {
                        messageRaw.asCompound().ifPresent(message -> notifications.add(PendingNotification.fromTag(message)));
                    }
                    if (!notifications.isEmpty()) {
                        data.pending.put(owner, notifications);
                    }
                });
            }
            return data;
        }

        private CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            ListTag players = new ListTag();
            for (Map.Entry<UUID, List<PendingNotification>> entry : pending.entrySet()) {
                CompoundTag player = new CompoundTag();
                player.putString("uuid", entry.getKey().toString());
                ListTag messages = new ListTag();
                for (PendingNotification notification : entry.getValue()) {
                    messages.add(notification.toTag());
                }
                player.put("messages", messages);
                players.add(player);
            }
            tag.put("players", players);
            return tag;
        }
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
