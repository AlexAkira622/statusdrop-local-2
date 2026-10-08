package com.akira.statusdrop

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Transformer
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var source: Uri? = null
    private var sourceName = "video"
    private var clipIndex = 0
    private var clipCount = 0
    private var durationMs = 0L
    private var tempInput: File? = null
    private var outDir: File? = null

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            source = uri
            sourceName = displayName(uri)
            status.text = "Selected: $sourceName"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,48,32,32) }
        val title = TextView(this).apply { text = "StatusDrop Local"; textSize = 30f; gravity = Gravity.CENTER; setPadding(0,0,0,24) }
        val sub = TextView(this).apply { text = "1080×1920 • H.264/AAC • 29-second clips\nRuns locally. No upload. No watermark."; textSize = 16f; gravity = Gravity.CENTER; setPadding(0,0,0,24) }
        val choose = Button(this).apply { text = "Choose video"; setOnClickListener { picker.launch("video/*") } }
        val make = Button(this).apply { text = "Create Status clips"; setOnClickListener { startEncoding() } }
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = ProgressBar.GONE }
        status = TextView(this).apply { text = "No video selected"; textSize = 15f; setPadding(0,24,0,24) }
        root.addView(title); root.addView(sub); root.addView(choose); root.addView(make); root.addView(progress); root.addView(status)
        setContentView(root)
    }

    private fun startEncoding() {
        val uri = source ?: run { status.text = "Choose a video first."; return }
        try {
            tempInput = File(cacheDir, "input_${System.currentTimeMillis()}.mp4")
            contentResolver.openInputStream(uri)!!.use { input -> tempInput!!.outputStream().use { output -> input.copyTo(output) } }
            durationMs = MediaMetadataRetriever().run {
                setDataSource(tempInput!!.absolutePath)
                val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                release(); d
            }
            clipCount = ((durationMs + CLIP_MS - 1) / CLIP_MS).toInt().coerceAtLeast(1)
            clipIndex = 0
            outDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "StatusDrop").apply { mkdirs() }
            progress.visibility = ProgressBar.VISIBLE
            encodeNext()
        } catch (e: Exception) {
            status.text = "Could not read video: ${e.message}"
            tempInput?.delete()
        }
    }

    private fun encodeNext() {
        if (clipIndex >= clipCount) {
            progress.visibility = ProgressBar.GONE
            status.text = "Done! $clipCount clip(s) saved in your Gallery (Movies/StatusDrop). Open WhatsApp > Status and pick them."
            tempInput?.delete()
            return
        }
        val start = clipIndex * CLIP_MS
        val end = minOf(durationMs, start + CLIP_MS)
        status.text = "Encoding clip ${clipIndex + 1} of $clipCount…"
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(start)
            .setEndPositionMs(end)
            .build()
        val mediaItem = MediaItem.Builder().setUri(tempInput!!.absolutePath).setClippingConfiguration(clipping).build()
        val presentation = Presentation.createForWidthAndHeight(
            1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), listOf(presentation)))
            .build()
        val output = File(outDir, "${safeBaseName()}_${String.format("%02d", clipIndex + 1)}.mp4")
        if (output.exists()) output.delete()
        val transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveToGallery(output)
                    clipIndex++
                    encodeNext()
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: androidx.media3.transformer.ExportException) {
                    progress.visibility = ProgressBar.GONE
                    status.text = "Clip ${clipIndex + 1} failed: ${exportException.message}"
                    tempInput?.delete()
                }
            }).build()
        transformer.start(edited, output.absolutePath)
    }

    private fun saveToGallery(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StatusDrop")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            status.text = "Saved, but could not add to Gallery: ${e.message}"
        }
    }

    private fun safeBaseName(): String = sourceName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun displayName(uri: Uri): String {
        var name = "video"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        return name
    }

    companion object { private const val CLIP_MS = 29_000L }
}
