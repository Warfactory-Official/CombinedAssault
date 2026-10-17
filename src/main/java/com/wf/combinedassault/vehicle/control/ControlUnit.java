package com.wf.combinedassault.vehicle.control;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.network.message.ClientVehicleMoveControl;

import java.util.ArrayDeque;

public class ControlUnit {

    private final AbstractVehicle vehicle;
    private LivingEntity operator;
    private int operatorId;
    public boolean forward;
    public boolean backward;
    public boolean left;
    public boolean right;
    public boolean up;
    public boolean down;
    public boolean leftYaw;
    public boolean rightYaw;
    public boolean functionalUp;
    public boolean functionalDown;
    public boolean functionalLeft;
    public boolean functionalRight;
    public float xRot;
    public boolean xRotKeep;
    public float yRot;
    public boolean yRotKeep;
    /** Fixed-wing cockpit mouse stick: replaces the {@link #xRot}/{@link #yRot} aim; -1..1, x = roll (+ right), y = pitch (+ nose down). */
    public boolean stick;
    public float stickX;
    public float stickY;
    /** FPV turn this tick, body frame, deg: pitch (+ nose down), yaw (+ right). Server-side drivers only. */
    public float turnX;
    public float turnY;
    /** FPV: operator client's attitude, stepped through the previous input; server adopts it. */
    public boolean reported;
    public float reportedXRot;
    public float reportedYRot;
    public float reportedZRot;
    /** Server: seq of the input in effect (ack); client: seq of this input. -1 none. */
    public int seq = -1;
    /** Server: arrived, not yet consumed; one per tick. Cap: past it the oldest is dropped (client reconciles). */
    private final ArrayDeque<ClientVehicleMoveControl> queued = new ArrayDeque<>();
    private static final int QUEUE = 8;

    public ControlUnit(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
    }

    public LivingEntity getOperator() {
        if (operator == null && operatorId != -1) {
            if (vehicle.level().getEntity(operatorId) instanceof LivingEntity livingEntity) {
                operator = livingEntity;
            }
        }
        return operator;
    }

    public void setOperator(LivingEntity operator) {
        if (operator != this.operator) {
            queued.clear();
            seq = -1;
        }
        if (operator == null) {
            this.operator = null;
            this.operatorId = -1;
        } else {
            this.operator = operator;
            this.operatorId = operator.getId();
        }
    }

    public void setOperatorId(int operatorId) {
        this.operatorId = operatorId;
    }

    public void reset() {
        forward = false;
        backward = false;
        left = false;
        right = false;
        up = false;
        down = false;
        leftYaw = false;
        rightYaw = false;
        functionalUp = false;
        functionalDown = false;
        functionalLeft = false;
        functionalRight = false;
        xRot = 0;
        xRotKeep = false;
        yRot = 0;
        yRotKeep = false;
        stick = false;
        stickX = 0;
        stickY = 0;
        turnX = 0;
        turnY = 0;
        reported = false;
    }

    /** No keys, attitude held. */
    public void neutral() {
        reset();
        xRotKeep = true;
        yRotKeep = true;
    }

    public void update(ControlUnit controlUnit) {
        forward = controlUnit.forward;
        backward = controlUnit.backward;
        left = controlUnit.left;
        right = controlUnit.right;
        up = controlUnit.up;
        down = controlUnit.down;
        leftYaw = controlUnit.leftYaw;
        rightYaw = controlUnit.rightYaw;
        functionalUp = controlUnit.functionalUp;
        functionalDown = controlUnit.functionalDown;
        functionalLeft = controlUnit.functionalLeft;
        functionalRight = controlUnit.functionalRight;
        xRot = controlUnit.xRot;
        xRotKeep = controlUnit.xRotKeep;
        yRot = controlUnit.yRot;
        yRotKeep = controlUnit.yRotKeep;
        stick = controlUnit.stick;
        stickX = controlUnit.stickX;
        stickY = controlUnit.stickY;
        reported = controlUnit.reported;
        reportedXRot = controlUnit.reportedXRot;
        reportedYRot = controlUnit.reportedYRot;
        reportedZRot = controlUnit.reportedZRot;
        seq = controlUnit.seq;
    }

    /** Server tick start: next queued input into effect; false => none arrived, last held, ack unchanged. */
    public boolean consume() {
        ClientVehicleMoveControl message = queued.poll();
        if (message == null) {
            return false;
        }
        forward = message.forward;
        backward = message.backward;
        left = message.left;
        right = message.right;
        up = message.up;
        down = message.down;
        leftYaw = message.leftYaw;
        rightYaw = message.rightYaw;
        functionalUp = message.functionalUp;
        functionalDown = message.functionalDown;
        functionalLeft = message.functionalLeft;
        functionalRight = message.functionalRight;
        xRot = message.xRot;
        xRotKeep = message.xRotKeep;
        yRot = message.yRot;
        yRotKeep = message.yRotKeep;
        stick = message.stick;
        stickX = Mth.clamp(message.stickX, -1, 1);
        stickY = Mth.clamp(message.stickY, -1, 1);
        reported = message.reported;
        reportedXRot = message.reportedXRot;
        reportedYRot = message.reportedYRot;
        reportedZRot = message.reportedZRot;
        seq = message.seq;
        return true;
    }

    public static void onClientMessageReceived(ClientVehicleMoveControl message, IPayloadContext ctx) {
        if (ctx.player() != null && ctx.player().level().getEntity(message.vehicleEntityId) instanceof AbstractVehicle vehicle
                && ctx.player() == vehicle.controlUnit.operator) {
            ArrayDeque<ClientVehicleMoveControl> queued = vehicle.controlUnit.queued;
            if (queued.size() >= QUEUE) {
                queued.poll();
            }
            queued.add(message);
        }
    }

}
