package app.fieldwatch.radio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.fieldwatch.BuildConfig
import app.fieldwatch.domain.Observation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class UsbCaptureState(
    val active: Boolean = false, val status: String = "USB capture off", val packets: Long = 0,
    val invalid: Long = 0, val gaps: Long = 0, val deviceDrops: Long = 0, val bytes: Long = 0,
    val file: String? = null,
    val installing: Boolean = false, val installPercent: Int = 0,
    val awaitingUsbPermission: Boolean = false,
    val elapsedMs: Long = 0, val mode: String? = null, val channel: Int = 0, val gpsIncluded: Boolean = false,
)
data class UsbReceiver(val id: Int, val label: String)

/** One explicit session, owned by ScanService. USB permission never implies permission to auto-record. */
class UsbCaptureController(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(UsbCaptureState())
    val state = mutable.asStateFlow()
    val archive = CaptureArchive(File(context.filesDir, "usb-captures"))
    private var job: Job? = null
    private var pending: Request? = null
    private var permissionIntent: PendingIntent? = null
    private val permissionHandler = Handler(Looper.getMainLooper())
    private var permissionTimeout: Runnable? = null
    private var installRequest: Request? = null
    private var deviceId: Int? = null
    private val permissionAction = "${context.packageName}.USB_CAPTURE_PERMISSION"
    private data class Request(val device: UsbDevice, val mode: CaptureProtocol.Mode, val channel: Int,
        val label: String, val gps: Boolean, val observe: (Observation) -> Unit,
        val install: Boolean = false, val manualBoot: Boolean = false,
        val permissionToken: String = UUID.randomUUID().toString())

    init {
        val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (device.deviceId == deviceId) stop("USB disconnected")
                } else if (intent.action == permissionAction) {
                    val request = pending ?: return
                    // Immutable PendingIntents discard the system's EXTRA_DEVICE/GRANTED fill-in.
                    // Match our own token, then ask UsbManager about the current device/permission.
                    if (intent.getStringExtra(PERMISSION_TOKEN) != request.permissionToken) return
                    clearPendingPermission()
                    val device = manager.deviceList[request.device.deviceName]
                    if (device == null || device.deviceId != request.device.deviceId || !CdcSerial.supported(device)) {
                        stop("USB disconnected. Reconnect and retry.")
                    } else if (manager.hasPermission(device)) {
                        mutable.value = mutable.value.copy(awaitingUsbPermission = false)
                        launchRequest(request.copy(device = device))
                    } else stop("USB permission denied")
                }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun receivers(): List<UsbReceiver> = manager.deviceList.values.filter(CdcSerial::supported)
        .sortedBy { it.deviceId }.map { UsbReceiver(it.deviceId, "ESP32-C3 USB · ${it.deviceId}") }

    fun start(id: Int, mode: CaptureProtocol.Mode, channel: Int, label: String, gps: Boolean,
        observe: (Observation) -> Unit) {
        if (mutable.value.active) return
        val device = manager.deviceList.values.firstOrNull { it.deviceId == id && CdcSerial.supported(it) }
        if (device == null) { mutable.value = UsbCaptureState(status = "Connect an ESP32-C3 native USB receiver"); return }
        require(channel in 0..11)
        deviceId = id
        mutable.value = UsbCaptureState(active = true, status = "Connecting USB receiver…",
            mode = mode.name, channel = channel, gpsIncluded = gps)
        val request = Request(device, mode, if (mode == CaptureProtocol.Mode.WIFI) channel else 0,
            label.filterNot(Char::isISOControl).take(64), gps, observe)
        requestAccess(request)
    }

    /** Called only after the user confirms replacement of firmware and settings in the installer dialog. */
    fun install(id: Int, manualBoot: Boolean) {
        if (mutable.value.active) return
        val device = manager.deviceList.values.firstOrNull { it.deviceId == id && CdcSerial.supported(it) }
        if (device == null) { mutable.value = UsbCaptureState(status = "Connect the C3 receiver first"); return }
        deviceId = id
        mutable.value = UsbCaptureState(active = true, installing = true, status = "Preparing firmware installer…")
        requestAccess(Request(device, CaptureProtocol.Mode.WIFI, 0, "", false, {}, true, manualBoot))
    }

    private fun requestAccess(request: Request) {
        val device = request.device
        if (manager.hasPermission(device)) launchRequest(request)
        else {
            pending = request
            mutable.value = mutable.value.copy(awaitingUsbPermission = true, status = "Allow USB access in the Android dialog")
            try {
                val callback = PendingIntent.getBroadcast(context, device.deviceId,
                    Intent(permissionAction).setPackage(context.packageName)
                        .putExtra(PERMISSION_TOKEN, request.permissionToken),
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                permissionIntent = callback
                permissionTimeout = Runnable {
                    if (pending?.permissionToken == request.permissionToken)
                        stop("USB permission timed out. Reconnect the C3 and allow USB access, then retry.")
                }.also { permissionHandler.postDelayed(it, 90_000) }
                manager.requestPermission(device, callback)
            } catch (_: Exception) { stop("Could not request USB permission") }
        }
    }

    private fun clearPendingPermission() {
        pending = null
        permissionTimeout?.let(permissionHandler::removeCallbacks)
        permissionTimeout = null
        permissionIntent?.cancel()
        permissionIntent = null
    }

    fun cancelUsbPermissionRequest() {
        if (pending != null) stop("USB permission request cancelled. Retry when ready.")
    }

    private fun launchRequest(request: Request) {
        if (!request.install) { launchCapture(request); return }
        installRequest = request
        mutable.value = mutable.value.copy(status = "Starting firmware installer…")
        try {
            ContextCompat.startForegroundService(context, Intent(context, UsbFirmwareService::class.java)
                .putExtra("device", request.device.deviceId))
        } catch (_: Exception) { stop("Keep Fieldwatch-NG open and retry the installer") }
    }

    fun stopCapture(reason: String = "USB capture stopped") {
        if (!mutable.value.installing) stop(reason)
    }

    fun stop(reason: String = "USB capture stopped") {
        clearPendingPermission()
        installRequest = null
        deviceId = null
        val running = job
        if (running != null && !running.isCompleted) {
            mutable.value = mutable.value.copy(status = reason)
            running.cancel(CancellationException(reason))
        } else mutable.value = mutable.value.copy(active = false, installing = false, awaitingUsbPermission = false, status = reason)
    }

    internal fun runInstallation(id: Int): Job? {
        val request = installRequest ?: return null
        if (id != request.device.deviceId || !mutable.value.active || !mutable.value.installing) return null
        installRequest = null
        val entered = AtomicBoolean(false)
        val installJob = scope.launch(start = CoroutineStart.LAZY) {
            entered.set(true)
            var port: CdcSerial? = null
            var wake: PowerManager.WakeLock? = null
            var verified = false
            var status = "Firmware installation stopped"
            try {
                mutable.value = mutable.value.copy(status = "Checking bundled firmware…")
                val firmware = ReceiverFirmware.load(context.assets::open)
                wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fieldwatch:UsbInstall").apply { acquire(610_000) }
                val started = SystemClock.elapsedRealtime()
                val task = currentCoroutineContext()
                mutable.value = mutable.value.copy(status = "Opening USB receiver…")
                port = CdcSerial(manager, request.device)
                val serial = port
                val installer = Esp32C3Installer(serial, checkActive = {
                    task.ensureActive()
                    check(SystemClock.elapsedRealtime() - started < 600_000) { "Installation timed out. Reconnect and reinstall." }
                })
                installer.install(firmware.parts, request.manualBoot) { phase, percent ->
                    if (phase != mutable.value.status || percent != mutable.value.installPercent)
                        mutable.value = UsbCaptureState(active = true, installing = true, status = phase, installPercent = percent)
                }
                verified = true
                status = "Firmware verified. Reconnect USB or tap RESET, then start capture."
                mutable.value = mutable.value.copy(status = "Rebooting receiver…", installPercent = 98)
                installer.reboot()
                val lines = CaptureLineReader()
                val bytes = ByteArray(4096)
                val deadline = SystemClock.elapsedRealtime() + 4000
                var ready = false
                var lastHello = 0L
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    task.ensureActive()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastHello > 500) { serial.write("HELLO\n"); lastHello = now }
                    val n = serial.read(bytes)
                    if (n > 0) lines.feed(bytes, n) { line ->
                        val hello = CaptureProtocol.parse(line) as? CaptureProtocol.Message.Hello
                        if (hello?.firmware == firmware.version) ready = true
                    }
                }
                if (ready) status = "Firmware installed and verified. Receiver ready; start capture when ready."
            } catch (e: Exception) {
                status = if (verified) "Firmware verified. Reconnect USB or tap RESET, then start capture."
                    else "Install failed: ${e.message.orEmpty().take(220)}"
            } finally {
                runCatching { port?.close() }
                runCatching { if (wake?.isHeld == true) wake?.release() }
                context.stopService(Intent(context, UsbFirmwareService::class.java))
                mutable.value = UsbCaptureState(status = status, installPercent = if (verified) 100 else 0)
            }
        }
        job = installJob
        installJob.invokeOnCompletion {
            if (!entered.get() && job === installJob && mutable.value.installing) {
                context.stopService(Intent(context, UsbFirmwareService::class.java))
                mutable.value = UsbCaptureState(status = "Installation interrupted. Reconnect and reinstall.")
            }
        }
        installJob.start()
        return installJob
    }

    private fun launchCapture(request: Request) {
        val entered = AtomicBoolean(false)
        val captureJob = scope.launch(start = CoroutineStart.LAZY) {
            entered.set(true)
            var port: CdcSerial? = null
            var session: CaptureArchive.Session? = null
            var finalStatus = "USB capture stopped"
            var packets = 0L; var invalid = 0L; var gaps = 0L; var drops = 0L
            val guard = CaptureSessionGuard(request.mode, request.channel)
            val startedElapsed = SystemClock.elapsedRealtime()
            var framingErrors = 0L
            var wake: PowerManager.WakeLock? = null
            try {
                wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fieldwatch:UsbCapture").apply {
                        acquire(MAX_SESSION_MS + 10_000)
                    }
                port = CdcSerial(manager, request.device)
                val serial = port
                val lines = CaptureLineReader()
                val buffer = ByteArray(4096)
                val connectedAt = SystemClock.elapsedRealtime()
                var lastReceive = connectedAt
                var lastPing = 0L; var lastPublish = 0L
                var lastGps = 0L; var gps: JSONObject? = null
                serial.write("STOP\nHELLO\n")
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val now = SystemClock.elapsedRealtime()
                    check(now - connectedAt < MAX_SESSION_MS) { "30-minute capture limit reached" }
                    if (request.gps && now - lastGps >= 1000) { gps = CaptureGps.read(context, true).fix; lastGps = now }
                    if (now - lastPing >= 1000) { serial.write("PING\n"); lastPing = now }
                    val count = serial.read(buffer)
                    if (count > 0) lines.feed(buffer, count) { line ->
                        val message = CaptureProtocol.parse(line)
                        if (message == null) { invalid++; return@feed }
                        lastReceive = SystemClock.elapsedRealtime()
                        when (message) {
                            is CaptureProtocol.Message.Hello -> {
                                guard.hello(message)
                                session = archive.start(JSONObject().apply {
                                    put("type", "session"); put("v", 1); put("source", "esp32-c3-usb")
                                    put("firmware", guard.firmware); put("app_version", BuildConfig.VERSION_NAME)
                                    put("started_at_ms", System.currentTimeMillis())
                                    put("mode", request.mode.name); put("channel", request.channel)
                                    put("label", request.label)
                                    put("wifi_fcs_included", false); put("gps_included", request.gps)
                                    put("usb_device_id", request.device.deviceId)
                                })
                                serial.write(CaptureProtocol.startCommand(request.mode, request.channel))
                            }
                            is CaptureProtocol.Message.State -> {
                                guard.state(message)
                            }
                            is CaptureProtocol.Message.Stats -> {
                                guard.stats(message)
                                drops = message.dropped
                                session?.append(JSONObject().put("type", "stats").put("v", 1)
                                    .put("received_at_ms", System.currentTimeMillis()).put("mode", message.mode)
                                    .put("device_drops", drops))
                            }
                            is CaptureProtocol.Message.Error -> error("Receiver: ${message.message}")
                            is CaptureProtocol.Message.Packet -> {
                                if (!guard.accept(message)) { invalid++; return@feed }
                                gaps = guard.gaps
                                val at = System.currentTimeMillis()
                                val row = message.json().put("received_at_ms", at).put("source", "esp32-c3-usb")
                                if (request.gps) row.put("observer_gps", gps ?: JSONObject.NULL)
                                session!!.append(row)
                                packets++
                                CaptureObservation.decode(message, at)?.let(request.observe)
                            }
                        }
                    }
                    framingErrors = lines.rejected
                    check(guard.started || now - connectedAt < 6000) { "No capture handshake. Flash the Fieldwatch-NG receiver firmware first." }
                    check(now - lastReceive < 5000) { "USB receiver stopped responding" }
                    if (now - lastPublish >= 500) {
                        session?.flush()
                        mutable.value = UsbCaptureState(true,
                            if (guard.started) "USB ${request.mode} capture running" else "Waiting for receiver firmware…",
                            packets, invalid + lines.rejected, gaps, drops, session?.bytesWritten ?: 0,
                            session?.file?.name, elapsedMs = now - startedElapsed, mode = request.mode.name,
                            channel = request.channel, gpsIncluded = request.gps)
                        lastPublish = now
                    }
                }
            } catch (e: CancellationException) {
                finalStatus = e.message ?: "USB capture stopped"
            } catch (e: Exception) {
                finalStatus = e.message ?: "USB capture failed"
            } finally {
                runCatching { port?.write("STOP\n") }
                runCatching { port?.close() }
                runCatching { if (wake?.isHeld == true) wake?.release() }
                runCatching { session?.finish(JSONObject().put("type", "end").put("v", 1)
                    .put("ended_at_ms", System.currentTimeMillis()).put("reason", finalStatus.take(180))
                    .put("packets", packets).put("invalid", invalid + framingErrors).put("sequence_gaps", gaps).put("device_drops", drops)) }
                    .onFailure { finalStatus = "Capture storage error: ${it.message}" }
                runCatching { session?.close() }.onFailure { finalStatus = "Could not flush capture file" }
                mutable.value = UsbCaptureState(false, finalStatus, packets, invalid + framingErrors, gaps, drops,
                    session?.bytesWritten ?: 0, session?.file?.name,
                    elapsedMs = SystemClock.elapsedRealtime() - startedElapsed, mode = request.mode.name,
                    channel = request.channel, gpsIncluded = request.gps)
            }
        }
        job = captureJob
        captureJob.invokeOnCompletion {
            if (!entered.get() && job === captureJob && mutable.value.active)
                mutable.value = mutable.value.copy(active = false)
        }
        captureJob.start()
    }

    companion object {
        const val MAX_SESSION_MS = 30L * 60 * 1000
        private const val PERMISSION_TOKEN = "usb_permission_request"
    }
}
