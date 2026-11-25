package br.geracaoai.baixartg.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Logins salvos do Navegador (e-mail/usuário + senha), por site.
 * Ficam só neste aparelho, criptografados com uma chave do Android Keystore (não sai do aparelho, nem em backup).
 * Nunca entram em relatório, diagnóstico, log ou servidor. Só são preenchidos na página do MESMO site.
 */
object CredStore {
    data class Cred(val user: String, val pass: String)
    data class Offer(val site: String, val user: String, val pass: String)

    private const val ALIAS = "baixartg_login_v1"
    @Volatile private var p: SharedPreferences? = null

    /** Pedido de "Salvar login deste site?" para a tela mostrar. */
    val offer = MutableStateFlow<Offer?>(null)

    fun init(c: Context) {
        if (p == null) p = c.applicationContext.getSharedPreferences("logins_salvos", Context.MODE_PRIVATE)
    }

    fun site(hostOrUrl: String): String {
        val h = (if (hostOrUrl.contains("://")) try { java.net.URI(hostOrUrl).host } catch (_: Exception) { null } else hostOrUrl)
            ?.lowercase()?.removePrefix("www.").orEmpty()
        if (h.isBlank()) return ""
        return try { br.geracaoai.baixartg.web.WebCookies.baseDomain(h) } catch (_: Exception) { h }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun enc(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(s.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun dec(s: String): String? = try {
        val iv = Base64.decode(s.substringBefore(':'), Base64.NO_WRAP)
        val data = Base64.decode(s.substringAfter(':'), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(data), Charsets.UTF_8)
    } catch (_: Exception) { null }

    fun get(hostOrUrl: String): Cred? {
        val s = site(hostOrUrl); if (s.isBlank()) return null
        val raw = p?.getString("c:$s", null) ?: return null
        val j = dec(raw)?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return null
        val pass = j.optString("p"); if (pass.isBlank()) return null
        return Cred(j.optString("u"), pass)
    }

    fun save(hostOrUrl: String, user: String, pass: String) {
        val s = site(hostOrUrl); if (s.isBlank() || pass.isBlank()) return
        try { p?.edit()?.putString("c:$s", enc(JSONObject().put("u", user).put("p", pass).toString()))?.remove("n:$s")?.apply() } catch (_: Exception) {}
    }

    fun remove(site: String) { p?.edit()?.remove("c:${site(site)}")?.apply() }
    fun never(hostOrUrl: String) { val s = site(hostOrUrl); if (s.isNotBlank()) p?.edit()?.putBoolean("n:$s", true)?.apply() }
    private fun isNever(s: String) = p?.getBoolean("n:$s", false) == true
    fun sites(): List<String> = p?.all?.keys?.filter { it.startsWith("c:") }?.map { it.removePrefix("c:") }?.sorted().orEmpty()

    /** A página avisou que você entrou (enviou um formulário com senha). [pageUrl] é a página aberta agora (confere o site). */
    fun onLogin(reportedHost: String, user: String, pass: String, pageUrl: String) {
        val s = site(pageUrl)
        if (s.isBlank() || pass.isBlank() || site(reportedHost) != s || isNever(s)) return
        val have = get(s)
        if (have != null && have.user == user && have.pass == pass) return
        val cur = offer.value
        if (cur != null && cur.site == s && cur.user == user && cur.pass == pass) return
        offer.value = Offer(s, user, pass)
    }

    /** Ponte JS do Navegador: só recebe o login digitado; quem decide salvar é você. */
    class Bridge(private val pageUrl: () -> String) {
        @JavascriptInterface
        fun onLogin(host: String?, user: String?, pass: String?) {
            try { onLogin(host.orEmpty(), user.orEmpty(), pass.orEmpty(), pageUrl()) } catch (_: Exception) {}
        }
    }

    /** Percebe quando você entra num site (formulário com senha preenchida) e avisa o app. Não envia nada para fora. */
    const val CAPTURE_JS = """(function(){if(window.__bptCred)return;window.__bptCred=1;
function vis(e){try{var r=e.getBoundingClientRect();return r.width>0&&r.height>0}catch(x){return false}}
function grab(){try{if(!window.BaixarCred)return;var ps=document.querySelectorAll('input[type=password]'),pw=null;
for(var i=0;i<ps.length;i++){if(ps[i].value&&vis(ps[i])){pw=ps[i];break}}if(!pw)return;
var root=pw.form||document,us=root.querySelectorAll('input[type=email],input[type=text],input[type=tel],input:not([type])'),u='';
for(var j=0;j<us.length;j++){if(us[j]!==pw&&us[j].value&&vis(us[j])){u=us[j].value;break}}
if(!u){try{u=sessionStorage.getItem('__bptUser')||''}catch(x){}}
BaixarCred.onLogin(location.hostname,u,pw.value);}catch(e){}}
function keepUser(){try{var es=document.querySelectorAll('input[type=email],input[autocomplete=username],input[name*=mail i],input[name*=user i],input[name*=login i],input[id*=mail i],input[id*=user i]');
for(var i=0;i<es.length;i++){if(es[i].value&&vis(es[i])){sessionStorage.setItem('__bptUser',es[i].value);break}}}catch(e){}}
document.addEventListener('submit',function(){keepUser();grab()},true);
document.addEventListener('click',function(e){var t=e.target&&e.target.closest&&e.target.closest('button,input[type=submit],input[type=button],[role=button],a');if(t){keepUser();setTimeout(grab,0)}},true);
document.addEventListener('keydown',function(e){if(e.key==='Enter'){keepUser();if(e.target&&e.target.type==='password')grab()}},true);
})()"""

    /** Preenche o login salvo (só campos vazios e visíveis). Funciona também em login de duas etapas (e-mail, depois senha). Não clica em Entrar. */
    fun fillJs(c: Cred): String {
        val u = JSONObject.quote(c.user); val pw = JSONObject.quote(c.pass)
        return """(function(u,p){try{
function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0}
function set(el,v){try{var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(el,v)}catch(x){el.value=v}
el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))}
var n=0,ps=document.querySelectorAll('input[type=password]'),pw=null;
for(var i=0;i<ps.length;i++){if(vis(ps[i])){pw=ps[i];break}}
var root=(pw&&pw.form)||document;
var us=root.querySelectorAll('input[type=email],input[autocomplete=username],input[type=text],input[type=tel],input:not([type])');
for(var j=0;j<us.length;j++){var x=us[j];if(x!==pw&&vis(x)&&!x.value&&(pw||/mail|user|login|usu|conta|account/i.test((x.name||'')+' '+(x.id||'')+' '+(x.type||'')+' '+(x.autocomplete||'')+' '+(x.placeholder||'')))){set(x,u);n++;break}}
if(pw&&!pw.value){set(pw,p);n++}
return n}catch(e){return -1}})($u,$pw)"""
    }
}
