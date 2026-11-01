package io.deepseek.harness.android

import io.deepseek.harness.android.saf.SafPathResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafPathResolverTest {

    @Test
    fun `resuelve volumenes primary, home y tarjetas SD extraibles`() {
        assertEquals(
            "/storage/emulated/0/Proyectos/App",
            SafPathResolver.resolveFromTreeDocumentId(
                authority = "com.android.externalstorage.documents",
                treeDocId = "primary:Proyectos/App",
                primaryExternalRoot = "/storage/emulated/0",
            ),
        )

        assertEquals(
            "/storage/emulated/0/Documents/Notas",
            SafPathResolver.resolveFromTreeDocumentId(
                authority = "com.android.externalstorage.documents",
                treeDocId = "home:Notas",
                primaryExternalRoot = "/storage/emulated/0",
            ),
        )

        assertEquals(
            "/storage/1234-ABCD/Repo",
            SafPathResolver.resolveFromTreeDocumentId(
                authority = "com.android.externalstorage.documents",
                treeDocId = "1234-ABCD:Repo",
                primaryExternalRoot = "/storage/emulated/0",
            ),
        )

        assertNull(
            SafPathResolver.resolveFromTreeDocumentId(
                authority = "com.google.android.apps.docs.storage",
                treeDocId = "folder-99",
                primaryExternalRoot = "/storage/emulated/0",
            ),
        )
    }

    @Test
    fun `sanitiza alias SAF evitando caracteres especiales y colisiones`() {
        val existing = setOf("mi-proyecto", "mi-proyecto-2")
        val alias = SafPathResolver.sanitizeUniqueAlias("primary:Mi Proyecto!", existing)
        assertEquals("mi-proyecto-3", alias)
    }
}
