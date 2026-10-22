package com.wf.combinedassault.entity.vehicle;

import com.wf.wflib.sim.SimTargets;
import com.wf.wflib.audio.EngineSound;
import com.wf.combinedassault.audio.VehicleAudio;
import java.util.List;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import com.wf.combinedassault.vehicle.light.VehicleLight;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.CollisionContext;
import com.wf.combinedassault.vehicle.collision.SectionScan;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import com.wf.combinedassault.vehicle.crew.CrewRole;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.ballistics.ArmorProfile;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Math;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.all.AllDamageTypes;
import com.wf.combinedassault.autonomy.VehicleDrone;
import com.wf.combinedassault.vehicle.loadout.Loadouts;
import com.wf.combinedassault.vehicle.pojo.LoadoutInfo;
import com.wf.wflib.drone.DroneTracker;
import com.wf.wflib.drone.cam.Datalink;
import com.wf.wflib.laser.LaserTarget;
import com.wf.wflib.block.DroneNetBlock;
import com.wf.combinedassault.ballistics.ArmorFace;
import com.wf.combinedassault.uav.UavCamera;
import com.wf.combinedassault.all.AllSounds;
import com.wf.wflib.api.DetachedAnchors;
import com.wf.wflib.api.DetachedBodyHost;
import com.wf.combinedassault.api.entity.ICustomVehicle;
import com.wf.wflib.api.BeamSource;
import com.wf.wflib.api.TargetIlluminator;
import com.wf.wflib.entity.OBBEntity;
import com.wf.wflib.radio.RadioCarrier;
import com.wf.wflib.radio.RadioVoice;
import com.wf.combinedassault.compat.wflib.GuidedRounds;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.api.entity.RemoteTickEntity;
import com.wf.combinedassault.api.collision.CollisionProvider;
import com.wf.combinedassault.api.collision.CollisionProviders;
import com.wf.combinedassault.api.collision.OrientedBoxes;
import com.wf.combinedassault.api.event.VehicleAttackEvent;
import com.wf.combinedassault.api.event.VehicleMoveEvent;
import com.wf.combinedassault.client.particle.BulletHoleParticle;
import com.wf.combinedassault.client.resource.ClientAssetsManager;
import com.wf.combinedassault.client.model.VehicleModel;
import com.wf.combinedassault.client.model.VehicleModelInstance;
import com.wf.combinedassault.client.resource.vehicle.VehicleDisplay;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.part.data.PartUnitData;
import com.wf.combinedassault.custom.part.data.PartUnitPojo;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.ContainerCraft;
import com.wf.combinedassault.storage.CargoMass;
import com.wf.combinedassault.item.VehicleSpawnItem;
import com.wf.combinedassault.tow.TowInfo;
import com.wf.combinedassault.tow.TowLink;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.tow.TowMode;
import com.wf.combinedassault.item.CargoParachuteItem;
import com.wf.combinedassault.vehicle.cargo.CargoHold;
import com.wf.combinedassault.vehicle.cargo.VehicleParachute;
import com.wf.combinedassault.vehicle.parenting.VehicleMounts;
import com.wf.combinedassault.naval.trial.TrialProfile;
import com.wf.combinedassault.vehicle.pojo.MountPointInfo;
import com.wf.combinedassault.vehicle.pojo.MountableInfo;
import com.wf.combinedassault.item.VehicleItem;
import com.wf.combinedassault.network.message.*;
import com.wf.combinedassault.util.*;
import com.wf.combinedassault.vehicle.DamageSystem;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.PhysicsEngine;
import com.wf.combinedassault.vehicle.PhysicsRig;
import com.wf.combinedassault.vehicle.PhysicsTrace;
import com.wf.combinedassault.vehicle.VehicleRighting;
import com.wf.combinedassault.vehicle.collision.BoxBuffer;
import com.wf.combinedassault.vehicle.collision.ChunkCollisionCache;
import com.wf.combinedassault.vehicle.collision.CompoundBody;
import org.joml.Vector3d;
import com.wf.combinedassault.vehicle.parenting.DeckFrame;
import com.wf.combinedassault.vehicle.collision.SweptHull;
import com.wf.combinedassault.vehicle.control.ControlUnit;
import com.wf.combinedassault.vehicle.camo.CamoLayer;
import com.wf.combinedassault.ballistics.addon.AddonArmorLayer;
import com.wf.combinedassault.vehicle.pojo.CamoAction;
import com.wf.combinedassault.vehicle.part.DecorationUnit;
import com.wf.combinedassault.vehicle.part.DoorUnit;
import com.wf.combinedassault.vehicle.part.MissileHolderUnit;
import com.wf.combinedassault.vehicle.part.PartClipTracks;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.RotatableUnit;
import com.wf.combinedassault.vehicle.part.SuspensionUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.OversizedVehicles;
import com.wf.combinedassault.vehicle.parenting.CarrierDecks;
import com.wf.combinedassault.vehicle.parenting.CarrierLink;
import com.wf.combinedassault.vehicle.parenting.DeckAttachment;
import com.wf.combinedassault.vehicle.parenting.DeckCarrier;
import com.wf.combinedassault.vehicle.parenting.DeckCarry;
import com.wf.combinedassault.vehicle.parenting.DeckSnapshot;
import com.wf.combinedassault.vehicle.parenting.VehicleHarness;
import com.wf.combinedassault.vehicle.parenting.VehicleParenting;
import com.wf.combinedassault.vehicle.passenger.WarningReceiver;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.pojo.DefenseStats;
import com.wf.combinedassault.energy.FuelSpec;
import com.wf.combinedassault.energy.VehicleBattery;
import com.wf.combinedassault.energy.VehicleEnergyBuffer;
import com.wf.combinedassault.energy.VehicleFuelTank;
import com.wf.combinedassault.energy.VehicleRefuelInteraction;
import com.wf.combinedassault.energy.VehicleResupply;
import com.wf.combinedassault.vehicle.pojo.EnergyInfo;
import com.wf.combinedassault.vehicle.pojo.ViewInfo;
import com.wf.wflib.util.OBB;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;
import com.wf.combinedassault.vehicle.hit.ClipRig;
import com.wf.combinedassault.vehicle.hit.HitModel;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;
import com.wf.combinedassault.vehicle.structure.VehicleStructOBBs;

import java.util.*;
import com.wf.combinedassault.vehicle.structure.ConvexHull;
import com.wf.combinedassault.vehicle.collision.ConvexSweep;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.wflib.probe.ProbeJobs;

public abstract class AbstractVehicle extends ContainerCraft
        implements RemoteTickEntity, OBBEntity, ICustomVehicle, IEntityWithComplexSpawn, DetachedBodyHost,
        com.wf.combinedassault.vehicle.parenting.DeckCarrier, TargetIlluminator, BeamSource,
        com.wf.wflib.telemetry.TelemetryReporter, com.wf.wflib.entity.InterceptTarget, LaserTarget,
        RadioCarrier {

    public static final EntityDataAccessor<Float> X_ROT = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> Y_ROT = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> Z_ROT = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> ENERGY = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.FLOAT);
    /** kg of fitted parts/payload ({@link PartUnit#mass}, addon armour); synced so prediction agrees. */
    public static final EntityDataAccessor<Float> PART_MASS = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.FLOAT);
    /** Physics kg of hold items + stowed vehicles; synced so prediction agrees. */
    public static final EntityDataAccessor<Float> CARGO_MASS = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.FLOAT);
    /** UAV datalink to its remote pilot, 0..1 (wflib {@link Datalink}); 1 with no remote pilot. */
    public static final EntityDataAccessor<Float> LINK = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.FLOAT);
    private static final float LINK_QUANTUM = 1f / 64f;
    /** Index into the data's {@code loadouts}; -1 = none chosen yet. */
    public static final EntityDataAccessor<Integer> LOADOUT = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Float> POWER = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.FLOAT);
    /** wflib radio station; "" = off. */
    public static final EntityDataAccessor<String> RADIO = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.STRING);
    /** Registry id of the fluid in the tank, or empty. Synced so the fuel gauge can name it. */
    public static final EntityDataAccessor<String> FUEL = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<Float> ENGINE_SPEED = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Boolean> ENGINE_ON = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Boolean> DESTROYED = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.BOOLEAN);
    /** {@link VehicleLight.Kind#bit()} set => that group on. */
    public static final EntityDataAccessor<Byte> LIGHTS = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.BYTE);
    /** Bit = seat index whose sight is in IR (own illuminator). Not saved; seat changing hands cleared. */
    public static final EntityDataAccessor<Integer> IR_SIGHTS = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.INT);
    /** {@link HullComponents#toSync()}: pool health by id, for the HUD and probe. */
    public static final EntityDataAccessor<CompoundTag> SYSTEMS = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.COMPOUND_TAG);
    public static final EntityDataAccessor<CompoundTag> ARMOR_WEAR = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.COMPOUND_TAG);
    public static final EntityDataAccessor<CompoundTag> DETACHED_ANCHORS = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.COMPOUND_TAG);
    /** Stowed cargo as drawn: {@code v} = [{m: model, p: local pos, q: local rotation}]. */
    public static final EntityDataAccessor<CompoundTag> CARGO_VIEW = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.COMPOUND_TAG);
    public static final EntityDataAccessor<Boolean> PARACHUTE_DEPLOYED = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.BOOLEAN);
    /**
     * Carrier id this vehicle is asleep on, or -1. Synced: else client interpolates a parked
     * aircraft and its ship separately => visual drift.
     */
    public static final EntityDataAccessor<Integer> HARNESS_CARRIER = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.INT);
    /**
     * Carrier id holding this vehicle's deck while awake, or -1. Synced: client must place this
     * vehicle in one step against a hull reporting every tick, or lag reads as sliding on deck.
     */
    public static final EntityDataAccessor<Integer> DECK_CARRIER = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.INT);
    /**
     * Pose in the harness/deck carrier's deck frame (pivot-relative), server-written. Client placing from the two
     * entities' reported poses jumped one tick of carrier travel whenever their updates landed in different ticks.
     */
    public static final EntityDataAccessor<Vector3f> DECK_LOCAL = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.VECTOR3);
    public static final EntityDataAccessor<Quaternionf> DECK_LOCAL_ROTATION = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.QUATERNION);
    /**
     * Vehicle towing this one, entity id, or -1. Synced: client draws the coupling and needs to
     * know one exists; the solve stays server-side.
     */
    public static final EntityDataAccessor<Integer> TOW_TRACTOR = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.INT);
    /** Which of the tractor's hitches, and which kind of coupling: {@code index * TowMode.COUNT + mode}, or -1. */
    public static final EntityDataAccessor<Integer> TOW_HITCH = SynchedEntityData.defineId(AbstractVehicle.class, EntityDataSerializers.INT);
    private static final String DETACHED_ANCHORS_TAG = "DetachedAnchors";
    protected ResourceLocation vehicleId;
    protected ResourceLocation displayId;
    private VehicleModelInstance vehicleModelInstance;
    private VehicleModelInstance cabinModelInstance;
    private Component name;
    public final ControlUnit controlUnit;
    public List<Seat> seats;
    /** Server: passenger id that set each {@link #IR_SIGHTS} bit. */
    private final Int2IntMap irSightOwners = new Int2IntOpenHashMap();
    protected final List<PartUnit<?>> partUnits;
    protected Map<String, PartUnit<?>> partUnitMap;
    protected final List<SuspensionUnit<?>> suspensionUnits;
    protected final Map<String, DecorationUnit> decorationUnits;
    protected final CamoLayer camoLayer = new CamoLayer();
    protected final AddonArmorLayer addonArmor = new AddonArmorLayer();
    protected final HashSet<BulletHoleParticle> bulletHoleParticles;
    protected ViewInfo viewInfo;
    protected boolean viewZoomed;
    public EnergyInfo energyInfo;
    public DefenseStats defenseStats;
    public ArmorProfile armorProfile;
    private final com.wf.combinedassault.ballistics.ArmorRegions armorRegions = new com.wf.combinedassault.ballistics.ArmorRegions();
    public Vec3 centerOffset;
    public float curbWeight;
    /** Chassis mass added per kilogram of cargo; see StorageInfo#cargoMassPerKg. */
    public float cargoMassPerKg;
    /** Cargo kg past which the engine strains; 0 = none. StorageInfo#maxPayload. */
    public float maxPayload;
    private final VehicleFuelTank fuelTank = new VehicleFuelTank(this);
    private final VehicleEnergyBuffer energyBuffer = new VehicleEnergyBuffer(this);
    /** Slotted battery item ({@link EnergyInfo#batterySlot}); drains into {@link #addEnergy}, never ejected on its own. */
    private ItemStack batteryItem = ItemStack.EMPTY;
    @Nullable
    private FuelSpec fuelSpec;
    private String cachedFuelId = "";
    private float cachedFuelEfficiency = 1f;
    /** What the hold weighs, recounted lazily. */
    private final CargoMass cargoMass = new CargoMass();
    /** Declared places other vehicles can be bolted to. Empty for everything that declares none. */
    public List<MountPointInfo> mountPoints = List.of();
    /** Whether this vehicle can be bolted onto someone else's mount point. */
    public MountableInfo mountable = new MountableInfo();
    /** A mount read back from a save whose carrier has not been found yet. */
    @Nullable
    private VehicleMounts.Pending pendingMount;
    /** What this vehicle can pull and what can pull it. Empty for everything that declares none. */
    public TowInfo towing = TowInfo.NONE;
    @Nullable
    private CargoHold cargoHold;
    /** Saved hold of a def that no longer declares {@code cargo}: written back unread, never dropped. */
    @Nullable
    private ListTag cargoOrphan;
    private final VehicleParachute parachute = new VehicleParachute();
    /** Left a hold in flight: never seeks a deck below (the carrier's own ramp). */
    private boolean deckSeekBlocked;
    /** Blocks/tick downward on the last airborne tick; 0 grounded. */
    private double lastFall;
    /** Set while this vehicle is on the end of someone's hitch or cable. */
    @Nullable
    private TowLink towLink;
    /** A coupling read back from a save whose tractor has not been found yet. */
    @Nullable
    private TowLinks.Pending pendingTow;
    public Vec3 deltaMovementO;
    private float xRot;
    public float xRotO;
    private float lerpXRot;
    private float yRot;
    public float yRotO;
    private float lerpYRot;
    private float zRot;
    public float zRotO;
    private float lerpZRot;
    private int lerpRotSteps;
    protected List<VehicleCubeOBB> vehicleCubeOBBs;
    protected VehicleCubeOBB mainCubeOBB;
    private List<OBB> cachedOBBs = List.of();
    private List<OBB> activeOBBs = List.of();
    private ChunkCollisionCache.Cursor collisionCursor;
    private final BoxBuffer scratchBoxes = new BoxBuffer();
    // Providers hand back real AABBs, cached and reused across ticks rather than rebuilt.
    // Hull swept against world, rewritten per substep; must not live on shared cube groups.
    private final OBB sweepHull = new OBB(new Vector3f(), new Vector3f(), new Quaternionf());
    private final SweptHull.Broadphase sweptBroadphase = new SweptHull.Broadphase();
    private final OBB.SatFrame sweepFrame = new OBB.SatFrame();
    /**
     * Real cast shape, for a vehicle whose pack drew one. Cut to climb skirt, held in cast box's
     * frame: rebuilt only on skirt/shape change (rare). Pose = rotation, once/tick. Owned by the solve.
     */
    private final ConvexSweep castSweep = new ConvexSweep();
    /**
     * The pieces this vehicle collides as, beyond the one above. Empty unless the pack drew shapes
     * the physics hull does not already cover. Refilled by {@link #updateOBBs}; owned by the solve.
     */
    private final CompoundBody collisionBody = new CompoundBody();
    /** Damage to systems that are hull geometry rather than parts; see {@link #hullComponents()}. */
    @Nullable
    private HullComponents hullComponents;
    @Nullable
    private CompoundTag hullComponentSave;
    @Nullable
    private ConvexHull castHull;
    @Nullable
    private ConvexHull castSource;
    private double castSkirt = Double.NaN;
    private float castExtentY = Float.NaN;
    @Nullable
    private AABB preparedBounds;
    private boolean tickAnySolidNearby;
    private final OBB.SatFrame supportFrame = new OBB.SatFrame();
    private final Vector3f supportMtv = new Vector3f();
    private final Quaternionf cachedRotYXZ = new Quaternionf();
    private float cachedRotYXZYaw = Float.NaN;
    private float cachedRotYXZPitch = Float.NaN;
    private float cachedRotYXZRoll = Float.NaN;
    /** Blocks; covers sample overshoot + block rounding. */
    private static final double SAMPLE_GATE_MARGIN = 2.0;
    /** Flattened body+part geometry, indexed once at startup; static after initData. */
    private List<VehicleCubeOBB> allCubeOBBs;
    @Nullable
    private VehicleHits hits;
    @Nullable
    private PartClipTracks clipTracks;
    private int[] cubeGroupIndex = new int[0];
    /** Owning part unit per entry of allCubeOBBs, or -1 for body cubes. */
    private int[] cubePartIndex = new int[0];
    private boolean[] cubeDetached = new boolean[0];
    /** Group forest, topological order; transforms must not live on shared cube groups. */
    private VehicleCubeGroup[] groupOrder = new VehicleCubeGroup[0];
    private int[] groupParent = new int[0];
    private Vector3f[] groupOffset = new Vector3f[0];
    private Quaternionf[] groupRotation = new Quaternionf[0];
    private double localMinX, localMinY, localMinZ, localMaxX, localMaxY, localMaxZ;
    private boolean localBoundsValid;
    private final Matrix3f scratchLocalRot = new Matrix3f();
    /**
     * mainCubeOBB excluded deliberately: hull's bounding volume, too large to stand on for
     * anything wing-shaped. Double-buffered: updateOBBs fills the unpublished one, then
     * publishes it via deckSnapshot.
     */
    private final DeckSnapshot[] deckSnapshotBuffers = {new DeckSnapshot(), new DeckSnapshot()};
    private int deckSnapshotSlot;
    private volatile DeckSnapshot deckSnapshot = DeckSnapshot.EMPTY;
    /** Count of allCubeOBBs declared as landing surface. */
    private int deckCubes;
    /** Pack-tagged `main` collider: outside {@link #allCubeOBBs}, so the deck set adds it. */
    private boolean mainOwnPiece;
    /**
     * This tick's frozen view of the carrier this vehicle is flying over, driving on or landing on.
     * Per vehicle and rewritten on the tick thread, so the solve reads numbers nobody else is touching.
     */
    private final CarrierLink carrierLink = new CarrierLink();
    /** Set while this vehicle's pose is derived from a carrier's rather than solved. */
    @Nullable
    private VehicleHarness harness;
    /** Consecutive ticks this vehicle has been parked on a deck, counting towards harness. */
    private int harnessDwell;
    /** Game time this vehicle last ticked, which says whether its old pose has been taken yet. */
    private long lastTickTime = Long.MIN_VALUE;
    /** The ride this vehicle takes from a deck it stands on while awake. */
    private final DeckCarry deckCarry = new DeckCarry();
    /** Vehicles asleep on this one. */
    private final List<AbstractVehicle> harnessedVehicles = new ArrayList<>(0);
    /** Whether this vehicle is currently in its level's carrier list. */
    private boolean carrierRegistered;
    /** Entities parented to this vehicle. */
    private final List<Entity> deckRiders = new ArrayList<>();
    /**
     * support() resolve passes. One pass sums overlapping pushes; re-test converges
     * instead. Bounded against pathological geometry.
     */
    private static final int SUPPORT_RESOLVE_PASSES = 4;
    /** Upper bound on movement substeps; caps a fast vehicle's per-tick cost. */
    private static final int MAX_COLLISION_SUBSTEPS = 16;
    /** Blocks per collision step; half block width hides between samples. */
    private static final double SAFE_STEP = 0.5;
    /** Clearance above max step height in sweep hull; avoids clipping on boundary-height steps. */
    private static final double SWEEP_STEP_MARGIN = 0.05;
    protected double structureLength;
    protected float bodyDepth;
    /** Client only. */
    private VehicleAudio audio;
    public WarningReceiver warningReceiver;
    public PhysicsEngine physicsEngine;
    /** The working pose the rigid step runs against. */
    final PhysicsRig physicsRig = new PhysicsRig();
    final VehicleStep step = new VehicleStep(this);
    /** Client: driver-side prediction of this vehicle, null when not predicted (docs/client-prediction.md). */
    @Nullable
    VehiclePrediction prediction;
    /** World view frozen for this vehicle's async solve. */
    private final ChunkCollisionCache.PinnedSections pinnedSections =
            new ChunkCollisionCache.PinnedSections();
    /** Physics trace recorder if one was requested; null otherwise. */
    @Nullable
    private PhysicsTrace physicsTrace;
    /** Consecutive ticks players have been shoving this hull back onto its wheels. */
    public int rightingHold;
    private final HashMap<LivingEntity, Vec3> dismountLocations;
    private final DetachedAnchors anchors = new DetachedAnchors(this, DETACHED_ANCHORS);
    protected boolean driverXYRotControl = false;
    public boolean uav = false;
    /** Probe pick up -> spawn item carrying {@link com.wf.combinedassault.item.VehicleSpawnItem#TAG_STATE}. */
    public boolean pickup = false;
    /** Set each tick the autopilot wrote {@link #controlUnit}: no-driver reset skipped. */
    public boolean autopilotControl;
    /** wflib autonomy body; null = manual only. */
    @Nullable
    public VehicleDrone drone;
    private List<LoadoutInfo> loadouts = List.of();
    /** Saved drone state read before initData built the body. */
    @Nullable
    private CompoundTag droneTag;
    /** {@link #board} -> {@link #onEnterVehicle}; -1 = default pick. */
    private int requestedSeat = -1;
    /** Last player to fly this UAV: its datalink keeps reporting to them while it flies itself. */
    @Nullable
    private UUID uavOperator;
    /** Claimed side (WarForge faction / team UUID), server only; null = crew's team. */
    @Nullable
    private UUID side;
    protected boolean canWade;
    public boolean collision = true;
    public boolean remote = false;
    public PlayerTeam remoteTeam;
    public boolean protectPassenger;
    protected boolean dataInitialized;
    protected int destroyedTick;
    protected int engineParticleTick;
    public long lastRenderTime;

    protected AbstractVehicle(EntityType<? extends AbstractVehicle> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
        this.vehicleId = EntityType.getKey(pEntityType);
        this.seats = new ArrayList<>();
        this.controlUnit = new ControlUnit(this);
        this.partUnits = new ArrayList<>();
        this.partUnitMap = Map.of();
        this.suspensionUnits = new ArrayList<>();
        this.decorationUnits = new HashMap<>();
        this.bulletHoleParticles = new HashSet<>();
        this.vehicleCubeOBBs = new ArrayList<>();
        this.curbWeight = 1000;
        this.viewInfo = new ViewInfo();
        this.energyInfo = new EnergyInfo();
        this.physicsEngine = new PhysicsEngine(this);
        this.dismountLocations = new HashMap<>();
        // A remotely operated vehicle reaches the client's entity list before its chunk is streamed,
        // so the HUD can draw it before it has ever ticked client-side.
        this.deltaMovementO = Vec3.ZERO;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(X_ROT, 0f);
        builder.define(Y_ROT, 0f);
        builder.define(Z_ROT, 0f);
        builder.define(ENERGY, 0f);
        builder.define(PART_MASS, 0f);
        builder.define(CARGO_MASS, 0f);
        builder.define(LINK, 1f);
        builder.define(LOADOUT, -1);
        builder.define(FUEL, "");
        builder.define(RADIO, "");
        builder.define(POWER, 0f);
        builder.define(ENGINE_SPEED, 0f);
        builder.define(ENGINE_ON, false);
        builder.define(DESTROYED, false);
        builder.define(LIGHTS, (byte) 0);
        builder.define(IR_SIGHTS, 0);
        builder.define(SYSTEMS, new CompoundTag());
        builder.define(ARMOR_WEAR, new CompoundTag());
        builder.define(DETACHED_ANCHORS, new CompoundTag());
        builder.define(CARGO_VIEW, new CompoundTag());
        builder.define(PARACHUTE_DEPLOYED, false);
        builder.define(HARNESS_CARRIER, -1);
        builder.define(DECK_CARRIER, -1);
        builder.define(DECK_LOCAL, new Vector3f());
        builder.define(DECK_LOCAL_ROTATION, new Quaternionf());
        builder.define(TOW_TRACTOR, -1);
        builder.define(TOW_HITCH, -1);
    }

    @Override
    public void onSyncedDataUpdated(List<SynchedEntityData.DataValue<?>> dataValues) {
        super.onSyncedDataUpdated(dataValues);
        if (prediction != null) {
            prediction.resync(this);
        }
        if (dataValues.stream().anyMatch(value -> value.id() == ENERGY.id() || value.id() == PART_MASS.id()
                || value.id() == CARGO_MASS.id())) {
            refreshMass();
        }
        if (level().isClientSide() && hullComponents != null
                && dataValues.stream().anyMatch(value -> value.id() == SYSTEMS.id())) {
            hullComponents.fromSync(entityData.get(SYSTEMS));
        }
        if (level().isClientSide() && dataValues.stream().anyMatch(value -> value.id() == ARMOR_WEAR.id())) {
            armorRegions.fromSync(entityData.get(ARMOR_WEAR));
        }
        if (dataValues.stream().anyMatch(value -> value.id() == X_ROT.id() || value.id() == Y_ROT.id() || value.id() == Z_ROT.id())) {
            float targetXRot = entityData.get(X_ROT);
            float targetYRot = entityData.get(Y_ROT);
            float targetZRot = entityData.get(Z_ROT);
            if (targetXRot != lerpXRot || targetYRot != lerpYRot || targetZRot != lerpZRot) {
                this.lerpRotSteps = 3;
            }
            this.lerpXRot = targetXRot;
            this.lerpYRot = targetYRot;
            this.lerpZRot = targetZRot;
        }
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putBoolean("Engine", isEngineOn());
        compound.putByte("Lights", entityData.get(LIGHTS));
        compound.putFloat("Energy", getEnergy());
        compound.putString("Fuel", fuelId());
        if (!batteryItem.isEmpty()) {
            compound.put("BatteryItem", batteryItem.save(registryAccess()));
        }
        compound.putString("Radio", radioStation());
        compound.putFloat("Power", getPower());
        compound.putBoolean("Destroyed", isDestroyed());
        compound.putInt("DestroyedTick", destroyedTick);
        compound.putString(ICustomVehicle.TAG_VEHICLE_ID, this.getVehicleId().toString());
        compound.putString(ICustomVehicle.TAG_VEHICLE_DISPLAY_ID, this.getDisplayId().toString());
        compound.put("PartUnits", serializePartUnitsData());
        compound.put("DecorationUnits", serializeDecorationUnitsData());
        if (hullComponents != null) {
            hullComponents.save(compound);
        } else if (hullComponentSave != null) {
            // Saved again before anything asked for the systems, so hand back what came in rather
            // than building them just to write them out unchanged.
            compound.merge(hullComponentSave);
        }
        armorRegions.save(compound);
        CompoundTag camoTag = camoLayer.save();
        if (!camoTag.isEmpty()) {
            compound.put("Camo", camoTag);
        }
        if (addonArmor.anyInstalled()) {
            compound.put("AddonArmor", addonArmor.save());
        }
        VehicleMounts.save(this, compound);
        TowLinks.save(this, compound);
        ListTag hold = cargoHold != null ? cargoHold.save() : cargoOrphan;
        if (hold != null && !hold.isEmpty()) {
            compound.put(CargoHold.TAG, hold);
        }
        parachute.save(compound);
        if (uavOperator != null) {
            compound.putUUID("UavOperator", uavOperator);
        }
        if (drone != null) {
            compound.put("Drone", drone.save());
        }
        compound.putInt("Loadout", loadoutIndex());
        if (side != null) {
            compound.putUUID("Side", side);
        }
        CompoundTag anchors = entityData.get(DETACHED_ANCHORS);
        if (!anchors.isEmpty()) {
            compound.put(DETACHED_ANCHORS_TAG, anchors.copy());
        }
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag compound) {
        // ContainerCraft reads the hold, and the list it reads into was sized before this vehicle knew
        // what it declared. Size it first, or a save holding a stack past the default size loses it.
        if (compound.contains(ICustomVehicle.TAG_VEHICLE_ID, Tag.TAG_STRING)) {
            ResourceLocation savedVehicleId = ResourceLocation.tryParse(compound.getString(ICustomVehicle.TAG_VEHICLE_ID));
            if (savedVehicleId != null) {
                CommonAssetsManager.vehicleDataManager().getVehicleData(savedVehicleId)
                        .ifPresent(data -> setContainerSize(data.getStorage().resolvedSlots()));
            }
        }
        super.readAdditionalSaveData(compound);
        // Held rather than applied: the systems are built from collision pieces, and on a load those
        // may not exist yet. Applied the first time anything asks what condition they are in.
        hullComponentSave = compound.copy();
        hullComponents = null;
        armorRegions.load(compound);
        if (compound.contains("Engine")) {
            // Raw: toggleEngine's system gate would build the pools before their pieces exist.
            entityData.set(ENGINE_ON, compound.getBoolean("Engine"));
        }
        entityData.set(LIGHTS, compound.getByte("Lights"));
        String radio = compound.getString("Radio");
        entityData.set(RADIO, RadioCarrier.validStation(radio) ? radio : "");
        if (compound.contains("Energy")) {
            setEnergy(compound.getFloat("Energy"));
        }
        // A save from before tanks knew what was in them arrives with no fluid at all; setEnergy
        // above has already given it the vehicle's own default, which is what it burned back then.
        if (compound.contains("Fuel", Tag.TAG_STRING)) {
            entityData.set(FUEL, compound.getString("Fuel"));
        }
        batteryItem = compound.contains("BatteryItem")
                ? ItemStack.parse(registryAccess(), compound.getCompound("BatteryItem")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        if (compound.contains("Power")) {
            setPower(compound.getFloat("Power"));
        }
        uavOperator = compound.hasUUID("UavOperator") ? compound.getUUID("UavOperator") : null;
        if (compound.contains("Loadout")) {
            setLoadout(compound.getInt("Loadout"));
        }
        if (compound.contains("Drone")) {
            if (drone != null) {
                drone.load(compound.getCompound("Drone"));
            } else {
                droneTag = compound.getCompound("Drone");
            }
        }
        side = compound.hasUUID("Side") ? compound.getUUID("Side") : null;
        if (compound.contains("Destroyed", Tag.TAG_ANY_NUMERIC)) {
            entityData.set(DESTROYED, compound.getBoolean("Destroyed"));
        }
        if (compound.contains("DestroyedTick", Tag.TAG_ANY_NUMERIC)) {
            destroyedTick = compound.getInt("DestroyedTick");
        }
        if (compound.contains(ICustomVehicle.TAG_VEHICLE_ID, Tag.TAG_STRING)) {
            ResourceLocation vehicleId = ResourceLocation.tryParse(compound.getString(ICustomVehicle.TAG_VEHICLE_ID));
            if (vehicleId != null) {
                this.vehicleId = vehicleId;
            }
        }
        if (compound.contains(ICustomVehicle.TAG_VEHICLE_DISPLAY_ID, Tag.TAG_STRING)) {
            ResourceLocation displayId = ResourceLocation.tryParse(compound.getString(ICustomVehicle.TAG_VEHICLE_DISPLAY_ID));
            if (displayId != null) {
                this.displayId = displayId;
            }
        }
        this.initData();
        if (compound.contains("PartUnits", Tag.TAG_COMPOUND)) {
            deserializePartUnitsData(compound.getCompound("PartUnits"));
        }
        if (compound.contains("DecorationUnits", Tag.TAG_COMPOUND)) {
            deserializeDecorationUnitsData(compound.getCompound("DecorationUnits"));
        }
        camoLayer.load(registryAccess(), compound.getCompound("Camo"));
        addonArmor.load(compound.getCompound("AddonArmor"));
        VehicleMounts.load(this, compound);
        TowLinks.load(this, compound);
        ListTag hold = compound.getList(CargoHold.TAG, Tag.TAG_COMPOUND);
        if (cargoHold != null) {
            cargoHold.load(hold);
        } else {
            cargoOrphan = hold.isEmpty() ? null : hold;
        }
        parachute.load(compound);
        if (compound.contains(DETACHED_ANCHORS_TAG, Tag.TAG_COMPOUND)) {
            entityData.set(DETACHED_ANCHORS, compound.getCompound(DETACHED_ANCHORS_TAG).copy());
        }
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        buffer.writeResourceLocation(this.getVehicleId());
        buffer.writeResourceLocation(this.getDisplayId());
        buffer.writeInt(destroyedTick);
        buffer.writeNbt(serializePartUnitsData());
        buffer.writeNbt(serializeDecorationUnitsData());
        buffer.writeNbt(camoLayer.save());
        buffer.writeNbt(addonArmor.save());
        buffer.writeInt(seats.size());
        for (Seat seat : seats) {
            buffer.writeInt(seat.passengerId);
        }
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        this.vehicleId = buffer.readResourceLocation();
        this.displayId = buffer.readResourceLocation();
        destroyedTick = buffer.readInt();
        initData();
        deserializePartUnitsData(buffer.readNbt());
        deserializeDecorationUnitsData(buffer.readNbt());
        camoLayer.load(registryAccess(), buffer.readNbt());
        addonArmor.load(buffer.readNbt());
        initDisplayData();
        int[] passengerIdsBySeat = new int[buffer.readInt()];
        for (int index = 0; index < passengerIdsBySeat.length; index += 1) {
            passengerIdsBySeat[index] = buffer.readInt();
        }
        setSeats(passengerIdsBySeat);
    }

    public void writeData(CompoundTag data) {
        data.putString(ICustomVehicle.TAG_VEHICLE_ID, getVehicleId().toString());
        Entity driver = getDriver();
        if (driver != null) {
            data.putInt("driverId", driver.getId());
            PlayerTeam team = driver.getTeam();
            if (team != null) {
                data.putString("teamName", team.getName());
            }
        }
        data.putBoolean("Destroyed", isDestroyed());
    }

    public void readData(CompoundTag data) {
        if (data.contains("driverId")) {
            controlUnit.setOperatorId(data.getInt("driverId"));
        }
        if (data.contains("teamName")) {
            remoteTeam = level().getScoreboard().getPlayerTeam(data.getString("teamName"));
        } else {
            remoteTeam = null;
        }
        if (data.contains("Destroyed")) {
            entityData.set(DESTROYED, data.getBoolean("Destroyed"));
        }
    }

    public void remoteTick() {}

    private CompoundTag serializePartUnitsData() {
        CompoundTag partUnitsTag = new CompoundTag();
        partUnits.forEach((partUnit -> {
            CompoundTag partTag = partUnit.serializeNBT(registryAccess());
            if (partTag.isEmpty()) {
                return;
            }
            partUnitsTag.put(partUnit.getId(), partTag);
        }));
        return partUnitsTag;
    }

    private void deserializePartUnitsData(CompoundTag partUnitsTag) {
        if (partUnitsTag != null) {
            partUnits.forEach(partUnit -> {
                if (partUnitsTag.contains(partUnit.getId(), Tag.TAG_COMPOUND)) {
                    CompoundTag partTag = partUnitsTag.getCompound(partUnit.getId());
                    partUnit.deserializeNBT(registryAccess(), partTag);
                }
            });
        }
    }

    private CompoundTag serializeDecorationUnitsData() {
        CompoundTag decorationUnitsTag = new CompoundTag();
        decorationUnits.values().forEach((decorationUnit -> {
            CompoundTag partTag = decorationUnit.serializeNBT(registryAccess());
            if (partTag.isEmpty()) {
                return;
            }
            decorationUnitsTag.put(decorationUnit.getId(), partTag);
        }));
        return decorationUnitsTag;
    }

    private void deserializeDecorationUnitsData(CompoundTag decorationUnitsTag) {
        if (decorationUnitsTag == null) {
            return;
        }
        for (String id : decorationUnitsTag.getAllKeys()) {
            if (decorationUnitsTag.contains(id, Tag.TAG_COMPOUND)) {
                PartUnitPojo partUnitPojo = new PartUnitPojo();
                partUnitPojo.id = id;
                DecorationUnit decorationUnit = new DecorationUnit(id.hashCode(), this, new PartUnitData(partUnitPojo));
                CompoundTag partTag = decorationUnitsTag.getCompound(id);
                decorationUnit.deserializeNBT(registryAccess(), partTag);
                decorationUnits.put(id, decorationUnit);
            }
        }
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!level().isClientSide()) {
            if (!dataInitialized) {
                initData();
            }
            if (drone != null) {
                DroneTracker.track(drone);
            }
            if (!loadouts.isEmpty() && loadoutIndex() < 0) {
                Loadouts.select(this, 0, null);
            }
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (!level().isClientSide() && drone != null) {
            DroneTracker.untrack(drone);
            drone.release();
        }
        CarrierDecks.unregister(this);
        carrierRegistered = false;
        OversizedVehicles.setRegistered(this, false);
        oversizedRegistered = false;
        RemovalReason reason = getRemovalReason();
        if (!level().isClientSide() && reason == RemovalReason.KILLED && cargoHold != null) {
            cargoHold.releaseAll();
        }
        if (!level().isClientSide()
                && (reason == RemovalReason.UNLOADED_TO_CHUNK || reason == RemovalReason.UNLOADED_WITH_PLAYER)) {
            // Own links were saved with this entity; the far ends wait for it to load again.
            VehicleParenting.unloadAll(this);
            VehicleHarness.unloadAll(this);
            VehicleHarness.detach(this, false);
            TowLinks.unload(this);
        } else {
            VehicleParenting.releaseAll(this, true);
            VehicleHarness.releaseAll(this, true);
            VehicleHarness.detach(this, true);
            TowLinks.detach(this);
        }
        partUnits.forEach((PartUnit::onRemoved));
        if (!level().isClientSide()) {
            for (Entity operator : new ArrayList<>(getDetachedOperators())) {
                operator.stopRiding();
            }
        }
    }

    public void initDisplayData() {
        ClientAssetsManager.INSTANCE.getVehicleDisplay(this.getDisplayId()).ifPresent(this::initDisplayData);
    }

    public void initData() {
        CommonAssetsManager.vehicleDataManager()
                .getVehicleData(this.getVehicleId())
                .ifPresentOrElse(this::initData,
                    () -> {
                        CombinedAssault.LOGGER.error("No vehicle data found for {}", vehicleId);
                        this.discard();
                    }
                );
    }

    public void initDisplayData(VehicleDisplay<?, ?> display) {
        VehicleModel vehicleModel = display.getVehicleModel();
        if (vehicleModel != null) {
            vehicleModelInstance = vehicleModel.createInstance();
        }
        if (display.getCabinDisplay() != null) {
            VehicleModel cabinModel = display.getCabinDisplay().getVehicleModel();
            if (cabinModel != null) {
                cabinModelInstance = cabinModel.createInstance();
            }
        }
    }

    private void initData(BaseVehicleData vehicleData) {
        if (getHealth() < 0) {
            setMaxHealth(vehicleData.getMaxHealth());
            setHealth(vehicleData.getMaxHealth());
        }
        this.name = vehicleData.getName();
        this.viewInfo = vehicleData.getViewInfo();
        setEnergyInfo(vehicleData.getEnergyInfo());
        // curbWeight = declared mass; everything carried adds to it. Must land before first
        // refuel/mass read (ctor default is a throwaway 1).
        this.curbWeight = vehicleData.getPhysicsInfo().mass;
        this.cargoMassPerKg = vehicleData.getStorage().cargoMassPerKg;
        this.maxPayload = vehicleData.getStorage().maxPayload;
        this.mountPoints = vehicleData.getMountPoints();
        this.mountable = vehicleData.getMountable();
        this.towing = vehicleData.getTowing();
        if (vehicleData.getCargo().enabled()) {
            CargoHold hold = new CargoHold(this, vehicleData.getCargo());
            if (cargoHold != null) {
                hold.load(cargoHold.save());
            } else if (cargoOrphan != null) {
                hold.load(cargoOrphan);
                cargoOrphan = null;
            }
            cargoHold = hold;
        } else if (cargoHold != null) {
            cargoOrphan = cargoHold.save();
            cargoHold = null;
        }
        setContainerSize(vehicleData.getStorage().resolvedSlots());
        this.physicsEngine.mass = vehicleData.getPhysicsInfo().mass;
        this.physicsEngine.center = vehicleData.getPhysicsCenter();
        this.physicsEngine.canDestroyBlock = vehicleData.getPhysicsInfo().canDestroyBlock;
        this.physicsEngine.radarCrossSection = vehicleData.getPhysicsInfo().radarCrossSection;
        this.physicsEngine.friction = vehicleData.getPhysicsInfo().friction;
        this.physicsEngine.destroyExplosionVelocity = vehicleData.getPhysicsInfo().destroyExplosionVelocity;
        this.physicsEngine.density = vehicleData.getPhysicsInfo().density;
        this.physicsEngine.liquidDamping = vehicleData.getPhysicsInfo().liquidDamping;
        this.defenseStats = vehicleData.getDefenseStats();
        this.armorProfile = vehicleData.getArmor();
        this.addonArmor.bind(vehicleData.getAddonMounts());
        this.centerOffset = vehicleData.getCenterOffset();
        VehicleStructOBBs vehicleStruct = vehicleData.getVehicleStructObbs();
        // Reruns on every load (/data merge, save path): appending kept a stale part set first in partUnits.
        int[] riders = seats.stream().mapToInt(seat -> seat.passengerId).toArray();
        this.vehicleCubeOBBs.clear();
        this.partUnits.forEach(PartUnit::onRemoved);
        List<PartUnit<?>> replaced = List.copyOf(this.partUnits);
        this.partUnits.clear();
        this.seats.clear();
        this.vehicleCubeOBBs.addAll(vehicleStruct.obbs());
        this.mainCubeOBB = vehicleStruct.mainCubeOBB();
        this.structureLength = vehicleData.getStructureLength();
        this.bodyDepth = vehicleData.getBodyDepth();
        BaseVehicleData.PartUnitsAndSeats partUnitsAndSeats = vehicleData.createPartUnits(this);
        this.partUnits.addAll(partUnitsAndSeats.partUnitMap().values());
        // Fresh holders restart at version 0 => tracking clients drop every update until it passes theirs.
        for (int i = 0; i < Math.min(replaced.size(), partUnits.size()); i++) {
            partUnits.get(i).getSyncData().continueFrom(replaced.get(i).getSyncData());
        }
        this.suspensionUnits.clear();
        for (PartUnit<?> partUnit : partUnits) {
            if (partUnit instanceof SuspensionUnit<?> suspension) {
                suspensionUnits.add(suspension);
            }
        }
        this.seats.addAll(partUnitsAndSeats.seats());
        for (int i = 0; i < Math.min(riders.length, seats.size()); i++) {
            seats.get(i).passengerId = riders[i];
        }
        this.uav = vehicleData.isUav();
        this.pickup = vehicleData.canPickUp();
        this.loadouts = vehicleData.getLoadouts();
        this.canWade = vehicleData.canWade();
        if (vehicleData.withWarningReceiver()) {
            this.warningReceiver = new WarningReceiver(this);
        }
        this.protectPassenger = vehicleData.isProtectPassenger();
        Map<String, PartUnit<?>> map = new HashMap<>();
        for (PartUnit<?> partUnit : partUnits) {
            map.put(partUnit.getId(), partUnit);
        }
        this.partUnitMap = map;
        ClipRig clipRig = vehicleData.getClipRig();
        this.clipTracks = clipRig == null ? null
                : PartClipTracks.bind(clipRig, map, String.valueOf(vehicleData.getVehicleId()), String.valueOf(getDisplayId()));
        HitModel hitModel = vehicleData.getHitModel();
        this.hits = hitModel == null ? null
                : new VehicleHits(this, hitModel, partUnitsAndSeats.groups(), map, clipTracks);
        vehicleData.inject(this);
        if (vehicleData.getAutonomy() == null) {
            this.drone = null;
        } else if (this.drone == null) {
            this.drone = new VehicleDrone(this, vehicleData.getAutonomy());
            if (droneTag != null) {
                this.drone.load(droneTag);
                droneTag = null;
            }
        }
        // Re-index from scratch; initData runs more than once, on the save and spawn data paths,
        // and parts finish attaching cubes here.
        this.allCubeOBBs = null;
        this.collisionBody.clear();
        this.hullComponents = null;
        SuspensionUnit.calibrateRestLengths(this);
        updateOBBs();
        this.dataInitialized = true;
    }

    @NotNull
    @Override
    public ResourceLocation getVehicleId() {
        return this.vehicleId;
    }

    @Override
    public void setVehicleId(@NotNull ResourceLocation vehicleId) {
        this.vehicleId = vehicleId;
    }

    @Override
    public ResourceLocation getDisplayId() {
        if (this.displayId == null) {
            return this.vehicleId;
        }
        return this.displayId;
    }

    @Override
    public void setDisplayId(ResourceLocation displayId) {
        this.displayId = displayId;
        if (!level().isClientSide()) {
            PacketDistributor.sendToPlayersTrackingEntity(this, new ServerVehicleChangeDisplay(this.getId(), displayId));
        }
    }

    /** Fitted kit's hardpoint {@code vehicle_name} > def name. */
    @Override
    public Component getDisplayName() {
        for (PartUnit<?> part : partUnits) {
            if (part instanceof WeaponUnit unit && unit.kit() != null) {
                String named = unit.hardpoint().vehicleName(unit.kit());
                if (named != null) {
                    return Component.translatable(named);
                }
            }
        }
        return name;
    }

    @Override
    public void tick() {
        PhysicsTrace trace = physicsTrace;
        if (trace != null) {
            trace.beginTick(this);
        }
        lastTickTime = com.wf.combinedassault.vehicle.parenting.TickClock.now(level());
        if (!level().isClientSide() && controlUnit.consume()) {
            VehiclePrediction.due(this);
        }
        if (linkLost()) {
            controlUnit.neutral();
        }
        VehicleParenting.capture(this);
        long baseAt = TrialProfile.start();
        super.tick();
        deltaMovementO = getDeltaMovement();
        tickPosAndRot();
        TrialProfile.stop(TrialProfile.Stage.BASE, baseAt);
        long upkeepAt = TrialProfile.start();
        if (!level().isClientSide() && !this.isRemoved()) {
            clearVacantIrSights();
            if (hullComponents != null && hullComponents.dirty()) {
                entityData.set(SYSTEMS, hullComponents.toSync());
            }
            if (armorRegions.dirty()) {
                entityData.set(ARMOR_WEAR, armorRegions.toSync());
            }
            if (isEngineOn() && !VehicleSystems.engineWorks(this)) {
                toggleEngine(false);
            }
            tickLink();
            if (drone != null) {
                drone.tick();
            }
            VehicleResupply.tick(this);
            VehicleBattery.tick(this);
            float partMass = addonArmor.mass();
            for (PartUnit<?> part : partUnits) {
                partMass += part.mass();
            }
            boolean partsChanged = partMass != entityData.get(PART_MASS);
            if (partsChanged) {
                entityData.set(PART_MASS, partMass);
            }
            if (cargoMass.tick(this) | partsChanged) {
                refreshMass();
            }
            VehicleMounts.tickPending(this);
            TowLinks.tickPending(this);
            if (cargoHold != null) {
                cargoHold.tick();
            }
            parachute.tick(this);
            tickLanding();
            if (tickCount == 1) {
                boolean keptOperator = false;
                for (Entity passenger : new ArrayList<>(getPassengers())) {
                    // A remotely connected operator has an anchor recorded the moment they connect; anyone
                    // else came attached to a vehicle that had not ticked yet, which is how a restored or
                    // item-spawned one arrives, and is stale. Ejecting the operator too dismounted them
                    // beside the drone instead of returning them to their body.
                    if (getDetachedBodyAnchor(passenger) == null) {
                        passenger.stopRiding();
                    } else {
                        keptOperator = true;
                    }
                }
                if (!keptOperator) {
                    clearDetachedBodyAnchors();
                }
            }
            if (isDestroyed()) {
                destroyedTick += 1;
                if (destroyedTick > 20 * 60) {
                    this.discard();
                }
            }
            if (camoLayer.isDirty()) {
                camoLayer.markClean();
                PacketDistributor.sendToPlayersTrackingEntity(this, new ServerCamoSync(getId(), camoLayer.save()));
            }
            if (addonArmor.isDirty()) {
                addonArmor.markClean();
                PacketDistributor.sendToPlayersTrackingEntity(this, new ServerAddonArmorSync(getId(), addonArmor.save()));
            }
        }
        TrialProfile.stop(TrialProfile.Stage.UPKEEP, upkeepAt);
        boolean stepped = false;
        boolean harnessed = VehicleHarness.isHarnessed(this);
        boolean mounted = VehicleHarness.isMounted(this);
        // A mount waiting for its carrier holds still rather than falling off where the carrier will be.
        boolean resting = !level().isClientSide() && (pendingMount != null || skipRestingSolve());
        if (level().isClientSide()) {
            if (prediction != null && !prediction.tick(this)) {
                dropPrediction();
            }
            if (prediction == null && !this.isRemoved() && !harnessed) {
                aiStep();
            }
            tickSound();
            tickParticle();
            if (warningReceiver != null) {
                warningReceiver.tick();
            }
            if (isDestroyed()) {
                destroyedTick += 1;
            }
        } else if (!this.isRemoved() && (!harnessed || mounted)) {
            // An emplacement still burns fuel and builds power, because a mounted weapon has to be
            // able to spin up its traverse. What a harness skips is the solve, not the housekeeping;
            // a merely parked vehicle skips both, but it also wakes the moment its engine starts.
            tickEnergy();
            tickPower();
            tickEngineSpeed();
            // Housekeeping above still runs while resting; only the solve is worth skipping.
            if (!resting && !harnessed && mainCubeOBB != null) {
                prepareStep();
                RigidStep.register(this);
                stepped = true;
            }
        }
        if (!stepped) {
            finishTick(trace);
        }
    }

    private void finishTick(@Nullable PhysicsTrace trace) {
        long rightAt = TrialProfile.start();
        if (!level().isClientSide() && DroneNetBlock.catchable(this) && DroneNetBlock.caught(level(), this)) {
            if (DroneNetBlock.caught(level(), getBoundingBox().move(xo - getX(), yo - getY(), zo - getZ()))) {
                setPos(xo, yo, zo);
            }
            setDeltaMovement(Vec3.ZERO);
        }
        if (!level().isClientSide()) {
            VehicleRighting.tick(this);
            TrialProfile.stop(TrialProfile.Stage.PARTS, rightAt);
            // Before the OBBs and this vehicle's own riders are resolved, so everything aboard moves
            // with the deck in the same tick it does.
            long carryAt = TrialProfile.start();
            deckCarry.tick(this);
            TrialProfile.stop(TrialProfile.Stage.HARNESS, carryAt);
            rightAt = TrialProfile.start();
        }
        afterVehicleRot();
        tickParts();
        if (clipTracks != null) {
            clipTracks.tick();
            if (hits != null) {
                hits.refreshClips();
            }
        }
        tickDecorations();
        TrialProfile.stop(TrialProfile.Stage.PARTS, rightAt);
        this.level().getProfiler().push("vehicle_obb");
        long obbAt = TrialProfile.start();
        updateOBBs();
        holdBlockedMounts();
        TrialProfile.stop(TrialProfile.Stage.OBB, obbAt);
        this.level().getProfiler().pop();
        this.level().getProfiler().push("vehicle_riders");
        long ridersAt = TrialProfile.start();
        VehicleParenting.tick(this);
        TrialProfile.stop(TrialProfile.Stage.RIDERS, ridersAt);
        long harnessAt = TrialProfile.start();
        VehicleHarness.applyChildren(this, true);
        this.level().getProfiler().pop();
        VehicleHarness.tick(this);
        TrialProfile.stop(TrialProfile.Stage.HARNESS, harnessAt);
        if (trace != null) {
            trace.endTick(this);
        }
    }

    private void postMoveEvent() {
        VehicleMoveEvent __event = new VehicleMoveEvent(this);
        NeoForge.EVENT_BUS.post(__event);
        if (__event.isCanceled()) {
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    protected void tickEnergy() {
        float energy = getEnergy();
        energy = Math.max(0, energy - burnPerTick() * getPower() / 100);
        setEnergy(energy);
        if (energy <= 0) {
            // A dry tank forgets what was in it, which is the only way to change fuels without
            // draining one by hand.
            setFuelFluid(Fluids.EMPTY);
        }
    }

    protected void tickPower() {
        FluidState fluidState = level().getFluidState(BlockPos.containing(new Vec3(mainCubeOBB.obb().center())));
        if (!canWade && !fluidState.isEmpty()) {
            setPower(0);
            return;
        }
        if (getEnergy() == 0) {
            setPower(0);
            return;
        }
        if (isDestroyed()) {
            setPower(Math.max(getPower() - 10, 0));
            return;
        }
        setPower(Mth.clamp(getPower() + (isEngineOn() ? 1 : -1), 0, 100));
    }

    protected void tickEngineSpeed() {
        float engineSpeed = getEngineSpeed();
        if (hasPower() && engineSpeed <= 60) {
            setEngineSpeed(Math.max(engineSpeed + (isEngineOn() ? 1 : -1), 0));
        }
    }









    /** Ticks of stillness before a grounded or floating, empty vehicle drops to the reduced solve rate. */
    private static final int REST_TICKS_TO_SLEEP = 20;

    /**
     * Ticks between full solves while resting; a heartbeat, not a stop. A missed wake costs
     * latency, not a vehicle stuck hanging.
     */
    private static final int REST_SOLVE_INTERVAL = 10;

    private static final double REST_LINEAR = 0.003;
    private static final float REST_ANGULAR = 0.002f;

    private int physicsRestTicks;

    /** A resting hull re-derives the same contacts and zero movement every tick; redundant. */
    private boolean skipRestingSolve() {
        if (!AllConfigs.Cached.restingVehicleSleep || !atRest()) {
            physicsRestTicks = 0;
            return false;
        }
        if (physicsRestTicks < REST_TICKS_TO_SLEEP) {
            physicsRestTicks++;
            return false;
        }
        return tickCount % REST_SOLVE_INTERVAL != 0;
    }

    /** Afloat counts: no waves => damped heave settles under REST_LINEAR. */
    protected boolean restSupported() {
        return onGround() || physicsEngine.afloat();
    }

    private boolean atRest() {
        if (mainCubeOBB == null || !restSupported() || isDestroyed() || this.isRemoved()) {
            return false;
        }
        // Anything aboard can steer, and anything unsupported is still falling.
        if (!getPassengers().isEmpty() || isEngineOn() || carrierLink.active()
                || TowLinks.awake(this)) {
            return false;
        }
        Vec3 movement = getDeltaMovement();
        return movement.lengthSqr() <= REST_LINEAR * REST_LINEAR
                && physicsEngine.angularVelocity.lengthSquared() <= REST_ANGULAR * REST_ANGULAR;
    }





    /** Tick thread; never during the step (island tasks run while the tick thread waits). */
    public void queueRecoil(WeaponUnit weaponUnit, float recoil) {
        physicsEngine.recoil(weaponUnit, recoil);
    }


    /**
     * The snapshot cursor this tick's contacts were resolved through, or null before the first
     * tick that found anything nearby.
     */
    @Nullable
    public ChunkCollisionCache.Cursor collisionCursor() {
        return collisionCursor;
    }

    /**
     * The substep broadphase, already pointed at this level's snapshot cache. The tick's prepare
     * covers the climb band, so the headroom check can query it instead of walking sections.
     */
    public SweptHull.Broadphase sweptBroadphase() {
        return sweptBroadphase;
    }

    /** Whether the outer box fully contains the inner one. False when nothing was prepared. */
    private static boolean covers(@Nullable AABB outer, AABB inner) {
        return outer != null
                && outer.minX <= inner.minX && outer.minY <= inner.minY && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY && outer.maxZ >= inner.maxZ;
    }

    private List<CollisionProvider.Session> openProviderSessions(AABB hullBounds) {
        List<CollisionProvider> registered = CollisionProviders.providers();
        if (registered.isEmpty()) {
            return List.of();
        }
        List<CollisionProvider.Session> sessions = new ArrayList<>(registered.size());
        for (int i = 0, size = registered.size(); i < size; i++) {
            CollisionProvider.Session session = registered.get(i).begin(this, hullBounds);
            if (session != null) {
                sessions.add(session);
            }
        }
        return sessions;
    }

    @Override
    public void move(@NotNull MoverType pType, Vec3 pPos) {
        this.setPos(this.getX() + pPos.x, this.getY() + pPos.y, this.getZ() + pPos.z);
    }

    @Override
    public boolean isInvulnerableTo(@NotNull DamageSource source) {
        return super.isInvulnerableTo(source)
                || (!AllConfigs.common.allowMeleeDamageVehicle.get() && source.is(DamageTypes.PLAYER_ATTACK));
    }

    @Override
    public boolean hurt(@NotNull DamageSource damageSource, float amount) {
        VehicleAttackEvent __VehicleAttackEvent = new VehicleAttackEvent(this, damageSource, amount);
        NeoForge.EVENT_BUS.post(__VehicleAttackEvent);
        if (__VehicleAttackEvent.isCanceled()) {
            return false;
        }
        if (this.isInvulnerableTo(damageSource)) {
            return false;
        } else {
            if (!level().isClientSide()) {
                // Being shot is the clearest possible sign that a parked vehicle's situation has
                // changed. Wake it before the damage lands, so knockback and a wreck's collapse
                // are solved rather than held in place by the harness.
                VehicleHarness.wake(this);
                this.level().broadcastDamageEvent(this, damageSource);
                DamageSystem.hurt(damageSource, amount, this);
                ProbeJobs.cancelOn(this, ProbeJobs.Reason.TARGET_HURT);
                // Wreck: health is its own pool again (setDestroyed); alive: every system failed.
                if (isDestroyed() ? this.getHealth() <= 0 : VehicleSystems.allFailed(this)) {
                    if (isDestroyed()) {
                        this.discard();
                    } else {
                        this.getPassengers().forEach(Entity::stopRiding);
                        setDestroyed();
                        VehicleExplosion vehicleExplosion = new VehicleExplosion(level(), damageSource.getEntity(), this, this.position(),
                                (float) mainCubeOBB.depth / 2, AllConfigs.common.vehicleExplosionHurtPassengerDamage.get().floatValue(), false, false);
                        vehicleExplosion.explode(Collections.singletonList(this));
                        VehiclePartSpawner.spawnDestroyedParts(this);
                    }
                }
            }
            this.markHurt();
            return true;
        }
    }

    public int passengerCapacity() {
        return seats.size();
    }

    public VehicleModelInstance getVehicleModelInstance() {
        return vehicleModelInstance;
    }

    public VehicleModelInstance getCabinModelInstance() {
        return cabinModelInstance;
    }

    public SoundEvent getHurtSound(@NotNull DamageSource pDamageSource) {
        return AllSounds.VEHICLE_HURT.get();
    }

    /** Engine audio state, 0..1 ({@link EngineSound.Input}); SPEED over 100 m/s unless a type knows its maximum. */
    public float audioInput(EngineSound.Input input) {
        return switch (input) {
            case POWER -> getPower() / 100;
            case RPM -> hasPower() ? getEngineSpeed() / 100 : 0;
            case THROTTLE -> 0;
            case SPEED -> (float) (getDeltaMovement().length() * 20 / 100);
        };
    }

    @OnlyIn(Dist.CLIENT)
    protected void tickSound() {
        if (audio == null) {
            audio = new VehicleAudio(this);
        }
        audio.tick();
        if (isDestroyed() && tickCount % 20 == 0) {
            Level level = this.level();
            level.playLocalSound(
                    this.getX(),
                    this.getY(),
                    this.getZ(),
                    SoundEvents.FIRE_AMBIENT,
                    SoundSource.BLOCKS,
                    0.6F + level.random.nextFloat() * 0.4F,
                    0.8F + level.random.nextFloat() * 0.4F,
                    false
            );
        }
    }

    @OnlyIn(Dist.CLIENT)
    protected void tickParticle() {
        Level level = level();
        if (!level.isClientSide()) {
            return;
        }
        if (mainCubeOBB != null && tickCount % 5 == 0 && getDeltaMovement().length() > 0.01f) {
            ParticleUtil.spawnWaterSurfaceBubbles(level, random, mainCubeOBB);
        }
        if (isDestroyed()) {
            if (destroyedTick < 20 * 30 && destroyedTick % 5 == 0) {
                ParticleUtil.spawnDestroyedVehicleCloud(level,
                        new Vec3(mainCubeOBB.obb().center()),
                        (float) Math.max(mainCubeOBB.width, mainCubeOBB.depth),
                        mainCubeOBB.height);
            }
            if (tickCount % 20 == 0) {
                ParticleUtil.spawnWreckageSmoke(level, getBoundingBox(), 5);
            }
        }
    }

    protected abstract Vec3 tickMove();

    /** Hitched or on a cable (not slung): lateral grip is the step's towed-axle row. */
    protected boolean towedOnWheels() {
        TowLink link = towLink();
        return link != null && link.mode() != TowMode.SLING;
    }

    /** Unsprung gear down: contact rows frictionless, pack {@code friction} = rolling resistance. */
    protected boolean rollsOnGear() {
        return false;
    }

    /** Weight held by the controller ({@link #tickMove}); the step applies no gravity. */
    public boolean weightCarried() {
        return false;
    }

    /** Client: attitude predicted locally, synced rotation ignored. */
    protected boolean clientOwnsAttitude() {
        return false;
    }

    protected void tickPosAndRot() {
        this.xRotO = this.xRot;
        this.yRotO = this.yRot;
        this.zRotO = this.zRot;
        if (!level().isClientSide()) {
            VehicleHarness.follow(this);
        }
        if (level().isClientSide()) {
            if (VehicleHarness.clientFollow(this)) {
                return;
            }
            // Awake on a deck: the pose belongs in the carrier's rendered frame, not the world's.
            if (deckCarry.clientFollow(this)) {
                return;
            }
            if (prediction != null) {
                return;
            }
            if (this.lerpSteps > 0) {
                double dX = this.getX() + (this.lerpX - this.getX()) / (double)this.lerpSteps;
                double dY = this.getY() + (this.lerpY - this.getY()) / (double)this.lerpSteps;
                double dZ = this.getZ() + (this.lerpZ - this.getZ()) / (double)this.lerpSteps;
                this.lerpSteps -= 1;
                this.setPos(dX, dY, dZ);
            }
            if (this.lerpRotSteps > 0 && !clientOwnsAttitude()) {
                float dXRot = Mth.wrapDegrees(lerpXRot - this.getXRot());
                float dYRot = Mth.wrapDegrees(lerpYRot - this.getYRot());
                float dZRot = Mth.wrapDegrees(lerpZRot - this.getZRot());
                if (Math.abs(dYRot) > 90 && Math.abs(dZRot) > 90) {
                    this.xRot = Mth.wrapDegrees(180 - this.xRot);
                    this.xRotO = Mth.wrapDegrees(180 - this.xRotO);
                    this.yRot += Math.signum(dYRot) * 180;
                    this.yRotO += Math.signum(dYRot) * 180;
                    this.zRot += Math.signum(dZRot) * 180;
                    this.zRotO += Math.signum(dZRot) * 180;
                    dXRot = Mth.wrapDegrees(lerpXRot - this.getXRot());
                    dYRot = Mth.wrapDegrees(lerpYRot - this.getYRot());
                    dZRot = Mth.wrapDegrees(lerpZRot - this.getZRot());
                }
                this.setXRot(this.getXRot() + dXRot / this.lerpRotSteps);
                this.setYRot(this.getYRot() + dYRot / this.lerpRotSteps);
                this.setZRot(this.getZRot() + dZRot / this.lerpRotSteps);
                this.lerpRotSteps -= 1;
            }
        }
    }

    protected void tickParts() {
        partUnits.forEach(PartUnit::tick);
    }

    protected void tickDecorations() {
        decorationUnits.values().forEach(PartUnit::tick);
    }

    protected void afterVehicleRot() {
        float dXRot = xRot - xRotO;
        float dYRot = yRot - yRotO;
        float dZRot = zRot - zRotO;
        partUnits.forEach(partUnit -> partUnit.afterVehicleRot(dXRot, dYRot, dZRot));
        if (level().isClientSide()) {
            Player player = LocalVehiclePlayer.instance.getPlayer();
            if (player.getVehicle() == this && (!driverXYRotControl || player != getDriver())) {
                boolean rotTp = viewInfo.passengerViewRot.rotByVehicleInThirdPerson && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.THIRD_PERSON;
                boolean rotOp = viewInfo.passengerViewRot.rotByVehicleInOperator && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.OPERATOR;
                if (rotTp || rotOp) {
                    if (rotOp) {
                        player.xRotO = player.xRotO + dXRot;
                        player.setXRot(player.getXRot() + dXRot);
                    }
                    player.yRotO = player.yRotO + dYRot;
                    player.setYRot(player.getYRot() + dYRot);
                    player.setYBodyRot(player.yBodyRot + dYRot);
                }
            }
        } else {
            getPassengers().stream()
                    .filter(passenger -> !(passenger instanceof Player))
                    .forEach(passenger -> {
                        passenger.yRotO = passenger.yRotO + dYRot;
                        passenger.setYRot(passenger.getYRot() + dYRot);
                    });
        }
    }


    @Override
    public List<OBB> getOBBs() {
        return activeOBBs;
    }

    @Nullable
    @Override
    public Vec3 clip(Vec3 from, Vec3 to) {
        return VectorUtil.closestHitObbPosition(this, from, to);
    }

    @Nullable
    @Override
    public Entity illuminatedTarget(Entity missile) {
        return GuidedRounds.illuminated(missile);
    }

    @Nullable
    @Override
    public Beam guidanceBeam(Entity missile) {
        return GuidedRounds.beam(missile);
    }

    /** {@code VehicleRender} draws hull/hit debug itself. */
    @Override
    public boolean drawsDebugOBBs() {
        return false;
    }

    /**
     * Keeps {@link #activeOBBs} in step with which parts are detached. A detached part rides its
     * own entity, so leaving it in the collision list would go on colliding, inflate the AABB and
     * offer a landing surface. Only rebuilds when the detach state actually changed, and shares
     * the cached list outright while nothing is detached.
     */
    /** @return a cube's detached flag changed */
    private boolean refreshActiveOBBs() {
        boolean changed = false;
        boolean anyDetached = false;
        for (int i = 0; i < cubeDetached.length; i++) {
            int part = cubePartIndex[i];
            boolean detached = part >= 0 && partUnits.get(part).isDetached();
            anyDetached |= detached;
            if (cubeDetached[i] != detached) {
                cubeDetached[i] = detached;
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        if (!anyDetached) {
            activeOBBs = cachedOBBs;
            return true;
        }
        List<OBB> obbs = new ArrayList<>(cachedOBBs.size());
        for (VehicleCubeOBB cubeOBB : vehicleCubeOBBs) {
            obbs.add(cubeOBB.obb());
        }
        for (PartUnit<?> partUnit : partUnits) {
            if (!partUnit.isDetached()) {
                obbs.addAll(partUnit.getOBBs());
            }
        }
        activeOBBs = Collections.unmodifiableList(obbs);
        return true;
    }

    /**
     * This vehicle's frame and walkable geometry as of its last bounding box update. Never null,
     * and a vehicle with nothing walkable reads as empty. Take it once per operation, do not hold
     * it, since it is replaced rather than mutated.
     */
    public DeckSnapshot deckSnapshot() {
        return deckSnapshot;
    }

    public SoundType deckSoundType() {
        return SoundType.METAL;
    }

    public List<Entity> deckRiders() {
        return deckRiders;
    }

    @Override
    public void updateOBBs() {
        if (mainCubeOBB == null) {
            return;
        }
        boolean rebuilt = allCubeOBBs == null;
        if (rebuilt) {
            buildOBBIndex();
        }
        boolean detachChanged = refreshActiveOBBs();
        boolean groupsChanged = refreshGroupTransforms();
        if (rebuilt || detachChanged || groupsChanged || hits != null && !hits.boundsCurrent()) {
            localVersion++;
        }

        Quaternionf vehicleRotation = rotYXZShared();
        int structureCubes = allCubeOBBs.size();
        DeckSnapshot deck = deckSnapshotBuffers[deckSnapshotSlot];
        if (deckSlotVersion[deckSnapshotSlot] == localVersion) {
            for (int i = 0; i <= structureCubes; i++) {
                VehicleCubeOBB cubeOBB = i == structureCubes ? mainCubeOBB : allCubeOBBs.get(i);
                int group = cubeGroupIndex[i];
                cubeOBB.update(this, vehicleRotation, groupOffset[group], groupRotation[group]);
            }
            publishOBBs(deck, vehicleRotation, deckBoxCount, deckLandingCount);
            return;
        }
        deckSlotVersion[deckSnapshotSlot] = localVersion;
        float[] deckBoxes = deck.boxBuffer(structureCubes + 1);
        float[] landingBoxes = deckCubes > 0 ? deck.deckBoxBuffer(deckCubes) : deck.deckBoxes();
        deck.shapeBuffers(structureCubes + 1);
        float[] frames = deck.frames();
        ConvexHull[] shapes = deck.hulls();
        boolean[] landing = deck.landing();
        int landingCount = 0;
        int boxCount = 0;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0, size = structureCubes; i <= size; i++) {
            VehicleCubeOBB cubeOBB = i == size ? mainCubeOBB : allCubeOBBs.get(i);
            int group = cubeGroupIndex[i];
            cubeOBB.update(this, vehicleRotation, groupOffset[group], groupRotation[group]);

            // A detached part rides its own entity now. Keep posing it so nothing reads a stale
            // pose, but leave it out of the hull's bounds and deck surfaces.
            if (i < size && cubeDetached[i]) {
                continue;
            }

            Vector3f center = cubeOBB.localCenter();
            Vector3f extents = cubeOBB.obb().extents();
            cubeOBB.localRotation().get(scratchLocalRot);
            float rx = Math.abs(scratchLocalRot.m00) * extents.x + Math.abs(scratchLocalRot.m10) * extents.y + Math.abs(scratchLocalRot.m20) * extents.z;
            float ry = Math.abs(scratchLocalRot.m01) * extents.x + Math.abs(scratchLocalRot.m11) * extents.y + Math.abs(scratchLocalRot.m21) * extents.z;
            float rz = Math.abs(scratchLocalRot.m02) * extents.x + Math.abs(scratchLocalRot.m12) * extents.y + Math.abs(scratchLocalRot.m22) * extents.z;
            // A piece declared not solid is still shot at and still bounds the hull, but nothing
            // stands on it and nothing collides with it: a mudguard, an aerial, a mirror.
            if ((i < size || mainOwnPiece) && cubeOBB.isSolid()) {
                int o = boxCount * 6;
                deckBoxes[o] = center.x - rx;
                deckBoxes[o + 1] = center.y - ry;
                deckBoxes[o + 2] = center.z - rz;
                deckBoxes[o + 3] = center.x + rx;
                deckBoxes[o + 4] = center.y + ry;
                deckBoxes[o + 5] = center.z + rz;
                int f = boxCount * DeckSnapshot.FRAME;
                Quaternionf local = cubeOBB.localRotation();
                frames[f] = center.x;
                frames[f + 1] = center.y;
                frames[f + 2] = center.z;
                frames[f + 3] = extents.x;
                frames[f + 4] = extents.y;
                frames[f + 5] = extents.z;
                frames[f + 6] = local.x;
                frames[f + 7] = local.y;
                frames[f + 8] = local.z;
                frames[f + 9] = local.w;
                shapes[boxCount] = cubeOBB.hull();
                landing[boxCount] = cubeOBB.isDeck();
                if (cubeOBB.isDeck() && landingCount < deckCubes) {
                    System.arraycopy(deckBoxes, o, landingBoxes, landingCount * 6, 6);
                    landingCount++;
                }
                boxCount++;
            }
            if (center.x - rx < minX) minX = center.x - rx;
            if (center.y - ry < minY) minY = center.y - ry;
            if (center.z - rz < minZ) minZ = center.z - rz;
            if (center.x + rx > maxX) maxX = center.x + rx;
            if (center.y + ry > maxY) maxY = center.y + ry;
            if (center.z + rz > maxZ) maxZ = center.z + rz;
        }
        if (hits != null) {
            double[] bounds = {minX, minY, minZ, maxX, maxY, maxZ};
            hits.extendBounds(bounds);
            minX = bounds[0]; minY = bounds[1]; minZ = bounds[2];
            maxX = bounds[3]; maxY = bounds[4]; maxZ = bounds[5];
        }
        localMinX = minX; localMinY = minY; localMinZ = minZ;
        localMaxX = maxX; localMaxY = maxY; localMaxZ = maxZ;
        localBoundsValid = minX <= maxX;
        // Off the poses just written, and before any solve reads them. Relative geometry only
        // changes when a bone moves, which is once a tick, here.
        collisionBody.refresh(mainCubeOBB, allCubeOBBs, cubeDetached);
        deckBoxCount = boxCount;
        deckLandingCount = landingCount;
        publishOBBs(deck, vehicleRotation, boxCount, landingCount);
    }

    private void publishOBBs(DeckSnapshot deck, Quaternionf vehicleRotation, int boxCount, int landingCount) {
        setBoundingBox(makeBoundingBox());

        deck.set(vehicleRotation, getYRot(),
                getX() + centerOffset.x, getY() + centerOffset.y, getZ() + centerOffset.z,
                boxCount, landingCount);
        deckSnapshot = deck;
        deckSnapshotSlot ^= 1;
        boolean isCarrier = landingCount > 0;
        if (isCarrier != carrierRegistered) {
            CarrierDecks.setRegistered(this, isCarrier);
            carrierRegistered = isCarrier;
        }
        boolean oversized = OversizedVehicles.qualifies(this);
        if (oversized != oversizedRegistered) {
            OversizedVehicles.setRegistered(this, oversized);
            oversizedRegistered = oversized;
        }
    }

    private boolean oversizedRegistered;
    /**
     * Bumped when local geometry (cube index, detached flags, group transforms, hit bounds) changes; a deck slot
     * written at the current version holds current local boxes => {@link #updateOBBs} poses only.
     */
    private int localVersion;
    private final int[] deckSlotVersion = {-1, -1};
    private int deckBoxCount, deckLandingCount;

    private void translateOBBs(double dx, double dy, double dz) {
        if (mainCubeOBB == null) {
            return;
        }
        if (allCubeOBBs == null) {
            updateOBBs();
            return;
        }
        float fx = (float) dx;
        float fy = (float) dy;
        float fz = (float) dz;
        for (int i = 0, size = allCubeOBBs.size(); i <= size; i++) {
            VehicleCubeOBB cubeOBB = i == size ? mainCubeOBB : allCubeOBBs.get(i);
            cubeOBB.translate(fx, fy, fz, dx, dy, dz);
        }
    }

    private void buildOBBIndex() {
        List<VehicleCubeOBB> cubes = new ArrayList<>(vehicleCubeOBBs);
        for (PartUnit<?> partUnit : partUnits) {
            cubes.addAll(partUnit.getPartCubeOBBs());
        }
        allCubeOBBs = List.copyOf(cubes);
        cubePartIndex = new int[cubes.size()];
        cubeDetached = new boolean[cubes.size()];
        Arrays.fill(cubePartIndex, -1);
        int cubeCursor = vehicleCubeOBBs.size();
        for (int p = 0, parts = partUnits.size(); p < parts; p++) {
            for (int c = partUnits.get(p).getPartCubeOBBs().size(); c > 0; c--) {
                cubePartIndex[cubeCursor++] = p;
            }
        }
        int declaredDeck = 0;
        for (int i = 0, size = cubes.size(); i < size; i++) {
            if (cubes.get(i).isDeck()) {
                declaredDeck++;
            }
        }
        mainOwnPiece = !cubes.contains(mainCubeOBB);
        deckCubes = declaredDeck + (mainOwnPiece && mainCubeOBB.isDeck() ? 1 : 0);

        List<OBB> obbs = new ArrayList<>(cubes.size());
        for (VehicleCubeOBB cubeOBB : cubes) {
            obbs.add(cubeOBB.obb());
        }
        cachedOBBs = Collections.unmodifiableList(obbs);
        activeOBBs = cachedOBBs;

        List<VehicleCubeGroup> order = new ArrayList<>();
        Map<VehicleCubeGroup, Integer> indexOf = new IdentityHashMap<>();
        order.add(null);
        for (int i = 0, size = cubes.size(); i <= size; i++) {
            VehicleCubeOBB cubeOBB = i == size ? mainCubeOBB : cubes.get(i);
            indexGroupChain(cubeOBB.group, order, indexOf);
        }
        if (hits != null) {
            for (VehicleCubeGroup group : hits.groups()) {
                indexGroupChain(group, order, indexOf);
            }
        }

        groupOrder = order.toArray(new VehicleCubeGroup[0]);
        groupParent = new int[groupOrder.length];
        groupOffset = new Vector3f[groupOrder.length];
        groupRotation = new Quaternionf[groupOrder.length];
        for (int i = 0; i < groupOrder.length; i++) {
            VehicleCubeGroup group = groupOrder[i];
            groupParent[i] = group == null || group.parent == null ? -1 : indexOf.get(group.parent);
            groupOffset[i] = new Vector3f();
            groupRotation[i] = new Quaternionf();
        }

        cubeGroupIndex = new int[cubes.size() + 1];
        for (int i = 0, size = cubes.size(); i <= size; i++) {
            VehicleCubeOBB cubeOBB = i == size ? mainCubeOBB : cubes.get(i);
            cubeGroupIndex[i] = cubeOBB.group == null ? 0 : indexOf.get(cubeOBB.group);
        }
        if (hits != null) {
            hits.index(indexOf);
        }
    }

    private static void indexGroupChain(VehicleCubeGroup group, List<VehicleCubeGroup> order, Map<VehicleCubeGroup, Integer> indexOf) {
        if (group == null || indexOf.containsKey(group)) {
            return;
        }
        indexGroupChain(group.parent, order, indexOf);
        indexOf.put(group, order.size());
        order.add(group);
    }

    /** @return a group's offset or rotation changed (NaN => changed). */
    private boolean refreshGroupTransforms() {
        boolean changed = false;
        for (int i = 0; i < groupOrder.length; i++) {
            VehicleCubeGroup group = groupOrder[i];
            if (group == null) {
                continue;
            }
            Vector3f offset = groupOffset[i];
            Quaternionf rotation = groupRotation[i];
            float ox = offset.x, oy = offset.y, oz = offset.z, rx = rotation.x, ry = rotation.y, rz = rotation.z, rw = rotation.w;
            offset.set((float) group.pivot.x, (float) group.pivot.y, (float) group.pivot.z);
            int parent = groupParent[i];
            if (parent < 0) {
                rotation.set(group.rotation);
            } else {
                groupRotation[parent].transform(offset);
                offset.add(groupOffset[parent]);
                groupRotation[parent].mul(group.rotation, rotation);
            }
            changed |= !(ox == offset.x && oy == offset.y && oz == offset.z && rx == rotation.x && ry == rotation.y
                    && rz == rotation.z && rw == rotation.w);
        }
        return changed;
    }

    @Override
    protected AABB makeBoundingBox() {
        if (remote || !dataInitialized || !localBoundsValid) {
            return AABB.ofSize(position(), 1, 1, 1);
        }
        Quaternionf rotation = rotYXZShared();
        Vector3f corner = new Vector3f();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            corner.set(
                    (float) ((i & 1) == 0 ? localMinX : localMaxX),
                    (float) ((i & 2) == 0 ? localMinY : localMaxY),
                    (float) ((i & 4) == 0 ? localMinZ : localMaxZ));
            rotation.transform(corner);
            if (corner.x < minX) minX = corner.x;
            if (corner.y < minY) minY = corner.y;
            if (corner.z < minZ) minZ = corner.z;
            if (corner.x > maxX) maxX = corner.x;
            if (corner.y > maxY) maxY = corner.y;
            if (corner.z > maxZ) maxZ = corner.z;
        }
        double originX = getX() + centerOffset.x;
        double originY = getY() + centerOffset.y;
        double originZ = getZ() + centerOffset.z;
        return new AABB(
                minX + originX, minY + originY, minZ + originZ,
                maxX + originX, maxY + originY, maxZ + originZ);
    }

    @Override
    protected void addPassenger(Entity pPassenger) {
        if (pPassenger instanceof LivingEntity livingEntity) {
            onEnterVehicle(livingEntity);
            super.addPassenger(pPassenger);
        }
    }

    @Override
    protected void removePassenger(Entity pPassenger) {
        if (pPassenger instanceof LivingEntity livingEntity) {
            Vec3 dismountLocation = null;
            PartUnit<?> partUnit = getOwnOperatorUnit(livingEntity);
            if (partUnit != null) {
                dismountLocation = partUnit.worldDismountPosition();
            }
            if (dismountLocation == null) {
                DoorUnit doorUnit = getNearestDoorUnit(livingEntity);
                if (doorUnit != null) {
                    dismountLocation = doorUnit.worldDismountPosition();
                    if (dismountLocation == null) {
                        dismountLocation = doorUnit.worldPosition(doorUnit.getPivotOffset()).subtract(0, pPassenger.getEyeHeight() / 2, 0);
                    }
                }
            }
            if (dismountLocation == null) {
                dismountLocation = relativeRotPos(position().add(mainCubeOBB.obb().extents().x + 1, 1, partUnit != null ? partUnit.getSeatOffset().z : 0), false);
            }
            dismountLocations.put(livingEntity, dismountLocation);
            super.removePassenger(pPassenger);
            onLeaveVehicle(livingEntity);
        }
    }

    /** UAVs only. */
    @Override
    @Nullable
    public com.wf.wflib.telemetry.Telemetry datalink() {
        if (!uav || isDestroyed() || drone != null && drone.orders().isClassified()) {
            return null;
        }
        return new com.wf.wflib.telemetry.Telemetry(com.wf.wflib.telemetry.TelemetryKind.DRONE,
                getVehicleId().getPath(), uavSide(), uavOperator);
    }

    public List<LoadoutInfo> loadouts() {
        return loadouts;
    }

    public int loadoutIndex() {
        return entityData.get(LOADOUT);
    }

    @Nullable
    public LoadoutInfo loadout() {
        int index = loadoutIndex();
        return index >= 0 && index < loadouts.size() ? loadouts.get(index) : null;
    }

    /** Raw state; {@link Loadouts#select} moves hardware. */
    public void setLoadout(int index) {
        entityData.set(LOADOUT, index);
    }

    /** Server: remote pilot's link from their parked body to the camera seat. */
    private void tickLink() {
        float link = 1f;
        Entity driver = getDriver();
        if (uav && driver != null && !seats.isEmpty()) {
            UavCamera camera = new UavCamera(this);
            link = Datalink.quality((ServerLevel) level(), camera.spec(), Datalink.operatorAt(driver), this,
                    camera.eye());
        }
        if (Math.abs(link - entityData.get(LINK)) >= LINK_QUANTUM || link == 1f && entityData.get(LINK) != 1f) {
            entityData.set(LINK, link);
        }
    }

    public float link() {
        return entityData.get(LINK);
    }

    /** Remote pilot out of reach: their inputs are dropped; {@link VehicleDrone} holds. */
    public boolean linkLost() {
        return uav && !Datalink.usable(entityData.get(LINK));
    }

    @Override
    public double laserMassKg() {
        return physicsEngine.mass;
    }

    @Override
    public double laserArmorMm(Vec3 beamDirection) {
        return armorProfile == null ? 0
                : armorProfile.nominal(ArmorFace.ofWorld(this, beamDirection.scale(-1).toVector3f()));
    }

    @Override
    public double laserResistance() {
        return defenseStats.laserResistance;
    }

    /** The vehicle's team, else its operator's WarForge faction. */
    @Nullable
    public UUID uavSide() {
        UUID team = com.wf.wflib.recon.ReconOwners.owningEntity(this);
        if (team == null && uavOperator != null) {
            team = com.wf.wflib.compat.WarforgeCompat.factionOfPlayer(uavOperator);
        }
        return team;
    }

    /** UAVs only: air defence engages unmanned bodies. */
    @Override
    public boolean interceptEngageable() {
        return uav && !isDestroyed() && !isRemoved();
    }

    @Override
    public com.wf.wflib.recon.ContactClass interceptClass() {
        return uav ? com.wf.wflib.recon.ContactClass.DRONE : com.wf.wflib.recon.ContactClass.VEHICLE;
    }

    @Override
    @Nullable
    public UUID interceptControlId() {
        return null;
    }

    @Override
    @Nullable
    public UUID interceptTeamId() {
        return uavSide();
    }

    /** As a blast at the hull: positioned, no armour-penetration answer. */
    @Override
    public void interceptDamage(float amount) {
        hurt(AllDamageTypes.Sources.explosion(registryAccess(), null, null, position()), amount);
    }

    @Override
    public void interceptKill() {
        if (!isDestroyed()) {
            interceptDamage(getMaxHealth() + defenseStats.damageThreshold);
        }
        if (!isDestroyed() && !isRemoved()) {
            getPassengers().forEach(Entity::stopRiding);
            setDestroyed();
        }
    }

    /** Boards {@code seatIndex} (free) instead of the default pick. */
    public boolean board(LivingEntity rider, int seatIndex) {
        if (seatIndex < 0 || seatIndex >= seats.size() || seats.get(seatIndex).passengerId != -1) {
            return false;
        }
        requestedSeat = seatIndex;
        try {
            return rider.startRiding(this);
        } finally {
            requestedSeat = -1;
        }
    }

    public void onEnterVehicle(LivingEntity livingEntity) {
        if (!level().isClientSide()) {
            // UAV ride is always remote (interact() refuses by-hand boarding): record real position
            // before tick 1, or a freshly deployed drone has no anchor to return them to.
            if (uav && livingEntity instanceof ServerPlayer serverPlayer) {
                setDetachedBodyAnchor(serverPlayer, serverPlayer.position());
                uavOperator = serverPlayer.getUUID();
            }
            ServerLevel serverLevel = (ServerLevel) level();
            Seat targetSeat = requestedSeat >= 0 ? seats.get(requestedSeat) : null;
            if (targetSeat == null && drivingSeat() == null) {
                // First aboard takes the wheel, not the gun that happens to be seat 0 (Centauro: turret).
                targetSeat = seats.stream()
                        .filter(seat -> seat.passengerId == -1 && CrewRules.roles(seat).contains(CrewRole.DRIVER))
                        .findFirst().orElse(null);
            }
            if (targetSeat == null) {
                Optional<Seat> emptySeatOptional = seats.stream().filter(seat -> seat.passengerId == -1).findFirst();
                if (emptySeatOptional.isEmpty()) {
                    return;
                }
                targetSeat = emptySeatOptional.get();
            }
            targetSeat.partUnit.setOwner(livingEntity);
            targetSeat.passengerId = livingEntity.getId();
            if (CrewRules.drives(this, targetSeat)) {
                partUnits.forEach(partUnit -> {
                    if (partUnit instanceof DoorUnit doorUnit && (cargoHold == null || cargoHold.mayClose(doorUnit, null))) {
                        doorUnit.setOn(false);
                    }
                });
            }
            refreshDriver();
            for (ServerPlayer serverPlayer : serverLevel.players()) {
                PacketDistributor.sendToPlayer(serverPlayer, new ServerVehicleSeatsChange(this));
            }
        }
        livingEntity.setSprinting(false);
    }

    public void onLeaveVehicle(LivingEntity pPassenger) {
        if (!level().isClientSide()) {
            Optional<Seat> ownSeat = seats.stream().filter(seat -> seat.passengerId == pPassenger.getId()).findFirst();
            if (!ownSeat.isPresent()) {
                return;
            }
            Seat seat = ownSeat.get();
            seat.partUnit.setOwner(null);
            seat.passengerId = -1;
            refreshDriver();
            PacketDistributor.sendToPlayersTrackingEntity(this, new ServerVehicleSeatsChange(this));
        } else {
            onClientLeaveVehicle(pPassenger);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void onClientLeaveVehicle(LivingEntity pPassenger) {
        LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
        if (pPassenger == instance.getPlayer() && instance.toLeave) {
            instance.toSeat(null, this);
            if (warningReceiver != null) {
                warningReceiver.clear();
            }
        }
    }

    public boolean changeSeat(LivingEntity pPassenger, int toSeatIndex) {
        if (toSeatIndex >= 0 && toSeatIndex < seats.size() && seats.get(toSeatIndex).passengerId == -1) {
            Optional<Seat> ownSeat = seats.stream().filter(seat -> seat.passengerId == pPassenger.getId()).findFirst();
            if (ownSeat.isPresent()) {
                Seat seat = ownSeat.get();
                seat.partUnit.setOwner(null);
                seat.passengerId = -1;
            }
            Seat toSeat = seats.get(toSeatIndex);
            toSeat.partUnit.setOwner(pPassenger);
            toSeat.passengerId = pPassenger.getId();
            refreshDriver();
            toSeat.partUnit.applySeatRot(pPassenger);
            PacketDistributor.sendToPlayersTrackingEntity(this, new ServerVehicleSeatsChange(this));
            return true;
        }
        return false;
    }

    @OnlyIn(Dist.CLIENT)
    private void setSeats(int[] ids) {
        LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
        Player player = instance.getPlayer();
        List<Integer> passengerIdsBySeat = new ArrayList<>();
        for (int id : ids) {
            passengerIdsBySeat.add(id);
        }
        if (seats.stream().anyMatch(seat -> seat.passengerId == player.getId())
                && !passengerIdsBySeat.contains(player.getId())) {
            instance.toLeave = true;
        }
        for (int index = 0; index < passengerIdsBySeat.size(); index += 1) {
            Seat seat = seats.get(index);
            Integer id = passengerIdsBySeat.get(index);
            if (id != -1) {
                seat.partUnit.setOwnerId(id);
                seat.passengerId = id;
                if (seat.passengerId == player.getId()) {
                    instance.toSeat(seat, this);
                }
            } else {
                seat.partUnit.setOwner(null);
                seat.passengerId = -1;
            }
        }
        Seat driving = drivingSeat();
        if (driving != null) {
            controlUnit.setOperatorId(driving.passengerId);
        } else {
            controlUnit.setOperator(null);
        }
    }

    @Nullable
    private Seat drivingSeat() {
        for (Seat seat : seats) {
            if (seat.passengerId != -1 && CrewRules.drives(this, seat)) {
                return seat;
            }
        }
        return null;
    }

    /** Server: operator = occupant of {@link #drivingSeat()}. */
    private void refreshDriver() {
        Seat driving = drivingSeat();
        LivingEntity driver = driving != null && level().getEntity(driving.passengerId) instanceof LivingEntity living
                ? living : null;
        if (driver != controlUnit.getOperator()) {
            controlUnit.setOperator(driver);
            if (driver != null) {
                toggleEngine(true);
            }
        }
    }

    public void onClientVehicleChangeSeat(ClientVehicleChangeSeat message, Player player) {
        changeSeat(player, message.toSeat);
    }

    public void onClientVehicleAction(ClientVehicleAction message, Player player) {
        if (player != null && player.level().getEntity(message.vehicleEntityId) instanceof AbstractVehicle vehicle) {
            if (message.leaveVehicle) {
                player.stopRiding();
            } else if (message.toggleEngine) {
                vehicle.toggleEngine(null);
            } else if (message.lockEntity) {
                PartUnit<?> partUnit = vehicle.getOwnOperatorUnit(player);
                if (partUnit instanceof WeaponUnit weaponUnit && CrewRules.mayOperate(player, weaponUnit)) {
                    weaponUnit.setLockedEntity(SimTargets.resolve((ServerPlayer) player, message.lockedEntityId));
                }
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void onServerVehicleSeatsChange(ServerVehicleSeatsChange message) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level.getEntity(message.vehicleEntityId) instanceof AbstractVehicle vehicle) {
            vehicle.setSeats(message.passengerIdsBySeat);
        }
    }

    @Override
    protected boolean canAddPassenger(@NotNull Entity pPassenger) {
        return seats.stream().anyMatch(seat -> seat.passengerId == -1);
    }

    @Override
    public InteractionResult interact(Player pPlayer, InteractionHand pHand) {
        // Client PASS => the held item's own use runs too (a creative WFLib missile launches at the vehicle).
        if (this.level().isClientSide() && pHand == InteractionHand.MAIN_HAND
                && (MissileHolderUnit.couldLoad(this, pPlayer.getItemInHand(pHand))
                || MissileHolderUnit.podHolder(this, pPlayer.getItemInHand(pHand)) != null)) {
            return InteractionResult.SUCCESS;
        }
        if (uav) {
            // Never boarded by hand; still armed by hand.
            if (!this.level().isClientSide() && pHand == InteractionHand.MAIN_HAND
                    && (MissileHolderUnit.tryLoad(this, pPlayer.getItemInHand(pHand))
                    || Loadouts.strap(pPlayer, this, pPlayer.getItemInHand(pHand)))) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }
        if (!this.level().isClientSide()) {
            if (isDestroyed()) {
                this.openCustomInventoryScreen(pPlayer);
                return InteractionResult.SUCCESS;
            }
            if (pHand == InteractionHand.MAIN_HAND) {
                ItemStack itemStack = pPlayer.getItemInHand(pHand);
                if (itemStack.getItem() instanceof VehicleItem) {
                    return InteractionResult.PASS;
                }
                if (pPlayer.isShiftKeyDown() && itemStack.isEmpty()) {
                    // A mounted vehicle answers this gesture by coming off, because that is the only
                    // way to get it off, and its hold is reachable again the moment it is free.
                    if (VehicleMounts.unmount(this)) {
                        return InteractionResult.SUCCESS;
                    }
                    // Otherwise it opens the hold. Deliberately narrow: the same click without
                    // sneaking still boards, which is the gesture every existing vehicle expects.
                    if (hasContainer()) {
                        this.openCustomInventoryScreen(pPlayer);
                        return InteractionResult.SUCCESS;
                    }
                }
                if (itemStack.getItem() instanceof VehicleSpawnItem
                        && VehicleMounts.place(this, pPlayer, itemStack)) {
                    return InteractionResult.SUCCESS;
                }
                if (CargoParachuteItem.tryFit(this, pPlayer, itemStack)) {
                    return InteractionResult.SUCCESS;
                }
                if (VehicleRefuelInteraction.handle(this, pPlayer, pHand, itemStack)) {
                    return InteractionResult.SUCCESS;
                }
                if (MissileHolderUnit.trySeatPod(this, pPlayer, itemStack) || MissileHolderUnit.tryLoad(this, itemStack)) {
                    return InteractionResult.SUCCESS;
                }
                if (pPlayer.startRiding(this)) {
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @OnlyIn(Dist.CLIENT)
    public Vec3 thirdPersonPosition(float partialTick) {
        Vec3 offset = isViewZoomed() ? getViewInfo().thirdPersonCenterOffsetZoomed : getViewInfo().thirdPersonCenterOffset;
        Matrix3f axisRollMat = new Matrix3f();
        Quaternionf q = new Quaternionf();
        float vehicleYRot = partialTick == 1.0F ? this.getYRot() : this.getViewYRot(partialTick);
        q.rotateY(Math.toRadians(-vehicleYRot));
        q.get(axisRollMat);
        Vector3f rotPos = axisRollMat.transform(offset.toVector3f());
        return position(partialTick).add(new Vec3(rotPos.x, rotPos.y, rotPos.z));
    }

    @OnlyIn(Dist.CLIENT)
    public double thirdPersonDistance(float cameraXRot) {
        double distance = isViewZoomed()
                ? getViewInfo().thirdPersonDistanceZoomed
                : getViewInfo().thirdPersonDistance;
        if (!isViewZoomed()) {
            distance = Math.max(0, distance - cameraXRot / 90 * getViewInfo().thirdPersonCenterOffset.y);
        }
        return distance;
    }

    @NotNull
    @Override
    public Vec3 getDismountLocationForPassenger(@NotNull LivingEntity pPassenger) {
        Vec3 anchor = anchors.release(pPassenger);
        if (anchor != null) {
            return anchor;
        }
        Vec3 at = dismountLocations.getOrDefault(pPassenger, super.getDismountLocationForPassenger(pPassenger));
        // Low door pivot - eye/2, slope => feet in terrain: lift <= 3 m to first block-free stance.
        AABB box = pPassenger.getDimensions(Pose.STANDING).makeBoundingBox(at);
        for (int i = 0; i <= 12; i++) {
            if (level().noBlockCollision(pPassenger, box.move(0, i * 0.25, 0))) {
                return at.add(0, i * 0.25, 0);
            }
        }
        return at;
    }

    /** Remote operator (ride-inventory key from a UAV) => refused; storage is reached on foot (hull node). */
    @Override
    public void openCustomInventoryScreen(@NotNull Player player) {
        if (getDetachedBodyAnchor(player) == null) {
            super.openCustomInventoryScreen(player);
        }
    }

    /** UAV riders are remote (parked body at its anchor): stand. */
    @Override
    public boolean shouldRiderSit() {
        return !uav && super.shouldRiderSit();
    }

    @Override
    public boolean isDetachedBodyActive() {
        return this.uav && !anchors.isEmpty();
    }

    @Nullable
    @Override
    public Vec3 getDetachedBodyAnchor(Entity operator) {
        return operator == null ? null : anchors.get(operator);
    }

    @Override
    public void setDetachedBodyAnchor(Entity operator, @Nullable Vec3 anchor) {
        if (operator != null) {
            anchors.set(operator, anchor);
        }
    }

    @Override
    public void clearDetachedBodyAnchors() {
        anchors.clear();
    }

    @Override
    public Collection<Entity> getDetachedOperators() {
        return anchors.operators();
    }

    @Override
    protected void positionRider(@NotNull Entity pPassenger, Entity.MoveFunction pCallback) {
        Vec3 anchor = getDetachedBodyAnchor(pPassenger);
        if (anchor != null) {
            pCallback.accept(pPassenger, anchor.x, anchor.y, anchor.z);
            return;
        }
        if (!(pPassenger instanceof LivingEntity living)) {
            super.positionRider(pPassenger, pCallback);
            return;
        }
        PartUnit<?> partUnit = getOwnOperatorUnit(living);
        if (partUnit != null) {
            Vec3 pos = partUnit.worldSeatPosition();
            pCallback.accept(pPassenger, pos.x, pos.y, pos.z);
        } else {
            super.positionRider(pPassenger, pCallback);
        }
    }

    public LivingEntity getDriver() {
        return controlUnit.getOperator();
    }

    @Override
    public LivingEntity getControllingPassenger() {
        if (level().isClientSide()) {
            return null;
        } else {
            return getDriver();
        }
    }

    public PartUnit<?> getOwnOperatorUnit(LivingEntity pPassenger) {
        if (pPassenger == null) {
            return null;
        }
        int id = pPassenger.getId();
        for (int i = 0; i < seats.size(); i++) {
            Seat seat = seats.get(i);
            if (seat.passengerId == id) {
                return seat.partUnit;
            }
        }
        return null;
    }

    public List<PartUnit<?>> getPartUnits() {
        return partUnits;
    }

    public Optional<PartUnit<?>> getPartUnit(int index) {
        if (index >= 0 && index < partUnits.size()) {
            return Optional.of(partUnits.get(index));
        }
        return Optional.empty();
    }

    public DoorUnit getNearestDoorUnit(LivingEntity livingEntity) {
        PartUnit<?> seatUnit = getOwnOperatorUnit(livingEntity);
        double minDistance = Double.MAX_VALUE;
        DoorUnit nearestDoorUnit = null;
        for (PartUnit<?> partUnit : getPartUnits()) {
            if (partUnit instanceof DoorUnit doorUnit) {
                if (doorUnit.getSeatUnitOfDoor() != null && doorUnit.getSeatUnitOfDoor() == seatUnit) {
                    return doorUnit;
                }
                double distance = partUnit.worldPosition(partUnit.getPivotOffset()).distanceTo(livingEntity.position());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestDoorUnit = doorUnit;
                }
            }
        }
        return nearestDoorUnit;
    }

    public Optional<PartUnit<?>> getPartUnit(String id) {
        return Optional.ofNullable(partUnitMap.get(id));
    }

    public CamoLayer getCamoLayer() {
        return camoLayer;
    }

    public AddonArmorLayer addonArmor() {
        return addonArmor;
    }

    /**
     * Cheaper than the dirty-flag full resync next tick; the only reason to call it. A caller that
     * just mutates the layer and returns is still correct, just pays for the whole layer.
     */
    public void broadcastCamo(CamoAction action) {
        if (level().isClientSide()) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntity(this, new ServerCamoAction(action));
        camoLayer.markClean();
    }

    public List<SuspensionUnit<?>> getSuspensionUnits() {
        return suspensionUnits;
    }

    public boolean hasSuspension() {
        return !suspensionUnits.isEmpty();
    }

    /** A suspension unit attached and intact. All gone => rigid hull: belly carries it with Coulomb drag. */
    public boolean hasRunningGear() {
        for (int i = 0, size = suspensionUnits.size(); i < size; i++) {
            if (suspensionUnits.get(i).isActive()) {
                return true;
            }
        }
        return false;
    }

    public Map<String, DecorationUnit> getDecorationUnits() {
        return decorationUnits;
    }

    public HashSet<BulletHoleParticle> getBulletHoleParticles() {
        return bulletHoleParticles;
    }

    public List<VehicleCubeOBB> getVehicleCubeOBBs() {
        return vehicleCubeOBBs;
    }

    /** m/s of contact velocity change below which an impact does no damage. */
    public float impactSpeedThreshold() {
        if (defenseStats.impactSpeedThreshold != null) {
            return defenseStats.impactSpeedThreshold;
        }
        return this instanceof FixedWingVehicle || this instanceof RotaryWingVehicle ? 5 : 10;
    }

    /** Null: no clip drives a part. */
    @Nullable
    public PartClipTracks clipTracks() {
        return clipTracks;
    }

    /** Hit geometry from the display model, or null: structure boxes are the hit surface. */
    @Nullable
    public VehicleHits hits() {
        return hits;
    }

    /** Pose of indexed group {@code index} as of the last {@link #updateOBBs}: vehicle frame. */
    public Quaternionf groupRotation(int index) {
        return groupRotation[index];
    }

    public Vector3f groupOffset(int index) {
        return groupOffset[index];
    }

    public VehicleCubeOBB getMainCubeOBB() {
        return mainCubeOBB;
    }

    public double getStructureLength() {
        return structureLength;
    }

    public float getXRot() {
        return this.xRot;
    }

    public void setXRot(float rot) {
        if (!level().isClientSide()) {
            this.entityData.set(X_ROT, rot, true);
        }
        this.xRot = rot;
    }

    public float getYRot() {
        return this.yRot;
    }

    public void setYRot(float rot) {
        if (!level().isClientSide()) {
            this.entityData.set(Y_ROT, rot, true);
        }
        this.yRot = rot;
    }

    public float getZRot() {
        return this.zRot;
    }

    public void setZRot(float rot) {
        this.zRot = rot;
        if (!level().isClientSide()) {
            this.entityData.set(Z_ROT, this.zRot, true);
        }
    }

    public float getViewXRot(float pPartialTicks) {
        return pPartialTicks == 1.0F ? this.getXRot() : Mth.lerp(pPartialTicks, this.xRotO, this.getXRot());
    }

    public float getViewYRot(float pPartialTicks) {
        float dYRot = yRot - yRotO;
        if (Math.abs(dYRot) > 180) {
            yRotO += Math.signum(dYRot) * 360;
        }
        return pPartialTicks == 1.0F ? this.getYRot() : Mth.lerp(pPartialTicks, this.yRotO, this.getYRot());
    }

    public float getViewZRot(float pPartialTicks) {
        return pPartialTicks == 1.0F ? this.getZRot() : Mth.lerp(pPartialTicks, this.zRotO, this.getZRot());
    }

    public Quaternionf rotYXZ(float partialTick) {
        if (partialTick == 1.0F) {
            return rotYXZ();
        }
        Quaternionf previousRotation = new Quaternionf()
                .rotateY(Math.toRadians(-yRotO))
                .rotateX(Math.toRadians(xRotO))
                .rotateZ(Math.toRadians(zRotO));
        return previousRotation.slerp(rotYXZ(), partialTick);
    }

    public Quaternionf rotYXZ() {
        return new Quaternionf(rotYXZShared());
    }

    /** The composed rotation without the defensive copy. Read-only; shared until a rotation field changes. */
    Quaternionf rotYXZShared() {
        if (yRot != cachedRotYXZYaw || xRot != cachedRotYXZPitch || zRot != cachedRotYXZRoll) {
            cachedRotYXZ.identity()
                    .rotateY(Math.toRadians(-yRot))
                    .rotateX(Math.toRadians(xRot))
                    .rotateZ(Math.toRadians(zRot));
            cachedRotYXZYaw = yRot;
            cachedRotYXZPitch = xRot;
            cachedRotYXZRoll = zRot;
        }
        return cachedRotYXZ;
    }

    public Vec3 position(float partialTick) {
        if (partialTick == 1.0F) {
            return position();
        }
        return new Vec3(Mth.lerp(partialTick, xo, getX()),
                Mth.lerp(partialTick, yo, getY()),
                Mth.lerp(partialTick, zo, getZ()));
    }

    public Vector3f[] axes() {
        Vector3f[] axes = new Vector3f[]{
                new Vector3f(1, 0, 0),
                new Vector3f(0, 1, 0),
                new Vector3f(0, 0, 1)};
        Quaternionf rotation = rotYXZ();
        rotation.transform(axes[0]);
        rotation.transform(axes[1]);
        rotation.transform(axes[2]);
        return axes;
    }

    public Vec3 relativeRotPos(Vec3 worldPos, boolean reverse) {
        Vec3 center = position().add(centerOffset);
        return relativeRotDirection(worldPos.subtract(center), reverse).add(center);
    }

    public Vec3 relativeRotDirection(Vec3 worldDirection, boolean reverse) {
        Quaternionf q = rotYXZ();
        Matrix3f axisRollMat = new Matrix3f();
        q.get(axisRollMat);
        if (reverse) {
            axisRollMat = axisRollMat.transpose();
        }
        return new Vec3(axisRollMat.transform(worldDirection.toVector3f()));
    }

    public abstract void shoot(int partUnitIndex, int weaponIndex, List<AimContext> aimContexts, @Nullable LivingEntity operator);

    public float getEnergy() {
        float amount = entityData.get(ENERGY);
        if (amount == 0 && AllConfigs.common.infiniteFuel.get()) {
            amount = Float.MIN_VALUE;
        }
        return amount;
    }

    public void setEnergy(float amount) {
        entityData.set(ENERGY, amount);
        // Anything crediting energy without naming a fluid (command, creative, old save) still
        // gets a tank holding something: the no-mixing rule needs a subject.
        if (amount > 0 && !level().isClientSide() && !isElectric() && fuelId().isEmpty()) {
            setFuelFluid(fuelSpec().defaultFuel());
        }
        refreshMass();
    }

    public void refreshMass() {
        if (!level().isClientSide()) {
            entityData.set(CARGO_MASS, (float) (cargoMass.kilograms() * cargoMassPerKg)
                    + (cargoHold == null ? 0 : cargoHold.mass()));
        }
        physicsEngine.mass = unladenMass() + entityData.get(CARGO_MASS);
    }

    /** Curb + fuel (electric: full battery) + fitted parts. */
    public float unladenMass() {
        float stored = isElectric() ? energyInfo.capacityUnits() : getEnergy();
        return curbWeight + stored + entityData.get(PART_MASS);
    }

    /** Physics kg carried: hold items + stowed vehicles. */
    public float cargoMass() {
        return entityData.get(CARGO_MASS);
    }

    /** mass / unladen, >= 1: divides kinematic accelerations and turn rates declared unladen. */
    public double load() {
        return 1 + cargoMass() / Math.max(1.0E-3, unladenMass());
    }

    /** Cargo / {@link #maxPayload}; 0 without a limit. */
    public double overload() {
        return maxPayload > 0 ? cargoMass() / maxPayload : 0;
    }

    /** Engine output share: 1 up to {@link #maxPayload}, then 1 / overload^2. Sites keep their own power rounding x this:
     * terrain_humvee_hill flips on a 1-ULP power change. */
    public double strain() {
        double over = overload();
        return over > 1 ? 1 / (over * over) : 1;
    }

    @Nullable
    public VehicleMounts.Pending pendingMount() {
        return pendingMount;
    }

    public void setPendingMount(@Nullable VehicleMounts.Pending pending) {
        this.pendingMount = pending;
    }

    /** Kilograms in the hold as of the last recount. */
    public double cargoKilograms() {
        return cargoMass.kilograms();
    }

    @Override
    protected void onContentsChanged() {
        cargoMass.markDirty();
    }

    public float addEnergy(float amount) {
        float fuel = rawEnergy();
        float space = energyInfo.capacityUnits() - fuel;
        if (fuelId().isEmpty() && amount > 0) {
            setFuelFluid(fuelSpec().defaultFuel());
        }
        if (space > amount) {
            setEnergy(fuel + amount);
            return 0;
        } else {
            setEnergy(energyInfo.capacityUnits());
            return amount - space;
        }
    }

    /** Energy as stored, without the infinite-fuel config's stand-in for an empty tank. */
    public float rawEnergy() {
        return entityData.get(ENERGY);
    }

    public boolean isElectric() {
        return energyInfo.isElectric();
    }

    public boolean hasBatterySlot() {
        return energyInfo.batterySlot;
    }

    public ItemStack batteryItem() {
        return batteryItem;
    }

    public void setBatteryItem(ItemStack stack) {
        this.batteryItem = stack;
    }

    public void setEnergyInfo(EnergyInfo info) {
        this.energyInfo = info;
        this.fuelSpec = FuelSpec.of(info);
        this.cachedFuelId = null;
    }

    public FuelSpec fuelSpec() {
        if (fuelSpec == null) {
            fuelSpec = FuelSpec.of(energyInfo);
        }
        return fuelSpec;
    }

    public String fuelId() {
        return entityData.get(FUEL);
    }

    /** The fluid in the tank, or EMPTY. */
    public Fluid fuelFluid() {
        return FuelSpec.fluidOf(fuelId());
    }

    public void setFuelFluid(Fluid fluid) {
        ResourceLocation id = FuelSpec.idOf(fluid);
        String key = id == null ? "" : id.toString();
        if (!key.equals(fuelId())) {
            entityData.set(FUEL, key);
        }
    }

    /** Range multiplier of the fuel on board. Memoised, because it is read every tick. */
    public float fuelEfficiency() {
        String id = fuelId();
        if (!id.equals(cachedFuelId)) {
            cachedFuelId = id;
            cachedFuelEfficiency = fuelSpec().efficiencyOf(id);
        }
        return cachedFuelEfficiency;
    }

    /** Energy units burnt per tick at full power: fuel efficiency, x {@link #load}. */
    public float burnPerTick() {
        float efficiency = isElectric() ? 1f : fuelEfficiency();
        float burn = efficiency <= 0 ? energyInfo.energyConsumptionPerTick : energyInfo.energyConsumptionPerTick / efficiency;
        return (float) (burn * load());
    }

    public VehicleFuelTank fuelTank() {
        return fuelTank;
    }

    public VehicleEnergyBuffer energyBuffer() {
        return energyBuffer;
    }

    /**
     * Credits FE directly, ignoring the per-tick charge rate.
     * @return FE taken
     */
    public int addCharge(int fe) {
        if (!isElectric() || fe <= 0) {
            return 0;
        }
        int room = energyInfo.feCapacity() - energyBuffer.getEnergyStored();
        int accepted = java.lang.Math.min(fe, room);
        if (accepted <= 0) {
            return 0;
        }
        setEnergy(rawEnergy() + (float) accepted / energyInfo.fePerUnit());
        return accepted;
    }

    public ViewInfo getViewInfo() {
        return viewInfo;
    }

    public boolean isViewZoomed() {
        return viewZoomed;
    }

    public void toggleViewZoom() {
        viewZoomed = !viewZoomed;
    }

    public float getPower() {
        return entityData.get(POWER);
    }

    public void setPower(float power) {
        entityData.set(POWER, power);
    }

    public float getEngineSpeed() {
        return entityData.get(ENGINE_SPEED);
    }

    public void setEngineSpeed(float engineSpeed) {
        entityData.set(ENGINE_SPEED, engineSpeed);
    }

    public boolean hasPower() {
        return getPower() > 20;
    }

    public void toggleEngine(Boolean on) {
        boolean want = on == null ? !isEngineOn() : on;
        entityData.set(ENGINE_ON, want && VehicleSystems.engineWorks(this));
    }

    public boolean isEngineOn() {
        return entityData.get(ENGINE_ON);
    }

    /** Groups on; destroyed => none. */
    public int lightsOn() {
        return isDestroyed() ? 0 : entityData.get(LIGHTS);
    }

    public void toggleLights(VehicleLight.Kind kind) {
        entityData.set(LIGHTS, (byte) (entityData.get(LIGHTS) ^ kind.bit()));
    }

    /** Seats with the sight in IR; destroyed => none. */
    public int irSights() {
        return isDestroyed() ? 0 : entityData.get(IR_SIGHTS);
    }

    public void setIrSight(int seatIndex, boolean on) {
        irSightOwners.put(seatIndex, seats.get(seatIndex).passengerId.intValue());
        int mask = entityData.get(IR_SIGHTS);
        entityData.set(IR_SIGHTS, on ? mask | 1 << seatIndex : mask & ~(1 << seatIndex));
    }

    private void clearVacantIrSights() {
        int mask = entityData.get(IR_SIGHTS);
        for (int bits = mask; bits != 0; bits &= bits - 1) {
            int seat = Integer.numberOfTrailingZeros(bits);
            if (seats.get(seat).passengerId != irSightOwners.get(seat)) {
                mask &= ~(1 << seat);
            }
        }
        entityData.set(IR_SIGHTS, mask);
    }

    /** Strapped loudspeaker => SPEAKER; crewed non-UAV hull => CABIN; else none. */
    @Override
    @Nullable
    public RadioVoice radioVoice() {
        com.wf.combinedassault.fpv.payload.PayloadUnit hardpoint = com.wf.combinedassault.fpv.payload.PayloadUnit.on(this);
        if (hardpoint != null && hardpoint.payloadType() == com.wf.combinedassault.fpv.payload.FpvPayloadTypes.SPEAKER
                && hardpoint.isLoaded()) {
            return RadioVoice.SPEAKER;
        }
        return uav || seats.isEmpty() || isDestroyed() ? null : RadioVoice.CABIN;
    }

    @Override
    public String radioStation() {
        return entityData.get(RADIO);
    }

    @Override
    public void tuneRadio(String station) {
        if (!RadioCarrier.validStation(station)) {
            throw new IllegalArgumentException("radio station " + station);
        }
        entityData.set(RADIO, station);
    }

    public boolean isDestroyed() {
        return entityData.get(DESTROYED);
    }

    public void setDestroyed() {
        if (cargoHold != null) {
            cargoHold.releaseAll();
        }
        this.entityData.set(DESTROYED, true);
        this.setHealth(this.getMaxHealth());
        this.destroyedTick = 0;
    }

    @Override
    public Vec3 getLightProbePosition(float pPartialTicks) {
        if (mainCubeOBB == null) {
            return position();
        }
        return new Vec3(mainCubeOBB.obb().center());
    }

    @Nullable
    public UUID getSide() {
        return side;
    }

    public void setSide(@Nullable UUID side) {
        this.side = side;
    }

    /** Owner (claimed side, else crew team) unset, or {@code player}'s side allied to it. Server. */
    public boolean mayCommand(Player player) {
        UUID owner = com.wf.wflib.recon.ReconOwners.owningEntity(this);
        return owner == null || com.wf.wflib.compat.WarforgeCompat.areFactionsAllied(owner,
                com.wf.wflib.recon.ReconOwners.owningEntity(player));
    }

    @Override
    @Nullable
    public PlayerTeam getTeam() {
        if (!remote) {
            Entity driver = getDriver();
            if (driver == null) {
                return null;
            }
            return driver.getTeam();
        } else {
            return remoteTeam;
        }
    }

    @Override
    public void push(@NotNull Entity pEntity) {
        if (!collision) {
            return;
        }
        if (this.isPassengerOfSameVehicle(pEntity)) {
            return;
        }
        if (pEntity instanceof AbstractVehicle) {
            // RigidStep owns vehicle pairs.
            return;
        }
        OBB mainCubeOBB = getMainCubeOBB().obb();
        AABB entityAABB = pEntity.getBoundingBox();
        if (!OBB.isColliding(mainCubeOBB, entityAABB)) {
            return;
        }
        Vec3 contactNormal = new Vec3(mainCubeOBB.calculateMTV(entityAABB));
        if (contactNormal.dot(getDeltaMovement()) <= 0) {
            return;
        }
        impact(pEntity);
    }

    /** Pair answered by hull contact. A deck in reach answers through deck contacts; a hull push on top of them throws the rider off. */
    boolean hullContacts(AbstractVehicle vehicle) {
        boolean deck = vehicle.carrierLink().carrier() == this || carrierLink.carrier() == vehicle
                || standsOnDeckOf(vehicle, this) || standsOnDeckOf(this, vehicle);
        return !deck && !carriedBy(this, vehicle) && !carriedBy(vehicle, this) && !TowLinks.sameTrain(this, vehicle);
    }

    /** Neighbour gather beyond own bounds: a client's remote vehicle has a 1-block box. */
    private static final double MOUNT_NEIGHBOUR_REACH = 16;
    private final List<RotatableUnit<?>> turnedMounts = new ArrayList<>();

    /**
     * Traverse is kinematic: hull contact sees depth, no motion, and pays it out as spin at the muzzle (Krupp barrel
     * into an M983: Krupp launched 6.7 b/t). A mount turning deeper into another hull holds this tick's pose instead.
     */
    private void holdBlockedMounts() {
        if (!collision || mainCubeOBB == null) {
            return;
        }
        turnedMounts.clear();
        for (int i = 0, size = partUnits.size(); i < size; i++) {
            if (partUnits.get(i) instanceof RotatableUnit<?> mount && mount.turned()) {
                turnedMounts.add(mount);
            }
        }
        if (turnedMounts.isEmpty()) {
            return;
        }
        List<Entity> near = level().getEntities(this, getBoundingBox().inflate(MOUNT_NEIGHBOUR_REACH),
                entity -> entity instanceof AbstractVehicle vehicle && vehicle.getMainCubeOBB() != null
                        && vehicle.collision && hullContacts(vehicle));
        if (near.isEmpty()) {
            return;
        }
        float turned = overlapWith(near);
        if (turned == 0) {
            return;
        }
        for (int i = 0, size = turnedMounts.size(); i < size; i++) {
            turnedMounts.get(i).holdRot();
        }
        updateOBBs();
        if (overlapWith(near) < turned) {
            return;
        }
        for (int i = 0, size = turnedMounts.size(); i < size; i++) {
            turnedMounts.get(i).resumeRot();
        }
        updateOBBs();
    }

    /** Summed piece-pair depth against {@code others}. */
    private float overlapWith(List<Entity> others) {
        List<OBB> own = getOBBs();
        Vector3f axis = new Vector3f();
        float total = 0;
        for (int k = 0, count = others.size(); k < count; k++) {
            List<OBB> theirs = ((AbstractVehicle) others.get(k)).getOBBs();
            for (int i = 0, ownCount = own.size(); i < ownCount; i++) {
                OBB mine = own.get(i);
                for (int j = 0, theirCount = theirs.size(); j < theirCount; j++) {
                    OBB other = theirs.get(j);
                    if (near(mine, other, 0, 0, 0)) {
                        total += Math.max(0, OBB.mtv(mine, 0, 0, 0, other, axis));
                    }
                }
            }
        }
        return total;
    }





    private static boolean near(OBB moving, OBB own, double dx, double dy, double dz) {
        float reach = moving.extents().length() + own.extents().length();
        double cx = own.centerX() - moving.centerX() - dx;
        double cy = own.centerY() - moving.centerY() - dy;
        double cz = own.centerZ() - moving.centerZ() - dz;
        return cx * cx + cy * cy + cz * cz <= reach * reach;
    }





    /** Drive and steering bite: a wheel or track down, or (deck: wheel probes read blocks only) resting upright. */
    public boolean hasTraction() {
        if (!physicsEngine.suspended()) {
            return onGround();
        }
        return physicsEngine.wheelsGrounded()
                || onGround() && mainCubeOBB != null && mainCubeOBB.obb().getAxes()[1].y > 0.5F;
    }



    /** Hull bottom within this of a landing piece's top (its own frame) => the deck owns the pair. */
    private static final double DECK_STAND_REACH = 0.5;

    /**
     * Rider's hull centre over a carrier landing piece, bottom near its top. Covers the tick a woken rider has neither harness
     * nor deck link (refresh runs in its next plan): hull contact there turned boat speed into a 0.6 b/t launch.
     */
    private static boolean standsOnDeckOf(AbstractVehicle rider, AbstractVehicle carrier) {
        DeckSnapshot deck = carrier.deckSnapshot();
        if (deck.deckCount() == 0 || rider.mainCubeOBB == null) {
            return false;
        }
        OBB hull = rider.mainCubeOBB.obb();
        Vector3d local = DeckFrame.toLocal(deck.inverse(), deck.pivotX(), deck.pivotY(), deck.pivotZ(),
                hull.centerX(), hull.centerY(), hull.centerZ(), new Vector3d());
        double below = hull.extents().length();
        float[] frames = deck.frames();
        boolean[] landing = deck.landing();
        Quaternionf inverse = new Quaternionf();
        Vector3d p = new Vector3d();
        for (int i = 0, count = deck.count(); i < count; i++) {
            if (!landing[i]) {
                continue;
            }
            int f = i * DeckSnapshot.FRAME;
            inverse.set(frames[f + 6], frames[f + 7], frames[f + 8], frames[f + 9]).conjugate()
                    .transform(p.set(local.x - frames[f], local.y - frames[f + 1], local.z - frames[f + 2]));
            float top = frames[f + 4];
            if (Math.abs(p.x) <= frames[f + 3] && Math.abs(p.z) <= frames[f + 5]
                    && p.y >= top - DECK_STAND_REACH && p.y <= top + below + DECK_STAND_REACH) {
                return true;
            }
        }
        return false;
    }

    /** Whether carrier is anywhere under rider's chain of harnesses and decks. Tick thread. */
    private static boolean carriedBy(AbstractVehicle rider, AbstractVehicle carrier) {
        AbstractVehicle at = rider;
        for (int depth = 0; depth < 8 && at != null; depth++) {
            DeckCarrier above = VehicleHarness.carrierOf(at);
            if (above == null) {
                above = at.carrierLink().supportingCarrier();
            }
            if (above == carrier) {
                return true;
            }
            at = above instanceof AbstractVehicle vehicle ? vehicle : null;
        }
        return false;
    }

    public void support(Entity pEntity) {
        // Passenger pose = its vehicle's seat. Pushed here first (client carrier ticking before a parked vehicle's
        // occupant): old pose = depenetrated + carried spot => seated player jitters 0.25-0.4/frame.
        if (pEntity.noPhysics || this.noPhysics || !collision || pEntity.isPassenger()) {
            return;
        }
        DeckAttachment attachment = VehicleParenting.attachmentOf(pEntity);
        if (attachment != null && attachment.vehicle() == this && attachment.gripped()) {
            return;
        }
        boolean carried = false;
        List<OBB> obbs = getOBBs();
        for (int pass = 0; pass < SUPPORT_RESOLVE_PASSES; pass++) {
            boolean resolvedAny = false;
            for (int i = 0, size = obbs.size(); i < size; i++) {
                OBB obb = obbs.get(i);
                AABB entityAABB = pEntity.getBoundingBox();
                double offX = (entityAABB.minX + entityAABB.maxX) * 0.5 - obb.centerX();
                double offY = (entityAABB.minY + entityAABB.maxY) * 0.5 - obb.centerY();
                double offZ = (entityAABB.minZ + entityAABB.maxZ) * 0.5 - obb.centerZ();
                double entityHalfX = (entityAABB.maxX - entityAABB.minX) * 0.5;
                double entityHalfY = (entityAABB.maxY - entityAABB.minY) * 0.5;
                double entityHalfZ = (entityAABB.maxZ - entityAABB.minZ) * 0.5;
                double reach = obb.extents().length() + Math.sqrt(entityHalfX * entityHalfX
                        + entityHalfY * entityHalfY + entityHalfZ * entityHalfZ);
                if (offX * offX + offY * offY + offZ * offZ > reach * reach) {
                    continue;
                }
                float depth = OBB.mtv(supportFrame.set(obb.rotation()),
                        obb.centerX(), obb.centerY(), obb.centerZ(), obb.extents(),
                        entityAABB.minX, entityAABB.minY, entityAABB.minZ,
                        entityAABB.maxX, entityAABB.maxY, entityAABB.maxZ, supportMtv);
                if (depth <= 0) {
                    continue;
                }
                Vec3 mtv = new Vec3(supportMtv.x, supportMtv.y, supportMtv.z);
                if (mtv.y < 0) {
                    Vec3 direction = pEntity.position().subtract(this.position()).normalize();
                    direction = direction.scale(0.2f);
                    mtv = new Vec3(direction.x, 0, direction.z);
                } else if (mtv.y > 0) {
                    Vec3 movement = pEntity.getDeltaMovement();
                    pEntity.setOnGround(true);
                    pEntity.fallDistance = 0;
                    pEntity.setDeltaMovement(movement.x, Math.max(0, movement.y), movement.z);
                    carried = true;
                }

                pEntity.setPos(pEntity.getX() + mtv.x, pEntity.getY() + mtv.y, pEntity.getZ() + mtv.z);
                resolvedAny = true;
            }
            if (!resolvedAny) {
                break;
            }
        }
        if (carried) {
            Vec3 carry = carriedDisplacement(pEntity.position());
            pEntity.setPos(pEntity.getX() + carry.x, pEntity.getY() + carry.y, pEntity.getZ() + carry.z);
        }
    }


    private Vec3 carriedDisplacement(Vec3 worldPos) {
        Vec3 prevPos = mainCubeOBB.positionO;
        Quaternionf prevRot = mainCubeOBB.rotationO;
        Vec3 currPos = mainCubeOBB.position;
        Quaternionf currRot = mainCubeOBB.rotation;
        if (prevPos == null || prevRot == null || currPos == null || currRot == null) {
            return this.getDeltaMovement();
        }
        Vector3f offset = new Vector3f(
                (float) (worldPos.x - prevPos.x),
                (float) (worldPos.y - prevPos.y),
                (float) (worldPos.z - prevPos.z));
        new Quaternionf(prevRot).conjugate().transform(offset);
        currRot.transform(offset);
        return new Vec3(currPos.x + offset.x - worldPos.x,
                currPos.y + offset.y - worldPos.y,
                currPos.z + offset.z - worldPos.z);
    }

    private void supportEntities(List<Entity> candidates) {
        boolean clientSide = this.level().isClientSide();
        for (int i = 0, size = candidates.size(); i < size; i++) {
            Entity entity = candidates.get(i);
            if (entity instanceof AbstractVehicle || entity.isPassengerOfSameVehicle(this)) {
                continue;
            }
            if (clientSide && !(entity instanceof Player)) {
                continue;
            }
            support(entity);
        }
    }


    private int collisionSubsteps(Vec3 movement) {
        Vector3f extents = mainCubeOBB.obb().extents();
        // The whole vehicle's reach, not the physics hull's: a gun barrel out past the nose sweeps
        // further under a spin than any corner of the hull does, and it is the furthest point that
        // decides how fine a slice has to be.
        float radius = collisionBody.hasPieces()
                ? Math.max(extents.length(), collisionBody.reachRadius())
                : extents.length();
        double tipDisplacement = movement.length() + physicsEngine.angularVelocity.length() * radius;
        return Mth.clamp(Mth.ceil(tipDisplacement / SAFE_STEP), 1, MAX_COLLISION_SUBSTEPS);
    }

    private final BoxBuffer turnBoxes = new BoxBuffer();
    private final OBB turnTrialHull = new OBB(new Vector3f(), new Vector3f(), new Quaternionf());
    private final Quaternionf turnSpin = new Quaternionf();

    /**
     * Turns the hull by the given degrees, but only as far as the world allows; rams whatever refuses.
     * @return the rotation actually applied, in degrees
     */
    public float turnBy(float degrees) {
        if (degrees == 0) {
            return 0;
        }
        if (!collision || mainCubeOBB == null) {
            this.setYRot(this.getYRot() + degrees);
            return degrees;
        }
        OBB obb = mainCubeOBB.obb();
        double radius = obb.extents().length();
        double tipSwing = Math.toRadians(Math.abs(degrees)) * radius;
        turnBoxes.clear();
        AABB bounds = getBoundingBox().inflate(Math.max(1.0, tipSwing + 0.5));
        ChunkCollisionCache cache = ChunkCollisionCache.of(this.level());
        boolean deckNear = carrierLink.active();
        if (!deckNear && !covers(preparedBounds, bounds) && !cache.prepare(this.level(), bounds)) {
            this.setYRot(this.getYRot() + degrees);
            return degrees;
        }
        cache.collectBoxes(bounds, turnBoxes);
        if (turnBoxes.isEmpty() && !deckNear) {
            this.setYRot(this.getYRot() + degrees);
            return degrees;
        }

        turnSpin.rotationY((float) Math.toRadians(-degrees));
        turnTrialHull.extents().set(obb.extents());
        turnSpin.mul(obb.rotation(), turnTrialHull.rotation());
        // Spin the hull's offset from the pivot, then put it back; the arm is formed in doubles so
        // the rotation is about the vehicle's real position rather than a narrowed one.
        Vector3f arm = turnTrialHull.center().set(
                (float) (obb.centerX() - getX()),
                (float) (obb.centerY() - getY()),
                (float) (obb.centerZ() - getZ()));
        turnSpin.transform(arm);
        turnTrialHull.setCenter(getX() + arm.x, getY() + arm.y, getZ() + arm.z);
        OBB trial = SweptHull.climbHull(turnTrialHull, sweepSkirt(turnTrialHull.rotation()), sweepHull);
        int hit = SweptHull.firstOverlappingBox(trial, sweepShape(trial), turnBoxes);
        AABB rammed = hit >= 0 ? turnBoxes.get(hit)
                : collisionBody.firstOverlap(trial, sweepRise(trial.rotation()), sweepBottom(trial.rotation()), turnBoxes);
        if (rammed == null) {
            if (deckNear && carrierLink.overlaps(trial)) {
                return 0;
            }
            this.setYRot(this.getYRot() + degrees);
            return degrees;
        }
        double tipSpeed = Math.toRadians(Math.abs(degrees)) * radius;
        physicsEngine.ramByRotation(rammed, tipSpeed);
        return 0;
    }

    /**
     * Translates by up to (dx, dy, dz), stopped by the world and a deck the way travel is (vertical
     * cast, horizontal legs, step-up). Tick thread, outside a solve (constraints: towing).
     * @return fraction of the horizontal move taken, or of the vertical when there is none
     */
    public double sweepBy(double dx, double dy, double dz) {
        if (!collision || mainCubeOBB == null) {
            this.setPos(getX() + dx, getY() + dy, getZ() + dz);
            return 1;
        }
        ChunkCollisionCache cache = ChunkCollisionCache.of(this.level());
        AABB bounds = getBoundingBox().expandTowards(dx, dy, dz).expandTowards(0, maxUpStep(), 0)
                .inflate(SAMPLE_GATE_MARGIN);
        if (!cache.prepare(this.level(), bounds) && !carrierLink.active()) {
            this.setPos(getX() + dx, getY() + dy, getZ() + dz);
            return 1;
        }
        OBB hull = SweptHull.climbHull(mainCubeOBB.obb(), sweepSkirt(mainCubeOBB.obb().rotation()), sweepHull);
        sweepFrame.set(hull.rotation());
        sweptBroadphase.init(cache);
        BoxBuffer near = sweptBroadphase.near(hull, dx, dy + maxUpStep(), dz);
        double movedY = dy == 0 ? 0 : dy * castToi(hull, near, 0, dy, 0, null, sweepFrame);
        hull.translate(0, movedY, 0);
        double baseX = hull.centerX();
        double baseY = hull.centerY();
        double baseZ = hull.centerZ();
        sweepHorizontal(hull, near, dx, dz, null);
        double got = Math.abs(sweptLegX) + Math.abs(sweptLegZ);
        double wanted = Math.abs(dx) + Math.abs(dz);
        double lift = 0;
        if (got < wanted - 1.0e-7) {
            lift = stepUp(hull, near, baseX, baseY, baseZ, dx, dz, got);
            if (lift > 0) {
                got = Math.abs(sweptLegX) + Math.abs(sweptLegZ);
            }
        }
        this.setPos(getX() + sweptLegX, getY() + movedY + lift, getZ() + sweptLegZ);
        return wanted > 0 ? got / wanted : (dy == 0 ? 1 : movedY / dy);
    }

    private static boolean horizontal(Vec3 step) {
        return step.x != 0 || step.z != 0;
    }

    /**
     * The shape to cast this step, or null to cast the box.
     *
     * <p>A vehicle with no hull in its pack, or one whose hull is shorter than its own ride band, gets
     * null and sweeps the box exactly as it always did. The box is larger than any shape inside it,
     * so that answer stops early rather than passing through anything.
     */
    @Nullable
    private ConvexSweep sweepShape(OBB posed) {
        ConvexHull shape = mainCubeOBB == null ? null : mainCubeOBB.hull();
        if (shape == null) {
            castSweep.clear();
            return null;
        }
        float extentY = mainCubeOBB.obb().extents().y;
        double skirt = sweepSkirt(posed.rotation());
        if (shape != castSource || skirt != castSkirt || extentY != castExtentY) {
            castSource = shape;
            castSkirt = skirt;
            castExtentY = extentY;
            castHull = SweptHull.castShape(shape, extentY, skirt);
        }
        if (castHull == null) {
            castSweep.clear();
            return null;
        }
        castSweep.pose(castHull, posed.rotation());
        return castSweep.ready() ? castSweep : null;
    }

    /**
     * The pieces this vehicle collides as beyond its physics hull.
     *
     * <p>Empty for a vehicle whose pack drew no shapes, and for one whose shapes all fit inside the
     * hull's own; a hull file is not by itself a compound.
     */
    public CompoundBody collisionBody() {
        return collisionBody;
    }

    /** One sweep cast against world boxes and carrier decks, for every piece the vehicle is. */
    private double castToi(OBB hull, BoxBuffer near, double moveX, double moveY, double moveZ,
                           @Nullable SweptHull.Probe probe, OBB.SatFrame frame) {
        double toi = SweptHull.timeOfImpact(hull, sweepShape(hull), near, moveX, moveY, moveZ,
                probe, frame);
        if (collisionBody.hasPieces()) {
            double hullAlone = toi;
            toi = collisionBody.timeOfImpact(hull, sweepRise(hull.rotation()), sweepBottom(hull.rotation()), near,
                    moveX, moveY, moveZ, toi);
            if (probe != null && toi < hullAlone) {
                // The blocker stays whatever the hull met; the probe carries one, and what matters
                // to a trace is that the step was cut and by how much.
                probe.outcome = SweptHull.Outcome.CLIPPED;
                probe.toi = toi;
            }
        }
        if (carrierLink.active()) {
            toi = Math.min(toi, carrierLink.timeOfImpact(hull, moveX, moveY, moveZ));
        }
        return toi;
    }

    /** How far the swept climb box's centre sits above the hull's own, in the hull's frame. */
    private float sweepRise(Quaternionf rotation) {
        return SweptHull.climbRise(mainCubeOBB.obb().extents().y, sweepSkirt(rotation));
    }

    /** The skirt plane every piece of the vehicle is cut against, as a height in the hull's frame. */
    private float sweepBottom(Quaternionf rotation) {
        return SweptHull.climbBottom(mainCubeOBB.obb().extents().y, sweepSkirt(rotation));
    }

    public double climbToi(OBB hull, BoxBuffer near, double lift, OBB.SatFrame frame) {
        return castToi(hull, near, 0, lift, 0, null, frame);
    }

    public com.wf.combinedassault.ballistics.ArmorRegions armorRegions() {
        return armorRegions;
    }

    /**
     * The systems this vehicle's collision pieces declare, and what condition they are in.
     *
     * <p>Built on first use rather than on construction, because the pieces come from the pack and a
     * save may be read before they exist.
     */
    public HullComponents hullComponents() {
        if (hullComponents == null) {
            hullComponents = HullComponents.of(this);
            if (level().isClientSide()) {
                hullComponents.fromSync(entityData.get(SYSTEMS));
            } else if (hullComponentSave != null) {
                hullComponents.load(hullComponentSave);
                if (dataInitialized) {
                    hullComponentSave = null; // earlier builds are discarded by initData; keep the save for the real one
                }
            }
        }
        return hullComponents;
    }

    public DeckCarry deckCarry() {
        return deckCarry;
    }

    public CarrierLink carrierLink() {
        return carrierLink;
    }

    @Nullable
    public VehicleHarness harness() {
        return harness;
    }

    public void setHarness(@Nullable VehicleHarness harness) {
        this.harness = harness;
    }

    public int harnessDwell() {
        return harnessDwell;
    }

    /**
     * Whether something solid is under this vehicle, counting a carrier's deck.
     *
     * <p>The block below is the only ground a land vehicle can be on, and at sea there is never one:
     * a plane sitting on a flight deck has air under its wheels and open water under that. Both
     * carrier ids are synced, so this answers the same on a client as on the server, which the block
     * lookup also does but for the wrong reason.
     */
    public boolean groundedOrOnDeck() {
        return level().getBlockState(blockPosition().below()).isSolid()
                || deckCarrierId() >= 0 || harnessCarrierId() >= 0;
    }

    public int deckCarrierId() {
        return entityData.get(DECK_CARRIER);
    }

    public void setDeckCarrierId(int id) {
        if (entityData.get(DECK_CARRIER) != id) {
            entityData.set(DECK_CARRIER, id);
        }
    }

    public Vector3f deckLocal() {
        return entityData.get(DECK_LOCAL);
    }

    public Quaternionf deckLocalRotation() {
        return entityData.get(DECK_LOCAL_ROTATION);
    }

    /** Server: sets only on change (tolerance 1e-4), a parked vehicle sends nothing. */
    public void setDeckLocal(Vector3d local, Quaternionf rotation) {
        Vector3f was = entityData.get(DECK_LOCAL);
        if (Math.abs(was.x - local.x) > 1.0e-4 || Math.abs(was.y - local.y) > 1.0e-4 || Math.abs(was.z - local.z) > 1.0e-4) {
            entityData.set(DECK_LOCAL, new Vector3f((float) local.x, (float) local.y, (float) local.z));
        }
        if (!entityData.get(DECK_LOCAL_ROTATION).equals(rotation, 1.0e-5f)) {
            entityData.set(DECK_LOCAL_ROTATION, new Quaternionf(rotation));
        }
    }

    public long lastTickTime() {
        return lastTickTime;
    }

    /** Ticks left of the interpolation toward the pose the server last reported. */
    public int clientLerpSteps() {
        return lerpSteps;
    }

    /** Consumes one of those ticks, for a caller that interpolated the step itself. */
    public void takeClientLerpStep() {
        if (lerpSteps > 0) {
            lerpSteps--;
        }
    }

    /** The rotation the server last reported, which pairs with {@link #lerpTargetX()}. Predicted: own pose. */
    public float lerpTargetXRot() {
        return lerpSteps > 0 && prediction == null ? lerpXRot : getXRot();
    }

    public float lerpTargetYRot() {
        return lerpSteps > 0 && prediction == null ? lerpYRot : getYRot();
    }

    public float lerpTargetZRot() {
        return lerpSteps > 0 && prediction == null ? lerpZRot : getZRot();
    }

    @Override
    public double lerpTargetX() {
        return prediction == null ? super.lerpTargetX() : getX();
    }

    @Override
    public double lerpTargetY() {
        return prediction == null ? super.lerpTargetY() : getY();
    }

    @Override
    public double lerpTargetZ() {
        return prediction == null ? super.lerpTargetZ() : getZ();
    }

    /** Predicted: the server's velocity is ack-old; the snapshot carries it. */
    @Override
    public void lerpMotion(double x, double y, double z) {
        if (prediction == null) {
            super.lerpMotion(x, y, z);
        }
    }

    public void setHarnessDwell(int ticks) {
        this.harnessDwell = ticks;
    }

    /** Aircraft crash by their own rules. */
    private void tickLanding() {
        if (this instanceof FixedWingVehicle || this instanceof RotaryWingVehicle || VehicleHarness.isHarnessed(this)) {
            lastFall = 0;
            return;
        }
        if (!onGround()) {
            lastFall = Math.max(0, -getDeltaMovement().y);
        } else if (lastFall > 0) {
            DamageSystem.landingHurt(this, lastFall);
            lastFall = 0;
        }
    }

    @Nullable
    public CargoHold cargoHold() {
        return cargoHold;
    }

    public CompoundTag cargoView() {
        return entityData.get(CARGO_VIEW);
    }

    public void setCargoView(CompoundTag view) {
        entityData.set(CARGO_VIEW, view);
    }

    public VehicleParachute parachute() {
        return parachute;
    }

    public boolean parachuteDeployed() {
        return entityData.get(PARACHUTE_DEPLOYED);
    }

    public void setParachuteDeployed(boolean deployed) {
        entityData.set(PARACHUTE_DEPLOYED, deployed);
    }

    public boolean deckSeekBlocked() {
        return deckSeekBlocked;
    }

    /** Airdropped: static line armed, no deck seek. */
    public void setDeckSeekBlocked(boolean blocked) {
        deckSeekBlocked = blocked;
        if (blocked) {
            parachute.armStaticLine();
        }
    }

    public List<AbstractVehicle> harnessedVehicles() {
        return harnessedVehicles;
    }

    @Override
    public Vec3 carrierCenterOffset() {
        return centerOffset;
    }

    @Nullable
    public TowLink towLink() {
        return towLink;
    }

    public void setTowLink(@Nullable TowLink link) {
        this.towLink = link;
    }

    @Nullable
    public TowLinks.Pending pendingTow() {
        return pendingTow;
    }

    public void setPendingTow(@Nullable TowLinks.Pending pending) {
        this.pendingTow = pending;
    }

    public int getTowTractorId() {
        return entityData.get(TOW_TRACTOR);
    }

    public void setTowTractorId(int id) {
        if (entityData.get(TOW_TRACTOR) != id) {
            entityData.set(TOW_TRACTOR, id);
        }
    }

    public int getTowHitch() {
        return entityData.get(TOW_HITCH);
    }

    public void setTowHitch(int packed) {
        if (entityData.get(TOW_HITCH) != packed) {
            entityData.set(TOW_HITCH, packed);
        }
    }

    public int harnessCarrierId() {
        return entityData.get(HARNESS_CARRIER);
    }

    public void setHarnessCarrierId(int id) {
        entityData.set(HARNESS_CARRIER, id);
    }

    private double sweptLegX, sweptLegZ;

    /** Larger-magnitude axis swept first (carries the probe). */
    private void sweepHorizontal(OBB hull, BoxBuffer near, double moveX, double moveZ,
                                 @Nullable SweptHull.Probe probe) {
        if (Math.abs(moveX) >= Math.abs(moveZ)) {
            sweepLegX(hull, near, moveX, probe);
            sweepLegZ(hull, near, moveZ, null);
        } else {
            sweepLegZ(hull, near, moveZ, probe);
            sweepLegX(hull, near, moveX, null);
        }
    }

    private void sweepLegX(OBB hull, BoxBuffer near, double moveX,
                           @Nullable SweptHull.Probe probe) {
        sweptLegX = 0;
        if (moveX != 0) {
            sweptLegX = moveX * castToi(hull, near, moveX, 0, 0, probe, sweepFrame);
            hull.translate(sweptLegX, 0, 0);
        }
    }

    private void sweepLegZ(OBB hull, BoxBuffer near, double moveZ,
                           @Nullable SweptHull.Probe probe) {
        sweptLegZ = 0;
        if (moveZ != 0) {
            sweptLegZ = moveZ * castToi(hull, near, 0, 0, moveZ, probe, sweepFrame);
            hull.translate(0, 0, sweptLegZ);
        }
    }

    private static final int STEP_UP_ITERATIONS = 4;
    /**
     * Raised retry must gain this share of the move. Slop-level gain at a wall face => M983
     * ratcheted up a 2-block wall.
     */
    private static final double STEP_UP_MIN_GAIN = 0.05;

    /** Retries a clipped horizontal step with the hull raised, returning the smallest lift that helps. */
    private double stepUp(OBB hull, BoxBuffer near, double baseX, double baseY, double baseZ,
                          double moveX, double moveZ, double baseGain) {
        double stepLen = Math.sqrt(moveX * moveX + moveZ * moveZ);
        double budget = Math.min(maxUpStep(), stepLen * physicsEngine.climbGradient);
        if (budget <= 1.0e-4) {
            return 0;
        }
        hull.setCenter(baseX, baseY, baseZ);
        budget *= castToi(hull, near, 0, budget, 0, null, sweepFrame);
        if (budget <= 1.0e-4) {
            return 0;
        }
        hull.setCenter(baseX, baseY + budget, baseZ);
        sweepHorizontal(hull, near, moveX, moveZ, null);
        double raisedGain = Math.abs(sweptLegX) + Math.abs(sweptLegZ);
        if (raisedGain <= baseGain + STEP_UP_MIN_GAIN * stepLen) {
            return 0;
        }
        double bestX = sweptLegX;
        double bestZ = sweptLegZ;
        double lo = 0;
        double hi = budget;
        for (int i = 0; i < STEP_UP_ITERATIONS; i++) {
            double mid = (lo + hi) * 0.5;
            hull.setCenter(baseX, baseY + mid, baseZ);
            sweepHorizontal(hull, near, moveX, moveZ, null);
            if (Math.abs(sweptLegX) + Math.abs(sweptLegZ) >= raisedGain - 1.0e-7) {
                hi = mid;
                bestX = sweptLegX;
                bestZ = sweptLegZ;
            } else {
                lo = mid;
            }
        }
        sweptLegX = bestX;
        sweptLegZ = bestZ;
        return hi;
    }

    private static final double MAX_TICK_DISPLACEMENT = MAX_COLLISION_SUBSTEPS * SAFE_STEP;

    private static final int OVERSPEED_LOG_INTERVAL = 100;

    private int overspeedLogTick = -OVERSPEED_LOG_INTERVAL;

    /** Caps this tick's movement at what the substep loop can actually slice safely. */
    private Vec3 clampToSweepBudget(Vec3 movement) {
        double length = movement.length();
        if (length <= MAX_TICK_DISPLACEMENT || length <= 1.0e-9) {
            return movement;
        }
        if (tickCount - overspeedLogTick >= OVERSPEED_LOG_INTERVAL) {
            overspeedLogTick = tickCount;
            CombinedAssault.LOGGER.warn(
                    "{} #{} asked to move {} blocks in one tick; clamped to {} so collision"
                            + " substeps stay under {} blocks. Velocity is {} — this usually means"
                            + " nothing is cancelling its fall.",
                    getType().toShortString(), getId(), String.format(Locale.ROOT, "%.2f", length),
                    String.format(Locale.ROOT, "%.2f", MAX_TICK_DISPLACEMENT), SAFE_STEP,
                    getDeltaMovement());
        }
        return movement.scale(MAX_TICK_DISPLACEMENT / length);
    }

    public void impact(Entity entity) {
        if (this.equals(entity.getVehicle())) {
            return;
        }
        // A hull does not run over what it is carrying. A rider on a deck is moved kinematically, so its
        // world velocity is zero while the hull's is not, and the closing speed this reads is the whole
        // speed of the ship: on a carrier under way that is instant death for anyone who steps out of a
        // parked aircraft.
        DeckAttachment attachment = VehicleParenting.attachmentOf(entity);
        if (attachment != null && attachment.vehicle() == this && attachment.gripped()) {
            return;
        }
        LivingEntity driver = getDriver();
        if (entity instanceof TamableAnimal tamableAnimal) {
            if (tamableAnimal.getOwner() == driver) {
                return;
            }
        }
        if (entity instanceof AbstractVehicle vehicle) {
            if (vehicle.hurtTick > 0) {
                return;
            }
        }
        double velocity = this.getDeltaMovement().length();
        if (velocity == 0) {
            return;
        }
        double entityVelocity = entity.getDeltaMovement().dot(this.getDeltaMovement()) / velocity;
        double relVelocity = (velocity - entityVelocity) * 10;
        if (relVelocity > 1) {
            entity.hurt(AllDamageTypes.Sources.vehicleCollision(level().registryAccess(), this, this.getDriver(), null),
                    (float) (0.5 * (curbWeight / PhysicsHelper.KILOGRAMS_PER_TONNE) * relVelocity * relVelocity));
        }
    }

    public void triggerPosRotUpdate() {
        ClientboundMoveEntityPacket.PosRot packet = new ClientboundMoveEntityPacket.PosRot(this.getId(), (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, this.onGround());
        ((ServerLevel) this.level()).getChunkSource().broadcast(this, packet);
    }

    /** Unsprung (emplacement, trailer): climbs nothing; skirt 1 above a 0.35 base plate left its fall unswept. */
    @Override
    public float maxUpStep() {
        return hasSuspension() ? 1.0F : 0.0F;
    }

    /** |x axis . up| at which the skirt band is gone; fades from {@link PhysicsEngine#MAX_STEP_ROLL} (sin 20 deg). */
    private static final double SKIRT_GONE_ROLL = 0.5;
    /** Band quantum: cast shape rebuilt per distinct skirt. */
    private static final double SKIRT_STEPS = 8;

    /**
     * Hull-local Y of the underside swept against the world, at this attitude. Rolled past step-up => nothing lifts
     * the hull over band geometry => band fades: M142 (chassis wholly in band) rolled 37 deg slid 0.56 into a wall.
     */
    public double sweepSkirt(Quaternionf rotation) {
        // No step => no margin: 0.05 over the underside sank a dropped base plate 5 cm into the floor.
        float step = maxUpStep();
        float extentY = mainCubeOBB.obb().extents().y;
        double skirt = java.lang.Math.min(mainCubeOBB.climbSkirt(),
                -extentY + step + (step > 0 ? SWEEP_STEP_MARGIN : SweptHull.SLOP));
        double roll = java.lang.Math.abs(2 * (rotation.x * rotation.y + rotation.z * rotation.w));
        double keep = Mth.clamp((SKIRT_GONE_ROLL - roll) / (SKIRT_GONE_ROLL - PhysicsEngine.MAX_STEP_ROLL), 0, 1);
        keep = java.lang.Math.ceil(keep * SKIRT_STEPS) / SKIRT_STEPS;
        return -extentY + (skirt + extentY) * keep;
    }

    @Nullable
    public PhysicsTrace physicsTrace() {
        return physicsTrace;
    }

    public void setPhysicsTrace(@Nullable PhysicsTrace physicsTrace) {
        this.physicsTrace = physicsTrace;
    }

    public void aiStep() {
        applyVelocityDeadband();
        this.level().getProfiler().push("travel");
        long planAt = TrialProfile.start();
        physicsRig.capturePose(this);
        physicsRig.bindWorld(ChunkCollisionCache.of(this.level()));
        planTravel(physicsRig);
        TrialProfile.stop(TrialProfile.Stage.PLAN, planAt);
        TrialProfile.count(TrialProfile.Work.SUBSTEPS, physicsRig.substeps);
        long travelAt = TrialProfile.start();
        solveTravelLive(physicsRig);
        flushTravel(physicsRig);
        TrialProfile.stop(TrialProfile.Stage.TRAVEL, travelAt);
        this.level().getProfiler().pop();

        this.level().getProfiler().push("push");
        {
            long pushAt = TrialProfile.start();
            this.pushEntities();
            TrialProfile.stop(TrialProfile.Stage.PUSH, pushAt);
        }
        this.level().getProfiler().pop();
    }

    private void applyVelocityDeadband() {
        Vec3 v = this.getDeltaMovement();
        double dx = v.x;
        double dy = v.y;
        double dz = v.z;
        if (Math.abs(v.x) < 0.001D) {
            dx = 0.0D;
        }
        if (Math.abs(v.y) < 0.001D) {
            dy = 0.0D;
        }
        if (Math.abs(v.z) < 0.001D) {
            dz = 0.0D;
        }
        this.setDeltaMovement(dx, dy, dz);
    }




    /**
     * Tick thread. Workers never consult providers => a provider with geometry near => sync.
     * Gate on "any provider registered" FORBIDDEN: Create or Sable installed => every vehicle sync.
     */
    boolean providerNear(AABB gather) {
        List<CollisionProvider> registered = CollisionProviders.providers();
        if (registered.isEmpty()) {
            return false;
        }
        for (int i = 0, size = registered.size(); i < size; i++) {
            if (registered.get(i).begin(this, gather) != null) {
                return true;
            }
        }
        return false;
    }

    /** Client: prepare sections, decide how finely to slice this tick's predicted movement. */
    private void planTravel(PhysicsRig rig) {
        Vec3 movement = getDeltaMovement();
        boolean moving = movement.lengthSqr() > 1.0e-8;
        if (!collision || !moving) {
            rig.travelMovement = movement;
            rig.swept = false;
            rig.substeps = 1;
            rig.carried = List.of();
            return;
        }
        AABB gather = getBoundingBox().expandTowards(movement).expandTowards(0, maxUpStep(), 0)
                .inflate(SAMPLE_GATE_MARGIN);
        boolean anySolidNear = ChunkCollisionCache.of(this.level()).prepare(this.level(), gather);
        preparedBounds = gather;
        tickAnySolidNearby = anySolidNear;
        boolean deckNear = carrierLink.refresh(this, gather);
        plan(rig, movement, deckNear, anySolidNear);
        rig.carried = rig.substeps > 1
                ? this.level().getEntities(this, getBoundingBox().expandTowards(movement),
                        EntitySelector.pushableBy(this))
                : List.of();
    }

    /** Swept gate, sweep budget, slices. Worker-safe: rig sections only. */
    private void plan(PhysicsRig rig, Vec3 movement, boolean deckNear, boolean anySolidNear) {
        sweptBroadphase.init(rig.sections);
        boolean swept = collision && movement.lengthSqr() > 1.0e-8
                && (deckNear || (anySolidNear && ChunkCollisionCache.anyBoxIn(rig.sections,
                        rig.bounds().expandTowards(movement).inflate(1.0))));
        if (swept) {
            movement = clampToSweepBudget(movement);
            sweepFrame.set(rig.hull.rotation());
        }
        rig.travelMovement = movement;
        rig.swept = swept;
        rig.substeps = swept ? collisionSubsteps(movement) : 1;
    }





    /**
     * Unregistered (asleep, or not yet ticked) inside a stepping vehicle's reach: joins this tick's step as it stands,
     * awake from the next. Asleep left out => a falling hull met a parked tank only on heartbeat ticks, 1.1 deep.
     */
    boolean wakeForStep() {
        if (this.isRemoved() || mainCubeOBB == null || !collision || pendingMount != null
                || VehicleHarness.isHarnessed(this)) {
            return false;
        }
        physicsRestTicks = 0;
        Vec3 movement = getDeltaMovement();
        physicsEngine.noteDrive(movement, movement);
        physicsEngine.sampleBuoyancy();
        return true;
    }

    /** Tick thread, before the level's {@link RigidStep}: controllers edit velocity and yaw; buoyancy sampled live. */
    private void prepareStep() {
        applyVelocityDeadband();
        Vec3 before = getDeltaMovement();
        tickMove();
        physicsEngine.noteDrive(before, getDeltaMovement());
        physicsEngine.sampleBuoyancy();
    }

    /** Step capture, tick thread: rig at the captured hull pose, world prepared (pinned unless live), deck frozen. */
    void bindStepWorld(VehicleStep step, AABB gather) {
        PhysicsRig rig = physicsRig;
        rig.capturePose(this);
        rig.hull.setCenter(step.hullX, step.hullY, step.hullZ);
        rig.hull.setRotation(step.hullRotation);
        rig.refreshAxes();
        ChunkCollisionCache cache = ChunkCollisionCache.of(this.level());
        if (step.live) {
            tickAnySolidNearby = cache.prepare(this.level(), gather);
            rig.bindWorld(cache);
        } else {
            tickAnySolidNearby = cache.prepareAndPin(this.level(), gather, pinnedSections);
            rig.bindWorld(pinnedSections);
        }
        preparedBounds = gather;
        collisionCursor = rig.cursor;
        carrierLink.refresh(this, gather);
        Vec3 movement = getDeltaMovement();
        rig.carried = collisionSubsteps(movement) > 1
                ? this.level().getEntities(this, getBoundingBox().expandTowards(movement), EntitySelector.pushableBy(this))
                : List.of();
    }

    /** Live islands only (tick thread): provider geometry as boxes. Gap: providers without boxes are not seen. */
    /** Tick thread: open sessions near bounds; base[s] = session s's first box. */
    void collectProviderBoxes(AABB bounds, List<CollisionProvider.Session> sessions, IntArrayList base, OrientedBoxes out) {
        List<CollisionProvider.Session> open = openProviderSessions(bounds);
        for (int i = 0, size = open.size(); i < size; i++) {
            CollisionProvider.Session session = open.get(i);
            base.add(out.size());
            session.collectBoxes(bounds, out);
            sessions.add(session);
        }
    }

    /**
     * Island task: attitude turned by dq about the COM (unswept: speculative contacts bound it), then the COM's
     * displacement through the travel sweep. Velocity loses what the sweep refused.
     */
    void integrateStep(VehicleStep step, Vector3f dp, Quaternionf dq, Vector3f velocity) {
        PhysicsRig rig = physicsRig;
        if (dq.x != 0 || dq.y != 0 || dq.z != 0) {
            physicsEngine.setAttitude(rig, new Quaternionf(dq).mul(step.entityRotation));
        }
        physicsEngine.rightInLiquid(rig);
        Quaternionf applied = rig.rotYXZ();
        Vector3f before = step.entityRotation.transform(new Vector3f(step.comLocal));
        Vector3f after = applied.transform(new Vector3f(step.comLocal));
        rig.setPos(rig.x + before.x - after.x, rig.y + before.y - after.y, rig.z + before.z - after.z);
        Vector3f arm = applied.transform(new Vector3f(step.hullLocal));
        rig.hull.setCenter(rig.x + centerOffset.x + arm.x, rig.y + centerOffset.y + arm.y, rig.z + centerOffset.z + arm.z);
        rig.hull.setRotation(applied.mul(step.hullLocalRotation));
        rig.refreshAxes();
        step.startX = rig.x;
        step.startY = rig.y;
        step.startZ = rig.z;
        // Unswept: the solver's contacts are the collision (speculative margin covers the tick); a second sweep
        // geometry refused what the solver resolved (scope doc). Slices only pace carried entities.
        Vec3 movement = new Vec3(dp.x, dp.y, dp.z);
        int slices = collisionSubsteps(movement);
        rig.travelMovement = movement;
        rig.swept = true;
        rig.substeps = slices;
        for (int i = 0; i < slices; i++) {
            rig.move(movement.x / slices, movement.y / slices, movement.z / slices);
        }
        if (Boolean.getBoolean("combinedassault.rigid.debug")) {
            CombinedAssault.LOGGER.info(String.format(java.util.Locale.ROOT, "[rigid] body %d dp %s dq %s v %s rot %.2f/%.2f/%.2f",
                    getId(), dp, dq, velocity, rig.getXRot(), rig.getYRot(), rig.getZRot()));
        }
        rig.setDeltaMovement(new Vec3(velocity.x, velocity.y, velocity.z));
        rig.setOnGround(step.grounded);
    }

    public boolean predicted() {
        return prediction != null;
    }

    /** Class whose controller and carried state {@link #writePrediction} covers. */
    protected boolean predictable() {
        return false;
    }

    /** Client, driver's input this tick: predict when eligible (docs/client-prediction.md), else server pose lerped. */
    public void predictInput(ControlUnit input) {
        if (!predictionEligible()) {
            dropPrediction();
            return;
        }
        if (prediction == null) {
            prediction = new VehiclePrediction(this);
        }
        prediction.input(input);
    }

    private boolean predictionEligible() {
        if (!AllConfigs.Cached.predictDriving || !predictable() || mainCubeOBB == null || isDestroyed() || isRemoved()
                || VehicleHarness.isHarnessed(this) || deckCarrierId() >= 0 || carrierLink.active()
                || entityData.get(TOW_TRACTOR) >= 0 || entityData.get(TOW_HITCH) >= 0) {
            return false;
        }
        // Terrain absent client-side => the step flies through what the server collides with.
        AABB swept = getBoundingBox().expandTowards(getDeltaMovement().scale(PREDICT_TERRAIN_TICKS));
        // ClientLevel.hasChunk == true => ask the chunk source.
        var chunks = level().getChunkSource();
        for (int cx = SectionPos.blockToSectionCoord(swept.minX), maxX = SectionPos.blockToSectionCoord(swept.maxX); cx <= maxX; cx++) {
            for (int cz = SectionPos.blockToSectionCoord(swept.minZ), maxZ = SectionPos.blockToSectionCoord(swept.maxZ); cz <= maxZ; cz++) {
                if (!chunks.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        // Gap: no neighbour bodies client-side; within reach the server's contacts would correct every tick.
        AABB reach = getBoundingBox().inflate(NEIGHBOUR_REACH);
        for (AbstractVehicle other : level().getEntitiesOfClass(AbstractVehicle.class, reach)) {
            if (other != this && other.collision) {
                return false;
            }
        }
        return true;
    }

    private static final double NEIGHBOUR_REACH = 4;
    /** Lookahead of the client-terrain check: covers an RTT of unacked steps. */
    private static final double PREDICT_TERRAIN_TICKS = 10;

    /** Back to the server's pose: its last report blended in over the usual 3 ticks. */
    void dropPrediction() {
        if (prediction == null) {
            return;
        }
        prediction = null;
        lerpSteps = 3;
        lerpRotSteps = 3;
    }

    /** Client packet: server snapshot after the input {@code ack}. */
    public void predictionState(int ack, byte[] state) {
        if (prediction != null) {
            prediction.received(ack, state);
        }
    }

    /** Snapshot (docs/client-prediction.md): what the next step starts from beyond inputs and the world. */
    protected void writePrediction(FriendlyByteBuf buf) {
        buf.writeDouble(getX());
        buf.writeDouble(getY());
        buf.writeDouble(getZ());
        buf.writeFloat(xRot);
        buf.writeFloat(yRot);
        buf.writeFloat(zRot);
        Vec3 velocity = getDeltaMovement();
        buf.writeDouble(velocity.x);
        buf.writeDouble(velocity.y);
        buf.writeDouble(velocity.z);
        buf.writeBoolean(onGround());
        buf.writeFloat(getPower());
        physicsEngine.writePrediction(buf);
        step.writeWarm(buf);
    }

    protected void readPrediction(FriendlyByteBuf buf) {
        setPos(buf.readDouble(), buf.readDouble(), buf.readDouble());
        setXRot(buf.readFloat());
        setYRot(buf.readFloat());
        setZRot(buf.readFloat());
        setDeltaMovement(buf.readDouble(), buf.readDouble(), buf.readDouble());
        setOnGround(buf.readBoolean());
        setPower(buf.readFloat());
        physicsEngine.readPrediction(buf);
        step.readWarm(buf);
    }

    byte[] snapshot() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256));
        writePrediction(buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    /** Snapshot in place; OBBs reposed (the next capture bounds from them). */
    void restoreSnapshot(byte[] state) {
        readPrediction(new FriendlyByteBuf(Unpooled.wrappedBuffer(state)));
        updateOBBs();
    }

    /** Client tick thread: the server's step for this vehicle alone, minus its side effects. */
    void predictStep() {
        tickPower();
        prepareStep();
        step.woken = false;
        step.predicting = true;
        step.capture(false);
        RigidStep.predict(step);
        PhysicsRig rig = physicsRig;
        if (!step.failed) {
            this.setPos(step.startX, step.startY, step.startZ);
            this.setXRot(rig.getXRot());
            this.setYRot(rig.getYRot());
            this.setZRot(rig.getZRot());
            flushTravel(rig);
            this.setDeltaMovement(rig.getDeltaMovement());
            this.setOnGround(rig.onGround());
        }
        physicsEngine.applyPendingBreaks();
    }

    /** Tick thread, id order (carriers first): pose, velocity, damage, breaks, entity pushes, move event, finishTick. */
    void completeStep(VehicleStep step) {
        PhysicsRig rig = physicsRig;
        if (!step.failed) {
            this.setPos(step.startX, step.startY, step.startZ);
            this.setXRot(rig.getXRot());
            this.setYRot(rig.getYRot());
            this.setZRot(rig.getZRot());
            flushTravel(rig);
            this.setDeltaMovement(rig.getDeltaMovement());
            this.setOnGround(rig.onGround());
            // Wreck pool = weapons only: its own crash would discard it on touchdown.
            if (rig.impactVelocityDiff > 0 && !isDestroyed()) {
                DamageSystem.impactHurt(rig.impactVelocityDiff, rig.impactPos, this);
            }
            for (int i = 0, size = step.touched.size(); i < size; i++) {
                impact(step.touched.get(i));
            }
        }
        carrierLink.publish();
        physicsEngine.applyPendingBreaks();
        if (step.woken) {
            // finishTick already ran in this tick's entity tick.
            updateOBBs();
            return;
        }
        this.pushEntities();
        postMoveEvent();
        finishTick(physicsTrace);
    }

    /** The substep sweep. Worker-safe; reads prepared snapshots and records each slice. */
    void solveTravelLive(PhysicsRig rig) {
        boolean swept = rig.swept;
        int substeps = rig.substeps;
        Vec3 stepMovement = substeps > 1 ? rig.travelMovement.scale(1.0 / substeps) : rig.travelMovement;
        PhysicsTrace trace = physicsTrace != null && physicsTrace.isRecording()
                ? physicsTrace : null;
        SweptHull.Probe probe = trace != null ? trace.probe() : null;
        boolean sidewaysTick = horizontal(stepMovement);
        // Gathered once for the whole tick rather than per slice. Every substep's query bound is
        // contained in the bound of the tick's full movement, because a slice can only ever move
        // the hull less than it asked for, so the per-slice gathers were re-reading the same
        // sections and refilling the same buffer up to sixteen times over.
        BoxBuffer near = null;
        if (swept) {
            OBB tickHull = SweptHull.climbHull(rig.hull, sweepSkirt(rig.hull.rotation()), sweepHull);
            Vec3 whole = rig.travelMovement;
            double lift = whole.y + (sidewaysTick ? maxUpStep() : 0);
            // A compound reaches past the hull's own box, so the gather is bounded by what covers
            // every piece. That bound is not centred on the hull, hence the second overload.
            near = collisionBody.hasPieces()
                    ? sweptBroadphase.near(tickHull, collisionBody.sweptCentre(sweepRise(rig.hull.rotation())),
                            collisionBody.sweptExtents(), whole.x, lift, whole.z)
                    : sweptBroadphase.near(tickHull, whole.x, lift, whole.z);
        }
        for (int step = 0; step < substeps; step++) {
            Vec3 clipped = stepMovement;
            if (swept) {
                OBB hull = SweptHull.climbHull(rig.hull, sweepSkirt(rig.hull.rotation()), sweepHull);
                boolean sideways = sidewaysTick;
                double stepY = stepMovement.y;
                if (stepY != 0) {
                    stepY *= castToi(hull, near, 0, stepY, 0,
                            sideways ? null : probe, sweepFrame);
                }
                double movedX = 0;
                double movedZ = 0;
                double stepUpLift = 0;
                if (sideways) {
                    hull.translate(0, stepY, 0);
                    double baseX = hull.centerX();
                    double baseY = hull.centerY();
                    double baseZ = hull.centerZ();
                    sweepHorizontal(hull, near, stepMovement.x, stepMovement.z, probe);
                    movedX = sweptLegX;
                    movedZ = sweptLegZ;
                    double got = Math.abs(movedX) + Math.abs(movedZ);
                    double wanted = Math.abs(stepMovement.x) + Math.abs(stepMovement.z);
                    if (got < wanted - 1.0e-7 && rig.onGround()
                            && Math.abs(rig.hull.getAxes()[0].y) <= PhysicsEngine.MAX_STEP_ROLL) {
                        stepUpLift = stepUp(hull, near, baseX, baseY, baseZ,
                                stepMovement.x, stepMovement.z, got);
                        if (stepUpLift > 0) {
                            movedX = sweptLegX;
                            movedZ = sweptLegZ;
                            if (trace != null) {
                                trace.add(PhysicsTrace.Source.STEP_UP, stepUpLift);
                            }
                        }
                    }
                }
                clipped = new Vec3(movedX, stepY + stepUpLift, movedZ);
            } else if (probe != null) {
                probe.reset();
            }
            rig.move(clipped.x, clipped.y, clipped.z);
            if (trace != null) {
                if (swept) {
                    // Measured against the tick's buffer; it is a superset of what a fresh gather
                    // at this pose would return, and re-gathering would clobber the shared scratch.
                    OBB ended = SweptHull.climbHull(rig.hull, sweepSkirt(rig.hull.rotation()), sweepHull);
                    SweptHull.measurePenetration(ended, near, probe);
                }
                trace.sweep(this, rig, step, substeps, stepMovement);
            }
        }
    }

    /** Tick thread only. */
    void flushTravel(PhysicsRig rig) {
        var moves = rig.substepMoves;
        int steps = moves.size() / 3;
        // A swept slice has already been clipped against the world by the hull's own cast. Handing
        // it to the vanilla mover makes it walk every cell in this entity's bounding box to derive
        // an axis-aligned answer that is both coarser and thrown away, and that box is the world
        // bound of the rotated hull, so the cost grows with the cube of vehicle size.
        boolean resolved = rig.swept;
        for (int step = 0; step < steps; step++) {
            double dx = moves.getDouble(step * 3);
            double dy = moves.getDouble(step * 3 + 1);
            double dz = moves.getDouble(step * 3 + 2);
            if (resolved) {
                this.setPos(this.getX() + dx, this.getY() + dy, this.getZ() + dz);
            } else {
                this.move(MoverType.SELF, new Vec3(dx, dy, dz));
            }
            this.translateOBBs(dx, dy, dz);
            if (step < steps - 1) {
                this.supportEntities(rig.carried);
            }
        }
        if (resolved && steps > 0) {
            // The vanilla mover would have done this; portals, fire and the like still have to see
            // the blocks the vehicle ended up in.
            this.checkInsideBlocks();
        }
    }

    private static final int SUPPORT_PROBE_CELLS = 2;

    /** No fluid in any section palette under the box => vanilla's per-cell scan finds none. */
    @Override
    public void updateFluidHeightAndDoFluidPushing() {
        if (SectionScan.maybeAny(level(), getBoundingBox().deflate(0.001), state -> !state.getFluidState().isEmpty())) {
            super.updateFluidHeightAndDoFluidPushing();
        }
    }

    /** = vanilla, minus air cells (entityInside no-op) and the per-cell chunk lookup. */
    @Override
    protected void checkInsideBlocks() {
        AABB box = getBoundingBox();
        BlockPos min = BlockPos.containing(box.minX + 1.0E-7, box.minY + 1.0E-7, box.minZ + 1.0E-7);
        BlockPos max = BlockPos.containing(box.maxX - 1.0E-7, box.maxY - 1.0E-7, box.maxZ - 1.0E-7);
        if (!level().hasChunksAt(min, max)) {
            return;
        }
        SectionScan.forEachNonAir(level(), min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(),
                (pos, state) -> {
                    if (!isAlive()) {
                        return false;
                    }
                    state.entityInside(level(), pos, this);
                    onInsideBlock(state);
                    return true;
                });
    }

    /**
     * Divergence: first collidable cell in the column under the centre, <= {@link #SUPPORT_PROBE_CELLS} down.
     * Vanilla: BlockCollisions over the whole footprint (cost = area), and a sprung hull rides ~0.01 above the
     * blocks => its 1e-6 slab finds none, empty every tick.
     */
    @Override
    protected void checkSupportingBlock(boolean onGround, @Nullable Vec3 movement) {
        if (!onGround) {
            mainSupportingBlockPos = Optional.empty();
            return;
        }
        BlockPos.MutableBlockPos pos = BlockPos.containing(getX(), getBoundingBox().minY, getZ()).mutable();
        for (int i = 0; i <= SUPPORT_PROBE_CELLS; i++, pos.move(Direction.DOWN)) {
            if (!level().getBlockState(pos).getCollisionShape(level(), pos, CollisionContext.of(this)).isEmpty()) {
                mainSupportingBlockPos = Optional.of(pos.immutable());
                return;
            }
        }
        mainSupportingBlockPos = Optional.empty();
    }

    public static class Seat {

        public final Integer seatIndex;
        public final PartUnit<?> partUnit;
        public Integer passengerId;

        public Seat(Integer seatIndex, PartUnit<?> partUnit) {
            this.seatIndex = seatIndex;
            this.partUnit = partUnit;
            this.passengerId = -1;
        }

    }

}
