package dev.gold.mdvault.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class SafDocumentRepositoryPerformanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val contentResolver: ContentResolver = context.contentResolver
    private val treeUri: Uri =
        DocumentsContract.buildTreeDocumentUri(TestVaultDocumentsProvider.AUTHORITY, TestVaultDocumentsProvider.ROOT_ID)
    private val rootDocumentUri: Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, TestVaultDocumentsProvider.ROOT_ID)

    @Before
    fun setUp() {
        contentResolver.call(TestVaultDocumentsProvider.AUTHORITY, TestVaultDocumentsProvider.METHOD_RESET, null, null)
        repeat(FILE_COUNT) { index ->
            val name = "doc-${index.toString().padStart(3, '0')}.md"
            val uri = DocumentsContract.createDocument(contentResolver, rootDocumentUri, "text/markdown", name)
                ?: error("Failed to create $name")
            contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                output.write("# $name\n\nbody\n".toByteArray())
            }
        }
    }

    @Test
    fun listTwoHundredDocumentsCompletesUnderFiveHundredMillis() = runBlocking {
        val repository = SafDocumentRepository(contentResolver)
        lateinit var documents: List<SafDocument>

        val elapsedMs = measureTimeMillis {
            documents = repository.list(treeUri)
        }

        assertEquals(FILE_COUNT, documents.size)
        assertTrue("SAF list query took ${elapsedMs}ms for $FILE_COUNT files", elapsedMs < 500)
    }

    private companion object {
        private const val FILE_COUNT = 200
    }
}
