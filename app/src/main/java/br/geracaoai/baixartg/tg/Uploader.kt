package br.geracaoai.baixartg.tg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream

object Uploader {
    @Volatile private var saved: Long = 0
    suspend fun savedChat(): Long {
        if (saved != 0L) return saved
        val me = Td.send(TdApi.GetMe()); saved = Td.send(TdApi.CreatePrivateChat(me.id, false)).id; return saved
    }

    private class Info(val dur: Int, val w: Int, val h: Int, val thumb: File?)
    private fun probe(f: File): Info = try {
        val r = MediaMetadataRetriever(); r.setDataSource(f.absolutePath)
        val dur = ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000).toInt()
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        val bmp = r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
        val thumb = bmp?.let { b -> File(f.parentFile, f.name + ".jpg").also { t -> FileOutputStream(t).use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } } }
        r.release(); Info(dur, w, h, thumb)
    } catch (_: Throwable) { Info(0, 0, 0, null) }

    /** Envia para Mensagens Salvas. Cancelar a corrotina (Parar) aborta o envio no TDLib. */
    suspend fun upload(file: File, caption: String, asVideo: Boolean = true, onProgress: (Float) -> Unit): TdApi.Message {
        val chat = savedChat()
        val ext = file.extension.lowercase()
        val isVideo = ext in setOf("mp4", "mov", "m4v")
        val isPhoto = !asVideo && ext in setOf("jpg", "jpeg", "png", "webp") && file.length() in 1..(10L * 1024 * 1024)
        if (asVideo && ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
            throw IllegalStateException("O arquivo não é um vídeo (veio uma imagem (capa)).")
        }
        val info = if (isVideo) withContext(Dispatchers.IO) { probe(file) } else Info(0, 0, 0, null)
        // padrão: só a mídia, sem legenda (título/link) — a não ser que o usuário ligue "Enviar legenda"
        val cap = TdApi.FormattedText(if (br.geracaoai.baixartg.Prefs.sendCaption) caption.take(1000) else "", emptyArray())
        val thumb = info.thumb?.let { t -> TdApi.InputThumbnail().apply { thumbnail = TdApi.InputFileLocal(t.absolutePath); width = 320; height = 320 } }
        val content: TdApi.InputMessageContent = if (isVideo && info.w > 0) TdApi.InputMessageVideo().apply {
            video = TdApi.InputVideo().apply { video = TdApi.InputFileLocal(file.absolutePath); thumbnail = thumb; duration = info.dur; width = info.w; height = info.h
            supportsStreaming = true }; this.caption = cap
        } else if (isPhoto) {
            val (w, h) = withContext(Dispatchers.IO) { photoSize(file) }
            TdApi.InputMessagePhoto().apply {
                photo = TdApi.InputPhoto().apply {
                    photo = TdApi.InputFileLocal(file.absolutePath)
                    width = w; height = h
                }
                this.caption = cap
            }
        } else TdApi.InputMessageDocument().apply {
            document = TdApi.InputDocument().apply { document = TdApi.InputFileLocal(file.absolutePath); thumbnail = thumb }; this.caption = cap
        }
        try {
            val tmp = Td.send(TdApi.SendMessage().apply { chatId = chat; inputMessageContent = content; options = TdApi.MessageSendOptions().apply { disableNotification = true } })
            val fileId = fileOf(tmp)?.id ?: -1
            val job = CoroutineScope(Dispatchers.Default).launch {
                Td.fileUpdates.collect { f -> if (f.id == fileId && f.expectedSize > 0) onProgress(f.remote.uploadedSize.toFloat() / f.expectedSize) }
            }
            val res = try { Td.awaitSend(tmp.id) } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    try { Td.send(TdApi.DeleteMessages(chat, longArrayOf(tmp.id), true)) } catch (_: Exception) {}
                    if (fileId > 0) try { Td.send(TdApi.CancelPreliminaryUploadFile(fileId)) } catch (_: Exception) {}
                }
                throw e
            } finally { job.cancel() }
            return res.message ?: throw TdException(res.error?.code ?: 0, res.error?.message ?: "Falha no envio")
        } finally { info.thumb?.delete() }
    }

    private fun fileOf(m: TdApi.Message): TdApi.File? = when (val c = m.content) {
        is TdApi.MessageVideo -> c.video.video
        is TdApi.MessagePhoto -> c.photo.sizes.maxByOrNull { it.photo.expectedSize }?.photo
        is TdApi.MessageDocument -> c.document.document
        else -> null
    }

    private fun photoSize(f: File): Pair<Int, Int> = try {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opt)
        (opt.outWidth.coerceAtLeast(0)) to (opt.outHeight.coerceAtLeast(0))
    } catch (_: Throwable) { 0 to 0 }
}
