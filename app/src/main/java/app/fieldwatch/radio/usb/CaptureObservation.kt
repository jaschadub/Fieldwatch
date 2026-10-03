package app.fieldwatch.radio.usb

import app.fieldwatch.domain.Observation
import app.fieldwatch.domain.ObservationSource
import app.fieldwatch.domain.RadioFacts
import app.fieldwatch.domain.RadioKind
import app.fieldwatch.radio.BleAdParser
import app.fieldwatch.radio.WifiIeParser

/** Research frames are always logged, but only BLE and AP advertisements enter the existing UI. */
object CaptureObservation {
    fun decode(p: CaptureProtocol.Message.Packet, receivedAt: Long): Observation? {
        if (p.radio == CaptureProtocol.Mode.BLE) {
            var offset = 0
            while (offset < p.bytes.size) {
                val length = p.bytes[offset].toInt() and 255
                if (length == 0) break
                if (offset + length >= p.bytes.size) return null
                offset += length + 1
            }
            val parsed = BleAdParser.parse(p.bytes)
            val mfg = parsed.mfg.firstOrNull()
            return Observation(
                RadioKind.BLE, p.address, parsed.localName.orEmpty(), p.rssi, 0, 0, false,
                (parsed.uuids + parsed.serviceData.map { it.uuid }).distinct(), mfg?.companyId,
                mfg?.dataHex.orEmpty(), CaptureProtocol.hex(p.bytes), "USB ESP32-C3", receivedAt,
                facts = RadioFacts(
                    txPowerDbm = parsed.txPower, advFlags = parsed.flags, appearance = parsed.appearance,
                    addressType = if (p.addressType == 0 || p.addressType == 2) "Public" else "Random",
                    advertisingIntervalMs = parsed.advertisingIntervalMs,
                    connectable = p.eventType == 0 || p.eventType == 1, primaryPhy = "LE 1M",
                    deviceClass = parsed.deviceClass, mfgRecords = parsed.mfg, serviceData = parsed.serviceData,
                ),
                source = ObservationSource.USB_RECEIVER,
            )
        }
        val b = p.bytes
        // Beacon / probe response only; a probe request or deauth is not an AP sighting.
        if (p.truncated || b.size < 36 || (b[0].toInt() and 255) !in listOf(0x80, 0x50)) return null
        if (b[1].toInt() and 0x47 != 0 || b[22].toInt() and 15 != 0) return null
        val bssid = b.copyOfRange(16, 22)
        if (bssid[0].toInt() and 1 != 0 || bssid.all { it == 0.toByte() }) return null
        val ies = ArrayList<WifiIeParser.Ie>()
        var offset = 36
        while (offset < b.size) {
            if (offset + 2 > b.size) return null
            val length = b[offset + 1].toInt() and 255
            if (offset + 2 + length > b.size) return null
            ies += WifiIeParser.Ie(b[offset].toInt() and 255, b.copyOfRange(offset + 2, offset + 2 + length))
            offset += length + 2
        }
        val ssids = ies.filter { it.id == 0 }
        if (ssids.size != 1 || ssids[0].bytes.size > 32) return null
        val name = ssids[0].bytes.toString(Charsets.UTF_8).trim('\u0000')
            .map { if (it.isISOControl()) ' ' else it }.joinToString("")
        val parsed = WifiIeParser.parseIes(ies, null)
        // Use receive channel, not an untrusted advertised DS channel.
        return Observation(
            RadioKind.WIFI, bssid.joinToString(":") { "%02X".format(it) }, name, p.rssi,
            p.channel, 2407 + p.channel * 5, name.isBlank(), emptyList(), null, "",
            CaptureProtocol.hex(b), "USB ESP32-C3", receivedAt,
            vendorIeOuis = parsed.vendorIes.map { it.oui }.distinct(),
            facts = RadioFacts(supportedRates = parsed.rates, security = parsed.security,
                vendorIes = parsed.vendorIes),
            source = ObservationSource.USB_RECEIVER,
        )
    }
}
