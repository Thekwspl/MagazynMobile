package pl.magazyn.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test fun comparesSemanticVersions() {
        assertTrue(isNewerVersion("0.9.9", "0.9.8"))
        assertTrue(isNewerVersion("1.0.0", "0.9.99"))
        assertFalse(isNewerVersion("0.9.8", "0.9.8"))
        assertFalse(isNewerVersion("0.9.7", "0.9.8"))
    }
}
