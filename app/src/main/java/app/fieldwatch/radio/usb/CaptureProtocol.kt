package app.fieldwatch.radio.usb

import org.json.JSONObject

/** Versioned USB wire format. A record is one bounded ASCII JSON line, never a command. */
object CaptureProtocol {
    const val VERSION = 1
    const val MAX_LINE = 4096
    const val MAX_WIFI_BYTES = 1536
    enum class Mode { WIFI, BLE }

    sealed interface Message {
        data class Hello(val firmware: String) : Message
        data class State(val mode: String, val channel: Int) : Message
        data class Stats(val dropped: Long, val mode: String) : Message
        data class Error(val message: String) : Message
        data class Packet(
            val radio: Mode, val seq: Long, val us: Long, val rssi: Int,
            val channel: Int, val originalLength: Int, val bytes: ByteArray,
            val address: String = "", val addressType: Int = 0, val eventType: Int = 0,
        ) : Message {
            val truncated: Boolean get() = bytes.size < originalLength
            fun json(): JSONObject = JSONObject().apply {
                put("v", VERSION); put("type", "packet"); put("radio", radio.name)
                put("seq", seq); put("us", us); put("rssi", rssi); put("channel", channel)
                put("original_length", originalLength); put("data", hex(bytes))
                put("truncated", truncated)
                if (radio == Mode.BLE) {
                    put("address", address); put("address_type", addressType); put("event_type", eventType)
                } else put("fcs_included", false)
            }
        }
    }

    fun startCommand(mode: Mode, channel: Int): String {
        require(channel in 0..11)
        return "START ${mode.name} ${if (mode == Mode.WIFI) channel else 0}\n"
    }

    fun parse(line: String): Message? = runCatching {
        require(line.length in 2..MAX_LINE)
        // The protocol is flat. Bound parser nesting even for a hostile USB peripheral.
        require(line.count { it == '{' || it == '[' } == 1)
        val j = JSONObject(line)
        require(number(j, "v") == VERSION.toLong())
        when (string(j, "type")) {
            "hello" -> {
                require(string(j, "chip") == "ESP32-C3")
                Message.Hello(string(j, "firmware").also { require(it.length in 1..64) })
            }
            "state" -> {
                val channel = number(j, "channel").also { require(it in 0..11) }
                Message.State(string(j, "mode"), channel.toInt()).also {
                    require(it.mode in listOf("OFF", "WIFI", "BLE"))
                }
            }
            "stats" -> Message.Stats(number(j, "dropped").also { require(it >= 0) },
                string(j, "mode").also { require(it in listOf("OFF", "WIFI", "BLE")) })
            "error" -> Message.Error(string(j, "message").take(120))
            "packet" -> {
                val radio = Mode.valueOf(string(j, "radio"))
                val seq = number(j, "seq"); val us = number(j, "us")
                val rssi = number(j, "rssi"); val channel = number(j, "channel")
                val length = number(j, "original_length")
                require(seq >= 0 && us >= 0 && rssi in -127..20)
                val data = string(j, "data")
                val max = if (radio == Mode.WIFI) MAX_WIFI_BYTES else 31
                require(data.length <= max * 2 && data.length % 2 == 0)
                require(data.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                val bytes = ByteArray(data.length / 2) { data.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                require(length in bytes.size.toLong()..4096L)
                if (radio == Mode.WIFI) {
                    require(channel in 1..11 && bytes.size >= 24)
                    require(j.get("fcs_included") == false)
                    // Only management frames; encrypted application traffic is never in this protocol.
                    require(bytes[0].toInt() and 0x0f == 0)
                    Message.Packet(radio, seq, us, rssi.toInt(), channel.toInt(), length.toInt(), bytes)
                } else {
                    require(channel == 0L && length == bytes.size.toLong())
                    val address = string(j, "address").uppercase()
                    require(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}").matches(address))
                    require(address != "00:00:00:00:00:00" && address != "FF:FF:FF:FF:FF:FF")
                    val addressType = number(j, "address_type")
                    val eventType = number(j, "event_type")
                    require(addressType in 0..3 && eventType in listOf(0L, 1L, 2L, 3L))
                    Message.Packet(radio, seq, us, rssi.toInt(), 0, bytes.size, bytes, address,
                        addressType.toInt(), eventType.toInt())
                }
            }
            else -> error("Unknown record")
        }
    }.getOrNull()

    private fun number(j: JSONObject, key: String): Long {
        val value = j.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }
    private fun string(j: JSONObject, key: String): String = (j.get(key) as? String) ?: error(key)
    fun hex(bytes: ByteArray): String {
        val digits = "0123456789ABCDEF"
        return buildString(bytes.size * 2) {
            bytes.forEach { append(digits[(it.toInt() ushr 4) and 15]); append(digits[it.toInt() and 15]) }
        }
    }
}

/** Handles arbitrary USB read boundaries. Oversize lines are discarded through the next newline. */
class CaptureLineReader {
    private val line = StringBuilder()
    private var discarding = false
    var rejected = 0L
        private set
    fun feed(bytes: ByteArray, count: Int, emit: (String) -> Unit) {
        require(count in 0..bytes.size)
        for (i in 0 until count) {
            val c = bytes[i].toInt() and 255
            if (c == 10) {
                if (!discarding && line.isNotEmpty()) emit(line.toString())
                line.setLength(0); discarding = false
            } else if (!discarding && c != 13) {
                if (c !in 32..126 || line.length >= CaptureProtocol.MAX_LINE) {
                    line.setLength(0); discarding = true; rejected++
                } else line.append(c.toChar())
            }
        }
    }
}
