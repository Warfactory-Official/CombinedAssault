package com.wf.combinedassault.custom.part.data;

public class LinkedHingeUnitData extends RotatableUnitData {

    private final String link;
    private final float openXRot;
    private final float openYRot;

    public LinkedHingeUnitData(LinkedHingeUnitPojo pojo) {
        super(pojo);
        if (pojo.link.isEmpty()) {
            throw new IllegalArgumentException(getId() + ": linked_hinge without link");
        }
        this.link = pojo.link;
        this.openXRot = pojo.openXRot;
        this.openYRot = pojo.openYRot;
    }

    public String link() {
        return link;
    }

    public float openXRot() {
        return openXRot;
    }

    public float openYRot() {
        return openYRot;
    }

}
