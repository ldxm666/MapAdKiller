package io.github.ldxm666.mapclean

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Content-addressed extraction: APK length and library length are not version identities. */
object NativeLibraryCache {
    @JvmStatic
    fun extract(zip: ZipFile, entryName: String, directory: File, abi: String): File {
        require(abi in arrayOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
        val entry = zip.getEntry(entryName) ?: throw IOException("native entry missing")
        if (entry.size <= 0 || entry.size > 32L * 1024 * 1024) throw IOException("invalid native size")
        val temporary = File.createTempFile(".mapclean-native-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            zip.getInputStream(entry).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (size > entry.size) throw IOException("native entry grew")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (size != entry.size) throw IOException("incomplete native entry")
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val target = File(directory, "$abi-$hash-libmapclean_labels.so")
            val valid = try { target.isFile && target.length() == size && sha256(target) == hash }
                catch (ignored: IOException) { false }
            if (!valid) {
                if (!temporary.renameTo(target)) throw IOException("native cache rename failed")
            }
            target.setReadable(true, true)
            target.setExecutable(true, true)
            target.setWritable(false, false)
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
