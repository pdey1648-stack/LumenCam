package com.example.data.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import com.example.domain.model.BokehShape
import com.example.domain.model.CapturedMediaItem
import com.example.domain.model.PortraitSidecarInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStoreRepository(private val context: Context) {

    private val relativeDirectory = "${Environment.DIRECTORY_PICTURES}/LumenCam"
    private val fileNameFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun generateCaptureBaseName(timestampMs: Long = System.currentTimeMillis()): String {
        return "LUMEN_${fileNameFormatter.format(Date(timestampMs))}"
    }

    fun createJpegOutputOptions(
        baseName: String,
        isFrontCamera: Boolean,
    ): ImageCapture.OutputFileOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
        }
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = isFrontCamera
        }
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        )
            .setMetadata(metadata)
            .build()
    }

    fun createRawOutputOptions(baseName: String): ImageCapture.OutputFileOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.dng")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
        }
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        ).build()
    }

    suspend fun saveBitmapToMediaStore(
        bitmap: Bitmap,
        displayName: String,
        mimeType: String,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return@withContext null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(format, quality, out)
            }
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun savePortraitSidecarMetadata(sidecar: PortraitSidecarInfo) {
        val dir = File(context.filesDir, "portrait_sidecars").apply { mkdirs() }
        val metaFile = File(dir, "${sidecar.baseName}.json")
        val json = JSONObject().apply {
            put("baseName", sidecar.baseName)
            put("originalPath", sidecar.originalPath)
            put("matteDepthPath", sidecar.matteDepthPath)
            put("apertureFStop", sidecar.apertureFStop.toDouble())
            put("focusX", sidecar.focusX.toDouble())
            put("focusY", sidecar.focusY.toDouble())
            put("bokehShape", sidecar.bokehShape.name)
            put("zoomRatio", sidecar.zoomRatio.toDouble())
            put("lutPreset", sidecar.lutPreset.name)
        }
        metaFile.writeText(json.toString())
    }

    private fun loadPortraitSidecar(baseName: String): PortraitSidecarInfo? {
        val metaFile = File(File(context.filesDir, "portrait_sidecars"), "$baseName.json")
        if (!metaFile.exists()) return null
        return runCatching {
            val json = JSONObject(metaFile.readText())
            val origPath = json.getString("originalPath")
            val depthPath = json.getString("matteDepthPath")
            if (!File(origPath).exists() || !File(depthPath).exists()) return null
            PortraitSidecarInfo(
                baseName = json.getString("baseName"),
                originalPath = origPath,
                matteDepthPath = depthPath,
                apertureFStop = json.getDouble("apertureFStop").toFloat(),
                focusX = json.getDouble("focusX").toFloat(),
                focusY = json.getDouble("focusY").toFloat(),
                bokehShape = runCatching {
                    BokehShape.valueOf(json.getString("bokehShape"))
                }.getOrDefault(BokehShape.HEXAGON),
                zoomRatio = json.optDouble("zoomRatio", 2.0).toFloat(),
                lutPreset = runCatching {
                    com.example.domain.model.LutPreset.valueOf(json.optString("lutPreset", "NATURAL"))
                }.getOrDefault(com.example.domain.model.LutPreset.NATURAL),
            )
        }.getOrNull()
    }

    suspend fun queryCapturedPhotos(limit: Int = 60): List<CapturedMediaItem> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
            )
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%LumenCam%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val jpegItems = mutableListOf<CapturedMediaItem>()
            val dngBaseNames = mutableSetOf<String>()

            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol).orEmpty()
                    val mime = cursor.getString(mimeCol).orEmpty()
                    if (mime.equals("image/x-adobe-dng", ignoreCase = true) ||
                        name.endsWith(".dng", ignoreCase = true)
                    ) {
                        dngBaseNames += name.substringBeforeLast('.')
                        continue
                    }
                    // Hide internal companion _ORIG / _DEPTH files from main contact sheet
                    // (they remain saved separately in Pictures/LumenCam & linked via sidecar)
                    val base = name.substringBeforeLast('.')
                    if (base.endsWith("_ORIG") || base.endsWith("_DEPTH")) {
                        continue
                    }
                    if (jpegItems.size < limit) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id,
                        )
                        jpegItems += CapturedMediaItem(
                            id = id,
                            uri = uri,
                            displayName = name,
                            timestampMs = cursor.getLong(dateCol) * 1000L,
                            width = cursor.getInt(widthCol),
                            height = cursor.getInt(heightCol),
                            sizeBytes = cursor.getLong(sizeCol),
                            portraitSidecar = loadPortraitSidecar(base),
                        )
                    }
                }
            }

            jpegItems.map { item ->
                val base = item.displayName.substringBeforeLast('.')
                item.copy(hasRawCompanion = base in dngBaseNames)
            }
        }
}
