package com.wf.combinedassault.all;

import com.wf.combinedassault.vehicle.ThirdPersonRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AllConfigs {

    public static CommonConfig common;
    public static ServerConfig server;
    public static List<String> figureBoxCaptureBlacklist = new ArrayList<>();
    public static List<String> serverBroadcastEntityWhitelist = new ArrayList<>();

    public static void register(ModLoadingContext context, IEventBus modEventBus) {
        Pair<CommonConfig, ModConfigSpec> specPairCommon = new ModConfigSpec.Builder().configure(CommonConfig::new);
        common = specPairCommon.getLeft();
        context.getActiveContainer().registerConfig(ModConfig.Type.COMMON, specPairCommon.getRight());
        Pair<ServerConfig, ModConfigSpec> specPairServer = new ModConfigSpec.Builder().configure(ServerConfig::new);
        server = specPairServer.getLeft();
        context.getActiveContainer().registerConfig(ModConfig.Type.SERVER, specPairServer.getRight());
        modEventBus.addListener((ModConfigEvent.Loading event) -> Cached.refresh());
        modEventBus.addListener((ModConfigEvent.Reloading event) -> Cached.refresh());
        loadExternal();
    }

    /** Volatile: read off vehicle-physics worker threads. */
    public static final class Cached {

        public static volatile boolean invertedCollisionQuery = true;
        public static volatile boolean canDestroyBlock = true;
        public static volatile double ramBreakMomentum = 125;
        public static volatile boolean selfRighting = true;
        public static volatile boolean tumbling = true;
        public static volatile double rightingEffortPerPlayer = 30000.0;
        public static volatile boolean deckParenting = true;
        public static volatile boolean carrierDecks = true;
        public static volatile boolean deckHarness = true;
        public static volatile boolean vehicleMounts = true;
        public static volatile boolean asyncVehiclePhysics = true;
        public static volatile boolean nativeVehiclePhysics = true;
        public static volatile boolean restingVehicleSleep = true;
        public static volatile boolean predictDriving = true;
        public static volatile int physicsThreads = 0;
        public static volatile int camoMaxPieces = 512;
        public static volatile double camoStrokeSpacing = 0.30;
        public static volatile boolean camoBufferedRender = true;
        public static volatile boolean wflibRecon = true;
        public static volatile double wflibRadarRange = 512.0;
        public static volatile double wflibReceiverRange = 1024.0;

        static void refresh() {
            if (common == null) {
                return;
            }
            invertedCollisionQuery = common.invertedCollisionQuery.get();
            canDestroyBlock = common.canDestroyBlock.get();
            ramBreakMomentum = common.ramBreakMomentum.get();
            selfRighting = common.selfRighting.get();
            tumbling = common.tumbling.get();
            rightingEffortPerPlayer = common.rightingEffortPerPlayer.get();
            deckParenting = common.deckParenting.get();
            carrierDecks = common.carrierDecks.get();
            deckHarness = common.deckHarness.get();
            vehicleMounts = common.vehicleMounts.get();
            asyncVehiclePhysics = common.asyncVehiclePhysics.get();
            nativeVehiclePhysics = common.nativeVehiclePhysics.get();
            restingVehicleSleep = common.restingVehicleSleep.get();
            predictDriving = common.predictDriving.get();
            physicsThreads = common.physicsThreads.get();
            camoMaxPieces = common.camoMaxPieces.get();
            camoStrokeSpacing = common.camoStrokeSpacing.get();
            camoBufferedRender = common.camoBufferedRender.get();
            wflibRecon = common.wflibRecon.get();
            wflibRadarRange = common.wflibRadarRange.get();
            wflibReceiverRange = common.wflibReceiverRange.get();
        }

    }

    public static void loadExternal() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("combinedassault");
        Path figureBoxCaptureBlacklistPath = configDir.resolve("figure_box_capture_blacklist.txt");
        Path serverBroadcastEntityWhitelistPath = configDir.resolve("server_broadcast_entity_whitelist.txt");
        try {
            if (Files.notExists(configDir)) Files.createDirectories(configDir);
            if (Files.notExists(figureBoxCaptureBlacklistPath)) {
                List<String> defaultLines = Arrays.asList(
                        "minecraft:ender_dragon",
                        "corpse:corpse",
                        "twilightforest:naga"
                );
                Files.write(figureBoxCaptureBlacklistPath, defaultLines);
            }
            if (Files.notExists(serverBroadcastEntityWhitelistPath)) {
                List<String> defaultLines = Arrays.asList(
                        "superbwarfare:.*"
                );
                Files.write(serverBroadcastEntityWhitelistPath, defaultLines);
            }
            figureBoxCaptureBlacklist.clear();
            figureBoxCaptureBlacklist = Files.readAllLines(figureBoxCaptureBlacklistPath);
            figureBoxCaptureBlacklist.removeIf(line -> line.startsWith("#") || line.trim().isEmpty());
            serverBroadcastEntityWhitelist.clear();
            serverBroadcastEntityWhitelist = Files.readAllLines(serverBroadcastEntityWhitelistPath);
            serverBroadcastEntityWhitelist.removeIf(line -> line.startsWith("#") || line.trim().isEmpty());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static class CommonConfig {

        public final ModConfigSpec.ConfigValue<Boolean> allowMeleeDamageVehicle;
        public final ModConfigSpec.ConfigValue<Boolean> canDestroyBlock;
        public final ModConfigSpec.ConfigValue<Double> ramBreakMomentum;
        public final ModConfigSpec.ConfigValue<Boolean> explosionDropBlock;
        public final ModConfigSpec.ConfigValue<Double> vehicleExplosionHurtPassengerDamage;
        public final ModConfigSpec.ConfigValue<Double> cameraShakeMultiplier;
        public final ModConfigSpec.ConfigValue<Boolean> selfRighting;
        public final ModConfigSpec.ConfigValue<Boolean> tumbling;
        public final ModConfigSpec.ConfigValue<Double> rightingEffortPerPlayer;
        public final ModConfigSpec.ConfigValue<Boolean> invertedCollisionQuery;
        public final ModConfigSpec.ConfigValue<Boolean> arcadeFlightModel;
        public final ModConfigSpec.ConfigValue<Boolean> deckParenting;
        public final ModConfigSpec.ConfigValue<Boolean> carrierDecks;
        public final ModConfigSpec.ConfigValue<Boolean> deckHarness;
        public final ModConfigSpec.ConfigValue<Boolean> vehicleMounts;
        public final ModConfigSpec.ConfigValue<Boolean> asyncVehiclePhysics;
        public final ModConfigSpec.ConfigValue<Boolean> nativeVehiclePhysics;
        public final ModConfigSpec.ConfigValue<Boolean> restingVehicleSleep;
        public final ModConfigSpec.ConfigValue<Boolean> predictDriving;
        public final ModConfigSpec.ConfigValue<Integer> physicsThreads;
        public final ModConfigSpec.ConfigValue<Integer> camoMaxPieces;
        public final ModConfigSpec.ConfigValue<Double> camoStrokeSpacing;
        public final ModConfigSpec.ConfigValue<Boolean> camoBufferedRender;
        public final ModConfigSpec.ConfigValue<Boolean> wflibRecon;
        public final ModConfigSpec.ConfigValue<Double> wflibRadarRange;
        public final ModConfigSpec.ConfigValue<Double> wflibReceiverRange;
        public final ModConfigSpec.ConfigValue<Boolean> infiniteFuel;
        public final ModConfigSpec.ConfigValue<List<? extends String>> fuelNameWhiteList;
        public final ModConfigSpec.ConfigValue<Boolean> parkedResupply;
        public final ModConfigSpec.ConfigValue<Boolean> hitIndicator;
        public final ModConfigSpec.ConfigValue<Boolean> checkTeamOnEnterVehicle;
        public final ModConfigSpec.ConfigValue<Boolean> figureBoxOnlyCaptureVehicle;
        public final ModConfigSpec.ConfigValue<Boolean> overload;
        public final ModConfigSpec.ConfigValue<Double> overloadCapacityMultiplier;
        public final ModConfigSpec.ConfigValue<Integer> aerobaticSmokeR;
        public final ModConfigSpec.ConfigValue<Integer> aerobaticSmokeG;
        public final ModConfigSpec.ConfigValue<Integer> aerobaticSmokeB;

        public CommonConfig(ModConfigSpec.Builder builder) {
            allowMeleeDamageVehicle = builder.comment("近战是否能伤害载具")
                    .define("allowMeleeDamageVehicle", false);
            canDestroyBlock = builder.comment("载具是否能破坏方块")
                    .define("canDestroyBlock", true);
            ramBreakMomentum = builder.comment("撞击破坏方块所需的动量（kg·格/tick，每点方块硬度），数值越小越容易撞碎")
                    .defineInRange("ramBreakMomentum", 125.0, 0.0, Double.MAX_VALUE);
            explosionDropBlock = builder.comment("爆炸是否掉落方块")
                    .define("explosionDropBlock", true);
            vehicleExplosionHurtPassengerDamage = builder.comment("载具爆炸对乘客造成的伤害值")
                    .defineInRange("vehicleExplosionHurtPassengerDamage", 512.0, 0.0, Double.MAX_VALUE);
            cameraShakeMultiplier = builder.comment("镜头晃动强度倍率")
                    .defineInRange("cameraShakeMultiplier", 1.0, 0.0, 10.0);
            selfRighting = builder.comment("倾角过大时是否自动回正")
                    .define("selfRighting", true);
            tumbling = builder.comment("载具可自由翻滚（关闭自动回正等保护，改用真实角速度）")
                    .define("tumbling", true);
            rightingEffortPerPlayer = builder.comment("每名潜行玩家能推翻的车体量（质量kg×主碰撞体积），需要 ceil(质量×体积/此值) 人同时推；0为禁用手动翻正")
                    .defineInRange("rightingEffortPerPlayer", 30000.0, 0.0, Double.MAX_VALUE);
            invertedCollisionQuery = builder.comment("碰撞检测使用反向查询（按接触面积计算，而非车体表面采样）")
                    .define("invertedCollisionQuery", true);
            arcadeFlightModel = builder.comment("飞行器使用街机风格气动模型（贴合机头方向的阻力）")
                    .define("arcadeFlightModel", true);
            deckParenting = builder.comment("实体站上载具时使用父子坐标系承载（关闭则回退为推出修正）")
                    .define("deckParenting", true);
            carrierDecks = builder.comment("载具可停靠在其他载具声明的甲板上（模型中名为 deck 的骨骼）")
                    .define("carrierDecks", true);
            deckHarness = builder.comment("停放在甲板上的载具进入休眠，位姿直接由母舰推导（纯性能优化）")
                    .define("deckHarness", true);
            vehicleMounts = builder.comment("载具可安装到其他载具声明的挂载点上（如皮卡车斗上的迫击炮）")
                    .define("vehicleMounts", true);
            asyncVehiclePhysics = builder.comment("载具物理在工作线程上求解（结果按刻内顺序确定性应用）")
                    .define("asyncVehiclePhysics", true);
            nativeVehiclePhysics = builder.comment("载具与地形的碰撞检测使用本地库（结果与Java路径逐位一致；无本地库的平台自动回退）")
                    .define("nativeVehiclePhysics", true);
            restingVehicleSleep = builder.comment("静止且无人的载具降低物理求解频率（纯性能优化）")
                    .define("restingVehicleSleep", true);
            predictDriving = builder.comment("Client: predict the vehicle you drive (no input round trip); server stays authoritative and corrects")
                    .define("predictDriving", true);
            physicsThreads = builder.comment("物理求解线程数，0为自动（约为CPU核心数的一半）")
                    .defineInRange("physicsThreads", 0, 0, 64);
            camoMaxPieces = builder.comment("单个载具的伪装碎片上限")
                    .defineInRange("camoMaxPieces", 512, 0, 4096);
            camoStrokeSpacing = builder.comment("涂刷伪装时相邻碎片的最小间距（方块）")
                    .defineInRange("camoStrokeSpacing", 0.30, 0.05, 4.0);
            camoBufferedRender = builder.comment("伪装使用静态顶点缓冲绘制（每个骨骼一次绘制，与碎片数量无关）；关闭则改为逐帧重放已烘焙的顶点")
                    .define("camoBufferedRender", true);
            builder.push("wflib");
            wflibRecon = builder.comment("Contribute vehicles to the WFLib sensor net, and let a vehicle's radar join it as a sensor.",
                            "Off keeps every radar on its own private scan.")
                    .define("recon", true);
            wflibRadarRange = builder.comment("Ceiling on the base range a vehicle radar contributes to the sensor net, in blocks.",
                            "The net detects a reference target at this range and scales by the fourth root of signature, so a raw max_scan_distance of a few thousand would make one fighter outrange every fixed installation in the game.")
                    .defineInRange("radarRange", 512.0, 16.0, 4096.0);
            wflibReceiverRange = builder.comment("How far an unaffiliated vehicle looks for a recon hub to adopt as its network, in blocks.",
                            "Only used when the vehicle cannot say whose it is. A vehicle on a team or in a faction is on that network wherever it goes, because a relay carrying a picture beyond its owner's ground presence is what relays are for.")
                    .defineInRange("receiverRange", 1024.0, 0.0, 8192.0);
            builder.pop();
            infiniteFuel = builder.comment("无需燃油仍可运作")
                    .define("infiniteFuel", false);
            fuelNameWhiteList = builder.comment("允许视作燃油的液体（仅用于未在数据包中声明 fuels 的载具）")
                    .defineList("fuelNameWhiteList", Arrays.asList("fuel", "gas", "lava"), obj -> obj instanceof String);
            parkedResupply = builder.comment("载具停在储罐或供电方块上时自动加注燃料/充电（能量系统无法向实体推送，只能由载具向下抽取）")
                    .define("parkedResupply", true);
            hitIndicator = builder.comment("开启命中提示")
                    .define("hitIndicator", true);
            checkTeamOnEnterVehicle = builder.comment("载具乘客是否需为同队")
                    .define("checkTeamOnEnterVehicle", true);
            figureBoxOnlyCaptureVehicle = builder.comment("手办盒是否只能收纳载具")
                    .define("figureBoxOnlyCaptureVehicle", false);
            overload = builder.comment("是否启用过载机制")
                    .define("overload", true);
            overloadCapacityMultiplier = builder.comment("过载耐受倍率")
                    .defineInRange("overloadCapacityMultiplier", 1.0, 0.1, 100.0);
            aerobaticSmokeR = builder.comment("特技飞行烟雾颜色 - 红")
                    .defineInRange("aerobaticSmokeR", 255, 0, 255);
            aerobaticSmokeG = builder.comment("特技飞行烟雾颜色 - 绿")
                    .defineInRange("aerobaticSmokeG", 0, 0, 255);
            aerobaticSmokeB = builder.comment("特技飞行烟雾颜色 - 蓝")
                    .defineInRange("aerobaticSmokeB", 0, 0, 255);
        }

    }

    public static class ServerConfig {

        public final ModConfigSpec.ConfigValue<Integer> serverBroadcastEntitiesInterval;
        public final ModConfigSpec.ConfigValue<Boolean> crewRequired;
        public final ModConfigSpec.ConfigValue<Double> crewLoaderReloadMultiplier;
        public final ModConfigSpec.ConfigValue<List<? extends String>> thirdPersonClasses;
        public final ModConfigSpec.ConfigValue<List<? extends String>> thirdPersonAllow;
        public final ModConfigSpec.ConfigValue<List<? extends String>> thirdPersonDeny;
        public final ModConfigSpec.ConfigValue<Integer> leadLoadTicks;
        public final ModConfigSpec.ConfigValue<Integer> altitudeViewFloor;

        public ServerConfig(ModConfigSpec.Builder builder) {
            serverBroadcastEntitiesInterval = builder.comment("服务端向玩家广播其所在世界超视距实体的时间间隔（单位：tick）")
                    .defineInRange("serverBroadcastEntitiesInterval", 5, 1, 72000);
            builder.push("crew");
            crewRequired = builder.comment("Seats keep their pack crew_roles: only a driver drives, only a gunner aims and fires,",
                            "a commander only watches, a manually loaded gun needs a loader aboard. Takes effect on the next seat change.")
                    .define("required", false);
            crewLoaderReloadMultiplier = builder.comment("Reload time multiplier for an autoloaded gun while a loader is aboard, and for a manually loaded gun with two loaders.")
                    .defineInRange("loaderReloadMultiplier", 0.6, 0.05, 1.0);
            builder.pop();
            builder.push("thirdPerson");
            thirdPersonClasses = builder.comment("Vehicle classes whose crews may use a third-person camera (vehicle orbit view and F5):",
                            "ground (tracked, wheeled, static), aircraft (fixed and rotary wing), naval. [] = first person only.")
                    .defineListAllowEmpty("classes", List.of("ground", "aircraft", "naval"), () -> "ground",
                            value -> value instanceof String name && ThirdPersonRules.CLASSES.contains(name));
            thirdPersonAllow = builder.comment("Vehicle ids allowed regardless of class, e.g. \"combinedassault:ah64d\".")
                    .defineListAllowEmpty("allow", List.of(), () -> "", value -> value instanceof String);
            thirdPersonDeny = builder.comment("Vehicle ids refused regardless of class or allow.")
                    .defineListAllowEmpty("deny", List.of(), () -> "", value -> value instanceof String);
            builder.pop();
            leadLoadTicks = builder.comment("Players moving faster than 1 block/tick (riding or flying): load the chunks their view will reach",
                            "within this many ticks ahead of time, capped at one view distance. 0 = off.")
                    .defineInRange("leadLoadTicks", 40, 0, 400);
            altitudeViewFloor = builder.comment("With Boxy: chunk view shrinks with height over terrain (LOD draws beyond), down to this radius.",
                            "Vehicles past the shrunk view need Boxy trackedEntityTypes \"combinedassault:*\", else they vanish. 0 = off.")
                    .defineInRange("altitudeViewFloor", 0, 0, 32);
        }

    }

}
