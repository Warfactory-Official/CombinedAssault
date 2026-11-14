package com.wf.combinedassault.vehicle.sightline;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wf.combinedassault.ballistics.hit.ModelData;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** {@code camaudit <vehicle>} / {@code camaudit id <vehicle id>}: {@link Sightlines} per camera, worst pose + occluders. */
public final class CamAudit {

    private static final Map<ModelData, SightMesh> MESHES = new WeakHashMap<>();

    private CamAudit() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("camaudit")
                .then(Commands.literal("id").then(Commands.argument("vehicle_id", ResourceLocationArgument.id())
                        .executes(ctx -> print(ctx, of(ctx.getSource().getLevel(),
                                ResourceLocationArgument.getId(ctx, "vehicle_id"))))))
                .then(Commands.argument("vehicle", EntityArgument.entity()).executes(ctx -> print(ctx, of(vehicle(ctx)))));
    }

    private static AbstractVehicle vehicle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "vehicle");
        if (entity instanceof AbstractVehicle vehicle) {
            return vehicle;
        }
        throw EntityArgument.NO_ENTITIES_FOUND.create();
    }

    private static int print(CommandContext<CommandSourceStack> ctx, List<Sightlines.Result> results) {
        for (Sightlines.Result r : results) {
            ctx.getSource().sendSuccess(() -> Component.literal(r.line()), false);
        }
        return results.size();
    }

    /** Fresh vehicle of type {@code id}, never added to the level. */
    public static List<Sightlines.Result> of(Level level, ResourceLocation id) {
        BaseVehicleData<?> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id)
                .orElseThrow(() -> new IllegalArgumentException("no vehicle " + id));
        AbstractVehicle vehicle = data.construct(level, new Vec3(0, 200, 0), 0, 0);
        vehicle.setDisplayId(id);
        vehicle.initData(); // onAddedToLevel does it in the world
        return of(vehicle);
    }

    /** Leaves its station and switch states as found. */
    public static List<Sightlines.Result> of(AbstractVehicle vehicle) {
        if (vehicle.hits() == null) {
            throw new IllegalArgumentException(vehicle.getVehicleId() + ": no hit model, nothing posed to trace");
        }
        BaseVehicleData<?> data = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicle.getVehicleId())
                .orElseThrow(() -> new IllegalArgumentException("no vehicle " + vehicle.getVehicleId()));
        SightMesh mesh;
        synchronized (MESHES) {
            mesh = MESHES.computeIfAbsent(data.getModel(), model -> {
                try {
                    return SightMesh.of(model, data.getHitModel());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return new Sightlines(vehicle, mesh).audit();
    }
}
