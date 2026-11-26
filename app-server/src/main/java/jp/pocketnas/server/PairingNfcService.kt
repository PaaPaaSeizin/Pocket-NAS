package jp.pocketnas.server

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

class PairingNfcService : HostApduService() {
    companion object {
        private val SELECT_AID = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x05,
            0xF0.toByte(), 0x50, 0x4E, 0x41, 0x53
        )
        private val GET_PAIRING = byteArrayOf(
            0x00, 0xCA.toByte(), 0x00, 0x01, 0x00
        )
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_UNKNOWN = byteArrayOf(0x6F, 0x00)
        private val SW_NOT_READY = byteArrayOf(0x69, 0x85.toByte())
    }

    override fun processCommandApdu(apdu: ByteArray?, extras: Bundle?): ByteArray {
        if (apdu == null) return SW_UNKNOWN
        return when {
            apdu.contentEquals(SELECT_AID) -> SW_OK
            apdu.contentEquals(GET_PAIRING) -> buildPayload()
            else -> SW_UNKNOWN
        }
    }

    private fun buildPayload(): ByteArray {
        val ip = wifiIp() ?: return SW_NOT_READY
        val json = """{"h":"$ip","p":8080,"v":1}"""
        return json.toByteArray(Charsets.UTF_8) + SW_OK
    }

    private fun wifiIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.name.startsWith("wlan") && it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull()?.hostAddress
    } catch (_: Exception) { null }

    override fun onDeactivated(reason: Int) {}
}