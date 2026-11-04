package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** Testes pela rede com links públicos do MEGA (pula se não houver rede). */
class MegaLiveTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "megatest").apply { mkdirs() }

    @Test fun publicFile() = runBlocking {
        for (u in listOf("https://mega.nz/file/YfNngDKR#qk9THHhxbakddRmt_tLR8OhInexzVCpPPG6M6feFfZg", "https://mega.nz/file/vJ9EBJBC#vA1XpbngXcnN7lqXEWGQFPDc5ZlG6yltCHwGN-0O2LQ")) {
            try {
                val n = Mega.fileInfo(Mega.parseLink(u)!!)
                println("ARQ $u -> ${n.name} (${n.size} bytes)")
                if (n.size < 30_000_000) {
                    val f = Mega.download(Mega.toFound(n, 'p', page = u), dir) { _, _ -> }
                    println("   baixado ${f.length()} bytes; início: " + f.readBytes().take(48).map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString(""))
                    check(f.length() == n.size)
                }
            } catch (e: MegaException) { println("ARQ $u -> ${e.message}") }
        }
    }

    @Test fun publicFolders() = runBlocking {
        for (u in listOf("https://mega.nz/folder/CJ8y1SDJ#KX8YfTd526P4o_hDH02jLQ", "https://mega.nz/folder/YPV0nCKS#bSruKSPPubdCmm5harBJOQ", "https://mega.nz/folder/saMRXBYL#9GETCO4E-Po45d3qSjZhbQ")) {
            try {
                val l = Mega.parseLink(u)!!; val nodes = Mega.folderNodes(l)
                println("PASTA $u -> ${nodes.size} nós: " + nodes.take(8).joinToString { "${if (it.isFolder) "[d]" else ""}${it.name}(${it.size})" })
                println("   vídeos: ${Mega.resolvePublic(u).size}")
                nodes.filter { !it.isFolder && it.size in 1..5_000_000 }.take(1).forEach { n ->
                    val f = Mega.download(Mega.toFound(n, 'f', l.id, page = u), dir) { _, _ -> }
                    println("   baixado ${n.name}: ${f.length()} bytes; início: " + f.readBytes().take(48).map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString(""))
                    check(f.length() == n.size)
                }
            } catch (e: MegaException) { println("PASTA $u -> ${e.message}") }
        }
    }

    /** Vídeo grande: baixa só o começo e confere que a descriptografia gera um MP4 válido ("ftyp"). */
    @Test fun videoDecryptsToMp4() = runBlocking {
        val l = Mega.parseLink("https://mega.nz/file/vJ9EBJBC#vA1XpbngXcnN7lqXEWGQFPDc5ZlG6yltCHwGN-0O2LQ")!!
        val r = Mega.api(org.json.JSONObject().put("a", "g").put("g", 1).put("ssl", 2).put("p", l.id)) as org.json.JSONObject
        val enc = Net.http.newCall(okhttp3.Request.Builder().url(r.getString("g") + "/0-63").build()).await().use { it.body!!.bytes() }
        val c = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").apply { init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(Mega.fileKey(l.key), "AES"), javax.crypto.spec.IvParameterSpec(Mega.fileIv(l.key))) }
        val dec = String(c.doFinal(enc).copyOfRange(4, 8), Charsets.ISO_8859_1); println("MP4 cabeçalho: $dec"); check(dec == "ftyp")
    }

    @Test fun loginWrongPassword() = runBlocking {
        try { Mega.login("nao-existe-${System.nanoTime()}@example.com", "senha-errada"); error("não deveria entrar") }
        catch (e: MegaException) { println("LOGIN errado -> ${e.code} ${e.message}") }
    }
}
