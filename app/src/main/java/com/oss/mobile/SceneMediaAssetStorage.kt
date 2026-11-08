package com.oss.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Persistent descriptor for a user-selected visual asset. */
data class SceneMediaAsset(
    val id: String,
    val mimeType: String,
    val sizeBytes: Long,
    val displayName: String
) {
    init {
        require(id.matches(Regex("[a-f0-9]{32}")))
        require(mimeType in SUPPORTED_MIME_TYPES)
        require(sizeBytes in 1..MAX_BYTES)
        require(displayName.isNotBlank() && displayName.length <= 120)
    }

    val kind: Kind
        get() = when (mimeType) {
            "image/gif" -> Kind.ANIMATED_IMAGE
            "video/mp4", "video/webm" -> Kind.VIDEO
            else -> Kind.STATIC_IMAGE
        }

    enum class Kind { STATIC_IMAGE, ANIMATED_IMAGE, VIDEO }

    companion object {
        const val MAX_BYTES = 100L * 1024L * 1024L
        val SUPPORTED_MIME_TYPES = setOf(
            "image/gif", "image/webp", "image/png", "image/jpeg",
            "video/mp4", "video/webm"
        )

        fun supports(mimeType: String?): Boolean = mimeType?.lowercase() in SUPPORTED_MIME_TYPES
    }
}

/**
 * Copies SAF-selected media into private app storage. Scene documents retain only the
 * generated asset ID and MIME type; they never depend on temporary content URIs.
 */
object SceneMediaAssetStorage {
    data class Imported(val asset: SceneMediaAsset, val file: File)

    fun import(context: Context, uri: Uri): Imported {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.lowercase()
            ?: throw IllegalArgumentException("Jenis media tidak diketahui")
        require(SceneMediaAsset.supports(mime)) { "Format media belum didukung: $mime" }

        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.take(120)?.takeIf { it.isNotBlank() } ?: "media"

        val directory = File(context.applicationContext.filesDir, "scene_media/assets")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Tidak bisa membuat penyimpanan media")
        }

        val id = UUID.randomUUID().toString().replace("-", "")
        val temporary = File(directory, "$id.tmp")
        val target = File(directory, "$id.asset")
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Media tidak bisa dibuka")
            var total = 0L
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total in 1..SceneMediaAsset.MAX_BYTES) {
                            "Ukuran media maksimal 100 MB"
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(temporary.renameTo(target)) { "Gagal menyimpan media" }
            return Imported(SceneMediaAsset(id, mime, total, displayName), target)
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    fun file(context: Context, id: String): File? {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "ID media tidak valid" }
        return File(File(context.applicationContext.filesDir, "scene_media/assets"), "$id.asset")
            .takeIf { it.isFile && it.length() in 1..SceneMediaAsset.MAX_BYTES }
    }


    /**
     * Deletes only unreferenced imported GIF/video assets. If scene metadata cannot be
     * read, the caller must skip pruning rather than risk deleting a still-used asset.
     */
    fun pruneUnreferenced(context: Context, documents: Collection<SceneDocument>): Int {
        val referenced = SceneMediaAssetReferences.collect(documents)
        val directory = File(context.applicationContext.filesDir, "scene_media/assets")
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".asset") }
            ?: return 0
        var deleted = 0
        files.forEach { file ->
            val id = file.name.removeSuffix(".asset")
            if (id.matches(Regex("[a-f0-9]{32}")) && id !in referenced && file.delete()) {
                deleted++
            }
        }
        return deleted
    }

    fun delete(context: Context, id: String): Boolean = file(context, id)?.delete() ?: false
}
