package app.pitchstone.data

import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 as lower-case hex: what attachments are compared by, and folder sync's hashes. */
object Sha256 {
    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Of everything [input] holds, read in pieces (a picture need not fit in memory); closes it. */
    fun of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        input.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(digest: ByteArray) = digest.joinToString("") { "%02x".format(it) }
}
