package dev.xaeroaddons.structureoverlay.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The fair-play presets and how per-field overrides combine with them. */
class ServerConfigTest {

    private static ServerConfig config(String preset) {
        ServerConfig config = new ServerConfig();
        config.preset = preset;
        return config;
    }

    @Test
    void balancedIsTheDefaultAndTheFallback() {
        ServerConfig.Policy byDefault = new ServerConfig().policy();
        assertEquals(6000, byDefault.maxDistance());
        assertEquals(16, byDefault.snapTo());
        assertTrue(byDefault.footprints());
        assertTrue(byDefault.allowRescan());
        assertEquals(600, byDefault.rescanCooldownSeconds());
        assertEquals(2000, byDefault.maxStructures());

        ServerConfig.Policy unknown = config("not-a-preset").policy();
        assertEquals(byDefault, unknown);
    }

    @Test
    void strictRevealsLittle() {
        ServerConfig.Policy policy = config("strict").policy();
        assertTrue(policy.enabled());
        assertEquals(1500, policy.maxDistance());
        assertEquals(32, policy.snapTo());
        assertFalse(policy.footprints());
        assertFalse(policy.allowRescan());
        assertEquals(0, policy.rescanCooldownSeconds());
        assertEquals(400, policy.maxStructures());
    }

    @Test
    void offSendsNothing() {
        assertFalse(config("off").policy().enabled());
    }

    @Test
    void fullIsTheOldBehaviour() {
        ServerConfig.Policy policy = config("full").policy();
        assertTrue(policy.enabled());
        assertEquals(0, policy.maxDistance());
        assertFalse(policy.limitedByDistance());
        assertEquals(0, policy.snapTo());
        assertTrue(policy.footprints());
        assertEquals(0, policy.maxStructures());
    }

    @Test
    void overridesWinOverThePreset() {
        ServerConfig config = config("strict");
        config.maxDistance = 3200;
        config.footprints = true;
        config.disabledTypes = Set.of("minecraft:ancient_city");

        ServerConfig.Policy policy = config.policy();
        assertEquals(3200, policy.maxDistance());
        assertTrue(policy.footprints());
        // untouched fields still come from the preset
        assertEquals(32, policy.snapTo());
        assertEquals(400, policy.maxStructures());
        assertTrue(policy.disabledTypes().contains("minecraft:ancient_city"));
    }

    @Test
    void negativeOverridesAreTreatedAsUnlimited() {
        ServerConfig config = config("balanced");
        config.maxStructures = -5;
        assertEquals(0, config.policy().maxStructures());
    }

    @Test
    void nullTypeListDoesNotBreakThePolicy() {
        ServerConfig config = config("balanced");
        config.disabledTypes = null;
        assertTrue(config.policy().disabledTypes().isEmpty());
    }
}
