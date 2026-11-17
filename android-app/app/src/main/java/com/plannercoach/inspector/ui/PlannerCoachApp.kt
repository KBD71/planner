package com.plannercoach.inspector.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.plannercoach.inspector.data.AppSettings
import com.plannercoach.inspector.data.PlannerRepository
import com.plannercoach.inspector.data.SettingsRepository
import com.plannercoach.inspector.export.PdfPrintAndShareManager
import com.plannercoach.inspector.model.AccumulationStage
import com.plannercoach.inspector.model.DailyInspectionResult
import com.plannercoach.inspector.model.FocusHabitType
import com.plannercoach.inspector.model.PlanCorrectionItem
import com.plannercoach.inspector.model.StudentProfileType
import com.plannercoach.inspector.model.StudyMode
import com.plannercoach.inspector.model.TimeBlock
import com.plannercoach.inspector.model.WeeklyInsightReport
import com.plannercoach.inspector.network.Bible71LogItem
import com.plannercoach.inspector.network.Bible71Service
import com.plannercoach.inspector.network.BibleDayStatus
import com.plannercoach.inspector.network.BibleReadingPattern
import com.plannercoach.inspector.network.GeminiInspectorService
import com.plannercoach.inspector.render.ReportCardRenderer
import com.plannercoach.inspector.sync.GoogleDriveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

private enum class MainTab(val label: String) {
    HOME("홈 대시보드"),
    INSPECT("플래너 검사"),
    WEEKLY("주간 리포트"),
    SETTINGS("설정·드라이브")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerCoachApp(
    repository: PlannerRepository,
    settingsRepository: SettingsRepository,
    geminiService: GeminiInspectorService,
    bible71Service: Bible71Service,
    exportManager: PdfPrintAndShareManager,
    driveSyncManager: GoogleDriveSyncManager
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var settings by remember { mutableStateOf(settingsRepository.load()) }
    var activeStudent by remember { mutableStateOf(StudentProfileType.HIGH_SCHOOL) }
    var currentTab by remember { mutableStateOf(MainTab.HOME) }

    val studentRecords by repository.observeInspections(activeStudent).collectAsState(initial = emptyList())
    var bible71Logs by remember { mutableStateOf<List<Bible71LogItem>>(emptyList()) }

    // Daily inspection state
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var inspectionDate by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)) }
    var teacherMemo by remember { mutableStateOf("") }
    var elemManualBibleDone by remember { mutableStateOf(false) }
    var elemManualBiblePassage by remember { mutableStateOf("") }
    var elemManualBibleChapters by remember { mutableIntStateOf(1) }
    var keptYesterdayPromise by remember { mutableStateOf(false) }
    var focusHabitType by remember(settings.focusHabitKey) { mutableStateOf(FocusHabitType.fromId(settings.focusHabitKey)) }
    var isAnalyzingDaily by remember { mutableStateOf(false) }
    var currentDailyResult by remember { mutableStateOf<DailyInspectionResult?>(null) }
    var dailyCardBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Weekly report state
    var weekOffset by remember { mutableIntStateOf(0) }
    var isGeneratingWeekly by remember { mutableStateOf(false) }
    var currentWeeklyReport by remember { mutableStateOf<WeeklyInsightReport?>(null) }
    var weeklyCardBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun showMessage(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    suspend fun syncBible71Logs(showFeedback: Boolean = false) {
        runCatching {
            bible71Service.fetchLogsForStudent(
                memberName = settings.bibleMemberName,
                memberPhone = settings.bibleMemberPhone
            )
        }.onSuccess { fetched ->
            bible71Logs = fetched
            if (showFeedback) {
                showMessage("bible71에서 ${settings.bibleMemberName} 성경읽기 기록 ${fetched.size}건을 불러왔습니다!")
            }
        }.onFailure { err ->
            if (showFeedback) showMessage("bible71 DB 조회 실패: ${err.message}")
        }
    }

    LaunchedEffect(settings.bibleMemberName, settings.bibleMemberPhone) {
        syncBible71Logs(showFeedback = false)
    }

    val currentBible71DayStatus = remember(bible71Logs, inspectionDate) {
        bible71Service.getStatusForDate(bible71Logs, inspectionDate)
    }

    val currentBiblePattern = remember(activeStudent, bible71Logs, studentRecords, inspectionDate) {
        if (activeStudent == StudentProfileType.HIGH_SCHOOL) {
            bible71Service.analyzeReadingPattern(bible71Logs, inspectionDate, isElementary = false)
        } else {
            val elemLogs = studentRecords
                .filter { it.bibleCompleted || it.biblePassageText.isNotBlank() }
                .mapIndexed { idx, rec ->
                    Bible71LogItem(
                        id = (idx + 1).toLong(),
                        date = rec.dateIso,
                        category = "planner",
                        itemId = "",
                        title = rec.biblePassageText,
                        passage = rec.biblePassageText,
                        chaptersCount = rec.bibleChaptersCount.coerceAtLeast(1)
                    )
                }
            bible71Service.analyzeReadingPattern(elemLogs, inspectionDate, isElementary = true)
        }
    }

    // Re-render daily preview whenever result or PDF practice toggle changes
    LaunchedEffect(currentDailyResult, settings.includeHandwritingPracticeInPdf) {
        val res = currentDailyResult ?: run {
            dailyCardBitmap = null
            return@LaunchedEffect
        }
        dailyCardBitmap = withContext(Dispatchers.Default) {
            ReportCardRenderer.renderDailyReportBitmap(
                result = res,
                includePracticeLines = settings.includeHandwritingPracticeInPdf
            )
        }
    }

    // Recompute weekly report when student, records, bible71 logs, or weekOffset changes
    LaunchedEffect(activeStudent, studentRecords, bible71Logs, weekOffset, settings.includeHandwritingPracticeInPdf) {
        val report = geminiService.buildWeeklyInsightReport(
            studentType = activeStudent,
            studentName = settings.nameFor(activeStudent),
            allRecords = studentRecords,
            weekOffset = weekOffset,
            apiKey = "",
            model = settings.geminiModel,
            bible71Logs = bible71Logs
        )
        currentWeeklyReport = report
        weeklyCardBitmap = withContext(Dispatchers.Default) {
            ReportCardRenderer.renderWeeklyReportBitmap(
                report = report,
                includePracticeLines = settings.includeHandwritingPracticeInPdf
            )
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraTempUri != null) {
            selectedImageUri = cameraTempUri
            currentTab = MainTab.INSPECT
            showMessage("플래너 사진이 촬영되었습니다. [AI 플래너 분석 시작]을 눌러주세요!")
        }
    }

    val pickGalleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            currentTab = MainTab.INSPECT
            showMessage("플래너 사진이 선택되었습니다. [AI 플래너 분석 시작]을 눌러주세요!")
        }
    }

    val pickDriveFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val updated = settings.copy(googleDriveTreeUri = uri.toString())
            settings = updated
            settingsRepository.save(updated)
            showMessage("구글 드라이브(폴더)가 성공적으로 연결되었습니다!")
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val json = repository.exportAllToBackupJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }
                showMessage("전체 누적 데이터 백업 JSON을 저장했습니다.")
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val count = runCatching {
                    val raw = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }
                    repository.importBackupJson(raw)
                }.getOrDefault(0)
                showMessage("백업에서 ${count}건의 학습 기록을 복원했습니다.")
            }
        }
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val photoFile = File(dir, "planner_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
        cameraTempUri = uri
        takePictureLauncher.launch(uri)
    }

    fun runDailyAiAnalysis() {
        val uri = selectedImageUri
        if (uri == null) {
            showMessage("먼저 플래너 사진을 촬영하거나 선택해 주세요.")
            return
        }
        if (settings.geminiApiKey.isBlank()) {
            currentTab = MainTab.SETTINGS
            showMessage("설정 탭에서 보유하신 Gemini API 키를 입력해 주세요.")
            return
        }
        scope.launch {
            isAnalyzingDaily = true
            runCatching {
                if (activeStudent == StudentProfileType.HIGH_SCHOOL) {
                    syncBible71Logs(showFeedback = false)
                }
                val latestBibleStatus = if (activeStudent == StudentProfileType.HIGH_SCHOOL) {
                    bible71Service.getStatusForDate(bible71Logs, inspectionDate)
                } else null
                val latestBiblePattern = if (activeStudent == StudentProfileType.HIGH_SCHOOL) {
                    bible71Service.analyzeReadingPattern(bible71Logs, inspectionDate, isElementary = false)
                } else {
                    currentBiblePattern
                }

                val analyzed = geminiService.inspectPlannerPhoto(
                    imageUri = uri,
                    apiKey = settings.geminiApiKey,
                    model = settings.geminiModel,
                    studentType = activeStudent,
                    studentName = settings.nameFor(activeStudent),
                    dateIso = inspectionDate,
                    teacherMemo = teacherMemo,
                    recentHistory = studentRecords,
                    bibleStatusFromDb = latestBibleStatus,
                    biblePattern = latestBiblePattern,
                    elemManualBibleDone = elemManualBibleDone,
                    elemManualBiblePassage = elemManualBiblePassage,
                    elemManualBibleChapters = elemManualBibleChapters,
                    focusHabitType = focusHabitType,
                    keptYesterdayPromise = keptYesterdayPromise,
                    useNanoBananaBasic = settings.useNanoBananaBasic
                )
                repository.saveInspection(analyzed)
                currentDailyResult = analyzed
                if (analyzed.studentType == StudentProfileType.ELEMENTARY) {
                    elemManualBibleDone = analyzed.bibleCompleted
                    elemManualBiblePassage = analyzed.biblePassageText
                    elemManualBibleChapters = analyzed.bibleChaptersCount
                }
                val rendered = withContext(Dispatchers.Default) {
                    ReportCardRenderer.renderDailyReportBitmap(analyzed, settings.includeHandwritingPracticeInPdf)
                }
                dailyCardBitmap = rendered

                if (settings.autoSyncToDrive && (settings.googleDriveTreeUri.isNotBlank() || settings.googleAppsScriptWebhookUrl.isNotBlank())) {
                    val pngBytes = exportManager.bitmapToPngBytes(rendered)
                    val pdfBytes = exportManager.generateA4PdfBytes(rendered)
                    val syncRes = driveSyncManager.syncDailyInspection(settings, analyzed, pngBytes, pdfBytes)
                    showMessage("분석 완료! ${syncRes.message}")
                } else {
                    showMessage("플래너 분석 완료! 아래 수정안과 베껴쓰기 박스를 확인하세요.")
                }
            }.onFailure { err ->
                showMessage("분석 실패: ${err.message}")
            }
            isAnalyzingDaily = false
        }
    }

    Scaffold(
        containerColor = Color(0xFFF8FAFC),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "플래너 코치 AI",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = "${activeStudent.badgeEmoji} ${settings.nameFor(activeStudent)} · ${activeStudent.subtitle}",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                    Text(
                        text = "누적 ${studentRecords.map { it.dateIso }.distinct().size}일",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2563EB),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFFDBEAFE))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 2-Student Profile Switcher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFF1F5F9))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StudentProfileType.entries.forEach { profile ->
                        val selected = activeStudent == profile
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Color.White else Color.Transparent)
                                .clickable { activeStudent = profile }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${profile.badgeEmoji} ${settings.nameFor(profile)} (${profile.displayName})",
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color(0xFF0F172A) else Color(0xFF64748B)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = currentTab == MainTab.HOME,
                    onClick = { currentTab = MainTab.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("홈") }
                )
                NavigationBarItem(
                    selected = currentTab == MainTab.INSPECT,
                    onClick = { currentTab = MainTab.INSPECT },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    label = { Text("검사") }
                )
                NavigationBarItem(
                    selected = currentTab == MainTab.WEEKLY,
                    onClick = { currentTab = MainTab.WEEKLY },
                    icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                    label = { Text("주간리포트") }
                )
                NavigationBarItem(
                    selected = currentTab == MainTab.SETTINGS,
                    onClick = { currentTab = MainTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("설정") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentTab) {
                MainTab.HOME -> HomeDashboardScreen(
                    studentType = activeStudent,
                    studentName = settings.nameFor(activeStudent),
                    records = studentRecords,
                    bible71Logs = bible71Logs,
                    biblePattern = currentBiblePattern,
                    onRefreshBible71 = { scope.launch { syncBible71Logs(showFeedback = true) } },
                    onTakePhoto = { launchCamera() },
                    onPickGallery = { pickGalleryLauncher.launch("image/*") },
                    onOpenWeekly = { currentTab = MainTab.WEEKLY },
                    onOpenRecord = { rec ->
                        currentDailyResult = rec
                        currentTab = MainTab.INSPECT
                    },
                    onDeleteRecord = { rec ->
                        scope.launch {
                            repository.deleteInspection(rec.id)
                            showMessage("${rec.dateIso} 기록을 삭제했습니다.")
                        }
                    },
                    onSeedDemoData = {
                        scope.launch {
                            val samples = buildDemoSampleWeek(activeStudent, settings.nameFor(activeStudent))
                            samples.forEach { repository.saveInspection(it) }
                            showMessage("${activeStudent.displayName} 체험용 샘플 데이터를 넣었습니다! 주간 리포트를 확인해 보세요.")
                        }
                    }
                )

                MainTab.INSPECT -> DailyInspectionScreen(
                    studentType = activeStudent,
                    studentName = settings.nameFor(activeStudent),
                    selectedImageUri = selectedImageUri,
                    inspectionDate = inspectionDate,
                    teacherMemo = teacherMemo,
                    bible71DayStatus = currentBible71DayStatus,
                    biblePattern = currentBiblePattern,
                    elemBibleDone = elemManualBibleDone,
                    elemBiblePassage = elemManualBiblePassage,
                    elemBibleChapters = elemManualBibleChapters,
                    focusHabitType = focusHabitType,
                    keptYesterdayPromise = keptYesterdayPromise,
                    useNanoBananaBasic = settings.useNanoBananaBasic,
                    isAnalyzing = isAnalyzingDaily,
                    dailyResult = currentDailyResult,
                    cardBitmap = dailyCardBitmap,
                    onDateChange = { inspectionDate = it },
                    onMemoChange = { teacherMemo = it },
                    onRefreshBible71 = { scope.launch { syncBible71Logs(showFeedback = true) } },
                    onElemBibleDoneChange = { elemManualBibleDone = it },
                    onElemBiblePassageChange = { elemManualBiblePassage = it },
                    onElemBibleChaptersChange = { elemManualBibleChapters = it },
                    onFocusHabitChange = {
                        focusHabitType = it
                        val updated = settings.copy(focusHabitKey = it.id)
                        settings = updated
                        settingsRepository.save(updated)
                    },
                    onKeptYesterdayChange = { keptYesterdayPromise = it },
                    onTakePhoto = { launchCamera() },
                    onPickGallery = { pickGalleryLauncher.launch("image/*") },
                    onAnalyze = { runDailyAiAnalysis() },
                    onUpdateCopywriting = { newPromise, newPractice, newSummary ->
                        val cur = currentDailyResult ?: return@DailyInspectionScreen
                        val updated = cur.copy(
                            copywritingPromise = newPromise,
                            copywritingPractice = newPractice,
                            improvementSummary = newSummary,
                            keptYesterdayPromise = keptYesterdayPromise,
                            bibleCompleted = if (cur.studentType == StudentProfileType.ELEMENTARY) elemManualBibleDone else cur.bibleCompleted,
                            biblePassageText = if (cur.studentType == StudentProfileType.ELEMENTARY) elemManualBiblePassage else cur.biblePassageText,
                            bibleChaptersCount = if (cur.studentType == StudentProfileType.ELEMENTARY) elemManualBibleChapters else cur.bibleChaptersCount
                        )
                        currentDailyResult = updated
                        scope.launch {
                            repository.saveInspection(updated)
                            showMessage("수정된 필사 문구와 성경·코멘트를 반영했습니다.")
                        }
                    },
                    onShareKakao = {
                        val res = currentDailyResult ?: return@DailyInspectionScreen
                        val bmp = dailyCardBitmap ?: return@DailyInspectionScreen
                        val msg = exportManager.buildDailyShareMessage(res)
                        exportManager.shareToKakaoTalk(bmp, msg, "daily_${res.dateIso}")
                        Toast.makeText(context, "코칭/베껴쓰기 텍스트도 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
                    },
                    onSavePdf = {
                        val res = currentDailyResult ?: return@DailyInspectionScreen
                        val bmp = dailyCardBitmap ?: return@DailyInspectionScreen
                        scope.launch {
                            val (uri, _) = withContext(Dispatchers.IO) {
                                exportManager.saveA4PdfToDevice(bmp, "플래너수정안_${res.studentName}_${res.dateIso}.pdf")
                            }
                            showMessage("Downloads/PlannerCoach 폴더에 A4 PDF로 저장했습니다!")
                            exportManager.openOrSharePdf(uri, "${res.studentName} 플래너 수정안")
                        }
                    },
                    onPrint = {
                        val res = currentDailyResult ?: return@DailyInspectionScreen
                        val bmp = dailyCardBitmap ?: return@DailyInspectionScreen
                        if (activity != null) {
                            exportManager.printReportBitmap(activity, "플래너수정안_${res.studentName}_${res.dateIso}", bmp)
                        }
                    },
                    onSyncDrive = {
                        val res = currentDailyResult ?: return@DailyInspectionScreen
                        val bmp = dailyCardBitmap ?: return@DailyInspectionScreen
                        scope.launch {
                            val pngBytes = withContext(Dispatchers.Default) { exportManager.bitmapToPngBytes(bmp) }
                            val pdfBytes = withContext(Dispatchers.Default) { exportManager.generateA4PdfBytes(bmp) }
                            val sync = driveSyncManager.syncDailyInspection(settings, res, pngBytes, pdfBytes)
                            showMessage(sync.message)
                        }
                    }
                )

                MainTab.WEEKLY -> WeeklyReportScreen(
                    report = currentWeeklyReport,
                    cardBitmap = weeklyCardBitmap,
                    weekOffset = weekOffset,
                    isGenerating = isGeneratingWeekly,
                    onChangeWeekOffset = { weekOffset = it },
                    onGenerateAiSynthesis = {
                        if (settings.geminiApiKey.isBlank()) {
                            showMessage("API 키가 없어도 통계 리포트는 자동 생성됩니다. 심층 AI 코칭을 원하시면 설정에서 API 키를 입력해 주세요.")
                            return@WeeklyReportScreen
                        }
                        scope.launch {
                            isGeneratingWeekly = true
                            val report = geminiService.buildWeeklyInsightReport(
                                studentType = activeStudent,
                                studentName = settings.nameFor(activeStudent),
                                allRecords = studentRecords,
                                weekOffset = weekOffset,
                                apiKey = settings.geminiApiKey,
                                model = settings.geminiModel,
                                bible71Logs = bible71Logs
                            )
                            currentWeeklyReport = report
                            weeklyCardBitmap = withContext(Dispatchers.Default) {
                                ReportCardRenderer.renderWeeklyReportBitmap(report, settings.includeHandwritingPracticeInPdf)
                            }
                            isGeneratingWeekly = false
                            showMessage("Gemini 누적 분석·예측 리포트를 최신화했습니다!")
                        }
                    },
                    onShareKakao = {
                        val rep = currentWeeklyReport ?: return@WeeklyReportScreen
                        val bmp = weeklyCardBitmap ?: return@WeeklyReportScreen
                        val msg = exportManager.buildWeeklyShareMessage(rep)
                        exportManager.shareToKakaoTalk(bmp, msg, "weekly_${rep.startDateIso}")
                    },
                    onSavePdf = {
                        val rep = currentWeeklyReport ?: return@WeeklyReportScreen
                        val bmp = weeklyCardBitmap ?: return@WeeklyReportScreen
                        scope.launch {
                            val (uri, _) = withContext(Dispatchers.IO) {
                                exportManager.saveA4PdfToDevice(bmp, "주간분석리포트_${rep.studentName}_${rep.startDateIso}.pdf")
                            }
                            showMessage("Downloads/PlannerCoach 폴더에 주간 리포트 PDF를 저장했습니다!")
                            exportManager.openOrSharePdf(uri, "${rep.studentName} 주간 공부 리포트")
                        }
                    },
                    onPrint = {
                        val rep = currentWeeklyReport ?: return@WeeklyReportScreen
                        val bmp = weeklyCardBitmap ?: return@WeeklyReportScreen
                        if (activity != null) {
                            exportManager.printReportBitmap(activity, "주간리포트_${rep.studentName}_${rep.startDateIso}", bmp)
                        }
                    },
                    onSyncDrive = {
                        val rep = currentWeeklyReport ?: return@WeeklyReportScreen
                        val bmp = weeklyCardBitmap ?: return@WeeklyReportScreen
                        scope.launch {
                            val pngBytes = withContext(Dispatchers.Default) { exportManager.bitmapToPngBytes(bmp) }
                            val pdfBytes = withContext(Dispatchers.Default) { exportManager.generateA4PdfBytes(bmp) }
                            val res = driveSyncManager.syncWeeklyReportFiles(
                                settings = settings,
                                studentType = activeStudent,
                                weekFileStem = "주간리포트_${rep.startDateIso}_${rep.endDateIso}",
                                pngBytes = pngBytes,
                                pdfBytes = pdfBytes
                            )
                            showMessage(res.message)
                        }
                    }
                )

                MainTab.SETTINGS -> SettingsAndDriveScreen(
                    settings = settings,
                    onSaveSettings = { updated ->
                        settings = updated
                        settingsRepository.save(updated)
                        showMessage("설정이 저장되었습니다.")
                    },
                    onSyncBibleNow = { scope.launch { syncBible71Logs(showFeedback = true) } },
                    onPickDriveFolder = { pickDriveFolderLauncher.launch(null) },
                    onExportBackup = {
                        exportBackupLauncher.launch("planner_backup_${LocalDate.now()}.json")
                    },
                    onImportBackup = {
                        importBackupLauncher.launch(arrayOf("application/json"))
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeDashboardScreen(
    studentType: StudentProfileType,
    studentName: String,
    records: List<DailyInspectionResult>,
    bible71Logs: List<Bible71LogItem>,
    biblePattern: BibleReadingPattern,
    onRefreshBible71: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onOpenWeekly: () -> Unit,
    onOpenRecord: (DailyInspectionResult) -> Unit,
    onDeleteRecord: (DailyInspectionResult) -> Unit,
    onSeedDemoData: () -> Unit
) {
    val distinctDays = records.map { it.dateIso }.distinct().size
    val stage = AccumulationStage.fromDays(distinctDays)
    val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    val dayNames = listOf("월", "화", "수", "목", "금", "토", "일")
    val todayIso = LocalDate.now().format(fmt)
    val weekIsoDates = (0..6).map { monday.plusDays(it.toLong()).format(fmt) }
    val weekBibleDays = if (studentType == StudentProfileType.HIGH_SCHOOL) {
        weekIsoDates.count { d -> bible71Logs.any { it.date == d } || records.any { it.dateIso == d && it.bibleCompleted } }
    } else {
        weekIsoDates.count { d -> records.any { it.dateIso == d && it.bibleCompleted } }
    }
    val todayBibleDone = if (studentType == StudentProfileType.HIGH_SCHOOL) {
        bible71Logs.any { it.date == todayIso }
    } else {
        records.any { it.dateIso == todayIso && it.bibleCompleted }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${studentType.badgeEmoji} $studentName 이번 주 누적 현황",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = stage.badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF059669),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFD1FAE5))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (0..6).forEach { idx ->
                            val dateIso = monday.plusDays(idx.toLong()).format(fmt)
                            val dayRecs = records.filter { it.dateIso == dateIso }
                            val done = dayRecs.isNotEmpty()
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (done) Color(0xFF2563EB) else Color(0xFFF1F5F9)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (done) "✓" else dayNames[idx],
                                        color = if (done) Color.White else Color(0xFF64748B),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = dayNames[idx],
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onTakePhoto,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("플래너 촬영")
                        }
                        OutlinedButton(
                            onClick = onPickGallery,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("사진 선택")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenWeekly,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("주간 공부 분석 · 예측 리포트 보기")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(if (weekBibleDays > 0) 0xFFF0FDFA else 0xFFFFFBEB)
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (studentType == StudentProfileType.HIGH_SCHOOL)
                                "📖 $studentName 성경읽기 (bible71 DB 연동)"
                            else
                                "📖 $studentName 종이 플래너 성경읽기",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color(0xFF0F766E)
                        )
                        if (studentType == StudentProfileType.HIGH_SCHOOL) {
                            TextButton(onClick = onRefreshBible71) {
                                Text("동기화")
                            }
                        }
                    }
                    Text(
                        text = if (studentType == StudentProfileType.HIGH_SCHOOL) {
                            val latest = bible71Logs.firstOrNull()
                            "이번 주 ${weekBibleDays}/7일 완료 · 오늘: ${if (todayBibleDone) "✅ 완료" else "⏳ 미기록"}" +
                                "\n📊 패턴: ${biblePattern.patternSummary}" +
                                "\n👉 내일 제안: ${biblePattern.nextDaySuggestion}" +
                                (latest?.let { "\n최근 bible71 기록: ${it.date} (${it.passage})" } ?: "")
                        } else {
                            "이번 주 ${weekBibleDays}/7일 완료 · 오늘: ${if (todayBibleDone) "✅ 기록됨" else "종이 플래너 사진에서 판독"}" +
                                "\n📊 패턴: ${biblePattern.patternSummary}" +
                                "\n👉 내일 제안: ${biblePattern.nextDaySuggestion}"
                        },
                        fontSize = 13.sp,
                        color = Color(0xFF334155)
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "최근 검사 누적 기록 (${records.size}건)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                if (records.isEmpty()) {
                    TextButton(onClick = onSeedDemoData) {
                        Text("체험용 7일 샘플 채우기")
                    }
                }
            }
        }

        if (records.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("아직 저장된 플래너 검사 기록이 없습니다.", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "플래너 사진을 찍어 첫 검사를 시작하거나, 위의 '체험용 7일 샘플 채우기'로 주간 분석·PDF·인쇄 기능을 바로 확인해 보세요.",
                            fontSize = 13.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            }
        } else {
            items(records, key = { it.id }) { rec ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecord(rec) },
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFEEF2FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${rec.habitScore}점",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = Color(0xFF4F46E5)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${rec.dateIso} · 총 ${rec.totalStudyMinutes / 60}시간 ${rec.totalStudyMinutes % 60}분 (달성 ${rec.completionRatePercent}%)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF0F172A)
                            )
                            Text(
                                text = rec.copywritingPromise,
                                maxLines = 1,
                                fontSize = 12.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                        IconButton(onClick = { onDeleteRecord(rec) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "삭제", tint = Color(0xFF94A3B8))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyInspectionScreen(
    studentType: StudentProfileType,
    studentName: String,
    selectedImageUri: Uri?,
    inspectionDate: String,
    teacherMemo: String,
    bible71DayStatus: BibleDayStatus,
    biblePattern: BibleReadingPattern,
    elemBibleDone: Boolean,
    elemBiblePassage: String,
    elemBibleChapters: Int,
    focusHabitType: FocusHabitType,
    keptYesterdayPromise: Boolean,
    useNanoBananaBasic: Boolean,
    isAnalyzing: Boolean,
    dailyResult: DailyInspectionResult?,
    cardBitmap: Bitmap?,
    onDateChange: (String) -> Unit,
    onMemoChange: (String) -> Unit,
    onRefreshBible71: () -> Unit,
    onElemBibleDoneChange: (Boolean) -> Unit,
    onElemBiblePassageChange: (String) -> Unit,
    onElemBibleChaptersChange: (Int) -> Unit,
    onFocusHabitChange: (FocusHabitType) -> Unit,
    onKeptYesterdayChange: (Boolean) -> Unit,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onAnalyze: () -> Unit,
    onUpdateCopywriting: (String, String, String) -> Unit,
    onShareKakao: () -> Unit,
    onSavePdf: () -> Unit,
    onPrint: () -> Unit,
    onSyncDrive: () -> Unit
) {
    val scroll = rememberScrollState()
    var editPromise by remember(dailyResult?.id) { mutableStateOf(dailyResult?.copywritingPromise ?: "") }
    var editPractice by remember(dailyResult?.id) { mutableStateOf(dailyResult?.copywritingPractice ?: "") }
    var editSummary by remember(dailyResult?.id) { mutableStateOf(dailyResult?.improvementSummary ?: "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${studentType.badgeEmoji} $studentName 플래너 사진 검사",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Gemini 비전 분석" + if (useNanoBananaBasic) " · 🍌 나노바나나 기본형(gemini-nano-banana-2.1) ON" else "",
                    fontSize = 12.sp,
                    color = Color(0xFF4F46E5),
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onTakePhoto, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("카메라 촬영")
                    }
                    OutlinedButton(onClick = onPickGallery, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("갤러리 선택")
                    }
                }
                if (selectedImageUri != null) {
                    Text(
                        text = "✓ 사진 선택됨 (${selectedImageUri.lastPathSegment ?: "image"})",
                        fontSize = 12.sp,
                        color = Color(0xFF059669),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedTextField(
                    value = inspectionDate,
                    onValueChange = onDateChange,
                    label = { Text("검사 날짜 (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Bible Reading Habit Panel (Date-Pattern + Next-Day Suggestion)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(
                            if ((studentType == StudentProfileType.HIGH_SCHOOL && bible71DayStatus.completed) ||
                                (studentType == StudentProfileType.ELEMENTARY && elemBibleDone)
                            ) 0xFFF0FDFA else 0xFFFFFBEB
                        )
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (studentType == StudentProfileType.HIGH_SCHOOL) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (bible71DayStatus.completed)
                                        "📖 bible71 성경읽기 완료 (${bible71DayStatus.chaptersCount}장)"
                                    else
                                        "⚠️ bible71 성경읽기 미기록 ($inspectionDate)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(if (bible71DayStatus.completed) 0xFF0F766E else 0xFFB45309)
                                )
                                TextButton(onClick = onRefreshBible71) { Text("조회") }
                            }
                            Text(bible71DayStatus.passageText, fontSize = 12.sp, color = Color(0xFF334155))
                            Text("📊 패턴: ${biblePattern.patternSummary}", fontSize = 12.sp, color = Color(0xFF0F766E))
                            Text("👉 내일 제안: ${biblePattern.nextDaySuggestion}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1D4ED8))
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📖 초등 종이 플래너 성경읽기", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F766E))
                                Switch(checked = elemBibleDone, onCheckedChange = onElemBibleDoneChange)
                            }
                            OutlinedTextField(
                                value = elemBiblePassage,
                                onValueChange = onElemBiblePassageChange,
                                label = { Text("읽은 성경 본문 (예: 잠언 1장)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = elemBibleChapters.toString(),
                                onValueChange = { onElemBibleChaptersChange(it.toIntOrNull() ?: 0) },
                                label = { Text("읽은 장수 (숫자)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("📊 패턴: ${biblePattern.patternSummary}", fontSize = 12.sp, color = Color(0xFF0F766E))
                            Text("👉 내일 제안: ${biblePattern.nextDaySuggestion}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1D4ED8))
                        }
                    }
                }

                // Focus Habit Selector & Yesterday Promise Verification
                Text("🎯 관리자 집중 코칭 습관 (1가지씩 정복)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusHabitType.entries.forEach { habit ->
                        FilterChip(
                            selected = focusHabitType == habit,
                            onClick = { onFocusHabitChange(habit) },
                            label = { Text(habit.label, fontSize = 11.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("✅ 어제 약속을 오늘 지켰나요?", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("이행 시 +10점 습관 보너스 배지 표시", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                    Switch(checked = keptYesterdayPromise, onCheckedChange = onKeptYesterdayChange)
                }

                OutlinedTextField(
                    value = teacherMemo,
                    onValueChange = onMemoChange,
                    label = { Text("지도자 참고 메모 (선택)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = onAnalyze,
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Gemini AI가 플래너를 분석하고 그림을 그리는 중...")
                    } else {
                        Text("✨ AI 플래너 분석 및 모범 그림 생성")
                    }
                }
            }
        }

        if (dailyResult != null && cardBitmap != null) {
            // Export & Share Action Bar
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("전송 · PDF 저장 · 인쇄 · 드라이브", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onShareKakao,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFEE500),
                                contentColor = Color(0xFF191919)
                            )
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("카톡 전송", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = onSavePdf, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("PDF 저장", fontSize = 13.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPrint, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("프린터 인쇄", fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = onSyncDrive, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("드라이브 저장", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Quick Teacher Polish Editor before sending
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✍️ 보내기 전 필사(베껴쓰기) · 코멘트 손질", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = editSummary,
                        onValueChange = { editSummary = it },
                        label = { Text("핵심 수정 방향") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editPromise,
                        onValueChange = { editPromise = it },
                        label = { Text("오늘의 플래너 약속 (베껴쓰기 1)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editPractice,
                        onValueChange = { editPractice = it },
                        label = { Text("수정 연습 문장 (베껴쓰기 2)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onUpdateCopywriting(editPromise, editPractice, editSummary) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("그림 카드에 즉시 반영")
                    }
                }
            }

            val nanoPreviewBmp = remember(dailyResult.nanoBananaImageBase64) {
                dailyResult.nanoBananaImageBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
                    runCatching {
                        val clean = b64.substringAfter("base64,", b64)
                        val bytes = Base64.decode(clean, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
            }
            if (nanoPreviewBmp != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🍌 나노바나나 기본형 색상 교정 예시 (gemini-nano-banana-2.1)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF4F46E5))
                        Image(
                            bitmap = nanoPreviewBmp.asImageBitmap(),
                            contentDescription = "나노바나나 기본형 모범 플래너",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
                        )
                    }
                }
            }

            // High-res Rendered Report Image Preview
            Text("생성된 모범 플래너 수정안 그림 (미리보기)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Image(
                bitmap = cardBitmap.asImageBitmap(),
                contentDescription = "모범 플래너 수정안 카드",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(18.dp))
            )
        }
    }
}

@Composable
private fun WeeklyReportScreen(
    report: WeeklyInsightReport?,
    cardBitmap: Bitmap?,
    weekOffset: Int,
    isGenerating: Boolean,
    onChangeWeekOffset: (Int) -> Unit,
    onGenerateAiSynthesis: () -> Unit,
    onShareKakao: () -> Unit,
    onSavePdf: () -> Unit,
    onPrint: () -> Unit,
    onSyncDrive: () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("📊 주간 공부 분석 & 다음 주 목표 리포트", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "이번 주", 1 to "지난주", 2 to "2주 전").forEach { (off, label) ->
                        FilterChip(
                            selected = weekOffset == off,
                            onClick = { onChangeWeekOffset(off) },
                            label = { Text(label) }
                        )
                    }
                }
                Button(
                    onClick = onGenerateAiSynthesis,
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("AI 주간 인사이트 및 진도 예측 생성 중...")
                    } else {
                        Text("✨ Gemini AI 심층 분석·예측 리포트 업데이트")
                    }
                }
                if (cardBitmap != null && report != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onShareKakao,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFEE500),
                                contentColor = Color(0xFF191919)
                            )
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("카톡 전송", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = onSavePdf, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("PDF 저장", fontSize = 13.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPrint, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("인쇄하기", fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = onSyncDrive, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("드라이브 저장", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (cardBitmap != null) {
            Image(
                bitmap = cardBitmap.asImageBitmap(),
                contentDescription = "주간 분석 인포그래픽 리포트",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(18.dp))
            )
        }
    }
}

@Composable
private fun SettingsAndDriveScreen(
    settings: AppSettings,
    onSaveSettings: (AppSettings) -> Unit,
    onSyncBibleNow: () -> Unit,
    onPickDriveFolder: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    var apiKey by remember(settings) { mutableStateOf(settings.geminiApiKey) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember(settings) { mutableStateOf(settings.geminiModel) }
    var useNanoBanana by remember(settings) { mutableStateOf(settings.useNanoBananaBasic) }
    var hsName by remember(settings) { mutableStateOf(settings.highSchoolName) }
    var elName by remember(settings) { mutableStateOf(settings.elementaryName) }
    var bibleName by remember(settings) { mutableStateOf(settings.bibleMemberName) }
    var biblePhone by remember(settings) { mutableStateOf(settings.bibleMemberPhone) }
    var webhookUrl by remember(settings) { mutableStateOf(settings.googleAppsScriptWebhookUrl) }
    var autoSync by remember(settings) { mutableStateOf(settings.autoSyncToDrive) }
    var pdfLines by remember(settings) { mutableStateOf(settings.includeHandwritingPracticeInPdf) }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. Gemini API 키 & 나노바나나 기본형 설정", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("🍌 나노바나나 기본형 이미지 생성", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("원본 플래너 사진에 과목별 색상을 입힌 예시 생성 (gemini-nano-banana-2.1)", fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                    Switch(checked = useNanoBanana, onCheckedChange = { useNanoBanana = it })
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Google Gemini API Key") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "숨김" else "보기")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Gemini 텍스트·비전 모델", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("gemini-4-hydrogen", "gemini-4-argon", "gemini-3.5-flash-lite").forEach { m ->
                        FilterChip(
                            selected = model == m,
                            onClick = { model = m },
                            label = { Text(m.removePrefix("gemini-"), fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2. 2인용 학생 이름 & 출력 옵션", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                OutlinedTextField(
                    value = hsName,
                    onValueChange = { hsName = it },
                    label = { Text("🎓 고등학생 이름/별칭") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = elName,
                    onValueChange = { elName = it },
                    label = { Text("🌱 초등학생 이름/별칭") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("PDF/인쇄용 손글씨 베껴쓰기 줄 포함", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("출력물 하단에 학생이 직접 볼펜으로 따라 쓸 빈 줄 추가", fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                    Switch(checked = pdfLines, onCheckedChange = { pdfLines = it })
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2.5 bible71 성경읽기 DB 연동 (김희균 전용)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                OutlinedTextField(
                    value = bibleName,
                    onValueChange = { bibleName = it },
                    label = { Text("bible71 등록 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = biblePhone,
                    onValueChange = { biblePhone = it },
                    label = { Text("bible71 전화번호") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = onSyncBibleNow, modifier = Modifier.fillMaxWidth()) {
                    Text("📖 bible71 성경읽기 기록 지금 동기화")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("3. 내 구글 드라이브 누적 연동", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "검사 결과(JSON 백업, 구글시트 호환 CSV, 리포트 PNG & PDF)를 내 구글 드라이브에 자동 저장합니다.",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                )
                OutlinedButton(onClick = onPickDriveFolder, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (settings.googleDriveTreeUri.isBlank()) "구글 드라이브 폴더 선택하여 연결" else "연결된 드라이브 폴더 변경 (연결됨 ✓)")
                }
                OutlinedTextField(
                    value = webhookUrl,
                    onValueChange = { webhookUrl = it },
                    label = { Text("Apps Script 웹훅 URL (드라이브 + 구글시트 자동 누적용, 선택)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("일일 검사 완료 시 자동 동기화", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Switch(checked = autoSync, onCheckedChange = { autoSync = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExportBackup, modifier = Modifier.weight(1f)) {
                        Text("전체 백업 내보내기")
                    }
                    OutlinedButton(onClick = onImportBackup, modifier = Modifier.weight(1f)) {
                        Text("백업 파일 복원")
                    }
                }
            }
        }

        Button(
            onClick = {
                onSaveSettings(
                    settings.copy(
                        geminiApiKey = apiKey,
                        geminiModel = model,
                        useNanoBananaBasic = useNanoBanana,
                        highSchoolName = hsName,
                        elementaryName = elName,
                        bibleMemberName = bibleName,
                        bibleMemberPhone = biblePhone,
                        googleAppsScriptWebhookUrl = webhookUrl,
                        autoSyncToDrive = autoSync,
                        includeHandwritingPracticeInPdf = pdfLines
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("설정 저장하기", fontWeight = FontWeight.Bold)
        }
    }
}

private fun buildDemoSampleWeek(studentType: StudentProfileType, studentName: String): List<DailyInspectionResult> {
    val today = LocalDate.now()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val fmt = DateTimeFormatter.ISO_LOCAL_DATE

    return if (studentType == StudentProfileType.HIGH_SCHOOL) {
        (0..5).map { offset ->
            val date = monday.plusDays(offset.toLong()).format(fmt)
            val totalMins = listOf(290, 260, 320, 210, 340, 360)[offset]
            val startCh = offset * 2 + 1
            DailyInspectionResult(
                id = "demo_hs_$offset",
                studentType = StudentProfileType.HIGH_SCHOOL,
                studentName = studentName,
                dateIso = date,
                habitScore = listOf(72, 76, 84, 68, 86, 90)[offset],
                gradeLabel = listOf("B", "B+", "A", "C+", "A", "A+")[offset],
                praiseOneLiner = "수·금요일 오답 복습 블록을 꾸준히 배치한 점이 매우 우수합니다.",
                improvementSummary = "과목명만 적는 습관을 버리고 [교재명 + 쪽수/문항수 + 예상시간]을 반드시 함께 기입하세요.",
                corrections = listOf(
                    PlanCorrectionItem("성경", "성경 읽기", "마가복음 ${startCh}~${startCh + 1}장 정독 후 bible71 완료 체크하기", "#말씀분량기록", 20, offset != 3),
                    PlanCorrectionItem("수학", "수학 문제집 풀기", "쎈 수학(상) 이차함수 B단계 p.64~68 (22문항) + 오답", "#분량미기재", 90, true),
                    PlanCorrectionItem("영어", "영어 단어랑 인강", "워드마스터 Day 15 암기(30분) + 구문 인강 1강 후 20분 백지복습", "#복습누락", 70, offset != 3),
                    PlanCorrectionItem("국어", "비문학 지문", "마더텅 비문학 사회 3지문 시간재기(45분) 및 근거 표시", "#시간모호", 50, true)
                ),
                timeBlocks = listOf(
                    TimeBlock(8, 20, 20, "성경", StudyMode.SELF_STUDY, "마가복음 ${startCh}장"),
                    TimeBlock(9, 0, 50, "국어", StudyMode.SELF_STUDY, "비문학"),
                    TimeBlock(10, 0, 90, "수학", StudyMode.SELF_STUDY, "쎈 B단계"),
                    TimeBlock(13, 30, 60, "영어", StudyMode.LECTURE, "구문 인강"),
                    TimeBlock(14, 30, 30, "영어", StudyMode.REVIEW, "백지복습"),
                    TimeBlock(19, 0, 60, "탐구", StudyMode.SELF_STUDY, "개념완성")
                ),
                totalStudyMinutes = totalMins,
                selfStudyMinutes = (totalMins * 0.58).toInt(),
                lectureMinutes = (totalMins * 0.25).toInt(),
                reviewMinutes = (totalMins * 0.17).toInt(),
                plannedTasksCount = 4,
                completedTasksCount = if (offset == 3) 2 else 4,
                weakHabitTags = if (offset < 2) listOf("#분량미기재", "#인강편중") else listOf("#오답시간확보"),
                copywritingPromise = "매일 성경읽기를 마치고 교재명·페이지·목표시간을 함께 적으며, 인강 후 20분 복습한다.",
                copywritingPractice = "내일 첫 시작: 오후 11시 20분 마가복음 ${startCh + 2}~${startCh + 3}장 읽고 쎈 수학 p.69~72 풀기!",
                bibleCompleted = offset != 3,
                biblePassageText = "마가복음 ${startCh}~${startCh + 1}장 (bible71 연동)",
                bibleChaptersCount = if (offset != 3) 2 else 0,
                keptYesterdayPromise = offset >= 4,
                biblePatternSummary = "주중·심야(KST 23시경) 읽기 비중 높음 · 평균 1.2일 간격 · 회당 2장",
                bibleNextSuggestion = "마가복음 ${startCh + 2}~${startCh + 3}장 (2장 이어 읽기)"
            )
        }
    } else {
        (0..5).map { offset ->
            val date = monday.plusDays(offset.toLong()).format(fmt)
            val totalMins = listOf(90, 90, 120, 60, 120, 90)[offset]
            DailyInspectionResult(
                id = "demo_el_$offset",
                studentType = StudentProfileType.ELEMENTARY,
                studentName = studentName,
                dateIso = date,
                habitScore = listOf(78, 82, 88, 74, 90, 92)[offset],
                gradeLabel = listOf("B+", "A", "A+", "B", "A+", "A+")[offset],
                praiseOneLiner = "매일 정해진 시간에 성경읽기와 플래너 색칠을 끝까지 해낸 점이 최고예요!",
                improvementSummary = "'성경 읽기', '숙제'라고만 쓰지 말고 책 이름과 몇 장/몇 쪽 풀지 숫자를 적어요.",
                corrections = listOf(
                    PlanCorrectionItem("성경", "성경 읽기", "잠언 ${offset + 1}장 소리 내어 읽고 플래너에 장수 쓰기", "#성경분량기재", 15, offset != 3),
                    PlanCorrectionItem("수학·연산", "수학 숙제", "기탄수학 D단계 3장(p.12~14) 풀고 채점하기", "#몇쪽인지쓰기", 30, true),
                    PlanCorrectionItem("국어·독서", "책 읽기", "과학 뒤집기 1단원(20쪽) 읽고 한 줄 느낌 쓰기", "#분량약속", 30, true),
                    PlanCorrectionItem("영어", "영어 공부", "영어 단어카드 10개 소리 내어 3번씩 읽기", "#구체적목표", 30, offset != 3)
                ),
                timeBlocks = listOf(
                    TimeBlock(14, 30, 30, "성경", StudyMode.SELF_STUDY, "잠언 ${offset + 1}장"),
                    TimeBlock(15, 0, 30, "학교·숙제", StudyMode.SELF_STUDY, "알림장 숙제"),
                    TimeBlock(16, 0, 30, "수학·연산", StudyMode.SELF_STUDY, "연산 3장"),
                    TimeBlock(17, 0, 30, "영어", StudyMode.LECTURE, "단어 읽기"),
                    TimeBlock(19, 30, 30, "국어·독서", StudyMode.REVIEW, "독서 20쪽")
                ),
                totalStudyMinutes = totalMins,
                selfStudyMinutes = (totalMins * 0.65).toInt(),
                lectureMinutes = (totalMins * 0.2).toInt(),
                reviewMinutes = (totalMins * 0.15).toInt(),
                plannedTasksCount = 4,
                completedTasksCount = if (offset == 3) 2 else 4,
                weakHabitTags = listOf("#성경분량기재", "#몇쪽인지쓰기"),
                copywritingPromise = "성경 몇 장 읽었는지, 무슨 책 몇 쪽 풀지 숫자로 꼭 적을게요!",
                copywritingPractice = "내일 첫 시작: 오후 3시 잠언 ${offset + 2}장 읽고 기탄수학 3장 풀기!",
                bibleCompleted = offset != 3,
                biblePassageText = "잠언 ${offset + 1}장 (종이 플래너 기록)",
                bibleChaptersCount = if (offset != 3) 1 else 0,
                keptYesterdayPromise = offset >= 4,
                biblePatternSummary = "종이 플래너 누적 · 평균 1.1일 간격 · 잠언 이어 읽기 중",
                bibleNextSuggestion = "잠언 ${offset + 2}장 (오늘 이어서 1장 읽기)"
            )
        }
    }
}
