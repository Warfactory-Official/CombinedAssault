package com.wf.combinedassault.vehicle.loadout.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.gametest.TestLanes;
import com.wf.combinedassault.vehicle.loadout.Loadouts;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.pda.drone.PdaDrones;
import com.wf.wflib.radio.RadioTunePacket;
import com.wf.wflib.radio.RadioVoice;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class RadioGameTest {

    private static final String TEMPLATE = "empty";
    private static final String STATION = "wflib:test";

    private static AbstractVehicle mavic(GameTestHelper helper) {
        return TestLanes.spawn(helper, CombinedAssault.modLocation("mavic"), helper.absoluteVec(new Vec3(1.5, 2, 1.5)));
    }

    private static ServerPlayer player(GameTestHelper helper, AbstractVehicle vehicle, String name) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        Vec3 at = vehicle.position().add(1.5, 0, 0);
        player.moveTo(at.x, at.y, at.z);
        return player;
    }

    /** UAV: no set until a loudspeaker is strapped; then SPEAKER. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aStrappedLoudspeakerGivesAMavicASpeaker(GameTestHelper helper) {
        AbstractVehicle drone = mavic(helper);
        helper.runAfterDelay(2, () -> {
            RadioVoice bare = drone.radioVoice();
            ServerPlayer player = player(helper, drone, "ca_radio_strap");
            boolean strapped = Loadouts.strap(player, drone, new ItemStack(ModItems.LOUDSPEAKER.get()));
            String loadout = drone.loadout().id;
            RadioVoice voice = drone.radioVoice();
            drone.discard();
            helper.assertTrue(bare == null, "bare Mavic voice " + bare);
            helper.assertTrue(strapped && "speaker".equals(loadout) && voice == RadioVoice.SPEAKER,
                    "strapped " + strapped + ", loadout " + loadout + ", voice " + voice);
            helper.succeed();
        });
    }

    /** Stranger refused; PDA linked to the body tunes it; station survives save/load; off = "". */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void tuningNeedsTheCrewOrALinkedPda(GameTestHelper helper) {
        AbstractVehicle drone = mavic(helper);
        helper.runAfterDelay(2, () -> {
            ServerPlayer player = player(helper, drone, "ca_radio_tune");
            Loadouts.strap(player, drone, new ItemStack(ModItems.LOUDSPEAKER.get()));
            boolean stranger = RadioTunePacket.tune(player, drone.getUUID(), STATION);
            ItemStack pda = new ItemStack(ModItems.PDA.get());
            PdaDrones.link(pda, drone.getUUID());
            player.setItemInHand(InteractionHand.MAIN_HAND, pda);
            boolean linked = RadioTunePacket.tune(player, drone.getUUID(), STATION);
            CompoundTag saved = new CompoundTag();
            drone.saveWithoutId(saved);
            boolean off = RadioTunePacket.tune(player, drone.getUUID(), "");
            String afterOff = drone.radioStation();
            drone.load(saved);
            String reloaded = drone.radioStation();
            player.getInventory().clearContent();
            drone.discard();
            helper.assertTrue(!stranger, "stranger tuned");
            helper.assertTrue(linked && off && afterOff.isEmpty(), "linked " + linked + ", off " + off + " -> '"
                    + afterOff + "'");
            helper.assertTrue(STATION.equals(reloaded), "reloaded '" + reloaded + "'");
            helper.succeed();
        });
    }
}
