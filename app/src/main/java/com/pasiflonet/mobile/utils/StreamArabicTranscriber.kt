package com.pasiflonet.mobile.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Live Arabic -> Hebrew transcription from an internet stream URL.
 * - Audio is decoded from the stream via FFmpegKit into 16kHz mono PCM.
 * - Recognition is offline via Vosk.
 * - Translation is on-device via ML Kit (no API key).
 */
class StreamArabicTranscriber(
    private val ctx: Context,
    private val onHebrewLine: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    private var translator: Translator? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    private var pipePath: String? = null
    private var ffmpegSessionId: Long? = null

    // Arabic model (large) – downloaded once to app storage.
    private val modelZipUrl = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
    private val modelRootDir = File(ctx.filesDir, "vosk")
    private val expectedModelDir = File(modelRootDir, "vosk-model-ar-mgb2-0.4")

    fun isRunning(): Boolean = running.get()

    fun start(streamUrl: String) {
        if (streamUrl.isBlank()) {
            postStatus("אין קישור שידור")
            return
        }
        if (running.getAndSet(true)) return

        thread(name = "stream-ar-transcriber") {
            try {
                postStatus("מכין תרגום…")
                ensureTranslator()

                postStatus("מכין מודל תמלול… (פעם ראשונה יכול לקחת זמן)")
                ensureModel()

                val m = model ?: error("Vosk model not ready")
                recognizer = Recognizer(m, 16000.0f).apply {
                    setMaxAlternatives(0)
                    setWords(false)
                }

                // Create pipe
                pipePath = FFmpegKitConfig.registerNewFFmpegPipe(ctx)
                val pipe = pipePath!!

                // Decode stream audio -> PCM s16le 16k mono
                val cmd = listOf(
                    "-hide_banner", "-loglevel", "error",
                    "-reconnect", "1",
                    "-reconnect_streamed", "1",
                    "-reconnect_delay_max", "5",
                    "-i", streamUrl,
                    "-vn",
                    "-ac", "1",
                    "-ar", "16000",
                    "-f", "s16le",
                    pipe
                ).joinToString(" ")

                postStatus("תמלול פעיל ✅")

                val session = FFmpegKit.executeAsync(cmd) { _ -> }
                ffmpegSessionId = session.sessionId

                FileInputStream(File(pipe)).use { fis ->
                    val buf = ByteArray(4096)
                    var lastAr = ""

                    while (running.get()) {
                        val n = fis.read(buf)
                        if (n <= 0) break

                        val rec = recognizer ?: break
                        val isFinal = rec.acceptWaveForm(buf, n)

                        if (isFinal) {
                            val ar = extractText(rec.result).trim()
                            if (ar.isNotBlank() && ar != lastAr) {
                                lastAr = ar
                                translateAndEmit(ar)
                            }
                        }
                    }
                }

                postStatus("התמלול נעצר")
            } catch (t: Throwable) {
                postStatus("שגיאה בתמלול: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                stopInternal()
            }
        }
    }

    fun stop() {
        running.set(false)
        stopInternal()
    }

    private fun stopInternal() {
        try { ffmpegSessionId?.let { FFmpegKit.cancel(it) } } catch (_: Throwable) {}
        ffmpegSessionId = null

        try { pipePath?.let { FFmpegKitConfig.closeFFmpegPipe(it) } } catch (_: Throwable) {}
        pipePath = null

        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
    }

    private fun ensureTranslator() {
        if (translator != null) return
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ARABIC)
            .setTargetLanguage(TranslateLanguage.HEBREW)
            .build()
        val tr = Translation.getClient(options)
        // Download model on-device if needed
        Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        translator = tr
    }

    private fun ensureModel() {
        if (model != null) return
        modelRootDir.mkdirs()

        // If not extracted – download and unzip
        if (!expectedModelDir.exists() || expectedModelDir.listFiles().isNullOrEmpty()) {
            val zipFile = File(ctx.cacheDir, "vosk_ar.zip")
            downloadToFile(modelZipUrl, zipFile)
            unzip(zipFile, modelRootDir)
            zipFile.delete()
        }

        val dir = if (expectedModelDir.exists()) expectedModelDir else findFirstModelDir(modelRootDir)
        if (dir == null) error("לא מצאתי תיקיית מודל אחרי חילוץ")

        model = Model(dir.absolutePath)
    }

    private fun findFirstModelDir(root: File): File? {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return null
        return dirs.firstOrNull { File(it, "am").exists() && File(it, "conf").exists() }
    }

    private fun downloadToFile(url: String, out: File) {
        postStatus("מוריד מודל Vosk (פעם ראשונה)…")
        URL(url).openStream().use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    if (!running.get()) break
                }
            }
        }
        if (!out.exists() || out.length() < 10_000_000) {
            error("הורדת מודל נכשלה")
        }
    }

    private fun unzip(zipFile: File, outDir: File) {
        postStatus("מחלץ מודל…")
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outPath = File(outDir, entry.name)
                if (entry.isDirectory) {
                    outPath.mkdirs()
                } else {
                    outPath.parentFile?.mkdirs()
                    FileOutputStream(outPath).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun translateAndEmit(arText: String) {
        val tr = translator ?: return
        tr.translate(arText)
            .addOnSuccessListener { he ->
                val out = he?.trim().orEmpty()
                if (out.isNotBlank()) postHebrew(out)
            }
            .addOnFailureListener {
                // Fallback: if translate fails, output Arabic text
                postHebrew(arText)
            }
    }

    private fun extractText(json: String): String {
        return try {
            JSONObject(json).optString("text", "")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun postHebrew(line: String) {
        main.post { onHebrewLine(line) }
    }

    private fun postStatus(s: String) {
        main.post { onStatus(s) }
    }
}
