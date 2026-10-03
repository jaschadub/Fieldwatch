package app.fieldwatch.radio.usb

import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID

/** A capture never silently rotates away evidence. Quotas stop collection with a visible reason. */
class CaptureArchive(private val directory: File) {
    fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(Regex("capture-[0-9a-f-]+\\.jsonl")) }
        .sortedByDescending { it.lastModified() }

    fun start(metadata: JSONObject): Session {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create capture directory" }
        val existing = files()
        check(existing.size < 20 && existing.sumOf { it.length() } + 16L * 1024 * 1024 <= 128L * 1024 * 1024) {
            "Capture storage full. Export and delete older USB captures."
        }
        val file = File(directory, "capture-${UUID.randomUUID()}.jsonl")
        check(file.createNewFile())
        val session = Session(file)
        try { session.append(metadata) } catch (e: Exception) {
            runCatching { session.close() }; throw e
        }
        return session
    }

    class Session(val file: File, private val limit: Long = 16L * 1024 * 1024) : Closeable {
        private val writer = file.outputStream().buffered(32 * 1024)
        var bytesWritten = 0L
            private set
        fun append(record: JSONObject) {
            val bytes = (record.toString() + "\n").toByteArray(Charsets.UTF_8)
            check(bytesWritten + bytes.size < limit - 2048) { "16 MiB capture limit reached; export and start another capture" }
            writer.write(bytes); bytesWritten += bytes.size
        }
        fun flush() = writer.flush()
        fun finish(record: JSONObject) {
            val bytes = (record.toString() + "\n").toByteArray(Charsets.UTF_8)
            require(bytes.size <= 2048)
            writer.write(bytes); bytesWritten += bytes.size
            writer.flush()
        }
        override fun close() = writer.close()
    }
}
