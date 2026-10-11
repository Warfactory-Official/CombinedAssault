package com.wf.combinedassault.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.fpv.net.FpvTriggerMessage;
import com.wf.combinedassault.network.message.*;
import com.wf.combinedassault.tow.net.TowCoupleMessage;
import com.wf.combinedassault.uav.net.UavTabletAction;
import com.wf.combinedassault.uav.net.UavTabletState;

public class Channel {

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(CombinedAssault.PROTOCOL);
        registrar.playToServer(ClientVehicleMoveControl.TYPE, ClientVehicleMoveControl.STREAM_CODEC, ClientVehicleMoveControl::handle);
        registrar.playToClient(ServerVehicleState.TYPE, ServerVehicleState.STREAM_CODEC, ServerVehicleState::handle);
        registrar.playToServer(ClientVehicleAction.TYPE, ClientVehicleAction.STREAM_CODEC, ClientVehicleAction::handle);
        registrar.playToClient(ServerVehicleSeatsChange.TYPE, ServerVehicleSeatsChange.STREAM_CODEC, ServerVehicleSeatsChange::handle);
        registrar.playToServer(ClientVehicleChangeSeat.TYPE, ClientVehicleChangeSeat.STREAM_CODEC, ClientVehicleChangeSeat::handle);
        registrar.playToClient(ServerHitVehicleEvent.TYPE, ServerHitVehicleEvent.STREAM_CODEC, ServerHitVehicleEvent::handle);
        registrar.playToClient(ServerEntityDataUpdate.TYPE, ServerEntityDataUpdate.STREAM_CODEC, ServerEntityDataUpdate::handle);
        registrar.playToServer(ClientVehicleSwitchWeapon.TYPE, ClientVehicleSwitchWeapon.STREAM_CODEC, ClientVehicleSwitchWeapon::handle);
        registrar.playToServer(ClientSelectRound.TYPE, ClientSelectRound.STREAM_CODEC, ClientSelectRound::handle);
        registrar.playToClient(ServerVehicleFire.TYPE, ServerVehicleFire.STREAM_CODEC, ServerVehicleFire::handle);
        registrar.playToClient(ServerVehicleWarn.TYPE, ServerVehicleWarn.STREAM_CODEC, ServerVehicleWarn::handle);
        registrar.playToClient(ServerVehicleHurtEntity.TYPE, ServerVehicleHurtEntity.STREAM_CODEC, ServerVehicleHurtEntity::handle);
        registrar.playToServer(ClientMachineMaxAction.TYPE, ClientMachineMaxAction.STREAM_CODEC, ClientMachineMaxAction::handle);
        registrar.playToServer(ClientFigureBoxUpdate.TYPE, ClientFigureBoxUpdate.STREAM_CODEC, ClientFigureBoxUpdate::handle);
        registrar.playToClient(ServerVehicleChangeDisplay.TYPE, ServerVehicleChangeDisplay.STREAM_CODEC, ServerVehicleChangeDisplay::handle);
        registrar.playToServer(ClientVehicleChangeDisplay.TYPE, ClientVehicleChangeDisplay.STREAM_CODEC, ClientVehicleChangeDisplay::handle);
        registrar.playToClient(ServerVehicleExplosion.TYPE, ServerVehicleExplosion.STREAM_CODEC, ServerVehicleExplosion::handle);
        registrar.playToServer(ClientDecorationAction.TYPE, ClientDecorationAction.STREAM_CODEC, ClientDecorationAction::handle);
        registrar.playToClient(ServerDecorationAction.TYPE, ServerDecorationAction.STREAM_CODEC, ServerDecorationAction::handle);
        registrar.playToServer(ClientCamoAction.TYPE, ClientCamoAction.STREAM_CODEC, ClientCamoAction::handle);
        registrar.playToClient(ServerCamoAction.TYPE, ServerCamoAction.STREAM_CODEC, ServerCamoAction::handle);
        registrar.playToClient(ServerCamoSync.TYPE, ServerCamoSync.STREAM_CODEC, ServerCamoSync::handle);
        registrar.playToClient(ServerAddonArmorSync.TYPE, ServerAddonArmorSync.STREAM_CODEC, ServerAddonArmorSync::handle);
        registrar.playToClient(ServerBroadcastEntities.TYPE, ServerBroadcastEntities.STREAM_CODEC, ServerBroadcastEntities::handle);
        registrar.playToServer(ClientRadarAction.TYPE, ClientRadarAction.STREAM_CODEC, ClientRadarAction::handle);
        registrar.playToServer(ClientVehicleSelectPartWeapon.TYPE, ClientVehicleSelectPartWeapon.STREAM_CODEC, ClientVehicleSelectPartWeapon::handle);
        registrar.playToServer(ClientOpenParachute.TYPE, ClientOpenParachute.STREAM_CODEC, ClientOpenParachute::handle);
        registrar.playToServer(FpvTriggerMessage.TYPE, FpvTriggerMessage.STREAM_CODEC, FpvTriggerMessage::handle);
        registrar.playToClient(ServerDeckRejoin.TYPE, ServerDeckRejoin.STREAM_CODEC, ServerDeckRejoin::handle);
        registrar.playToClient(ServerHitEffect.TYPE, ServerHitEffect.STREAM_CODEC, ServerHitEffect::handle);
        registrar.playToServer(TowCoupleMessage.TYPE, TowCoupleMessage.STREAM_CODEC, TowCoupleMessage::handle);
        registrar.playToServer(UavTabletAction.TYPE, UavTabletAction.STREAM_CODEC, UavTabletAction::handle);
        registrar.playToClient(UavTabletState.TYPE, UavTabletState.STREAM_CODEC, UavTabletState::handle);
        registrar.playToServer(com.wf.combinedassault.vehicle.light.LightToggleMessage.TYPE,
                com.wf.combinedassault.vehicle.light.LightToggleMessage.STREAM_CODEC,
                com.wf.combinedassault.vehicle.light.LightToggleMessage::handle);
    }

}
