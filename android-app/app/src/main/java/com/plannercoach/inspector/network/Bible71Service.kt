package com.plannercoach.inspector.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class Bible71LogItem(
    val id: Long,
    val date: String,
    val category: String,
    val itemId: String,
    val title: String,
    val passage: String,
    val chaptersCount: Int,
    val completedAt: String = ""
)

data class BibleDayStatus(
    val source: String,
    val completed: Boolean,
    val chaptersCount: Int,
    val passageText: String
)

data class BibleReadingPattern(
    val totalDays: Int,
    val avgIntervalDays: Double,
    val avgChaptersPerSession: Int,
    val strongDaysText: String,
    val weakDaysText: String,
    val preferredTimeSlot: String,
    val patternSummary: String,
    val nextDaySuggestion: String
)

data class BibleBookMeta(val no: Int, val name: String, val chapters: Int)

/**
 * /Users/kbd/Desktop/bible71 프로젝트와 동일한 Supabase reading_logs 테이블 및
 * 맥체인 365일 매핑(assets/mccheyne_365_map.json)을 읽어 김희균 학생의 성경읽기 분량을 조회한다.
 */
class Bible71Service(private val context: Context) {

    private val mccheyneMap: JSONObject by lazy {
        runCatching {
            val raw = context.assets.open("mccheyne_365_map.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(raw)
        }.getOrElse { JSONObject() }
    }

    fun lookupMcCheyneByDateIso(dateIso: String): Pair<String, Int>? {
        if (dateIso.length < 10) return null
        val mmdd = dateIso.substring(5, 10)
        val entry = mccheyneMap.optJSONObject(mmdd) ?: return null
        val passages = entry.optString("passages", "")
        val dateLabel = entry.optString("date", mmdd)
        val chapters = entry.optInt("chaptersCount", 4)
        return "맥체인 ($dateLabel): $passages" to chapters
    }

    suspend fun fetchLogsForStudent(
        memberName: String = "김희균",
        memberPhone: String = "01023708994"
    ): List<Bible71LogItem> = withContext(Dispatchers.IO) {
        val digits = memberPhone.replace(Regex("[^0-9]"), "").ifBlank { "01023708994" }
        val dashed = if (digits.length == 11) {
            "${digits.substring(0, 3)}-${digits.substring(3, 7)}-${digits.substring(7)}"
        } else digits
        val encName = URLEncoder.encode(memberName.ifBlank { "김희균" }, "UTF-8")
        val orFilter = "or=(member_phone.eq.$digits,member_phone.eq.$dashed,member_name.eq.$encName)"
        val endpoint = "$SUPABASE_URL/rest/v1/reading_logs?$orFilter&is_completed=eq.true&select=*&order=date.desc&limit=500"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("apikey", SUPABASE_ANON_KEY)
            setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
        }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (status !in 200..299) {
            throw IllegalStateException("bible71 DB 오류 (HTTP $status)")
        }

        val arr = JSONArray(body)
        buildList {
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                val cat = row.optString("category", "")
                val dateIso = row.optString("date", "")
                val title = row.optString("title", "")
                val itemId = row.optString("item_id", "")

                val (passage, chapters) = when (cat) {
                    "mccheyne" -> lookupMcCheyneByDateIso(dateIso) ?: ("맥체인: $title" to 4)
                    "dailybible" -> "매일성경: ${title.ifBlank { dateIso }}" to 1
                    "bible_reading" -> {
                        val txt = "성경통독: ${title.ifBlank { itemId }}"
                        val range = Regex("(\\d+)\\s*[-~]\\s*(\\d+)").find(txt)
                        val count = if (range != null) {
                            (range.groupValues[2].toInt() - range.groupValues[1].toInt() + 1).coerceAtLeast(1)
                        } else 1
                        txt to count
                    }
                    "catechism" -> "날마다 교리: ${title.ifBlank { itemId }}" to 0
                    else -> title.ifBlank { itemId } to 1
                }

                add(
                    Bible71LogItem(
                        id = row.optLong("id", i.toLong()),
                        date = dateIso,
                        category = cat,
                        itemId = itemId,
                        title = title,
                        passage = passage,
                        chaptersCount = chapters,
                        completedAt = row.optString("completed_at", "")
                    )
                )
            }
        }
    }

    fun getStatusForDate(logs: List<Bible71LogItem>, dateIso: String): BibleDayStatus {
        val dayLogs = logs.filter { it.date == dateIso }
        if (dayLogs.isNotEmpty()) {
            val totalCh = dayLogs.sumOf { it.chaptersCount }.coerceAtLeast(1)
            val text = dayLogs.joinToString(" · ") { it.passage }
            return BibleDayStatus(
                source = "bible71",
                completed = true,
                chaptersCount = totalCh,
                passageText = text
            )
        }
        return BibleDayStatus(
            source = "bible71",
            completed = false,
            chaptersCount = 0,
            passageText = "오늘 성경읽기 미기록"
        )
    }

    fun analyzeReadingPattern(
        logs: List<Bible71LogItem>,
        targetDateIso: String,
        isElementary: Boolean = false
    ): BibleReadingPattern {
        val baseDate = runCatching { LocalDate.parse(targetDateIso) }.getOrElse { LocalDate.now() }
        val tomorrow = baseDate.plusDays(1)
        val tomorrowIso = tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weekdayKorean = when (tomorrow.dayOfWeek) {
            DayOfWeek.MONDAY -> "월"
            DayOfWeek.TUESDAY -> "화"
            DayOfWeek.WEDNESDAY -> "수"
            DayOfWeek.THURSDAY -> "목"
            DayOfWeek.FRIDAY -> "금"
            DayOfWeek.SATURDAY -> "토"
            DayOfWeek.SUNDAY -> "일"
        }

        val sorted = logs.filter { it.date.isNotBlank() }.sortedBy { it.date }
        val groupedByDate = sorted.groupBy { it.date }
        val uniqueDates = groupedByDate.keys.sorted()

        if (uniqueDates.isEmpty()) {
            val defaultBook = if (isElementary) "잠언 1장 (1장)" else "마가복음 1~2장 (2장)"
            val defaultTime = if (isElementary) "오후 4시 00분" else "밤 10시 00분"
            return BibleReadingPattern(
                totalDays = 0,
                avgIntervalDays = 0.0,
                avgChaptersPerSession = if (isElementary) 1 else 2,
                strongDaysText = "기록 수집 중",
                weakDaysText = "매일 10분 루틴 시작",
                preferredTimeSlot = defaultTime,
                patternSummary = "아직 성경읽기 누적 기록이 없습니다. 오늘부터 날짜별 패턴을 기록해 보세요.",
                nextDaySuggestion = "내일($tomorrowIso, $weekdayKorean) $defaultTime: $defaultBook 읽고 체크하기"
            )
        }

        val weekdayCounts = IntArray(7) // 0=Sun..6=Sat
        val dayLabels = listOf("일", "월", "화", "수", "목", "금", "토")
        val parsedDates = uniqueDates.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        parsedDates.forEach { d ->
            val idx = if (d.dayOfWeek == DayOfWeek.SUNDAY) 0 else d.dayOfWeek.value
            weekdayCounts[idx]++
        }

        var intervalSum = 0L
        var intervalCnt = 0
        for (i in 1 until parsedDates.size) {
            val diff = ChronoUnit.DAYS.between(parsedDates[i - 1], parsedDates[i])
            if (diff > 0) {
                intervalSum += diff
                intervalCnt++
            }
        }
        val avgInterval = if (intervalCnt > 0) ((intervalSum * 10.0) / intervalCnt).toInt() / 10.0 else 1.0

        val maxWd = weekdayCounts.maxOrNull() ?: 1
        val strongDays = dayLabels.filterIndexed { i, _ -> weekdayCounts[i] > 0 && weekdayCounts[i] >= maxOf(1, (maxWd * 0.6).toInt()) }
        val weakDays = dayLabels.filterIndexed { i, _ -> weekdayCounts[i] == 0 }

        val kstZone = ZoneId.of("Asia/Seoul")
        val kstHours = sorted.mapNotNull { item ->
            if (item.completedAt.isBlank()) null
            else runCatching { OffsetDateTime.parse(item.completedAt).atZoneSameInstant(kstZone).hour }.getOrNull()
        }

        var preferredSlot = if (isElementary) "방과후(오후 4시~5시)" else "저녁~밤 시간대"
        var recommendedTime = if (isElementary) "오후 4시 30분" else "밤 10시 00분"
        if (kstHours.isNotEmpty()) {
            val avgH = kstHours.average().toInt()
            when {
                avgH >= 22 || avgH < 5 -> {
                    preferredSlot = "밤 마감 시간대 (평균 ${avgH}시경)"
                    recommendedTime = if (avgH >= 23) "밤 10시 30분 (취침 30분 전)" else "밤 ${if (avgH > 12) avgH - 12 else avgH}시"
                }
                avgH in 5..11 -> {
                    preferredSlot = "오전/아침 시간대 (평균 ${avgH}시경)"
                    recommendedTime = "오전 ${avgH}시"
                }
                avgH in 12..17 -> {
                    preferredSlot = "오후 시간대 (평균 ${if (avgH > 12) avgH - 12 else 12}시경)"
                    recommendedTime = "오후 ${if (avgH > 12) avgH - 12 else 12}시"
                }
                else -> {
                    preferredSlot = "저녁 시간대 (평균 ${avgH - 12}시경)"
                    recommendedTime = "저녁 ${avgH - 12}시"
                }
            }
        }

        val totalChapters = groupedByDate.values.sumOf { dayList -> dayList.sumOf { it.chaptersCount.coerceAtLeast(1) } }
        val avgChapters = (totalChapters / uniqueDates.size).coerceAtLeast(1)
        val paceTarget = if (isElementary) avgChapters.coerceAtMost(2) else avgChapters.coerceIn(2, 4)

        val lastItem = sorted.last()
        val lastText = lastItem.passage.ifBlank { lastItem.title }
        val booksDesc = BIBLE_66_BOOKS.sortedByDescending { it.name.length }
        var matchedBook: BibleBookMeta? = null
        var lastEndCh = 0
        for (book in booksDesc) {
            val idx = lastText.lastIndexOf(book.name)
            if (idx >= 0) {
                val after = lastText.substring(idx + book.name.length)
                val match = Regex("^\\s*(\\d+)(?:\\s*[-~]\\s*(\\d+))?").find(after)
                if (match != null) {
                    matchedBook = book
                    lastEndCh = (match.groupValues[2].ifBlank { match.groupValues[1] }).toIntOrNull() ?: 0
                    break
                }
            }
        }

        val nextPassage = if (matchedBook != null && lastEndCh > 0) {
            var nextStart = lastEndCh + 1
            var targetBook = matchedBook
            if (nextStart > matchedBook.chapters) {
                targetBook = BIBLE_66_BOOKS[matchedBook.no % BIBLE_66_BOOKS.size]
                nextStart = 1
            }
            val nextEnd = minOf(targetBook.chapters, nextStart + paceTarget - 1)
            if (nextEnd > nextStart) "${targetBook.name} ${nextStart}~${nextEnd}장 (${nextEnd - nextStart + 1}장)"
            else "${targetBook.name} ${nextStart}장 (1장)"
        } else {
            lookupMcCheyneByDateIso(tomorrowIso)?.let { "${it.first} 중 핵심 ${paceTarget}장" }
                ?: "복음서/잠언 이어 읽기 ${paceTarget}장"
        }

        val strongTxt = if (strongDays.isNotEmpty()) strongDays.joinToString("·") else "고른 분포"
        val weakTxt = if (weakDays.isNotEmpty()) weakDays.take(3).joinToString("·") else "없음"
        val patternSummary = "평균 ${avgInterval}일 간격 · 주력 요일($strongTxt) · $preferredSlot · 회당 평균 ${avgChapters}장"
        val gapTip = if (avgInterval > 1.6) " (공백 요일 '${weakTxt}'에 10분 먼저 열기!)" else " (매일 루틴 안정적!)"
        val nextSuggestion = "내일($weekdayKorean) $recommendedTime: $nextPassage$gapTip"

        return BibleReadingPattern(
            totalDays = uniqueDates.size,
            avgIntervalDays = avgInterval,
            avgChaptersPerSession = avgChapters,
            strongDaysText = strongTxt,
            weakDaysText = weakTxt,
            preferredTimeSlot = preferredSlot,
            patternSummary = patternSummary,
            nextDaySuggestion = nextSuggestion
        )
    }

    companion object {
        private const val SUPABASE_URL = "https://zeitxdnomtweyniblynu.supabase.co"
        private const val SUPABASE_ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InplaXR4ZG5vbXR3ZXluaWJseW51Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODgyNDE3MzksImV4cCI6MjEwMzgxNzczOX0.oUOB2A1xt2TpY9Z1hoUrdecI2QdmaxfBQHCF9pjq57c"

        val BIBLE_66_BOOKS = listOf(
            BibleBookMeta(1, "창세기", 50), BibleBookMeta(2, "출애굽기", 40), BibleBookMeta(3, "레위기", 27),
            BibleBookMeta(4, "민수기", 36), BibleBookMeta(5, "신명기", 34), BibleBookMeta(6, "여호수아", 24),
            BibleBookMeta(7, "사사기", 21), BibleBookMeta(8, "룻기", 4), BibleBookMeta(9, "사무엘상", 31),
            BibleBookMeta(10, "사무엘하", 24), BibleBookMeta(11, "열왕기상", 22), BibleBookMeta(12, "열왕기하", 25),
            BibleBookMeta(13, "역대상", 29), BibleBookMeta(14, "역대하", 36), BibleBookMeta(15, "에스라", 10),
            BibleBookMeta(16, "느헤미야", 13), BibleBookMeta(17, "에스더", 10), BibleBookMeta(18, "욥기", 42),
            BibleBookMeta(19, "시편", 150), BibleBookMeta(20, "잠언", 31), BibleBookMeta(21, "전도서", 12),
            BibleBookMeta(22, "아가", 8), BibleBookMeta(23, "이사야", 66), BibleBookMeta(24, "예레미야", 52),
            BibleBookMeta(25, "예레미야애가", 5), BibleBookMeta(26, "에스겔", 48), BibleBookMeta(27, "다니엘", 12),
            BibleBookMeta(28, "호세아", 14), BibleBookMeta(29, "요엘", 3), BibleBookMeta(30, "아모스", 9),
            BibleBookMeta(31, "오바댜", 1), BibleBookMeta(32, "요나", 4), BibleBookMeta(33, "미가", 7),
            BibleBookMeta(34, "나훔", 3), BibleBookMeta(35, "하박국", 3), BibleBookMeta(36, "스바냐", 3),
            BibleBookMeta(37, "학개", 2), BibleBookMeta(38, "스가랴", 14), BibleBookMeta(39, "말라기", 4),
            BibleBookMeta(40, "마태복음", 28), BibleBookMeta(41, "마가복음", 16), BibleBookMeta(42, "누가복음", 24),
            BibleBookMeta(43, "요한복음", 21), BibleBookMeta(44, "사도행전", 28), BibleBookMeta(45, "로마서", 16),
            BibleBookMeta(46, "고린도전서", 16), BibleBookMeta(47, "고린도후서", 13), BibleBookMeta(48, "갈라디아서", 6),
            BibleBookMeta(49, "에베소서", 6), BibleBookMeta(50, "빌립보서", 4), BibleBookMeta(51, "골로새서", 4),
            BibleBookMeta(52, "데살로니가전서", 5), BibleBookMeta(53, "데살로니가후서", 3), BibleBookMeta(54, "디모데전서", 6),
            BibleBookMeta(55, "디모데후서", 4), BibleBookMeta(56, "디도서", 3), BibleBookMeta(57, "빌레몬서", 1),
            BibleBookMeta(58, "히브리서", 13), BibleBookMeta(59, "야고보서", 5), BibleBookMeta(60, "베드로전서", 5),
            BibleBookMeta(61, "베드로후서", 3), BibleBookMeta(62, "요한일서", 5), BibleBookMeta(63, "요한이서", 1),
            BibleBookMeta(64, "요한삼서", 1), BibleBookMeta(65, "유다서", 1), BibleBookMeta(66, "요한계시록", 22)
        )
    }
}
