package app.fieldwatch.radio.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.MainActivity
import app.fieldwatch.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/** Keeps the bounded USB install alive independently of the phone radio scanning service. */
class UsbFirmwareService : LifecycleService() {
    private var installation: Job? = null
    private val controller get() = (application as FieldwatchApp).usbCapture
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Receiver firmware", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification(controller.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        lifecycleScope.launch {
            controller.state.collect { state ->
                if (state.installing) notifications.notify(ID, notification(state))
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        installation = controller.runInstallation(intent?.getIntExtra("device", -1) ?: -1)
        if (installation == null) stopSelf()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        installation?.cancel(CancellationException("Installer service stopped. Reconnect and reinstall."))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    private fun notification(state: UsbCaptureState): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_fieldwatch)
        .setContentTitle("Fieldwatch-NG receiver installer")
        .setContentText(state.status)
        .setProgress(100, state.installPercent, state.installPercent == 0)
        .setOnlyAlertOnce(true).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()
    companion object { private const val CHANNEL = "receiver_install"; private const val ID = 43 }
}
