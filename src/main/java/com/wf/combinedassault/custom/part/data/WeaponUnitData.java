package com.wf.combinedassault.custom.part.data;

import com.wf.combinedassault.vehicle.crew.WeaponLoading;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.hardware.HardwareKits;
import com.wf.combinedassault.vehicle.pojo.Bolt;
import com.wf.combinedassault.vehicle.pojo.Eject;
import com.wf.combinedassault.vehicle.pojo.WeaponInfo;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.*;

public class WeaponUnitData extends RotatableUnitData {

    private List<Bolt> bolts;
    /** Kit mount bolts; a kit absent here fires from {@link #bolts}. */
    private Map<ResourceLocation, List<Bolt>> kitBolts = Map.of();
    /** Kit mount case ports; a kit absent here ejects from {@link #ejects}. */
    private Map<ResourceLocation, List<Eject>> kitEjects = Map.of();
    /** Kit mount sight sockets; a kit absent here sights from {@link #opticalSightOffset}. */
    private Map<ResourceLocation, Vec3> kitSights = Map.of();
    /** {@code sight_lens} node, its own and per kit mount; null => none. */
    @Nullable
    private String lens;
    private Map<ResourceLocation, String> kitLenses = Map.of();
    @Nullable
    private HardwareKits.Hardpoint hardpoint;
    /** Hardpoint: per kit, this part with the kit's station; identical structure (weapons, mounts, frames). */
    private Map<ResourceLocation, WeaponUnitData> variants = Map.of();
    /** By bolt index; shorter than the bolts => the rest eject nothing. */
    private List<Eject> ejects = List.of();
    private int ammoCapacity;
    private FiringMode firingMode;
    private int coldLaunchTimeTick;
    private Vec3 coldLaunchVelocity;
    private boolean parentWeaponUnitAim;
    private Vec3 opticalSightOffset;
    private Vec3 operatorViewOffset;
    private boolean operatorOnWeaponUnit;
    private OpticalSightType opticalSightType;
    private boolean withStabilizer;
    private boolean withFocusLocker;
    private boolean withThermalImager;
    private boolean withNightVision;
    private boolean withIr;
    private WeaponLoading loading = WeaponLoading.SELF;
    private float zoomMin;
    private float zoomMax;
    private FireControlSensorType fireControlSensorType;
    private CrosshairStyle crosshairStyle;
    private Handling handling = Handling.REMOTE;
    @Nullable
    private OpticSpec optic;
    private List<WeaponInfo> weapons;
    @org.jetbrains.annotations.Nullable
    private com.wf.combinedassault.indirect.IndirectFireSpec indirectFire;
    @org.jetbrains.annotations.Nullable
    private com.wf.combinedassault.airdefense.AirDefenseSpec airDefense;
    private VehicleCubeGroup xTurnGroup;

    public WeaponUnitData(String id) {
        super(id);
    }

    public WeaponUnitData(WeaponUnitPojo pojo) {
        super(pojo);
        if (pojo.ownerViewOffset != null) {
            throw new IllegalArgumentException("weapon part " + pojo.id + ": owner_view_offset unused on a weapon, use operator_view_offset");
        }
        this.bolts = pojo.bolts;
        this.ejects = pojo.ejects;
        this.ammoCapacity = pojo.ammoCapacity;
        this.firingMode = pojo.firingMode;
        this.coldLaunchTimeTick = pojo.coldLaunchTimeTick;
        this.coldLaunchVelocity = pojo.coldLaunchVelocity;
        this.parentWeaponUnitAim = pojo.parentWeaponUnitAim;
        this.opticalSightOffset = pojo.opticalSightOffset;
        this.operatorViewOffset = pojo.operatorViewOffset;
        this.operatorOnWeaponUnit = pojo.operatorOnWeaponUnit;
        this.fireControlSensorType = pojo.fireControlSensorType;
        this.opticalSightType = pojo.opticalSightType;
        this.withStabilizer = pojo.withStabilizer;
        this.withFocusLocker = pojo.withFocusLocker;
        this.withThermalImager = pojo.withThermalImager;
        this.withNightVision = pojo.withNightVision;
        this.withIr = pojo.withIr;
        this.loading = pojo.loading;
        this.zoomMin = pojo.zoomMin;
        this.zoomMax = pojo.zoomMax;
        this.crosshairStyle = pojo.crosshairStyle;
        this.handling = pojo.handling;
        if (pojo.optic != null && pojo.optic.zoom().isEmpty()) {
            throw new IllegalArgumentException("weapon part " + pojo.id + ": optic.zoom empty");
        }
        this.optic = pojo.optic;
        this.weapons = pojo.weapons;
        this.hardpoint = pojo.hardpoint == null ? null : HardwareKits.Hardpoint.parse(pojo.hardpoint);
        this.indirectFire = pojo.indirectFire == null ? null
                : new com.wf.combinedassault.indirect.IndirectFireSpec(pojo.indirectFire.map,
                pojo.indirectFire.launchElevation);
        this.airDefense = pojo.airDefense == null ? null
                : com.wf.combinedassault.airdefense.AirDefenseSpec.of(pojo.airDefense);
    }

    /** glTF sockets for keys the json left out; null = keep. */
    public void applySockets(Vec3 opticalSight, Vec3 operatorView, List<Bolt> sockets) {
        if (opticalSight != null) {
            this.opticalSightOffset = opticalSight;
        }
        if (operatorView != null) {
            this.operatorViewOffset = operatorView;
        }
        if (sockets != null) {
            this.bolts = new ArrayList<>(sockets);
        }
    }

    public void applyEjects(List<Eject> ejects) {
        this.ejects = List.copyOf(ejects);
    }

    public List<Eject> getEjects() {
        return ejects;
    }

    public void applyKitEjects(Map<ResourceLocation, List<Eject>> byKit) {
        this.kitEjects = Map.copyOf(byKit);
    }

    public List<Eject> getEjects(ResourceLocation kit) {
        return kitEjects.getOrDefault(kit, ejects);
    }

    public void applyKitBolts(Map<ResourceLocation, List<Bolt>> byKit) {
        this.kitBolts = Map.copyOf(byKit);
    }

    public void applyKitSights(Map<ResourceLocation, Vec3> byKit) {
        this.kitSights = Map.copyOf(byKit);
    }

    @Nullable
    public Vec3 getKitSight(@Nullable ResourceLocation kit) {
        return kit == null ? null : kitSights.get(kit);
    }

    public void applyLenses(@Nullable String lens, Map<ResourceLocation, String> byKit) {
        if (lens != null) {
            this.lens = lens;
        }
        this.kitLenses = Map.copyOf(byKit);
    }

    @Nullable
    public String getLens(@Nullable ResourceLocation kit) {
        return kit == null ? lens : kitLenses.getOrDefault(kit, lens);
    }

    @Nullable
    public HardwareKits.Hardpoint getHardpoint() {
        return hardpoint;
    }

    public void applyVariants(Map<ResourceLocation, WeaponUnitData> variants) {
        this.variants = Map.copyOf(variants);
    }

    /** Hardpoint part with {@code kit} fitted; null kit => this. */
    public WeaponUnitData variant(@Nullable ResourceLocation kit) {
        return kit == null ? this : variants.get(kit);
    }

    /** Pitch frame, null if none (yaw frame fires). */
    @Nullable
    public VehicleCubeGroup getRawPitchGroup() {
        return xTurnGroup == structureGroup ? null : xTurnGroup;
    }

    public List<Bolt> getBolts() {
        return bolts;
    }

    @Nullable
    public List<Bolt> getBolts(@Nullable ResourceLocation kit) {
        return kit == null ? bolts : kitBolts.getOrDefault(kit, bolts);
    }

    public int getAmmoCapacity() {
        return ammoCapacity;
    }

    public FiringMode getFiringMode() {
        return firingMode;
    }

    public int getColdLaunchTimeTick() {
        return coldLaunchTimeTick;
    }

    public Vec3 getColdLaunchVelocity() {
        return coldLaunchVelocity;
    }

    public boolean isParentWeaponUnitAim() {
        return parentWeaponUnitAim;
    }

    public Vec3 getOpticalSightOffset() {
        return opticalSightOffset;
    }

    public Vec3 getOperatorViewOffset() {
        return operatorViewOffset;
    }

    public boolean isOperatorOnWeaponUnit() {
        return operatorOnWeaponUnit;
    }

    /** Null: direct fire only. */
    @org.jetbrains.annotations.Nullable
    public com.wf.combinedassault.indirect.IndirectFireSpec getIndirectFire() {
        return indirectFire;
    }

    @org.jetbrains.annotations.Nullable
    public com.wf.combinedassault.airdefense.AirDefenseSpec getAirDefense() {
        return airDefense;
    }

    public FireControlSensorType getFireControlSensorType() {
        return fireControlSensorType;
    }

    public OpticalSightType getOpticalSightType() {
        return opticalSightType;
    }

    public boolean withStabilizer() {
        return withStabilizer;
    }

    public boolean withFocusLocker() {
        return withFocusLocker;
    }

    public boolean withThermalImager() {
        return withThermalImager;
    }

    public boolean withNightVision() {
        return withNightVision;
    }

    public boolean withIr() {
        return withIr;
    }

    public WeaponLoading getLoading() {
        return loading;
    }

    public float getZoomMin() {
        return zoomMin;
    }

    public float getZoomMax() {
        return zoomMax;
    }

    public CrosshairStyle getCrosshairStyle() {
        return crosshairStyle;
    }

    public Handling getHandling() {
        return handling;
    }

    @Nullable
    public OpticSpec getOptic() {
        return optic;
    }

    public List<WeaponInfo> getWeapons() {
        return weapons;
    }

    public VehicleCubeGroup getRawXTurnGroup() {
        return xTurnGroup;
    }

    @Override
    public void initFrames(@org.jetbrains.annotations.Nullable VehicleCubeGroup part,
                           @org.jetbrains.annotations.Nullable VehicleCubeGroup pitch) {
        super.initFrames(part, pitch);
        this.xTurnGroup = pitch != null ? pitch : part;
        List<VehicleCubeOBB> yTurnUnitOBBs = part == null ? List.of() : part.cubeOBBs;
        this.partCubeOBBs = new ArrayList<>();
        if (pitch != null) {
            this.partCubeOBBs.addAll(pitch.cubeOBBs);
        }
        this.partCubeOBBs.addAll(yTurnUnitOBBs);
        // No bolts configured, yTurn-only structure => bolt at +Z face center of its OBB.
        if (pitch == null && (this.bolts == null || this.bolts.isEmpty()) && !yTurnUnitOBBs.isEmpty()) {
            this.bolts = new ArrayList<>();
            this.bolts.add(new Bolt(Vec3.ZERO, (float) (yTurnUnitOBBs.get(0).depth / 2), 0, 0));
        }
    }

    public enum FiringMode {
        @SerializedName("ripple")
        RIPPLE,
        @SerializedName("salvo")
        SALVO,
        @SerializedName("full_salvo")
        FULL_SALVO
    }

    public enum OpticalSightType {
        @SerializedName("none")
        NONE,
        @SerializedName("operator")
        OPERATOR,
        @SerializedName("optical_scope")
        OPTICAL_SCOPE,
        @SerializedName("crt")
        CRT
    }

    public enum FireControlSensorType {
        @SerializedName("none")
        NONE,
        @SerializedName("ir")
        IR,
        @SerializedName("rf")
        RF,
        @SerializedName("eo")
        EO,
        @SerializedName("loc")
        LOC,
        @SerializedName("ccip")
        CCIP
    }

    public enum CrosshairStyle {
        @SerializedName("none")
        NONE,
        @SerializedName("circle")
        CIRCLE,
        @SerializedName("square")
        SQUARE,
        @SerializedName("cross")
        CROSS,
        @SerializedName("big_cross")
        BIG_CROSS
    }

    /** {@code manual}: operator's hands on the gun => recoil moves the aim. {@code remote}: visual jolt only. */
    public enum Handling {
        @SerializedName("manual")
        MANUAL,
        @SerializedName("remote")
        REMOTE
    }

}
