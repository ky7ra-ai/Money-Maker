package com.ky7ra.fototeks

import android.content.Context
import android.graphics.Typeface

class FontFace(
    val id: String,
    val family: String,
    val weight: Int,
    val italic: Boolean,
    val typeface: Typeface,
) {
    val label: String
        get() {
            val w = when {
                weight <= 150 -> " Thin"
                weight <= 250 -> " ExtraLight"
                weight <= 350 -> " Light"
                weight <= 450 -> ""
                weight <= 550 -> " Medium"
                weight <= 650 -> " SemiBold"
                weight <= 750 -> " Bold"
                weight <= 850 -> " ExtraBold"
                else -> " Black"
            }
            return family + w + (if (italic) " Italic" else "")
        }
}

/**
 * Pustaka font: font bawaan Android + semua font .ttf/.otf di assets/fonts
 * (diunduh oleh scripts/fetch-fonts.sh). Font variabel dibuat dalam beberapa bobot.
 */
object FontLibrary {
    @Volatile
    private var cache: List<FontFace>? = null
    @Volatile
    private var index: Map<String, FontFace> = emptyMap()

    fun all(context: Context): List<FontFace> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val list = load(context.applicationContext)
            index = list.associateBy { it.id }
            cache = list
            return list
        }
    }

    /** Daftar font bila sudah dimuat, kosong bila belum. */
    fun loaded(): List<FontFace> = cache ?: emptyList()

    fun byId(id: String): FontFace? = index[id]

    fun fallback(): FontFace =
        index["sys|sans-serif|400|false"] ?: cache?.firstOrNull()
            ?: FontFace("sys|none", "Sans", 400, false, Typeface.DEFAULT)

    private fun load(ctx: Context): List<FontFace> {
        val out = ArrayList<FontFace>()
        val seen = HashSet<String>()

        fun add(face: FontFace) {
            if (seen.add(face.id)) out += face
        }

        // Font bawaan sistem.
        fun sys(label: String, key: String, weights: IntArray, italics: BooleanArray) {
            for (w in weights) {
                for (it in italics) {
                    val tf = try {
                        Typeface.create(Typeface.create(key, Typeface.NORMAL), w, it)
                    } catch (_: Exception) {
                        continue
                    }
                    add(FontFace("sys|$key|$w|$it", label, w, it, tf))
                }
            }
        }
        sys("Sans Sistem", "sans-serif", intArrayOf(300, 400, 500, 700, 900), booleanArrayOf(false, true))
        sys("Sans Sempit Sistem", "sans-serif-condensed", intArrayOf(400, 700), booleanArrayOf(false))
        sys("Serif Sistem", "serif", intArrayOf(400, 700), booleanArrayOf(false, true))
        sys("Mono Sistem", "monospace", intArrayOf(400, 700), booleanArrayOf(false))
        sys("Tulisan Tangan Sistem", "cursive", intArrayOf(400, 700), booleanArrayOf(false))
        sys("Casual Sistem", "casual", intArrayOf(400), booleanArrayOf(false))

        // Font dari assets.
        val files = try {
            ctx.assets.list("fonts")?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        for (name in files) {
            if (!(name.endsWith(".ttf", true) || name.endsWith(".otf", true))) continue
            val base = name.substringBeforeLast('.')
            val axes = if (base.contains('~')) base.substringAfter('~') else ""
            val clean = base.substringBefore('~')
            val famRaw = clean.substringBefore('-')
            val style = if (clean.contains('-')) clean.substringAfter('-') else "Regular"
            val family = famRaw.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").replace('_', ' ')
            val italic = style.contains("italic", true) || style.contains("oblique", true)
            val path = "fonts/$name"

            if (axes.contains("wght")) {
                for (w in intArrayOf(300, 400, 500, 600, 700, 800, 900)) {
                    val tf = try {
                        Typeface.Builder(ctx.assets, path)
                            .setFontVariationSettings("'wght' $w")
                            .build()
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    add(FontFace("$family|$w|$italic", family, w, italic, tf))
                }
            } else {
                val tf = try {
                    Typeface.createFromAsset(ctx.assets, path)
                } catch (_: Exception) {
                    null
                } ?: continue
                val s = style.lowercase()
                val weight = when {
                    s.contains("thin") || s.contains("hairline") -> 100
                    s.contains("extralight") || s.contains("ultralight") -> 200
                    s.contains("light") -> 300
                    s.contains("medium") -> 500
                    s.contains("semibold") || s.contains("demibold") -> 600
                    s.contains("extrabold") || s.contains("ultrabold") -> 800
                    s.contains("black") || s.contains("heavy") -> 900
                    s.contains("bold") -> 700
                    else -> 400
                }
                add(FontFace("$family|$weight|$italic", family, weight, italic, tf))
            }
        }
        return out
    }
}
