package com.oss.mobile.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a user-requested, offline support archive.
 * No upload, raw media, stream endpoint, or credentials are included.
 */
class DiagnosticReportExporter(private val context: Context) {

    fun createArchive(): File {
        val snapshot = OssDiagnostics.center.snapshot()
        val reportId = java.util.UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        val report = JSONObject().apply {
            put("schemaVersion", 1)
            put("reportId", reportId)
            put("createdAtUtcMs", createdAt)
            put("app", appInfo())
            put("device", deviceInfo())
            put("system", systemInfo())
            put("session", snapshot.session?.let { session ->
                JSONObject().apply {
                    put("sessionId", session.sessionId)
                    put("startedAtElapsedMs", session.startedAtElapsedMs)
                    put("label", session.label)
                    put("endedAtElapsedMs", session.endedAtElapsedMs ?: JSONObject.NULL)
                    put("outcome", session.outcome ?: JSONObject.NULL)
                }
            } ?: JSONObject.NULL)
            put("droppedEventCount", snapshot.droppedEventCount)
            put("eventCount", snapshot.events.size)
            put("events", JSONArray().apply {
                snapshot.events.forEach { event ->
                    put(JSONObject().apply {
                        put("eventId", event.eventId)
                        put("elapsedRealtimeMs", event.elapsedRealtimeMs)
                        put("sessionId", event.sessionId ?: JSONObject.NULL)
                        put("category", event.category.name)
                        put("severity", event.severity.name)
                        put("code", event.code)
                        put("message", event.message)
                        put("attributes", JSONObject(event.attributes))
                    })
                }
            })
        }

        val timeline = buildString {
            appendLine("OSS DIAGNOSTIC TIMELINE")
            appendLine("Report ID: $reportId")
            appendLine("Schema: 1")
            snapshot.events.forEach { event ->
                append(event.elapsedRealtimeMs)
                append("ms [")
                append(event.severity.name)
                append("] ")
                append(event.category.name)
                append("/")
                append(event.code)
                append(" — ")
                appendLine(event.message)
                if (event.attributes.isNotEmpty()) {
                    appendLine("  " + event.attributes.entries.joinToString { "${it.key}=${it.value}" })
                }
            }
            appendLine("Dropped events: ${snapshot.droppedEventCount}")
            appendLine("No audio/video media payloads are included.")
        }

        val reportBytes = report.toString(2).toByteArray(Charsets.UTF_8)
        val timelineBytes = timeline.toByteArray(Charsets.UTF_8)
        val manifest = JSONObject().apply {
            put("schemaVersion", 1)
            put("reportId", reportId)
            put("files", JSONArray().apply {
                put(fileEntry("report.json", reportBytes))
                put(fileEntry("timeline.log", timelineBytes))
            })
        }.toString(2).toByteArray(Charsets.UTF_8)

        val directory = File(context.cacheDir, "diagnostic-reports").apply { mkdirs() }
        directory.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }?.forEach { it.delete() }
        val archive = File(directory, "OSS-Diagnostic-$reportId.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("report.json"))
            zip.write(reportBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("timeline.log"))
            zip.write(timelineBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
        }
        return archive
    }

    private fun appInfo(): JSONObject {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject().apply {
            put("packageName", context.packageName)
            put("versionName", packageInfo.versionName ?: "unknown")
            put("versionCode", if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong())
            put("sdkInt", Build.VERSION.SDK_INT)
        }
    }

    private fun deviceInfo(): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("release", Build.VERSION.RELEASE)
        put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
    }

    private fun systemInfo(): JSONObject {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject().apply {
            put("totalMemoryBytes", memory.totalMem)
            put("availableMemoryBytes", memory.availMem)
            put("lowMemory", memory.lowMemory)
            put("thermalStatus", if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else JSONObject.NULL)
        }
    }

    private fun fileEntry(name: String, bytes: ByteArray): JSONObject = JSONObject().apply {
        put("name", name)
        put("sizeBytes", bytes.size)
        put("sha256", sha256(bytes))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }
}
