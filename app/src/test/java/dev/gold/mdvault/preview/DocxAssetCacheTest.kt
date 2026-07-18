package dev.gold.mdvault.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocxAssetCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed transaction removes staging and preserves published assets`() {
        val root = temporaryFolder.newFolder("opened")
        val published = File(root, CACHE_KEY).apply { mkdirs() }
        File(published, "old.txt").writeText("old")

        DocxAssetTransaction(root, CACHE_KEY).use { transaction ->
            File(transaction.stagingDirectory, "partial.txt").writeText("partial")
        }

        assertEquals("old", File(published, "old.txt").readText())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".staging") })
    }

    @Test
    fun `commit replaces stale assets as one published generation`() {
        val root = temporaryFolder.newFolder("opened")
        val stale = File(root, CACHE_KEY).apply { mkdirs() }
        File(stale, "stale.txt").writeText("stale")

        val published = DocxAssetTransaction(root, CACHE_KEY).use { transaction ->
            File(transaction.stagingDirectory, "fresh.txt").writeText("fresh")
            transaction.commit()
        }

        assertFalse(File(published, "stale.txt").exists())
        assertEquals("fresh", File(published, "fresh.txt").readText())
        assertEquals(listOf(CACHE_KEY), root.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun `eviction removes oldest cache while protecting active generation`() {
        val root = temporaryFolder.newFolder("opened")
        val oldest = File(root, "111111111111").apply { mkdirs() }
        val active = File(root, CACHE_KEY).apply { mkdirs() }
        File(oldest, "asset.bin").writeBytes(ByteArray(16))
        File(active, "asset.bin").writeBytes(ByteArray(16))
        oldest.setLastModified(1_000L)
        active.setLastModified(2_000L)

        DocxAssetTransaction.evict(
            cacheRoot = root,
            protectedDirectory = active,
            nowMillis = 2_000L,
            maxAgeMillis = Long.MAX_VALUE,
            maxTotalBytes = 16L,
        )

        assertFalse(oldest.exists())
        assertTrue(active.exists())
    }

    private companion object {
        const val CACHE_KEY = "abcdef123456"
    }
}
