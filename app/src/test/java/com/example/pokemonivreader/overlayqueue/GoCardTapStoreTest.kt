package com.example.pokemonivreader.overlayqueue

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoCardTapStoreTest {
    @Test fun learningIsRelativeToFreshlyDetectedCardAndSurvivesReopen() {
        val c = RuntimeEnvironment.getApplication() as Context
        c.getSharedPreferences("go_button_templates_v1",Context.MODE_PRIVATE).edit().clear().commit()
        val store = GoCardTapStore(c)
        val card = GoWorkflowPolicy.Card(131,GoWorkflowPolicy.Box(100,600,300,1000))
        assertEquals(GoWorkflowPolicy.Box(200,800,201,801),store.point(card,null))
        store.learn(GoWorkflowPolicy.Choice(0,.4f,.6f))
        val reopened = GoCardTapStore(c)
        assertEquals(GoWorkflowPolicy.Box(180,840,181,841),reopened.point(card,null))
        val moved = card.copy(box = GoWorkflowPolicy.Box(400,1000,800,1800))
        assertEquals(GoWorkflowPolicy.Box(560,1480,561,1481),reopened.point(moved,null))
    }
    @Test fun successfulLayoutsLearnPersistAndResetOnFailureWithoutStoringIndividuals() {
        val c=RuntimeEnvironment.getApplication() as Context
        c.getSharedPreferences("go_button_templates_v1",0).edit().clear().commit()
        val store=GoCardTapStore(c)
        val scene=GoWorkflowPolicy.Scene(list=true,count=1,cards=listOf(GoWorkflowPolicy.Card(131,
            GoWorkflowPolicy.Box(100,600,300,1000))),width=1220,height=2712)
        repeat(2){store.verified(scene)};assertFalse(store.fast(scene))
        store.verified(scene);assertTrue(GoCardTapStore(c).fast(scene))
        assertTrue(store.fast(scene.copy(cards=scene.cards.map{it.copy(cp=500)})))
        assertFalse(store.fast(scene.copy(keyboard=true)))
        store.failed(scene);assertFalse(store.fast(scene))
    }
    @Test fun invalidCorrectionsCannotEscapeTheRecognizedCard() {
        val c = RuntimeEnvironment.getApplication() as Context
        c.getSharedPreferences("go_button_templates_v1",Context.MODE_PRIVATE).edit().clear().commit()
        val store = GoCardTapStore(c)
        val card = GoWorkflowPolicy.Card(131,GoWorkflowPolicy.Box(0,0,100,100))
        store.learn(GoWorkflowPolicy.Choice(0,Float.NaN,Float.POSITIVE_INFINITY))
        assertEquals(50,store.point(card,null).cx)
        val point = store.point(card,GoWorkflowPolicy.Choice(0,-999f,999f))
        assertEquals(15,point.cx);assertEquals(85,point.cy)
    }
    @Test fun automaticCorrectionNeedsVerifiedSuccessAndIsLayoutSpecific() {
        val c=RuntimeEnvironment.getApplication() as Context
        c.getSharedPreferences("go_button_templates_v1",0).edit().clear().commit()
        val store=GoCardTapStore(c)
        val card=GoWorkflowPolicy.Card(131,GoWorkflowPolicy.Box(100,600,300,1000))
        val s=GoWorkflowPolicy.Scene(list=true,count=1,cards=listOf(card),width=1220,height=2712)
        repeat(3){store.point(card,GoWorkflowPolicy.Choice(0,.4f,.6f),s);store.verified(s)}
        val reopened=GoCardTapStore(c)
        assertEquals(180,reopened.point(card,null,s).cx)
        assertEquals(200,reopened.point(card,null,s.copy(keyboard=true)).cx)
        reopened.failed(s);assertFalse(reopened.fast(s));assertEquals(200,reopened.point(card,null,s).cx)
    }
    @Test fun automaticProfilesHaveAFixedStorageBound() {
        val c=RuntimeEnvironment.getApplication() as Context
        val prefs=c.getSharedPreferences("go_button_templates_v1",0);prefs.edit().clear().commit()
        val store=GoCardTapStore(c)
        repeat(100){index->
            val s=GoWorkflowPolicy.Scene(list=true,count=1,cards=listOf(GoWorkflowPolicy.Card(131,GoWorkflowPolicy.Box(0,200,200,500))),width=1000+index,height=2500)
            store.verified(s)
        }
        assertEquals(32,prefs.getString("auto_grid_keys_v2","")!!.split('|').size)
        assertEquals(32,prefs.all.keys.count{it.startsWith("auto_grid_v2_")})
    }
}
