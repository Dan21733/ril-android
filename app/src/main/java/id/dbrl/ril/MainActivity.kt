package id.dbrl.ril

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Satu layar: halaman kasir/gudang di WebView, dikunci sebagai satu-satunya aplikasi.
 * Kalau tablet boleh dua mode, bilah atas berisi tab Kasir | Gudang untuk pindah sendiri.
 * Setiap kali masuk ke Gudang, sesi gudang sebelumnya dikeluarkan: harus masuk lagi dengan PIN.
 * Menu pemilik: ketuk pojok kanan atas 5 kali dalam 3 detik, lalu masukkan PIN pemilik.
 */
class MainActivity : Activity() {
    private lateinit var setelan: Setelan
    private lateinit var web: WebView
    private lateinit var galat: LinearLayout
    private lateinit var galatTeks: TextView
    private lateinit var bilah: LinearLayout
    private lateinit var tabKasir: TextView
    private lateinit var tabGudang: TextView

    /** Halaman gudang berikutnya yang selesai dimuat harus berakhir di halaman masuk (PIN). */
    private var wajibKeluarGudang = true
    /** Riwayat mundur dibuang setelah halaman berikutnya selesai dimuat (pindah mode / muat ulang). */
    private var bersihkanRiwayat = false

    /** Mode perawatan: kunci dilepas sampai pemilik memilih "Kunci lagi" (tidak bertahan setelah restart). */
    private var perawatan = false

    private var ketukan = 0
    private var ketukanMulai = 0L
    private var salahPin = 0
    private var terkunciSampai = 0L
    private var unggahan: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setelan = Setelan(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(bangunLayar())
        siapkanWeb()
        perbaruiBilah()
        web.loadUrl(setelan.alamat)
        if (!setelan.punyaPin) mintaPinBaru(wajib = true)
    }

    override fun onResume() {
        super.onResume()
        kunci()   // kembali dari pengaturan Wi-Fi/Tailscale: kunci dipasang lagi
    }

    private fun kunci() {
        if (perawatan || !Kebijakan.pemilik(this)) return
        Kebijakan.terapkan(this)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            try { startLockTask() } catch (e: Exception) { }
        }
    }

    private fun lepasKunci() {
        try { stopLockTask() } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ layar

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun bangunLayar(): View {
        val akar = FrameLayout(this)
        val kolom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        akar.addView(kolom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bilah = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#2A2724"))
            setPadding(dp(8), 0, dp(64), 0)
        }
        tabKasir = buatTab("Kasir") { gantiKe(Setelan.MODE_KASIR) }
        tabGudang = buatTab("Gudang") { gantiKe(Setelan.MODE_GUDANG) }
        bilah.addView(tabKasir)
        bilah.addView(tabGudang)
        kolom.addView(bilah, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        val isi = FrameLayout(this)
        kolom.addView(isi, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        web = WebView(this)
        isi.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        galat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F5F3EF"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        galatTeks = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#2A2724"))
        }
        val coba = Button(this).apply {
            text = "Coba lagi"
            setOnClickListener { galat.visibility = View.GONE; bersihkanRiwayat = true; web.loadUrl(setelan.alamat) }
        }
        galat.addView(galatTeks)
        galat.addView(coba, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        isi.addView(galat, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Pemicu menu pemilik: kotak tak terlihat di pojok kanan atas.
        val pojok = View(this).apply { setOnClickListener { ketukPojok() } }
        akar.addView(pojok, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))
        return akar
    }

    private fun buatTab(judul: String, klik: () -> Unit) = TextView(this).apply {
        text = judul
        textSize = 17f
        gravity = Gravity.CENTER
        setPadding(dp(22), 0, dp(22), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setOnClickListener { klik() }
    }

    private fun perbaruiBilah() {
        bilah.visibility = if (setelan.bisaPindah) View.VISIBLE else View.GONE
        for ((tab, m) in listOf(tabKasir to Setelan.MODE_KASIR, tabGudang to Setelan.MODE_GUDANG)) {
            val aktif = setelan.mode == m
            tab.setTextColor(Color.parseColor(if (aktif) "#FFFFFF" else "#A8A29A"))
            tab.setTypeface(null, if (aktif) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tab.setBackgroundColor(Color.parseColor(if (aktif) "#C2571A" else "#2A2724"))
        }
    }

    /** Pindah mode. Keluar dari Gudang = sesi gudang ditutup, jadi masuk lagi wajib PIN. */
    private fun gantiKe(baru: String) {
        if (baru == setelan.mode) return
        val dariGudang = setelan.mode == Setelan.MODE_GUDANG
        setelan.mode = baru
        perbaruiBilah()
        if (dariGudang) keluarGudang { bukaMode() } else bukaMode()
    }

    private fun bukaMode() {
        galat.visibility = View.GONE
        bersihkanRiwayat = true
        if (setelan.mode == Setelan.MODE_GUDANG) wajibKeluarGudang = true
        web.loadUrl(setelan.alamat)
    }

    /**
     * Tutup sesi gudang: kirim formulir Keluar dari halaman yang sedang terbuka (keepalive: tetap
     * terkirim walau halaman langsung diganti), lalu hapus cookie sesinya sebagai cadangan.
     */
    private fun keluarGudang(lanjut: () -> Unit) {
        val js = "(function(){var f=document.querySelector('form[action\$=\"/keluar/\"]');if(!f)return false;" +
            "fetch(f.action,{method:'POST',body:new URLSearchParams(new FormData(f)),credentials:'same-origin',keepalive:true});return true;})()"
        web.evaluateJavascript(js) {
            web.postDelayed({
                val cm = CookieManager.getInstance()
                cm.setCookie(setelan.alamatGudang, "gudang_sessionid=; Max-Age=0; Path=/; Secure; HttpOnly")
                cm.flush()
                lanjut()
            }, 400)
        }
    }

    private fun halamanMasuk(url: String?) = url != null && (url.contains("/masuk") || url.contains("/login"))

    private fun siapkanWeb() {
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            builtInZoomControls = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // Hanya server toko yang boleh dibuka; tautan lain diabaikan.
                val boleh = Uri.parse(setelan.alamat).host
                return req.url.host != boleh
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (!req.isForMainFrame) return
                // Mundur ke halaman hasil kirim formulir: bukan masalah sambungan, buka ulang saja.
                if (err.description?.contains("ERR_CACHE_MISS") == true) { bersihkanRiwayat = true; view.loadUrl(setelan.alamat); return }
                tampilGalat()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (bersihkanRiwayat) { bersihkanRiwayat = false; view.clearHistory() }
                val diGudang = url != null && Uri.parse(url).port == Uri.parse(setelan.alamatGudang).port &&
                    Uri.parse(url).host == Uri.parse(setelan.alamatGudang).host
                if (setelan.mode == Setelan.MODE_GUDANG && wajibKeluarGudang && diGudang) {
                    wajibKeluarGudang = false
                    // Masih ada sesi dari tadi (mis. aplikasi dibuka ulang): keluarkan dulu, wajib PIN.
                    if (!halamanMasuk(url)) keluarGudang { bersihkanRiwayat = true; view.loadUrl(setelan.alamat) }
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                unggahan?.onReceiveValue(null)
                unggahan = cb
                return try {
                    startActivityForResult(p.createIntent(), PILIH_BERKAS); true
                } catch (e: Exception) {
                    unggahan = null; false
                }
            }
        }
    }

    private fun tampilGalat() {
        galatTeks.text = "Tidak tersambung ke server toko.\n\nPastikan internet menyala dan Tailscale tersambung, lalu coba lagi."
        galat.visibility = View.VISIBLE
    }

    @Deprecated("Activity lama, cukup untuk satu pilihan berkas")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PILIH_BERKAS) {
            unggahan?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            unggahan = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Tombol kembali: mundur di halaman, tidak keluar aplikasi")
    override fun onBackPressed() {
        val riwayat = web.copyBackForwardList()
        if (riwayat.currentIndex <= 0) return
        val sebelum = riwayat.getItemAtIndex(riwayat.currentIndex - 1).url
        // Tidak mundur ke halaman masuk (formulirnya sudah basi) atau ke mode lain.
        if (halamanMasuk(sebelum) || Uri.parse(sebelum).port != Uri.parse(web.url ?: "").port) return
        web.goBack()
    }

    // ------------------------------------------------------------------ menu pemilik

    private fun ketukPojok() {
        val sekarang = SystemClock.elapsedRealtime()
        if (sekarang - ketukanMulai > 3000) { ketukan = 0; ketukanMulai = sekarang }
        if (++ketukan >= 5) { ketukan = 0; mintaPin { menuPemilik() } }
    }

    private fun kotakPin(petunjuk: String) = EditText(this).apply {
        hint = petunjuk
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun mintaPin(lanjut: () -> Unit) {
        val sisa = terkunciSampai - SystemClock.elapsedRealtime()
        if (sisa > 0) { pesan("Terlalu banyak salah. Tunggu ${sisa / 1000 + 1} detik."); return }
        val isian = kotakPin("PIN pemilik")
        AlertDialog.Builder(this).setTitle("Menu pemilik").setView(isian)
            .setPositiveButton("Buka") { _, _ ->
                if (setelan.cocokPin(isian.text.toString())) { salahPin = 0; lanjut() }
                else {
                    salahPin++
                    if (salahPin >= 5) { salahPin = 0; terkunciSampai = SystemClock.elapsedRealtime() + 60_000 }
                    pesan("PIN salah.")
                }
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun mintaPinBaru(wajib: Boolean) {
        val satu = kotakPin("PIN baru (minimal 6 angka)")
        val dua = kotakPin("Ulangi PIN")
        val isi = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(satu); addView(dua)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (wajib) "Buat PIN pemilik" else "Ganti PIN pemilik")
            .setMessage("PIN ini untuk membuka menu pemilik di tablet ini. Jangan beri tahu karyawan.")
            .setView(isi)
            .setPositiveButton("Simpan", null)
            .apply { if (!wajib) setNegativeButton("Batal", null) }
            .setCancelable(!wajib)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = satu.text.toString()
                when {
                    a.length < 6 || !a.all { it.isDigit() } -> pesan("PIN minimal 6 angka.")
                    a != dua.text.toString() -> pesan("PIN tidak sama.")
                    else -> { setelan.simpanPin(a); pesan("PIN pemilik disimpan."); dlg.dismiss() }
                }
            }
        }
        dlg.show()
    }

    private fun menuPemilik() {
        val pemilik = Kebijakan.pemilik(this)
        val pilihan = mutableListOf<Pair<String, () -> Unit>>(
            "Muat ulang halaman" to { galat.visibility = View.GONE; bersihkanRiwayat = true; web.loadUrl(setelan.alamat) },
            "Wi-Fi" to { bukaPengaturan(Settings.ACTION_WIFI_SETTINGS) },
            "Buka Tailscale" to { bukaTailscale() },
            "Mode tablet: ${labelPilihan()} (ganti)" to { gantiMode() },
            "Ubah alamat server" to { ubahAlamat() },
            "Sembunyikan / munculkan aplikasi" to { aturAplikasi() },
            "Ganti PIN pemilik" to { mintaPinBaru(wajib = false) },
            "Status perangkat" to { statusPerangkat() },
        )
        if (pemilik) {
            if (perawatan) pilihan.add(Pair("Kunci lagi") { perawatan = false; kunci(); pesan("Tablet dikunci lagi.") })
            else pilihan.add(Pair("Mode perawatan (buka kunci sementara)") { masukPerawatan() })
            pilihan.add(Pair("Lepas Ril dari tablet ini") { konfirmasiLepas() })
        }
        AlertDialog.Builder(this).setTitle("Menu pemilik")
            .setItems(pilihan.map { it.first }.toTypedArray()) { _, i -> pilihan[i].second() }
            .setNegativeButton("Tutup", null).show()
    }

    private fun bukaPengaturan(aksi: String) {
        lepasKunci()
        try { startActivity(Intent(aksi)) } catch (e: Exception) { pesan("Pengaturan tidak bisa dibuka.") }
    }

    private fun bukaTailscale() {
        val i = packageManager.getLaunchIntentForPackage(Setelan.PAKET_TAILSCALE)
        if (i == null) { pesan("Tailscale belum terpasang."); return }
        startActivity(i)   // Tailscale ada di daftar boleh lock task, jadi kunci tidak perlu dilepas
    }

    private fun labelPilihan() = when (setelan.pilihanMode) {
        Setelan.MODE_KASIR -> "Kasir saja"
        Setelan.MODE_GUDANG -> "Gudang saja"
        else -> "Kasir & Gudang"
    }

    private fun gantiMode() {
        val nilai = arrayOf(Setelan.PILIHAN_KEDUANYA, Setelan.MODE_KASIR, Setelan.MODE_GUDANG)
        val nama = arrayOf("Kasir & Gudang (karyawan bisa pindah di bilah atas)", "Kasir saja", "Gudang saja")
        val kini = nilai.indexOf(setelan.pilihanMode).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Mode tablet ini").setSingleChoiceItems(nama, kini) { d, i ->
            d.dismiss()
            val dariGudang = setelan.mode == Setelan.MODE_GUDANG
            setelan.pilihanMode = nilai[i]
            perbaruiBilah()
            if (dariGudang && setelan.mode != Setelan.MODE_GUDANG) keluarGudang { bukaMode() } else bukaMode()
        }.show()
    }

    private fun ubahAlamat() {
        val kasir = setelan.mode == Setelan.MODE_KASIR
        val isian = EditText(this).apply {
            setText(setelan.alamat)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Alamat ${if (kasir) "kasir" else "gudang"}").setView(isian)
            .setPositiveButton("Simpan") { _, _ ->
                val u = isian.text.toString().trim()
                if (!u.startsWith("https://")) { pesan("Alamat harus diawali https://"); return@setPositiveButton }
                if (kasir) setelan.alamatKasir = u else setelan.alamatGudang = u
                bukaMode()
            }
            .setNeutralButton("Bawaan") { _, _ ->
                if (kasir) setelan.alamatKasir = Setelan.ALAMAT_KASIR else setelan.alamatGudang = Setelan.ALAMAT_GUDANG
                bukaMode()
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun aturAplikasi() {
        if (!Kebijakan.pemilik(this)) { pesan("Hanya bisa setelah Ril dijadikan device owner."); return }
        val daftar = Kebijakan.semuaAplikasi(this)
        if (daftar.isEmpty()) { pesan("Tidak ada aplikasi."); return }
        val centang = daftar.map { it.tersembunyi }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Centang = disembunyikan (tidak bisa jalan)")
            .setMultiChoiceItems(daftar.map { it.nama }.toTypedArray(), centang) { _, i, v -> centang[i] = v }
            .setPositiveButton("Simpan") { _, _ ->
                var gagal = 0
                daftar.forEachIndexed { i, a ->
                    if (a.tersembunyi != centang[i] && !Kebijakan.sembunyikan(this, a.paket, centang[i])) gagal++
                }
                pesan(if (gagal == 0) "Tersimpan." else "$gagal aplikasi tidak bisa diubah (aplikasi sistem inti).")
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun statusPerangkat() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val versi = packageManager.getPackageInfo(packageName, 0).versionName
        val teks = buildString {
            appendLine("Ril $versi · ${labelPilihan()} · sekarang ${setelan.mode}")
            appendLine("Device owner: ${if (Kebijakan.pemilik(this@MainActivity)) "ya" else "BELUM"}")
            appendLine("Terkunci: ${if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) "ya" else "tidak"}")
            appendLine("Tailscale terpasang: ${if (Kebijakan.terpasang(this@MainActivity, Setelan.PAKET_TAILSCALE)) "ya" else "TIDAK"}")
            append("Alamat: ${setelan.alamat}")
        }
        AlertDialog.Builder(this).setTitle("Status perangkat").setMessage(teks).setPositiveButton("Tutup", null).show()
    }

    private fun masukPerawatan() {
        AlertDialog.Builder(this).setTitle("Mode perawatan")
            .setMessage("Kunci aplikasi, USB, dan opsi pengembang dibuka sampai kamu memilih \"Kunci lagi\" atau tablet dinyalakan ulang. Jangan tinggalkan tablet dalam mode ini.")
            .setPositiveButton("Buka kunci") { _, _ ->
                perawatan = true
                Kebijakan.cabutSementara(this)
                lepasKunci()
                pesan("Mode perawatan aktif.")
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun konfirmasiLepas() {
        AlertDialog.Builder(this).setTitle("Lepas Ril dari tablet ini?")
            .setMessage("Semua kunci dicabut, aplikasi tersembunyi dimunculkan lagi, dan Ril bukan device owner lagi. Untuk memasang lagi perlu reset pabrik.")
            .setPositiveButton("Lepas") { _, _ ->
                lepasKunci()
                Kebijakan.lepas(this)
                pesan("Ril dilepas. Tablet kembali normal.")
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun pesan(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        private const val PILIH_BERKAS = 7
    }
}
