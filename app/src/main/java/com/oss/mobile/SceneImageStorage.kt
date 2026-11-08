package com.oss.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Imports selected images into private app storage. Persisted scenes store only a
 * generated media ID, never a temporary picker URI or a user-visible filesystem path.
 */
object SceneImageStorage {
    private const val MAX_FILE_BYTES = 30L * 1024L * 1024L
    private const val MAX_SOURCE_DIMENSION = 16_384
    private const val MAX_SOURCE_PIXELS = 100_000_000L
    private val ID_PATTERN = Regex("[a-f0-9]{32}")

    data class ImportedImage(val id: String, val width: Int, val height: Int)

    fun import(context: Context, uri: Uri): ImportedImage {
        val directory = imageDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Tidak bisa membuat penyimpanan gambar")
        }

        val id = UUID.randomUUID().toString().replace("-", "")
        val temporary = File(directory, "$id.tmp")
        val target = File(directory, "$id.img")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("File gambar tidak bisa dibuka")
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_FILE_BYTES) { "Ukuran gambar maksimal 30 MB" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "File yang dipilih bukan gambar yang didukung"
            }
            require(bounds.outWidth <= MAX_SOURCE_DIMENSION && bounds.outHeight <= MAX_SOURCE_DIMENSION) {
                "Resolusi gambar terlalu besar"
            }
            require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) {
                "Resolusi gambar terlalu besar"
            }

            check(temporary.renameTo(target)) { "Gagal menyimpan gambar ke perangkat" }
            return ImportedImage(id, bounds.outWidth, bounds.outHeight)
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    fun decode(context: Context, id: String, maxDimension: Int = 1600): Bitmap? {
        require(id.matches(ID_PATTERN)) { "ID gambar tidak valid" }
        require(maxDimension in 64..4096)
        val file = File(imageDirectory(context), "$id.img")
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun imageDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "scene_media/images")
}
