package app.fieldwatch.radio

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import app.fieldwatch.radio.usb.UsbCaptureController
import app.fieldwatch.radio.usb.UsbFirmwareService
import app.fieldwatch.radio.usb.CaptureProtocol
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Exercise Android's immutable PendingIntent delivery, not just the ROM transport fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class,
    shadows = [UsbPermissionTest.PermissionManager::class, UsbPermissionTest.TestDevice::class])
class UsbPermissionTest {
    @Implements(UsbManager::class)
    class PermissionManager : ShadowUsbManager() {
        lateinit var callback: PendingIntent
        @Implementation
        fun requestPermission(device: UsbDevice, intent: PendingIntent) { callback = intent }
    }

    @Implements(UsbDevice::class)
    class TestDevice {
        @Implementation fun getDeviceId(): Int = 101
    }

    private lateinit var app: Application
    private lateinit var usb: PermissionManager
    private lateinit var device: UsbDevice
    private lateinit var controller: UsbCaptureController

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        // AndroidX uses its merged-manifest signature permission on API < 33.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        usb = Shadow.extract(app.getSystemService(Context.USB_SERVICE) as UsbManager)
        device = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", "/dev/bus/usb/001/002")
            ReflectionHelpers.setField(this, "mVendorId", 0x303a)
            ReflectionHelpers.setField(this, "mProductId", 0x1001)
        }
        usb.addOrUpdateUsbDevice(device, false)
        controller = UsbCaptureController(app)
    }

    private fun begin() {
        controller.install(device.deviceId, false)
        assertTrue(controller.state.value.installing)
        assertEquals("Allow USB access in the Android dialog", controller.state.value.status)
    }

    @Test fun grantedPermissionStartsInstallerEvenWhenSystemExtrasAreDiscarded() {
        begin()
        usb.addOrUpdateUsbDevice(device, true)
        // Immutable callbacks discard Android's fill-in extras. The app must use its own identity.
        usb.callback.send(app, 0, Intent().putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(UsbFirmwareService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertNotEquals("Allow USB access in the Android dialog", controller.state.value.status)
    }

    @Test fun deniedPermissionClearsBusyStateWithoutStartingInstaller() {
        begin()
        usb.callback.send(app, 0, Intent().putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.state.value.active)
        assertFalse(controller.state.value.installing)
        assertEquals("USB permission denied", controller.state.value.status)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun systemGrantedExtraCannotSubstituteForActualPermission() {
        begin()
        usb.callback.send(app, 0, Intent().putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("USB permission denied", controller.state.value.status)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun unansweredRequestExpiresAndCannotLaterStartFlashing() {
        begin()
        val stale = Intent(shadowOf(usb.callback).savedIntent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(91))
        assertFalse(controller.state.value.active)
        assertFalse(controller.state.value.awaitingUsbPermission)
        assertTrue(controller.state.value.status.contains("timed out"))
        usb.addOrUpdateUsbDevice(device, true)
        app.sendBroadcast(stale)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun cancelClearsRequestAndRevokesItsCallback() {
        begin()
        val callback = usb.callback
        controller.cancelUsbPermissionRequest()
        assertFalse(controller.state.value.active)
        assertFalse(controller.state.value.awaitingUsbPermission)
        assertThrows(PendingIntent.CanceledException::class.java) { callback.send() }
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun staleReplyCannotApproveAReplacementRequestForSameDevice() {
        begin()
        val stale = Intent(shadowOf(usb.callback).savedIntent)
        controller.cancelUsbPermissionRequest()
        begin()
        usb.addOrUpdateUsbDevice(device, true)
        app.sendBroadcast(stale)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.state.value.awaitingUsbPermission)
        assertNull(shadowOf(app).nextStartedService)
        usb.callback.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test fun missingTokenDoesNotApprovePendingRequest() {
        begin()
        usb.addOrUpdateUsbDevice(device, true)
        app.sendBroadcast(Intent("${app.packageName}.USB_CAPTURE_PERMISSION").setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.state.value.awaitingUsbPermission)
        assertNull(shadowOf(app).nextStartedService)
        controller.cancelUsbPermissionRequest()
    }

    @Test fun deviceRemovedBeforeReplyDoesNotStartInstaller() {
        begin()
        usb.removeUsbDevice(device)
        usb.callback.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.state.value.active)
        assertTrue(controller.state.value.status.contains("disconnected"))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun grantConsumesCallbackOnceAndRemovesPermissionTimeout() {
        begin()
        val callback = Intent(shadowOf(usb.callback).savedIntent)
        usb.addOrUpdateUsbDevice(device, true)
        usb.callback.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(shadowOf(app).nextStartedService)
        app.sendBroadcast(callback)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(91))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(controller.state.value.installing)
        assertFalse(controller.state.value.awaitingUsbPermission)
        controller.cancelUsbPermissionRequest() // No longer allowed to cancel a running installation.
        assertTrue(controller.state.value.installing)
    }

    @Test fun previouslyGrantedDeviceBypassesPermissionRequest() {
        usb.addOrUpdateUsbDevice(device, true)
        controller.install(device.deviceId, false)
        assertFalse(controller.state.value.awaitingUsbPermission)
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test fun capturePermissionDenialAlsoClearsBusyState() {
        controller.start(device.deviceId, CaptureProtocol.Mode.BLE, 0, "", false, {})
        assertTrue(controller.state.value.awaitingUsbPermission)
        usb.callback.send()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.state.value.active)
        assertEquals("USB permission denied", controller.state.value.status)
    }
}
