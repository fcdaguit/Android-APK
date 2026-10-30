package com.termprompter

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Keeps offline Vosk models in app-private storage: files/models/<modelKey>/.
 * Models can be bundled in assets (assets/model-<key>/) or imported once from a .zip file;
 * after that nothing ever touches the network.
 */
class ModelManager(private val ctx: Context) {
    private val root = File(ctx.filesDir, "models")

    fun dir(l: Lang) = File(root, l.modelKey)

    fun installed(l: Lang): Boolean = File(dir(l), "am").isDirectory || File(dir(l), "conf").isDirectory

    /**
     * Returns (modelDir, isFallback). Bisaya falls back to the Filipino model when no
     * dedicated Bisaya model has been imported.
     */
    fun resolve(l: Lang): Pair<File, Boolean>? {
        if (installed(l)) return dir(l) to false
        if (l == Lang.BISAYA && installed(Lang.FILIPINO)) return dir(Lang.FILIPINO) to true
        return null
    }

    fun installFromAssetsIfPresent(l: Lang) {
        val name = "model-${l.modelKey}"
        if (installed(l)) return
        if (ctx.assets.list("")?.contains(name) != true) return
        copyAssets(name, dir(l))
    }

    private fun copyAssets(path: String, out: File) {
        val kids = ctx.assets.list(path) ?: emptyArray()
        if (kids.isEmpty()) {
            out.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        } else {
            out.mkdirs()
            for (k in kids) copyAssets("$path/$k", File(out, k))
        }
    }

    /** Unpacks a Vosk model .zip (with or without the top-level folder) into this language's slot. */
    fun installFromZip(input: InputStream, l: Lang) {
        val target = dir(l)
        target.deleteRecursively()
        target.mkdirs()
        val canon = target.canonicalPath
        val topLevel = setOf("am", "conf", "graph", "ivector", "rescore", "rnnlm", "README")
        ZipInputStream(input.buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                val rel = if (parts.size > 1 && parts[0] !in topLevel) parts.drop(1) else parts
                if (rel.isNotEmpty()) {
                    val f = File(target, rel.joinToString("/"))
                    if (!f.canonicalPath.startsWith(canon + File.separator)) throw SecurityException("Bad zip entry")
                    if (e.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { zis.copyTo(it) }
                    }
                }
                e = zis.nextEntry
            }
        }
        if (!installed(l)) {
            target.deleteRecursively()
            throw IllegalStateException("That zip does not look like a Vosk model (no am/ or conf/ folder).")
        }
    }
}
