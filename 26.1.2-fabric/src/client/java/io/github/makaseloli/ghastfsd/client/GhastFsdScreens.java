package io.github.makaseloli.ghastfsd.client;

import io.github.makaseloli.ghastfsd.network.GhastFsdPayloads;
import io.github.makaseloli.ghastfsd.route.RouteData;
import io.github.makaseloli.ghastfsd.route.RouteInstruction;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;

public final class GhastFsdScreens {
    private GhastFsdScreens() {
    }

    public static void openTaskEditor(InteractionHand hand, CompoundTag routeRoot, List<GhastFsdPayloads.StationOption> stations) {
        Minecraft.getInstance().setScreen(new TaskScreen(hand, routeRoot, stations));
    }

    public static void openStationEditor(BlockPos pos, String name, int dockingHeight, String stationDirection, String arrivalInstrument, int arrivalNote, String groupName) {
        Minecraft.getInstance().setScreen(new StationScreen(pos, name, dockingHeight, stationDirection, arrivalInstrument, arrivalNote, groupName));
    }

    static final class StationScreen extends Screen {
        private static final String[] INSTRUMENTS = {
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime", "xylophone",
            "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"
        };
        private static final String[] DIRECTIONS = { "north", "east", "south", "west" };

        private final BlockPos pos;
        private final String initialName;
        private final int initialDockingHeight;
        private final String initialStationDirection;
        private final String initialArrivalInstrument;
        private final int initialArrivalNote;
        private final String initialGroupName;
        private EditBox name;
        private EditBox dockingHeight;
        private EditBox arrivalNote;
        private EditBox groupName;
        private String stationDirection;
        private String arrivalInstrument;

        StationScreen(BlockPos pos, String initialName, int initialDockingHeight, String initialStationDirection, String initialArrivalInstrument, int initialArrivalNote, String initialGroupName) {
            super(Component.translatable("screen.ghastfsd.station"));
            this.pos = pos;
            this.initialName = initialName;
            this.initialDockingHeight = initialDockingHeight;
            this.initialStationDirection = normalizeDirection(initialStationDirection);
            this.initialArrivalInstrument = normalizeInstrument(initialArrivalInstrument);
            this.initialArrivalNote = initialArrivalNote;
            this.initialGroupName = initialGroupName;
            this.stationDirection = this.initialStationDirection;
            this.arrivalInstrument = this.initialArrivalInstrument;
        }

        @Override
        protected void init() {
            int center = width / 2;
            int top = height / 2 - 84;
            addLabel(center - 170, top, 92, "screen.ghastfsd.station_name");
            name = new EditBox(font, center - 72, top, 222, 20, Component.translatable("screen.ghastfsd.station_name"));
            name.setMaxLength(48);
            name.setHint(Component.literal("Central"));
            name.setValue(initialName);
            addRenderableWidget(name);

            addLabel(center - 170, top + 28, 92, "screen.ghastfsd.docking_height");
            dockingHeight = new EditBox(font, center - 72, top + 28, 64, 20, Component.translatable("screen.ghastfsd.docking_height"));
            dockingHeight.setMaxLength(2);
            dockingHeight.setHint(Component.literal("5"));
            dockingHeight.setValue(Integer.toString(initialDockingHeight));
            addRenderableWidget(dockingHeight);

            addLabel(center - 170, top + 56, 92, "screen.ghastfsd.station_direction");
            addRenderableWidget(Button.builder(directionLabel(), button -> {
                stationDirection = next(stationDirection, DIRECTIONS);
                button.setMessage(directionLabel());
            }).bounds(center - 72, top + 56, 222, 20).build());

            addLabel(center - 170, top + 84, 92, "screen.ghastfsd.arrival_instrument");
            addRenderableWidget(Button.builder(instrumentLabel(), button -> {
                arrivalInstrument = next(arrivalInstrument, INSTRUMENTS);
                button.setMessage(instrumentLabel());
            }).bounds(center - 72, top + 84, 222, 20).build());

            addLabel(center - 170, top + 112, 92, "screen.ghastfsd.arrival_note");
            arrivalNote = new EditBox(font, center - 72, top + 112, 64, 20, Component.translatable("screen.ghastfsd.arrival_note"));
            arrivalNote.setMaxLength(2);
            arrivalNote.setHint(Component.literal("12"));
            arrivalNote.setValue(Integer.toString(initialArrivalNote));
            addRenderableWidget(arrivalNote);

            addLabel(center - 170, top + 140, 92, "screen.ghastfsd.group_name");
            groupName = new EditBox(font, center - 72, top + 140, 222, 20, Component.translatable("screen.ghastfsd.group_name"));
            groupName.setMaxLength(48);
            groupName.setHint(Component.literal("FSD"));
            groupName.setValue(initialGroupName);
            addRenderableWidget(groupName);

            addRenderableWidget(Button.builder(Component.translatable("button.ghastfsd.save"), button -> save()).bounds(center - 72, top + 174, 106, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose()).bounds(center + 44, top + 174, 106, 20).build());
            setInitialFocus(name);
        }

        private void save() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getConnection() != null) {
                minecraft.getConnection().send(new ServerboundCustomPayloadPacket(new GhastFsdPayloads.SaveStationPayload(
                    pos,
                    name.getValue(),
                    parseInt(dockingHeight, initialDockingHeight),
                    stationDirection,
                    arrivalInstrument,
                    parseInt(arrivalNote, initialArrivalNote),
                    groupName.getValue()
                )));
            }
            onClose();
        }

        private void addLabel(int x, int y, int width, String key) {
            addRenderableWidget(new StringWidget(x, y, width, 20, Component.translatable(key), font));
        }

        private Component instrumentLabel() {
            return Component.translatable("instrument.ghastfsd." + arrivalInstrument);
        }

        private Component directionLabel() {
            return Component.translatable("direction.ghastfsd." + stationDirection);
        }

        private static String next(String current, String[] options) {
            for (int i = 0; i < options.length; i++) {
                if (options[i].equals(current)) {
                    return options[(i + 1) % options.length];
                }
            }
            return options[0];
        }

        private static String normalizeInstrument(String instrument) {
            for (String candidate : INSTRUMENTS) {
                if (candidate.equals(instrument)) {
                    return candidate;
                }
            }
            return INSTRUMENTS[0];
        }

        private static String normalizeDirection(String direction) {
            for (String candidate : DIRECTIONS) {
                if (candidate.equals(direction)) {
                    return candidate;
                }
            }
            return DIRECTIONS[0];
        }

        private static int parseInt(EditBox box, int fallback) {
            try {
                return Integer.parseInt(box.getValue());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    static final class TaskScreen extends Screen {
        private static final String[] CONDITIONS = {
            "wait_seconds",
            "wait_for_passengers",
            "wait_for_redstone_on",
            "wait_for_redstone_off"
        };

        private final InteractionHand hand;
        private final List<GhastFsdPayloads.StationOption> stations;
        private final List<RouteInstruction> route;
        private int selected;
        private int selectedStation;
        private boolean loop;
        private EditBox wait;
        private EditBox passengers;
        private String condition = "wait_seconds";
        private Checkbox loopBox;
        private boolean stationPickerOpen;
        private int stationPage;

        TaskScreen(InteractionHand hand, CompoundTag routeRoot, List<GhastFsdPayloads.StationOption> stations) {
            super(Component.translatable("screen.ghastfsd.task"));
            this.hand = hand;
            this.stations = List.copyOf(stations);
            this.route = new ArrayList<>(RouteData.readRouteRoot(routeRoot));
            this.loop = routeRoot.getBooleanOr("loop", true);
            this.selected = route.isEmpty() ? -1 : Math.min(route.size() - 1, Math.max(0, routeRoot.getIntOr("focus", 0)));
        }

        @Override
        protected void init() {
            rebuildEditor();
        }

        private void rebuildEditor() {
            clearWidgets();
            wait = null;
            passengers = null;
            int left = 12;
            int top = 24;
            int listWidth = Math.min(250, width / 2 - 18);
            addRenderableWidget(new StringWidget(left, 8, listWidth, 12, title, font));
            int rows = Math.min(10, Math.max(3, (height - 92) / 22));
            for (int i = 0; i < rows && i < route.size(); i++) {
                int index = i;
                addRenderableWidget(Button.builder(rowLabel(index, route.get(index)), button -> {
                    applyFields();
                    selected = index;
                    stationPickerOpen = false;
                    rebuildEditor();
                }).bounds(left, top + i * 22, listWidth, 20).build());
            }

            int actionY = height - 58;
            addRenderableWidget(Button.builder(Component.translatable("button.ghastfsd.add_station"), button -> { applyFields(); stationPickerOpen = false; addStation(); rebuildEditor(); }).bounds(left, actionY, 118, 20).build());
            loopBox = Checkbox.builder(Component.translatable("screen.ghastfsd.loop"), font).pos(left, actionY + 24).selected(loop).build();
            addRenderableWidget(loopBox);
            addRenderableWidget(Button.builder(Component.translatable("button.ghastfsd.clear"), button -> { route.clear(); selected = -1; stationPickerOpen = false; rebuildEditor(); }).bounds(left + 124, actionY + 24, 118, 20).build());

            int editorLeft = left + listWidth + 22;
            int editorTop = top;
            if (selected >= 0 && selected < route.size()) {
                RouteInstruction instruction = route.get(selected);
                prepareSelection(instruction);
                if (stationPickerOpen) {
                    addStationPicker(editorLeft, editorTop);
                } else {
                    addLabel(editorLeft, editorTop, 76, "screen.ghastfsd.station_select");
                    addRenderableWidget(Button.builder(stationButtonLabel(), button -> { applyFields(); stationPickerOpen = true; stationPage = Math.max(0, selectedStation / 3); rebuildEditor(); }).bounds(editorLeft + 82, editorTop, 180, 20).build());
                    addLabel(editorLeft, editorTop + 28, 76, "screen.ghastfsd.departure_condition");
                    addRenderableWidget(Button.builder(Component.translatable("condition.ghastfsd." + condition), button -> {
                        applyFields();
                        setSelectedCondition(nextCondition(condition));
                        rebuildEditor();
                    }).bounds(editorLeft + 82, editorTop + 28, 180, 20).build());
                    if ("wait_seconds".equals(condition)) {
                        addLabeledField(editorLeft, editorTop + 58, 84, "screen.ghastfsd.wait_seconds", "5", box -> wait = box);
                    } else if ("wait_for_passengers".equals(condition)) {
                        addLabeledField(editorLeft, editorTop + 58, 84, "screen.ghastfsd.passengers", "1", box -> passengers = box);
                    }
                    loadFields();
                }
                if (!stationPickerOpen) {
                    addRenderableWidget(Button.builder(Component.translatable("button.ghastfsd.move_up"), button -> { applyFields(); move(-1); rebuildEditor(); }).bounds(editorLeft, editorTop + 106, 126, 20).build());
                    addRenderableWidget(Button.builder(Component.translatable("button.ghastfsd.move_down"), button -> { applyFields(); move(1); rebuildEditor(); }).bounds(editorLeft + 136, editorTop + 106, 126, 20).build());
                    addRenderableWidget(Button.builder(Component.translatable("button.ghastfsd.delete"), button -> { deleteSelected(); rebuildEditor(); }).bounds(editorLeft, editorTop + 132, 262, 20).build());
                }
            } else {
                addRenderableWidget(new StringWidget(editorLeft, editorTop, 260, 20, Component.translatable("screen.ghastfsd.no_route"), font));
            }

            addRenderableWidget(Button.builder(Component.translatable("button.ghastfsd.save"), button -> save()).bounds(width - 184, height - 28, 84, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(width - 94, height - 28, 84, 20).build());
        }

        private void addLabeledField(int x, int y, int fieldWidth, String labelKey, String sample, java.util.function.Consumer<EditBox> consumer) {
            addLabel(x, y, 96, labelKey);
            EditBox box = new EditBox(font, x + 104, y, fieldWidth, 20, Component.translatable(labelKey));
            box.setHint(Component.literal(sample));
            addRenderableWidget(box);
            consumer.accept(box);
        }

        private void addLabel(int x, int y, int width, String key) {
            addRenderableWidget(new StringWidget(x, y, width, 20, Component.translatable(key), font));
        }

        private Component rowLabel(int index, RouteInstruction instruction) {
            String label = instruction.stationName();
            return Component.literal((selected == index ? "> " : "") + (index + 1) + ". " + label);
        }

        private void prepareSelection(RouteInstruction instruction) {
            condition = instruction.departureCondition().isBlank() ? "wait_seconds" : instruction.departureCondition();
            selectedStation = stationIndex(instruction.stationName());
        }

        private void loadFields() {
            RouteInstruction instruction = route.get(selected);
            if (wait != null) {
                wait.setValue(Integer.toString(instruction.waitSeconds()));
            }
            if (passengers != null) {
                passengers.setValue(Integer.toString(instruction.passengerCount()));
            }
        }

        private void applyFields() {
            loop = loopBox == null ? loop : loopBox.selected();
            if (selected < 0 || selected >= route.size()) {
                return;
            }
            RouteInstruction old = route.get(selected);
            GhastFsdPayloads.StationOption station = selectedStation >= 0 && selectedStation < stations.size() ? stations.get(selectedStation) : null;
            String stationName = station == null ? old.stationName() : station.name();
            ResourceKey<Level> dimension = station == null ? old.dimension() : ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(station.dimension()));
            route.set(selected, new RouteInstruction(
                "fly_to_station",
                dimension,
                BlockPos.ZERO,
                stationName,
                condition,
                Math.max(0, parseInt(wait, old.waitSeconds())),
                Math.max(0, parseInt(passengers, old.passengerCount())),
                0.0,
                stationName
            ));
        }

        private void addStation() {
            if (stations.isEmpty()) {
                return;
            }
            GhastFsdPayloads.StationOption station = stations.get(0);
            route.add(new RouteInstruction("fly_to_station", ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(station.dimension())), BlockPos.ZERO, station.name(), "wait_seconds", 5, 1, 0.0, station.name()));
            selected = route.size() - 1;
        }

        private Component stationButtonLabel() {
            if (stations.isEmpty()) {
                return Component.translatable("screen.ghastfsd.no_stations");
            }
            if (selectedStation < 0 || selectedStation >= stations.size()) {
                return Component.translatable("screen.ghastfsd.select_station");
            }
            return Component.literal(stations.get(selectedStation).name());
        }

        private void addStationPicker(int x, int y) {
            addRenderableWidget(new StringWidget(x, y, 262, 20, Component.translatable("screen.ghastfsd.station_select"), font));
            if (stations.isEmpty()) {
                addRenderableWidget(new StringWidget(x, y + 26, 262, 20, Component.translatable("screen.ghastfsd.no_stations"), font));
                addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> { stationPickerOpen = false; rebuildEditor(); }).bounds(x, y + 92, 262, 20).build());
                return;
            }
            int pageSize = 3;
            int pageCount = Math.max(1, (stations.size() + pageSize - 1) / pageSize);
            stationPage = Math.max(0, Math.min(stationPage, pageCount - 1));
            int start = stationPage * pageSize;
            int end = Math.min(stations.size(), start + pageSize);
            for (int i = start; i < end; i++) {
                int index = i;
                Component label = Component.literal((index == selectedStation ? "> " : "") + stations.get(index).name());
                addRenderableWidget(Button.builder(label, button -> {
                    applyFields();
                    setSelectedStation(index);
                    stationPickerOpen = false;
                    rebuildEditor();
                }).bounds(x, y + 24 + (i - start) * 22, 262, 20).build());
            }
            addRenderableWidget(Button.builder(Component.translatable("button.ghastfsd.prev"), button -> { stationPage = Math.max(0, stationPage - 1); rebuildEditor(); }).bounds(x, y + 92, 82, 20).build());
            addRenderableWidget(Button.builder(Component.literal((stationPage + 1) + "/" + pageCount), button -> { }).bounds(x + 90, y + 92, 82, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("button.ghastfsd.next"), button -> { stationPage = Math.min(pageCount - 1, stationPage + 1); rebuildEditor(); }).bounds(x + 180, y + 92, 82, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> { stationPickerOpen = false; rebuildEditor(); }).bounds(x, y + 132, 262, 20).build());
        }

        private void setSelectedCondition(String nextCondition) {
            if (selected < 0 || selected >= route.size()) {
                return;
            }
            RouteInstruction old = route.get(selected);
            route.set(selected, new RouteInstruction(old.type(), old.dimension(), old.pos(), old.stationName(), nextCondition, old.waitSeconds(), old.passengerCount(), old.value(), old.label()));
        }

        private void setSelectedStation(int stationIndex) {
            if (selected < 0 || selected >= route.size() || stationIndex < 0 || stationIndex >= stations.size()) {
                return;
            }
            RouteInstruction old = route.get(selected);
            GhastFsdPayloads.StationOption station = stations.get(stationIndex);
            ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(station.dimension()));
            route.set(selected, new RouteInstruction(old.type(), dimension, BlockPos.ZERO, station.name(), old.departureCondition(), old.waitSeconds(), old.passengerCount(), old.value(), station.name()));
        }

        private int stationIndex(String stationName) {
            for (int i = 0; i < stations.size(); i++) {
                if (stations.get(i).name().equals(stationName)) {
                    return i;
                }
            }
            return stations.isEmpty() ? -1 : 0;
        }

        private void move(int direction) {
            int target = selected + direction;
            if (target >= 0 && target < route.size()) {
                RouteInstruction instruction = route.remove(selected);
                route.add(target, instruction);
                selected = target;
            }
        }

        private void deleteSelected() {
            if (selected >= 0 && selected < route.size()) {
                route.remove(selected);
                selected = route.isEmpty() ? -1 : Math.min(selected, route.size() - 1);
            }
        }

        private void save() {
            applyFields();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getConnection() != null) {
                minecraft.getConnection().send(new ServerboundCustomPayloadPacket(new GhastFsdPayloads.SaveTaskRoutePayload(hand, RouteData.writeRoute(route, loop, selected))));
            }
            onClose();
        }

        private static int parseInt(EditBox box, int fallback) {
            if (box == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(box.getValue());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static String nextCondition(String current) {
            for (int i = 0; i < CONDITIONS.length; i++) {
                if (CONDITIONS[i].equals(current)) {
                    return CONDITIONS[(i + 1) % CONDITIONS.length];
                }
            }
            return CONDITIONS[0];
        }
    }
}
