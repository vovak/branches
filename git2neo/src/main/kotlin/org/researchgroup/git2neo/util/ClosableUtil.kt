package org.researchgroup.git2neo.util

/**
 * @author *blinded*
 * @since 18/11/16
 */
inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
    var closed = false
    try {
        return block(this)
    } catch (e: Exception) {
        closed = true
        try {
            close()
        } catch (closeException: Exception) {
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") (e as java.lang.Throwable).addSuppressed(closeException)
        }
        throw e
    } finally {
        if (!closed) {
            close()
        }
    }
}