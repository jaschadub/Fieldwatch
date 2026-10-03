package app.fieldwatch.radio

import app.fieldwatch.radio.usb.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CancellationException

class UsbInstallerTest {
    private fun bundle() = ReceiverFirmware.load { name -> File("src/main/assets", name).inputStream() }

    private class FakeRom : RomTransport {
        val ops = mutableListOf<Int>()
        val resets = mutableListOf<Pair<Boolean, Boolean>>()
        val rx = ArrayDeque<Byte>()
        val flashed = mutableMapOf<Int, ByteArray>()
        val registers = mutableMapOf(0x60008850L to (3L shl 18)) // C3 revision 0.3.
        var chip = 5L
        var flags = 0L
        var crypt = 0
        var flashId = 0x1640efL
        var corruptMd5 = false
        var dropOp: Int? = null
        var failOp: Int? = null
        var offset = 0
        var sequence = 0
        var ticks = 0L
        var fragment = 37
        override fun lines(dtr: Boolean, rts: Boolean) { resets += dtr to rts }
        override fun read(buffer: ByteArray): Int {
            ticks += 100
            var n = 0
            while (rx.isNotEmpty() && n < minOf(buffer.size, fragment)) buffer[n++] = rx.removeFirst()
            return n
        }
        override fun write(bytes: ByteArray) {
            val frames = mutableListOf<ByteArray>()
            RomSlipReader().feed(bytes, bytes.size, frames::add)
            assertEquals(1, frames.size)
            val p = frames.single()
            assertEquals(0, p[0].toInt())
            val op = p[1].toInt() and 255
            val data = p.copyOfRange(8, p.size)
            assertEquals(data.size, (p[2].toInt() and 255) or ((p[3].toInt() and 255) shl 8))
            ops += op
            if (op == dropOp) return
            var value = 0L
            var result = byteArrayOf()
            when (op) {
                8 -> { value = 0x55201207; assertEquals(36, data.size) }
                20 -> {
                    result = Esp32C3Installer.words(flags) + byteArrayOf(crypt.toByte()) + ByteArray(7) + Esp32C3Installer.words(chip, 1)
                }
                10 -> {
                    val addr = Esp32C3Installer.u32(data, 0)
                    value = when (addr) { 0x60002058L -> flashId; 0x60002000L -> 0; else -> registers[addr] ?: 0 }
                }
                9 -> registers[Esp32C3Installer.u32(data, 0)] = Esp32C3Installer.u32(data, 4)
                13 -> assertArrayEquals(ByteArray(8), data)
                11 -> assertEquals(0x400000L, Esp32C3Installer.u32(data, 4))
                2 -> {
                    assertEquals(20, data.size)
                    assertEquals(0, data.last().toInt())
                    offset = Esp32C3Installer.u32(data, 12).toInt()
                    val blocks = Esp32C3Installer.u32(data, 4).toInt()
                    flashed[offset] = ByteArray(blocks * 1024) { 0xff.toByte() }
                    sequence = 0
                }
                3 -> {
                    assertEquals(sequence.toLong(), Esp32C3Installer.u32(data, 4))
                    assertEquals(1024L, Esp32C3Installer.u32(data, 0))
                    val block = data.copyOfRange(16, data.size)
                    assertEquals(block.fold(0xef) { x, b -> x xor (b.toInt() and 255) }.toLong(), Esp32C3Installer.u32(p, 4))
                    block.copyInto(flashed.getValue(offset), sequence * 1024)
                    sequence++
                }
                19 -> {
                    val target = Esp32C3Installer.u32(data, 0).toInt()
                    val size = Esp32C3Installer.u32(data, 4).toInt()
                    result = (if (corruptMd5) "0".repeat(32) else
                        Esp32C3Installer.digest("MD5", flashed.getValue(target).copyOf(size))).toByteArray()
                }
                4 -> assertArrayEquals(Esp32C3Installer.words(1), data)
                else -> error("Unexpected write opcode $op")
            }
            // The physical C3 rejects FLASH_END after our verified-ROM write sequence.
            val status = if (op == 4) byteArrayOf(1, 6, 0, 0)
                else byteArrayOf(if (op == failOp) 1 else 0, 7, 0, 0)
            val payload = result + status
            val response = byteArrayOf(1, op.toByte(), payload.size.toByte(), 0) + Esp32C3Installer.words(value) + payload
            repeat(if (op == 8) 8 else 1) { RomSlipReader.encode(response).forEach(rx::addLast) }
        }
    }
    private fun installer(rom: FakeRom, active: () -> Unit = {}) = Esp32C3Installer(rom, active, { rom.ticks }, {})

    @Test fun bundledImagesHaveValidShaChipAndLayout() {
        val firmware = bundle()
        assertEquals("fieldwatch-ng-usb-0.1.0", firmware.version)
        assertEquals(listOf(0, 0x8000, 0x9000, 0x10000), firmware.parts.map { it.offset })
    }
    @Test fun installsActualBundleWithFragmentedAcksAndVerifiesEveryPart() {
        val rom = FakeRom()
        val parts = bundle().parts
        val progress = mutableListOf<Int>()
        installer(rom).install(parts, false) { _, p -> progress += p }
        parts.forEach { assertArrayEquals(it.bytes, rom.flashed.getValue(it.offset).copyOf(it.bytes.size)) }
        assertEquals(4, rom.ops.count { it == 19 })
        assertEquals(19, rom.ops.last())
        assertFalse(rom.ops.contains(4))
        assertEquals(96, progress.last())
        assertEquals(progress.sorted(), progress)
        assertEquals(listOf(false to false, true to false, true to true, false to true, false to false), rom.resets)
    }
    @Test fun manualBootDoesNotResetBeforeFlashing() {
        val rom = FakeRom()
        installer(rom).install(bundle().parts, true) { _, _ -> }
        assertTrue(rom.resets.isEmpty())
        installer(rom).reboot()
        assertEquals(listOf(false to true, false to false), rom.resets)
    }
    @Test fun wrongChipIsRejectedBeforeAnyRegisterOrFlashWrite() {
        val rom = FakeRom().apply { chip = 9 }
        assertThrows(IllegalStateException::class.java) { installer(rom).install(bundle().parts, true) { _, _ -> } }
        assertEquals(listOf(8, 20), rom.ops)
    }
    @Test fun securedDevicesNeverReachErase() {
        for ((flags, crypt) in listOf(1L to 0, 4L to 0, 0L to 1, 0L to 2)) {
            val rom = FakeRom().apply { this.flags = flags; this.crypt = crypt }
            assertThrows(IllegalStateException::class.java) { installer(rom).install(bundle().parts, true) { _, _ -> } }
            assertFalse(rom.ops.contains(2))
        }
    }
    @Test fun incompatibleChipRevisionIsRejectedBeforeWrites() {
        val rom = FakeRom().apply { registers[0x60008850] = 0 }
        assertThrows(IllegalStateException::class.java) { installer(rom).install(bundle().parts, true) { _, _ -> } }
        assertFalse(rom.ops.contains(2))
        assertFalse(rom.ops.contains(9))
    }
    @Test fun wrongFlashCapacityNeverReachesErase() {
        val rom = FakeRom().apply { flashId = 0x1540ef }
        assertThrows(IllegalStateException::class.java) { installer(rom).install(bundle().parts, true) { _, _ -> } }
        assertFalse(rom.ops.contains(2))
    }
    @Test fun md5MismatchStopsBeforeNextRegionOrReboot() {
        val rom = FakeRom().apply { corruptMd5 = true }
        assertThrows(IllegalStateException::class.java) { installer(rom).install(bundle().parts, true) { _, _ -> } }
        assertEquals(1, rom.ops.count { it == 2 })
        assertFalse(rom.ops.contains(4))
        assertTrue(rom.resets.isEmpty())
    }
    @Test fun missingAckAbortsWithoutSendingTheNextBlock() {
        val rom = FakeRom().apply { dropOp = 3 }
        assertThrows(RomTimeout::class.java) { installer(rom).install(bundle().parts, true) { _, _ -> } }
        assertEquals(1, rom.ops.count { it == 3 })
        assertFalse(rom.ops.contains(4))
    }
    @Test fun failedRomStatusAborts() {
        val rom = FakeRom().apply { failOp = 2 }
        assertThrows(IllegalStateException::class.java) { installer(rom).install(bundle().parts, true) { _, _ -> } }
        assertFalse(rom.ops.contains(3))
    }
    @Test fun cancellationStopsWrites() {
        val rom = FakeRom()
        assertThrows(CancellationException::class.java) {
            installer(rom) { if (rom.ops.contains(3)) throw CancellationException("Detached") }
                .install(bundle().parts, true) { _, _ -> }
        }
        assertEquals(1, rom.ops.count { it == 3 })
        assertFalse(rom.ops.contains(4))
    }
    @Test fun corruptedBundleNeverReachesUsb() {
        assertThrows(IllegalStateException::class.java) {
            ReceiverFirmware.load { name ->
                val bytes = File("src/main/assets", name).readBytes()
                if (name.endsWith("receiver.bin")) bytes[50] = (bytes[50].toInt() xor 1).toByte()
                bytes.inputStream()
            }
        }
    }
    @Test fun unexpectedLayoutOrImageChipIsRejected() {
        val parts = bundle().parts
        assertThrows(IllegalArgumentException::class.java) { Esp32C3Installer.validateParts(parts.reversed()) }
        val bad = parts[0].bytes.copyOf().apply { this[12] = 9 }
        assertThrows(IllegalArgumentException::class.java) { Esp32C3Installer.validateParts(listOf(parts[0].copy(bytes = bad)) + parts.drop(1)) }
    }
    @Test fun slipEscapesAllBytesAcrossEveryBoundary() {
        val payload = ByteArray(256) { it.toByte() }
        val encoded = RomSlipReader.encode(payload)
        for (split in encoded.indices) {
            val out = mutableListOf<ByteArray>(); val decoder = RomSlipReader()
            decoder.feed(encoded.copyOfRange(0, split), split, out::add)
            val rest = encoded.copyOfRange(split, encoded.size)
            decoder.feed(rest, rest.size, out::add)
            assertEquals(1, out.size); assertArrayEquals(payload, out.single())
        }
    }
    @Test fun slipRecoversFromMalformedEscapesAndOversizeNoise() {
        val good = byteArrayOf(1, 2, 3)
        val bad = byteArrayOf(0xc0.toByte(), 0xdb.toByte(), 1) + RomSlipReader.encode(ByteArray(5000))
        val stream = "boot log".toByteArray() + bad + RomSlipReader.encode(good)
        val out = mutableListOf<ByteArray>()
        RomSlipReader().feed(stream, stream.size, out::add)
        assertEquals(1, out.size); assertArrayEquals(good, out.single())
    }
}
