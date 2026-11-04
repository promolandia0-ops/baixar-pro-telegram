package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class V16Test {
    private class Mem(val m: Map<String, String>) : CookieSource {
        override fun cookieFor(url: String) = m.entries.firstOrNull { java.net.URI(url).host.endsWith(it.key) }?.value
    }

    @Test fun cookiesOnlyForMatchingDomain() {
        Net.cookies = Mem(mapOf("example.com" to "sess=abc"))
        assertEquals("sess=abc", Net.withCookies(Request.Builder().url("https://www.example.com/v.mp4").build()).header("Cookie"))
        assertNull(Net.withCookies(Request.Builder().url("https://other.org/").header("Cookie", "x=1").build()).header("Cookie"))
        Net.cookies = Mem(mapOf("mega.nz" to "s=1", "api.telegram.org" to "t=1"))
        assertNull(Net.withCookies(Request.Builder().url("https://mega.nz/x").build()).header("Cookie"))
        assertNull(Net.withCookies(Request.Builder().url("https://api.telegram.org/x").build()).header("Cookie"))
        Net.cookies = null
    }

    @Test fun uaAndReferer() {
        Net.userAgent = "TestUA/1"
        val r = Net.decorate(Request.Builder().url("https://site.com/a/b?c=1").build())
        assertEquals("TestUA/1", r.header("User-Agent")); assertEquals("https://site.com/", r.header("Referer"))
        assertEquals("https://x/", Net.decorate(Request.Builder().url("https://site.com/").header("Referer", "https://x/").build()).header("Referer"))
        Net.userAgent = UA
    }

    /** Ponta a ponta pela rede: httpbin devolve os cookies e o UA que recebeu. */
    @Test fun liveHttpbin() = runBlocking {
        Net.cookies = Mem(mapOf("httpbin.org" to "sessao=logado123; outra=xyz")); Net.userAgent = "WebViewUA/9"
        try {
            val c = Net.http.newCall(Request.Builder().url("https://httpbin.org/cookies").build()).await().use { org.json.JSONObject(it.body!!.string()) }
            assertEquals("logado123", c.getJSONObject("cookies").getString("sessao"))
            val h = Net.http.newCall(Request.Builder().url("https://httpbin.org/headers").build()).await().use { org.json.JSONObject(it.body!!.string()).getJSONObject("headers") }
            assertEquals("WebViewUA/9", h.getString("User-Agent")); assertEquals("https://httpbin.org/", h.getString("Referer"))
            // redirecionamento para outro domínio não leva o cookie
            val r = Net.http.newCall(Request.Builder().url("https://httpbin.org/redirect-to?url=https%3A%2F%2Fpostman-echo.com%2Fcookies").build()).await().use { it.body!!.string() }
            assertFalse(r, r.contains("logado123"))
        } catch (e: java.io.IOException) { System.err.println("sem rede: $e") }
        finally { Net.cookies = null; Net.userAgent = UA }
    }

    @Test fun pbkdf2Vector() {
        // RFC 6070-style vetor para HMAC-SHA512 ("password","salt",1)
        val out = Mega.pbkdf2Sha512("password".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals("867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
            out.joinToString("") { "%02x".format(it) })
    }

    @Test fun parseLinks() {
        val f = Mega.parseLink("https://mega.nz/file/AbCdEfGh#" + Mega.b64e(ByteArray(32) { it.toByte() }))!!
        assertFalse(f.folder); assertEquals("AbCdEfGh", f.id); assertEquals(32, f.key.size)
        val d = Mega.parseLink("https://mega.nz/folder/XyZ12345#" + Mega.b64e(ByteArray(16)) + "/folder/sub1")!!
        assertTrue(d.folder); assertEquals("sub1", d.sub)
        assertTrue(Mega.parseLink("https://mega.nz/#F!XyZ12345!" + Mega.b64e(ByteArray(16)))!!.folder)
        assertNull(Mega.parseLink("https://mega.nz/file/AbCd"))
    }

    @Test fun attrRoundTrip() {
        val k = ByteArray(32) { (it * 7).toByte() }
        val plain = "MEGA{\"n\":\"vídeo.mp4\"}".toByteArray().let { it.copyOf((it.size + 15) / 16 * 16) }
        val c = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(Mega.fileKey(k), "AES"), javax.crypto.spec.IvParameterSpec(ByteArray(16))) }
        assertEquals("vídeo.mp4", Mega.decAttr(Mega.b64e(c.doFinal(plain)), k))
    }
}
