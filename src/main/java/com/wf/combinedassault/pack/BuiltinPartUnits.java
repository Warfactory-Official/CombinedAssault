package com.wf.combinedassault.pack;

import com.wf.combinedassault.pack.part.PartUnitType;
import com.wf.combinedassault.pack.part.PartUnitTypes;

public class BuiltinPartUnits {

    public static void registerTypes() {
        add(PartUnitTypes.GENERIC);
        add(PartUnitTypes.SWITCHABLE);
        add(PartUnitTypes.ROTATABLE);
        add(PartUnitTypes.DOOR);
        add(PartUnitTypes.LANDING_GEAR);
        add(PartUnitTypes.WEAPON_BAY);
        add(PartUnitTypes.WEAPON);
        add(PartUnitTypes.AUTO_WEAPON);
        add(PartUnitTypes.MISSILE_HOLDER);
        add(PartUnitTypes.LINKED_HINGE);
        add(PartUnitTypes.ROPE);
        add(PartUnitTypes.SUSPENSION);
        add(PartUnitTypes.TRACK);
    }

    public static void add(PartUnitType<?, ?> type) {
        PackTypes.PART_UNITS.register(type.id(), type);
    }

}
