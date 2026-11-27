package br.geracaoai.baixartg.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import java.net.URI

/**
 * Automação genérica de "Carregar tudo" no navegador:
 *  1) Na página aberta: rola até o fim e clica em botões/links in-page de "ver mais" / "carregar mais" / "load more" / "show more" / "mais".
 *  2) Em paginação (1, 2, 3..., próxima, next, », >, rel=next): NÃO navega a aba visível;
 *     busca as páginas seguintes do mesmo domínio em segundo plano e junta os cards na lista única.
 */
object LoadMore {
    const val DEFAULT_MAX_PAGES = 20
    const val MAX_SCROLL_ROUNDS = 50
    const val MAX_NO_NEW_ROUNDS = 3
    const val MAX_DURATION_MS = 120_000L

    private val POS_LABEL = Regex(
        """(?i)^(?:ver\s*mais(?:\s*v[ií]deos?)?|carregar\s*mais(?:\s*v[ií]deos?)?|mostrar\s*mais(?:\s*v[ií]deos?)?|exibir\s*mais|mais\s*v[ií]deos|mais\s*resultados|load\s*more(?:\s*videos?)?|show\s*more(?:\s*videos?)?|view\s*more|see\s*more|more\s*videos?|more|next(?:\s*page)?|pr[oó]xim[ao](?:\s*p[aá]gina)?|seguinte|avancar|avan[cç]ar|›|»|>|>>)$"""
    )
    private val NEXT_ONLY_LABEL = Regex(
        """(?i)^(?:next(?:\s*page)?|pr[oó]xim[ao](?:\s*p[aá]gina)?|seguinte|avan[cç]ar|›|»|>|>>)$"""
    )
    private val NEG_LABEL = Regex(
        """(?i)(?:login|entrar|log\s*in|sign\s*in|sign\s*up|cadastr|registr|conta|account|comprar|buy|assinar|subscri|plano|plan|vip|premium|upgrade|pagar|pay|checkout|carrinho|cart|an[uú]ncio|sponsor|promo|cookie|privacidade|privacy|aceitar|accept|compartilhar|share|comentar|comment|app\s*store|play\s*store|baixar\s*app|download\s*app)"""
    )
    private val NEG_URL = Regex(
        """(?i)/(?:login|signin|sign-in|signup|register|cadastro|auth|account|conta|checkout|cart|carrinho|buy|comprar|assinar|subscribe|pricing|vip|premium|plans?|ads?)(?:[/?#]|$)"""
    )

    fun isLoadMoreLabel(raw: String): Boolean {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.length > 40) return false
        if (NEG_LABEL.containsMatchIn(t)) return false
        return POS_LABEL.matches(t)
    }

    fun isNextLabel(raw: String): Boolean {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.length > 30) return false
        if (NEG_LABEL.containsMatchIn(t)) return false
        return NEXT_ONLY_LABEL.matches(t)
    }

    fun canFollowSameDomain(baseUrl: String, targetUrl: String, targetAttr: String = ""): Boolean {
        if (targetAttr.equals("_blank", ignoreCase = true)) return false
        val u = targetUrl.trim()
        if (u.isEmpty() || u.startsWith("javascript:", true) || u.startsWith("mailto:", true) || u.startsWith("tel:", true)) return false
        if (u.startsWith("#")) return false
        val abs = Extract.abs(baseUrl, u) ?: return false
        if (!abs.startsWith("http://", true) && !abs.startsWith("https://", true)) return false
        val baseHost = Extract.host(baseUrl)
        val tgtHost = Extract.host(abs)
        if (baseHost.isBlank() || baseHost != tgtHost) return false
        if (br.geracaoai.baixartg.web.AdBlock.isAdHost(tgtHost)) return false
        if (NEG_URL.containsMatchIn(abs)) return false
        if (abs.substringBefore('#') == baseUrl.substringBefore('#')) return false
        return true
    }

    /**
     * Extrai da página HTML possíveis URLs da(s) próxima(s) página(s) do mesmo domínio, na ordem:
     * 1) link/a[rel=next]
     * 2) links "próxima"/"next"/"»"/">"
     * 3) links numerados de paginação (2, 3, 4...) > currentPageNum.
     */
    fun extractNextUrls(pageUrl: String, html: String, visited: Set<String>, currentPageNum: Int = 1): List<String> {
        val doc = try { Jsoup.parse(html, pageUrl) } catch (_: Exception) { return emptyList() }
        val out = LinkedHashSet<String>()

        fun tryAdd(rawHref: String?, targetAttr: String = "") {
            if (rawHref.isNullOrBlank()) return
            if (!canFollowSameDomain(pageUrl, rawHref, targetAttr)) return
            val abs = Extract.abs(pageUrl, rawHref)?.substringBefore('#') ?: return
            if (abs !in visited) out += abs
        }

        // 1) rel=next
        for (el in doc.select("link[rel~=next],a[rel~=next]")) {
            val href = el.absUrl("href").ifBlank { el.attr("href") }
            tryAdd(href, el.attr("target"))
        }

        // 2) Links textuais de próxima página
        for (a in doc.select("a[href]")) {
            val label = listOf(a.text(), a.attr("aria-label"), a.attr("title")).firstOrNull { it.isNotBlank() }.orEmpty()
            if (isNextLabel(label)) {
                tryAdd(a.absUrl("href").ifBlank { a.attr("href") }, a.attr("target"))
            }
        }

        // 3) Links numerados (1, 2, 3...) dentro de containers de paginação
        val numLinks = ArrayList<Pair<Int, String>>()
        for (a in doc.select("[class*=pag] a[href], [id*=pag] a[href], .pager a[href], nav a[href], ul a[href], div a[href]")) {
            val txt = a.text().trim()
            val num = txt.toIntOrNull() ?: continue
            if (num !in (currentPageNum + 1)..200) continue
            val inPager = a.parents().any { p ->
                val cls = (p.className() + " " + p.id() + " " + p.attr("aria-label") + " " + p.tagName()).lowercase()
                cls.contains("pag") || cls.contains("pager") || cls == "nav" || cls.contains("nav")
            }
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!inPager && !Regex("""(?i)[?&/](?:page|p|pg|pagina|pag)=?$num\b|/page/$num\b""").containsMatchIn(href)) continue
            if (canFollowSameDomain(pageUrl, href, a.attr("target"))) {
                val abs = Extract.abs(pageUrl, href)?.substringBefore('#') ?: continue
                if (abs !in visited) numLinks += num to abs
            }
        }
        numLinks.sortedBy { it.first }.forEach { out += it.second }

        return out.toList()
    }

    /**
     * JS executado no WebView visível para um passo in-page:
     * rola até o fim e clica em botões/links in-page visíveis ("ver mais", "carregar mais", "load more", etc.)
     * sem navegar para outro domínio, sem popup/target=_blank e ignorando login/compra.
     */
    const val STEP_JS = """(function(){
try{
  var h=Math.max(document.body?document.body.scrollHeight:0,document.documentElement?document.documentElement.scrollHeight:0);
  window.scrollTo(0,h);
  var se=document.scrollingElement; if(se) se.scrollTop=se.scrollHeight;
  try{window.dispatchEvent(new Event('scroll'))}catch(e){}
  var pos=/^(?:ver\s*mais(?:\s*v[ií]deos?)?|carregar\s*mais(?:\s*v[ií]deos?)?|mostrar\s*mais(?:\s*v[ií]deos?)?|exibir\s*mais|mais\s*v[ií]deos|mais\s*resultados|load\s*more(?:\s*videos?)?|show\s*more(?:\s*videos?)?|view\s*more|see\s*more|more\s*videos?|more)$/i;
  var neg=/(?:login|entrar|log\s*in|sign\s*in|sign\s*up|cadastr|registr|conta|account|comprar|buy|assinar|subscri|plano|plan|vip|premium|upgrade|pagar|pay|checkout|carrinho|cart|an[uú]ncio|sponsor|promo|cookie|privacidade|privacy|aceitar|accept|compartilhar|share|comentar|comment|app\s*store|play\s*store)/i;
  function vis(el){
    if(!el||el.disabled)return false;
    var st=window.getComputedStyle?getComputedStyle(el):null;
    if(st&&(st.display==='none'||st.visibility==='hidden'||parseFloat(st.opacity)===0))return false;
    var r=el.getBoundingClientRect?el.getBoundingClientRect():null;
    return !!r&&r.width>4&&r.height>4;
  }
  function inBadZone(el){
    var p=el,k=0;
    while(p&&k<10){
      var cls=((p.className||'')+' '+(p.id||'')).toLowerCase();
      if(/cookie|consent|banner|modal-login|auth|checkout|sponsor/.test(cls))return true;
      p=p.parentElement;k++;
    }
    return false;
  }
  var candidates=document.querySelectorAll('button,[role=button],input[type=button],input[type=submit],a,span[onclick],div[onclick]');
  var clicked='', didClick=0;
  for(var i=0;i<candidates.length;i++){
    var el=candidates[i];
    if(!vis(el)||inBadZone(el))continue;
    var tag=(el.tagName||'').toUpperCase();
    if(tag==='A'){
      if((el.getAttribute('target')||'').toLowerCase()==='_blank')continue;
      var href=(el.getAttribute('href')||'').trim();
      if(href&&href.charAt(0)!=='#'&&!/^javascript:/i.test(href)){
        // link que navega pra outra URL: não clicar na aba visível (vai para paginação em segundo plano)
        continue;
      }
    }
    var lbl=((el.innerText||el.textContent||el.value||el.getAttribute('aria-label')||el.getAttribute('title')||'')+'').replace(/\s+/g,' ').trim();
    var clsName=String(el.className||'');
    if(!lbl&&/load-?more|show-?more|ver-?mais|carregar-?mais/i.test(clsName))lbl='load more';
    if(!lbl||lbl.length>40||neg.test(lbl))continue;
    if(pos.test(lbl)||/load-?more|show-?more/i.test(clsName)){
      try{el.scrollIntoView({block:'center',behavior:'instant'})}catch(e){try{el.scrollIntoView()}catch(e2){}}
      try{el.click();didClick=1;clicked=lbl;break}catch(e){}
    }
  }
  var cards=document.querySelectorAll('a[href],video,article,[class*=card],[class*=item],[class*=video]').length;
  return JSON.stringify({clicked:didClick,label:clicked,scrollH:h,cards:cards});
}catch(e){return JSON.stringify({clicked:0,label:'',scrollH:0,cards:0})}
})()"""

    const val PAGINATION_JS = """(function(){
try{
  var out=[];
  var seen={};
  function add(u){
    if(!u||typeof u!=='string')return;
    try{u=new URL(u,location.href).href}catch(e){return}
    u=u.split('#')[0];
    if(!/^https?:/i.test(u)||u===location.href.split('#')[0]||seen[u])return;
    seen[u]=1; out.push(u);
  }
  var neg=/(?:login|entrar|cadastr|registr|conta|account|comprar|buy|assinar|subscri|plano|vip|premium|checkout|cart|cookie|share)/i;
  document.querySelectorAll('link[rel~=next],a[rel~=next]').forEach(function(el){
    if((el.getAttribute('target')||'').toLowerCase()==='_blank')return;
    if(new URL(el.href,location.href).host===location.host)add(el.href);
  });
  var nextRe=/^(?:next(?:\s*page)?|pr[oó]xim[ao](?:\s*p[aá]gina)?|seguinte|avan[cç]ar|›|»|>|>>)$/i;
  var as=document.querySelectorAll('a[href]');
  for(var i=0;i<as.length;i++){
    var a=as[i];
    if((a.getAttribute('target')||'').toLowerCase()==='_blank')continue;
    try{if(new URL(a.href,location.href).host!==location.host)continue}catch(e){continue}
    var lbl=((a.innerText||a.textContent||a.getAttribute('aria-label')||a.getAttribute('title')||'')+'').replace(/\s+/g,' ').trim();
    if(neg.test(lbl)||neg.test(a.href))continue;
    if(lbl.length<30&&nextRe.test(lbl))add(a.href);
  }
  var nums=[];
  for(var j=0;j<as.length;j++){
    var b=as[j];
    if((b.getAttribute('target')||'').toLowerCase()==='_blank')continue;
    try{if(new URL(b.href,location.href).host!==location.host)continue}catch(e){continue}
    var t=(b.innerText||b.textContent||'').trim();
    if(!/^\d{1,3}$/.test(t))continue;
    var n=parseInt(t,10);
    if(n<=1||n>200)continue;
    var p=b,inP=false,k=0;
    while(p&&k<6){var cls=((p.className||'')+' '+(p.id||'')+' '+(p.tagName||'')).toLowerCase();if(/pag|pager|nav/.test(cls)){inP=true;break}p=p.parentElement;k++}
    if(inP||/[?&/](?:page|p|pg|pagina|pag)=?\d+|\/page\/\d+/i.test(b.href)){nums.push({n:n,u:b.href})}
  }
  nums.sort(function(x,y){return x.n-y.n});
  nums.forEach(function(x){add(x.u)});
  return JSON.stringify(out.slice(0,50));
}catch(e){return '[]'}
})()"""

    /**
     * Executa paginação em segundo plano (sem navegar a aba visível):
     * para cada URL seguinte do mesmo domínio, lê via [fetchHtml] (ou [renderHtml] se HTML estático não tiver cards),
     * entrega para [collectPage] e para quando atingir [maxPages], acabar a fila, ou uma página não trouxer itens novos.
     */
    suspend fun runPagination(
        startUrl: String,
        seedUrls: List<String>,
        maxPages: Int = DEFAULT_MAX_PAGES,
        isStopped: () -> Boolean,
        itemCount: () -> Int,
        fetchHtml: suspend (pageUrl: String, referer: String) -> String?,
        renderHtml: (suspend (pageUrl: String) -> Pair<String?, JsScripts.Collected?>)? = null,
        collectPage: suspend (pageUrl: String, html: String, preCollected: JsScripts.Collected?) -> Int,
        onProgress: (pageNum: Int, totalItems: Int) -> Unit,
    ): Int {
        val visited = LinkedHashSet<String>().apply { add(startUrl.substringBefore('#')) }
        val queue = ArrayDeque<String>()
        val baseHost = Extract.host(startUrl)
        for (u in seedUrls) {
            val clean = u.substringBefore('#')
            if (Extract.host(clean) == baseHost && canFollowSameDomain(startUrl, clean) && visited.add(clean)) {
                queue.addLast(clean)
            }
        }
        var pageNum = 1
        var prevUrl = startUrl
        val limit = maxPages.coerceIn(1, 100)
        while (queue.isNotEmpty() && pageNum < limit && !isStopped()) {
            currentCoroutineContext().ensureActive()
            val nextUrl = queue.removeFirst()
            pageNum++
            val before = itemCount()
            var html = try { fetchHtml(nextUrl, prevUrl) } catch (e: Exception) { if (e is CancellationException) throw e; null }
            var col: JsScripts.Collected? = null
            var added = if (html != null) collectPage(nextUrl, html, null) else 0
            if (added <= 0 && renderHtml != null && !isStopped()) {
                val (rh, rc) = try { renderHtml(nextUrl) } catch (e: Exception) { if (e is CancellationException) throw e; null to null }
                if (rh != null) html = rh
                if (rc != null || rh != null) {
                    col = rc
                    added = collectPage(nextUrl, html.orEmpty(), rc)
                }
            }
            val nowTotal = itemCount()
            onProgress(pageNum, nowTotal)
            if (nowTotal <= before && added <= 0) {
                // página sem nenhum item novo: para
                break
            }
            if (!html.isNullOrBlank()) {
                val more = extractNextUrls(nextUrl, html, visited, pageNum)
                for (m in more) {
                    if (visited.add(m)) queue.addLast(m)
                }
            } else if (col != null && col.next.startsWith("http")) {
                val nx = col.next.substringBefore('#')
                if (Extract.host(nx) == baseHost && visited.add(nx)) queue.addLast(nx)
            }
            prevUrl = nextUrl
        }
        return pageNum
    }
}
