package br.geracaoai.baixartg.core

import android.webkit.WebView

/** Ajustes para WebView oculto: nunca abre o teclado sozinho e nunca deixa selecionar texto. */
object Quiet {
    fun hidden(wv: WebView) {
        try { wv.settings.setNeedInitialFocus(false) } catch (_: Exception) {}
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        wv.isLongClickable = false
        wv.setOnLongClickListener { true }
        wv.setOnFocusChangeListener { v, has -> if (has) v.clearFocus() }
    }
}
