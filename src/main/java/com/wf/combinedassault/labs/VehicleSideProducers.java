package com.wf.combinedassault.labs;

import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.labs.api.LabLauncher;
import com.wf.wflib.labs.api.LabProducer;
import com.wf.wflib.labs.api.LabSink;
import com.wf.wflib.labs.api.LabTable;

import java.util.List;
import java.util.Map;

/** Side tables of {@code combinedassault:vehicle}: launcher -> sweep launcher, and the grid actually swept. */
final class VehicleSideProducers {

    private VehicleSideProducers() {
    }

    static final class Launchers implements LabProducer {
        static final LabTable TABLE = LabTable.of("combinedassault:vehicle_launchers")
                .dimStr("launcher").measureStr("sweep").measureStr("round")
                .measure("muzzle_velocity_mps", "m/s").measure("damage_scale", "")
                .build();

        @Override
        public String domain() {
            return "vehicle";
        }

        @Override
        public String id() {
            return TABLE.name();
        }

        @Override
        public LabTable table() {
            return TABLE;
        }

        @Override
        public void produce(LabContext ctx, LabSink sink) {
            VehicleProducer.Groups g = VehicleProducer.groups(ctx);
            Map<String, LabLauncher> byId = new java.util.HashMap<>();
            for (LabLauncher l : ctx.launchers()) {
                byId.put(l.id(), l);
            }
            g.sweepOf().forEach((id, sweep) -> {
                LabLauncher l = byId.get(id);
                sink.row(id, sweep, l.roundId(), l.muzzleVelocity(), l.damageScale());
            });
        }
    }

    static final class GridTable implements LabProducer {
        static final LabTable TABLE = LabTable.of("combinedassault:vehicle_grid")
                .dimStr("what").measure("value", "").measureStr("note")
                .build();

        @Override
        public String domain() {
            return "vehicle";
        }

        @Override
        public String id() {
            return TABLE.name();
        }

        @Override
        public LabTable table() {
            return TABLE;
        }

        @Override
        public void produce(LabContext ctx, LabSink sink) {
            List<VehicleRig> rigs = VehicleProducer.rigs(ctx);
            int keys = VehicleProducer.groups(ctx).reps().size();
            VehicleProducer.Grid grid = VehicleProducer.grid(ctx, rigs, keys);
            sink.row("vehicles", rigs.size(), "");
            sink.row("sweep_keys", keys, "distinct round x muzzle velocity x damage scale");
            sink.row("ranges", grid.ranges().length, java.util.Arrays.toString(grid.ranges()));
            sink.row("azimuths", grid.azimuths().length, "step " + (grid.azimuths().length > 1 ? grid.azimuths()[1] : 0));
            sink.row("elevations", grid.elevations().length, java.util.Arrays.toString(grid.elevations()));
            sink.row("aims", grid.parts() ? 1 : 0, grid.parts() ? "hull + parts" : "hull");
            sink.row("max_rows", ctx.paramNum("max_rows", 1.5e6), "");
            sink.row("coarsened", grid.coarsened().isEmpty() ? 0 : 1, grid.coarsened());
            for (VehicleRig rig : rigs) {
                rig.vehicle.discard();
            }
        }
    }
}
