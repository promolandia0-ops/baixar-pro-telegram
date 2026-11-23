package br.geracaoai.baixartg.core

import java.net.URLEncoder

/**
 * Voltar do Android na aba Navegador: primeiro o histórico do WebView;
 * se já estiver na primeira página, volta para a aba anterior.
 */
object BrowserNav {
    enum class BackAction { WEBVIEW, PREVIOUS_TAB }

    fun onBack(canGoBack: Boolean): BackAction =
        if (canGoBack) BackAction.WEBVIEW else BackAction.PREVIOUS_TAB
}

/**
 * Estado e regras da barra de endereço/pesquisa do Navegador (testável sem Compose):
 *  - estado de edição separado da URL da página;
 *  - ao focar, seleciona todo o texto;
 *  - enquanto focado, mudanças de URL da página não sobrescrevem o texto digitado;
 *  - botão X limpa o texto;
 *  - Enter/Ir abre URL se parecer endereço, senão pesquisa no Google;
 *  - ao sair do foco sem enviar, restaura a URL atual.
 */
object BrowserAddress {
    private val IPV4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?(?:/.*)?$""")

    fun toLoadUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
        val noSpace = !t.contains(' ')
        val looksLikeUrl = noSpace && (
            t.contains('.') ||
                t.equals("localhost", true) ||
                t.startsWith("localhost:", true) ||
                t.startsWith("localhost/", true) ||
                IPV4.matches(t)
            )
        return if (looksLikeUrl) "https://$t"
        else "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    class State(initialPageUrl: String = "") {
        var pageUrl: String = initialPageUrl
            private set
        var text: String = initialPageUrl
            private set
        var selStart: Int = initialPageUrl.length
            private set
        var selEnd: Int = initialPageUrl.length
            private set
        var focused: Boolean = false
            private set

        fun onFocusChanged(nowFocused: Boolean) {
            if (nowFocused == focused) return
            focused = nowFocused
            if (nowFocused) {
                selStart = 0
                selEnd = text.length
            } else if (pageUrl.startsWith("http", true)) {
                text = pageUrl
                selStart = text.length
                selEnd = text.length
            }
        }

        fun onEdit(newText: String, s: Int = newText.length, e: Int = newText.length) {
            text = newText
            selStart = s.coerceIn(0, newText.length)
            selEnd = e.coerceIn(0, newText.length)
        }

        fun onClear() {
            text = ""
            selStart = 0
            selEnd = 0
        }

        fun onPageUrl(url: String) {
            if (url.isBlank()) return
            pageUrl = url
            if (!focused) {
                text = url
                selStart = url.length
                selEnd = url.length
            }
        }

        fun onSubmit(): String {
            val target = toLoadUrl(text)
            focused = false
            if (target.isNotEmpty()) {
                text = target
                selStart = target.length
                selEnd = target.length
            } else if (pageUrl.startsWith("http", true)) {
                text = pageUrl
                selStart = text.length
                selEnd = text.length
            }
            return target
        }
    }
}
