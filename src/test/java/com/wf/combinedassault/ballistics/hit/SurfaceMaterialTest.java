package com.wf.combinedassault.ballistics.hit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurfaceMaterialTest {

    @Test
    void tokensFromShippedMaterialNames() {
        assertEquals(SurfaceMaterial.RUBBER, SurfaceMaterial.of("04 - Matte black rubber"));
        assertEquals(SurfaceMaterial.GLASS, SurfaceMaterial.of("M983_HEMTT | transparent glass"));
        assertEquals(SurfaceMaterial.GLASS, SurfaceMaterial.of("09 - Coated optical glass"));
        assertEquals(SurfaceMaterial.SOFT, SurfaceMaterial.of("seat_canvas"));
        assertEquals(SurfaceMaterial.RUBBER, SurfaceMaterial.of("rubber"));
    }

    @Test
    void untaggedAndPartialWordsAreSteel() {
        assertEquals(SurfaceMaterial.STEEL, SurfaceMaterial.of("armor_80"));
        assertEquals(SurfaceMaterial.STEEL, SurfaceMaterial.of("rubberized_paint"));
        assertEquals(SurfaceMaterial.STEEL, SurfaceMaterial.of("fibreglass"));
        assertEquals(SurfaceMaterial.STEEL, SurfaceMaterial.of(""));
    }
}
