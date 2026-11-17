package com.plannercoach.inspector.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 2인용 학습자 프로필 타입 (고등학생 / 초등학생).
 * 타임테이블 눈금 단위, 기본 시간대, 과목 색상 체계, 피드백 어조를 결정한다.
 */
enum class StudentProfileType(
    val id: String,
    val displayName: String,
    val badgeEmoji: String,
    val slotMinutes: Int,
    val startHour: Int,
    val endHour: Int,
    val subtitle: String
) {
    HIGH_SCHOOL(
        id = "HIGH_SCHOOL",
        displayName = "고등학생",
        badgeEmoji = "🎓",
        slotMinutes = 10,
        startHour = 8,
        endHour = 24,
        subtitle = "10분 텐미닛 · 순공/인강 · bible71 성경읽기 연동"
    ),
    ELEMENTARY(
        id = "ELEMENTARY",
        displayName = "초등학생",
        badgeEmoji = "🌱",
        slotMinutes = 30,
        startHour = 14,
        endHour = 21,
        subtitle = "30분 블록 · 파스텔 5색 · 종이 플래너 성경읽기"
    );

    companion object {
        fun fromId(id: String?): StudentProfileType =
            entries.firstOrNull { it.id == id } ?: HIGH_SCHOOL
    }
}

/**
 * 공부 시간대별 학습 성격 (고등학생 모드에서 색상 농도 및 패턴으로 시각화).
 */
enum class StudyMode(val id: String, val label: String) {
    SELF_STUDY("SELF_STUDY", "자습·문제풀이"),
    LECTURE("LECTURE", "인강·수업"),
    REVIEW("REVIEW", "오답·복습");

    companion object {
        fun fromId(id: String?): StudyMode =
            entries.firstOrNull { it.id == id } ?: SELF_STUDY
    }
}

/**
 * 데이터 누적 일수에 따른 4단계 적응형 주간 분석 엔진 단계.
 */
enum class AccumulationStage(
    val level: Int,
    val title: String,
    val badgeText: String,
    val description: String
) {
    STAGE_1_SEED(1, "씨앗기", "Lv.1 씨앗기 (1~3일)", "기초 시간·분량 집계 및 최우선 습관 진단"),
    STAGE_2_SPROUT(2, "새싹기", "Lv.2 새싹기 (4~7일)", "요일별 집중 패턴 & 첫 주 현실 목표 산출"),
    STAGE_3_GROWTH(3, "성장기", "Lv.3 성장기 (8~20일)", "전주 대비 성장률 & 계획 과욕지수 정밀 진단"),
    STAGE_4_PREDICT(4, "예측기", "Lv.4 예측기 (21일+)", "누적 속도 기반 교재 완독 예상일 시뮬레이션");

    companion object {
        fun fromDays(daysCount: Int): AccumulationStage = when {
            daysCount <= 3 -> STAGE_1_SEED
            daysCount <= 7 -> STAGE_2_SPROUT
            daysCount <= 20 -> STAGE_3_GROWTH
            else -> STAGE_4_PREDICT
        }
    }
}

enum class FocusHabitType(val id: String, val label: String) {
    BIBLE_AND_VOLUME("BIBLE_AND_VOLUME", "1단계: 매일 성경읽기 + [교재명·쪽수] 구체적 분량 쓰기"),
    COLOR_TIMETABLE("COLOR_TIMETABLE", "2단계: 공부한 시간만큼 과목별 색깔로 타임테이블 칠하기"),
    SELF_REVIEW_PAIR("SELF_REVIEW_PAIR", "3단계: 인강/수업 직후 20분 자습·복습 블록 짝짓기"),
    NEXT_DAY_RESERVE("NEXT_DAY_RESERVE", "4단계: 하루 마감 & 내일 첫 공부·말씀 시간 미리 예약하기");

    companion object {
        fun fromId(id: String?): FocusHabitType =
            entries.firstOrNull { it.id == id } ?: BIBLE_AND_VOLUME
    }
}

/**
 * 과목별 세련된 컬러 팔레트 매퍼.
 */
object SubjectPalette {
    fun colorHexFor(profile: StudentProfileType, subject: String): Int {
        val key = subject.trim()
        if (key.contains("성경") || key.contains("말씀") || key.contains("맥체인") ||
            key.contains("QT") || key.contains("묵상") || key.contains("교리")
        ) {
            return 0xFF0D9488.toInt()
        }
        return when (profile) {
            StudentProfileType.HIGH_SCHOOL -> when {
                key.contains("국어") || key.contains("문학") || key.contains("독서") -> 0xFF3B82F6.toInt()
                key.contains("수학") || key.contains("미적") || key.contains("확통") -> 0xFFEF4444.toInt()
                key.contains("영어") -> 0xFFF59E0B.toInt()
                key.contains("탐구") || key.contains("과학") || key.contains("사회") ||
                    key.contains("물리") || key.contains("화학") || key.contains("생명") -> 0xFF10B981.toInt()
                key.contains("한국사") || key.contains("역사") -> 0xFF06B6D4.toInt()
                else -> 0xFF8B5CF6.toInt()
            }
            StudentProfileType.ELEMENTARY -> when {
                key.contains("국어") || key.contains("독서") || key.contains("책") -> 0xFF60A5FA.toInt()
                key.contains("수학") || key.contains("연산") -> 0xFFF87171.toInt()
                key.contains("영어") -> 0xFFFBBF24.toInt()
                key.contains("숙제") || key.contains("학교") || key.contains("학원") -> 0xFF34D399.toInt()
                else -> 0xFFA78BFA.toInt()
            }
        }
    }

    fun legendItems(profile: StudentProfileType): List<Pair<String, Int>> = when (profile) {
        StudentProfileType.HIGH_SCHOOL -> listOf(
            "성경" to 0xFF0D9488.toInt(),
            "국어" to 0xFF3B82F6.toInt(),
            "수학" to 0xFFEF4444.toInt(),
            "영어" to 0xFFF59E0B.toInt(),
            "탐구" to 0xFF10B981.toInt(),
            "기타" to 0xFF8B5CF6.toInt()
        )
        StudentProfileType.ELEMENTARY -> listOf(
            "성경" to 0xFF0D9488.toInt(),
            "국어·독서" to 0xFF60A5FA.toInt(),
            "수학·연산" to 0xFFF87171.toInt(),
            "영어" to 0xFFFBBF24.toInt(),
            "숙제" to 0xFF34D399.toInt()
        )
    }
}

data class PlanCorrectionItem(
    val subject: String,
    val beforeText: String,
    val afterText: String,
    val missingTag: String,
    val targetMinutes: Int,
    val completed: Boolean
)

data class TimeBlock(
    val startHour: Int,
    val startMinute: Int,
    val durationMinutes: Int,
    val subject: String,
    val studyMode: StudyMode,
    val label: String
)

data class SubjectTimeBreakdown(
    val subject: String,
    val minutes: Int,
    val colorHex: Int
)

/**
 * 일일 플래너 사진 검사 및 모범 교정 결과 모델.
 */
data class DailyInspectionResult(
    val id: String,
    val studentType: StudentProfileType,
    val studentName: String,
    val dateIso: String,
    val habitScore: Int,
    val gradeLabel: String,
    val praiseOneLiner: String,
    val improvementSummary: String,
    val corrections: List<PlanCorrectionItem>,
    val timeBlocks: List<TimeBlock>,
    val totalStudyMinutes: Int,
    val selfStudyMinutes: Int,
    val lectureMinutes: Int,
    val reviewMinutes: Int,
    val plannedTasksCount: Int,
    val completedTasksCount: Int,
    val weakHabitTags: List<String>,
    val copywritingPromise: String,
    val copywritingPractice: String,
    val bibleCompleted: Boolean = false,
    val biblePassageText: String = "",
    val bibleChaptersCount: Int = 0,
    val keptYesterdayPromise: Boolean = false,
    val focusHabitKey: String = FocusHabitType.BIBLE_AND_VOLUME.id,
    val biblePatternSummary: String = "",
    val bibleNextSuggestion: String = "",
    val nanoBananaImageBase64: String? = null,
    val syncedToDrive: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val completionRatePercent: Int
        get() = if (plannedTasksCount <= 0) 0 else ((completedTasksCount * 100f) / plannedTasksCount).toInt().coerceIn(0, 100)

    fun subjectBreakdowns(): List<SubjectTimeBreakdown> {
        val grouped = linkedMapOf<String, Int>()
        timeBlocks.forEach { block ->
            grouped[block.subject] = (grouped[block.subject] ?: 0) + block.durationMinutes
        }
        if (grouped.isEmpty()) {
            corrections.forEach { item ->
                grouped[item.subject] = (grouped[item.subject] ?: 0) + item.targetMinutes
            }
        }
        return grouped.entries
            .sortedByDescending { it.value }
            .map { (subject, mins) ->
                SubjectTimeBreakdown(
                    subject = subject,
                    minutes = mins,
                    colorHex = SubjectPalette.colorHexFor(studentType, subject)
                )
            }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("studentType", studentType.id)
        put("studentName", studentName)
        put("dateIso", dateIso)
        put("habitScore", habitScore)
        put("gradeLabel", gradeLabel)
        put("praiseOneLiner", praiseOneLiner)
        put("improvementSummary", improvementSummary)
        put("totalStudyMinutes", totalStudyMinutes)
        put("selfStudyMinutes", selfStudyMinutes)
        put("lectureMinutes", lectureMinutes)
        put("reviewMinutes", reviewMinutes)
        put("plannedTasksCount", plannedTasksCount)
        put("completedTasksCount", completedTasksCount)
        put("copywritingPromise", copywritingPromise)
        put("copywritingPractice", copywritingPractice)
        put("bibleCompleted", bibleCompleted)
        put("biblePassageText", biblePassageText)
        put("bibleChaptersCount", bibleChaptersCount)
        put("keptYesterdayPromise", keptYesterdayPromise)
        put("focusHabitKey", focusHabitKey)
        put("biblePatternSummary", biblePatternSummary)
        put("bibleNextSuggestion", bibleNextSuggestion)
        if (!nanoBananaImageBase64.isNullOrBlank()) {
            put("nanoBananaImageBase64", nanoBananaImageBase64)
        }
        put("syncedToDrive", syncedToDrive)
        put("createdAtEpochMs", createdAtEpochMs)
        put("weakHabitTags", JSONArray(weakHabitTags))
        put("corrections", JSONArray().apply {
            corrections.forEach { c ->
                put(JSONObject().apply {
                    put("subject", c.subject)
                    put("beforeText", c.beforeText)
                    put("afterText", c.afterText)
                    put("missingTag", c.missingTag)
                    put("targetMinutes", c.targetMinutes)
                    put("completed", c.completed)
                })
            }
        })
        put("timeBlocks", JSONArray().apply {
            timeBlocks.forEach { b ->
                put(JSONObject().apply {
                    put("startHour", b.startHour)
                    put("startMinute", b.startMinute)
                    put("durationMinutes", b.durationMinutes)
                    put("subject", b.subject)
                    put("studyMode", b.studyMode.id)
                    put("label", b.label)
                })
            }
        })
    }

    companion object {
        fun fromJson(obj: JSONObject): DailyInspectionResult {
            val profile = StudentProfileType.fromId(obj.optString("studentType"))
            val tagsArr = obj.optJSONArray("weakHabitTags") ?: JSONArray()
            val tags = buildList {
                for (i in 0 until tagsArr.length()) add(tagsArr.optString(i))
            }
            val corrArr = obj.optJSONArray("corrections") ?: JSONArray()
            val corrs = buildList {
                for (i in 0 until corrArr.length()) {
                    val c = corrArr.getJSONObject(i)
                    add(
                        PlanCorrectionItem(
                            subject = c.optString("subject", "기타"),
                            beforeText = c.optString("beforeText", ""),
                            afterText = c.optString("afterText", ""),
                            missingTag = c.optString("missingTag", "#구체화필요"),
                            targetMinutes = c.optInt("targetMinutes", 30),
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
                            startHour = b.optInt("startHour", profile.startHour),
                            startMinute = b.optInt("startMinute", 0),
                            durationMinutes = b.optInt("durationMinutes", 30),
                            subject = b.optString("subject", "기타"),
                            studyMode = StudyMode.fromId(b.optString("studyMode")),
                            label = b.optString("label", "")
                        )
                    )
                }
            }
            return DailyInspectionResult(
                id = obj.optString("id"),
                studentType = profile,
                studentName = obj.optString("studentName", profile.displayName),
                dateIso = obj.optString("dateIso", ""),
                habitScore = obj.optInt("habitScore", 75),
                gradeLabel = obj.optString("gradeLabel", "B+"),
                praiseOneLiner = obj.optString("praiseOneLiner", ""),
                improvementSummary = obj.optString("improvementSummary", ""),
                corrections = corrs,
                timeBlocks = blocks,
                totalStudyMinutes = obj.optInt("totalStudyMinutes", 0),
                selfStudyMinutes = obj.optInt("selfStudyMinutes", 0),
                lectureMinutes = obj.optInt("lectureMinutes", 0),
                reviewMinutes = obj.optInt("reviewMinutes", 0),
                plannedTasksCount = obj.optInt("plannedTasksCount", corrs.size.coerceAtLeast(1)),
                completedTasksCount = obj.optInt("completedTasksCount", 0),
                weakHabitTags = tags,
                copywritingPromise = obj.optString("copywritingPromise", ""),
                copywritingPractice = obj.optString("copywritingPractice", ""),
                bibleCompleted = obj.optBoolean("bibleCompleted", false),
                biblePassageText = obj.optString("biblePassageText", ""),
                bibleChaptersCount = obj.optInt("bibleChaptersCount", 0),
                keptYesterdayPromise = obj.optBoolean("keptYesterdayPromise", false),
                focusHabitKey = obj.optString("focusHabitKey", FocusHabitType.BIBLE_AND_VOLUME.id),
                biblePatternSummary = obj.optString("biblePatternSummary", ""),
                bibleNextSuggestion = obj.optString("bibleNextSuggestion", ""),
                nanoBananaImageBase64 = obj.optString("nanoBananaImageBase64", "").ifBlank { null },
                syncedToDrive = obj.optBoolean("syncedToDrive", false),
                createdAtEpochMs = obj.optLong("createdAtEpochMs", System.currentTimeMillis())
            )
        }
    }
}

data class DailyBarPoint(
    val dayLabel: String,
    val dateIso: String,
    val minutes: Int,
    val completionPercent: Int,
    val bibleDone: Boolean = false,
    val bibleChapters: Int = 0
)

data class NextWeekGoal(
    val title: String,
    val targetMetric: String,
    val actionTip: String
)

/**
 * 주간 공부 분석 & 예측 인포그래픽 리포트 모델.
 */
data class WeeklyInsightReport(
    val studentType: StudentProfileType,
    val studentName: String,
    val weekLabel: String,
    val startDateIso: String,
    val endDateIso: String,
    val totalAccumulatedDays: Int,
    val thisWeekDaysCount: Int,
    val stage: AccumulationStage,
    val totalStudyMinutes: Int,
    val prevWeekDeltaMinutes: Int?,
    val selfStudyRatioPercent: Int,
    val completionRatePercent: Int,
    val avgHabitScore: Int,
    val bibleDaysCount: Int = 0,
    val bibleChaptersTotal: Int = 0,
    val bibleSummaryLine: String = "",
    val dailyBars: List<DailyBarPoint>,
    val subjectBreakdowns: List<SubjectTimeBreakdown>,
    val topWeakTags: List<Pair<String, Int>>,
    val bestPatternInsight: String,
    val warningHabitInsight: String,
    val overPlanningDiagnosis: String,
    val predictionInsight: String,
    val nextWeekTargetDailyMinutes: Int,
    val nextWeekGoals: List<NextWeekGoal>,
    val weeklyCopywritingPromise: String
)
