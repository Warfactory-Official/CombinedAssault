package com.wf.combinedassault.vehicle.part;

import org.jetbrains.annotations.UnmodifiableView;
import com.wf.combinedassault.custom.part.data.LinkedHingeUnitData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.Map;

/** Hinge that swings its structure bone (hull pieces, deck, hits) open while a switchable part is on. */
public class LinkedHingeUnit extends RotatableUnit<LinkedHingeUnitData> {

    private SwitchableUnit<?> link;

    public LinkedHingeUnit(int index, AbstractVehicle vehicle, LinkedHingeUnitData data) {
        super(index, vehicle, data);
    }

    @Override
    public void combineAndInit(@UnmodifiableView Map<String, PartUnit<?>> partUnitsView, AbstractVehicle vehicle) {
        super.combineAndInit(partUnitsView, vehicle);
        if (!(partUnitsView.get(data.link()) instanceof SwitchableUnit<?> switchable)) {
            throw new IllegalArgumentException(getId() + ": link " + data.link() + " is not a switchable part");
        }
        link = switchable;
    }

    /** Open share, as drawn. */
    public float openness(float partialTick) {
        float x = data.openXRot() == 0 ? 0 : getViewXRot(partialTick) / data.openXRot();
        float y = data.openYRot() == 0 ? 0 : getViewYRot(partialTick) / data.openYRot();
        return Math.max(x, y);
    }

    @Override
    public void tick() {
        boolean open = link.isOn();
        setXAimRot(open ? data.openXRot() : 0);
        setYAimRot(open ? data.openYRot() : 0);
        super.tick();
    }

}
