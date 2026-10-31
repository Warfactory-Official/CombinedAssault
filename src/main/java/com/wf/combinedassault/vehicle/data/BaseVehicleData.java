package com.wf.combinedassault.vehicle.data;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.StringUtils;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.part.PartUnitEntry;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.entity.NoneVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.combinedassault.ballistics.addon.AddonMount;
import com.wf.combinedassault.ballistics.addon.AddonMountInfo;
import com.wf.combinedassault.vehicle.pojo.AutonomyInfo;
import com.wf.combinedassault.weapon.pojo.LoadoutInfo;
import com.wf.combinedassault.ballistics.pojo.DefenseStats;
import com.wf.combinedassault.energy.pojo.EnergyInfo;
import com.wf.combinedassault.dynamics.pojo.PhysicsInfo;
import com.wf.combinedassault.vehicle.screen.ScreenInfo;
import com.wf.combinedassault.tow.TowInfo;
import com.wf.combinedassault.deck.pojo.MountPointInfo;
import com.wf.combinedassault.cargo.pojo.CargoInfo;
import com.wf.combinedassault.vehicle.pojo.MountableInfo;
import com.wf.combinedassault.cargo.pojo.StorageInfo;
import com.wf.combinedassault.vehicle.pojo.ViewInfo;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.wflib.physics.api.ConvexHull;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.combinedassault.dynamics.structure.VehicleStructOBBs;

import java.util.*;
import com.wf.combinedassault.ballistics.hit.ClipRig;
import com.wf.combinedassault.ballistics.hit.HitMesh;
import com.wf.combinedassault.ballistics.hit.ModelData;
import com.wf.combinedassault.ballistics.hit.ModelRig;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.wflib.physics.api.HullTag;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.VehicleContent;

public class BaseVehicleData<T extends AbstractVehicle> {

    protected ResourceLocation vehicleId;
    protected Component name;
    protected float maxHealth;
    protected ViewInfo viewInfo;
    protected EnergyInfo energyInfo;
    protected PhysicsInfo physicsInfo;
    /** physics_info.center in the physics body's frame. Hull model: pack value is vehicle-local. */
    protected Vec3 physicsCenter;
    protected DefenseStats defenseStats;
    protected StorageInfo storage = new StorageInfo();
    protected List<MountPointInfo> mountPoints = List.of();
    protected MountableInfo mountable = new MountableInfo();
    protected TowInfo towing = TowInfo.NONE;
    protected Map<String, ScreenInfo> screens = Map.of();
    protected CargoInfo cargo = CargoInfo.NONE;
    protected HullProfile armor;
    protected List<AddonMount> addonMounts = List.of();
    protected boolean uav;
    protected boolean pickup;
    @Nullable
    protected AutonomyInfo autonomy;
    protected List<LoadoutInfo> loadouts = List.of();
    protected boolean canWade;
    protected boolean withWarningReceiver;
    protected boolean protectPassenger;
    protected boolean experimental;
    protected Vec3 centerOffset;
    /** Set by the data manager around {@link VehicleDataType#parse}: data types build inside Gson parsing. */
    public static final ThreadLocal<ModelData> MODEL = new ThreadLocal<>();
    protected ModelData model;
    @org.jetbrains.annotations.Nullable
    protected HitModel hitModel;
    protected ClipRig clipRig;
    private final Map<String, VehicleCubeGroup> groupsByName = new HashMap<>();
    protected double structureLength;
    /** Local XZ extents of body + part cubes: minX, maxX, minZ, maxZ. */
    protected final double[] extentsXZ = new double[4];
    /** Z extent of the solid body colliders; turn geometry. Main cube = one convex piece, not the hull. */
    protected float bodyDepth;
    protected List<PartUnitEntry<?, ?>> parts;
    protected VehicleCubeOBB mainCubeOBB;
    protected final List<VehicleCubeOBB> vehicleBodyOBBs = new ArrayList<>();
    /** Per rig frame: type-level group; [0] = body. Part groups are copied per vehicle ({@link #vehicleCubeGroupsCopy}). */
    protected final List<VehicleCubeGroup> frameGroups = new ArrayList<>();
    private int[] frameParent = new int[0];
    private boolean hasDeckGeometry;

    public BaseVehicleData() {}

    public AbstractVehicle construct(Level level, Vec3 position, float xRot, float yRot) {
        AbstractVehicle vehicle = fromRegistries(level, position, xRot, yRot);
        if (vehicle != null) {
            return vehicle;
        }
        vehicle = fromCustom(level);
        vehicle.setVehicleId(vehicleId);
        vehicle.setPos(position);
        vehicle.setXRot(xRot);
        vehicle.setYRot(yRot);
        return vehicle;
    }

    protected AbstractVehicle fromRegistries(Level level, Vec3 position, float xRot, float yRot) {
        if (BuiltInRegistries.ENTITY_TYPE.containsKey(vehicleId)) {
            EntityType<?> vehicleType = BuiltInRegistries.ENTITY_TYPE.get(vehicleId);
            Entity entity = vehicleType.create(level);
            if (entity instanceof AbstractVehicle vehicle) {
                vehicle.setPos(position);
                vehicle.setXRot(xRot);
                vehicle.setYRot(yRot);
                return vehicle;
            }
        }
        return null;
    }

    protected AbstractVehicle fromCustom(Level level) {
        return new NoneVehicle(VehicleContent.NONE_VEHICLE.get(), level);
    }

    protected static String check(BaseVehicleDataPojo pojo) {
        return "";
    }

    public void build(BaseVehicleDataPojo pojo) {
        String checkResult = check(pojo);
        if (!StringUtils.isBlank(checkResult)) {
            CombinedAssault.LOGGER.warn(checkResult);
            return;
        }

        this.maxHealth = pojo.maxHealth;
        this.viewInfo = pojo.viewInfo;
        this.energyInfo = pojo.energyInfo;
        this.physicsInfo = pojo.physicsInfo;
        this.defenseStats = pojo.defenseStats;
        this.storage = pojo.storage == null ? new StorageInfo() : pojo.storage;
        this.mountPoints = pojo.mountPoints == null ? List.of() : List.copyOf(pojo.mountPoints);
        this.mountable = pojo.mountable == null ? new MountableInfo() : pojo.mountable;
        this.towing = pojo.towing == null ? TowInfo.NONE : pojo.towing;
        pojo.screens.forEach((id, screen) -> screen.validate(id));
        this.screens = pojo.screens;
        this.cargo = pojo.cargo == null ? CargoInfo.NONE : pojo.cargo;
        this.armor = pojo.armor;
        this.uav = pojo.uav;
        this.pickup = pojo.pickup;
        this.autonomy = pojo.uav ? pojo.autonomy : null;
        this.loadouts = pojo.loadouts == null ? List.of() : List.copyOf(pojo.loadouts);
        this.canWade = pojo.canWade;
        this.withWarningReceiver = pojo.withWarningReceiver;
        this.protectPassenger = pojo.protectPassenger;
        this.experimental = pojo.experimental;

        this.centerOffset = pojo.centerOffset;
        this.model = Objects.requireNonNull(MODEL.get(), "vehicle built outside the data manager");
        buildFrames(model.rig());
        this.parts = pojo.parts;
        for (var entry : this.parts) {
            var partData = entry.data();
            int part = model.rig().part(partData.getId());
            int pitch = model.rig().pitch(partData.getId());
            partData.initFrames(part < 0 ? null : frameGroups.get(part), pitch < 0 ? null : frameGroups.get(pitch));
        }
        if (cargo != CargoInfo.NONE) {
            if (!cargo.enabled()) {
                throw new IllegalArgumentException("cargo needs door and a cargo_bay socket (or bay_center + bay_size)");
            }
            if (parts.stream().noneMatch(entry -> entry.data().getId().equals(cargo.door))) {
                throw new IllegalArgumentException("cargo.door names no part: " + cargo.door);
            }
        }
        physicsCenter = physicsInfo.center.subtract(mainCubeOBB.offset());

        this.addonMounts = resolveAddonMounts(pojo.addonArmor == null ? List.of() : pojo.addonArmor);
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        List<VehicleCubeOBB> vehicleOBBs = new ArrayList<>(vehicleBodyOBBs);
        for (PartUnitEntry<?, ?> partUnitEntry : parts) {
            vehicleOBBs.addAll(partUnitEntry.data().getRawPartCubeOBBs());
        }
        for (VehicleCubeOBB vehicleOBB : vehicleOBBs) {
            double x1 = vehicleOBB.offset().x + vehicleOBB.getWidth() / 2;
            double x2 = vehicleOBB.offset().x - vehicleOBB.getWidth() / 2;
            if (maxX < x1) {
                maxX = x1;
            }
            if (minX > x2) {
                minX = x2;
            }
            double z1 = vehicleOBB.offset().z + vehicleOBB.getDepth() / 2;
            double z2 = vehicleOBB.offset().z - vehicleOBB.getDepth() / 2;
            if (maxZ < z1) {
                maxZ = z1;
            }
            if (minZ > z2) {
                minZ = z2;
            }
        }
        this.structureLength = Math.max(maxX - minX, maxZ - minZ);
        extentsXZ[0] = minX;
        extentsXZ[1] = maxX;
        extentsXZ[2] = minZ;
        extentsXZ[3] = maxZ;
    }

    public void inject(T vehicle) {}

    /** @param groups type-level group -> this vehicle's copy (part groups only; body groups are shared) */
    public record PartUnitsAndSeats(
            Map<String, PartUnit<?>> partUnitMap,
            List<AbstractVehicle.Seat> seats,
            Map<VehicleCubeGroup, VehicleCubeGroup> groups
    ) {}

    public PartUnitsAndSeats createPartUnits(AbstractVehicle vehicle) {
        return createBasePartUnits(vehicle);
    }

    public Optional<PartUnit<?>> copyPartUnit(AbstractVehicle owner, String partUnitId, CompoundTag state) {
        PartUnit<?> partUnit = createBasePartUnits(owner).partUnitMap().get(partUnitId);
        if (partUnit == null) {
            return Optional.empty();
        }
        partUnit.deserializeNBT(owner.registryAccess(), state);
        return Optional.of(partUnit);
    }

    private PartUnitsAndSeats createBasePartUnits(AbstractVehicle vehicle) {
        Map<String, PartUnit<?>> partUnitMap = new LinkedHashMap<>();
        List<AbstractVehicle.Seat> seats = new ArrayList<>();
        HashMap<VehicleCubeGroup, VehicleCubeGroup> vehicleCubeGroupCopy = vehicleCubeGroupsCopy();
        int index = 0;
        for (var partData : parts) {
            var partUnit = partData.create(index, vehicle);
            partUnit.buildStructure(vehicleCubeGroupCopy);
            if (partData.data().isSeat()) {
                int seatIndex = seats.size();
                seats.add(new AbstractVehicle.Seat(seatIndex, partUnit));
            }
            partUnitMap.put(partData.data().getId(), partUnit);
            index++;
        }
        var view = Collections.unmodifiableMap(partUnitMap);
        for (var partUnit : partUnitMap.values()) {
            partUnit.combineAndInit(view, vehicle);
        }
        return new PartUnitsAndSeats(partUnitMap, seats, vehicleCubeGroupCopy);
    }

    /** Frames -> groups (pre-order: parent first); colliders -> pieces; main = flagged, else largest solid body piece. */
    private void buildFrames(ModelRig rig) {
        frameParent = new int[rig.frames().size()];
        for (int f = 0; f < rig.frames().size(); f++) {
            ModelRig.Frame frame = rig.frames().get(f);
            frameParent[f] = frame.parent();
            Vector3f origin = frame.world().getTranslation(new Vector3f());
            Quaternionf rotation = frame.world().getNormalizedRotation(new Quaternionf());
            VehicleCubeGroup group;
            if (frame.parent() <= ModelRig.BODY) {
                group = new VehicleCubeGroup(null, rotation, new Vec3(origin), frame.name());
            } else {
                ModelRig.Frame up = rig.frames().get(frame.parent());
                Quaternionf inverse = up.world().getNormalizedRotation(new Quaternionf()).conjugate();
                Vector3f pivot = inverse.transform(origin.sub(up.world().getTranslation(new Vector3f()), new Vector3f()));
                group = new VehicleCubeGroup(frameGroups.get(frame.parent()), inverse.mul(rotation, new Quaternionf()),
                        new Vec3(pivot), frame.name());
            }
            frameGroups.add(group);
            groupsByName.putIfAbsent(frame.name(), group);
        }
        List<ConvexHull> bodyHulls = new ArrayList<>();
        for (ModelRig.Collider collider : rig.colliders()) {
            VehicleCubeOBB built = VehicleCubeOBB.init(frameGroups.get(collider.frame()), collider.hull(), collider.deck(),
                    new HullTag(collider.name(), collider.solid(), collider.world()));
            hasDeckGeometry |= collider.deck();
            if (collider.frame() != ModelRig.BODY) {
                continue;
            }
            if (built.isSolid()) {
                bodyHulls.add(collider.hull());
            }
            if (collider.main()) {
                mainCubeOBB = built;
            } else {
                vehicleBodyOBBs.add(built);
            }
        }
        if (mainCubeOBB == null) {
            mainCubeOBB = vehicleBodyOBBs.stream().filter(VehicleCubeOBB::isSolid)
                    .max(Comparator.comparingDouble(VehicleCubeOBB::volume)).orElseThrow(() ->
                            new IllegalArgumentException(model.source() + ": no solid body collider"));
        }
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (ConvexHull hull : bodyHulls) {
            minZ = Math.min(minZ, hull.minZ());
            maxZ = Math.max(maxZ, hull.maxZ());
        }
        bodyDepth = maxZ - minZ;
    }

    /** After {@link #setVehicleId}. */
    public void bindHitModel() {
        this.clipRig = model.clips();
        HitMesh hitMesh = model.hits();
        if (hitMesh == null) {
            return;
        }
        Set<String> present = new HashSet<>();
        for (var entry : this.parts) {
            present.add(entry.data().getId());
        }
        List<String> frameParts = new ArrayList<>();
        for (ModelRig.Frame frame : model.rig().frames()) {
            frameParts.add(present.contains(frame.part()) ? frame.part() : null);
        }
        this.hitModel = HitModel.bind(String.valueOf(vehicleId), hitMesh, frameGroups, frameParts, frameParent,
                this instanceof TrackedVehicleData);
        if ((armor == null || !armor.isDefined()) && hitMesh.nodes().stream()
                .anyMatch(n -> n.mesh() != null && n.mesh().hasArmor())) {
            CombinedAssault.LOGGER.warn("{}: {} has armor_<mm> materials but the vehicle has no armor block, so nothing "
                    + "reads them", vehicleId, hitMesh.source());
        }
        if (armor != null) {
            for (String part : armor.parts.keySet()) {
                if (!present.contains(part)) {
                    throw new IllegalArgumentException(vehicleId + ": armor.parts names no part '" + part + "'");
                }
            }
            for (HullProfile.Zone zone : armor.zones) {
                if (zone.name() == null || zone.min() == null || zone.max() == null || zone.min().length != 3
                        || zone.max().length != 3 || zone.ke() <= 0
                        || zone.part() != null && !armor.parts.containsKey(zone.part())) {
                    throw new IllegalArgumentException(vehicleId + ": armor zone " + zone.name()
                            + " needs name, min[3], max[3], ke > 0, part listed in armor.parts");
                }
            }
        }
    }

    /** Type-level group per model frame. */
    public List<VehicleCubeGroup> getFrameGroups() {
        return frameGroups;
    }

    /** Null: no clips. */
    @org.jetbrains.annotations.Nullable
    public ClipRig getClipRig() {
        return clipRig;
    }

    /** Null: {@code "hitbox": false}, colliders are the hit surface. */
    @org.jetbrains.annotations.Nullable
    public HitModel getHitModel() {
        return hitModel;
    }

    public ModelData getModel() {
        return model;
    }

    /** Each vehicle owns copies of the part groups; the body group is shared (never moves). */
    public HashMap<VehicleCubeGroup, VehicleCubeGroup> vehicleCubeGroupsCopy() {
        HashMap<VehicleCubeGroup, VehicleCubeGroup> clone = new HashMap<>();
        for (VehicleCubeGroup group : frameGroups.subList(1, frameGroups.size())) {
            VehicleCubeGroup copy = new VehicleCubeGroup(group.parent == null ? null : clone.get(group.parent),
                    group.rotation, group.pivot, group.name);
            copy.baseRotation = group.baseRotation;
            clone.put(group, copy);
        }
        return clone;
    }

    public VehicleStructOBBs getVehicleStructObbs() {
        var obbs = vehicleBodyOBBs.stream().map(VehicleCubeOBB::new).toList();
        return new VehicleStructOBBs(obbs, new VehicleCubeOBB(mainCubeOBB));
    }

    /** Vehicles of this type can be landed on. */
    public boolean hasDeckGeometry() {
        return hasDeckGeometry;
    }

    public ResourceLocation getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(ResourceLocation vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Component getName() {
        return name;
    }

    public void setName(Component name) {
        this.name = name;
    }

    public double getStructureLength() {
        return structureLength;
    }

    /** World box of body + part cubes spawned at {@code pos} facing {@code yRot}; y = [pos.y, pos.y + height]. */
    public AABB footprint(Vec3 pos, float yRot, double height) {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        float rad = -yRot * Mth.DEG_TO_RAD;
        for (int i = 0; i < 4; i++) {
            Vec3 corner = new Vec3(extentsXZ[i & 1], 0, extentsXZ[2 + (i >> 1)]).yRot(rad);
            minX = Math.min(minX, corner.x);
            maxX = Math.max(maxX, corner.x);
            minZ = Math.min(minZ, corner.z);
            maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(pos.x + minX, pos.y, pos.z + minZ, pos.x + maxX, pos.y + height, pos.z + maxZ);
    }

    public float getBodyDepth() {
        return bodyDepth;
    }

    public float getMaxHealth() {
        return maxHealth;
    }

    public ViewInfo getViewInfo() {
        return viewInfo;
    }

    public EnergyInfo getEnergyInfo() {
        return energyInfo;
    }

    public PhysicsInfo getPhysicsInfo() {
        return physicsInfo;
    }


    public Vec3 getPhysicsCenter() {
        return physicsCenter;
    }

    public HullProfile getArmor() {
        return armor;
    }

    public List<PartUnitEntry<?, ?>> getParts() {
        return parts;
    }

    public List<AddonMount> getAddonMounts() {
        return addonMounts;
    }

    /** A bad entry is dropped with a warning, never moved into another frame. */
    private List<AddonMount> resolveAddonMounts(List<AddonMountInfo> infos) {
        if (infos.isEmpty()) {
            return List.of();
        }
        Map<String, VehicleCubeGroup> groups = new HashMap<>();
        List<VehicleCubeOBB> cubes = new ArrayList<>(vehicleBodyOBBs);
        for (PartUnitEntry<?, ?> entry : parts) {
            cubes.addAll(entry.data().getRawPartCubeOBBs());
        }
        for (VehicleCubeOBB cube : cubes) {
            for (VehicleCubeGroup group = cube.group; group != null; group = group.parent) {
                if (!group.name.isEmpty()) {
                    groups.putIfAbsent(group.name, group);
                }
            }
        }
        List<AddonMount> mounts = new ArrayList<>(infos.size());
        Set<String> ids = new HashSet<>();
        for (AddonMountInfo info : infos) {
            if (info.id.isEmpty() || !ids.add(info.id)) {
                CombinedAssault.LOGGER.warn("{}: addon_armor id '{}' is empty or repeated; dropped", getName(), info.id);
                continue;
            }
            VehicleCubeGroup group = null;
            if (!info.bone.isEmpty()) {
                group = groups.get(info.bone);
                if (group == null) {
                    CombinedAssault.LOGGER.warn("{}: addon_armor '{}' names bone '{}', which carries no collision geometry; dropped",
                            getName(), info.id, info.bone);
                    continue;
                }
            }
            try {
                mounts.add(AddonMount.resolve(mounts.size(), info, group));
            } catch (IllegalArgumentException invalid) {
                CombinedAssault.LOGGER.warn("{}: {}; dropped", getName(), invalid.getMessage());
            }
        }
        return List.copyOf(mounts);
    }

    public DefenseStats getDefenseStats() {
        return defenseStats;
    }

    public StorageInfo getStorage() {
        return storage;
    }

    public List<MountPointInfo> getMountPoints() {
        return mountPoints;
    }

    public MountableInfo getMountable() {
        return mountable;
    }

    public Map<String, ScreenInfo> getScreens() {
        return screens;
    }

    public TowInfo getTowing() {
        return towing;
    }

    public CargoInfo getCargo() {
        return cargo;
    }

    public boolean isUav() {
        return uav;
    }

    public boolean canPickUp() {
        return pickup;
    }

    @Nullable
    public AutonomyInfo getAutonomy() {
        return autonomy;
    }

    public List<LoadoutInfo> getLoadouts() {
        return loadouts;
    }

    public boolean canWade() {
        return canWade;
    }

    public boolean withWarningReceiver() {
        return withWarningReceiver;
    }

    public boolean isProtectPassenger() {
        return protectPassenger;
    }

    public boolean isExperimental() {
        return experimental;
    }

    public Vec3 getCenterOffset() {
        return centerOffset;
    }

}
