package com.termprompter

import android.content.Context
import java.io.File

/** Saves scripts as plain .txt files in app-private storage. */
class ScriptStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "scripts").apply { mkdirs() }

    private fun safe(n: String) =
        n.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(60).ifEmpty { "script" }

    fun names(): List<String> =
        dir.listFiles { f -> f.extension == "txt" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun save(name: String, text: String) = File(dir, safe(name) + ".txt").writeText(text)
    fun load(name: String): String = File(dir, safe(name) + ".txt").readText()
    fun delete(name: String) { File(dir, safe(name) + ".txt").delete() }
}
