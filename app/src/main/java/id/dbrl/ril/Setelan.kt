package id.dbrl.ril

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** Setelan per perangkat: mode, alamat halaman, dan PIN pemilik (disimpan sebagai hash bergaram). */
class Setelan(context: Context) {
    private val sp = context.getSharedPreferences("ril", Context.MODE_PRIVATE)

    /** Mode yang boleh dipakai di tablet ini: keduanya (bisa pindah sendiri), kasir saja, atau gudang saja. */
    var pilihanMode: String
        get() = sp.getString("pilihan_mode", PILIHAN_KEDUANYA) ?: PILIHAN_KEDUANYA
        set(v) = sp.edit().putString("pilihan_mode", v).apply()

    val bisaPindah: Boolean get() = pilihanMode == PILIHAN_KEDUANYA

    /** Mode yang boleh dibuka di tablet ini (urutan tab bilah atas). Siapkan ikut kasir. */
    val modeBoleh: List<String> get() = when (pilihanMode) {
        MODE_KASIR -> listOf(MODE_KASIR, MODE_SIAPKAN)
        MODE_GUDANG -> listOf(MODE_GUDANG)
        else -> listOf(MODE_KASIR, MODE_GUDANG, MODE_SIAPKAN)
    }

    /** Mode yang sedang tampil, selalu salah satu dari modeBoleh. */
    var mode: String
        get() {
            val m = sp.getString("mode", null)
            return if (m != null && m in modeBoleh) m else modeBoleh.first()
        }
        set(v) = sp.edit().putString("mode", v).apply()

    var alamatGudang: String
        get() = sp.getString("alamat_gudang", ALAMAT_GUDANG) ?: ALAMAT_GUDANG
        set(v) = sp.edit().putString("alamat_gudang", v.trim()).apply()

    var alamatKasir: String
        get() = sp.getString("alamat_kasir", ALAMAT_KASIR) ?: ALAMAT_KASIR
        set(v) = sp.edit().putString("alamat_kasir", v.trim()).apply()

    /** Halaman Siapkan ada di server gudang (tanpa login, PIN per aksi). */
    val alamatSiapkan: String get() {
        val u = android.net.Uri.parse(alamatGudang)
        return "${u.scheme}://${u.authority}/gudang/siapkan/"
    }

    val alamat: String get() = when (mode) {
        MODE_KASIR -> alamatKasir
        MODE_SIAPKAN -> alamatSiapkan
        else -> alamatGudang
    }

    /** Printer struk (host:port) hasil cari atau isian pemilik. Kosong = cari otomatis saat cetak. */
    var printer: String
        get() = sp.getString("printer", "") ?: ""
        set(v) = sp.edit().putString("printer", v.trim()).apply()

    /** Warna tablet (lihat TEMA); halaman kasir membacanya lewat RilTablet.tema(). */
    var tema: String
        get() = sp.getString("tema", TEMA[0].kunci) ?: TEMA[0].kunci
        set(v) = sp.edit().putString("tema", v).apply()

    val warnaTema: String get() = (TEMA.firstOrNull { it.kunci == tema } ?: TEMA[0]).warna

    /** Layar mati setelah N menit tidak disentuh; 0 = selalu nyala. */
    var layarMati: Int
        get() = sp.getInt("layar_mati", 2)
        set(v) = sp.edit().putInt("layar_mati", v).apply()

    /** Kecerahan layar dalam persen (5-100), OTOMATIS = ikut cahaya ruangan, BELUM = tidak diubah Ril. */
    var kecerahan: Int
        get() = sp.getInt("kecerahan", KECERAHAN_BELUM)
        set(v) = sp.edit().putInt("kecerahan", v).apply()

    var nyalaSaatCas: Boolean
        get() = sp.getBoolean("nyala_cas", true)
        set(v) = sp.edit().putBoolean("nyala_cas", v).apply()

    val punyaPin: Boolean get() = sp.contains("pin_hash")

    fun simpanPin(pin: String) {
        val garam = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit().putString("pin_garam", garam.hex()).putString("pin_hash", hash(garam.hex(), pin)).apply()
    }

    fun cocokPin(pin: String): Boolean {
        val garam = sp.getString("pin_garam", null) ?: return false
        val simpan = sp.getString("pin_hash", null) ?: return false
        return MessageDigest.isEqual(simpan.toByteArray(), hash(garam, pin).toByteArray())
    }

    private fun hash(garam: String, pin: String): String {
        var b = (garam + pin).toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        repeat(50_000) { b = md.digest(b) }
        return b.hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    data class Tema(val kunci: String, val nama: String, val warna: String)

    companion object {
        /** 8 warna tablet; versi gelapnya diatur di CSS kasir (data-warna). Warna bilah = versi terang. */
        val TEMA = listOf(
            Tema("toska", "Toska", "#0F766E"),
            Tema("biru", "Biru", "#1D6FD6"),
            Tema("navy", "Biru tua", "#1E3A8A"),
            Tema("ungu", "Ungu", "#6D28D9"),
            Tema("pink", "Pink", "#C2185B"),
            Tema("jingga", "Jingga", "#C2410C"),
            Tema("cokelat", "Cokelat", "#8A5A3B"),
            Tema("abu", "Abu", "#475569"),
        )

        const val KECERAHAN_OTOMATIS = -1
        const val KECERAHAN_BELUM = -2

        const val MODE_GUDANG = "gudang"
        const val MODE_KASIR = "kasir"
        const val MODE_SIAPKAN = "siapkan"
        const val PILIHAN_KEDUANYA = "keduanya"
        const val ALAMAT_GUDANG = "https://server-rumah.tail8bf945.ts.net:8443/gudang/"
        const val ALAMAT_KASIR = "https://server-rumah.tail8bf945.ts.net/"
        const val PAKET_TAILSCALE = "com.tailscale.ipn"
    }
}
