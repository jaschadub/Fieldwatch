package app.fieldwatch.radio.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable

/** Native ESP32-C3 USB Serial/JTAG only. Does not claim arbitrary USB serial or storage devices. */
internal class CdcSerial(manager: UsbManager, device: UsbDevice) : Closeable, RomTransport {
    private val connection: UsbDeviceConnection = manager.openDevice(device) ?: error("Cannot open USB device")
    private var control: UsbInterface? = null
    private var data: UsbInterface? = null
    private lateinit var input: UsbEndpoint
    private lateinit var output: UsbEndpoint

    init {
        try {
            require(supported(device)) { "Use the ESP32-C3 native USB connector" }
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            control = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
                ?: error("USB CDC control interface missing")
            data = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
                ?: error("USB CDC data interface missing")
            check(connection.claimInterface(control, true)) { "USB control interface is busy" }
            check(connection.claimInterface(data, true)) { "USB data interface is busy" }
            val endpoints = (0 until data!!.endpointCount).map(data!!::getEndpoint)
            input = endpoints.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            output = endpoints.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            // CDC line coding: 115200, one stop bit, no parity, eight data bits.
            val coding = byteArrayOf(0x00, 0xc2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
            check(connection.controlTransfer(0x21, 0x20, 0, control!!.id, coding, coding.size, 1000) == coding.size)
            // DTR asserted, RTS clear. Never request the ESP bootloader/reset RTS+DTR sequence.
            check(connection.controlTransfer(0x21, 0x22, 1, control!!.id, null, 0, 1000) >= 0)
        } catch (e: Exception) {
            close(); throw e
        }
    }

    override fun read(buffer: ByteArray): Int = connection.bulkTransfer(input, buffer, buffer.size, 100)
    fun write(command: String) = write(command.toByteArray(Charsets.US_ASCII))
    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = connection.bulkTransfer(output, bytes, offset, bytes.size - offset, 300)
            check(count > 0) { "USB write failed" }
            offset += count
        }
    }
    override fun lines(dtr: Boolean, rts: Boolean) {
        val value = (if (dtr) 1 else 0) or (if (rts) 2 else 0)
        check(connection.controlTransfer(0x21, 0x22, value, control!!.id, null, 0, 1000) >= 0) {
            "USB reset failed. Hold BOOT, tap RESET, release BOOT, then retry in manual mode."
        }
    }
    override fun close() {
        data?.let { runCatching { connection.releaseInterface(it) } }
        control?.let { runCatching { connection.releaseInterface(it) } }
        connection.close()
    }
    companion object {
        fun supported(device: UsbDevice): Boolean = device.vendorId == 0x303a && device.productId == 0x1001
    }
}
