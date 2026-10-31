package com.wf.combinedassault.vehicle;

import java.util.List;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class VehicleConfig {

    public record Common(boolean allowMeleeDamageVehicle, double cameraShakeMultiplier, boolean checkTeamOnEnterVehicle,
                         boolean hitIndicator, boolean overload, double overloadCapacityMultiplier) {
    }

    public record Server(double crewLoaderReloadMultiplier, List<String> thirdPersonClasses, List<String> thirdPersonAllow,
                         List<String> thirdPersonDeny, int leadLoadTicks, int altitudeViewFloor) {
    }

    static final ModConfigSpec COMMON_SPEC;
    static final ModConfigSpec SERVER_SPEC;
    private static final ModConfigSpec.BooleanValue ALLOW_MELEE_DAMAGE_VEHICLE;
    private static final ModConfigSpec.DoubleValue CAMERA_SHAKE_MULTIPLIER;
    private static final ModConfigSpec.BooleanValue CHECK_TEAM_ON_ENTER_VEHICLE;
    private static final ModConfigSpec.BooleanValue HIT_INDICATOR;
    private static final ModConfigSpec.BooleanValue OVERLOAD;
    private static final ModConfigSpec.DoubleValue OVERLOAD_CAPACITY_MULTIPLIER;
    /** Live: gametests flip it; read per seat change, not per tick. */
    private static final ModConfigSpec.BooleanValue CREW_REQUIRED;
    private static final ModConfigSpec.DoubleValue CREW_LOADER_RELOAD_MULTIPLIER;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> THIRD_PERSON_CLASSES;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> THIRD_PERSON_ALLOW;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> THIRD_PERSON_DENY;
    private static final ModConfigSpec.IntValue LEAD_LOAD_TICKS;
    private static final ModConfigSpec.IntValue ALTITUDE_VIEW_FLOOR;
    private static volatile Common common = new Common(false, 1.0, true, true, true, 1.0);
    private static volatile Server server = new Server(0.6, List.of("ground", "aircraft", "naval"), List.of(), List.of(), 40, 0);

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ALLOW_MELEE_DAMAGE_VEHICLE = builder.comment("近战是否能伤害载具")
                .define("allowMeleeDamageVehicle", false);
        CAMERA_SHAKE_MULTIPLIER = builder.comment("镜头晃动强度倍率")
                .defineInRange("cameraShakeMultiplier", 1.0, 0.0, 10.0);
        CHECK_TEAM_ON_ENTER_VEHICLE = builder.comment("载具乘客是否需为同队")
                .define("checkTeamOnEnterVehicle", true);
        HIT_INDICATOR = builder.comment("开启命中提示")
                .define("hitIndicator", true);
        OVERLOAD = builder.comment("是否启用过载机制")
                .define("overload", true);
        OVERLOAD_CAPACITY_MULTIPLIER = builder.comment("过载耐受倍率")
                .defineInRange("overloadCapacityMultiplier", 1.0, 0.1, 100.0);
        COMMON_SPEC = builder.build();

        builder = new ModConfigSpec.Builder();
        builder.push("crew");
        CREW_REQUIRED = builder.comment("Seats keep their pack crew_roles: only a driver drives, only a gunner aims and fires,",
                        "a commander only watches, a manually loaded gun needs a loader aboard. Takes effect on the next seat change.")
                .define("required", false);
        CREW_LOADER_RELOAD_MULTIPLIER = builder.comment("Reload time multiplier for an autoloaded gun while a loader is aboard, and for a manually loaded gun with two loaders.")
                .defineInRange("loaderReloadMultiplier", 0.6, 0.05, 1.0);
        builder.pop();
        builder.push("thirdPerson");
        THIRD_PERSON_CLASSES = builder.comment("Vehicle classes whose crews may use a third-person camera (vehicle orbit view and F5):",
                        "ground (tracked, wheeled, static), aircraft (fixed and rotary wing), naval. [] = first person only.")
                .defineListAllowEmpty("classes", List.of("ground", "aircraft", "naval"), () -> "ground",
                        value -> value instanceof String name && ThirdPersonRules.CLASSES.contains(name));
        THIRD_PERSON_ALLOW = builder.comment("Vehicle ids allowed regardless of class, e.g. \"combinedassault:ah64d\".")
                .defineListAllowEmpty("allow", List.of(), () -> "", value -> value instanceof String);
        THIRD_PERSON_DENY = builder.comment("Vehicle ids refused regardless of class or allow.")
                .defineListAllowEmpty("deny", List.of(), () -> "", value -> value instanceof String);
        builder.pop();
        LEAD_LOAD_TICKS = builder.comment("Players moving faster than 1 block/tick (riding or flying): load the chunks their view will reach",
                        "within this many ticks ahead of time, capped at one view distance. 0 = off.")
                .defineInRange("leadLoadTicks", 40, 0, 400);
        ALTITUDE_VIEW_FLOOR = builder.comment("With Boxy: chunk view shrinks with height over terrain (LOD draws beyond), down to this radius.",
                        "Vehicles past the shrunk view need Boxy trackedEntityTypes \"combinedassault:*\", else they vanish. 0 = off.")
                .defineInRange("altitudeViewFloor", 0, 0, 32);
        SERVER_SPEC = builder.build();
    }

    private VehicleConfig() {
    }

    public static Common common() {
        return common;
    }

    public static Server server() {
        return server;
    }

    public static ModConfigSpec.BooleanValue crewRequired() {
        return CREW_REQUIRED;
    }

    static void onLoad(ModConfigEvent event) {
        Object spec = event.getConfig().getSpec();
        if (spec == COMMON_SPEC) {
            common = new Common(ALLOW_MELEE_DAMAGE_VEHICLE.get(), CAMERA_SHAKE_MULTIPLIER.get(), CHECK_TEAM_ON_ENTER_VEHICLE.get(),
                    HIT_INDICATOR.get(), OVERLOAD.get(), OVERLOAD_CAPACITY_MULTIPLIER.get());
        } else if (spec == SERVER_SPEC) {
            server = new Server(CREW_LOADER_RELOAD_MULTIPLIER.get(), List.copyOf(THIRD_PERSON_CLASSES.get()),
                    List.copyOf(THIRD_PERSON_ALLOW.get()), List.copyOf(THIRD_PERSON_DENY.get()), LEAD_LOAD_TICKS.get(),
                    ALTITUDE_VIEW_FLOOR.get());
        }
    }
}
