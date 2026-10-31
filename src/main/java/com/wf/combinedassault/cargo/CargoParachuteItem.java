package com.wf.combinedassault.cargo;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

/** Use on a vehicle: fits a cargo parachute; sneak-use on a fitted one takes it back. */
public class CargoParachuteItem extends Item {

    public CargoParachuteItem() {
        super(new Item.Properties().stacksTo(16));
    }

    /** Server, main hand. @return whether the click was this item's */
    public static boolean tryFit(AbstractVehicle vehicle, Player player, ItemStack stack) {
        if (!(stack.getItem() instanceof CargoParachuteItem)) {
            return false;
        }
        VehicleParachute parachute = vehicle.parachute();
        String message;
        if (!VehicleParachute.accepts(vehicle)) {
            message = "refused";
        } else if (parachute.fitted() && player.isShiftKeyDown()) {
            if (vehicle.parachuteDeployed()) {
                return true;
            }
            parachute.setFitted(false);
            if (!player.getAbilities().instabuild) {
                stack.grow(1);
            }
            message = "removed";
        } else if (parachute.fitted()) {
            message = "already";
        } else {
            parachute.setFitted(true);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            message = "fitted";
            vehicle.level().playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(),
                    SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        player.displayClientMessage(Component.translatable("message.combinedassault.cargo_parachute." + message), true);
        return true;
    }

}
