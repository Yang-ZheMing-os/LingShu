package com.lingshu.agent.utils

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.DecimalFormat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val SUB_DIR_CHAT_MEDIA = "chat_media"
        const val SUB_DIR_AUDIO = "audio"
        const val SUB_DIR_TTS = "tts_cache"
        const val SUB_DIR_MODS = "mods"
        const val SUB_DIR_SCRIPTS = "scripts"
        const val SUB_DIR_BACKUPS = "backups"
        const val SUB_DIR_LOGS = "logs"
        const val SUB_DIR_KNOWLEDGE = "knowledge"
        const val SUB_DIR_EXPORT = "export"
        const val SUB_DIR_TEMP = "temp"
    }

    fun getInternalDir(): File = context.filesDir

    fun getInternalCacheDir(): File = context.cacheDir

    fun getExternalDir(): File? = context.getExternalFilesDir(null)

    fun getExternalCacheDir(): File? = context.externalCacheDir

    fun getPublicDir(type: String = Environment.DIRECTORY_DOCUMENTS): File? {
        return Environment.getExternalStoragePublicDirectory(type)
    }

    fun getChatMediaDir(): File = getOrCreateSubDir(SUB_DIR_CHAT_MEDIA)

    fun getAudioDir(): File = getOrCreateSubDir(SUB_DIR_AUDIO)

    fun getTtsCacheDir(): File = getOrCreateSubDir(SUB_DIR_TTS)

    fun getModsDir(): File = getOrCreateSubDir(SUB_DIR_MODS)

    fun getScriptsDir(): File = getOrCreateSubDir(SUB_DIR_SCRIPTS)

    fun getBackupsDir(): File = getOrCreateSubDir(SUB_DIR_BACKUPS)

    fun getLogsDir(): File = getOrCreateSubDir(SUB_DIR_LOGS)

    fun getKnowledgeDir(): File = getOrCreateSubDir(SUB_DIR_KNOWLEDGE)

    fun getExportDir(): File = getOrCreateSubDir(SUB_DIR_EXPORT)

    fun getTempDir(): File = getOrCreateSubDir(SUB_DIR_TEMP)

    private fun getOrCreateSubDir(subDir: String): File {
        val dir = File(context.filesDir, subDir)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun readTextFile(file: File): String {
        return FileInputStream(file).use { stream ->
            readTextFromStream(stream)
        }
    }

    fun readTextFile(uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            readTextFromStream(stream)
        } ?: throw IOException("无法打开文件流")
    }

    private fun readTextFromStream(inputStream: InputStream): String {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val builder = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            builder.append(line)
            builder.append('\n')
        }
        return builder.toString()
    }

    fun readBinaryFile(file: File): ByteArray {
        return file.readBytes()
    }

    fun readBinaryFile(uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use {
            it.readBytes()
        } ?: throw IOException("无法打开文件流")
    }

    fun writeTextFile(file: File, content: String, append: Boolean = false) {
        ensureParentDir(file)
        FileOutputStream(file, append).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    fun writeBinaryFile(file: File, data: ByteArray, append: Boolean = false) {
        ensureParentDir(file)
        FileOutputStream(file, append).use { stream ->
            stream.write(data)
        }
    }

    fun writeStreamToFile(outputFile: File, inputStream: InputStream) {
        ensureParentDir(outputFile)
        FileOutputStream(outputFile).use { output ->
            inputStream.copyTo(output, bufferSize = 8192)
        }
    }

    private fun ensureParentDir(file: File) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
    }

    fun copyFile(source: File, destination: File): Boolean {
        return try {
            ensureParentDir(destination)
            source.copyTo(destination, overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun copyFile(sourceUri: Uri, destination: File): Boolean {
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                writeStreamToFile(destination, input)
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun moveFile(source: File, destination: File): Boolean {
        return try {
            ensureParentDir(destination)
            if (source.renameTo(destination)) {
                true
            } else {
                val copied = copyFile(source, destination)
                if (copied) source.delete() else false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun deleteFile(file: File): Boolean {
        return file.delete()
    }

    fun deleteDirectory(dir: File): Boolean {
        if (!dir.exists()) return true
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                deleteDirectory(child)
            }
        }
        return dir.delete()
    }

    fun clearCacheDir(): Boolean {
        var success = true
        context.cacheDir.listFiles()?.forEach {
            if (!deleteDirectory(it)) success = false
        }
        context.externalCacheDir?.let { ext ->
            ext.listFiles()?.forEach {
                if (!deleteDirectory(it)) success = false
            }
        }
        return success
    }

    fun clearTempDir(): Boolean = deleteDirectory(getTempDir())

    fun getUriForFile(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun getFileName(uri: Uri): String {
        var result: String? = null
        if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(
                        cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                    )
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1 && result != null) {
                result = result.substring(cut + 1)
            }
        }
        return result ?: "unknown_file"
    }

    fun getFileSize(file: File): Long = file.length()

    fun getFileSize(uri: Uri): Long {
        var size: Long = -1
        if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            val cursor: Cursor? = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    size = cursor.getLong(
                        cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
                    )
                }
            } finally {
                cursor?.close()
            }
        }
        if (size == -1L) {
            val path = uri.path ?: return -1
            val file = File(path)
            if (file.exists()) return file.length()
        }
        return size
    }

    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceAtMost(units.size - 1)
        val format = DecimalFormat("#,##0.#")
        return format.format(size / Math.pow(1024.0, index.toDouble())) + " " + units[index]
    }

    fun getExtension(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot >= 0) {
            fileName.substring(lastDot + 1).lowercase()
        } else {
            ""
        }
    }

    fun removeExtension(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot >= 0) {
            fileName.substring(0, lastDot)
        } else {
            fileName
        }
    }

    fun hasExtension(fileName: String, vararg extensions: String): Boolean {
        val ext = getExtension(fileName)
        return extensions.any { it.equals(ext, ignoreCase = true) }
    }

    /**
     * 将 content:// URI 拷贝到缓存目录，返回拷贝后的 File 对象
     */
    fun copyUriToCache(uri: Uri, fileNamePrefix: String = "cache_"): File {
        val originalName = getFileName(uri)
        val ext = getExtension(originalName)
        val targetFile = File(getTempDir(), "${fileNamePrefix}_${System.currentTimeMillis()}.$ext")
        val success = copyFile(uri, targetFile)
        if (!success) throw IOException("拷贝文件到缓存失败: $uri")
        return targetFile
    }

    fun generateUniqueFileName(baseName: String, extension: String): String {
        val dir = getTempDir()
        val base = removeExtension(baseName)
        var name = "$base.$extension"
        var counter = 1
        while (File(dir, name).exists()) {
            name = "${base}_$counter.$extension"
            counter++
        }
        return name
    }

    fun createTempFile(prefix: String = "temp_", suffix: String = ".tmp"): File {
        return File.createTempFile(prefix, suffix, getTempDir())
    }

    fun listFilesByExtension(dir: File, vararg extensions: String): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { file ->
            file.isFile && hasExtension(file.name, *extensions)
        } ?: emptyList()
    }

    fun listSubDirectories(dir: File): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }

    fun getAvailableInternalSpace(): Long {
        return context.filesDir.freeSpace
    }

    fun getTotalInternalSpace(): Long {
        return context.filesDir.totalSpace
    }

    fun getUsedInternalSpacePercent(): Int {
        val total = getTotalInternalSpace()
        if (total == 0L) return 0
        val used = total - getAvailableInternalSpace()
        return (used * 100 / total).toInt()
    }

    fun writeStream(output: OutputStream, inputStream: InputStream) {
        inputStream.use { input ->
            output.use { out ->
                input.copyTo(out, bufferSize = 8192)
            }
        }
    }
}
