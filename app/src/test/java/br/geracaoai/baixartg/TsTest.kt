package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.TsDemux
import org.junit.Test
import java.io.File

class TsTest {
    @Test fun demux() {
        val f = File("/tmp/rx/clip.hls.ts"); if (!f.exists()) return
        val d = TsDemux(f); d.probe(); var v = 0; var a = 0; var k = 0; var first = -1L; var lastA = -1L; var bad = 0
        val md5 = java.security.MessageDigest.getInstance("MD5")
        while (true) { val s = d.next() ?: break; if (s.video) { v++; if (s.key) k++; md5.update(s.data) } else { a++; if (s.ptsUs <= lastA) bad++; lastA = s.ptsUs }; if (first < 0) first = s.ptsUs }
        println("TS v=$v (key=$k) a=$a sr=${d.sampleRate} audioMonotonicErr=$bad firstPts=$first lastA=$lastA vmd5=${md5.digest().joinToString("") { "%02x".format(it) }}")
    }
}
