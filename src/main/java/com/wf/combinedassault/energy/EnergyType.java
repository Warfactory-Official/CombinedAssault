package com.wf.combinedassault.energy;

/** What a vehicle runs on. Chosen by {@code energy_info.energy_type}. */
public enum EnergyType {

    /** Burns a fluid held in an on-board tank. */
    FUEL,
    /** Holds a charge, filled from Forge Energy or GregTech EU. */
    ELECTRIC;

    public static EnergyType parse(String raw) {
        if (raw == null) {
            return FUEL;
        }
        return switch (raw.trim().toLowerCase()) {
            case "electric", "electricity", "electrical", "battery", "ev", "fe" -> ELECTRIC;
            default -> FUEL;
        };
    }

}
