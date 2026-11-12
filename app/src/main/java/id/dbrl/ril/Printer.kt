package id.dbrl.ril

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cetak langsung ke printer struk di LAN toko (port 9100), menggantikan penghubung cetak di laptop.
 *
 * Halaman kasir sudah merakit byte ESC/POS sendiri; Ril hanya meneruskannya. Alamat printer
 * dipegang Ril (menu pemilik / hasil cari), tidak pernah ditentukan halaman.
 *
 * Printer yang pindah IP (DHCP) dicari lagi otomatis: pindai port 9100 di subnet Wi-Fi, lalu
 * pilih yang jelas Epson. Android 10+ tidak memberi akses tabel ARP, jadi tidak bisa mengenali
 * lewat MAC seperti penghubung cetak di Windows. Obat akarnya: kunci IP printer di router.
 */
object Printer {
    const val PORT = 9100
    private const val BATAS_SAMBUNG = 1500
    private const val BATAS_PINDAI = 350

    /** Jaringan Wi-Fi toko (bukan Tailscale), supaya soket ke LAN tidak lewat VPN. */
    fun wifi(c: Context): Network? {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun soket(n: Network?): Socket = n?.socketFactory?.createSocket() ?: Socket()

    fun pisah(alamat: String): Pair<String, Int> {
        val bagian = alamat.trim().split(":")
        return bagian[0] to (bagian.getOrNull(1)?.toIntOrNull() ?: PORT)
    }

    fun terbuka(n: Network?, host: String, port: Int = PORT, batas: Int = BATAS_SAMBUNG): Boolean = try {
        soket(n).use { it.connect(InetSocketAddress(host, port), batas); true }
    } catch (e: Exception) {
        false
    }

    /** Kirim byte ke printer. Melempar kalau printer tidak bisa dihubungi. */
    fun kirim(n: Network?, alamat: String, data: ByteArray) {
        val (host, port) = pisah(alamat)
        soket(n).use { s ->
            s.connect(InetSocketAddress(host, port), BATAS_SAMBUNG)
            s.soTimeout = 8000
            s.getOutputStream().apply { write(data); flush() }
        }
    }

    /** Nama model kalau halaman web printer menyebut EPSON/TM-, "" kalau tidak tahu. */
    fun kenaliEpson(n: Network?, host: String): String = try {
        soket(n).use { s ->
            s.connect(InetSocketAddress(host, 80), 800)
            s.soTimeout = 1200
            s.getOutputStream().write("GET / HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
            val teks = s.getInputStream().readBytes().take(20000).toByteArray().toString(Charsets.ISO_8859_1)
            Regex("TM-[A-Z0-9][A-Z0-9-]*").find(teks)?.value ?: if (teks.contains("epson", true)) "EPSON" else ""
        }
    } catch (e: Exception) {
        ""
    }

    data class Hasil(val alamat: String = "", val model: String = "", val semua: List<String> = emptyList(), val pesan: String = "")

    /**
     * Cari printer di subnet Wi-Fi. Satu-satunya perangkat port 9100, atau satu-satunya Epson,
     * dipakai. Lebih dari satu kandidat = pemilik memilih (printer kantor juga membuka 9100).
     */
    fun cari(c: Context): Hasil {
        val n = wifi(c) ?: return Hasil(pesan = "Tablet belum tersambung ke Wi-Fi toko.")
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ip = cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }
            ?: return Hasil(pesan = "Alamat Wi-Fi tablet tidak terbaca.")
        val b = ip.address
        val awalan = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val sendiri = b[3].toInt() and 255
        val kolam = Executors.newFixedThreadPool(48)
        val ketemu = try {
            val tugas = (1..254).filter { it != sendiri }.map { akhir ->
                Callable { val h = awalan + akhir; if (terbuka(n, h, PORT, BATAS_PINDAI)) h else null }
            }
            kolam.invokeAll(tugas, 20, TimeUnit.SECONDS).mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            kolam.shutdownNow()
        }
        if (ketemu.isEmpty()) return Hasil(pesan = "Printer tidak ditemukan. Cek printer menyala dan kabel LAN-nya.")
        if (ketemu.size == 1) return Hasil(alamat = "${ketemu[0]}:$PORT", model = kenaliEpson(n, ketemu[0]), semua = ketemu)
        val epson = ketemu.map { it to kenaliEpson(n, it) }.filter { it.second.isNotEmpty() }
        if (epson.size == 1) return Hasil(alamat = "${epson[0].first}:$PORT", model = epson[0].second, semua = ketemu)
        return Hasil(semua = ketemu, pesan = "Ada ${ketemu.size} perangkat cetak. Pilih printer kasir di menu pemilik.")
    }
}
