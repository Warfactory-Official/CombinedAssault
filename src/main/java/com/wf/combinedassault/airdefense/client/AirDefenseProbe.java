package com.wf.combinedassault.airdefense.client;

import com.wf.combinedassault.airdefense.AirDefense;
import com.wf.combinedassault.airdefense.AirDefenseActions;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.part.client.PartNames;
import com.wf.wflib.probe.ProbeAction;
import com.wf.wflib.probe.ProbeContext;
import com.wf.wflib.probe.ProbeInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Hull node rows: owner, settings and state of each {@code air_defense} station; all from server data. */
public final class AirDefenseProbe {

    private static final String KEY = "air_defense.combinedassault.";

    private AirDefenseProbe() {
    }

    /** {@code ctx.data()} = server data of {@code vehicle}. */
    public static void append(ProbeInfo info, ProbeContext ctx, AbstractVehicle vehicle) {
        CompoundTag data = ctx.data().getCompound(AirDefenseActions.DATA);
        if (data.isEmpty()) {
            return;
        }
        String side = data.getString("Side");
        info.entry(Component.translatable(KEY + "owner"),
                side.isEmpty() ? Component.translatable(KEY + "unowned") : Component.literal(side));
        boolean may = data.getBoolean("May");
        Component refused = Component.translatable(KEY + "not_yours");
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (!(part instanceof WeaponUnit unit) || unit.airDefense() == null) {
                continue;
            }
            CompoundTag station = data.getCompound(Integer.toString(unit.getIndex()));
            AirDefense.Mode mode = AirDefense.Mode.valueOf(station.getString("Mode"));
            AirDefense.Source source = AirDefense.Source.valueOf(station.getString("Source"));
            int salvo = station.getInt("Salvo");
            info.entry(PartNames.name(unit), name("mode", mode));
            info.entry(Component.translatable(KEY + "status"), mode == AirDefense.Mode.AUTO
                    ? Component.translatable(KEY + "state." + station.getString("State").toLowerCase(Locale.ROOT))
                    : Component.translatable(KEY + "state.manual"));
            boolean beam = station.getBoolean("Beam");
            if (!beam) {
                info.entry(Component.translatable(KEY + "interceptors"), Component.literal(
                        Integer.toString(station.getInt("Interceptors"))));
            }
            int index = unit.getIndex();
            AirDefense.Mode nextMode = AirDefense.Mode.values()[(mode.ordinal() + 1) % AirDefense.Mode.values().length];
            AirDefense.Source nextSource =
                    AirDefense.Source.values()[(source.ordinal() + 1) % AirDefense.Source.values().length];
            int nextSalvo = salvo % AirDefense.MAX_SALVO + 1;
            offer(info, may, refused, ProbeAction.of(AirDefenseActions.MODE,
                    AirDefenseActions.arg(index, nextMode.ordinal()),
                    Component.translatable(KEY + "set_mode", name("mode", nextMode))));
            offer(info, may, refused, ProbeAction.of(AirDefenseActions.SOURCE,
                    AirDefenseActions.arg(index, nextSource.ordinal()),
                    Component.translatable(KEY + "set_source", name("source", nextSource),
                            name("source", source))));
            if (!beam) {
                offer(info, may, refused, ProbeAction.of(AirDefenseActions.SALVO, AirDefenseActions.arg(index, nextSalvo),
                        Component.translatable(KEY + "set_salvo", nextSalvo, salvo)));
            }
        }
        if (data.getBoolean("Claimed")) {
            offer(info, may, refused, ProbeAction.of(AirDefenseActions.CLAIM, 0,
                    Component.translatable(KEY + "release")));
        } else {
            ProbeAction claim = ProbeAction.of(AirDefenseActions.CLAIM, 1, Component.translatable(KEY + "claim"));
            offer(info, may, refused, data.getBoolean("HasSide") ? claim
                    : claim.unavailable(Component.translatable(KEY + "no_side")));
        }
    }

    private static void offer(ProbeInfo info, boolean may, Component refused, ProbeAction action) {
        info.action(may ? action : action.unavailable(refused));
    }

    private static Component name(String kind, Enum<?> value) {
        return Component.translatable(KEY + kind + "." + value.name().toLowerCase(Locale.ROOT));
    }
}
