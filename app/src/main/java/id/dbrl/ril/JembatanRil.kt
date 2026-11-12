package id.dbrl.ril

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Jembatan halaman kasir ↔ Ril (`window.RilTablet`).
 *
 * Aturan aman: halaman hanya menyerahkan isi struk (byte ESC/POS). Alamat printer dipegang Ril,
 * tidak pernah dari halaman, jadi halaman tidak bisa dipakai menyentuh perangkat lain di LAN.
 * Cetak hanya boleh dari halaman kasir milik toko (`boleh` diatur MainActivity per halaman).
 *
 * Cetak dan cek status berjalan di latar (soket + pencarian printer bisa beberapa detik) lalu
 * menjawab lewat `window.__rilJawab(id, hasil)`, supaya layar kasir tidak membeku.
 */
class JembatanRil(private val c: Context, private val setelan: Setelan, private val web: WebView) {
    @Volatile var boleh = false
    private val latar = Executors.newSingleThreadExecutor()

    private fun balas(id: String, json: String) {
        web.post { web.evaluateJavascript("window.__rilJawab && window.__rilJawab(${JSONObject.quote(id)}, $json)", null) }
    }

    @JavascriptInterface
    fun cetakNanti(id: String, jobId: String?, isiBase64: String?) {
        latar.execute { balas(id, cetak(jobId, isiBase64)) }
    }

    @JavascriptInterface
    fun statusNanti(id: String) {
        latar.execute { balas(id, status()) }
    }

    private val kunci = Any()
    /** Job yang sudah keluar (antrean kasir mengulang job yang sama kalau jawabannya hilang). */
    private val terkirim = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 200
    }

    @JavascriptInterface fun tema(): String = setelan.tema

    @JavascriptInterface fun bisaCetak(): Boolean = boleh

    private fun cetak(jobId: String?, isiBase64: String?): String {
        if (!boleh) return jawab(false, "Cetak hanya dari halaman kasir.", false)
        if (jobId.isNullOrBlank() || isiBase64.isNullOrBlank()) return jawab(false, "Isi cetak kosong.", false)
        val data = try { Base64.decode(isiBase64, Base64.DEFAULT) } catch (e: Exception) {
            return jawab(false, "Isi cetak rusak.", false)
        }
        synchronized(kunci) {
            if (terkirim.containsKey(jobId)) return JSONObject().put("ok", true).put("duplicate", true).toString()
            val n = Printer.wifi(c)
            val alamat = setelan.printer
            val berhasil = alamat.isNotEmpty() && runCatching { Printer.kirim(n, alamat, data) }.isSuccess
            if (!berhasil) {
                // Printer pindah IP atau belum pernah dicari: cari sekali, lalu coba lagi.
                val h = Printer.cari(c)
                if (h.alamat.isEmpty()) return jawab(false, h.pesan, true)
                setelan.printer = h.alamat
                if (runCatching { Printer.kirim(n, h.alamat, data) }.isFailure) {
                    return jawab(false, "Printer tidak menerima nota. Cek kertas/kabel lalu coba lagi.", true)
                }
            }
            terkirim[jobId] = true
            return JSONObject().put("ok", true).put("duplicate", false).toString()
        }
    }

    /** Status printer untuk indikator kasir. Printer tidak menjawab = dicari ulang otomatis. */
    private fun status(): String {
        if (!boleh) return JSONObject().put("printer_status", "unreachable").put("printer_detail", "Bukan halaman kasir.").toString()
        val n = Printer.wifi(c)
        val lama = setelan.printer
        if (lama.isNotEmpty()) {
            val (h, p) = Printer.pisah(lama)
            if (Printer.terbuka(n, h, p)) return sehat(lama, "", false)
        }
        val hasil = Printer.cari(c)
        if (hasil.alamat.isEmpty()) {
            return JSONObject().put("printer_status", "unreachable").put("printer", lama)
                .put("printer_detail", hasil.pesan).toString()
        }
        setelan.printer = hasil.alamat
        return sehat(hasil.alamat, hasil.model, lama.isNotEmpty() && lama != hasil.alamat)
    }

    private fun sehat(alamat: String, model: String, pindah: Boolean) = JSONObject()
        .put("printer_status", "ok").put("printer", alamat)
        .put("printer_name", if (model.isNotEmpty()) "$model (langsung dari Ril)" else "Printer struk (langsung dari Ril)")
        .put("printer_detail", if (pindah) "Printer pindah alamat; sudah ditemukan dan dipakai." else "")
        .toString()

    private fun jawab(ok: Boolean, pesan: String, ulang: Boolean) =
        JSONObject().put("ok", ok).put("pesan", pesan).put("retryable", ulang).toString()
}
