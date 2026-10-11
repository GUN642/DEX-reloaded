package de.chris.dexreloaded.render

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import androidx.core.content.res.ResourcesCompat
import de.chris.dexreloaded.R

/** Schriften für Bilder auf der Brille (Einblendungen, Fensterleisten, Testbild). */
object GlassFonts {
    /** Space Mono für Beschriftungen. */
    var mono: Typeface = Typeface.MONOSPACE
        private set

    /** Doto in fetter Punktmatrix für große Titel. */
    var dot: Typeface = Typeface.DEFAULT_BOLD
        private set

    fun init(context: Context) {
        runCatching { ResourcesCompat.getFont(context, R.font.space_mono) }.getOrNull()?.let { mono = it }
        runCatching {
            val font = Font.Builder(context.resources, R.font.doto).setFontVariationSettings("'wght' 800, 'ROND' 100").build()
            Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).build()
        }.getOrNull()?.let { dot = it }
    }
}
