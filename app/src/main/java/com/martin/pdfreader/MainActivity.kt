package com.martin.pdfreader

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

/**
 * Pick a PDF, pull its text out, and read it aloud with an offline Piper voice.
 *
 * Everything lives in this one file on purpose. The pieces are:
 *   1. loadVoice()      - starts the speech engine (sherpa-onnx + Piper model)
 *   2. openPdf()        - extracts text with PdfBox and splits it into sentences
 *   3. Session          - one playback run: a thread that turns sentences into
 *                         audio and a thread that plays that audio
 */
class MainActivity : Activity() {

    private lateinit var openButton: Button
    private lateinit var playButton: Button
    private lateinit var stopButton: Button
    private lateinit var status: TextView
    private lateinit var sentenceView: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speedBar: SeekBar

    private var tts: OfflineTts? = null
    private val ttsLock = Any()

    private var sentences: List<String> = emptyList()
    private var fileName = ""
    private var position = 0          // index of the sentence being read
    private var session: Session? = null

    @Volatile
    private var speed = 1.0f

    // ---------------------------------------------------------------- setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        openButton = findViewById(R.id.openButton)
        playButton = findViewById(R.id.playButton)
        stopButton = findViewById(R.id.stopButton)
        status = findViewById(R.id.status)
        sentenceView = findViewById(R.id.sentence)
        speedLabel = findViewById(R.id.speedLabel)
        speedBar = findViewById(R.id.speedBar)

        PDFBoxResourceLoader.init(applicationContext)

        openButton.setOnClickListener { pickPdf() }
        playButton.setOnClickListener { togglePlay() }
        stopButton.setOnClickListener { stop() }

        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                // 0..15 on the slider -> 0.5x..2.0x
                speed = 0.5f + progress * 0.1f
                speedLabel.text = "Speed %.1fx".format(speed)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {
                // Restart from the current sentence so the new speed is heard right away.
                val current = session
                if (current != null && !current.paused) {
                    current.cancel()
                    startSession()
                } else if (current != null) {
                    current.cancel()
                    session = null
                    refreshButtons()
                }
            }
        })

        loadVoice()
        intent?.data?.let { openPdf(it) }   // launched via "Open with"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { openPdf(it) }
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        val engine = tts
        tts = null
        if (engine != null) {
            // Wait for any sentence still being generated before freeing the engine.
            thread { synchronized(ttsLock) { engine.release() } }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- voice

    private fun loadVoice() {
        thread(name = "load-voice") {
            try {
                // The model is read straight from the APK's assets, but the
                // pronunciation data has to be real files on disk.
                val dataDir = copyAssetDirOnce("voice/espeak-ng-data")
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = "voice/$MODEL_FILE",
                            tokens = "voice/tokens.txt",
                            dataDir = dataDir,
                        ),
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                        debug = false,
                        provider = "cpu",
                    ),
                )
                val engine = OfflineTts(assetManager = assets, config = config)
                runOnUiThread {
                    tts = engine
                    showStatus()
                    refreshButtons()
                }
            } catch (t: Throwable) {
                runOnUiThread { status.text = "Voice failed to load: ${t.message ?: t.javaClass.simpleName}" }
            }
        }
    }

    /** Copies an asset folder into app storage (once per install/update) and returns its path. */
    private fun copyAssetDirOnce(assetPath: String): String {
        val stamp = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        val marker = File(filesDir, "voice/.copied")
        val target = File(filesDir, assetPath)
        if (!marker.exists() || marker.readText() != stamp) {
            target.deleteRecursively()
            copyAssets(assetPath)
            marker.parentFile?.mkdirs()
            marker.writeText(stamp)
        }
        return target.absolutePath
    }

    private fun copyAssets(path: String) {
        val children = assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            val out = File(filesDir, path)
            out.parentFile?.mkdirs()
            assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } }
        } else {
            for (child in children) copyAssets("$path/$child")
        }
    }

    // ------------------------------------------------------------------ pdf

    private fun pickPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, PICK_PDF)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_PDF && resultCode == RESULT_OK) {
            data?.data?.let { openPdf(it) }
        }
    }

    private fun openPdf(uri: Uri) {
        stop()
        sentences = emptyList()
        fileName = displayName(uri)
        status.text = "Reading $fileName…"
        sentenceView.text = ""
        refreshButtons()

        thread(name = "open-pdf") {
            try {
                val text = contentResolver.openInputStream(uri)!!.use { input ->
                    PDDocument.load(input).use { doc -> PDFTextStripper().getText(doc) }
                }
                val result = splitSentences(text)
                runOnUiThread {
                    sentences = result
                    position = 0
                    if (result.isEmpty()) {
                        status.text = "No text found in $fileName. It may be a scanned PDF."
                    } else {
                        sentenceView.text = result[0]
                        showStatus()
                    }
                    refreshButtons()
                }
            } catch (t: Throwable) {
                runOnUiThread { status.text = "Could not open $fileName: ${t.message ?: t.javaClass.simpleName}" }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: "PDF"
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "PDF"
    }

    /** Cleans up PDF line breaks and cuts the text into speakable pieces. */
    private fun splitSentences(raw: String): List<String> {
        val clean = raw
            .replace("\r", "")
            .replace(Regex("(\\p{L})-\\n(\\p{Ll})"), "\$1\$2")   // re-join hyphen-ated words
            .replace(Regex("\\s+"), " ")
            .trim()

        val out = ArrayList<String>()
        for (sentence in clean.split(Regex("(?<=[.!?…])\\s+"))) {
            var rest = sentence.trim()
            // Very long "sentences" (tables, lists) are cut at a comma or space.
            while (rest.length > MAX_CHARS) {
                var cut = rest.lastIndexOfAny(charArrayOf(',', ';', ':'), MAX_CHARS)
                if (cut < MAX_CHARS / 2) cut = rest.lastIndexOf(' ', MAX_CHARS)
                if (cut <= 0) cut = MAX_CHARS - 1
                out.add(rest.substring(0, cut + 1).trim())
                rest = rest.substring(cut + 1).trim()
            }
            out.add(rest)
        }
        return out.filter { s -> s.any { it.isLetterOrDigit() } }
    }

    // ------------------------------------------------------------- controls

    private fun togglePlay() {
        val current = session
        if (current == null) {
            startSession()
        } else {
            current.paused = !current.paused
            refreshButtons()
        }
    }

    private fun stop() {
        session?.cancel()
        session = null
        position = 0
        if (sentences.isNotEmpty()) {
            sentenceView.text = sentences[0]
            showStatus()
        }
        refreshButtons()
    }

    private fun startSession() {
        val engine = tts ?: return
        if (sentences.isEmpty()) return
        if (position >= sentences.size) position = 0
        session = Session(engine, sentences, position).also { it.start() }
        refreshButtons()
    }

    private fun refreshButtons() {
        val ready = tts != null && sentences.isNotEmpty()
        val current = session
        playButton.isEnabled = ready
        playButton.text = if (current != null && !current.paused) "Pause" else "Play"
        stopButton.isEnabled = current != null
    }

    private fun showStatus() {
        status.text = when {
            sentences.isEmpty() && tts == null -> "Loading voice…"
            sentences.isEmpty() -> "Voice ready. Open a PDF to start."
            tts == null -> "$fileName · loading voice…"
            else -> "$fileName · sentence ${position + 1} of ${sentences.size}"
        }
    }

    // ------------------------------------------------------------- playback

    private class Chunk(val index: Int, val samples: FloatArray)

    /**
     * One run of playback, starting at sentence [first].
     * The synth thread stays a couple of sentences ahead of the player thread,
     * so there is no silence between sentences while the next one is generated.
     */
    private inner class Session(
        private val engine: OfflineTts,
        private val list: List<String>,
        private val first: Int,
    ) {
        @Volatile var paused = false
        @Volatile private var cancelled = false

        private val end = Chunk(-1, FloatArray(0))
        private val queue = ArrayBlockingQueue<Chunk>(2)
        private val synth = Thread({ synthLoop() }, "synth")
        private val player = Thread({ playLoop() }, "player")

        fun start() {
            synth.start()
            player.start()
        }

        fun cancel() {
            cancelled = true
            synth.interrupt()
            player.interrupt()
        }

        private fun synthLoop() {
            try {
                for (i in first until list.size) {
                    // Only one sentence is generated at a time, even across sessions.
                    val samples = synchronized(ttsLock) {
                        if (cancelled) null else engine.generate(list[i], 0, speed).samples
                    } ?: return
                    queue.put(Chunk(i, samples))
                }
                queue.put(end)
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                if (!cancelled) runOnUiThread { status.text = "Speech error: ${t.message}" }
            }
        }

        private fun playLoop() {
            val rate = engine.sampleRate()
            val track = newTrack(rate)
            val step = rate / 10          // write 0.1 s at a time so pause/stop feel instant
            var finished = false
            try {
                track.play()
                while (!cancelled) {
                    val chunk = queue.take()
                    if (chunk === end) {
                        track.stop()      // lets the last buffered audio play out
                        Thread.sleep(500)
                        finished = true
                        break
                    }
                    runOnUiThread {
                        if (session === this) {
                            position = chunk.index
                            sentenceView.text = list[chunk.index]
                            showStatus()
                        }
                    }
                    var offset = 0
                    while (offset < chunk.samples.size && !cancelled) {
                        if (paused) {
                            track.pause()
                            while (paused && !cancelled) Thread.sleep(50)
                            if (cancelled) break
                            track.play()
                        }
                        val count = minOf(step, chunk.samples.size - offset)
                        val written = track.write(chunk.samples, offset, count, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) break
                        offset += written
                    }
                }
            } catch (_: InterruptedException) {
            } catch (_: IllegalStateException) {
            } finally {
                try {
                    track.release()
                } catch (_: Exception) {
                }
                if (finished) runOnUiThread {
                    if (session === this) {
                        session = null
                        position = 0
                        status.text = "$fileName · finished"
                        refreshButtons()
                    }
                }
            }
        }

        private fun newTrack(rate: Int): AudioTrack {
            val minBuffer = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }

    private companion object {
        const val PICK_PDF = 1
        const val MODEL_FILE = "en_US-lessac-medium.onnx"
        const val MAX_CHARS = 300
    }
}
