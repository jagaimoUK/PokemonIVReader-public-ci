package com.example.pokemonivreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.ByteArrayOutputStream
import java.io.File

/** CPU inference on the capture worker. All reference data stays in noBackupFilesDir. */
internal object VisualFormMemory {
    data class Capture(val vector:FloatArray?, val jpeg:ByteArray?, val message:String)
    var onReferencesChanged: ((Context)->Unit)? = null
    private var engine:ImageEmbedder? = null
    private var memory:VisualMemoryStore? = null
    private var rootPath:String? = null
    @Synchronized fun store(context:Context): VisualMemoryStore {
        val root=File(context.noBackupFilesDir,"visual-memory-v1")
        if(memory==null || rootPath!=root.absolutePath) {
            memory=VisualMemoryStore(root) { onReferencesChanged?.invoke(context.applicationContext) };rootPath=root.absolutePath
        }
        return checkNotNull(memory)
    }

    /** Caller keeps ownership of crop. Grey letterboxing matches the stored model version. */
    @Synchronized fun extract(context:Context, crop:Bitmap): Capture {
        val image=Bitmap.createBitmap(224,224,Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(0xff808080.toInt())
            val scale=minOf(224.0/crop.width,224.0/crop.height)
            val w=(crop.width*scale).toInt().coerceAtLeast(1)
            val h=(crop.height*scale).toInt().coerceAtLeast(1)
            Canvas(image).drawBitmap(crop,null,Rect((224-w)/2,(224-h)/2,(224-w)/2+w,(224-h)/2+h),Paint(Paint.FILTER_BITMAP_FLAG))
            return capturePrepared(image) { input ->
                val extractor=engine ?: ImageEmbedder.createFromOptions(context,
                    ImageEmbedder.ImageEmbedderOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("mobilenet_v3_small.tflite").build())
                        .setL2Normalize(true).build()).also { engine=it }
                extractor.embed(input).embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
            }
        } catch (_:Exception) { return Capture(null,null,"画像比較を開始できません。文字認識の結果を使用します") }
        catch (_:LinkageError) { return Capture(null,null,"この端末では画像比較を開始できません") }
        finally { if(!image.isRecycled) image.recycle() }
    }

    /** MPImage.close() recycles its Bitmap. Finish JPEG encoding BEFORE closing it.
     * The embedding callback is injected so the real Bitmap/MPImage lifecycle can be tested
     * without requiring Android-only model native libraries on the JVM test runner.
     */
    internal fun capturePrepared(image:Bitmap, embed:(MPImage)->FloatArray?):Capture {
        val input=BitmapImageBuilder(image).build()
        return try {
            val vector=embed(input)?.let(VisualMemoryIndex::normalized)
                ?: return Capture(null,null,"画像の特徴を取得できません")
            val jpeg=ByteArrayOutputStream().use { output ->
                check(image.compress(Bitmap.CompressFormat.JPEG,85,output));output.toByteArray()
            }
            Capture(vector,jpeg,"")
        } finally { input.close() }
    }

    /** A manually taught typo only opens image candidates; it never supplies a prediction. */
    fun candidates(context:Context,catalog:PokemonCatalog,name:String):List<PokemonCatalog.Species> {
        val exact=catalog.visualCandidates(name)
        if(exact.isNotEmpty())return exact
        val key=VisualMemoryIndex.ocrKey(name)
        if(key.isEmpty())return emptyList()
        return store(context).read().samples.filter { it.origin=="manual" && it.ocrKey==key }
            .flatMap { catalog.comparisonCandidates(it.speciesId) }.distinctBy { it.id }
    }
    fun match(context:Context,name:String,vector:FloatArray,candidates:List<PokemonCatalog.Species>):VisualMemoryIndex.Match {
        val samples=store(context).read().samples
        if(candidates.size==1) {
            val id=candidates.single().id
            val key=VisualMemoryIndex.ocrKey(name)
            val score=samples.filter { it.origin=="manual" && it.ocrKey==key && key.isNotEmpty() && it.speciesId==id }
                .maxOfOrNull { VisualMemoryIndex.cosine(vector,it.vector) } ?: -1.0
            if(score>=0.98)return VisualMemoryIndex.Match(id,score,0.0,"手動訂正した同じ誤読と見本画像から判定")
        }
        return VisualMemoryIndex.match(vector,candidates.map{it.id}.toSet(),samples)
    }
    fun accepts(species:PokemonCatalog.Species, types:Set<String>, complete:Boolean, count:Int?,
                gender:GenderRecognizer.Gender):Boolean =
        (types.isEmpty() || if(complete) species.types==types else species.types.containsAll(types)) &&
        (count==null || count==species.types.size) &&
        (gender==GenderRecognizer.Gender.UNKNOWN || species.gender==GenderRecognizer.Gender.UNKNOWN || species.gender==gender)
}
