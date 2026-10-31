package br.geracaoai.baixartg.tg

import android.graphics.Bitmap
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
    suspend fun upload(file: File, caption: String, onProgress: (Float) -> Unit): TdApi.Message {
        val chat = savedChat()
        val isVideo = file.extension.lowercase() in setOf("mp4", "mov", "m4v")
        val info = if (isVideo) withContext(Dispatchers.IO) { probe(file) } else Info(0, 0, 0, null)
        val cap = TdApi.FormattedText(caption.take(1000), emptyArray())
        val thumb = info.thumb?.let { t -> TdApi.InputThumbnail().apply { thumbnail = TdApi.InputFileLocal(t.absolutePath); width = 320; height = 320 } }
        val content: TdApi.InputMessageContent = if (isVideo && info.w > 0) TdApi.InputMessageVideo().apply {
            video = TdApi.InputVideo().apply { video = TdApi.InputFileLocal(file.absolutePath); thumbnail = thumb; duration = info.dur; width = info.w; height = info.h
            supportsStreaming = true }; this.caption = cap
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
        is TdApi.MessageDocument -> c.document.document
        else -> null
    }
}
