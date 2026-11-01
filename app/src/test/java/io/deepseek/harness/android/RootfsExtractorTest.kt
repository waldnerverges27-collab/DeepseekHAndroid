package io.deepseek.harness.android

import io.deepseek.harness.android.container.RootfsExtractor
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RootfsExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extrae archivos ejecutables, symlinks, hardlinks y aplica politica WX`() {
        val archiveBytes = buildTarXz { tar ->
            // Archivo ejecutable
            val scriptContent = "#!/bin/bash\necho ok\n".toByteArray()
            val binEntry = TarArchiveEntry("usr/local/bin/hello.sh").apply {
                size = scriptContent.size.toLong()
                mode = 0b111_101_101 // 0755
            }
            tar.putArchiveEntry(binEntry)
            tar.write(scriptContent)
            tar.closeArchiveEntry()

            // Enlace simbólico
            val symEntry = TarArchiveEntry("usr/local/bin/hello-sym", TarArchiveEntry.LF_SYMLINK).apply {
                linkName = "hello.sh"
            }
            tar.putArchiveEntry(symEntry)
            tar.closeArchiveEntry()

            // Enlace duro
            val hardEntry = TarArchiveEntry("usr/local/bin/hello-hard", TarArchiveEntry.LF_LINK).apply {
                linkName = "usr/local/bin/hello.sh"
            }
            tar.putArchiveEntry(hardEntry)
            tar.closeArchiveEntry()
        }

        val dest = tmp.newFolder("extracted")
        RootfsExtractor.extractTarXzStream(
            rawInput = ByteArrayInputStream(archiveBytes),
            totalCompressedBytes = archiveBytes.size.toLong(),
            destRoot = dest,
        )

        val bin = File(dest, "usr/local/bin/hello.sh")
        val sym = File(dest, "usr/local/bin/hello-sym")
        val hard = File(dest, "usr/local/bin/hello-hard")

        assertTrue(bin.isFile)
        assertTrue(bin.canExecute())
        // Política W^X: ejecutable sin permiso de escritura
        assertFalse(bin.canWrite())

        assertTrue(Files.isSymbolicLink(sym.toPath()))
        assertEquals("hello.sh", Files.readSymbolicLink(sym.toPath()).toString())

        assertTrue(hard.isFile)
        assertEquals("#!/bin/bash\necho ok\n", hard.readText())
    }

    @Test(expected = IOException::class)
    fun `bloquea intento de escape TarSlip fuera del directorio destino`() {
        val maliciousBytes = buildTarXz { tar ->
            val payload = "pwned".toByteArray()
            val evil = TarArchiveEntry("../../escape.txt").apply {
                size = payload.size.toLong()
            }
            tar.putArchiveEntry(evil)
            tar.write(payload)
            tar.closeArchiveEntry()
        }

        val dest = tmp.newFolder("safe-dest")
        RootfsExtractor.extractTarXzStream(
            rawInput = ByteArrayInputStream(maliciousBytes),
            totalCompressedBytes = maliciousBytes.size.toLong(),
            destRoot = dest,
        )
    }

    private fun buildTarXz(populate: (TarArchiveOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        XZCompressorOutputStream(baos, 1).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                populate(tar)
                tar.finish()
            }
        }
        return baos.toByteArray()
    }
}
