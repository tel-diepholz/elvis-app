package de.teldiepholz.elvisapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Gleiche Fälle wie applink.test.ts in ELVIS */
public class SendRuleTest {
    @Test
    public void meldetErsteAbZehnMeternUndNachZehnMinuten() {
        assertTrue(SendRule.shouldSend(0, 0, 0, 52.6, 8.37, 0));
        assertFalse(SendRule.shouldSend(52.6, 8.37, 1_000, 52.60005, 8.37, 2_000)); // ~5,6 m
        assertTrue(SendRule.shouldSend(52.6, 8.37, 1_000, 52.6001, 8.37, 2_000)); // ~11 m
        assertFalse(SendRule.shouldSend(52.6, 8.37, 1_000, 52.6, 8.37, 1_000 + SendRule.INTERVAL_MS - 1));
        assertTrue(SendRule.shouldSend(52.6, 8.37, 1_000, 52.6, 8.37, 1_000 + SendRule.INTERVAL_MS));
    }

    @Test
    public void rechnetAbstaendeInMetern() {
        assertEquals(111_195, SendRule.distanceM(0, 0, 1, 0), 10);
    }

    @Test
    public void formatiertIdUndCode() {
        assertEquals("K7M2-QX9P", SendRule.formatDeviceId("K7M2QX9P"));
        assertEquals("123 456", SendRule.formatCode("123456"));
    }
}
