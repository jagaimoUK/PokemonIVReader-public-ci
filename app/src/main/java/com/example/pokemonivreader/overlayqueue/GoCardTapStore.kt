package com.example.pokemonivreader.overlayqueue

import android.content.Context

/** A bounded local correction inside a freshly detected card; never a remembered screen tap. */
internal class GoCardTapStore(context: Context) {
    // Reuse the existing backup-covered store; distinct keys leave button exemplars intact.
    private val prefs = context.getSharedPreferences("go_button_templates_v1", Context.MODE_PRIVATE)
    private data class Pending(val box:GoWorkflowPolicy.Box,val key:String?,val x:Float,val y:Float)
    private var pending:Pending?=null
    fun point(card: GoWorkflowPolicy.Card, choice: GoWorkflowPolicy.Choice?, scene:GoWorkflowPolicy.Scene?=null): GoWorkflowPolicy.Box {
        val k=scene?.let(::key)
        val x = choice?.x ?: k?.takeIf{prefs.getInt(it,0)>=3}?.let{prefs.getFloat("${it}_x",.5f)} ?: prefs.getFloat("card_tap_x_v1", .5f)
        val y = choice?.y ?: k?.takeIf{prefs.getInt(it,0)>=3}?.let{prefs.getFloat("${it}_y",.5f)} ?: prefs.getFloat("card_tap_y_v1", .5f)
        pending=Pending(card.box,k,safe(x),safe(y))
        val px = card.box.left + ((card.box.right - card.box.left) * safe(x)).toInt()
        val py = card.box.top + ((card.box.bottom - card.box.top) * safe(y)).toInt()
        return GoWorkflowPolicy.Box(px, py, px + 1, py + 1)
    }
    // Called only AFTER matching detail CP has been observed twice. No images or identity stored.
    fun learn(choice: GoWorkflowPolicy.Choice) {
        if (!choice.x.isFinite() || !choice.y.isFinite()) return
        prefs.edit().putFloat("card_tap_x_v1", safe(choice.x)).putFloat("card_tap_y_v1", safe(choice.y)).apply()
    }
    private fun key(scene: GoWorkflowPolicy.Scene): String? {
        val card=scene.cards.singleOrNull() ?: return null
        if(scene.count!=1 || scene.width<=0 || scene.height<=0) return null
        val bw=(card.box.right-card.box.left).coerceAtLeast(1)
        val bh=(card.box.bottom-card.box.top).coerceAtLeast(1)
        return "auto_grid_v2_${scene.width}_${scene.height}_${scene.keyboard}_" +
            "${card.anchor.left/maxOf(1,scene.width/10)}_${card.anchor.top/maxOf(1,scene.height/20)}_${bw/maxOf(1,scene.width/20)}_${bh/maxOf(1,scene.height/20)}"
    }
    fun fast(scene: GoWorkflowPolicy.Scene): Boolean = key(scene)?.let { prefs.getInt(it,0)>=3 } == true
    fun verified(scene: GoWorkflowPolicy.Scene) {
        val k=key(scene) ?: return
        val editor=prefs.edit()
        val keys=prefs.getString("auto_grid_keys_v2", "").orEmpty().split('|').filter{it.isNotEmpty() && it!=k}.toMutableList()
        keys.add(k)
        while(keys.size>32){val old=keys.removeAt(0);editor.remove(old).remove("${old}_x").remove("${old}_y")}
        editor.putString("auto_grid_keys_v2",keys.joinToString("|"))
            .putInt(k,(prefs.getInt(k,0)+1).coerceAtMost(20))
        pending?.takeIf{it.key==k && it.box==scene.cards.single().box}?.let {
            editor.putFloat("${k}_x",it.x).putFloat("${k}_y",it.y)
        }
        pending=null
        editor.apply()
    }
    fun failed(scene: GoWorkflowPolicy.Scene) {
        pending=null
        key(scene)?.let { prefs.edit().remove(it).remove("${it}_x").remove("${it}_y").apply() }
    }

    private fun safe(v: Float) = if (v.isFinite()) v.coerceIn(.15f, .85f) else .5f
}
