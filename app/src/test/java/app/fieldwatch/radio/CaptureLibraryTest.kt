package app.fieldwatch.radio

import app.fieldwatch.radio.usb.CaptureArchive
import app.fieldwatch.radio.usb.CaptureLibrary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class CaptureLibraryTest {
    @Test fun namesAndNotesSurviveReloadWithoutChangingOriginalBytes() {
        val dir = Files.createTempDirectory("capture-library").toFile()
        try {
            val archive = CaptureArchive(dir)
            archive.start(JSONObject().put("type", "session").put("label", "entrance")
                .put("mode", "WIFI").put("started_at_ms", 1000).put("gps_included", true)).use {
                it.append(JSONObject().put("type", "packet").put("received_at_ms", 2000))
                it.finish(JSONObject().put("type", "end").put("ended_at_ms", 4000).put("packets", 1))
            }
            val file = archive.files().single()
            val original = file.readBytes()
            val library = CaptureLibrary(archive)
            val initial = library.entries().single()
            assertEquals("entrance", initial.title)
            assertEquals(3000L, initial.durationMs)
            assertTrue(initial.gpsRequested)
            assertTrue(initial.complete)
            library.annotate(file.name, "  Store entrance  ", "After opening\nKnown AP nearby")
            assertEquals("Store entrance", library.entries().single().title)
            val loaded = CaptureLibrary(archive).entries().single()
            assertEquals("After opening\nKnown AP nearby", loaded.notes)
            assertEquals("entrance", loaded.originalLabel)
            assertArrayEquals(original, file.readBytes())
            val output = ByteArrayOutputStream()
            library.exportJsonl(loaded, output)
            val lines = output.toString("UTF-8").lineSequence().filter { it.isNotEmpty() }.toList()
            val header = JSONObject(lines.first())
            assertEquals("entrance", header.getString("label"))
            assertEquals("Store entrance", header.getString("library_title"))
            assertEquals(loaded.notes, header.getString("library_notes"))
            assertEquals(file.readLines().drop(1), lines.drop(1))
            library.delete(file.name)
            assertTrue(library.entries().isEmpty())
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedAndOldUnlabelledCapturesRemainVisible() {
        val dir = Files.createTempDirectory("capture-library").toFile()
        try {
            val archive = CaptureArchive(dir)
            archive.start(JSONObject().put("type", "session").put("started_at_ms", 1000).put("mode", "BLE")).use {
                it.append(JSONObject().put("type", "packet").put("received_at_ms", 3500))
            }
            val file = archive.files().single()
            file.appendText("{\"type\":\"packet\"")
            val entry = CaptureLibrary(archive).entries().single()
            assertEquals("Untitled capture", entry.title)
            assertEquals("BLE", entry.mode)
            assertEquals(2500L, entry.durationMs)
            assertNull(entry.packets)
            assertFalse(entry.complete)
            assertFalse(entry.gpsRequested)
        } finally { dir.deleteRecursively() }
    }

    @Test fun metadataCannotTargetOtherFilesOrExceedBounds() {
        val dir = Files.createTempDirectory("capture-library").toFile()
        try {
            val archive = CaptureArchive(dir)
            archive.start(JSONObject().put("type", "session")).close()
            val name = archive.files().single().name
            val library = CaptureLibrary(archive)
            assertThrows(IllegalStateException::class.java) { library.annotate("../outside", "title", "") }
            assertThrows(IllegalArgumentException::class.java) { library.annotate(name, " ", "") }
            assertThrows(IllegalArgumentException::class.java) { library.annotate(name, "title", "x".repeat(2001)) }
            assertEquals(1, library.entries().size)
        } finally { dir.deleteRecursively() }
    }
}
