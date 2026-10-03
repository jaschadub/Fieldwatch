package app.fieldwatch.radio

import app.fieldwatch.radio.usb.CaptureProtocol
import app.fieldwatch.radio.usb.WifiPcapng
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WifiPcapngTest {
    private fun header(mode: String = "WIFI") = JSONObject().put("type", "session").put("v", 1)
        .put("mode", mode).put("label", "Synthetic survey").put("gps_included", true)
    private fun packet(truncated: Boolean = false): JSONObject {
        val frame = ByteArray(36) // Synthetic beacon header and fixed parameters, no FCS.
        frame[0] = 0x80.toByte()
        for (i in 4..9) frame[i] = 0xff.toByte()
        frame[10] = 2; frame[15] = 1; frame[16] = 2; frame[21] = 1
        frame[32] = 100
        val bytes = if (truncated) frame.copyOf(24) else frame + byteArrayOf(0, 4, 84, 101, 115, 116)
        return CaptureProtocol.Message.Packet(CaptureProtocol.Mode.WIFI, 7, 500000, -52, 6,
            if (truncated) 80 else bytes.size, bytes).json()
            .put("received_at_ms", 1710000000123L).put("source", "esp32-c3-usb")
            .put("observer_gps", JSONObject().put("lat", 12.0).put("lon", 34.0).put("accuracy_m", 10))
    }
    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun blocks(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val input = le(bytes)
        val result = mutableListOf<Pair<Int, ByteArray>>()
        while (input.hasRemaining()) {
            val type = input.int; val size = input.int
            assertTrue(size >= 12 && size % 4 == 0)
            val body = ByteArray(size - 12); input.get(body)
            assertEquals(size, input.int)
            result += type to body
        }
        return result
    }

    @Test fun wiresharkBlocksKeepFrameTimestampRadioMetadataAndTruncation() {
        val file = Files.createTempFile("pcapng-test", ".jsonl").toFile()
        try {
            val first = packet()
            file.writeText(listOf(header(), first, packet(true), JSONObject().put("type", "end")).joinToString("\n") + "\n")
            val out = ByteArrayOutputStream()
            val result = WifiPcapng.export(file, out, "Survey name", "Field notes")
            assertEquals(WifiPcapng.Result(2, 0, false), result)
            val blocks = blocks(out.toByteArray())
            assertEquals(listOf(0x0a0d0d0a, 1, 6, 6), blocks.map { it.first })
            assertEquals(0x1a2b3c4d, le(blocks[0].second).int)
            assertTrue(blocks[0].second.toString(Charsets.UTF_8).contains("Field notes"))
            val idb = le(blocks[1].second)
            assertEquals(127, idb.short.toInt())
            assertEquals(0, idb.short.toInt())
            assertEquals(8192, idb.int)
            var foundResolution = false
            while (idb.remaining() >= 4) {
                val code = idb.short.toInt(); val size = idb.short.toInt() and 65535
                if (code == 0) break
                if (code == 9) { assertEquals(1, size); assertEquals(3, idb.get(idb.position()).toInt()); foundResolution = true }
                idb.position(idb.position() + ((size + 3) and -4))
            }
            assertTrue(foundResolution)
            val epb = le(blocks[2].second)
            assertEquals(0, epb.int)
            val timestamp = (epb.int.toLong() shl 32) or (epb.int.toLong() and 0xffffffffL)
            assertEquals(1710000000123L, timestamp)
            val captured = epb.int
            assertEquals(captured, epb.int)
            val radio = ByteArray(15); epb.get(radio)
            val radiotap = le(radio)
            assertEquals(0, radiotap.short.toInt())
            assertEquals(15, radiotap.short.toInt())
            assertEquals(0x2a, radiotap.int)
            assertEquals(0, radiotap.get().toInt()) // FCS absent.
            radiotap.get() // Channel alignment.
            assertEquals(2437, radiotap.short.toInt())
            assertEquals(0x80, radiotap.short.toInt())
            assertEquals(-52, radiotap.get().toInt())
            val data = ByteArray(captured - 15); epb.get(data)
            assertEquals(first.getString("data"), CaptureProtocol.hex(data))
            assertTrue(blocks[2].second.toString(Charsets.UTF_8).contains("observer_gps"))
            assertEquals(39, le(blocks[3].second).getInt(12))
            assertEquals(95, le(blocks[3].second).getInt(16))
            // A synthetic fixture for independent capinfos/tshark validation.
            File("build/test-output/usb-wifi.pcapng").apply { parentFile?.mkdirs(); writeBytes(out.toByteArray()) }
        } finally { file.delete() }
    }

    @Test fun interruptedCaptureReportsBadRecordsAndStillExportsValidFrames() {
        val file = Files.createTempFile("pcapng-test", ".jsonl").toFile()
        try {
            file.writeText(header().toString() + "\n" + packet() + "\n" +
                packet().put("received_at_ms", -1) + "\n" + "{broken tail")
            val result = WifiPcapng.export(file, ByteArrayOutputStream(), "test", "")
            assertEquals(WifiPcapng.Result(1, 2, true), result)
        } finally { file.delete() }
    }

    @Test fun bleAndEmptyCapturesCannotMasqueradeAsWifi() {
        val file = Files.createTempFile("pcapng-test", ".jsonl").toFile()
        try {
            file.writeText(header("BLE").toString() + "\n")
            assertThrows(IllegalStateException::class.java) { WifiPcapng.export(file, ByteArrayOutputStream(), "", "") }
            file.writeText(header().toString() + "\n")
            assertThrows(IllegalStateException::class.java) { WifiPcapng.export(file, ByteArrayOutputStream(), "", "") }
        } finally { file.delete() }
    }
}
