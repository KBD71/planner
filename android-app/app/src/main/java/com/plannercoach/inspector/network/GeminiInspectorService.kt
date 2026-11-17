package com.plannercoach.inspector.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.plannercoach.inspector.model.AccumulationStage
import com.plannercoach.inspector.model.DailyBarPoint
import com.plannercoach.inspector.model.DailyInspectionResult
import com.plannercoach.inspector.model.FocusHabitType
import com.plannercoach.inspector.model.NextWeekGoal
import com.plannercoach.inspector.model.PlanCorrectionItem
import com.plannercoach.inspector.model.StudentProfileType
import com.plannercoach.inspector.model.StudyMode
import com.plannercoach.inspector.model.SubjectPalette
import com.plannercoach.inspector.model.SubjectTimeBreakdown
import com.plannercoach.inspector.model.TimeBlock
import com.plannercoach.inspector.model.WeeklyInsightReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

class GeminiInspectorService(private val context: Context) {

    companion object {
        private const val INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1/interactions"
        private const val INTERACTIONS_BETA_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val NANO_BANANA_BASIC_MODEL = "gemini-nano-banana-2.1"
    }

    /**
     * 촬영하거나 선택한 플래너 사진을 Gemini API로 분석하고,
     * 필요 시 나노바나나 기본형(gemini-nano-banana-2.1) 교정 예시 이미지를 생성한다.
     */
    suspend fun inspectPlannerPhoto(
        imageUri: Uri,
        apiKey: String,
        model: String,
        studentType: StudentProfileType,
        studentName: String,
        dateIso: String,
        teacherMemo: String,
        recentHistory: List<DailyInspectionResult>,
        bibleStatusFromDb: BibleDayStatus? = null,
        biblePattern: BibleReadingPattern? = null,
        elemManualBibleDone: Boolean = false,
        elemManualBiblePassage: String = "",
        elemManualBibleChapters: Int = 0,
        focusHabitType: FocusHabitType = FocusHabitType.BIBLE_AND_VOLUME,
        keptYesterdayPromise: Boolean = false,
        useNanoBananaBasic: Boolean = true
    ): DailyInspectionResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "설정 화면에서 Gemini API 키를 먼저 입력해 주세요." }

        val base64Image = encodeUriToJpegBase64(imageUri)
        val systemInstruction = buildDailySystemInstruction(studentType, studentName)
        val historyContext = buildRecentHistoryContext(recentHistory)
        val yesterdayPromise = recentHistory.firstOrNull()?.copywritingPromise.orEmpty()

        val bibleContext = if (studentType == StudentProfileType.HIGH_SCHOOL) {
            buildString {
                bibleStatusFromDb?.let {
                    append("bible71 DB 성경읽기($dateIso): ${if (it.completed) "완료 (${it.passageText}, ${it.chaptersCount}장)" else "오늘 날짜 기록 없음"}\n")
                } ?: append("bible71 성경읽기 연동 대기\n")
                biblePattern?.let {
                    append("날짜별 성경읽기 패턴: ${it.patternSummary}\n")
                    append("내일 성경읽기 제안: ${it.nextDaySuggestion}")
                }
            }.trim()
        } else {
            buildString {
                if (elemManualBibleDone) {
                    append("관리자 확인 초등 성경읽기: 완료 ($elemManualBiblePassage)\n")
                } else {
                    append("사진 속 종이 플래너에서 성경읽기 분량 기록을 직접 판독하세요.\n")
                }
                biblePattern?.let {
                    append("초등 누적 성경 패턴: ${it.patternSummary} / 제안: ${it.nextDaySuggestion}")
                }
            }.trim()
        }

        val promptText = buildString {
            append("학생 구분: ${studentType.badgeEmoji} ${studentType.displayName} (이름: $studentName)\n")
            append("검사 날짜: $dateIso\n")
            append("관리자 이번 주 집중 코칭 습관: ${focusHabitType.label}\n")
            if (yesterdayPromise.isNotBlank()) {
                append("어제 손글씨 약속: \"$yesterdayPromise\" (이행 확인: ${if (keptYesterdayPromise) "지킴" else "미확인"})\n")
            }
            append("$bibleContext\n")
            if (teacherMemo.isNotBlank()) {
                append("지도자 추가 메모: $teacherMemo\n")
            }
            if (historyContext.isNotBlank()) {
                append("\n[최근 누적 습관 요약]\n$historyContext\n")
            }
            append(
                "\n첨부한 종이 플래너 사진을 꼼꼼히 읽고, 수정안(Before -> After), 색상별 모범 타임테이블, " +
                    "성경읽기 상태(bibleReadingCompleted/biblePassageText/bibleChaptersCount), 그리고 학생이 플래너 여백에 직접 손으로 베껴쓸 수 있는 약속·내일 예약 문장을 JSON으로 반환해 주세요."
            )
        }

        val requestBody = JSONObject().apply {
            put("model", model.ifBlank { "gemini-4-hydrogen" })
            put("system_instruction", systemInstruction)
            put("store", false)
            put(
                "input",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", promptText)
                    })
                    put(JSONObject().apply {
                        put("type", "image")
                        put("data", base64Image)
                        put("mime_type", "image/jpeg")
                    })
                }
            )
            put("response_format", dailyInspectionResponseFormat())
        }
        val rawResponse = postInteraction(apiKey, requestBody)
        val jsonText = extractTextFromInteraction(rawResponse)
        val parsed = parseJsonObjectSafe(jsonText)

        val baseResult = mapParsedDailyResult(
            obj = parsed,
            studentType = studentType,
            studentName = studentName,
            dateIso = dateIso,
            bibleStatusFromDb = bibleStatusFromDb,
            biblePattern = biblePattern,
            recentHistory = recentHistory,
            elemManualBibleDone = elemManualBibleDone,
            elemManualBiblePassage = elemManualBiblePassage,
            elemManualBibleChapters = elemManualBibleChapters,
            focusHabitType = focusHabitType,
            keptYesterdayPromise = keptYesterdayPromise
        )

        val nanoBananaB64 = if (useNanoBananaBasic && apiKey.isNotBlank()) {
            runCatching {
                generateNanoBananaBasicImage(
                    apiKey = apiKey,
                    base64Jpeg = base64Image,
                    corrections = baseResult.corrections,
                    studentType = studentType
                )
            }.getOrNull()
        } else null

        if (nanoBananaB64 != null) baseResult.copy(nanoBananaImageBase64 = nanoBananaB64) else baseResult
    }

    /**
     * 누적된 일일 기록을 집계하고, Gemini API(가능한 경우) 또는 통계 분석 엔진을 결합하여
     * 4단계 적응형 주간 분석·예측 리포트를 생성한다.
     */
    suspend fun buildWeeklyInsightReport(
        studentType: StudentProfileType,
        studentName: String,
        allRecords: List<DailyInspectionResult>,
        weekOffset: Int,
        apiKey: String,
        model: String,
        bible71Logs: List<Bible71LogItem> = emptyList()
    ): WeeklyInsightReport = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val targetMonday = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .minusWeeks(weekOffset.toLong())
        val targetSunday = targetMonday.plusDays(6)
        val prevMonday = targetMonday.minusWeeks(1)
        val prevSunday = targetSunday.minusWeeks(1)

        val fmt = DateTimeFormatter.ISO_LOCAL_DATE
        val startIso = targetMonday.format(fmt)
        val endIso = targetSunday.format(fmt)
        val prevStartIso = prevMonday.format(fmt)
        val prevEndIso = prevSunday.format(fmt)

        val thisWeekRecords = allRecords.filter { it.dateIso in startIso..endIso }
        val prevWeekRecords = allRecords.filter { it.dateIso in prevStartIso..prevEndIso }

        val totalAccumulatedDays = allRecords.map { it.dateIso }.distinct().size
        val stage = AccumulationStage.fromDays(totalAccumulatedDays)

        val bibleHelper = Bible71Service(context)
        val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
        val dailyBars = (0..6).map { offset ->
            val date = targetMonday.plusDays(offset.toLong())
            val iso = date.format(fmt)
            val dayRecs = thisWeekRecords.filter { it.dateIso == iso }
            val mins = dayRecs.sumOf { it.totalStudyMinutes }
            val planned = dayRecs.sumOf { it.plannedTasksCount }
            val done = dayRecs.sumOf { it.completedTasksCount }
            val pct = if (planned > 0) ((done * 100f) / planned).toInt().coerceIn(0, 100) else 0

            val (bDone, bCh) = if (studentType == StudentProfileType.HIGH_SCHOOL) {
                val bStatus = bibleHelper.getStatusForDate(bible71Logs, iso)
                val doneFlag = bStatus.completed || dayRecs.any { it.bibleCompleted }
                val ch = if (bStatus.completed) bStatus.chaptersCount else dayRecs.sumOf { it.bibleChaptersCount }
                doneFlag to ch
            } else {
                val doneFlag = dayRecs.any { it.bibleCompleted }
                val ch = dayRecs.sumOf { if (it.bibleChaptersCount > 0) it.bibleChaptersCount else if (it.bibleCompleted) 1 else 0 }
                doneFlag to ch
            }
            DailyBarPoint(dayLabels[offset], iso, mins, pct, bDone, bCh)
        }

        val bibleDaysCount = dailyBars.count { it.bibleDone }
        val bibleChaptersTotal = dailyBars.sumOf { it.bibleChapters }
        val effectiveBiblePattern = if (studentType == StudentProfileType.HIGH_SCHOOL) {
            bibleHelper.analyzeReadingPattern(bible71Logs, endIso, isElementary = false)
        } else {
            val elemLogs = allRecords
                .filter { it.bibleCompleted || it.biblePassageText.isNotBlank() }
                .mapIndexed { idx, rec ->
                    Bible71LogItem(
                        id = idx.toLong(),
                        date = rec.dateIso,
                        category = "planner",
                        itemId = "",
                        title = rec.biblePassageText,
                        passage = rec.biblePassageText,
                        chaptersCount = rec.bibleChaptersCount.coerceAtLeast(1)
                    )
                }
            bibleHelper.analyzeReadingPattern(elemLogs, endIso, isElementary = true)
        }
        val bibleSummaryLine = if (studentType == StudentProfileType.HIGH_SCHOOL) {
            "bible71 날짜패턴 · 이번 주 ${bibleDaysCount}일/${bibleChaptersTotal}장 · ${effectiveBiblePattern.patternSummary} | 제안: ${effectiveBiblePattern.nextDaySuggestion}"
        } else {
            "종이 플래너 기록 · 이번 주 ${bibleDaysCount}일/${bibleChaptersTotal}장 · ${effectiveBiblePattern.nextDaySuggestion}"
        }

        val totalMinutes = thisWeekRecords.sumOf { it.totalStudyMinutes }
        val prevMinutes = if (prevWeekRecords.isNotEmpty()) prevWeekRecords.sumOf { it.totalStudyMinutes } else null
        val deltaMinutes = prevMinutes?.let { totalMinutes - it }

        val totalSelf = thisWeekRecords.sumOf { it.selfStudyMinutes + it.reviewMinutes }
        val selfStudyRatio = if (totalMinutes > 0) ((totalSelf * 100f) / totalMinutes).toInt().coerceIn(0, 100) else 0

        val totalPlanned = thisWeekRecords.sumOf { it.plannedTasksCount }
        val totalDone = thisWeekRecords.sumOf { it.completedTasksCount }
        val completionRate = if (totalPlanned > 0) ((totalDone * 100f) / totalPlanned).toInt().coerceIn(0, 100) else 0

        val avgHabitScore = if (thisWeekRecords.isNotEmpty()) {
            thisWeekRecords.map { it.habitScore }.average().toInt()
        } else 0

        val subjectMap = linkedMapOf<String, Int>()
        thisWeekRecords.forEach { rec ->
            rec.subjectBreakdowns().forEach { sb ->
                subjectMap[sb.subject] = (subjectMap[sb.subject] ?: 0) + sb.minutes
            }
        }
        val subjectBreakdowns = subjectMap.entries
            .sortedByDescending { it.value }
            .map { (subj, mins) ->
                SubjectTimeBreakdown(subj, mins, SubjectPalette.colorHexFor(studentType, subj))
            }

        val tagCounts = linkedMapOf<String, Int>()
        thisWeekRecords.flatMap { it.weakHabitTags }.forEach { tag ->
            val clean = tag.trim()
            if (clean.isNotEmpty()) tagCounts[clean] = (tagCounts[clean] ?: 0) + 1
        }
        val topWeakTags = tagCounts.entries.sortedByDescending { it.value }.map { it.key to it.value }.take(4)

        val fallback = buildDeterministicWeeklyInsights(
            studentType = studentType,
            studentName = studentName,
            stage = stage,
            thisWeekRecords = thisWeekRecords,
            totalMinutes = totalMinutes,
            deltaMinutes = deltaMinutes,
            selfStudyRatio = selfStudyRatio,
            completionRate = completionRate,
            dailyBars = dailyBars,
            subjectBreakdowns = subjectBreakdowns,
            topWeakTags = topWeakTags
        )

        if (apiKey.isNotBlank() && thisWeekRecords.isNotEmpty()) {
            val aiInsights = runCatching {
                requestWeeklyAiInsights(
                    apiKey = apiKey,
                    model = model,
                    studentType = studentType,
                    studentName = studentName,
                    stage = stage,
                    totalAccumulatedDays = totalAccumulatedDays,
                    thisWeekRecords = thisWeekRecords,
                    totalMinutes = totalMinutes,
                    deltaMinutes = deltaMinutes,
                    selfStudyRatio = selfStudyRatio,
                    completionRate = completionRate,
                    subjectBreakdowns = subjectBreakdowns,
                    topWeakTags = topWeakTags
                )
            }.getOrNull()

            if (aiInsights != null) {
                return@withContext WeeklyInsightReport(
                    studentType = studentType,
                    studentName = studentName,
                    weekLabel = "${targetMonday.monthValue}/${targetMonday.dayOfMonth} ~ ${targetSunday.monthValue}/${targetSunday.dayOfMonth}",
                    startDateIso = startIso,
                    endDateIso = endIso,
                    totalAccumulatedDays = totalAccumulatedDays,
                    thisWeekDaysCount = thisWeekRecords.map { it.dateIso }.distinct().size,
                    stage = stage,
                    totalStudyMinutes = totalMinutes,
                    prevWeekDeltaMinutes = deltaMinutes,
                    selfStudyRatioPercent = selfStudyRatio,
                    completionRatePercent = completionRate,
                    avgHabitScore = avgHabitScore,
                    bibleDaysCount = bibleDaysCount,
                    bibleChaptersTotal = bibleChaptersTotal,
                    bibleSummaryLine = bibleSummaryLine,
                    dailyBars = dailyBars,
                    subjectBreakdowns = subjectBreakdowns,
                    topWeakTags = topWeakTags,
                    bestPatternInsight = aiInsights.bestPattern,
                    warningHabitInsight = aiInsights.warningHabit,
                    overPlanningDiagnosis = aiInsights.overPlanning,
                    predictionInsight = aiInsights.prediction,
                    nextWeekTargetDailyMinutes = aiInsights.nextWeekDailyMinutes,
                    nextWeekGoals = aiInsights.goals,
                    weeklyCopywritingPromise = aiInsights.weeklyCopywriting
                )
            }
        }

        WeeklyInsightReport(
            studentType = studentType,
            studentName = studentName,
            weekLabel = "${targetMonday.monthValue}/${targetMonday.dayOfMonth} ~ ${targetSunday.monthValue}/${targetSunday.dayOfMonth}",
            startDateIso = startIso,
            endDateIso = endIso,
            totalAccumulatedDays = totalAccumulatedDays,
            thisWeekDaysCount = thisWeekRecords.map { it.dateIso }.distinct().size,
            stage = stage,
            totalStudyMinutes = totalMinutes,
            prevWeekDeltaMinutes = deltaMinutes,
            selfStudyRatioPercent = selfStudyRatio,
            completionRatePercent = completionRate,
            avgHabitScore = avgHabitScore,
            bibleDaysCount = bibleDaysCount,
            bibleChaptersTotal = bibleChaptersTotal,
            bibleSummaryLine = bibleSummaryLine,
            dailyBars = dailyBars,
            subjectBreakdowns = subjectBreakdowns,
            topWeakTags = topWeakTags,
            bestPatternInsight = fallback.bestPattern,
            warningHabitInsight = fallback.warningHabit,
            overPlanningDiagnosis = fallback.overPlanning,
            predictionInsight = fallback.prediction,
            nextWeekTargetDailyMinutes = fallback.nextWeekDailyMinutes,
            nextWeekGoals = fallback.goals,
            weeklyCopywritingPromise = fallback.weeklyCopywriting
        )
    }

    private fun buildDailySystemInstruction(studentType: StudentProfileType, studentName: String): String {
        return when (studentType) {
            StudentProfileType.HIGH_SCHOOL -> """
                당신은 대한민국 상위권 입시·자기주도학습 및 경건 습관 플래너 코치입니다.
                현재 지도자는 학생($studentName)의 종이 플래너를 직접 점검·교정하는 시작 단계입니다.
                '플래너 5대 원칙(1.구체적 교재/단원 2.정확한 페이지/문항 분량 3.목표시간-타임테이블 매칭 4.인강 대 자습 균형 5.오답·복습 배치)'과 매일 성경읽기(bible71 DB 연계) 습관 기준으로 평가하세요.
                
                반드시 지켜야 할 규칙:
                1. Before -> After 교정 시, 학생이 적은 실제 과목/교재 내용을 최대한 살리되 모호한 표현('수학 공부', '영어 단어')을 '교재명 + 단원 + 페이지/문항 수 + 예상시간' 형태로 구체화하세요.
                2. timeBlocks는 08:00~24:00 범위에서 10분 단위(durationMinutes는 10의 배수)로 겹치지 않게 모범 타임테이블을 배치하고, 성경읽기 블록(subject: "성경", 20분)도 포함하세요.
                3. 고등학생은 반드시 studyMode를 'SELF_STUDY'(자습·문제풀이), 'LECTURE'(인강·수업), 'REVIEW'(오답·복습)로 구별해 색상 농도와 복습 루틴을 배울 수 있게 하세요.
                4. copywritingPromise(오늘의 플래너 약속, 40~65자)와 copywritingPractice(내일 첫 공부·말씀 예약 문장 포함, 35~65자)는 종이 플래너 여백에 손으로 베껴쓸 수 있도록 간결하게 작성하세요.
            """.trimIndent()

            StudentProfileType.ELEMENTARY -> """
                당신은 초등학생($studentName)의 자기주도 학습 및 성경읽기 습관을 따뜻하고 명확하게 길러주는 학습 코치입니다.
                초등학생은 bible71 앱을 쓰지 않고 종이 플래너에 직접 읽은 성경 분량을 손으로 기록합니다.
                
                반드시 지켜야 할 규칙:
                1. 사진 속 종이 플래너에 성경읽기(예: 잠언 1장, 마태복음 3장)가 적혀 있는지 반드시 확인하여 bibleReadingCompleted, biblePassageText, bibleChaptersCount에 반환하세요. 누락되거나 모호하면 수정안(Before->After)에 성경읽기 분량 예시를 포함하세요.
                2. 일반 과목도 '[무엇을 + 몇 장/몇 쪽 + 몇 분]' 형태로 쉽고 성취감 있게 바꿔 주세요.
                3. timeBlocks는 14:00~21:00 범위에서 30분 단위 블록(durationMinutes는 30 또는 60)으로 배치하세요.
                4. copywritingPromise(오늘의 다짐 약속, 20~35자)와 copywritingPractice(내일 첫 공부·성경 약속 문장, 20~40자)는 초등학생이 기쁘게 손글씨로 베껴쓸 수 있는 다정한 문장으로 만드세요.
            """.trimIndent()
        }
    }

    private fun dailyInspectionResponseFormat(): JSONObject = JSONObject().apply {
        put("type", "text")
        put("mime_type", "application/json")
        put(
            "schema",
            JSONObject(
                """
                {
                  "type": "object",
                  "required": [
                    "habitScore", "gradeLabel", "praiseOneLiner", "improvementSummary",
                    "weakHabitTags", "corrections", "timeBlocks",
                    "totalStudyMinutes", "selfStudyMinutes", "lectureMinutes", "reviewMinutes",
                    "plannedTasksCount", "completedTasksCount",
                    "copywritingPromise", "copywritingPractice",
                    "bibleReadingCompleted", "biblePassageText", "bibleChaptersCount"
                  ],
                  "properties": {
                    "habitScore": { "type": "integer", "minimum": 0, "maximum": 100 },
                    "gradeLabel": { "type": "string" },
                    "praiseOneLiner": { "type": "string" },
                    "improvementSummary": { "type": "string" },
                    "weakHabitTags": { "type": "array", "items": { "type": "string" } },
                    "totalStudyMinutes": { "type": "integer" },
                    "selfStudyMinutes": { "type": "integer" },
                    "lectureMinutes": { "type": "integer" },
                    "reviewMinutes": { "type": "integer" },
                    "plannedTasksCount": { "type": "integer" },
                    "completedTasksCount": { "type": "integer" },
                    "copywritingPromise": { "type": "string" },
                    "copywritingPractice": { "type": "string" },
                    "bibleReadingCompleted": { "type": "boolean" },
                    "biblePassageText": { "type": "string" },
                    "bibleChaptersCount": { "type": "integer" },
                    "corrections": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["subject", "beforeText", "afterText", "missingTag", "targetMinutes", "completed"],
                        "properties": {
                          "subject": { "type": "string" },
                          "beforeText": { "type": "string" },
                          "afterText": { "type": "string" },
                          "missingTag": { "type": "string" },
                          "targetMinutes": { "type": "integer" },
                          "completed": { "type": "boolean" }
                        }
                      }
                    },
                    "timeBlocks": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["startHour", "startMinute", "durationMinutes", "subject", "studyMode", "label"],
                        "properties": {
                          "startHour": { "type": "integer" },
                          "startMinute": { "type": "integer" },
                          "durationMinutes": { "type": "integer" },
                          "subject": { "type": "string" },
                          "studyMode": { "type": "string", "enum": ["SELF_STUDY", "LECTURE", "REVIEW"] },
                          "label": { "type": "string" }
                        }
                      }
                    }
                  }
                }
                """.trimIndent()
            )
        )
    }

    private fun buildRecentHistoryContext(recentHistory: List<DailyInspectionResult>): String {
        if (recentHistory.isEmpty()) return ""
        return recentHistory.take(5).joinToString("\n") { rec ->
            "- ${rec.dateIso}: 점수 ${rec.habitScore}점, 공부 ${rec.totalStudyMinutes}분, 부족태그 ${rec.weakHabitTags.joinToString(",")}"
        }
    }

    private fun mapParsedDailyResult(
        obj: JSONObject,
        studentType: StudentProfileType,
        studentName: String,
        dateIso: String,
        bibleStatusFromDb: BibleDayStatus?,
        biblePattern: BibleReadingPattern?,
        recentHistory: List<DailyInspectionResult>,
        elemManualBibleDone: Boolean,
        elemManualBiblePassage: String,
        elemManualBibleChapters: Int,
        focusHabitType: FocusHabitType,
        keptYesterdayPromise: Boolean
    ): DailyInspectionResult {
        val tagsArr = obj.optJSONArray("weakHabitTags") ?: JSONArray()
        val tags = buildList {
            for (i in 0 until tagsArr.length()) {
                val t = tagsArr.optString(i).trim()
                if (t.isNotEmpty()) add(if (t.startsWith("#")) t else "#$t")
            }
        }
        val corrArr = obj.optJSONArray("corrections") ?: JSONArray()
        val corrections = buildList {
            for (i in 0 until corrArr.length()) {
                val c = corrArr.getJSONObject(i)
                add(
                    PlanCorrectionItem(
                        subject = c.optString("subject", "기타"),
                        beforeText = c.optString("beforeText", "미기재 계획"),
                        afterText = c.optString("afterText", "구체적 교재/분량/시간 추가"),
                        missingTag = c.optString("missingTag", "#분량미기재"),
                        targetMinutes = c.optInt("targetMinutes", studentType.slotMinutes * 2).coerceAtLeast(10),
                        completed = c.optBoolean("completed", false)
                    )
                )
            }
        }
        val blockArr = obj.optJSONArray("timeBlocks") ?: JSONArray()
        val blocks = buildList {
            for (i in 0 until blockArr.length()) {
                val b = blockArr.getJSONObject(i)
                add(
                    TimeBlock(
                        startHour = b.optInt("startHour", studentType.startHour).coerceIn(0, 23),
                        startMinute = b.optInt("startMinute", 0).coerceIn(0, 50),
                        durationMinutes = b.optInt("durationMinutes", studentType.slotMinutes).coerceIn(10, 240),
                        subject = b.optString("subject", "기타"),
                        studyMode = StudyMode.fromId(b.optString("studyMode")),
                        label = b.optString("label", "")
                    )
                )
            }
        }.sortedBy { it.startHour * 60 + it.startMinute }

        val computedTotal = blocks.sumOf { it.durationMinutes }
        val totalMinutes = obj.optInt("totalStudyMinutes", computedTotal).takeIf { it > 0 } ?: computedTotal
        val selfMinutes = obj.optInt("selfStudyMinutes", (totalMinutes * 0.6).toInt())
        val lectureMinutes = obj.optInt("lectureMinutes", (totalMinutes * 0.25).toInt())
        val reviewMinutes = obj.optInt("reviewMinutes", (totalMinutes - selfMinutes - lectureMinutes).coerceAtLeast(0))

        val finalBibleCompleted = if (studentType == StudentProfileType.HIGH_SCHOOL) {
            bibleStatusFromDb?.completed ?: false
        } else {
            elemManualBibleDone || obj.optBoolean("bibleReadingCompleted", false)
        }
        val finalBiblePassage = if (studentType == StudentProfileType.HIGH_SCHOOL) {
            bibleStatusFromDb?.passageText ?: (biblePattern?.nextDaySuggestion ?: "오늘 날짜 패턴 기반 성경읽기 대기")
        } else {
            elemManualBiblePassage.ifBlank {
                obj.optString("biblePassageText", if (finalBibleCompleted) "종이 플래너 성경읽기 확인" else "오늘 읽을 성경 본문을 적고 읽어보세요")
            }
        }
        val finalBibleChapters = if (studentType == StudentProfileType.HIGH_SCHOOL) {
            bibleStatusFromDb?.chaptersCount ?: 0
        } else {
            elemManualBibleChapters.takeIf { it > 0 }
                ?: obj.optInt("bibleChaptersCount", if (finalBibleCompleted) 1 else 0)
        }

        val effectivePattern = if (studentType == StudentProfileType.HIGH_SCHOOL) {
            biblePattern
        } else {
            val combinedLogs = buildList {
                recentHistory.filter { it.bibleCompleted || it.biblePassageText.isNotBlank() }
                    .forEachIndexed { idx, rec ->
                        add(
                            Bible71LogItem(
                                id = (idx + 1).toLong(),
                                date = rec.dateIso,
                                category = "planner",
                                itemId = "",
                                title = rec.biblePassageText,
                                passage = rec.biblePassageText,
                                chaptersCount = rec.bibleChaptersCount.coerceAtLeast(1)
                            )
                        )
                    }
                if (finalBibleCompleted || finalBiblePassage.isNotBlank()) {
                    add(
                        Bible71LogItem(
                            id = 9999L,
                            date = dateIso,
                            category = "planner",
                            itemId = "",
                            title = finalBiblePassage,
                            passage = finalBiblePassage,
                            chaptersCount = finalBibleChapters.coerceAtLeast(1)
                        )
                    )
                }
            }
            Bible71Service(context).analyzeReadingPattern(combinedLogs, dateIso, isElementary = true)
        }

        val baseScore = obj.optInt("habitScore", 78).coerceIn(0, 100)
        val finalScore = (baseScore + if (keptYesterdayPromise) 10 else 0).coerceIn(0, 100)

        return DailyInspectionResult(
            id = UUID.randomUUID().toString(),
            studentType = studentType,
            studentName = studentName,
            dateIso = dateIso,
            habitScore = finalScore,
            gradeLabel = when {
                finalScore >= 90 -> "A+"
                finalScore >= 82 -> "A"
                else -> obj.optString("gradeLabel", "B+")
            },
            praiseOneLiner = obj.optString("praiseOneLiner", "계획을 세우고 기록하려는 태도가 훌륭합니다!"),
            improvementSummary = obj.optString("improvementSummary", "교재명·쪽수·목표 시간을 함께 적어 실행력을 높이세요."),
            corrections = corrections,
            timeBlocks = blocks,
            totalStudyMinutes = totalMinutes,
            selfStudyMinutes = selfMinutes,
            lectureMinutes = lectureMinutes,
            reviewMinutes = reviewMinutes,
            plannedTasksCount = obj.optInt("plannedTasksCount", corrections.size.coerceAtLeast(1)),
            completedTasksCount = obj.optInt("completedTasksCount", corrections.count { it.completed }),
            weakHabitTags = tags.ifEmpty { listOf("#구체적분량보완") },
            copywritingPromise = obj.optString(
                "copywritingPromise",
                if (studentType == StudentProfileType.HIGH_SCHOOL)
                    "매일 성경읽기를 마치고 교재명·페이지·목표시간을 함께 적으며 자습 복습 30분을 확보한다."
                else
                    "성경 몇 장 읽었는지, 무슨 책 몇 쪽 풀지 꼭 적을게요!"
            ),
            copywritingPractice = obj.optString(
                "copywritingPractice",
                corrections.firstOrNull()?.let { "${it.beforeText} (X) → ${it.afterText} (O)" }
                    ?: "구체적인 분량과 시간을 함께 적어 실천율을 높이자!"
            ),
            bibleCompleted = finalBibleCompleted,
            biblePassageText = finalBiblePassage,
            bibleChaptersCount = finalBibleChapters,
            keptYesterdayPromise = keptYesterdayPromise,
            focusHabitKey = focusHabitType.id,
            biblePatternSummary = effectivePattern?.patternSummary.orEmpty(),
            bibleNextSuggestion = effectivePattern?.nextDaySuggestion.orEmpty()
        )
    }

    private data class WeeklyAiSynthesis(
        val bestPattern: String,
        val warningHabit: String,
        val overPlanning: String,
        val prediction: String,
        val nextWeekDailyMinutes: Int,
        val goals: List<NextWeekGoal>,
        val weeklyCopywriting: String
    )

    private fun requestWeeklyAiInsights(
        apiKey: String,
        model: String,
        studentType: StudentProfileType,
        studentName: String,
        stage: AccumulationStage,
        totalAccumulatedDays: Int,
        thisWeekRecords: List<DailyInspectionResult>,
        totalMinutes: Int,
        deltaMinutes: Int?,
        selfStudyRatio: Int,
        completionRate: Int,
        subjectBreakdowns: List<SubjectTimeBreakdown>,
        topWeakTags: List<Pair<String, Int>>
    ): WeeklyAiSynthesis {
        val summaryPrompt = buildString {
            append("학생: ${studentType.badgeEmoji} ${studentType.displayName} ($studentName)\n")
            append("누적 분석 단계: ${stage.badgeText} (총 누적 ${totalAccumulatedDays}일)\n")
            append("이번 주 공부시간: ${totalMinutes}분 (전주 대비: ${deltaMinutes?.let { "${it}분" } ?: "비교 데이터 없음"})\n")
            append("순공/복습 비율: $selfStudyRatio%, 계획 달성률: $completionRate%\n")
            append("과목별 시간: ${subjectBreakdowns.joinToString { "${it.subject} ${it.minutes}분" }}\n")
            append("주요 감점 습관 태그: ${topWeakTags.joinToString { "${it.first}(${it.second}회)" }}\n")
            append("일별 기록 요약:\n")
            thisWeekRecords.forEach { r ->
                append("- ${r.dateIso}: ${r.totalStudyMinutes}분, 점수 ${r.habitScore}, 달성 ${r.completedTasksCount}/${r.plannedTasksCount}, 계획 예시: ${r.corrections.joinToString("; ") { it.afterText }}\n")
            }
            append("\n위 누적 데이터를 분석해 주간 인사이트, 계획 과욕지수 진단, 향후 학습 예측, 다음 1주일 맞춤 목표 3가지, 주간 다짐 필사 문장을 JSON으로 생성하세요. 데이터량이 적은 초기(1~3일)라면 무리한 완독 예측 대신 현 시점 진단과 첫 주 습관 목표에 집중하세요.")
        }

        val req = JSONObject().apply {
            put("model", model.ifBlank { "gemini-4-hydrogen" })
            put(
                "system_instruction",
                "당신은 누적 학습 데이터를 기반으로 정확하고 따뜻한 주간 리포트를 쓰는 자기주도학습 데이터 코치입니다. 과장 없이 실제 데이터에 근거해 예측과 목표를 세우세요."
            )
            put("store", false)
            put("input", summaryPrompt)
            put(
                "response_format",
                JSONObject().apply {
                    put("type", "text")
                    put("mime_type", "application/json")
                    put(
                        "schema",
                        JSONObject(
                            """
                            {
                              "type": "object",
                              "required": ["bestPattern", "warningHabit", "overPlanning", "prediction", "nextWeekDailyMinutes", "goals", "weeklyCopywriting"],
                              "properties": {
                                "bestPattern": { "type": "string" },
                                "warningHabit": { "type": "string" },
                                "overPlanning": { "type": "string" },
                                "prediction": { "type": "string" },
                                "nextWeekDailyMinutes": { "type": "integer" },
                                "weeklyCopywriting": { "type": "string" },
                                "goals": {
                                  "type": "array",
                                  "minItems": 3,
                                  "maxItems": 3,
                                  "items": {
                                    "type": "object",
                                    "required": ["title", "targetMetric", "actionTip"],
                                    "properties": {
                                      "title": { "type": "string" },
                                      "targetMetric": { "type": "string" },
                                      "actionTip": { "type": "string" }
                                    }
                                  }
                                }
                              }
                            }
                            """.trimIndent()
                        )
                    )
                }
            )
        }

        val raw = postInteraction(apiKey, req)
        val obj = parseJsonObjectSafe(extractTextFromInteraction(raw))
        val goalsArr = obj.optJSONArray("goals") ?: JSONArray()
        val goals = buildList {
            for (i in 0 until goalsArr.length()) {
                val g = goalsArr.getJSONObject(i)
                add(
                    NextWeekGoal(
                        title = g.optString("title"),
                        targetMetric = g.optString("targetMetric"),
                        actionTip = g.optString("actionTip")
                    )
                )
            }
        }
        return WeeklyAiSynthesis(
            bestPattern = obj.optString("bestPattern"),
            warningHabit = obj.optString("warningHabit"),
            overPlanning = obj.optString("overPlanning"),
            prediction = obj.optString("prediction"),
            nextWeekDailyMinutes = obj.optInt("nextWeekDailyMinutes", 120),
            goals = goals,
            weeklyCopywriting = obj.optString("weeklyCopywriting")
        )
    }

    private fun buildDeterministicWeeklyInsights(
        studentType: StudentProfileType,
        studentName: String,
        stage: AccumulationStage,
        thisWeekRecords: List<DailyInspectionResult>,
        totalMinutes: Int,
        deltaMinutes: Int?,
        selfStudyRatio: Int,
        completionRate: Int,
        dailyBars: List<DailyBarPoint>,
        subjectBreakdowns: List<SubjectTimeBreakdown>,
        topWeakTags: List<Pair<String, Int>>
    ): WeeklyAiSynthesis {
        val activeDays = thisWeekRecords.map { it.dateIso }.distinct().size.coerceAtLeast(1)
        val avgDaily = if (thisWeekRecords.isEmpty()) 0 else totalMinutes / activeDays
        val bestDay = dailyBars.maxByOrNull { it.minutes }
        val topSubject = subjectBreakdowns.firstOrNull()?.subject ?: "주요 과목"
        val topTag = topWeakTags.firstOrNull()?.first ?: "#분량구체화"

        val nextTargetDaily = if (avgDaily > 0) {
            ((avgDaily * 1.12).toInt() / 10 * 10).coerceAtLeast(if (studentType == StudentProfileType.HIGH_SCHOOL) 120 else 40)
        } else {
            if (studentType == StudentProfileType.HIGH_SCHOOL) 180 else 60
        }

        val bestPattern = if (thisWeekRecords.isEmpty()) {
            "이번 주 기록이 아직 없습니다. 오늘 플래너 사진 1장만 찍어 첫 기록을 시작해 보세요!"
        } else {
            "${bestDay?.dayLabel ?: "주중"}요일에 ${formatMinutes(bestDay?.minutes ?: 0)} 공부하며 가장 높은 집중력을 보였고, $topSubject 중심 학습 리듬이 안정적입니다."
        }

        val warningHabit = if (thisWeekRecords.isEmpty()) {
            "꾸준한 기록 누적이 주간 리포트의 정확도를 높여 줍니다."
        } else {
            "이번 주 가장 자주 발견된 보완점은 '$topTag'입니다. 계획 작성 시 교재·쪽수·목표시간을 빠짐없이 적어 보세요."
        }

        val overPlanning = when {
            thisWeekRecords.isEmpty() -> "기록이 쌓이면 계획 과욕지수(계획 대비 달성률)를 자동 진단합니다."
            completionRate < 65 -> "계획 과욕지수 높음(달성률 $completionRate%): 욕심내어 많은 할 일을 적기보다 하루 핵심 3가지만 적어 85% 달성을 노려보세요."
            completionRate < 85 -> "계획량 적정(달성률 $completionRate%): 소화 가능한 계획을 세우고 있습니다. 미완료 항목은 다음 날 복습 칸으로 이월하세요."
            else -> "실천 균형 최상(달성률 $completionRate%): 세운 계획을 정확히 지키는 실행력이 돋보입니다! 다음 주는 소폭(+10%) 분량을 늘려보세요."
        }

        val prediction = when (stage) {
            AccumulationStage.STAGE_1_SEED ->
                "현재 씨앗기(누적 데이터 수집 중)입니다. 4일 이상 기록이 쌓이면 요일별 슬럼프 분석과 현실적 주간 목표 예측이 열립니다."
            AccumulationStage.STAGE_2_SPROUT ->
                "일평균 ${formatMinutes(avgDaily)} 속도 유지 시 다음 주 총 ${formatMinutes(nextTargetDaily * 6)} 학습이 가능하며, 순공 비율 ${selfStudyRatio}%를 ${((selfStudyRatio + 8).coerceAtMost(85))}%까지 끌어올릴 수 있습니다."
            AccumulationStage.STAGE_3_GROWTH ->
                "전주 대비 ${deltaMinutes?.let { if (it >= 0) "+${formatMinutes(it)} 증가" else "${formatMinutes(-it)} 감소" } ?: "안정 유지"} 추세입니다. 현재 리듬이면 다음 달 핵심 문제집 1권 완독이 무난합니다."
            AccumulationStage.STAGE_4_PREDICT ->
                "장기 누적 패턴 분석 완료! 현재 주당 ${formatMinutes(totalMinutes)} 페이스를 유지하면 현재 진행 중인 주요 교재·단원을 2~3주 내 완료할 수 있습니다."
        }

        val goals = if (studentType == StudentProfileType.HIGH_SCHOOL) {
            listOf(
                NextWeekGoal("일일 목표 공부량", "하루 ${formatMinutes(nextTargetDaily)} (주 6일)", "가장 집중력이 떨어지는 요일에 30분 쪼개기 루틴 배치"),
                NextWeekGoal("순공·복습 황금비율", "자습·오답 비중 ${(selfStudyRatio + 5).coerceIn(60, 85)}% 달성", "인강 1시간당 반드시 자습·백지복습 30분 이상 짝짓기"),
                NextWeekGoal("플래너 구체성 습관", "$topTag 제로화 (85점 이상)", "할 일 작성 시 [교재명+쪽수+문항수+예상시간] 4요소 필수 기입")
            )
        } else {
            listOf(
                NextWeekGoal("매일 플래너 약속", "하루 ${formatMinutes(nextTargetDaily)} 실천하기", "학교 끝나고 10분 안에 오늘 할 일 3가지 먼저 적기"),
                NextWeekGoal("무지개 과목 골고루", "국·수·영·독서 5색 블록 채우기", "한 과목만 몰아 하지 않고 30분씩 색깔 바꿔가며 공부하기"),
                NextWeekGoal("칭찬 습관 기르기", "계획 달성률 85% 이상 도전", "'숙제' 대신 '무슨 책 몇 쪽'까지 쓰고 다 하면 별표 도장 찍기")
            )
        }

        val copywriting = if (studentType == StudentProfileType.HIGH_SCHOOL) {
            "$studentName 의 다음 주 약속: 감으로 계획하지 않고 하루 ${formatMinutes(nextTargetDaily)} 분량과 시간을 정확히 설계해 85% 이상 완수한다."
        } else {
            "다음 주에는 매일 ${formatMinutes(nextTargetDaily)}씩, 해야 할 책과 쪽수를 꼭 적고 끝까지 해낼게요!"
        }

        return WeeklyAiSynthesis(
            bestPattern = bestPattern,
            warningHabit = warningHabit,
            overPlanning = overPlanning,
            prediction = prediction,
            nextWeekDailyMinutes = nextTargetDaily,
            goals = goals,
            weeklyCopywriting = copywriting
        )
    }

    private fun generateNanoBananaBasicImage(
        apiKey: String,
        base64Jpeg: String,
        corrections: List<PlanCorrectionItem>,
        studentType: StudentProfileType
    ): String? {
        val corrSummary = corrections.take(4).joinToString("\n") {
            "- [${it.subject}] ${it.beforeText} -> ${it.afterText} (${it.targetMinutes}분)"
        }.ifBlank { "- 과목별 색상 구분 + 교재명·쪽수·예상시간 구체화 + 성경읽기 블록 배치" }

        val paletteGuide = if (studentType == StudentProfileType.HIGH_SCHOOL) {
            "과목 색상: 성경(청록 #0D9488), 국어(로즈 #E11D48), 수학(블루 #2563EB), 영어(에메랄드 #059669), 탐구(퍼플 #7C3AED). 진한 색=자습, 연한 색=인강, 노란 테두리=복습."
        } else {
            "과목 색상: 성경(청록 #0D9488), 국어·독서(핑크 #EC4899), 수학·연산(블루 #3B82F6), 영어(민트 #10B981), 학교·숙제(앰버 #F59E0B)."
        }

        val editPrompt = """
            첨부한 학생 종이 플래너 사진의 실제 글씨와 칸 구조를 참고하되, 가장 바른 자기주도학습 플래너 예시 이미지로 깔끔하게 다시 그려 주세요.
            조건:
            1) 타임테이블을 위 과목 색상 규칙으로 뚜렷하게 색칠하세요. ($paletteGuide)
            2) 모호한 항목은 아래 수정안처럼 교재명·쪽수·목표시간으로 바꾼 모범 손글씨 느낌으로 보여주세요:
            $corrSummary
            3) 읽기 쉬운 한국어 텍스트와 깔끔한 형광펜 색칠 스타일을 유지하세요.
        """.trimIndent()

        val body = JSONObject().apply {
            put("model", NANO_BANANA_BASIC_MODEL)
            put(
                "input",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", editPrompt)
                    })
                    put(JSONObject().apply {
                        put("type", "image")
                        put("mime_type", "image/jpeg")
                        put("data", base64Jpeg)
                    })
                }
            )
        }

        val raw = postJsonRequest(
            endpoint = INTERACTIONS_BETA_ENDPOINT,
            headers = mapOf(
                "x-goog-api-key" to apiKey.trim(),
                "Api-Revision" to "2026-05-20"
            ),
            body = body,
            errorPrefix = "나노바나나 기본형 API 오류"
        )
        return extractImageBase64FromInteraction(raw)
    }

    private fun extractImageBase64FromInteraction(root: JSONObject): String? {
        root.optJSONObject("output_image")?.optString("data")?.takeIf { it.isNotBlank() }?.let { return it }

        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") == "model_output") {
                    val content = step.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "image") {
                            part.optString("data").takeIf { it.isNotBlank() }?.let { return it }
                        }
                    }
                }
            }
        }

        val outputs = root.optJSONArray("outputs")
        if (outputs != null) {
            for (i in 0 until outputs.length()) {
                val item = outputs.optJSONObject(i) ?: continue
                if (item.optString("type") == "image") {
                    item.optString("data").takeIf { it.isNotBlank() }?.let { return it }
                }
                item.optJSONObject("image")?.optString("data")?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private fun postInteraction(apiKey: String, body: JSONObject): JSONObject {
        return postJsonRequest(
            endpoint = INTERACTIONS_ENDPOINT,
            headers = mapOf("x-goog-api-key" to apiKey.trim()),
            body = body,
            errorPrefix = "Gemini API 오류"
        )
    }

    private fun postJsonRequest(
        endpoint: String,
        headers: Map<String, String>,
        body: JSONObject,
        errorPrefix: String
    ): JSONObject {
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        conn.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()

        if (status !in 200..299) {
            throw IllegalStateException("$errorPrefix (HTTP $status): $responseText")
        }
        return JSONObject(responseText)
    }

    private fun extractTextFromInteraction(root: JSONObject): String {
        val direct = root.optString("output_text", "")
        if (direct.isNotBlank()) return direct

        val steps = root.optJSONArray("steps")
        if (steps != null) {
            val sb = StringBuilder()
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") == "model_output") {
                    val content = step.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val item = content.optJSONObject(j) ?: continue
                        if (item.optString("type") == "text") {
                            sb.append(item.optString("text"))
                        }
                    }
                }
            }
            if (sb.isNotBlank()) return sb.toString()
        }

        val outputs = root.optJSONArray("outputs")
        if (outputs != null && outputs.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until outputs.length()) {
                val item = outputs.optJSONObject(i) ?: continue
                if (item.has("text")) sb.append(item.optString("text"))
            }
            if (sb.isNotBlank()) return sb.toString()
        }

        throw IllegalStateException("Gemini 응답에서 텍스트 출력을 찾을 수 없습니다.")
    }

    private fun parseJsonObjectSafe(rawText: String): JSONObject {
        val trimmed = rawText.trim()
        val clean = if (trimmed.startsWith("```")) {
            trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        } else {
            val firstBrace = trimmed.indexOf('{')
            val lastBrace = trimmed.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) trimmed.substring(firstBrace, lastBrace + 1) else trimmed
        }
        return JSONObject(clean)
    }

    private fun encodeUriToJpegBase64(uri: Uri): String {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("선택한 사진을 읽을 수 없습니다.")

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)

        val maxDim = 1440
        var sampleSize = 1
        while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: throw IllegalStateException("이미지를 디코딩할 수 없습니다.")

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 86, baos)
        if (!bitmap.isRecycled) bitmap.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun formatMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}시간 ${m}분"
            h > 0 -> "${h}시간"
            else -> "${m}분"
        }
    }
}
