package br.geracaoai.baixartg.ui

import android.webkit.WebView
import androidx.compose.foundation.clickable
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
fun VideoDoctorDialog(web: WebView?, page: String, tapPlay: () -> Unit, collect: () -> Unit, example: HistEntry? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val steps = remember { mutableStateListOf<Detective.Step>() }
    var running by remember { mutableStateOf(true) }
    var verdict by remember { mutableStateOf("") }
    // esperando o login: a janela vira uma faixa pequena para você poder digitar na página
    var waitingLogin by remember { mutableStateOf(false) }

    LaunchedEffect(page) {
        val w = web
        if (w == null) { verdict = "O navegador não está aberto."; running = false; return@LaunchedEffect }
        val log = object : Detective.Log {
            override fun begin(t: String) { waitingLogin = t.startsWith("Esperando você entrar"); steps.add(Detective.Step(t, Detective.St.RUNNING)) }
            override fun done(st: Detective.St, d: String) { waitingLogin = false; if (steps.isNotEmpty()) steps[steps.lastIndex] = steps.last().copy(st = st, detail = d) }
            override fun note(d: String) { if (steps.isNotEmpty()) steps[steps.lastIndex] = steps.last().copy(detail = d) }
        }
        try {
            val r = Detective.run(w, Detective.Mode.FULL, Detective.Hooks(tapPlay, collect), log, example)
            verdict = r.verdict
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            CrashLog.write("detetive", e)
            verdict = "O detetive parou por um erro (${e.javaClass.simpleName}). Copie o diagnóstico e mande no chat."
        } finally {
            // passo que ficou rodando (janela fechada/erro) não fica girando no relatório
            for (i in steps.indices) if (steps[i].st == Detective.St.RUNNING) steps[i] = steps[i].copy(st = Detective.St.SKIP)
            running = false
        }
    }

    if (waitingLogin && running) {
        androidx.compose.ui.window.Popup(alignment = Alignment.TopCenter, properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
            Surface(Modifier.padding(top = 56.dp, start = 12.dp, end = 12.dp), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Detetive esperando você entrar na conta…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                    TextButton({ onClose() }) { Text("Parar") }
                }
            }
        }
        return
    }

    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        icon = { Icon(Icons.Rounded.TravelExplore, null) },
        title = { Text("Detetive: vídeos e fotos") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEach { s ->
                    Row(verticalAlignment = Alignment.Top) {
                        if (s.st == Detective.St.RUNNING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
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
            // dá para fechar a qualquer momento (ex.: para entrar na conta); o detetive para junto
            TextButton({ onClose() }) { Text(if (running) "Parar" else "Fechar") }
        },
        dismissButton = {
            if (!running) TextButton({
                val rep = buildString {
                    appendLine("— detetive — app v${br.geracaoai.baixartg.BuildConfig.VERSION_NAME}")
                    steps.forEach { appendLine("[${it.st}] ${it.title}${if (it.detail.isNotBlank()) ": " + it.detail else ""}") }
                    appendLine("resultado: $verdict")
                    append(BrowserDiag.report())
                }
                copy(ctx, rep, "Diagnóstico copiado (sem links). Cole no chat.")
            }) { Text("Copiar") }
        },
    )
}

/**
 * "Aprender com um vídeo baixado": os últimos 30 vídeos do histórico (só no celular). Você escolhe um que deu certo e o
 * detetive descobre o método e repete nesta página e nas outras aulas.
 */
@Composable
fun LearnPickDialog(onPick: (HistEntry) -> Unit, onClose: () -> Unit) {
    val all by History.list.collectAsState()
    val items = remember(all) { all.filter { it.f.kind != Kind.PHOTO }.take(30) }
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Rounded.School, null) },
        title = { Text("Aprender com um vídeo baixado") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Escolha um vídeo que deu certo. O detetive descobre como ele foi achado e baixado e repete o mesmo caminho nas outras aulas do site.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                if (items.isEmpty()) Text("Nenhum vídeo baixado ainda.", style = MaterialTheme.typography.bodyMedium)
                items.forEach { e ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(e) }.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        run {
                            Text(e.f.title.ifBlank { Extract.fileName(e.f.url) }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            val whenTxt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(e.time))
                            Text("${Extract.host(e.f.page.ifBlank { e.f.url })} • $whenTxt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Fechar") } },
    )
}

private fun iconOf(s: Detective.St): ImageVector = when (s) {
    Detective.St.OK -> Icons.Rounded.CheckCircle
    Detective.St.FIXED -> Icons.Rounded.Build
    Detective.St.FAIL -> Icons.Rounded.Cancel
    Detective.St.INFO -> Icons.Rounded.Info
    else -> Icons.Rounded.RemoveCircleOutline
}

@Composable
private fun colorOf(s: Detective.St) = when (s) {
    Detective.St.OK -> MaterialTheme.colorScheme.primary
    Detective.St.FIXED -> MaterialTheme.colorScheme.tertiary
    Detective.St.FAIL -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
