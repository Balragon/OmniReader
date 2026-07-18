package dev.gold.mdvault.preview

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A failed or cancelled import never writes into the currently published asset directory. */
internal class DocxAssetTransaction(
    private val cacheRoot: File,
    private val cacheKey: String,
) : Closeable {
    val stagingDirectory: File
    private var committed = false

    init {
        require(CACHE_KEY.matches(cacheKey)) { "Invalid DOCX cache key" }
        if (!cacheRoot.isDirectory && !cacheRoot.mkdirs()) {
            throw IOException("Couldn't create DOCX asset cache")
        }
        stagingDirectory = File(cacheRoot, ".$cacheKey-${UUID.randomUUID()}$STAGING_SUFFIX")
        if (!stagingDirectory.mkdir()) throw IOException("Couldn't create DOCX asset staging directory")
    }

    fun commit(): File {
        check(!committed) { "DOCX asset transaction was already committed" }
        val published = File(cacheRoot, cacheKey)
        val backup = File(cacheRoot, ".$cacheKey-${UUID.randomUUID()}$BACKUP_SUFFIX")
        var backupCreated = false
        try {
            if (published.exists()) {
                moveDirectory(published, backup)
                backupCreated = true
            }
            moveDirectory(stagingDirectory, published)
            committed = true
            published.setLastModified(System.currentTimeMillis())
            if (backupCreated) backup.deleteRecursively()
            return published
        } catch (error: Exception) {
            if (!published.exists() && backupCreated) {
                runCatching { moveDirectory(backup, published) }
            }
            throw IOException("Couldn't publish DOCX assets", error)
        } finally {
            if (!committed) stagingDirectory.deleteRecursively()
            if (backup.exists() && published.exists()) backup.deleteRecursively()
        }
    }

    override fun close() {
        if (!committed) stagingDirectory.deleteRecursively()
    }

    companion object {
        fun evict(
            cacheRoot: File,
            protectedDirectory: File,
            nowMillis: Long = System.currentTimeMillis(),
            maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
            maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        ) {
            if (!cacheRoot.isDirectory) return
            val protectedPath = protectedDirectory.canonicalFile.path
            val directories = cacheRoot.listFiles { file -> file.isDirectory }.orEmpty()

            directories.filter { it.name.endsWith(STAGING_SUFFIX) || it.name.endsWith(BACKUP_SUFFIX) }
                .filter { nowMillis - it.lastModified() > ABANDONED_WORK_MAX_AGE_MILLIS }
                .forEach(File::deleteRecursively)

            val published = directories
                .filterNot { it.name.startsWith('.') }
                .filterNot { it.canonicalFile.path == protectedPath }
                .sortedBy(File::lastModified)
                .toMutableList()
            published.filter { nowMillis - it.lastModified() > maxAgeMillis }
                .forEach { directory ->
                    directory.deleteRecursively()
                    published.remove(directory)
                }

            var totalBytes = cacheRoot.listFiles { file -> file.isDirectory && !file.name.startsWith('.') }
                .orEmpty()
                .sumOf { it.directorySize() }
            for (directory in published) {
                if (totalBytes <= maxTotalBytes) break
                val size = directory.directorySize()
                if (directory.deleteRecursively()) totalBytes -= size
            }
        }

        private fun moveDirectory(source: File, target: File) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                if (!source.renameTo(target)) throw IOException("Couldn't move DOCX asset directory")
            }
        }

        private fun File.directorySize(): Long =
            walkTopDown().filter(File::isFile).sumOf(File::length)

        private val CACHE_KEY = Regex("[a-f0-9]{12}")
        private const val STAGING_SUFFIX = ".staging"
        private const val BACKUP_SUFFIX = ".backup"
        private const val ABANDONED_WORK_MAX_AGE_MILLIS = 60L * 60L * 1_000L
        private const val DEFAULT_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        private const val DEFAULT_MAX_TOTAL_BYTES = 64L * 1024L * 1024L
    }
}
