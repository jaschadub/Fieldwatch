package app.fieldwatch.radio.usb

import org.json.JSONObject
import java.io.InputStream

/** Only the APK's checked-in, SHA-256-verified receiver bundle may be installed. */
data class ReceiverFirmware(val version: String, val parts: List<FirmwarePart>) {
    companion object {
        fun load(open: (String) -> InputStream): ReceiverFirmware {
            fun read(name: String, limit: Int): ByteArray = open("receiver/$name").use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    check(out.size() + n <= limit) { "Firmware asset too large: $name" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val manifest = JSONObject(read("manifest.json", 4096).toString(Charsets.UTF_8))
            check(manifest.getInt("schema") == 1 && manifest.getString("chip") == "ESP32-C3" &&
                manifest.getInt("flash_size") == 0x400000) { "Unsupported firmware bundle" }
            val names = listOf("bootloader.bin", "partition-table.bin", "settings-reset.bin", "receiver.bin")
            val rows = manifest.getJSONArray("parts")
            check(rows.length() == names.size) { "Incomplete firmware bundle" }
            val parts = names.mapIndexed { index, name ->
                val row = rows.getJSONObject(index)
                check(row.getString("file") == name) { "Unexpected firmware asset" }
                val bytes = read(name, 0x100000)
                check(bytes.size == row.getInt("size") &&
                    Esp32C3Installer.digest("SHA-256", bytes) == row.getString("sha256")) {
                    "Firmware integrity check failed: $name"
                }
                FirmwarePart(name, row.getInt("offset"), bytes)
            }
            Esp32C3Installer.validateParts(parts)
            val version = manifest.getString("firmware")
            check(version.length in 1..64)
            return ReceiverFirmware(version, parts)
        }
    }
}
