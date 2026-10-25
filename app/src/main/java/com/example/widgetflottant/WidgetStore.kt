package com.example.widgetflottant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un widget affiché en fenêtre flottante : identifiant Android du widget + position et taille (en pixels). */
data class FloatingEntry(
    val id: Int,
    var x: Int,
    var y: Int,
    var w: Int,
    var h: Int,
    var collapsed: Boolean = false
)

/** Mémorise la liste des fenêtres flottantes (positions et tailles comprises). */
object WidgetStore {
    private const val PREFS = "floating_widgets"
    private const val KEY = "entries"

    @Synchronized
    fun load(context: Context): MutableList<FloatingEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        val list = mutableListOf<FloatingEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    FloatingEntry(
                        o.getInt("id"), o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"),
                        o.optBoolean("c", false)
                    )
                )
            }
        } catch (_: Exception) {
            // données illisibles : on repart d'une liste vide
        }
        return list
    }

    @Synchronized
    fun add(context: Context, entry: FloatingEntry) {
        val list = load(context)
        list.removeAll { it.id == entry.id }
        list.add(entry)
        save(context, list)
    }

    @Synchronized
    fun update(context: Context, entry: FloatingEntry) {
        val list = load(context)
        val index = list.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            list[index] = entry
            save(context, list)
        }
    }

    @Synchronized
    fun remove(context: Context, id: Int) {
        val list = load(context)
        if (list.removeAll { it.id == id }) save(context, list)
    }

    private fun save(context: Context, list: List<FloatingEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("x", it.x)
                    .put("y", it.y)
                    .put("w", it.w)
                    .put("h", it.h)
                    .put("c", it.collapsed)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
