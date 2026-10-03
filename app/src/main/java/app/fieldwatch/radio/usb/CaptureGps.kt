package app.fieldwatch.radio.usb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject

object CaptureGps {
    enum class Status { OFF, PERMISSION, DISABLED, WAITING, STALE, INACCURATE, READY }
    data class Reading(val status: Status, val ageSeconds: Long? = null,
        val accuracyM: Float? = null, val fix: JSONObject? = null)

    @android.annotation.SuppressLint("MissingPermission")
    fun read(context: Context, requested: Boolean): Reading {
        if (!requested) return Reading(Status.OFF)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return Reading(Status.PERMISSION)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!manager.isLocationEnabled) return Reading(Status.DISABLED)
        val fixes = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull {
            runCatching { manager.getLastKnownLocation(it) }.getOrNull()
        }
        return select(fixes, SystemClock.elapsedRealtimeNanos())
    }

    /** The UI and archive use exactly the same age and accuracy requirements. */
    internal fun select(fixes: List<Location>, nowNanos: Long): Reading {
        val valid = fixes.filter { it.elapsedRealtimeNanos in 0..nowNanos &&
            it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0 }
        if (valid.isEmpty()) return Reading(Status.WAITING)
        val fresh = valid.filter { nowNanos - it.elapsedRealtimeNanos <= 30_000_000_000L }
        val usable = fresh.filter { it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy in 0f..75f }
        val fix = usable.minByOrNull { it.accuracy }
            ?: fresh.maxByOrNull { it.elapsedRealtimeNanos }
            ?: valid.maxBy { it.elapsedRealtimeNanos }
        val age = (nowNanos - fix.elapsedRealtimeNanos) / 1_000_000_000L
        val accuracy = fix.accuracy.takeIf { fix.hasAccuracy() && it.isFinite() && it >= 0 }
        val status = when { usable.isNotEmpty() -> Status.READY
            fresh.isEmpty() -> Status.STALE; else -> Status.INACCURATE }
        val json = if (status == Status.READY) JSONObject().put("lat", fix.latitude).put("lon", fix.longitude)
            .put("accuracy_m", fix.accuracy).put("fix_at_ms", fix.time) else null
        return Reading(status, age, accuracy, json)
    }
}
