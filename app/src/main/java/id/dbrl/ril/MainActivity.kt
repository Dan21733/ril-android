package id.dbrl.ril

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
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
    private lateinit var jembatan: JembatanRil
    private lateinit var web: WebView
    private lateinit var galat: LinearLayout
    private lateinit var galatTeks: TextView
    private lateinit var bilah: LinearLayout
    private lateinit var tabKasir: TextView
    private lateinit var tabGudang: TextView
    private lateinit var tabSiapkan: TextView

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
    private var fotoKamera: java.io.File? = null
    private var fotoKameraUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setelan = Setelan(this)
        terapkanLayar()
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
        tabSiapkan = buatTab("Siapkan") { gantiKe(Setelan.MODE_SIAPKAN) }
        bilah.addView(tabKasir)
        bilah.addView(tabGudang)
        bilah.addView(tabSiapkan)
        bilah.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // Pengaturan yang boleh diatur karyawan sendiri (warna, layar mati). Printer dll. di menu pemilik.
        bilah.addView(buatTab("Pengaturan") { pengaturanKaryawan() })
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
        for ((tab, m) in listOf(tabKasir to Setelan.MODE_KASIR, tabGudang to Setelan.MODE_GUDANG, tabSiapkan to Setelan.MODE_SIAPKAN)) {
            tab.visibility = if (m in setelan.modeBoleh) View.VISIBLE else View.GONE
            val aktif = setelan.mode == m
            tab.setTextColor(Color.parseColor(if (aktif) "#FFFFFF" else "#A8A29A"))
            tab.setTypeface(null, if (aktif) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tab.setBackgroundColor(Color.parseColor(if (aktif) setelan.warnaTema else "#2A2724"))
        }
    }

    /** Pindah mode. Keluar dari Gudang = sesi gudang ditutup, jadi masuk lagi wajib PIN. */
    private fun gantiKe(baru: String) {
        if (baru == setelan.mode || baru !in setelan.modeBoleh) return
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

    /** Halaman kasir milik toko (host + port sama dengan alamat kasir): hanya di sini cetak boleh. */
    private fun halamanKasir(url: String?): Boolean {
        if (url == null) return false
        val u = Uri.parse(url)
        val k = Uri.parse(setelan.alamatKasir)
        return u.scheme == "https" && u.host == k.host && u.port == k.port
    }

    /** Layar mati otomatis: 0 menit = selalu nyala (cara lama). */
    private fun terapkanLayar() {
        if (setelan.layarMati == 0) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Kebijakan.aturLayar(this, setelan.layarMati, setelan.nyalaSaatCas)
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
        jembatan = JembatanRil(applicationContext, setelan, web)
        web.addJavascriptInterface(jembatan, "RilTablet")
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

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                jembatan.boleh = halamanKasir(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                jembatan.boleh = halamanKasir(url)
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
                // <input capture> (tombol "Ambil foto" bukti transfer): buka kamera langsung.
                // Dulu semua input berkas membuka pemilih berkas, jadi tombol kamera ikut membuka Files.
                if (p.isCaptureEnabled && bukaKamera()) return true
                return try {
                    startActivityForResult(p.createIntent(), PILIH_BERKAS); true
                } catch (e: Exception) {
                    unggahan = null; false
                }
            }
        }
    }

    /** Kamera menulis ke berkas Ril sendiri (FotoProvider). Gagal (tidak ada kamera) = pemilih berkas. */
    private fun bukaKamera(): Boolean {
        val (f, uri) = try { FotoProvider.baru(this) } catch (e: Exception) { return false }
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("bukti", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (i.resolveActivity(packageManager) == null) {
            pesan("Aplikasi kamera tidak ditemukan (mungkin disembunyikan di menu pemilik). Pilih foto dari berkas.")
            f.delete()
            return false
        }
        return try {
            fotoKamera = f
            fotoKameraUri = uri
            startActivityForResult(i, AMBIL_FOTO)
            true
        } catch (e: Exception) {
            fotoKamera = null; fotoKameraUri = null; f.delete()
            false
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
        if (requestCode == AMBIL_FOTO) {
            val f = fotoKamera
            val uri = fotoKameraUri
            val ada = resultCode == Activity.RESULT_OK && f != null && uri != null && f.length() > 0
            unggahan?.onReceiveValue(if (ada) arrayOf(uri!!) else null)
            if (!ada) f?.delete()
            unggahan = null; fotoKamera = null; fotoKameraUri = null
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
            "Printer: ${setelan.printer.ifEmpty { "belum ada" }} (cari / ubah)" to { menuPrinter() },
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
        Setelan.MODE_KASIR -> "Kasir (+ Siapkan)"
        Setelan.MODE_GUDANG -> "Gudang saja"
        else -> "Kasir & Gudang"
    }

    private fun gantiMode() {
        val nilai = arrayOf(Setelan.PILIHAN_KEDUANYA, Setelan.MODE_KASIR, Setelan.MODE_GUDANG)
        val nama = arrayOf("Kasir, Gudang & Siapkan (karyawan bisa pindah di bilah atas)", "Kasir & Siapkan", "Gudang saja")
        val kini = nilai.indexOf(setelan.pilihanMode).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Mode tablet ini").setSingleChoiceItems(nama, kini) { d, i ->
            d.dismiss()
            val dariGudang = setelan.mode == Setelan.MODE_GUDANG
            setelan.pilihanMode = nilai[i]
            perbaruiBilah()
            if (dariGudang && setelan.mode != Setelan.MODE_GUDANG) keluarGudang { bukaMode() } else bukaMode()
        }.show()
    }

    /** Tab "Pengaturan": tanpa PIN, hanya hal tampilan tablet. */
    private fun pengaturanKaryawan() {
        val warna = (Setelan.TEMA.firstOrNull { it.kunci == setelan.tema } ?: Setelan.TEMA[0]).nama
        val layar = if (setelan.layarMati == 0) "selalu nyala" else "mati setelah ${setelan.layarMati} menit"
        val pilihan = arrayOf("Warna tablet: $warna", "Layar: $layar${if (setelan.nyalaSaatCas) ", nyala saat dicas" else ""}")
        AlertDialog.Builder(this).setTitle("Pengaturan tablet")
            .setItems(pilihan) { _, i -> if (i == 0) pilihTema() else aturLayar() }
            .setNegativeButton("Tutup", null).show()
    }

    private fun menuPrinter() {
        val pilihan = arrayOf("Cari printer otomatis", "Isi alamat manual", "Cetak halaman uji")
        AlertDialog.Builder(this).setTitle("Printer: ${setelan.printer.ifEmpty { "belum ada" }}")
            .setItems(pilihan) { _, i ->
                when (i) {
                    0 -> cariPrinter()
                    1 -> isiPrinter()
                    else -> cetakUji()
                }
            }.setNegativeButton("Tutup", null).show()
    }

    private fun cariPrinter() {
        pesan("Mencari printer di Wi-Fi toko…")
        Thread {
            val h = Printer.cari(applicationContext)
            runOnUiThread {
                if (h.alamat.isNotEmpty()) {
                    setelan.printer = h.alamat
                    pesan("Printer ditemukan: ${h.alamat}${if (h.model.isNotEmpty()) " (${h.model})" else ""}")
                } else if (h.semua.size > 1) {
                    val daftar = h.semua.map { "$it:${Printer.PORT}" }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Pilih printer kasir").setItems(daftar) { _, i ->
                        setelan.printer = daftar[i]; pesan("Printer: ${daftar[i]}. Coba cetak halaman uji.")
                    }.setNegativeButton("Batal", null).show()
                } else pesan(h.pesan)
            }
        }.start()
    }

    private fun isiPrinter() {
        val isian = EditText(this).apply {
            setText(setelan.printer); hint = "192.168.1.224:9100"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Alamat printer").setView(isian)
            .setPositiveButton("Simpan") { _, _ -> setelan.printer = isian.text.toString(); pesan("Tersimpan.") }
            .setNegativeButton("Batal", null).show()
    }

    private fun cetakUji() {
        val alamat = setelan.printer
        if (alamat.isEmpty()) { pesan("Cari printer dulu."); return }
        val uji = "\u001B@Ril: halaman uji\n${android.os.Build.MODEL}\nPrinter $alamat\n\n\n\n\u001DV\u0001"
        Thread {
            val ok = runCatching { Printer.kirim(Printer.wifi(applicationContext), alamat, uji.toByteArray(Charsets.ISO_8859_1)) }.isSuccess
            runOnUiThread { pesan(if (ok) "Halaman uji dikirim ke $alamat." else "Printer $alamat tidak bisa dihubungi.") }
        }.start()
    }

    private fun pilihTema() {
        val kini = Setelan.TEMA.indexOfFirst { it.kunci == setelan.tema }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Warna tablet ini")
            .setSingleChoiceItems(Setelan.TEMA.map { it.nama }.toTypedArray(), kini) { d, i ->
                d.dismiss()
                setelan.tema = Setelan.TEMA[i].kunci
                perbaruiBilah()
                web.evaluateJavascript("document.documentElement.dataset.warna='${setelan.tema}'", null)
                pesan("Warna: ${Setelan.TEMA[i].nama}")
            }.show()
    }

    private fun aturLayar() {
        val menit = intArrayOf(1, 2, 5, 10, 0)
        val nama = arrayOf("1 menit", "2 menit", "5 menit", "10 menit", "Tidak pernah (selalu nyala)")
        val kini = menit.indexOf(setelan.layarMati).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Layar mati setelah tidak disentuh")
            .setSingleChoiceItems(nama, kini) { d, i ->
                d.dismiss()
                setelan.layarMati = menit[i]
                AlertDialog.Builder(this).setTitle("Saat dicas")
                    .setItems(arrayOf("Tetap nyala saat dicas", "Ikut mati juga")) { _, j ->
                        setelan.nyalaSaatCas = j == 0
                        terapkanLayar()
                        pesan(if (Kebijakan.pemilik(this) || menit[i] == 0) "Tersimpan." else "Tersimpan. Batas waktu layar hanya berlaku kalau Ril device owner.")
                    }.show()
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
        private const val AMBIL_FOTO = 8
    }
}
