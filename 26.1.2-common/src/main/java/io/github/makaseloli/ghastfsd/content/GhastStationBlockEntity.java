package io.github.makaseloli.ghastfsd.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class GhastStationBlockEntity extends BlockEntity {
    public static final int MIN_DOCKING_HEIGHT = 5;
    public static final int MAX_DOCKING_HEIGHT = 10;
    public static final int DEFAULT_DOCKING_HEIGHT = 5;
    public static final int MIN_NOTE = 0;
    public static final int MAX_NOTE = 24;
    public static final int DEFAULT_NOTE = 12;

    private String stationName = "";
    private int dockingHeight = DEFAULT_DOCKING_HEIGHT;
    private NoteBlockInstrument arrivalInstrument = NoteBlockInstrument.HARP;
    private int arrivalNote = DEFAULT_NOTE;
    private boolean comparatorOccupied;

    public GhastStationBlockEntity(BlockPos pos, BlockState state) {
        super(GhastFsdContent.GHAST_STATION_BLOCK_ENTITY, pos, state);
    }

    public String stationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = GhastStationData.sanitizeName(stationName);
        setChanged();
    }

    public int dockingHeight() {
        return dockingHeight;
    }

    public void setDockingHeight(int dockingHeight) {
        this.dockingHeight = clamp(dockingHeight, MIN_DOCKING_HEIGHT, MAX_DOCKING_HEIGHT);
        setChanged();
    }

    public NoteBlockInstrument arrivalInstrument() {
        return arrivalInstrument;
    }

    public void setArrivalInstrument(String instrumentName) {
        arrivalInstrument = parseInstrument(instrumentName);
        setChanged();
    }

    public int arrivalNote() {
        return arrivalNote;
    }

    public void setArrivalNote(int arrivalNote) {
        this.arrivalNote = clamp(arrivalNote, MIN_NOTE, MAX_NOTE);
        setChanged();
    }

    boolean updateComparatorOccupied(boolean occupied) {
        if (comparatorOccupied == occupied) {
            return false;
        }
        comparatorOccupied = occupied;
        setChanged();
        return true;
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        stationName = GhastStationData.sanitizeName(input.getStringOr("station_name", ""));
        dockingHeight = clamp(input.getIntOr("docking_height", DEFAULT_DOCKING_HEIGHT), MIN_DOCKING_HEIGHT, MAX_DOCKING_HEIGHT);
        arrivalInstrument = parseInstrument(input.getStringOr("arrival_instrument", NoteBlockInstrument.HARP.getSerializedName()));
        arrivalNote = clamp(input.getIntOr("arrival_note", DEFAULT_NOTE), MIN_NOTE, MAX_NOTE);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!stationName.isBlank()) {
            output.putString("station_name", stationName);
        }
        output.putInt("docking_height", dockingHeight);
        output.putString("arrival_instrument", arrivalInstrument.getSerializedName());
        output.putInt("arrival_note", arrivalNote);
    }

    public static NoteBlockInstrument parseInstrument(String instrumentName) {
        String sanitized = instrumentName == null ? "" : instrumentName.trim();
        for (NoteBlockInstrument instrument : NoteBlockInstrument.values()) {
            if (instrument.isTunable() && instrument.getSerializedName().equals(sanitized)) {
                return instrument;
            }
        }
        return NoteBlockInstrument.HARP;
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
