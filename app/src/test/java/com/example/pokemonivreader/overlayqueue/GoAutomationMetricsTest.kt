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
@Config(sdk=[28],application=Application::class)
class GoAutomationMetricsTest {
 @Test fun aggregatesPersistClampAndRejectSensitiveLabels(){
  val c=RuntimeEnvironment.getApplication() as Context
  val m=GoAutomationMetrics(c);m.clear()
  m.record("ocr",100);m.record("ocr",300,false);m.record("a-private-search-query",999)
  val report=GoAutomationMetrics(c).report()
  assertTrue(report.contains("通常OCR：2回／失敗1回／平均200ms"))
  assertFalse(report.contains("a-private-search-query"))
  m.record("capture",-100);assertTrue(m.report().contains("画面取得：1回／失敗0回／平均0ms"))
  assertEquals("reason_cp",GoAutomationMetrics.failureEvent("CP不一致：private"))
  assertEquals("reason_cancel",GoAutomationMetrics.failureEvent("停止しました"))
  m.clear();assertTrue(m.report().contains("まだ計測データがありません"))
 }
 @Test fun stopLogPersistsIsBoundedAndContainsOnlyStructuredEvidence(){
  val c=RuntimeEnvironment.getApplication() as Context
  val m=GoAutomationMetrics(c);m.clear()
  val scene=GoWorkflowPolicy.Scene(list=true,count=1,keyboard=true,
   lines=listOf(GoWorkflowPolicy.Line("secret nickname",GoWorkflowPolicy.Box(1,1,2,2))),diagnostic="private OCR")
  repeat(55){m.recordStop("SEARCH","RESULTS","reason_timeout",scene)}
  m.recordStop("private query","RESULTS","reason_timeout",scene)
  val report=GoAutomationMetrics(c).exportReport()
  assertEquals(50,report.lines().count{it.contains(" / SEARCH / RESULTS / reason_timeout")})
  assertTrue(report.contains("キーボード=true 件数=1"))
  assertFalse(report.contains("secret nickname"));assertFalse(report.contains("private"))
  m.clear();assertTrue(m.stopReport().contains("停止記録はありません"))
 }

 @Test fun detailedStopShowsObservationAndInputSequenceWithoutRawValues() {
  val c = RuntimeEnvironment.getApplication() as Context
  val metrics = GoAutomationMetrics(c)
  metrics.clear()
  val trace = GoAutomationTrace("INDIVIDUAL_SEQUENCE", 100, expectedCp = 1499)
  val box = GoWorkflowPolicy.Box(1, 1, 2, 2)
  val scene = GoWorkflowPolicy.Scene(
   list = true, count = 1, keyboard = true,
   cards = listOf(GoWorkflowPolicy.Card(1499, box, "secret nickname")),
   lines = listOf(GoWorkflowPolicy.Line("secret query", box)), diagnostic = "secret OCR",
  )
  trace.add(110, "INPUT_SEARCH", "WRITE_INPUT_CONNECTION")
  trace.observe(210, "RESULTS", scene, "source=DISPLAY")
  trace.observe(310, "RESULTS", scene, "source=DISPLAY")
  val reason = GoAutomationMetrics.failureDetail("画面が変わったため停止しました secret message")
  metrics.recordStop("SEARCH", "RESULTS", "reason_other", scene, trace, reason)

  val report = GoAutomationMetrics(c).exportReport()
  assertTrue(report.contains("schema=2"))
  assertTrue(report.contains("detail=screen_changed"))
  assertTrue(report.contains("+10ms INPUT_SEARCH WRITE_INPUT_CONNECTION"))
  assertTrue(report.contains("+110ms RESULTS OBSERVED source=DISPLAY"))
  assertTrue(report.contains("matchingCards=1"))
  assertTrue(report.contains("x2, last=+210ms"))
  assertFalse(report.contains("secret"))
  assertFalse(report.contains("1499"))
 }

 @Test fun prevResetDiagnosticsKeepOnlyTheRecentEvents() {
  val c = RuntimeEnvironment.getApplication() as Context
  val metrics = GoAutomationMetrics(c)
  metrics.clear()
  val trace = GoAutomationTrace("PREV_RESET", 100)
  repeat(28) { trace.add(100L + it, "PREV_SELECT", "HOLD", "attempt=$it") }
  metrics.recordStop("BULK", "PREV_SELECT", "reason_tap", trace = trace, detail = "selection_not_verified")
  val report = GoAutomationMetrics(c).exportReport()
  assertTrue(report.contains("stage=PREV_SELECT"))
  assertTrue(report.contains("omittedEvents=4"))
  assertFalse(report.contains("+3ms PREV_SELECT"))
  assertTrue(report.contains("+27ms PREV_SELECT"))
 }

 @Test fun failureClassificationDistinguishesAllErrorTypes() {
  val cases = listOf(
   "停止しました" to ("reason_cancel" to "cancelled"),
   "prev一括解除を停止しました" to ("reason_cancel" to "cancelled"),
   "画面下の白いOKによる検索確定を確認できず停止しました" to ("reason_input" to "search_submit_failed"),
   "検索が確定する前に入力欄が閉じたため、整理を停止しました" to ("reason_input" to "search_submit_failed"),
   "キーボードが閉じません。GOのキーボードを閉じてからprev解除を再開してください" to ("reason_input" to "keyboard_dismiss_failed"),
   "キーボードの非表示キーを検出できませんでした" to ("reason_input" to "keyboard_dismiss_failed"),
   "検索条件が消失し、再入力後も維持されなかったため、別の個体のタグを変更しないよう停止しました" to ("reason_input" to "lost_search_filter"),
   "検索した種名と #prev を確認できません。別の個体のタグを変更しないため停止しました" to ("reason_other" to "search_verify_failed"),
   "Pokémon GOが前面と認識されません。GOのポケモン一覧へ戻り、Androidの「厳選テキスト入力」を確認してください" to ("reason_other" to "screen_recognition_failed"),
   "prev一括解除を確認できませんでした。表示と選択件数を確認してください" to ("reason_timeout" to "timeout"),
   "キーボード操作中に画面が切り替わったため、整理を停止しました" to ("reason_other" to "async_conflict"),
   "Pokémon GOの表示言語を確認できないため、prev一括解除を停止しました" to ("reason_other" to "go_language_unconfirmed"),
  )
  for ((message, expected) in cases) {
   assertEquals("event for $message", expected.first, GoAutomationMetrics.failureEvent(message))
   assertEquals("detail for $message", expected.second, GoAutomationMetrics.failureDetail(message))
  }
 }
}
