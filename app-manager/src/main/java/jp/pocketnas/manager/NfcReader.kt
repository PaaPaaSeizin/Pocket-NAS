package jp.pocketnas.manager

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import org.json.JSONObject

class NfcReader(
    private val activity: Activity,
    private val onResult: (host: String?, pin: String?) -> Unit
) {
    companion object {
        private val SELECT_AID = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x05,
            0xF0.toByte(), 0x50, 0x4E, 0x41, 0x53
        )
        private val GET_PAIRING = byteArrayOf(
            0x00, 0xCA.toByte(), 0x00, 0x01, 0x00
        )
    }

    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private var enabled = false

    fun isAvailable(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    fun start() {
        val a = adapter ?: run { onResult(null, null); return }
        if (!a.isEnabled) { onResult(null, null); return }
        val opts = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }
        a.enableReaderMode(
            activity,
            { tag -> handle(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            opts
        )
        enabled = true
    }

    fun stop() {
        if (enabled) {
            try { adapter?.disableReaderMode(activity) } catch (_: Exception) {}
            enabled = false
        }
    }

    private fun handle(tag: Tag) {
        Thread {
            try {
                val iso = IsoDep.get(tag) ?: run { onResult(null, null); return@Thread }
                iso.connect()
                iso.timeout = 5000
                val r1 = iso.transceive(SELECT_AID)
                if (!isOk(r1)) { iso.close(); onResult(null, null); return@Thread }
                val r2 = iso.transceive(GET_PAIRING)
                iso.close()
                if (!isOk(r2)) { onResult(null, null); return@Thread }
                val json = String(r2, 0, r2.size - 2, Charsets.UTF_8)
                val o = JSONObject(json)
                val h = o.getString("h")
                val p = o.optInt("p", 8080)
                onResult("http://$h:$p", null)
            } catch (_: Exception) {
                onResult(null, null)
            }
        }.start()
    }

    private fun isOk(r: ByteArray): Boolean {
        if (r.size < 2) return false
        return (r[r.size - 2].toInt() and 0xFF) == 0x90 &&
                (r[r.size - 1].toInt() and 0xFF) == 0x00
    }
}