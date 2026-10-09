package com.droiddeck.launcher.frontend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Games added as one picked .exe (the Games tab's +) rather than a whole Games folder: kept by
 * the exe's folder in `files/added-exes.json`, which [AddedGames.scan] merges in. The exe chosen
 * for the folder, its shortcut appid and its removal live where every added game keeps them
 * ([com.droiddeck.launcher.session.SessionPrefs]); this holds which folders are games and the
 * exe first picked.
 */
object AddedExes {
    class Entry(val folder: String, val exe: String)

    private fun file(context: Context) = File(context.filesDir, "added-exes.json")

    @Synchronized
    fun list(context: Context): List<Entry> = try {
        val array = JSONArray(file(context).takeIf { it.isFile }?.readText() ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val folder = o.optString("folder"); val exe = o.optString("exe")
            if (folder.isEmpty() || exe.isEmpty()) null else Entry(folder, exe)
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Adds [entry], replacing one for the same folder. */
    @Synchronized
    fun put(context: Context, entry: Entry) = write(context, list(context).filter { it.folder != entry.folder } + entry)

    @Synchronized
    fun drop(context: Context, folder: String) = write(context, list(context).filter { it.folder != folder })

    private fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("folder", it.folder).put("exe", it.exe)) }
        val f = file(context)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
