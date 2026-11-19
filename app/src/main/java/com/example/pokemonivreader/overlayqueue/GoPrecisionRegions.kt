package com.example.pokemonivreader.overlayqueue

/** Missing evidence only. Original-resolution crops, capped to two and 40% of visible pixels. */
internal object GoPrecisionRegions {
    fun plan(s: GoWorkflowPolicy.Scene, stage: GoIndividualWorkflow.Stage?, visibleHeight: Int, expectedCp:Int?=null): List<GoWorkflowPolicy.Box> {
        val w=s.width; val h=s.height
        if(s.searchSuggestions || w<=840 || h<=0 || visibleHeight<=0) return emptyList()
        fun box(l:Float,t:Float,r:Float,b:Float)=GoWorkflowPolicy.Box((w*l).toInt(),(h*t).toInt(),(w*r).toInt(),minOf((h*b).toInt(),visibleHeight))
        val suspectEmpty = s.count == 0 && (s.keyboard || s.cards.isNotEmpty() || s.visualCount > 0)
        val header = if ((s.count == null || suspectEmpty) && (s.list || stage == GoIndividualWorkflow.Stage.RESULTS))
            listOf(box(.18f,.02f,.88f,.20f)) else emptyList()
        val candidates=header + if(s.list) s.cards.filter{it.cp==null || (s.count==1 && expectedCp!=null && it.cp!=expectedCp) || it.label.isBlank()}.take(2).map {
            GoWorkflowPolicy.Box(it.box.left.coerceAtLeast(0),it.box.top.coerceAtLeast(0),it.box.right.coerceAtMost(w),minOf(it.box.bottom,visibleHeight))
        } else when(stage) {
            GoIndividualWorkflow.Stage.DETAIL -> if(s.detail && (s.cp==null || (expectedCp!=null && s.cp!=expectedCp)))listOf(box(.15f,.04f,.85f,.22f)) else emptyList()
            GoIndividualWorkflow.Stage.NAME_CONFIRM, GoIndividualWorkflow.Stage.SAVE, GoIndividualWorkflow.Stage.TAG_RETURN ->
                if(s.rename) renameRegions(s, visibleHeight) else if(s.detail)listOf(box(.08f,.29f,.92f,.55f))else emptyList()
            GoIndividualWorkflow.Stage.TAG_MENU -> if(s.tag==null)listOf(box(.5f,.18f,1f,.85f))else emptyList()
            GoIndividualWorkflow.Stage.TAG_PICK, GoIndividualWorkflow.Stage.TAG_VERIFY ->
                if(s.prev==null)listOf(box(.08f,.15f,.63f,.85f))else if(s.tagDone==null)listOf(box(.2f,.78f,.8f,.95f))else emptyList()
            else -> emptyList()
        }
        var budget=w.toLong()*minOf(h,visibleHeight)*4/10
        return candidates.filter { b ->
            val area=(b.right-b.left).toLong()*(b.bottom-b.top)
            if(b.right<=b.left || b.bottom<=b.top || area>budget)false else {budget-=area;true}
        }.take(2)
    }
    /** Name/title and the lower game OK, within the existing two-crop/40% budget.
     * Also used for a contrast/upscale pass when native OCR still misses OK. */
    fun renameRegions(s: GoWorkflowPolicy.Scene, visibleHeight: Int): List<GoWorkflowPolicy.Box> {
        if (s.width <= 0 || s.height <= 0 || visibleHeight <= 0 || !s.rename) return emptyList()
        fun box(l: Float, t: Float, r: Float, b: Float) = GoWorkflowPolicy.Box(
            (s.width*l).toInt(), (s.height*t).toInt(), (s.width*r).toInt(), minOf((s.height*b).toInt(), visibleHeight))
        var budget = s.width.toLong()*minOf(s.height,visibleHeight)*4/10
        // Put the missing OK first so a short visible viewport cannot spend all
        // its budget on the already readable name.
        return listOf(box(.20f,.55f,.80f,.85f), box(.15f,.25f,.85f,.55f)).filter {
            val area = (it.right-it.left).toLong()*(it.bottom-it.top)
            if (it.right <= it.left || it.bottom <= it.top || area > budget) false
            else { budget -= area; true }
        }
    }
    fun merge(old:List<GoWorkflowPolicy.Line>, refined:List<GoWorkflowPolicy.Line>, region:GoWorkflowPolicy.Box):List<GoWorkflowPolicy.Line> {
        // No text in the crop is no proof that a previously read label disappeared.
        if(refined.isEmpty())return old
        return old.filterNot { it.box.cx in region.left until region.right && it.box.cy in region.top until region.bottom }+refined
    }
}


