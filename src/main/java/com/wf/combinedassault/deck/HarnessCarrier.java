package com.wf.combinedassault.deck;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.physics.api.DeckCarrier;

import java.util.List;

/** Carrier vehicles can be harnessed to (mount points, parked decks, flatcars). */
public interface HarnessCarrier extends DeckCarrier {
    List<AbstractVehicle> harnessedVehicles();
}
