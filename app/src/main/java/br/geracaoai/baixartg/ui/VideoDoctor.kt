package br.geracaoai.baixartg.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.geracaoai.baixartg.Prefs
import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * "Diagnosticar e corrigir": quando a página não mostra vídeo, testa passo a passo por que não achou,
 * tenta corrigir cada problema sozinho e para assim que o vídeo aparece. O relatório não tem links.
 */
object VideoDoctor {
    enum class St { RUNNING, OK, FIXED, FAIL, INFO, SKIP }
    data class Step(val title: String, val st: St, val detail: String = "")

    /** Estado da página, só leitura. */
    const val SNAP_JS = """(function(){try{
  var q=function(s){try{return document.querySelectorAll(s)}catch(e){return []}};
  var vids=q('video'),blob=0,src=0;
  for(var i=0;i<vids.length;i++){var s=vids[i].currentSrc||vids[i].src||'';if(/^blob:/i.test(s))blob++;else if(/^https?:/i.test(s))src++;}
  var ifr=[];q('iframe').forEach(function(f){var s=f.src||'';var r=f.getBoundingClientRect();if(/^https?:/i.test(s)&&r.width>=120&&r.height>=80&&ifr.length<6)ifr.push(s)});
  var txt=((document.body&&document.body.innerText)||'').slice(0,20000);
  var pw=0;q('input[type=password]').forEach(function(p){var r=p.getBoundingClientRect();if(r.width>0&&r.height>0)pw++});
  return JSON.stringify({ready:document.readyState,hook:window.__bptMseHook?1:0,mse:window.__bptMseActive?1:0,
    videos:vids.length,blob:blob,src:src,iframes:ifr,net:(window.__bptNet||[]).length,
    consent:/cookie|consentimento|privacidade|lgpd|gdpr|consent/i.test(txt)?1:0,
    age:/(verifica[cç][aã]o de idade|maior de 18|tenho 18|are you 18|over 18|age verification|18\+)/i.test(txt)?1:0,
    login:pw,players:q('.video-js,.jwplayer,.plyr,.vjs-tech,.shaka-video-container,.bitmovinplayer-container,[class*=player i]').length});
}catch(e){return '{}'}})()"""

    /** Aceita o aviso de cookies (só botões curtos de aceitar). */
    const val CONSENT_JS = """(function(){try{
  var re=/^(aceitar|aceito|aceitar todos|aceitar tudo|aceitar e fechar|accept|accept all|accept cookies|allow all|allow|concordo|agree|i agree|entendi|got it|ok|permitir|permitir todos|continuar)$/i,n=0;
  document.querySelectorAll('button,[role=button],a[class*=accept i],a[id*=accept i],input[type=button],input[type=submit]').forEach(function(el){
    var t=((el.innerText||el.value||'')+'').replace(/\s+/g,' ').trim(); var r=el.getBoundingClientRect();
    if(t.length>0&&t.length<26&&re.test(t)&&r.width>0&&r.height>0){try{el.click();n++}catch(e){}}
  });
  return n;
}catch(e){return -1}})()"""

    /** Leva o player para a tela (players que só carregam quando aparecem). */
    const val SCROLL_JS = """(function(){try{
  var el=document.querySelector('video')||document.querySelector('iframe[src]')||document.querySelector('[class*=player i]');
  if(el){el.scrollIntoView({block:'center'});return 1}
  window.scrollTo(0,document.body.scrollHeight/3);return 0;
}catch(e){return -1}})()"""

    suspend fun eval(web: WebView?, js: String, timeoutMs: Long = 6_000): String? {
        val w = web ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String?> { c ->
                try { w.evaluateJavascript(js) { r -> if (c.isActive) c.resume(r) } } catch (_: Exception) { if (c.isActive) c.resume(null) }
            }
        }
    }

    fun unq(raw: String?): String = try { org.json.JSONTokener(raw ?: "").nextValue() as? String ?: (raw ?: "") } catch (_: Exception) { raw ?: "" }

    fun found(): Int = BrowserDetect.list.count { !it.sent }
}

@Composable
fun VideoDoctorDialog(web: WebView?, page: String, tapPlay: () -> Unit, collect: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val steps = remember { mutableStateListOf<VideoDoctor.Step>() }
    var running by remember { mutableStateOf(true) }
    var verdict by remember { mutableStateOf("") }

    LaunchedEffect(page) {
        val start = VideoDoctor.found()
        CrashLog.mark("diagnóstico de vídeo iniciado")
        fun begin(t: String) { steps.add(VideoDoctor.Step(t, VideoDoctor.St.RUNNING)) }
        fun done(st: VideoDoctor.St, d: String = "") { if (steps.isNotEmpty()) steps[steps.lastIndex] = steps.last().copy(st = st, detail = d) }
        fun gotIt() = VideoDoctor.found() > start
        suspend fun waitFor(ms: Long): Boolean { var t = 0L; while (t < ms) { if (gotIt()) return true; delay(400); t += 400 }; return gotIt() }
        val title = web?.title ?: ""

        try {
            // 1) página
            begin("Página carregada")
            val snap = try { JSONObject(VideoDoctor.unq(VideoDoctor.eval(web, VideoDoctor.SNAP_JS))) } catch (_: Exception) { JSONObject() }
            if (!page.startsWith("http") || snap.length() == 0) { done(VideoDoctor.St.FAIL, "A página não respondeu. Recarregue e tente de novo."); verdict = "A página não respondeu."; return@LaunchedEffect }
            done(VideoDoctor.St.OK, if (snap.optString("ready") == "complete") "pronta" else "ainda carregando (${snap.optString("ready")})")

            // 2) login
            if (snap.optInt("login") > 0) {
                begin("Login"); done(VideoDoctor.St.INFO, "Há um campo de senha nesta página. Se o vídeo é só para quem entra na conta, entre primeiro e abra o vídeo de novo.")
            }

            // 3) gancho de detecção
            begin("Detecção de vídeo ativa na página")
            if (snap.optInt("hook") == 1) done(VideoDoctor.St.OK)
            else { VideoDoctor.eval(web, MseHookScript.SCRIPT); done(VideoDoctor.St.FIXED, "Estava desligada; liguei de novo. Vídeos que o player pedir agora entram na lista.") }

            // 4) o que tem na página
            val iframes = snap.optJSONArray("iframes")
            val nIfr = iframes?.length() ?: 0
            begin("Players na página")
            done(VideoDoctor.St.INFO, "vídeo: ${snap.optInt("videos")} (arquivo: ${snap.optInt("src")}, em pedaços/blob: ${snap.optInt("blob")}) • player em iframe: $nIfr • caixas de player: ${snap.optInt("players")}")

            // 5) vídeos vistos e descartados
            val disc = synchronized(BrowserDiag.discarded) { BrowserDiag.discarded.toList() }
            if (disc.isNotEmpty()) {
                begin("Vídeos vistos mas descartados")
                disc.forEach { (u, why) -> BrowserDetect.intercepted(u, page, title, kindHint = if (Extract.isHls(u)) Kind.HLS else if (Extract.isDash(u)) Kind.SERVER else Kind.DIRECT, force = true) }
                if (waitFor(1_500)) { done(VideoDoctor.St.FIXED, "O app tinha descartado ${disc.size} (${disc.map { it.second }.distinct().joinToString()}). Coloquei de volta na lista."); }
                else done(VideoDoctor.St.FAIL, "${disc.size} descartado(s) (${disc.map { it.second }.distinct().joinToString()}), mas não eram vídeos válidos.")
            }
            if (gotIt()) return@LaunchedEffect

            // 6) aviso de cookies
            if (snap.optInt("consent") == 1) {
                begin("Aviso de cookies")
                val n = VideoDoctor.unq(VideoDoctor.eval(web, VideoDoctor.CONSENT_JS)).toIntOrNull() ?: 0
                if (n > 0) { if (waitFor(4_000)) { done(VideoDoctor.St.FIXED, "Aceitei o aviso e o player liberou o vídeo."); return@LaunchedEffect } else done(VideoDoctor.St.FIXED, "Aceitei o aviso ($n botão).") }
                else done(VideoDoctor.St.INFO, "Não achei o botão de aceitar. Se houver um aviso na tela, toque em aceitar.")
            }

            // 7) confirmação de idade
            if (snap.optInt("age") == 1) {
                begin("Confirmação de idade")
                VideoDoctor.eval(web, AgeGate.BYPASS_JS)
                if (waitFor(4_000)) { done(VideoDoctor.St.FIXED, "Confirmei e o vídeo apareceu."); return@LaunchedEffect } else done(VideoDoctor.St.INFO, "Tentei confirmar. Se a tela ainda pedir, confirme você.")
            }

            // 8) play
            begin("Dar play no player (sem som)")
            tapPlay()
            if (waitFor(5_000)) { done(VideoDoctor.St.FIXED, "O player pediu o vídeo depois do play."); return@LaunchedEffect }
            done(VideoDoctor.St.FAIL, "O play não fez o player pedir um vídeo.")

            // 9) rolar até o player
            begin("Mostrar o player na tela")
            VideoDoctor.eval(web, VideoDoctor.SCROLL_JS); tapPlay(); collect()
            if (waitFor(4_000)) { done(VideoDoctor.St.FIXED, "O player carregou quando apareceu na tela."); return@LaunchedEffect }
            done(VideoDoctor.St.FAIL)

            // 10) endereços no código da página
            begin("Procurar endereços de vídeo no código da página")
            val html = VideoDoctor.unq(VideoDoctor.eval(web, "(function(){try{return document.documentElement.outerHTML}catch(e){return ''}})()", 10_000))
            val urls = try { SiteAnalyzer.mediaUrlsInHtml(page, html).distinct().take(20) } catch (_: Exception) { emptyList() }
            urls.forEach { BrowserDetect.intercepted(it, page, title) }
            if (waitFor(1_500)) { done(VideoDoctor.St.FIXED, "Achei ${urls.size} endereço(s) no código."); return@LaunchedEffect }
            done(if (urls.isEmpty()) VideoDoctor.St.FAIL else VideoDoctor.St.INFO, if (urls.isEmpty()) "Nenhum." else "${urls.size} endereço(s), mas não eram vídeos válidos.")

            // 11) players em iframe e a própria página no servidor yt-dlp
            val targets = buildList { for (i in 0 until nIfr) iframes?.optString(i)?.takeIf { it.startsWith("http") }?.let { add(it) }; add(page) }.distinct().take(4)
            begin("Perguntar ao servidor yt-dlp (${targets.size} endereço(s))")
            var ok = 0; var lastErr = ""
            for (u in targets) {
                val t = withTimeoutOrNull(45_000) { Downloader.probe(Prefs.server, u) }
                if (t != null) { ok++; BrowserDetect.intercepted(u, page, t.ifBlank { title }, kindHint = Kind.SERVER, force = true) }
                else lastErr = Downloader.lastProbeError ?: "não reconheceu"
            }
            if (ok > 0 && waitFor(1_500)) { done(VideoDoctor.St.FIXED, "O servidor reconheceu $ok vídeo(s)${if (nIfr > 0) " (o player estava num iframe)" else ""}."); return@LaunchedEffect }
            done(VideoDoctor.St.FAIL, lastErr.take(160))

            // 12) última leitura completa
            begin("Procurar de novo na página inteira")
            collect()
            if (waitFor(5_000)) { done(VideoDoctor.St.FIXED); return@LaunchedEffect }
            done(VideoDoctor.St.FAIL)
        } finally {
            val got = VideoDoctor.found() - start
            if (verdict.isBlank()) verdict = if (got > 0) "Pronto: $got vídeo(s) na lista." else {
                val s2 = try { JSONObject(VideoDoctor.unq(VideoDoctor.eval(web, VideoDoctor.SNAP_JS))) } catch (_: Exception) { JSONObject() }
                when {
                    s2.optInt("login") > 0 -> "Não achei vídeo. A página pede login: entre na conta e abra a página do vídeo."
                    s2.optInt("blob") > 0 || s2.optInt("mse") == 1 -> "O player monta o vídeo em pedaços e não mostrou a lista. Deixe o vídeo tocando alguns segundos e toque em Procurar."
                    s2.optInt("videos") == 0 && (s2.optJSONArray("iframes")?.length() ?: 0) == 0 -> "Não há player nesta página. Abra a página do vídeo (onde ele toca)."
                    else -> "Não consegui achar o vídeo nesta página. Copie o diagnóstico e mande no chat."
                }
            }
            running = false
            CrashLog.mark("diagnóstico de vídeo: ${if (got > 0) "achou $got" else "nada"}")
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        icon = { Icon(Icons.Rounded.BugReport, null) },
        title = { Text("Diagnosticar e corrigir") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEach { s ->
                    Row(verticalAlignment = Alignment.Top) {
                        if (s.st == VideoDoctor.St.RUNNING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(iconOf(s.st), null, Modifier.size(18.dp), tint = colorOf(s.st))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(s.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (s.detail.isNotBlank()) Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (verdict.isNotBlank()) {
                    HorizontalDivider()
                    Text(verdict, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton({ onClose() }, enabled = !running) { Text(if (running) "Analisando…" else "Fechar") }
        },
        dismissButton = {
            if (!running) TextButton({
                val rep = buildString {
                    appendLine("— diagnosticar e corrigir — app v${br.geracaoai.baixartg.BuildConfig.VERSION_NAME}")
                    steps.forEach { appendLine("[${it.st}] ${it.title}${if (it.detail.isNotBlank()) ": " + it.detail else ""}") }
                    appendLine("resultado: $verdict")
                    append(BrowserDiag.report())
                }
                copy(ctx, rep, "Diagnóstico copiado (sem links). Cole no chat.")
            }) { Text("Copiar") }
        },
    )
}

private fun iconOf(s: VideoDoctor.St): ImageVector = when (s) {
    VideoDoctor.St.OK -> Icons.Rounded.CheckCircle
    VideoDoctor.St.FIXED -> Icons.Rounded.Build
    VideoDoctor.St.FAIL -> Icons.Rounded.Cancel
    VideoDoctor.St.INFO -> Icons.Rounded.Info
    else -> Icons.Rounded.RemoveCircleOutline
}

@Composable
private fun colorOf(s: VideoDoctor.St) = when (s) {
    VideoDoctor.St.OK -> MaterialTheme.colorScheme.primary
    VideoDoctor.St.FIXED -> MaterialTheme.colorScheme.tertiary
    VideoDoctor.St.FAIL -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
