package com.wf.combinedassault.api.entity;

import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.parenting.DeckAttachment;
import com.wf.combinedassault.vehicle.parenting.DeckRejoin;

/** Mixed into every entity; parent-frame storage slot. */
public interface VehicleParented {

    @Nullable
    DeckAttachment combinedassault$deckAttachment();

    void combinedassault$setDeckAttachment(@Nullable DeckAttachment attachment);

    @Nullable
    DeckRejoin combinedassault$deckRejoin();

    void combinedassault$setDeckRejoin(@Nullable DeckRejoin rejoin);

}
