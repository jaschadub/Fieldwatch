package app.fieldwatch.radio.usb

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class CaptureEntry(
    val name: String,
    val title: String,
    val notes: String,
    val originalLabel: String,
    val startedAtMs: Long,
    val durationMs: Long?,
    val mode: String,
    val gpsRequested: Boolean,
    val packets: Long?,
    val bytes: Long,
    val complete: Boolean,
)

/** Annotations live beside the recording. Listing reads only bounded header/tail windows. */
class CaptureLibrary(private val archive: CaptureArchive) {
    private data class Cached(val modified: Long, val size: Long, val entry: CaptureEntry)
    private val cache = mutableMapOf<String, Cached>()

    @Synchronized fun entries(): List<CaptureEntry> {
        val files = archive.files()
        cache.keys.retainAll(files.map { it.name }.toSet())
        return files.map { file ->
            val old = cache[file.name]
            if (old != null && old.modified == file.lastModified() && old.size == file.length()) old.entry
            else read(file).also { cache[file.name] = Cached(file.lastModified(), file.length(), it) }
        }.sortedByDescending { it.startedAtMs }
    }

    fun file(name: String): File = archive.files().firstOrNull { it.name == name }
        ?: error("Capture file not found")

    @Synchronized fun annotate(name: String, title: String, notes: String) {
        val file = file(name)
        val cleanTitle = title.filterNot(Char::isISOControl).trim()
        val cleanNotes = notes.filter { !it.isISOControl() || it == '\n' }.trim()
        require(cleanTitle.length in 1..80) { "Use a name between 1 and 80 characters" }
        require(cleanNotes.length <= 2000) { "Notes must be 2000 characters or fewer" }
        val temp = File(file.parentFile, ".annotation-${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(JSONObject().put("title", cleanTitle).put("notes", cleanNotes).toString())
            Files.move(temp.toPath(), annotation(file).toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
            cache.remove(name)
        } finally { temp.delete() }
    }

    @Synchronized fun delete(name: String) {
        val file = file(name)
        check(file.delete()) { "Could not delete capture" }
        annotation(file).delete()
        cache.remove(name)
    }

    /** Add editable library metadata to the exported header; never rewrite raw packet records. */
    fun exportJsonl(entry: CaptureEntry, out: OutputStream) {
        file(entry.name).inputStream().buffered().use { input ->
            val header = ArrayList<Byte>()
            while (true) {
                val b = input.read()
                check(b >= 0 && header.size < 8192) { "Capture header is incomplete" }
                if (b == 10) break
                header.add(b.toByte())
            }
            val json = JSONObject(header.toByteArray().toString(Charsets.UTF_8))
            check(json.optString("type") == "session") { "Invalid capture header" }
            json.put("library_title", entry.title).put("library_notes", entry.notes)
            out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
            input.copyTo(out)
        }
    }

    private fun annotation(file: File) = File(file.parentFile, file.name + ".meta.json")

    private fun read(file: File): CaptureEntry {
        var header = JSONObject()
        var end: JSONObject? = null
        var lastAt: Long? = null
        runCatching {
            RandomAccessFile(file, "r").use { input ->
                val head = ByteArray(minOf(8192L, input.length()).toInt())
                input.readFully(head)
                header = JSONObject(head.toString(Charsets.UTF_8).substringBefore('\n'))
                check(header.optString("type") == "session")
                val offset = (input.length() - 16384L).coerceAtLeast(0)
                input.seek(offset)
                val tail = ByteArray((input.length() - offset).toInt())
                input.readFully(tail)
                tail.toString(Charsets.UTF_8).lineSequence().forEach { line ->
                    val row = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    when (row.optString("type")) {
                        "end" -> end = row
                        "packet", "stats" -> lastAt = row.optLong("received_at_ms").takeIf { it > 0 }
                    }
                }
            }
        }
        val meta = runCatching {
            val sidecar = annotation(file)
            check(sidecar.length() in 1..16384)
            JSONObject(sidecar.readText())
        }.getOrDefault(JSONObject())
        val label = header.optString("label").take(64)
        val start = header.optLong("started_at_ms").takeIf { it > 0 } ?: file.lastModified()
        val finish = end?.optLong("ended_at_ms")?.takeIf { it > 0 } ?: lastAt
        return CaptureEntry(file.name, meta.optString("title").take(80).ifBlank {
            label.ifBlank { "Untitled capture" }
        }, meta.optString("notes").take(2000), label, start,
            finish?.let { (it - start).coerceAtLeast(0) }, header.optString("mode", "Unknown"),
            header.optBoolean("gps_included"), end?.optLong("packets"), file.length(), end != null)
    }
}
