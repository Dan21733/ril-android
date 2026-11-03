package id.dbrl.ril

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Tempat aplikasi kamera menyimpan foto bukti transfer (tombol "Ambil foto" di kasir).
 *
 * Kamera menulis langsung ke berkas di cache Ril lewat URI ini, lalu halaman kasir membacanya
 * seperti berkas yang dipilih. Foto tidak masuk galeri tablet. Dibuat sendiri (bukan
 * FileProvider androidx) supaya Ril tetap tanpa pustaka tambahan.
 */
class FotoProvider : ContentProvider() {
    override fun onCreate() = true

    private fun berkas(uri: Uri): File? {
        val nama = uri.lastPathSegment ?: return null
        if (!NAMA_SAH.matches(nama)) return null
        return File(folder(context ?: return null), nama)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = berkas(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri) = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = berkas(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(f.name, f.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val OTORITAS = "id.dbrl.ril.foto"
        private val NAMA_SAH = Regex("^bukti-\\d+\\.jpg$")

        fun folder(c: Context) = File(c.cacheDir, "foto").apply { mkdirs() }

        /** Berkas baru untuk satu kali ambil foto; foto lama (lebih dari sehari) dibuang. */
        fun baru(c: Context): Pair<File, Uri> {
            val batas = System.currentTimeMillis() - 24L * 3600 * 1000
            folder(c).listFiles()?.filter { it.lastModified() < batas }?.forEach { it.delete() }
            val f = File(folder(c), "bukti-${System.currentTimeMillis()}.jpg")
            f.createNewFile()
            return f to Uri.parse("content://$OTORITAS/${f.name}")
        }
    }
}
