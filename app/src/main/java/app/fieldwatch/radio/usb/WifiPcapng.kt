package app.fieldwatch.radio.usb

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCAPNG with LINKTYPE_IEEE802_11_RADIOTAP (127), using phone receipt timestamps. */
object WifiPcapng {
    data class Result(val packets: Int, val skipped: Int, val incomplete: Boolean)

    fun export(file: File, out: OutputStream, title: String, notes: String): Result {
        var packets = 0
        var skipped = 0
        var ended = false
        file.bufferedReader().use { input ->
            val first = input.readLine() ?: error("Empty capture")
            check(first.length <= 8192) { "Invalid capture header" }
            val session = JSONObject(first)
            check(session.optString("type") == "session" && session.optString("mode") == "WIFI") {
                "Wireshark export requires a Wi-Fi capture. Use JSONL for BLE advertisements."
            }
            val comment = JSONObject().put("title", title.take(80)).put("notes", notes.take(2000))
                .put("original_label", session.optString("label").take(64))
                .put("firmware", session.optString("firmware").take(64))
                .put("gps_requested", session.optBoolean("gps_included"))
            block(out, 0x0a0d0d0a, le(16).putInt(0x1a2b3c4d).putShort(1).putShort(0).putLong(-1).array() +
                options(1 to comment.toString().toByteArray(Charsets.UTF_8)))
            val description = "ESP32-C3 passive 2.4 GHz management frames. Timestamps are Android USB receipt time, " +
                "not synchronized RF arrival. FCS removed. Channel and RSSI are receiver measurements."
            block(out, 1, le(8).putShort(127).putShort(0).putInt(8192).array() + options(
                2 to "Fieldwatch-NG C3 Wi-Fi".toByteArray(),
                3 to description.toByteArray(),
                9 to byteArrayOf(3), // if_tsresol: 10^-3 seconds.
            ))
            input.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                if (line.length > 8192) { skipped++; return@forEach }
                val row = runCatching { JSONObject(line) }.getOrNull()
                if (row == null) { skipped++; return@forEach }
                if (row.optString("type") == "end") { ended = true; return@forEach }
                if (row.optString("type") != "packet") return@forEach
                val fix = row.optJSONObject("observer_gps")
                row.remove("observer_gps")
                val packet = CaptureProtocol.parse(row.toString()) as? CaptureProtocol.Message.Packet
                val timestamp = row.optLong("received_at_ms", -1)
                if (packet == null || packet.radio != CaptureProtocol.Mode.WIFI || timestamp < 0) {
                    skipped++; return@forEach
                }
                // Flags=0 explicitly means no FCS. Align the channel field to 2 bytes.
                val radio = le(15).put(0).put(0).putShort(15).putInt(0x2a)
                    .put(0).put(0).putShort((2407 + 5 * packet.channel).toShort())
                    .putShort(0x0080).put(packet.rssi.toByte()).array()
                val data = radio + packet.bytes
                val packetComment = JSONObject().put("receiver_us", packet.us).put("seq", packet.seq)
                    .put("received_at_ms", timestamp)
                if (fix != null) packetComment.put("observer_gps", fix)
                val header = le(20).putInt(0).putInt((timestamp ushr 32).toInt()).putInt(timestamp.toInt())
                    .putInt(data.size).putInt(radio.size + packet.originalLength).array()
                block(out, 6, header + padded(data) + options(1 to packetComment.toString().toByteArray(Charsets.UTF_8)))
                packets++
            }
        }
        check(packets > 0) { "No valid Wi-Fi packets to export. The original JSONL is unchanged." }
        return Result(packets, skipped, !ended)
    }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun padded(bytes: ByteArray) = bytes.copyOf((bytes.size + 3) and -4)
    private fun options(vararg values: Pair<Int, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        values.forEach { (code, data) ->
            require(data.size <= 65535)
            write(le(4).putShort(code.toShort()).putShort(data.size.toShort()).array())
            write(padded(data))
        }
        write(ByteArray(4))
    }.toByteArray()
    private fun block(out: OutputStream, type: Int, body: ByteArray) {
        require(body.size % 4 == 0)
        val size = body.size + 12
        out.write(le(8).putInt(type).putInt(size).array())
        out.write(body)
        out.write(le(4).putInt(size).array())
    }
}
