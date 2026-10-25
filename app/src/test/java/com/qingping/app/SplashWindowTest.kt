package com.qingping.app

import org.junit.Assert.*
import org.junit.Test

class SplashWindowTest {
    @Test fun idleSessionCannotScan() {
        assertFalse(SplashWindow().active(0))
    }
    @Test fun windowHasExclusiveTenSecondDeadline() {
        val w = SplashWindow()
        w.enter("app", 100)
        assertFalse(w.active(99))
        assertTrue(w.active(100))
        assertTrue(w.active(10099))
        assertFalse(w.active(10100))
    }
    @Test fun sameAppPagesAndContentCannotExtendExpiredWindow() {
        val w = SplashWindow()
        w.enter("app", 100)
        val token = w.generation
        assertFalse(w.enter("app", 20000))
        assertEquals(token, w.generation)
        assertFalse(w.active(20000))
    }
    @Test fun switchingAppStartsFreshWindowAndInvalidatesOldRetries() {
        val w = SplashWindow()
        w.enter("a", 0)
        val token = w.generation
        assertTrue(w.enter("b", 20000))
        assertNotEquals(token, w.generation)
        assertEquals("b", w.packageName)
        assertTrue(w.active(20000))
    }
    @Test fun homeOrScreenOffStopsWindowAndAllowsSameAppReentry() {
        val w = SplashWindow()
        w.enter("a", 0)
        val token = w.generation
        w.reset()
        assertFalse(w.active(100))
        assertNotEquals(token, w.generation)
        assertTrue(w.enter("a", 20000))
        assertTrue(w.active(20000))
    }
    @Test fun contentAndRetryEventsCannotStartOrRearmWindow() {
        val w = SplashWindow()
        assertFalse(w.acceptsEvent("a", false, 100))
        assertFalse(w.acceptsEvent(null, false, 100))
        assertTrue(w.acceptsEvent("a", true, 100))
        w.enter("a", 100)
        assertTrue(w.acceptsEvent("a", false, 500))
        assertTrue(w.acceptsEvent(null, false, 500))
        assertFalse(w.acceptsEvent("keyboard", false, 500))
        assertFalse(w.acceptsEvent("a", true, 10100))
        assertFalse(w.acceptsEvent("a", false, 10100))
        assertFalse(w.acceptsEvent(null, false, 10100))
        assertTrue(w.acceptsEvent("b", true, 10100))
        assertFalse(w.acceptsEvent("b", false, 10100))
    }
}
