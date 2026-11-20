package de.sourcescout.scout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateServiceTest {

    @Test
    void comparesVersionsNumerically() {
        assertTrue(SelfUpdateService.compareVersions("v0.3.0", "v0.2.1") > 0);
        assertTrue(SelfUpdateService.compareVersions("v0.2.1", "v0.10.0") < 0);
        assertEquals(0, SelfUpdateService.compareVersions("v0.2.1", "0.2.1"));
        assertTrue(SelfUpdateService.compareVersions("v1.0", "v0.9.9") > 0);
        assertTrue(SelfUpdateService.compareVersions("v0.2.1-RC1", "v0.2.1") == 0);
    }
}
