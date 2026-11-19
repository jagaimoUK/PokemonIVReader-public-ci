package com.example.pokemonivreader.catchassist

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class CatchMarker(val label: String, val ball: CatchBall? = null) {
    MAP("マップ画面のモンスターボール型メニュー"),
    ENCOUNTER("遭遇画面の「逃げる」アイコン"),
    BAG("遭遇画面の右下にあるボール選択ボタン"),
    BAG_OPEN("ボール一覧に表示される見出し"),
    BERRY("遭遇画面の左下にあるきのみボタン"),
    BERRY_TRAY("きのみ一覧に表示される見出し"),
    POKE_OPTION("ボール一覧のモンスターボール", CatchBall.POKE),
    GREAT_OPTION("ボール一覧のスーパーボール", CatchBall.GREAT),
    ULTRA_OPTION("ボール一覧のハイパーボール", CatchBall.ULTRA),
    POKE_READY("遭遇画面で手前にあるモンスターボール", CatchBall.POKE),
    GREAT_READY("遭遇画面で手前にあるスーパーボール", CatchBall.GREAT),
    ULTRA_READY("遭遇画面で手前にあるハイパーボール", CatchBall.ULTRA),
    RESULT_OK("捕獲報酬の画面にある OK ボタン"),
    DETAIL_EXIT("捕獲直後の個体詳細にある閉じるボタン");

    fun validPoint(x: Float, y: Float): Boolean = when (this) {
        BERRY -> x in 0.04f..0.33f && y in 0.60f..0.96f
        BERRY_TRAY -> y in 0.60f..0.96f
        else -> true
    }

    companion object {
        fun option(ball: CatchBall) = entries.first { it.ball == ball && it.name.endsWith("_OPTION") }
        fun ready(ball: CatchBall) = entries.first { it.ball == ball && it.name.endsWith("_READY") }
    }
}

/** A small local color reference at a user-confirmed point on the same game viewport. */
internal data class CatchAnchor(
    val x: Float,
    val y: Float,
    val aspect: Float,
    val colors: IntArray,
) {
    init {
        require(x in 0f..1f && y in 0f..1f && aspect in .3f..1f && colors.size == GRID * GRID)
    }

    fun point(width: Int, height: Int) = PointF(x * width, y * height)

    fun matches(image: Bitmap, whiteIcon: Boolean = false): Boolean {
        if (abs(image.width.toFloat() / image.height - aspect) > .013f) return false
        val current = sample(image, x, y) ?: return false
        var error = 0
        for (i in colors.indices) {
            val a = colors[i]
            val b = current[i]
            error += abs((a shr 16 and 255) - (b shr 16 and 255))
            error += abs((a shr 8 and 255) - (b shr 8 and 255))
            error += abs((a and 255) - (b and 255))
        }
        if (error <= colors.size * 3 * 22) return true
        if (!whiteIcon) return false
        // The flee icon is white, but its sky/background changes between encounters.
        val templateWhite = colors.indices.filter { isWhite(colors[it]) }.toSet()
        if (templateWhite.size !in 8..(colors.size - 20)) return false
        val observedWhite = current.indices.filter { isWhite(current[it]) }.toSet()
        val common = templateWhite.intersect(observedWhite).size
        return common * 100 >= (templateWhite.size + observedWhite.size - common) * 76
    }

    companion object {
        private const val GRID = 11

        private fun isWhite(color: Int): Boolean {
            val r = color shr 16 and 255
            val g = color shr 8 and 255
            val b = color and 255
            return minOf(r, g, b) >= 182 && maxOf(r, g, b) - minOf(r, g, b) <= 38
        }

        fun from(image: Bitmap, point: PointF): CatchAnchor? {
            val x = point.x / image.width
            val y = point.y / image.height
            val pixels = sample(image, x, y) ?: return null
            if (pixels.toSet().size < 10) return null
            return CatchAnchor(x, y, image.width.toFloat() / image.height, pixels)
        }

        fun region(width: Int, height: Int, point: PointF): Rect {
            val radius = (width * .022f).roundToInt()
            return Rect(
                point.x.roundToInt() - radius, point.y.roundToInt() - radius,
                point.x.roundToInt() + radius, point.y.roundToInt() + radius,
            ).apply { intersect(0, 0, width, height) }
        }

        private fun sample(image: Bitmap, x: Float, y: Float): IntArray? {
            val box = region(image.width, image.height, PointF(x * image.width, y * image.height))
            if (box.width() < GRID || box.height() < GRID) return null
            return IntArray(GRID * GRID) { index ->
                val column = index % GRID
                val row = index / GRID
                image.getPixel(
                    box.left + ((column + .5f) * box.width() / GRID).toInt(),
                    box.top + ((row + .5f) * box.height() / GRID).toInt(),
                )
            }
        }
    }
}
