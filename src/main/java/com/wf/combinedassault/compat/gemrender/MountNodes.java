package com.wf.combinedassault.compat.gemrender;

import com.wf.combinedassault.barrel.GunBarrel;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.gemrender.gltf.NodeTable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Mount model nodes shown by the carrying weapon's state (current weapon): {@code ammo_can} while loaded or
 * reloading; {@code round@<k>} while more than k rounds remain; {@code barrel@<variant>} ({@code standard} = no
 * variant) while that barrel is on and not being changed.
 */
final class MountNodes {
    static final String CAN = "ammo_can";
    static final String ROUND = "round@";
    static final String BARREL = "barrel@";
    static final String STANDARD = "standard";

    private final int can;
    private final int[] rounds;
    private final int[] roundIndex;
    private final int[] barrels;
    private final String[] barrelVariant;

    private MountNodes(int can, int[] rounds, int[] roundIndex, int[] barrels, String[] barrelVariant) {
        this.can = can;
        this.rounds = rounds;
        this.roundIndex = roundIndex;
        this.barrels = barrels;
        this.barrelVariant = barrelVariant;
    }

    /** Null: no rule node. */
    @Nullable
    static MountNodes of(NodeTable table) {
        int can = -1;
        List<int[]> rounds = new ArrayList<>();
        List<Integer> barrels = new ArrayList<>();
        List<String> variants = new ArrayList<>();
        for (int slot = 0; slot < table.nodeCount(); slot++) {
            String name = table.nodeName(slot);
            if (name == null || !table.isPosable(slot)) {
                continue;
            }
            if (name.equals(CAN)) {
                can = slot;
            } else if (name.startsWith(ROUND)) {
                rounds.add(new int[]{slot, Integer.parseInt(name.substring(ROUND.length()))});
            } else if (name.startsWith(BARREL)) {
                barrels.add(slot);
                variants.add(name.substring(BARREL.length()));
            }
        }
        if (can < 0 && rounds.isEmpty() && barrels.isEmpty()) {
            return null;
        }
        return new MountNodes(can, rounds.stream().mapToInt(r -> r[0]).toArray(),
                rounds.stream().mapToInt(r -> r[1]).toArray(), barrels.stream().mapToInt(Integer::intValue).toArray(),
                variants.toArray(String[]::new));
    }

    void apply(NodeTable table, float[] state, PartUnit<?> part) {
        AbstractVehicleWeapon<?> weapon = part instanceof WeaponUnit unit ? unit.getCurrentWeapon().orElse(null) : null;
        int remain = weapon == null ? 0 : weapon.getRemainAmmo();
        if (can >= 0 && remain <= 0 && (weapon == null || !weapon.isReloading())) {
            table.setScale(state, can, 0, 0, 0);
        }
        for (int i = 0; i < rounds.length; i++) {
            if (remain <= roundIndex[i]) {
                table.setScale(state, rounds[i], 0, 0, 0);
            }
        }
        GunBarrel barrel = weapon == null ? null : weapon.barrel();
        String on = barrel == null ? STANDARD : !barrel.fitted() || barrel.swapping() ? null
                : barrel.variant().orElse(STANDARD);
        for (int i = 0; i < barrels.length; i++) {
            if (!barrelVariant[i].equals(on)) {
                table.setScale(state, barrels[i], 0, 0, 0);
            }
        }
    }
}
