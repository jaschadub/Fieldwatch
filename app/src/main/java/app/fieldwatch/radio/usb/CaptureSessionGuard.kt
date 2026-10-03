package app.fieldwatch.radio.usb

/** One receiver boot and capture mode per archive; never silently join a reboot into the same timeline. */
class CaptureSessionGuard(private val mode: CaptureProtocol.Mode, private val channel: Int) {
    var firmware: String? = null
        private set
    var started = false
        private set
    var gaps = 0L
        private set
    private var sequence: Long? = null
    private var receiverUs = 0L

    fun hello(message: CaptureProtocol.Message.Hello) {
        check(firmware == null) { "Receiver restarted; start a new capture" }
        firmware = message.firmware
    }
    fun state(message: CaptureProtocol.Message.State) {
        val expectedChannel = if (mode == CaptureProtocol.Mode.WIFI) channel else 0
        if (firmware != null && message.mode == mode.name && message.channel == expectedChannel) started = true
        else check(!started) { "Receiver stopped or changed mode" }
    }
    fun stats(message: CaptureProtocol.Message.Stats) {
        check(!started || message.mode == mode.name) { "Receiver stopped or rebooted; start a new capture" }
    }
    fun accept(packet: CaptureProtocol.Message.Packet): Boolean {
        if (!started || packet.radio != mode) return false
        val previous = sequence
        check(previous == null || (packet.seq > previous && packet.us >= receiverUs)) {
            "Receiver sequence reset; start a new capture"
        }
        // A fresh session may start partway through the boot's sequence. Earlier records are not losses.
        if (previous != null) gaps += packet.seq - previous - 1
        sequence = packet.seq; receiverUs = packet.us
        return true
    }
}
