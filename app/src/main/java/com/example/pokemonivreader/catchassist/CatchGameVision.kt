package com.example.pokemonivreader.catchassist

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier

/** Called on the catch worker. The caller keeps ownership of the original bitmap. */
internal class CatchGameVision(private val context: Context) : AutoCloseable {
    data class Box(val x: Float, val y: Float, val width: Float, val height: Float, val score: Float)

    private var mapModel: ObjectDetector? = null
    private var screenModel: ImageClassifier? = null

    fun isMap(frame: Bitmap): Boolean {
        val model = screenModel ?: ImageClassifier.createFromOptions(context,
            ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("model_classifier_screen_v5.tflite").build())
                .setMaxResults(1)
                .build()).also { screenModel = it }
        val image = Bitmap.createScaledBitmap(frame, 256, 256, true)
        val owned = if (image === frame) checkNotNull(frame.copy(Bitmap.Config.ARGB_8888, false)) else image
        val input = try { BitmapImageBuilder(owned).build() } catch (e: Exception) { owned.recycle(); throw e }
        try {
            val category = model.classify(input).classificationResult().classifications()
                .flatMap { it.categories() }.maxByOrNull { it.score() }
            return category != null && category.categoryName() == "mapScreen" && category.score() >= .90f
        } finally {
            input.close()
        }
    }
    private var encounterModel: ObjectDetector? = null

    fun mapObjects(frame: Bitmap): CatchMapObjects {
        val detector = mapModel ?: detector("model_detector_map_v2.tflite", MAP_LABELS, .45f, 36)
            .also { mapModel = it }
        val objects = detect(detector, frame)
        return CatchMapObjects(
            objects.filter { it.first == "pokemon" && it.second.score >= .72f }.map { it.second },
            objects.filter { it.first != "pokemon" }.map { it.second },
        )
    }

    fun encounter(frame: Bitmap): Box? {
        val detector = encounterModel ?: detector("model_detector_encounter.tflite", listOf("boundingBox"), .45f, 12)
            .also { encounterModel = it }
        return detect(detector, frame).map { it.second }.firstOrNull {
            it.x in 0.16f..0.84f && it.y in 0.32f..0.73f && it.height in 0.045f..0.55f
        } ?: silhouette(frame)
    }

    private fun silhouette(frame: Bitmap): Box? {
        val width = 108
        val height = (frame.height.toFloat() * width / frame.width).toInt().coerceIn(108, 320)
        val small = Bitmap.createScaledBitmap(frame, width, height, true)
        try {
            val colors = IntArray(width * height)
            small.getPixels(colors, 0, width, 0, 0, width, height)
            return CatchSilhouette.estimate(width, height, colors)
        } finally {
            if (small !== frame) small.recycle()
        }
    }

    private fun detector(asset: String, labels: List<String>, threshold: Float, limit: Int): ObjectDetector =
        ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(asset).build())
            .setCategoryAllowlist(labels)
            .setScoreThreshold(threshold)
            .setMaxResults(limit)
            .build())

    private fun detect(detector: ObjectDetector, frame: Bitmap): List<Pair<String, Box>> {
        val ratio = minOf(1f, 720f / maxOf(frame.width, frame.height))
        val image = Bitmap.createScaledBitmap(frame, (frame.width * ratio).toInt(), (frame.height * ratio).toInt(), true)
        // MediaPipe takes ownership of its input. Copy if scaling returned the original.
        val owned = if (image === frame) checkNotNull(frame.copy(Bitmap.Config.ARGB_8888, false)) else image
        val input = try { BitmapImageBuilder(owned).build() } catch (e: Exception) { owned.recycle(); throw e }
        try {
            return detector.detect(input).detections().mapNotNull { item ->
                val category = item.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val bounds = item.boundingBox()
                category.categoryName() to Box(bounds.centerX() / owned.width, bounds.centerY() / owned.height,
                    bounds.width() / owned.width, bounds.height() / owned.height, category.score())
            }.sortedByDescending { it.second.score }
        } finally {
            input.close()
        }
    }

    private companion object {
        val MAP_LABELS = listOf("pokemon", "gym", "pokestop", "rocket_pokestop", "unable_pokestop")
    }

    override fun close() {
        screenModel?.close()
        screenModel = null
        mapModel?.close()
        encounterModel?.close()
        mapModel = null
        encounterModel = null
    }
}
