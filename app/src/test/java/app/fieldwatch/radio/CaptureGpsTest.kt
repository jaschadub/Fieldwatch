package app.fieldwatch.radio

import android.app.Application
import android.location.Location
import app.fieldwatch.radio.usb.CaptureGps
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CaptureGpsTest {
    private val now = 100_000_000_000L
    private fun fix(age: Long, accuracy: Float) = Location("gps").apply {
        latitude = 12.0; longitude = 34.0; time = 1000
        elapsedRealtimeNanos = now - age * 1_000_000_000L
        this.accuracy = accuracy
    }
    @Test fun readyUsesSameBoundaryAsArchivedFix() {
        val result = CaptureGps.select(listOf(fix(30, 75f)), now)
        assertEquals(CaptureGps.Status.READY, result.status)
        assertEquals(30L, result.ageSeconds)
        assertEquals(75f, result.accuracyM)
        assertEquals(12.0, result.fix!!.getDouble("lat"), 0.0)
    }
    @Test fun staleAndInaccurateFixesNeverGetExported() {
        val stale = CaptureGps.select(listOf(fix(31, 1f)), now)
        assertEquals(CaptureGps.Status.STALE, stale.status)
        assertNull(stale.fix)
        val inaccurate = CaptureGps.select(listOf(fix(1, 76f)), now)
        assertEquals(CaptureGps.Status.INACCURATE, inaccurate.status)
        assertNull(inaccurate.fix)
    }
    @Test fun choosesUsableFixOverMoreAccurateStaleFix() {
        val result = CaptureGps.select(listOf(fix(31, 1f), fix(2, 20f), fix(1, 40f)), now)
        assertEquals(CaptureGps.Status.READY, result.status)
        assertEquals(20f, result.accuracyM)
        assertEquals(2L, result.ageSeconds)
    }
    @Test fun missingFutureAndInvalidFixesCannotClaimReady() {
        assertEquals(CaptureGps.Status.WAITING, CaptureGps.select(emptyList(), now).status)
        assertEquals(CaptureGps.Status.WAITING, CaptureGps.select(listOf(fix(-1, 1f)), now).status)
        assertEquals(CaptureGps.Status.WAITING, CaptureGps.select(listOf(fix(1, 1f).apply { latitude = Double.NaN }), now).status)
        assertNull(CaptureGps.select(listOf(fix(1, Float.NaN)), now).fix)
        assertNull(CaptureGps.select(listOf(fix(1, 1f).apply { removeAccuracy() }), now).fix)
    }
}
