package br.geracaoai.baixartg.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

class MegaException(val code: Int, msg: String) : java.io.IOException(msg)

/**
 * Cliente MEGA em Kotlin puro (protocolo oficial da API "cs" + criptografia do lado do cliente).
 * A senha só é usada na hora do login para derivar a chave; nunca é guardada.
 * Guardamos apenas a sessão (sid) + chave mestra, criptografadas pelo Android Keystore (MegaStore).
 */
object Mega {
    const val API = "https://g.api.mega.co.nz/cs"

    data class Session(val sid: String, val masterKey: ByteArray, val user: String, val email: String = "")
    /** h = handle, parent, type 0 arquivo / 1 pasta / 2 raiz / 3 entrada / 4 lixeira. key = chave completa (32 bytes arquivo, 16 pasta). */
    data class Node(val h: String, val parent: String, val type: Int, val name: String, val size: Long, val key: ByteArray?, val ts: Long = 0) {
        val isFolder get() = type != 0
        val isVideo get() = type == 0 && VIDEO.containsMatchIn(name)
    }
    val VIDEO = Regex("""\.(mp4|m4v|mkv|mov|webm|avi|wmv|flv|3gp|ts|mts|m2ts|mpg|mpeg|vob|ogv)$""", RegexOption.IGNORE_CASE)

    @Volatile var session: Session? = null
    private val seq = AtomicLong((Math.random() * 1e9).toLong())

    // ---------- utilitários ----------
    fun b64d(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim().replace('+', '-').replace('/', '_').trimEnd('='))
    fun b64e(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun ecb(key: ByteArray, data: ByteArray, enc: Boolean): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(if (enc) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(data)
    fun aesDec(key: ByteArray, data: ByteArray) = ecb(key, data, false)
    fun aesEnc(key: ByteArray, data: ByteArray) = ecb(key, data, true)
    private fun pad16(b: ByteArray) = if (b.size % 16 == 0) b else b.copyOf((b.size + 15) / 16 * 16)

    /** Chave AES de arquivo (xor das metades) e IV do CTR. */
    fun fileKey(k: ByteArray): ByteArray = if (k.size == 32) ByteArray(16) { (k[it].toInt() xor k[it + 16].toInt()).toByte() } else k.copyOf(16)
    fun fileIv(k: ByteArray): ByteArray = ByteArray(16).also { if (k.size == 32) System.arraycopy(k, 16, it, 0, 8) }

    /** Atributos (nome) do nó: AES-CBC IV zero, texto "MEGA{json}". */
    fun decAttr(at: String, k: ByteArray): String? = try {
        val c = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey(k), "AES"), IvParameterSpec(ByteArray(16))) }
        val s = String(c.doFinal(pad16(b64d(at))), Charsets.UTF_8).trimEnd('\u0000')
        if (!s.startsWith("MEGA{")) null else JSONObject(s.substring(4)).optString("n", null)
    } catch (_: Exception) { null }

    // ---------- links públicos ----------
    data class Link(val folder: Boolean, val id: String, val key: ByteArray, val sub: String? = null)
    fun parseLink(u: String): Link? {
        val s = u.trim()
        Regex("""mega(?:\.co)?\.nz/(file|folder)/([\w-]+)#([\w-]+)(?:/(?:file|folder)/([\w-]+))?""").find(s)?.let { m ->
            return try { Link(m.groupValues[1] == "folder", m.groupValues[2], b64d(m.groupValues[3]), m.groupValues[4].ifBlank { null }) } catch (_: Exception) { null } }
        Regex("""mega(?:\.co)?\.nz/#(F?)!([\w-]+)!([\w-]+)""").find(s)?.let { m ->
            return try { Link(m.groupValues[1] == "F", m.groupValues[2], b64d(m.groupValues[3])) } catch (_: Exception) { null } }
        return null
    }
    fun isMegaLink(u: String) = parseLink(u) != null

    // ---------- API ----------
    private fun errMsg(c: Int) = when (c) {
        -2 -> "MEGA: pedido inválido"; -3 -> "MEGA: servidor ocupado, tente de novo"; -9 -> "MEGA: arquivo/pasta não encontrado (link ou chave errada?)"
        -11 -> "MEGA: acesso negado"; -13 -> "MEGA: incompleto"; -14 -> "MEGA: chave de descriptografia errada"; -15 -> "MEGA: sessão expirada, entre de novo"
        -16 -> "MEGA: conta ou arquivo bloqueado"; -17 -> "MEGA: limite de transferência atingido"; -18 -> "MEGA: temporariamente indisponível"
        -26 -> "MEGA: digite o código de verificação em duas etapas"; -9999 -> "MEGA: e-mail ou senha incorretos"
        else -> "MEGA: erro $c" }

    /** Faz uma chamada à API e devolve o primeiro resultado (JSONObject, JSONArray ou número). */
    suspend fun api(cmd: JSONObject, sid: String? = null, folder: String? = null): Any {
        var wait = 500L
        repeat(6) {
            var url = "$API?id=${seq.incrementAndGet()}"
            if (sid != null) url += "&sid=$sid"
            if (folder != null) url += "&n=$folder"
            val body = JSONArray().put(cmd).toString().toRequestBody("application/json".toMediaType())
            var hc: String? = null; var txt: String? = null
            for (pass in 0..1) {
                val rb = Request.Builder().url(url).post(body); if (hc != null) rb.header("X-Hashcash", hc)
                val (code, chal, t) = Net.http.newCall(rb.build()).await().use { r -> Triple(r.code, r.header("X-Hashcash"), r.body?.string() ?: "") }
                if (code == 402 && chal != null && pass == 0) { hc = hashcash(chal); continue } // prova de trabalho exigida pelo MEGA no login
                txt = if (code == 500 || code == 503 || t.isBlank()) null else t; break
            }
            if (txt != null) {
                val t = txt.trim()
                val code = if (t.startsWith("-")) t.toIntOrNull() else if (t.startsWith("[-")) JSONArray(t).optInt(0) else null
                if (code != null && code != -3) throw MegaException(code, errMsg(code))
                if (code == null) {
                    val res = JSONArray(t).get(0)
                    if (res is Number && res.toInt() < 0) { if (res.toInt() != -3) throw MegaException(res.toInt(), errMsg(res.toInt())) }
                    else if (res is JSONObject && res.has("e") && res.opt("e") is Number && res.getInt("e") < 0) throw MegaException(res.getInt("e"), errMsg(res.getInt("e")))
                    else return res
                }
            }
            delay(wait); wait *= 2
        }
        throw MegaException(-3, errMsg(-3))
    }

    // ---------- login ----------
    /** Desafio X-Hashcash do MEGA (desde 2025 no login): acha nonce tal que SHA-256(nonce + token*2^18) <= limiar. */
    fun hashcash(challenge: String): String {
        val (ver, eas, _, token) = challenge.split(":")
        val e = eas.toInt()
        val threshold = (((e and 63) shl 1) + 1).toLong() shl ((e shr 6) * 7 + 3)
        val tk = b64d(token); val n = 262144
        val buf = ByteArray(4 + tk.size * n); for (i in 0 until n) System.arraycopy(tk, 0, buf, 4 + i * tk.size, tk.size)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var nonce = 0
        while (true) {
            buf[0] = nonce.toByte(); buf[1] = (nonce ushr 8).toByte(); buf[2] = (nonce ushr 16).toByte(); buf[3] = (nonce ushr 24).toByte()
            val d = md.digest(buf)
            val v = ((d[0].toLong() and 255) shl 24) or ((d[1].toLong() and 255) shl 16) or ((d[2].toLong() and 255) shl 8) or (d[3].toLong() and 255)
            if (v <= threshold) return "$ver:$token:${b64e(buf.copyOf(4))}"
            nonce++
        }
    }
    fun pbkdf2Sha512(pw: ByteArray, salt: ByteArray, iter: Int, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(if (pw.isEmpty()) ByteArray(1) else pw, "HmacSHA512")) }
        val out = ByteArray(len); var block = 1; var off = 0
        while (off < len) {
            mac.update(salt); mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal(); val t = u.copyOf()
            for (i in 1 until iter) { u = mac.doFinal(u); for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte() }
            val n = minOf(t.size, len - off); System.arraycopy(t, 0, out, off, n); off += n; block++
        }
        return out
    }

    /** Contas antigas (v1): chave derivada com 65536 rodadas de AES. */
    fun prepareKeyV1(pw: ByteArray): ByteArray {
        val p = pad16(if (pw.size % 4 == 0) pw else pw.copyOf((pw.size + 3) / 4 * 4))
        var k = byteArrayOf(0x93.toByte(), 0xC4.toByte(), 0x67, 0xE3.toByte(), 0x7D, 0xB0.toByte(), 0xC7.toByte(), 0xA4.toByte(), 0xD1.toByte(), 0xBE.toByte(), 0x3F, 0x81.toByte(), 0x01, 0x52, 0xCB.toByte(), 0x56)
        val keys = (0 until p.size / 16).map { val c = Cipher.getInstance("AES/ECB/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(p.copyOfRange(it * 16, it * 16 + 16), "AES")); c }
        repeat(65536) { for (c in keys) k = c.doFinal(k) }
        return k
    }
    fun stringHashV1(email: String, key: ByteArray): String {
        val e = email.toByteArray(Charsets.UTF_8); val h = ByteArray(16)
        for (i in e.indices) h[i % 16] = (h[i % 16].toInt() xor e[i].toInt()).toByte()
        val c = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        var x = h; repeat(0x4000) { x = c.doFinal(x) }
        return b64e(x.copyOfRange(0, 4) + x.copyOfRange(8, 12))
    }

    private fun mpis(b: ByteArray, count: Int): List<BigInteger> {
        var o = 0; val out = ArrayList<BigInteger>()
        repeat(count) { val bits = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255); val n = (bits + 7) / 8
            out += BigInteger(1, b.copyOfRange(o + 2, o + 2 + n)); o += 2 + n }
        return out
    }

    /** Login com e-mail e senha. mfa = código de 6 dígitos se a conta tiver verificação em duas etapas. */
    suspend fun login(email0: String, password: String, mfa: String? = null): Session {
        val email = email0.trim().lowercase()
        val pre = api(JSONObject().put("a", "us0").put("user", email)) as JSONObject
        val pw = password.toByteArray(Charsets.UTF_8)
        val (pwKey, uh) = if (pre.optInt("v") == 2) {
            val dk = pbkdf2Sha512(pw, b64d(pre.getString("s")), 100000, 32); dk.copyOf(16) to b64e(dk.copyOfRange(16, 32))
        } else { val k = prepareKeyV1(pw); k to stringHashV1(email, k) }
        java.util.Arrays.fill(pw, 0)
        val cmd = JSONObject().put("a", "us").put("user", email).put("uh", uh)
        if (!mfa.isNullOrBlank()) cmd.put("mfa", mfa.trim())
        val r = try { api(cmd) as JSONObject } catch (e: MegaException) { if (e.code == -9) throw MegaException(-9999, errMsg(-9999)) else throw e }
        val master = aesDec(pwKey, b64d(r.getString("k")))
        val sid = when {
            r.has("csid") -> {
                val priv = aesDec(master, pad16(b64d(r.getString("privk"))))
                val (p, q, d) = mpis(priv, 3)
                val c = mpis(b64d(r.getString("csid")), 1)[0]
                var m = c.modPow(d, p.multiply(q)).toByteArray()
                if (m.isNotEmpty() && m[0] == 0.toByte()) m = m.copyOfRange(1, m.size)
                b64e(m.copyOf(43))
            }
            r.has("tsid") -> r.getString("tsid")
            else -> throw MegaException(-2, "MEGA: resposta de login inesperada")
        }
        val ug = api(JSONObject().put("a", "ug"), sid) as JSONObject
        return Session(sid, master, ug.getString("u"), ug.optString("email", email)).also { session = it }
    }

    suspend fun logout() { val s = session ?: return; session = null; try { api(JSONObject().put("a", "sml"), s.sid) } catch (_: Exception) {} }

    // ---------- nós ----------
    private fun parseNodes(arr: JSONArray, keyFor: (owner: String) -> ByteArray?): List<Node> {
        val out = ArrayList<Node>(arr.length())
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i); val t = n.optInt("t")
            if (t >= 2) { out += Node(n.getString("h"), n.optString("p"), t, when (t) { 2 -> "Meu Drive"; 3 -> "Entrada"; else -> "Lixeira" }, 0, null); continue }
            var key: ByteArray? = null
            for (part in n.optString("k").split('/')) {
                val owner = part.substringBefore(':', ""); val enc = part.substringAfter(':', "")
                if (enc.isEmpty() || enc.length > 60) continue // chaves RSA (compartilhamentos pendentes) não suportadas
                val kk = keyFor(owner) ?: continue
                key = try { aesDec(kk, pad16(b64d(enc))) } catch (_: Exception) { null }
                if (key != null) break
            }
            val name = key?.let { k -> n.optString("a").takeIf { it.isNotEmpty() }?.let { decAttr(it, k) } } ?: continue
            out += Node(n.getString("h"), n.optString("p"), t, name, n.optLong("s"), key, n.optLong("ts"))
        }
        return out
    }

    /** Todos os arquivos/pastas da conta logada. */
    suspend fun accountNodes(s: Session = session ?: throw MegaException(-15, errMsg(-15))): List<Node> {
        val r = api(JSONObject().put("a", "f").put("c", 1), s.sid) as JSONObject
        val shares = HashMap<String, ByteArray>()
        r.optJSONArray("ok")?.let { ok -> for (i in 0 until ok.length()) { val o = ok.getJSONObject(i); try { shares[o.getString("h")] = aesDec(s.masterKey, b64d(o.getString("k"))) } catch (_: Exception) {} } }
        return parseNodes(r.getJSONArray("f")) { owner -> if (owner == s.user) s.masterKey else shares[owner] }
    }

    /** Conteúdo de uma pasta pública (link com chave). */
    suspend fun folderNodes(l: Link): List<Node> {
        val r = api(JSONObject().put("a", "f").put("c", 1).put("r", 1), folder = l.id) as JSONObject
        return parseNodes(r.getJSONArray("f")) { l.key }
    }

    /** Informação de um arquivo público. */
    suspend fun fileInfo(l: Link): Node {
        val r = api(JSONObject().put("a", "g").put("p", l.id)) as JSONObject
        val name = decAttr(r.getString("at"), l.key) ?: throw MegaException(-14, errMsg(-14))
        return Node(l.id, "", 0, name, r.optLong("s"), l.key)
    }

    // ---------- Found (integração com a fila) ----------
    /** mega://a/-/<h>#<k> (conta) · mega://f/<pasta>/<h>#<k> (pasta pública) · mega://p/-/<id>#<k> (arquivo público) */
    fun toFound(n: Node, mode: Char, ctx: String = "-", page: String): Found =
        Found("mega://$mode/$ctx/${n.h}#${b64e(n.key!!)}", page, n.name, Kind.MEGA)

    /** Resolve um link público para a lista de vídeos (arquivo único ou todos os vídeos da pasta, com subpastas). */
    suspend fun resolvePublic(url: String): List<Pair<Found, Long>> {
        val l = parseLink(url) ?: throw MegaException(-2, "Link do MEGA inválido (precisa da chave depois do #)")
        if (!l.folder) { val n = fileInfo(l); return listOf(toFound(n, 'p', page = url) to n.size) }
        val nodes = folderNodes(l)
        val start = l.sub ?: nodes.firstOrNull { n -> n.isFolder && nodes.none { it.h == n.parent } }?.h
        val keep = if (start == null) nodes else { val ok = HashSet<String>().apply { add(start) }
            var changed = true; while (changed) { changed = false; for (n in nodes) if (n.isFolder && n.parent in ok && ok.add(n.h)) changed = true }
            nodes.filter { it.h == start || it.parent in ok } }
        return keep.filter { it.isVideo }.map { toFound(it, 'f', l.id, page = "https://mega.nz/folder/${l.id}#${b64e(l.key)}/file/${it.h}") to it.size }
    }

    /** Baixa e descriptografa (AES-CTR) em qualidade original. */
    suspend fun download(f: Found, dir: File, progress: (Long, Long) -> Unit): File {
        val m = Regex("""^mega://([apf])/([^/]+)/([^#]+)#(.+)$""").find(f.url) ?: throw DlException("Item MEGA inválido")
        val (mode, ctx, h, kb) = m.destructured
        val key = b64d(kb)
        val cmd = JSONObject().put("a", "g").put("g", 1).put("ssl", 2)
        val r = when (mode) {
            "p" -> api(cmd.put("p", h))
            "f" -> api(cmd.put("n", h), folder = ctx)
            else -> { val s = session ?: throw DlException("Entre na sua conta MEGA de novo (aba MEGA)"); api(cmd.put("n", h), s.sid) }
        } as JSONObject
        val dl = r.optString("g").ifBlank { throw DlException("MEGA não liberou o download (limite de transferência?)") }
        val total = r.optLong("s", -1)
        val name = decAttr(r.optString("at"), key) ?: f.title
        val ext = name.substringAfterLast('.', "mp4").lowercase().take(5)
        val out = File(dir.apply { mkdirs() }, Downloader.safe(name) + "_" + System.nanoTime() + "." + ext)
        val c = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey(key), "AES"), IvParameterSpec(fileIv(key))) }
        Net.http.newCall(Request.Builder().url(dl).build()).await().use { resp ->
            if (resp.code == 509) throw DlException("MEGA: limite de transferência atingido. Tente mais tarde ou use uma conta PRO.")
            if (!resp.isSuccessful) throw DlException("MEGA: HTTP ${resp.code} ao baixar")
            val ins = resp.body!!.byteStream(); val buf = ByteArray(256 * 1024); var got = 0L; var last = 0L
            try {
                out.outputStream().buffered(1 shl 20).use { os ->
                    while (true) { coroutineContext.ensureActive(); val n = ins.read(buf); if (n < 0) break
                        os.write(c.update(buf, 0, n)); got += n
                        val now = System.currentTimeMillis(); if (now - last > 300) { last = now; progress(got, total) } }
                    c.doFinal().let { if (it.isNotEmpty()) os.write(it) }
                }
            } catch (e: Throwable) { out.delete(); throw e }
            progress(got, total)
            if (total > 0 && got < total) { out.delete(); throw DlException("Download do MEGA incompleto") }
        }
        return out
    }
}
