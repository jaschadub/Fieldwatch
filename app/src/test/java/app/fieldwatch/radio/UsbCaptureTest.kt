package app.fieldwatch.radio

import app.fieldwatch.radio.usb.CaptureArchive
import app.fieldwatch.radio.usb.CaptureLineReader
import app.fieldwatch.radio.usb.CaptureObservation
import app.fieldwatch.radio.usb.CaptureProtocol
import app.fieldwatch.radio.usb.CaptureSessionGuard
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UsbCaptureTest {
    private fun ble(data: String = "0201060303BBB105FF46030102"): JSONObject = JSONObject()
        .put("v", 1).put("type", "packet").put("radio", "BLE").put("seq", 17).put("us", 123000)
        .put("rssi", -58).put("channel", 0).put("original_length", data.length / 2)
        .put("address", "C0:11:22:33:44:55").put("address_type", 1).put("event_type", 3).put("data", data)
    private fun packet(j: JSONObject) = CaptureProtocol.parse(j.toString()) as CaptureProtocol.Message.Packet
    private fun beacon(ies: String = "000454657374030101DD054603000102"): CaptureProtocol.Message.Packet {
        val header = "80000000FFFFFFFFFFFF0011223344550011223344550000000000000000000064000100"
        val hex = header + ies
        return CaptureProtocol.Message.Packet(CaptureProtocol.Mode.WIFI, 18, 124000, -60, 6,
            hex.length / 2, hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }

    @Test fun recordsSurviveEveryUsbSplitBoundary() {
        val line = ble().toString() + "\r\n"
        for (split in 0..line.length) {
            val reader = CaptureLineReader(); val out = mutableListOf<String>()
            reader.feed(line.take(split).toByteArray(), split, out::add)
            val rest = line.drop(split).toByteArray(); reader.feed(rest, rest.size, out::add)
            assertEquals(1, out.size); assertNotNull(CaptureProtocol.parse(out.single()))
        }
    }
    @Test fun oversizeAndBinaryInputRecoverAtNextLine() {
        val reader = CaptureLineReader(); val out = mutableListOf<String>()
        val stream = ("x".repeat(CaptureProtocol.MAX_LINE + 1) + "\n\u0000bad\n" + ble() + "\n").toByteArray()
        reader.feed(stream, stream.size, out::add)
        assertEquals(2L, reader.rejected); assertEquals(1, out.size)
    }
    @Test fun rejectsInvalidVersionRangeHexAndNestedRecords() {
        for ((key, value) in listOf("v" to 2, "seq" to -1, "us" to -1, "rssi" to -300,
            "address" to "../file", "address_type" to 4, "event_type" to 4,
            "channel" to 37, "original_length" to 999, "data" to "GG", "seq" to 1.5)) {
            assertNull("$key=$value", CaptureProtocol.parse(ble().put(key, value).toString()))
        }
        assertNull(CaptureProtocol.parse("{\"v\":1,\"nested\":[]}"))
        assertNull(CaptureProtocol.parse("{\"v\":1,\"type\":\"state\",\"mode\":\"OFF\",\"channel\":4294967296}"))
    }
    @Test fun bleRetainsFullAdvertisementAndServiceData() {
        val p = packet(ble("0201060516BBB1AABB05FF46030102"))
        val observation = CaptureObservation.decode(p, 999)!!
        assertEquals("0201060516BBB1AABB05FF46030102", observation.rawHex)
        assertEquals(0x0346, observation.manufacturerId)
        assertTrue(observation.serviceUuids.contains("B1BB"))
        assertEquals("AABB", observation.facts.serviceData.single().dataHex)
        assertEquals("Random", observation.facts.addressType)
        assertEquals(0, observation.frequencyMhz) // NimBLE does not report the physical advertising channel.
        assertEquals(999L, observation.at)
    }
    @Test fun malformedBleIsArchivedButNotClassified() {
        val p = packet(ble("02010607FF460301"))
        assertNull(CaptureObservation.decode(p, 1))
        assertEquals("02010607FF460301", p.json().getString("data"))
    }
    @Test fun beaconUsesReceiverChannelAndVendorIe() {
        val p = beacon()
        val decoded = CaptureObservation.decode(p, 123)!!
        assertEquals("Test", decoded.name)
        assertEquals("00:11:22:33:44:55", decoded.mac)
        assertEquals(6, decoded.channel) // DS claimed channel 1.
        assertNull(decoded.facts.channelWidth) // No advertised AP width was decoded.
        assertEquals("46:03:00", decoded.vendorIeOuis.single())
        assertTrue(decoded.fresh)
        assertEquals(app.fieldwatch.domain.ObservationSource.USB_RECEIVER, decoded.source)
        assertNotNull(CaptureProtocol.parse(p.json().toString()))
    }
    @Test fun clientsDeauthAndFragmentsDoNotBecomeAccessPoints() {
        for (subtype in listOf(0x40, 0xC0, 0xA0)) {
            val p = beacon(); p.bytes[0] = subtype.toByte()
            assertNull(CaptureObservation.decode(p, 1))
            assertNotNull(CaptureProtocol.parse(p.json().toString()))
        }
        val fragment = beacon(); fragment.bytes[1] = 4
        assertNull(CaptureObservation.decode(fragment, 1))
    }
    @Test fun repeatedUsbBeaconsRefreshLiveHistory() {
        val store = app.fieldwatch.data.DeviceStore()
        val p = beacon()
        store.ingest(CaptureObservation.decode(p, 1000)!!, emptyList(), 45)
        val second = store.ingest(CaptureObservation.decode(p, 4000)!!, emptyList(), 45)
        assertEquals(4000L, second.lastSeen)
        assertEquals(2, second.hitCount)
    }
    @Test fun truncatedOrMalformedBeaconIsNotClassified() {
        assertNull(CaptureObservation.decode(beacon().copy(originalLength = 2000), 1))
        assertNull(CaptureObservation.decode(beacon("002154657374"), 1))
        assertNull(CaptureObservation.decode(beacon("000154000154"), 1)) // Duplicate SSID.
    }
    @Test fun unknownFirmwareAndWifiPayloadFramesAreRejected() {
        assertNull(CaptureProtocol.parse("{\"v\":1,\"type\":\"hello\",\"chip\":\"ESP32\",\"firmware\":\"x\"}"))
        val p = beacon(); p.bytes[0] = 8
        assertNull(CaptureProtocol.parse(p.json().toString()))
        assertNull(CaptureProtocol.parse(beacon().json().put("fcs_included", true).toString()))
    }
    @Test fun commandsAreBoundedToSupportedModesAndChannels() {
        assertEquals("START WIFI 0\n", CaptureProtocol.startCommand(CaptureProtocol.Mode.WIFI, 0))
        assertEquals("START WIFI 11\n", CaptureProtocol.startCommand(CaptureProtocol.Mode.WIFI, 11))
        assertEquals("START BLE 0\n", CaptureProtocol.startCommand(CaptureProtocol.Mode.BLE, 11))
        assertThrows(IllegalArgumentException::class.java) { CaptureProtocol.startCommand(CaptureProtocol.Mode.WIFI, 12) }
    }
    @Test fun packetsNeedHelloThenAnAcknowledgedMatchingMode() {
        val guard = CaptureSessionGuard(CaptureProtocol.Mode.BLE, 0)
        val p = packet(ble())
        guard.state(CaptureProtocol.Message.State("BLE", 0))
        assertFalse(guard.accept(p))
        guard.hello(CaptureProtocol.Message.Hello("test"))
        guard.state(CaptureProtocol.Message.State("WIFI", 0))
        assertFalse(guard.accept(p))
        guard.state(CaptureProtocol.Message.State("BLE", 0))
        assertTrue(guard.accept(p))
        assertFalse(guard.accept(beacon()))
    }
    @Test fun receiverRebootAndUnexpectedStopEndTheSession() {
        fun running(): CaptureSessionGuard = CaptureSessionGuard(CaptureProtocol.Mode.BLE, 0).apply {
            hello(CaptureProtocol.Message.Hello("test")); state(CaptureProtocol.Message.State("BLE", 0))
        }
        assertThrows(IllegalStateException::class.java) { running().stats(CaptureProtocol.Message.Stats(0, "OFF")) }
        assertThrows(IllegalStateException::class.java) { running().state(CaptureProtocol.Message.State("OFF", 0)) }
        assertThrows(IllegalStateException::class.java) { running().hello(CaptureProtocol.Message.Hello("test")) }
        val guard = running(); guard.accept(packet(ble()))
        assertThrows(IllegalStateException::class.java) { guard.accept(packet(ble()).copy(seq = 1)) }
    }
    @Test fun lossAccountingStartsAtTheFirstPacketInThisSession() {
        val guard = CaptureSessionGuard(CaptureProtocol.Mode.BLE, 0)
        guard.hello(CaptureProtocol.Message.Hello("test")); guard.state(CaptureProtocol.Message.State("BLE", 0))
        val p = packet(ble())
        guard.accept(p.copy(seq = 200))
        assertEquals(0L, guard.gaps)
        guard.accept(p.copy(seq = 204))
        assertEquals(3L, guard.gaps)
        assertThrows(IllegalStateException::class.java) { guard.accept(p.copy(seq = 205, us = 1)) }
    }
    @Test fun archiveRoundTripKeepsRawAndNeverSilentlyOverwrites() {
        val dir = Files.createTempDirectory("fieldwatch-capture-test").toFile()
        try {
            val archive = CaptureArchive(dir)
            repeat(2) {
                archive.start(JSONObject().put("type", "session")).use { session ->
                    session.append(packet(ble()).json())
                    session.finish(JSONObject().put("type", "end"))
                }
            }
            assertEquals(2, archive.files().size)
            archive.files().forEach { file ->
                val records = file.readLines().map(::JSONObject)
                assertEquals(3, records.size)
                assertEquals("0201060303BBB105FF46030102", records[1].getString("data"))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun captureQuotaStopsWithSpaceForFinalStatus() {
        val dir = Files.createTempDirectory("fieldwatch-capture-test").toFile()
        try {
            val file = dir.resolve("capture.jsonl")
            CaptureArchive.Session(file, 2200).use {
                assertThrows(IllegalStateException::class.java) { it.append(JSONObject().put("data", "x".repeat(200))) }
                it.finish(JSONObject().put("type", "end").put("reason", "quota"))
            }
            assertEquals("end", JSONObject(file.readText()).getString("type"))
        } finally { dir.deleteRecursively() }
    }
}
