package com.oss.mobile

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Local-only scene storage. Each document is replaced atomically so a process interruption
 * cannot leave a half-written scene as the active file.
 */
class SceneDocumentRepository(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "scenes")

    @Synchronized
    fun save(document: SceneDocument) {
        val id = validatedId(document.id)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create scene storage directory")
        }

        val target = File(directory, "$id.oss-scene")
        val temporary = File(directory, "$id.oss-scene.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(SceneDocumentCodec.encode(document))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        pruneUnreferencedMedia()
    }

    @Synchronized
    fun load(id: String): SceneDocument {
        val safeId = validatedId(id)
        val file = File(directory, "$safeId.oss-scene")
        require(file.isFile) { "Scene not found: $safeId" }
        require(file.length() <= MAX_DOCUMENT_BYTES) { "Scene document exceeds size limit" }
        val document = SceneDocumentCodec.decode(file.readBytes())
        require(document.id == safeId) { "Scene ID does not match its storage key" }
        return document
    }

    @Synchronized
    fun list(): List<SceneDocument> {
        val files = directory.listFiles { file ->
            file.isFile && file.name.endsWith(FILE_SUFFIX)
        } ?: return emptyList()

        return files.sortedBy { it.name }.map { file ->
            require(file.length() <= MAX_DOCUMENT_BYTES) { "Scene document exceeds size limit: ${file.name}" }
            val document = SceneDocumentCodec.decode(file.readBytes())
            val storedId = file.name.removeSuffix(FILE_SUFFIX)
            require(document.id == storedId) { "Scene ID does not match its storage key: $storedId" }
            document
        }
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val safeId = validatedId(id)
        val file = File(directory, "$safeId.oss-scene")
        val deleted = !file.exists() || file.delete()
        if (deleted) pruneUnreferencedMedia()
        return deleted
    }

    private fun pruneUnreferencedMedia() {
        // Never garbage-collect media if any scene document cannot be decoded.
        val documents = runCatching { list() }.getOrNull() ?: return
        runCatching { SceneMediaAssetStorage.pruneUnreferenced(appContext, documents) }
    }

    private fun validatedId(id: String): String {
        require(id.matches(ID_PATTERN)) { "Invalid scene ID" }
        return id
    }

    companion object {
        private const val FILE_SUFFIX = ".oss-scene"
        private const val MAX_DOCUMENT_BYTES = 2L * 1024L * 1024L
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")
    }
}
