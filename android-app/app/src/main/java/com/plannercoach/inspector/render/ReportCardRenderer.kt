package com.plannercoach.inspector.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.plannercoach.inspector.model.DailyInspectionResult
import com.plannercoach.inspector.model.StudentProfileType
import com.plannercoach.inspector.model.StudyMode
import com.plannercoach.inspector.model.SubjectPalette
import com.plannercoach.inspector.model.WeeklyInsightReport
import kotlin.math.max

/**
 * 한글 깨짐 없이 고해상도(1080px 폭) 카드형 인포그래픽 이미지를 생성하는 렌더러.
 * 일일 수정안 카드(타임테이블 + Before/After + 베껴쓰기 박스)와
 * 주간 누적 분석·예측 인포그래픽 카드를 생성하며, 카톡 공유·PDF 저장·인쇄에 공용 사용된다.
 */
object ReportCardRenderer {

    private const val CARD_WIDTH = 1080
    private const val OUTER_PAD = 42f
    private const val INNER_PAD = 32f

    private val BG_CANVAS = Color.parseColor("#F1F5F9")
    private val CARD_WHITE = Color.parseColor("#FFFFFF")
    private val BORDER_LIGHT = Color.parseColor("#E2E8F0")
    private val TEXT_PRIMARY = Color.parseColor("#0F172A")
    private val TEXT_SECONDARY = Color.parseColor("#475569")
    private val TEXT_MUTED = Color.parseColor("#94A3B8")
    private val ACCENT_BLUE = Color.parseColor("#2563EB")
    private val ACCENT_INDIGO = Color.parseColor("#4F46E5")
    private val ACCENT_EMERALD = Color.parseColor("#059669")
    private val ACCENT_AMBER = Color.parseColor("#D97706")
    private val ACCENT_RED = Color.parseColor("#DC2626")
    private val NOTE_BG = Color.parseColor("#FFFBEB")
    private val NOTE_BORDER = Color.parseColor("#FDE68A")
    private val NOTE_LINE = Color.parseColor("#F59E0B")

    fun renderDailyReportBitmap(
        result: DailyInspectionResult,
        includePracticeLines: Boolean = false
    ): Bitmap {
        val contentWidth = CARD_WIDTH - OUTER_PAD * 2
        val innerWidth = (contentWidth - INNER_PAD * 2).toInt()

        // Measure dynamic text heights first so nothing ever overflows
        val praiseLayout = buildLayout(result.praiseOneLiner, 28f, TEXT_PRIMARY, false, innerWidth - 190)
        val improveLayout = buildLayout(result.improvementSummary, 25f, TEXT_SECONDARY, false, innerWidth)

        val correctionHeights = result.corrections.map { item ->
            val beforeLayout = buildLayout("✕ ${item.beforeText}", 25f, TEXT_SECONDARY, false, innerWidth - 44)
            val afterLayout = buildLayout("✓ ${item.afterText} (${item.targetMinutes}분)", 27f, TEXT_PRIMARY, true, innerWidth - 44)
            70f + beforeLayout.height + afterLayout.height + 28f
        }
        val correctionsCardHeight = 96f + correctionHeights.sum().coerceAtLeast(80f) + 20f

        val totalHours = result.studentType.endHour - result.studentType.startHour
        val rowHeight = if (result.studentType == StudentProfileType.HIGH_SCHOOL) 44f else 62f
        val timetableCardHeight = 148f + (totalHours * rowHeight) + 170f

        val promiseLayout = buildLayout("[오늘의 약속] ${result.copywritingPromise}", 28f, TEXT_PRIMARY, true, innerWidth - 44)
        val practiceLayout = buildLayout("[수정 & 내일 예약 연습] ${result.copywritingPractice}", 26f, ACCENT_BLUE, false, innerWidth - 44)
        val extraPracticeLinesH = if (includePracticeLines) 210f else 0f
        val copyCardHeight = 110f + promiseLayout.height + practiceLayout.height + 70f + extraPracticeLinesH

        val baseBibleText = if (result.bibleCompleted) {
            "✅ ${result.biblePassageText.ifBlank { "오늘 성경읽기 완료" }} (${result.bibleChaptersCount}장)"
        } else {
            "⏳ 미완료 — ${result.biblePassageText.ifBlank { "오늘 읽을 성경 본문을 플래너에 적어 보세요!" }}"
        }
        val bibleDetailLines = buildString {
            append(baseBibleText)
            if (result.biblePatternSummary.isNotBlank()) {
                append("\n📊 패턴: ${result.biblePatternSummary}")
            }
            if (result.bibleNextSuggestion.isNotBlank()) {
                append("\n👉 내일 제안: ${result.bibleNextSuggestion}")
            }
        }
        val bibleLayout = buildLayout(bibleDetailLines, 23f, TEXT_PRIMARY, true, innerWidth - 36)
        val bibleCardHeight = 84f + bibleLayout.height

        val nanoBitmap = result.nanoBananaImageBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching {
                val clean = b64.substringAfter("base64,", b64)
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        val nanoImgDrawH = if (nanoBitmap != null && nanoBitmap.width > 0) {
            ((innerWidth.toFloat() / nanoBitmap.width) * nanoBitmap.height).coerceIn(240f, 680f)
        } else 0f
        val nanoCardHeight = if (nanoBitmap != null) 118f + nanoImgDrawH else 0f

        val headerCardHeight = 264f + max(praiseLayout.height, 40) + improveLayout.height
        val totalHeight = (OUTER_PAD + headerCardHeight + 24f + bibleCardHeight + 24f + correctionsCardHeight + 24f + timetableCardHeight + 24f + (if (nanoBitmap != null) nanoCardHeight + 24f else 0f) + copyCardHeight + 70f).toInt()

        val bitmap = Bitmap.createBitmap(CARD_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_CANVAS)

        var y = OUTER_PAD

        // 1. Header & Score Gauge Card
        drawCard(canvas, OUTER_PAD, y, CARD_WIDTH - OUTER_PAD, y + headerCardHeight)
        val badgeText = "${result.studentType.badgeEmoji} ${result.studentName} · ${result.dateIso} 플래너 리포트"
        val mainBw = drawPillBadge(
            canvas,
            OUTER_PAD + INNER_PAD,
            y + 28f,
            badgeText,
            if (result.studentType == StudentProfileType.HIGH_SCHOOL) Color.parseColor("#DBEAFE") else Color.parseColor("#D1FAE5"),
            if (result.studentType == StudentProfileType.HIGH_SCHOOL) ACCENT_BLUE else ACCENT_EMERALD
        )
        if (result.keptYesterdayPromise) {
            drawPillBadge(
                canvas,
                OUTER_PAD + INNER_PAD + mainBw + 10f,
                y + 28f,
                "🎉 어제 약속 이행 (+10점)",
                Color.parseColor("#FEF3C7"),
                ACCENT_AMBER
            )
        }

        // Score Donut Gauge on right
        val gaugeCx = CARD_WIDTH - OUTER_PAD - INNER_PAD - 72f
        val gaugeCy = y + 122f
        drawScoreDonut(canvas, gaugeCx, gaugeCy, 62f, result.habitScore, "${result.habitScore}점", result.gradeLabel)

        // Praise + summary
        drawText(canvas, "오늘의 잘한 점", OUTER_PAD + INNER_PAD, y + 94f, 24f, ACCENT_BLUE, true)
        drawLayout(canvas, praiseLayout, OUTER_PAD + INNER_PAD, y + 128f)

        val summaryY = y + 150f + praiseLayout.height
        drawDivider(canvas, OUTER_PAD + INNER_PAD, summaryY, CARD_WIDTH - OUTER_PAD - INNER_PAD)
        drawText(canvas, "핵심 수정 방향", OUTER_PAD + INNER_PAD, summaryY + 22f, 24f, ACCENT_AMBER, true)
        drawLayout(canvas, improveLayout, OUTER_PAD + INNER_PAD, summaryY + 56f)

        // Weak tags row
        var tagX = OUTER_PAD + INNER_PAD
        val tagY = summaryY + 72f + improveLayout.height
        result.weakHabitTags.take(4).forEach { tag ->
            val w = drawPillBadge(canvas, tagX, tagY, tag, Color.parseColor("#FEE2E2"), ACCENT_RED)
            tagX += w + 12f
        }

        y += headerCardHeight + 24f

        // 1.5 Daily Bible Reading Habit Card
        val bibleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (result.bibleCompleted) "#F0FDFA" else "#FFFBEB")
        }
        val bibleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (result.bibleCompleted) "#5EEAD4" else "#FCD34D")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val bibleRect = RectF(OUTER_PAD, y, CARD_WIDTH - OUTER_PAD, y + bibleCardHeight)
        canvas.drawRoundRect(bibleRect, 24f, 24f, bibleBg)
        canvas.drawRoundRect(bibleRect, 24f, 24f, bibleBorder)

        val bibleTitleCol = Color.parseColor(if (result.bibleCompleted) "#0F766E" else "#B45309")
        val bibleSrc = if (result.studentType == StudentProfileType.HIGH_SCHOOL) "bible71 연동" else "종이 플래너 기록"
        drawText(canvas, "📖 매일 성경읽기 습관 ($bibleSrc)", OUTER_PAD + INNER_PAD, y + 22f, 26f, bibleTitleCol, true)
        drawLayout(canvas, bibleLayout, OUTER_PAD + INNER_PAD, y + 62f)

        y += bibleCardHeight + 24f

        // 2. Before & After Corrections Card
        drawCard(canvas, OUTER_PAD, y, CARD_WIDTH - OUTER_PAD, y + correctionsCardHeight)
        drawText(canvas, "1. 계획 수정안 (Before → After)", OUTER_PAD + INNER_PAD, y + 34f, 30f, TEXT_PRIMARY, true)
        var cy = y + 88f
        if (result.corrections.isEmpty()) {
            drawText(canvas, "작성된 항목을 바탕으로 아래 시간표를 참고해 계획을 구체화해 보세요.", OUTER_PAD + INNER_PAD, cy, 26f, TEXT_SECONDARY, false)
        } else {
            result.corrections.forEachIndexed { idx, item ->
                val beforeL = buildLayout("✕ ${item.beforeText}", 25f, TEXT_SECONDARY, false, innerWidth - 44)
                val afterL = buildLayout("✓ ${item.afterText} (${item.targetMinutes}분)", 27f, TEXT_PRIMARY, true, innerWidth - 44)
                val boxH = correctionHeights[idx] - 14f
                val subjColor = SubjectPalette.colorHexFor(result.studentType, item.subject)

                val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC") }
                val boxRect = RectF(OUTER_PAD + INNER_PAD, cy, CARD_WIDTH - OUTER_PAD - INNER_PAD, cy + boxH)
                canvas.drawRoundRect(boxRect, 18f, 18f, boxPaint)

                // Left color bar
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = subjColor }
                canvas.drawRoundRect(RectF(boxRect.left, boxRect.top, boxRect.left + 10f, boxRect.bottom), 6f, 6f, barPaint)

                val badgeW = drawPillBadge(canvas, boxRect.left + 26f, cy + 16f, item.subject, withAlpha(subjColor, 40), subjColor)
                drawPillBadge(canvas, boxRect.left + 38f + badgeW, cy + 16f, item.missingTag, Color.parseColor("#FEF3C7"), ACCENT_AMBER)

                drawLayout(canvas, beforeL, boxRect.left + 26f, cy + 64f, strikethrough = true)
                drawLayout(canvas, afterL, boxRect.left + 26f, cy + 72f + beforeL.height)

                cy += correctionHeights[idx]
            }
        }

        y += correctionsCardHeight + 24f

        // 3. Color-coded Timetable Card
        drawCard(canvas, OUTER_PAD, y, CARD_WIDTH - OUTER_PAD, y + timetableCardHeight)
        val ttTitle = if (result.studentType == StudentProfileType.HIGH_SCHOOL)
            "2. 색상별 모범 타임테이블 (10분 단위 · 순공/인강/복습 구분)"
        else
            "2. 무지개 색상 모범 시간표 (30분 블록)"
        drawText(canvas, ttTitle, OUTER_PAD + INNER_PAD, y + 34f, 30f, TEXT_PRIMARY, true)

        // Study mode guide line
        val modeGuide = if (result.studentType == StudentProfileType.HIGH_SCHOOL) {
            "진한 색 = 자습·문제풀이  |  연한 색[인강] = 수업·인강  |  ★ = 오답·복습"
        } else {
            "색깔 블록을 보며 한 과목씩 30분씩 칠하고, 끝나면 ★ 표시를 해보세요!"
        }
        drawText(canvas, modeGuide, OUTER_PAD + INNER_PAD, y + 78f, 22f, TEXT_SECONDARY, false)

        val gridTop = y + 124f
        val gridLeft = OUTER_PAD + INNER_PAD + 76f
        val gridRight = CARD_WIDTH - OUTER_PAD - INNER_PAD
        val gridWidth = gridRight - gridLeft
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER_LIGHT
            strokeWidth = 2f
        }
        val slotDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F1F5F9")
            strokeWidth = 1.5f
        }
        val slotsPerHour = 60 / result.studentType.slotMinutes

        for (hIndex in 0 until totalHours) {
            val hour = result.studentType.startHour + hIndex
            val rowTop = gridTop + hIndex * rowHeight
            val rowBottom = rowTop + rowHeight

            // Hour label
            drawText(canvas, String.format("%02d:00", hour), OUTER_PAD + INNER_PAD, rowTop + rowHeight * 0.28f, 22f, TEXT_SECONDARY, true)

            // Row background
            val rowBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FAFCFF") }
            canvas.drawRect(gridLeft, rowTop, gridRight, rowBottom, rowBg)

            // Slot vertical dividers
            val slotW = gridWidth / slotsPerHour.toFloat()
            for (s in 1 until slotsPerHour) {
                val sx = gridLeft + s * slotW
                canvas.drawLine(sx, rowTop, sx, rowBottom, slotDividerPaint)
            }
            canvas.drawLine(gridLeft, rowTop, gridRight, rowTop, gridPaint)
        }
        val gridBottom = gridTop + totalHours * rowHeight
        canvas.drawLine(gridLeft, gridBottom, gridRight, gridBottom, gridPaint)
        canvas.drawLine(gridLeft, gridTop, gridLeft, gridBottom, gridPaint)
        canvas.drawLine(gridRight, gridTop, gridRight, gridBottom, gridPaint)

        // Draw colored time blocks
        result.timeBlocks.forEach { block ->
            val startTotalMins = block.startHour * 60 + block.startMinute
            val endTotalMins = startTotalMins + block.durationMinutes
            var curMin = startTotalMins
            while (curMin < endTotalMins) {
                val curHour = curMin / 60
                val minuteInHour = curMin % 60
                val nextHourStart = (curHour + 1) * 60
                val segEnd = minOf(endTotalMins, nextHourStart)
                val rowIdx = curHour - result.studentType.startHour
                if (rowIdx in 0 until totalHours) {
                    val rowTop = gridTop + rowIdx * rowHeight + 4f
                    val rowBottom = gridTop + (rowIdx + 1) * rowHeight - 4f
                    val left = gridLeft + (minuteInHour / 60f) * gridWidth + 2f
                    val right = gridLeft + ((segEnd - curHour * 60) / 60f) * gridWidth - 2f

                    val baseColor = SubjectPalette.colorHexFor(result.studentType, block.subject)
                    val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = when (block.studyMode) {
                            StudyMode.SELF_STUDY -> baseColor
                            StudyMode.LECTURE -> withAlpha(baseColor, 130)
                            StudyMode.REVIEW -> baseColor
                        }
                    }
                    val rect = RectF(left, rowTop, right, rowBottom)
                    canvas.drawRoundRect(rect, 8f, 8f, blockPaint)

                    if (block.studyMode == StudyMode.REVIEW) {
                        val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#FDE047")
                            style = Paint.Style.STROKE
                            strokeWidth = 3.5f
                        }
                        canvas.drawRoundRect(rect, 8f, 8f, borderP)
                    }

                    // Label inside block if wide enough
                    if (right - left > 70f) {
                        val prefix = when (block.studyMode) {
                            StudyMode.LECTURE -> "[인강] "
                            StudyMode.REVIEW -> "★ "
                            StudyMode.SELF_STUDY -> ""
                        }
                        val labelColor = if (block.studyMode == StudyMode.LECTURE) TEXT_PRIMARY else Color.WHITE
                        val shortLabel = "$prefix${block.subject}"
                        drawText(canvas, shortLabel, left + 10f, rowTop + (rowHeight - 28f) / 2f, 20f, labelColor, true)
                    }
                }
                curMin = segEnd
            }
        }

        // Stacked subject ratio bar below timetable
        val barTop = gridBottom + 28f
        drawText(
            canvas,
            "총 ${formatMinutes(result.totalStudyMinutes)} (자습 ${formatMinutes(result.selfStudyMinutes)} · 인강 ${formatMinutes(result.lectureMinutes)} · 복습 ${formatMinutes(result.reviewMinutes)})",
            OUTER_PAD + INNER_PAD,
            barTop,
            24f,
            TEXT_PRIMARY,
            true
        )
        drawSubjectStackedBar(canvas, OUTER_PAD + INNER_PAD, barTop + 38f, innerWidth.toFloat(), 26f, result.subjectBreakdowns())

        // Legend chips
        var legX = OUTER_PAD + INNER_PAD
        val legY = barTop + 80f
        SubjectPalette.legendItems(result.studentType).forEach { (name, color) ->
            val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color }
            canvas.drawCircle(legX + 10f, legY + 14f, 9f, dotP)
            drawText(canvas, name, legX + 26f, legY + 2f, 21f, TEXT_SECONDARY, false)
            legX += measureTextWidth(name, 21f) + 52f
        }

        y += timetableCardHeight + 24f

        // 3.5 Nano Banana Basic Color-Annotated Planner Example Card
        if (nanoBitmap != null) {
            drawCard(canvas, OUTER_PAD, y, CARD_WIDTH - OUTER_PAD, y + nanoCardHeight)
            drawText(
                canvas,
                "2.5 🍌 나노바나나 기본형 색상 교정 예시 (gemini-nano-banana-2.1)",
                OUTER_PAD + INNER_PAD,
                y + 32f,
                27f,
                ACCENT_INDIGO,
                true
            )
            val dstRect = RectF(
                OUTER_PAD + INNER_PAD,
                y + 84f,
                CARD_WIDTH - OUTER_PAD - INNER_PAD,
                y + 84f + nanoImgDrawH
            )
            val srcRect = Rect(0, 0, nanoBitmap.width, nanoBitmap.height)
            canvas.drawBitmap(nanoBitmap, srcRect, dstRect, null)
            y += nanoCardHeight + 24f
        }

        // 4. Handwriting Copywriting Practice Card
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NOTE_BG }
        val noteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NOTE_BORDER
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val noteRect = RectF(OUTER_PAD, y, CARD_WIDTH - OUTER_PAD, y + copyCardHeight)
        canvas.drawRoundRect(noteRect, 26f, 26f, notePaint)
        canvas.drawRoundRect(noteRect, 26f, 26f, noteBorderPaint)

        drawText(canvas, "3. ✍️ 플래너 하단에 그대로 베껴쓰기 (1분 습관 각인)", OUTER_PAD + INNER_PAD, y + 32f, 28f, ACCENT_AMBER, true)
        val innerBoxRect = RectF(OUTER_PAD + INNER_PAD, y + 84f, CARD_WIDTH - OUTER_PAD - INNER_PAD, y + copyCardHeight - 28f - extraPracticeLinesH)
        val innerBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(innerBoxRect, 18f, 18f, innerBoxPaint)

        drawLayout(canvas, promiseLayout, innerBoxRect.left + 22f, innerBoxRect.top + 22f)
        drawLayout(canvas, practiceLayout, innerBoxRect.left + 22f, innerBoxRect.top + 38f + promiseLayout.height)

        if (includePracticeLines) {
            var lineY = innerBoxRect.bottom + 34f
            drawText(canvas, "✏️ 아래 빈칸에 직접 손으로 따라 써 보세요:", OUTER_PAD + INNER_PAD, lineY, 22f, ACCENT_AMBER, true)
            val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = NOTE_LINE
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
            }
            for (lineIdx in 1..3) {
                val ly = lineY + lineIdx * 52f
                canvas.drawLine(OUTER_PAD + INNER_PAD, ly, CARD_WIDTH - OUTER_PAD - INNER_PAD, ly, dashPaint)
            }
        }

        nanoBitmap?.let { if (!it.isRecycled) it.recycle() }
        drawText(canvas, "플래너 코치 AI · Daily Study Habit System", OUTER_PAD, totalHeight - 42f, 20f, TEXT_MUTED, false)
        return bitmap
    }

    fun renderWeeklyReportBitmap(
        report: WeeklyInsightReport,
        includePracticeLines: Boolean = false
    ): Bitmap {
        val contentWidth = CARD_WIDTH - OUTER_PAD * 2
        val innerWidth = (contentWidth - INNER_PAD * 2).toInt()

        val bestL = buildLayout("💡 이번 주 베스트: ${report.bestPatternInsight}", 25f, TEXT_PRIMARY, false, innerWidth - 36)
        val warnL = buildLayout("⚠️ 습관 코칭: ${report.warningHabitInsight}", 25f, TEXT_PRIMARY, false, innerWidth - 36)
        val overL = buildLayout("⚖️ 과욕지수 진단: ${report.overPlanningDiagnosis}", 25f, TEXT_PRIMARY, false, innerWidth - 36)
        val predL = buildLayout("🔮 데이터 예측: ${report.predictionInsight}", 26f, ACCENT_INDIGO, true, innerWidth - 36)

        val insightCardH = 120f + bestL.height + warnL.height + overL.height + predL.height + 100f

        val goalLayouts = report.nextWeekGoals.map { goal ->
            buildLayout("${goal.title} (${goal.targetMetric})\n${goal.actionTip}", 24f, TEXT_PRIMARY, false, innerWidth - 44)
        }
        val copyL = buildLayout("[다음 주 다짐] ${report.weeklyCopywritingPromise}", 27f, TEXT_PRIMARY, true, innerWidth - 44)
        val extraPracticeLinesH = if (includePracticeLines) 150f else 0f
        val goalsCardH = 110f + goalLayouts.sumOf { it.height + 40 }.toFloat() + copyL.height + 90f + extraPracticeLinesH

        val heroCardH = 330f
        val chartCardH = 515f
        val totalHeight = (OUTER_PAD + heroCardH + 24f + chartCardH + 24f + insightCardH + 24f + goalsCardH + 70f).toInt()

        val bitmap = Bitmap.createBitmap(CARD_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_CANVAS)

        var y = OUTER_PAD

        // 1. Hero Metrics Card
        drawCard(canvas, OUTER_PAD, y, CARD_WIDTH - OUTER_PAD, y + heroCardH)
        val bw = drawPillBadge(
            canvas,
            OUTER_PAD + INNER_PAD,
            y + 28f,
            "${report.studentType.badgeEmoji} ${report.studentName} · ${report.weekLabel} 주간 분석",
            Color.parseColor("#EEF2FF"),
            ACCENT_INDIGO
        )
        val bw2 = drawPillBadge(
            canvas,
            OUTER_PAD + INNER_PAD + bw + 12f,
            y + 28f,
            report.stage.badgeText,
            Color.parseColor("#ECFDF5"),
            ACCENT_EMERALD
        )
        drawPillBadge(
            canvas,
            OUTER_PAD + INNER_PAD + bw + bw2 + 24f,
            y + 28f,
            "📖 성경 ${report.bibleDaysCount}/7일 (${report.bibleChaptersTotal}장)",
            Color.parseColor("#CCFBF1"),
            Color.parseColor("#0F766E")
        )

        // 3 Hero Columns
        val colW = innerWidth / 3f
        val colTop = y + 98f

        // Col 1: Total Study Time
        drawText(canvas, "총 공부 시간", OUTER_PAD + INNER_PAD, colTop, 23f, TEXT_SECONDARY, true)
        drawText(canvas, formatMinutes(report.totalStudyMinutes), OUTER_PAD + INNER_PAD, colTop + 40f, 44f, TEXT_PRIMARY, true)
        val deltaTxt = report.prevWeekDeltaMinutes?.let {
            if (it >= 0) "▲ 지난주보다 +${formatMinutes(it)}" else "▼ 지난주보다 -${formatMinutes(-it)}"
        } ?: "누적 ${report.totalAccumulatedDays}일차 기록"
        val deltaColor = if ((report.prevWeekDeltaMinutes ?: 0) >= 0) ACCENT_EMERALD else ACCENT_RED
        drawText(canvas, deltaTxt, OUTER_PAD + INNER_PAD, colTop + 106f, 22f, deltaColor, true)

        // Col 2: Completion Rate Donut
        val col2Cx = OUTER_PAD + INNER_PAD + colW + colW * 0.5f
        drawScoreDonut(canvas, col2Cx, colTop + 72f, 60f, report.completionRatePercent, "${report.completionRatePercent}%", "계획 달성률")

        // Col 3: Self-Study Ratio & Bible
        val col3X = OUTER_PAD + INNER_PAD + colW * 2f + 16f
        drawText(canvas, "순공 비율 & 성경읽기", col3X, colTop, 23f, TEXT_SECONDARY, true)
        drawText(canvas, "${report.selfStudyRatioPercent}% · 📖${report.bibleDaysCount}일", col3X, colTop + 40f, 38f, ACCENT_BLUE, true)
        drawText(canvas, "평균 플래너 점수 ${report.avgHabitScore}점", col3X, colTop + 106f, 22f, TEXT_SECONDARY, true)

        drawText(
            canvas,
            "📖 주간 말씀 습관: ${report.bibleSummaryLine.ifBlank { "이번 주 ${report.bibleDaysCount}일 / ${report.bibleChaptersTotal}장 완료" }}",
            OUTER_PAD + INNER_PAD,
            colTop + 162f,
            21f,
            Color.parseColor("#0F766E"),
            true
        )

        y += heroCardH + 24f

        // 2. Daily Rhythm Bar Chart + Subject Balance Card
        drawCard(canvas, OUTER_PAD, y, CARD_WIDTH - OUTER_PAD, y + chartCardH)
        drawText(canvas, "요일별 공부 리듬 (📖=성경읽기) & 과목 색상 밸런스", OUTER_PAD + INNER_PAD, y + 32f, 30f, TEXT_PRIMARY, true)

        val chartLeft = OUTER_PAD + INNER_PAD + 20f
        val chartRight = CARD_WIDTH - OUTER_PAD - INNER_PAD - 20f
        val chartBottom = y + 310f
        val chartMaxH = 168f
        val maxMinutes = (report.dailyBars.maxOfOrNull { it.minutes } ?: 120).coerceAtLeast(60)
        val barSlotW = (chartRight - chartLeft) / 7f

        report.dailyBars.forEachIndexed { idx, pt ->
            val barW = barSlotW * 0.52f
            val cx = chartLeft + idx * barSlotW + barSlotW / 2f
            val barH = (pt.minutes / maxMinutes.toFloat()) * chartMaxH
            val barTop = chartBottom - barH

            val barP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (pt.minutes > 0) ACCENT_BLUE else Color.parseColor("#E2E8F0")
            }
            canvas.drawRoundRect(RectF(cx - barW / 2f, barTop.coerceAtMost(chartBottom - 8f), cx + barW / 2f, chartBottom), 12f, 12f, barP)

            val minLabel = if (pt.minutes > 0) formatShortMinutes(pt.minutes) else "-"
            val labelW = measureTextWidth(minLabel, 20f)
            val labelY = (barTop - 30f).coerceAtLeast(y + 92f)
            drawText(canvas, minLabel, cx - labelW / 2f, labelY, 20f, TEXT_SECONDARY, true)
            if (pt.bibleDone) {
                val bW = measureTextWidth("📖", 18f)
                drawText(canvas, "📖", cx - bW / 2f, labelY - 24f, 18f, Color.parseColor("#0D9488"), true)
            }

            val dayW = measureTextWidth(pt.dayLabel, 24f)
            drawText(canvas, pt.dayLabel, cx - dayW / 2f, chartBottom + 14f, 24f, TEXT_PRIMARY, true)
        }

        // Subject distribution bar
        val subjBarY = chartBottom + 76f
        drawText(canvas, "과목별 시간 배분", OUTER_PAD + INNER_PAD, subjBarY, 24f, TEXT_PRIMARY, true)
        drawSubjectStackedBar(canvas, OUTER_PAD + INNER_PAD, subjBarY + 36f, innerWidth.toFloat(), 28f, report.subjectBreakdowns)

        var sx = OUTER_PAD + INNER_PAD
        val sy = subjBarY + 78f
        report.subjectBreakdowns.take(5).forEach { sb ->
            val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.colorHex }
            canvas.drawCircle(sx + 10f, sy + 13f, 9f, dotP)
            val txt = "${sb.subject} ${formatShortMinutes(sb.minutes)}"
            drawText(canvas, txt, sx + 26f, sy, 21f, TEXT_SECONDARY, false)
            sx += measureTextWidth(txt, 21f) + 46f
        }

        y += chartCardH + 24f

        // 3. AI Insights & Predictive Analytics Card
        drawCard(canvas, OUTER_PAD, y, CARD_WIDTH - OUTER_PAD, y + insightCardH)
        drawText(canvas, "AI 학습 분석 & 데이터 예측 (${report.stage.title})", OUTER_PAD + INNER_PAD, y + 32f, 30f, TEXT_PRIMARY, true)
        var iy = y + 90f

        listOf(
            bestL to Color.parseColor("#ECFDF5"),
            warnL to Color.parseColor("#FEF2F2"),
            overL to Color.parseColor("#FFFBEB"),
            predL to Color.parseColor("#EEF2FF")
        ).forEach { (layout, bgCol) ->
            val boxH = layout.height + 28f
            val boxP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgCol }
            canvas.drawRoundRect(RectF(OUTER_PAD + INNER_PAD, iy, CARD_WIDTH - OUTER_PAD - INNER_PAD, iy + boxH), 16f, 16f, boxP)
            drawLayout(canvas, layout, OUTER_PAD + INNER_PAD + 18f, iy + 14f)
            iy += boxH + 14f
        }

        y += insightCardH + 24f

        // 4. Next Week Goals & Weekly Copywriting Card
        drawCard(canvas, OUTER_PAD, y, CARD_WIDTH - OUTER_PAD, y + goalsCardH)
        drawText(canvas, "다음 1주일 맞춤 학습 목표 & 다짐 필사", OUTER_PAD + INNER_PAD, y + 32f, 30f, ACCENT_EMERALD, true)
        var gy = y + 88f
        goalLayouts.forEachIndexed { idx, gl ->
            val boxH = gl.height + 24f
            val boxP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC") }
            canvas.drawRoundRect(RectF(OUTER_PAD + INNER_PAD, gy, CARD_WIDTH - OUTER_PAD - INNER_PAD, gy + boxH), 16f, 16f, boxP)
            drawText(canvas, "${idx + 1}", OUTER_PAD + INNER_PAD + 18f, gy + 12f, 24f, ACCENT_EMERALD, true)
            drawLayout(canvas, gl, OUTER_PAD + INNER_PAD + 48f, gy + 12f)
            gy += boxH + 14f
        }

        val noteRect = RectF(OUTER_PAD + INNER_PAD, gy + 6f, CARD_WIDTH - OUTER_PAD - INNER_PAD, gy + 44f + copyL.height)
        val noteP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NOTE_BG }
        val noteBP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NOTE_BORDER
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRoundRect(noteRect, 16f, 16f, noteP)
        canvas.drawRoundRect(noteRect, 16f, 16f, noteBP)
        drawLayout(canvas, copyL, noteRect.left + 20f, noteRect.top + 18f)

        if (includePracticeLines) {
            val lineStart = noteRect.bottom + 24f
            drawText(canvas, "✏️ 다음 주 첫 페이지에 손으로 따라 적어 보세요:", OUTER_PAD + INNER_PAD, lineStart, 22f, ACCENT_AMBER, true)
            val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = NOTE_LINE
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
            }
            for (lineIdx in 1..2) {
                val ly = lineStart + lineIdx * 50f
                canvas.drawLine(OUTER_PAD + INNER_PAD, ly, CARD_WIDTH - OUTER_PAD - INNER_PAD, ly, dashPaint)
            }
        }

        drawText(canvas, "플래너 코치 AI · Weekly Predictive Report", OUTER_PAD, totalHeight - 42f, 20f, TEXT_MUTED, false)
        return bitmap
    }

    private fun drawCard(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E2E8F0") }
        canvas.drawRoundRect(RectF(left + 2f, top + 6f, right + 2f, bottom + 6f), 28f, 28f, shadow)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_WHITE }
        canvas.drawRoundRect(RectF(left, top, right, bottom), 28f, 28f, bg)
    }

    private fun drawPillBadge(
        canvas: Canvas,
        x: Float,
        y: Float,
        text: String,
        bgColor: Int,
        textColor: Int
    ): Float {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tw = paint.measureText(text)
        val pillW = tw + 34f
        val pillH = 40f
        val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawRoundRect(RectF(x, y, x + pillW, y + pillH), 20f, 20f, bgP)
        canvas.drawText(text, x + 17f, y + 27f, paint)
        return pillW
    }

    private fun drawScoreDonut(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        percent: Int,
        centerTop: String,
        centerBottom: String
    ) {
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }
        val progPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when {
                percent >= 80 -> ACCENT_EMERALD
                percent >= 60 -> ACCENT_BLUE
                else -> ACCENT_AMBER
            }
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 14f
        }
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawOval(oval, trackPaint)
        canvas.drawArc(oval, -90f, (percent.coerceIn(0, 100) / 100f) * 360f, false, progPaint)

        val topW = measureTextWidth(centerTop, 28f)
        drawText(canvas, centerTop, cx - topW / 2f, cy - 22f, 28f, TEXT_PRIMARY, true)
        val botW = measureTextWidth(centerBottom, 20f)
        drawText(canvas, centerBottom, cx - botW / 2f, cy + 12f, 20f, TEXT_SECONDARY, true)
    }

    private fun drawSubjectStackedBar(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        breakdowns: List<com.plannercoach.inspector.model.SubjectTimeBreakdown>
    ) {
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E2E8F0") }
        canvas.drawRoundRect(RectF(x, y, x + width, y + height), height / 2f, height / 2f, trackPaint)

        val total = breakdowns.sumOf { it.minutes }
        if (total <= 0) return

        var curX = x
        breakdowns.forEach { sb ->
            val segW = (sb.minutes / total.toFloat()) * width
            if (segW > 2f) {
                val segP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sb.colorHex }
                canvas.drawRoundRect(RectF(curX, y, curX + segW - 2f, y + height), 8f, 8f, segP)
                curX += segW
            }
        }
    }

    private fun drawDivider(canvas: Canvas, left: Float, y: Float, right: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER_LIGHT
            strokeWidth = 2f
        }
        canvas.drawLine(left, y, right, y, p)
    }

    private fun buildLayout(text: String, sizeSp: Float, color: Int, bold: Boolean, widthPx: Int): StaticLayout {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = sizeSp
            this.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, widthPx.coerceAtLeast(100))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1.0f)
            .build()
    }

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float, strikethrough: Boolean = false) {
        layout.paint.isStrikeThruText = strikethrough
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, yTop: Float, size: Float, color: Int, bold: Boolean) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = size
            this.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val fm = p.fontMetrics
        canvas.drawText(text, x, yTop - fm.ascent, p)
    }

    private fun measureTextWidth(text: String, size: Float): Float {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        return p.measureText(text)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun formatMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}시간 ${m}분"
            h > 0 -> "${h}시간"
            else -> "${m}분"
        }
    }

    private fun formatShortMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}
