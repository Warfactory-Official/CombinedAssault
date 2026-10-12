package com.wf.combinedassault.entity.vehicle;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.network.message.ServerVehicleState;
import com.wf.combinedassault.vehicle.control.ControlUnit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Driver-side prediction of one vehicle (docs/client-prediction.md): inputs and post-step snapshots by seq, reconciled
 * against the server's acked snapshot. Client tick thread; statics below {@link #due} are the server's sender.
 */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID)
public final class VehiclePrediction {

    private static final int HISTORY = 64;
    private static final double POSITION = 1.0e-3, VELOCITY = 1.0e-3;
    private static final float ROTATION = 0.05f;
    /** Ticks without a driver input before the vehicle falls back to the server's pose. */
    private static final int IDLE = 2;

    /** Client counters (harness probes): acks matching the prediction, corrected, steps replayed. */
    public static long matched, corrected, replayed;

    private final int[] seqs = new int[HISTORY];
    private final ControlUnit[] inputs = new ControlUnit[HISTORY];
    private final byte[][] states = new byte[HISTORY][];
    /** Input to predict this tick, -1 none. */
    private int pending = -1;
    /** Last predicted seq, -1 none. */
    private int latest = -1;
    private int ack = -1;
    private byte[] ackState;
    private int idle;

    VehiclePrediction(AbstractVehicle vehicle) {
        for (int i = 0; i < HISTORY; i++) {
            seqs[i] = -1;
            inputs[i] = new ControlUnit(vehicle);
        }
    }

    void input(ControlUnit input) {
        int slot = input.seq & (HISTORY - 1);
        seqs[slot] = input.seq;
        inputs[slot].update(input);
        pending = input.seq;
    }

    void received(int ack, byte[] state) {
        if (ack > this.ack) {
            this.ack = ack;
            ackState = state;
        }
    }

    /** Vehicle client tick. False => no input for {@link #IDLE} ticks: stop predicting. */
    boolean tick(AbstractVehicle vehicle) {
        if (pending < 0) {
            return ++idle <= IDLE;
        }
        idle = 0;
        // Synced data (drive state, rotations) written since the last step is the server's, ack-old.
        if (latest >= 0 && seqs[latest & (HISTORY - 1)] == latest) {
            vehicle.restoreSnapshot(states[latest & (HISTORY - 1)]);
        }
        reconcile(vehicle);
        vehicle.controlUnit.update(inputs[pending & (HISTORY - 1)]);
        vehicle.predictStep();
        store(pending, vehicle.snapshot());
        latest = pending;
        pending = -1;
        return true;
    }

    private void reconcile(AbstractVehicle vehicle) {
        byte[] server = ackState;
        if (server == null) {
            return;
        }
        ackState = null;
        int slot = ack & (HISTORY - 1);
        if (seqs[slot] == ack && states[slot] != null && close(states[slot], server)) {
            matched++;
            return;
        }
        corrected++;
        vehicle.restoreSnapshot(server);
        for (int seq = ack + 1; seq <= latest && seqs[seq & (HISTORY - 1)] == seq; seq++) {
            vehicle.controlUnit.update(inputs[seq & (HISTORY - 1)]);
            vehicle.predictStep();
            vehicle.updateOBBs();
            store(seq, vehicle.snapshot());
            replayed++;
        }
    }

    private void store(int seq, byte[] state) {
        states[seq & (HISTORY - 1)] = state;
    }

    /** Leading pose fields of {@link AbstractVehicle#writePrediction}: position, rotation, velocity. */
    private static boolean close(byte[] a, byte[] b) {
        ByteBuffer x = ByteBuffer.wrap(a), y = ByteBuffer.wrap(b);
        for (int i = 0; i < 3; i++) {
            if (!(Math.abs(x.getDouble() - y.getDouble()) <= POSITION)) {
                return false;
            }
        }
        for (int i = 0; i < 3; i++) {
            float d = x.getFloat() - y.getFloat();
            if (!(Math.abs(d - 360 * Math.round(d / 360)) <= ROTATION)) {
                return false;
            }
        }
        for (int i = 0; i < 3; i++) {
            if (!(Math.abs(x.getDouble() - y.getDouble()) <= VELOCITY)) {
                return false;
            }
        }
        return true;
    }

    /** Server tick thread: consumed an input this tick => snapshot to its operator after the step. */
    private static final List<AbstractVehicle> DUE = new ArrayList<>();

    static void due(AbstractVehicle vehicle) {
        if (vehicle.predictable()) {
            DUE.add(vehicle);
        }
    }

    /** After {@link RigidStep} (HIGHEST). */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (DUE.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (Iterator<AbstractVehicle> it = DUE.iterator(); it.hasNext(); ) {
            AbstractVehicle vehicle = it.next();
            if (vehicle.level() != level) {
                continue;
            }
            it.remove();
            if (!vehicle.isRemoved() && vehicle.controlUnit.getOperator() instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, new ServerVehicleState(vehicle.getId(), vehicle.controlUnit.seq,
                        vehicle.snapshot()));
            }
        }
    }
}
