package com.reach.app.grounding

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.File

/**
 * Image embedding of a detection's crop, for matching registered objects (MediaPipe ImageEmbedder, MobileNet-V3
 * small, ~4 MB, L2-normalised). Called only on a request, a registration or an identity check, never per frame, and
 * only from the detector thread (the embedder is not thread-safe). Requires assets/mobilenet_v3_small.tflite
 * (scripts/download_models.sh); without it [create] returns null and registration says so.
 * shortcut: embeds the 96x128 crop the grounder already makes; a larger crop may separate instances better (check with IdentityEval).
 */
class CropEmbedder private constructor(private val embedder: ImageEmbedder) {
    fun embed(crop: Bitmap): FloatArray =
        embedder.embed(BitmapImageBuilder(crop).build()).embeddingResult().embeddings()[0].floatEmbedding()

    fun close() = embedder.close()

    companion object {
        const val MODEL = "mobilenet_v3_small.tflite"

        /** Null when the model asset is missing or fails to load. */
        fun create(context: Context): CropEmbedder? = try {
            val base = BaseOptions.builder().setModelAssetPath(MODEL).build()
            CropEmbedder(ImageEmbedder.createFromOptions(context, ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(base).setRunningMode(RunningMode.IMAGE).setL2Normalize(true).build()))
        } catch (e: Exception) {
            Log.w("REACH", "image embedder unavailable: ${e.message}"); null
        }
    }
}

/** The registered objects on this phone only: filesDir/objects/objects.tsv (names + embeddings) and the reference crops. */
class ObjectStore(private val dir: File) {
    private val index = File(dir, "objects.tsv")

    fun load(): ObjectMemory = try { ObjectMemory.parse(index.readText()) } catch (e: Exception) { ObjectMemory() }

    /** Saves the index, and [photo] as the newest reference image of [name] when given. */
    fun save(memory: ObjectMemory, name: String? = null, photo: Bitmap? = null) {
        dir.mkdirs()
        val tmp = File(dir, "objects.tsv.tmp")
        tmp.writeText(memory.serialize()); tmp.renameTo(index) // never a half-written index
        if (name != null && photo != null) File(dir, "${fileName(name)}_${System.currentTimeMillis()}.jpg").outputStream().use {
            photo.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }

    fun deletePhotos(name: String) = dir.listFiles { f -> f.name.startsWith(fileName(name) + "_") }?.forEach { it.delete() }

    private fun fileName(name: String) = name.replace(' ', '_')
}
