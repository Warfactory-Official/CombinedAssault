package com.wf.combinedassault.vehicle.part.client;

import com.wf.combinedassault.custom.part.data.PartUnitData;
import com.wf.combinedassault.vehicle.part.PartUnit;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class PartNames {

    private PartNames() {
    }

    /** Pack name if it is a translation key, else {@code part.combinedassault.<id>}, else the id spelled out. */
    public static Component name(PartUnit<?> part) {
        return name(part.getData());
    }

    public static Component name(PartUnitData part) {
        String name = part.getName();
        if (name != null && I18n.exists(name)) {
            return Component.translatable(name);
        }
        String id = part.getId();
        String spelled = id.replace('_', ' ');
        return Component.translatableWithFallback("part.combinedassault." + id,
                spelled.substring(0, 1).toUpperCase(Locale.ROOT) + spelled.substring(1));
    }
}
