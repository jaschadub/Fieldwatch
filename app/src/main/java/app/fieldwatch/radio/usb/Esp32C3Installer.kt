package app.fieldwatch.radio.usb

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Native USB transport; no serial stub, arbitrary firmware import, or eFuse writes. */
interface RomTransport {
    fun read(buffer: ByteArray): Int
    fun write(bytes: ByteArray)
    fun lines(dtr: Boolean, rts: Boolean)
}

data class FirmwarePart(val name: String, val offset: Int, val bytes: ByteArray)

/** ESP ROM protocol: https://docs.espressif.com/projects/esptool/en/latest/esp32c3/advanced-topics/serial-protocol.html */
class Esp32C3Installer(
    private val port: RomTransport,
    private val checkActive: () -> Unit = {},
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val decoder = RomSlipReader()
    private val replies = ArrayDeque<ByteArray>()
    private val buffer = ByteArray(4096)

    fun install(parts: List<FirmwarePart>, manualBoot: Boolean, progress: (String, Int) -> Unit) {
        validateParts(parts)
        progress("Connecting to C3 bootloader…", 0)
        if (!manualBoot) {
            // Native USB Serial/JTAG reset sequence; ordinary capture never uses this.
            port.lines(false, false); pause(100)
            port.lines(true, false); pause(100)
            port.lines(true, true); port.lines(false, true); pause(100)
            port.lines(false, false); pause(100)
        }
        var connected = false
        repeat(5) {
            if (!connected) {
                checkActive()
                try {
                    val reply = command(0x08, byteArrayOf(7, 7, 0x12, 0x20) + ByteArray(32) { 0x55 }, timeout = 600)
                    check(reply.value != 0L) { "ROM bootloader required; reset the C3 and retry" }
                    connected = true
                } catch (_: RomTimeout) { /* ROM may still be starting. */ }
            }
        }
        check(connected) { "No C3 bootloader. Hold BOOT, tap RESET, release BOOT, then retry in manual mode." }
        progress("Checking chip and flash…", 1)
        val security = command(0x14, expected = 20).data
        check(u32(security, 12) == 5L) { "Wrong chip: installer supports ESP32-C3 only. Nothing erased." }
        check(u32(security, 0) and 5L == 0L && security[4].toInt() == 0) {
            "Secure boot/encryption/download restrictions enabled. Nothing erased."
        }
        val word3 = readReg(0x60008850)
        val word5 = readReg(0x60008858)
        val revision = ((word5 ushr 24) and 3) * 100 +
            (((word5 ushr 23) and 1) shl 3) + ((word3 ushr 18) and 7)
        for (part in listOf(parts[0], parts[3])) {
            val min = (part.bytes[15].toInt() and 255) or ((part.bytes[16].toInt() and 255) shl 8)
            val max = (part.bytes[17].toInt() and 255) or ((part.bytes[18].toInt() and 255) shl 8)
            check(revision in min.toLong()..max.toLong()) { "C3 revision is incompatible with bundled firmware. Nothing erased." }
        }
        // Native USB reset leaves RTC watchdogs running. These are volatile registers, never eFuses.
        writeReg(0x600080a8, 0x50d83aa1); writeReg(0x60008090, 0); writeReg(0x600080a8, 0)
        writeReg(0x600080b0, 0x8f1d312a)
        writeReg(0x600080ac, readReg(0x600080ac) or 0x80000000)
        writeReg(0x600080b0, 0)
        command(0x0d, words(0, 0)) // Attach default SPI flash pins.
        val flashId = readFlashId()
        check((flashId ushr 16) and 255L == 22L && flashId and 255L !in listOf(0L, 255L)) {
            "Expected 4 MB SPI flash; unsupported flash ID ${flashId.toString(16)}. Nothing erased."
        }
        command(0x0b, words(0, 0x400000, 0x10000, 0x1000, 256, 0xffff))
        val total = parts.sumOf { it.bytes.size }
        var written = 0
        parts.forEach { part ->
            checkActive()
            val blocks = (part.bytes.size + 1023) / 1024
            progress("Erasing ${part.name}…", 2 + written * 90 / total)
            command(0x02, words(part.bytes.size.toLong(), blocks.toLong(), 1024, part.offset.toLong(), 0), timeout = 60_000)
            repeat(blocks) { sequence ->
                checkActive()
                val start = sequence * 1024
                val length = minOf(1024, part.bytes.size - start)
                val block = ByteArray(1024) { 0xff.toByte() }
                part.bytes.copyInto(block, 0, start, start + length)
                val checksum = block.fold(0xef) { sum, b -> sum xor (b.toInt() and 255) }
                // Abort on a missing ACK instead of risking a delayed ACK being applied to another block.
                command(0x03, words(1024, sequence.toLong(), 0, 0) + block, checksum.toLong(), timeout = 5000)
                written += length
                progress("Writing ${part.name}…", 2 + written * 90 / total)
            }
            progress("Verifying ${part.name}…", 2 + written * 90 / total)
            val actual = command(0x13, words(part.offset.toLong(), part.bytes.size.toLong(), 0, 0),
                expected = 32, timeout = 30_000).data.toString(Charsets.US_ASCII)
            val expected = digest("MD5", part.bytes)
            check(actual.equals(expected, ignoreCase = true)) { "Flash verification failed for ${part.name}. Reinstall before capture." }
        }
        command(0x04, words(1)) // Stay in ROM until every image has been verified.
        progress("All firmware verified", 96)
    }

    fun reboot() {
        port.lines(false, true); pause(200)
        port.lines(false, false); pause(200)
    }

    private fun readFlashId(): Long {
        val user = readReg(0x60002018); val user2 = readReg(0x60002020)
        val miso = readReg(0x60002028)
        try {
            writeReg(0x60002028, 23)
            writeReg(0x60002018, 0x90000000)
            writeReg(0x60002020, 0x7000009f)
            writeReg(0x60002058, 0)
            writeReg(0x60002000, 1L shl 18)
            repeat(20) {
                if (readReg(0x60002000) and (1L shl 18) == 0L) return readReg(0x60002058)
            }
            error("SPI flash did not respond. Nothing erased.")
        } finally {
            writeReg(0x60002018, user); writeReg(0x60002020, user2); writeReg(0x60002028, miso)
        }
    }
    private fun readReg(address: Long) = command(0x0a, words(address)).value
    private fun writeReg(address: Long, value: Long) { command(0x09, words(address, value, 0xffffffff, 0)) }

    private data class Reply(val value: Long, val data: ByteArray)
    private fun command(op: Int, data: ByteArray = byteArrayOf(), checksum: Long = 0,
        expected: Int = 0, timeout: Long = 3000): Reply {
        checkActive()
        val header = byteArrayOf(0, op.toByte(), data.size.toByte(), (data.size ushr 8).toByte()) + words(checksum)
        port.write(RomSlipReader.encode(header + data))
        val deadline = clockMs() + timeout
        while (clockMs() < deadline) {
            checkActive()
            if (replies.isEmpty()) {
                val count = port.read(buffer)
                if (count > 0) decoder.feed(buffer, count) {
                    check(replies.size < 128) { "Invalid bootloader response flood" }; replies.addLast(it)
                }
            }
            while (replies.isNotEmpty()) {
                val p = replies.removeFirst()
                if (p.size < 8 || p[0].toInt() != 1 || p[1].toInt() and 255 != op) continue
                val size = (p[2].toInt() and 255) or ((p[3].toInt() and 255) shl 8)
                check(size == p.size - 8 && size == expected + 4) { "Invalid ROM response to command $op" }
                check(p[8 + expected].toInt() == 0) { "ROM rejected command $op (error ${p[9 + expected].toInt() and 255})" }
                return Reply(u32(p, 4), p.copyOfRange(8, 8 + expected))
            }
        }
        throw RomTimeout("C3 bootloader timed out on command $op. Reconnect and reinstall.")
    }

    companion object {
        fun words(vararg values: Long): ByteArray = values.flatMap { n ->
            (0..3).map { (n ushr (it * 8)).toByte() }
        }.toByteArray()
        fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { v, i ->
            v or ((bytes[offset + i].toLong() and 255) shl (8 * i))
        }
        fun digest(algorithm: String, bytes: ByteArray) = MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun validateParts(parts: List<FirmwarePart>) {
            require(parts.map { it.offset } == listOf(0, 0x8000, 0x9000, 0x10000)) { "Unexpected firmware layout" }
            val limits = listOf(0x8000, 0x1000, 0x7000, 0x100000)
            parts.forEachIndexed { i, p -> require(p.bytes.isNotEmpty() && p.bytes.size <= limits[i]) { "Invalid firmware size" } }
            require(parts[2].bytes.size == 0x7000 && parts[2].bytes.all { it == 0xff.toByte() }) { "Invalid settings reset image" }
            for (p in listOf(parts[0], parts[3])) {
                require(p.bytes.size >= 24 && p.bytes[0].toInt() and 255 == 0xe9 &&
                    p.bytes[12].toInt() == 5 && p.bytes[13].toInt() == 0 &&
                    p.bytes[3].toInt() ushr 4 == 2) { "Firmware is not an ESP32-C3 / 4 MB image" }
            }
        }
    }
}

class RomTimeout(message: String) : java.io.IOException(message)

/** Bounded SLIP decoder. Boot messages outside frames are ignored. */
class RomSlipReader {
    private val packet = ByteArrayOutputStream()
    private var inside = false
    private var escaped = false
    fun feed(bytes: ByteArray, count: Int, emit: (ByteArray) -> Unit) {
        require(count in 0..bytes.size)
        for (i in 0 until count) {
            val b = bytes[i].toInt() and 255
            if (b == 0xc0) {
                if (inside && !escaped && packet.size() > 0) emit(packet.toByteArray())
                packet.reset(); inside = true; escaped = false
            } else if (inside) {
                if (escaped) {
                    if (b != 0xdc && b != 0xdd) { inside = false; packet.reset() }
                    else packet.write(if (b == 0xdc) 0xc0 else 0xdb)
                    escaped = false
                } else if (b == 0xdb) escaped = true
                else packet.write(b)
                if (packet.size() > 4096) { inside = false; packet.reset() }
            }
        }
    }
    companion object {
        fun encode(bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(0xc0)
            bytes.forEach { b -> when (b.toInt() and 255) {
                0xc0 -> { write(0xdb); write(0xdc) }
                0xdb -> { write(0xdb); write(0xdd) }
                else -> write(b.toInt() and 255)
            } }
            write(0xc0)
        }.toByteArray()
    }
}
