package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.PhotoGrab
import br.geracaoai.baixartg.core.Sent
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V199Test {
    @Test fun upgradesThumbnailUrls() {
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/4/45/Fusca.JPG",
            PhotoGrab.upgrade("https://upload.wikimedia.org/wikipedia/commons/thumb/4/45/Fusca.JPG/500px-Fusca.JPG")
        )
        assertEquals(
            "https://site.com/wp-content/uploads/2024/01/foto.jpg",
            PhotoGrab.upgrade("https://site.com/wp-content/uploads/2024/01/foto-300x200.jpg")
        )
        assertEquals(
            "https://cdn.com/pics/foto.jpg",
            PhotoGrab.upgrade("https://cdn.com/pics/foto_thumb.jpg")
        )
        assertEquals(
            "https://cdn.com/pics/foto.jpg",
            PhotoGrab.upgrade("https://cdn.com/thumbs/pics/foto.jpg")
        )
        assertEquals(
            "https://img.com/p.jpg?auto=compress",
            PhotoGrab.upgrade("https://img.com/p.jpg?auto=compress&w=240")
        )
        assertEquals(
            "https://img.com/p.jpg?auto=compress&w=1260",
            PhotoGrab.upgrade("https://img.com/p.jpg?auto=compress&w=1260")
        )
        assertEquals(
            "https://pbs.twimg.com/media/ABC.jpg?name=orig",
            PhotoGrab.upgrade("https://pbs.twimg.com/media/ABC.jpg?name=small")
        )
        assertEquals(
            "https://pbs.twimg.com/media/ABC.jpg?format=jpg&name=orig",
            PhotoGrab.upgrade("https://pbs.twimg.com/media/ABC.jpg?format=jpg&name=medium")
        )
        assertTrue(PhotoGrab.upgradeStrong("https://img.com/p.jpg?w=1260") == "https://img.com/p.jpg" ||
            PhotoGrab.upgradeStrong("https://img.com/p.jpg?w=1260").endsWith("p.jpg"))
        assertEquals(PhotoGrab.family("https://site.com/a-300x200.jpg"), PhotoGrab.family("https://site.com/a.jpg"))
    }

    @Test fun selectKeepsLargestOfSamePhoto() {
        fun c(u: String, w: Int = 0, h: Int = 0, o: Boolean = false) = PhotoGrab.Cand(u, w, h, "", "", "img", o)
        val got = PhotoGrab.select(listOf(
            c("https://site.com/foto-300x200.jpg", 300, 200),
            c("https://site.com/foto.jpg", 2000, 1500, true),
            c("https://encrypted-tbn0.gstatic.com/images?q=tbn:AAA", 150, 150),
            c("https://images.example.com/orig.jpg", 1800, 1200, true)
        ))
        assertTrue(got.any { it.url == "https://site.com/foto.jpg" && it.orig })
        assertTrue(got.none { it.url.contains("-300x200") })
        assertTrue(got.none { it.url.contains("encrypted-tbn") })
    }

    @Test fun unqueueRemovesOnlyQueuedKeys() {
        val dir = File.createTempFile("sent", "dir").apply { delete(); mkdirs() }
        try {
            Sent.init(dir)
            Sent.markQueued(listOf("pg:a.com/1", "v:cdn.com/a.mp4"))
            assertTrue(Sent.isQueued(listOf("pg:a.com/1")))
            Sent.unqueue(listOf("pg:a.com/1"))
            assertFalse(Sent.isQueued(listOf("pg:a.com/1")))
            assertTrue(Sent.isQueued(listOf("v:cdn.com/a.mp4")))
        } finally { dir.deleteRecursively() }
    }
}
