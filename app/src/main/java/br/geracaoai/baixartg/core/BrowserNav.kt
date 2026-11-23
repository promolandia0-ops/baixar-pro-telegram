package br.geracaoai.baixartg.core

/**
 * Voltar do Android na aba Navegador: primeiro o histórico do WebView;
 * se já estiver na primeira página, volta para a aba anterior.
 */
object BrowserNav {
    enum class BackAction { WEBVIEW, PREVIOUS_TAB }

    fun onBack(canGoBack: Boolean): BackAction =
        if (canGoBack) BackAction.WEBVIEW else BackAction.PREVIOUS_TAB
}
