package com.example.pokemonivreader.overlayqueue

/** Check only the right-hand check area of the exact OCR row; never the colored tag dot. */
internal object GoTagRecognition {
    fun checked(width: Int,height: Int,pixels: IntArray,row: GoWorkflowPolicy.Box): Boolean? {
        if (width<=0 || height<=0 || pixels.size!=width*height) return null
        val left=(width*.82f).toInt();val right=(width*.94f).toInt()
        val top=(row.cy-(row.bottom-row.top)).coerceAtLeast(0)
        val bottom=(row.cy+(row.bottom-row.top)).coerceAtMost(height)
        var green=0;var white=0;var total=0
        var minX=width;var maxX=0;var minY=height;var maxY=0
        for(y in top until bottom)for(x in left until right) {
            val c=pixels[y*width+x];val r=c shr 16 and 255;val g=c shr 8 and 255;val b=c and 255
            total++
            if(r>235 && g>235 && b>235)white++
            if(g>145 && g-r>25 && g-b>8) { green++;minX=minOf(minX,x);maxX=maxOf(maxX,x);minY=minOf(minY,y);maxY=maxOf(maxY,y) }
        }
        if(total==0)return null
        if(green>total*.012 && maxX-minX>width*.025 && maxY-minY>width*.012)return true
        return if(white>total*.97) false else null
    }
    private val pickerHeader = Regex("tag [0-9]+ pok[eé]mon|[0-9]+匹.*タグ.*|タグ.*[0-9]+匹.*")
    private val bulkLabel = Regex("tag\\s*\\(([0-9]+)\\)")
    private val countLabel = Regex("\\(\\s*([0-9]+)\\s*\\)")
    private val bulkTransfer = Regex("transfer\\s*\\([0-9]+\\)")
    private val menuLabels = setOf("appraise", "transfer", "unfavorite", "favorite", "博士に送る",
        "個体値を調べる", "ポケモンを調べてもらう", "お気に入りを解除")

    fun row(scene: GoWorkflowPolicy.Scene, target: GoLearningTarget,
        learned: (GoLearningTarget) -> GoWorkflowPolicy.Box? = { null }): GoWorkflowPolicy.Box? =
        if (scene.tagList && !scene.keyboard && !scene.rename && !scene.searchSuggestions)
            label(scene, target, learned) else null

    private fun label(scene: GoWorkflowPolicy.Scene, target: GoLearningTarget,
        learned: (GoLearningTarget) -> GoWorkflowPolicy.Box?): GoWorkflowPolicy.Box? {
        val exact = scene.lines.filter { target.accepts(it.text) }.map { it.box }.distinct()
        if (exact.isNotEmpty()) return exact.singleOrNull()
        val match = learned(target) ?: return null
        // Never substitute a learned match over another readable control.
        return match.takeIf { GoWorkflowPolicy.exposed(it, scene.lines.map { line -> line.box }) }
    }

    private fun bulk(scene: GoWorkflowPolicy.Scene, selectAll: GoWorkflowPolicy.Box?,
        learned: (GoLearningTarget) -> GoWorkflowPolicy.Box?): Pair<GoWorkflowPolicy.Box, Int>? {
        val exact = scene.lines.filter { it.box.top > scene.height * .7f }.mapNotNull { line ->
            bulkLabel.matchEntire(GoWorkflowPolicy.normalized(line.text))?.groupValues?.get(1)?.toIntOrNull()
                ?.let { line.box to it }
        }.distinct()
        if (exact.isNotEmpty()) return exact.singleOrNull()
        if (!scene.list || scene.keyboard || scene.rename || scene.searchSuggestions || scene.tagList) return null
        val selectionMode = selectAll != null || scene.lines.any {
            it.box.top > scene.height * .7f && bulkTransfer.matches(GoWorkflowPolicy.normalized(it.text))
        }
        if (!selectionMode) return null
        val match = learned(GoLearningTarget.BULK_TAG) ?: return null
        val counts = scene.lines.filter { line ->
            val b = line.box
            b.cx > match.cx && b.left >= match.right - (match.right - match.left) / 3 &&
                b.left - match.right < (match.right - match.left) &&
                b.cy in match.top until match.bottom
        }.mapNotNull { line ->
            countLabel.matchEntire(GoWorkflowPolicy.normalized(line.text))?.groupValues?.get(1)?.toIntOrNull()
        }.distinct()
        // The learned image locates TAG. Only OCR can prove the current selection count.
        val otherLabels = scene.lines.filterNot {
            GoLearningTarget.BULK_TAG.accepts(it.text) || countLabel.matches(GoWorkflowPolicy.normalized(it.text))
        }.map { it.box }
        if (!GoWorkflowPolicy.exposed(match, otherLabels)) return null
        return counts.singleOrNull()?.let { match to it }
    }

    fun selectAll(scene: GoWorkflowPolicy.Scene): GoWorkflowPolicy.Box? {
        val exact = scene.lines.filter {
            GoLearningTarget.SELECT_ALL.accepts(it.text)
        }.map { it.box }.distinct()
        if (exact.isNotEmpty()) return exact.singleOrNull()

        // A toolbar crop sometimes returns the words as separate OCR lines.
        val words = scene.lines.filter { it.box.cx > scene.width * .60f && it.box.cy < scene.height * .14f }
        val selects = words.filter { GoWorkflowPolicy.normalized(it.text) == "select" }.map { it.box }.distinct()
        val alls = words.filter { GoWorkflowPolicy.normalized(it.text) == "all" }.map { it.box }.distinct()
        val select = selects.singleOrNull() ?: return null
        val all = alls.singleOrNull() ?: return null
        val height = maxOf(select.bottom - select.top, all.bottom - all.top)
        if (all.left - select.right !in 0..height || kotlin.math.abs(select.cy - all.cy) > height / 2) return null
        return GoWorkflowPolicy.Box(select.left, minOf(select.top, all.top), all.right, maxOf(select.bottom, all.bottom))
    }

    /** subprev is OCR-only: a learned prev control must never be reused for another tag. */
    fun namedTagRow(scene: GoWorkflowPolicy.Scene, name: String): GoWorkflowPolicy.Box? =
        if (!scene.tagList || scene.keyboard) null else scene.lines
            .filter { GoWorkflowPolicy.normalized(it.text) == name }.map { it.box }
            .distinct().singleOrNull()
    fun annotate(scene: GoWorkflowPolicy.Scene, selected: Boolean?,
        learned: (GoLearningTarget) -> GoWorkflowPolicy.Box? = { null },
        tagName: String = SelectionTagPolicy.PREV): GoWorkflowPolicy.Scene {
        val pickerHeaders = scene.lines.map { GoWorkflowPolicy.normalized(it.text) }.filter(pickerHeader::matches)
        val tagList = pickerHeaders.isNotEmpty()
        val pickerCount = pickerHeaders.mapNotNull { Regex("[0-9]+").find(it)?.value?.toIntOrNull() }
            .distinct().singleOrNull()
        val menu = !tagList && !scene.list && !scene.rename && !scene.keyboard && !scene.searchSuggestions &&
            scene.lines.any { GoWorkflowPolicy.normalized(it.text) in menuLabels }
        val selectAll = if (tagList || scene.detail || scene.rename || scene.searchSuggestions) null else selectAll(scene)
        val withPicker = scene.copy(tagList = tagList, tagPickerCount = pickerCount)
        val bulk = bulk(withPicker, selectAll, learned)
        return withPicker.copy(
            selectAll = selectAll, bulkTag = bulk?.first, selectedCount = bulk?.second,
            tag = if (menu) label(scene, GoLearningTarget.TAG_MENU, learned) else null,
            prev = if (tagName == SelectionTagPolicy.PREV) row(withPicker, GoLearningTarget.PREV, learned)
                else namedTagRow(withPicker, tagName),
            prevSelected = if (tagList) selected else null,
            tagDone = row(withPicker, GoLearningTarget.TAG_DONE, learned),
        )
    }
}
