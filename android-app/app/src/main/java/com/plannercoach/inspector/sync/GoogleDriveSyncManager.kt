package com.plannercoach.inspector.sync

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.plannercoach.inspector.data.AppSettings
import com.plannercoach.inspector.data.PlannerRepository
import com.plannercoach.inspector.model.DailyInspectionResult
import com.plannercoach.inspector.model.StudentProfileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DriveSyncReport(
    val success: Boolean,
    val message: String
)

/**
 * 사용자의 Google Drive에 누적 학습 기록(JSON 백업, 구글시트/엑셀 호환 CSV, 리포트 PNG/PDF)을 저장하는 매니저.
 * 1) 안드로이드 SAF(Google Drive 폴더 직접 연결) 저장
 * 2) Google Apps Script 웹훅을 통한 Google Drive + Google Sheets 자동 누적
 */
class GoogleDriveSyncManager(
    private val context: Context,
    private val repository: PlannerRepository
) {

    suspend fun syncDailyInspection(
        settings: AppSettings,
        result: DailyInspectionResult,
        pngBytes: ByteArray?,
        pdfBytes: ByteArray?
    ): DriveSyncReport = withContext(Dispatchers.IO) {
        val channels = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // Channel 1: SAF linked Google Drive folder
        if (settings.googleDriveTreeUri.isNotBlank()) {
            runCatching {
                writeDailyToSafFolder(
                    treeUriString = settings.googleDriveTreeUri,
                    result = result,
                    pngBytes = pngBytes,
                    pdfBytes = pdfBytes
                )
                channels.add("드라이브 연결 폴더")
            }.onFailure { errors.add("폴더 저장 실패: ${it.message}") }
        }

        // Channel 2: Google Apps Script Webhook (Drive + Google Sheets)
        if (settings.googleAppsScriptWebhookUrl.isNotBlank()) {
            runCatching {
                postDailyToAppsScript(
                    webhookUrl = settings.googleAppsScriptWebhookUrl,
                    result = result,
                    pngBytes = pngBytes,
                    pdfBytes = pdfBytes
                )
                channels.add("구글 드라이브·시트 웹훅")
            }.onFailure { errors.add("웹훅 전송 실패: ${it.message}") }
        }

        if (channels.isNotEmpty()) {
            repository.markSynced(result.id, true)
            DriveSyncReport(true, "${channels.joinToString(" & ")}에 자동 누적 완료!")
        } else if (errors.isNotEmpty()) {
            DriveSyncReport(false, errors.joinToString(" / "))
        } else {
            DriveSyncReport(false, "설정에서 구글 드라이브 폴더 또는 Apps Script URL을 연결하면 자동 누적됩니다.")
        }
    }

    suspend fun syncWeeklyReportFiles(
        settings: AppSettings,
        studentType: StudentProfileType,
        weekFileStem: String,
        pngBytes: ByteArray,
        pdfBytes: ByteArray
    ): DriveSyncReport = withContext(Dispatchers.IO) {
        val channels = mutableListOf<String>()
        if (settings.googleDriveTreeUri.isNotBlank()) {
            runCatching {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(settings.googleDriveTreeUri))
                    ?: error("드라이브 폴더 접근 권한이 만료되었습니다.")
                val studentDir = getOrCreateSubDir(root, folderNameFor(studentType))
                writeBinaryFile(studentDir, "${weekFileStem}.png", "image/png", pngBytes)
                writeBinaryFile(studentDir, "${weekFileStem}.pdf", "application/pdf", pdfBytes)
                channels.add("드라이브 연결 폴더")
            }
        }
        if (settings.googleAppsScriptWebhookUrl.isNotBlank()) {
            runCatching {
                val body = JSONObject().apply {
                    put("action", "save_weekly_report")
                    put("studentType", studentType.id)
                    put("studentLabel", folderNameFor(studentType))
                    put("fileStem", weekFileStem)
                    put("pngBase64", Base64.encodeToString(pngBytes, Base64.NO_WRAP))
                    put("pdfBase64", Base64.encodeToString(pdfBytes, Base64.NO_WRAP))
                }
                postJsonWebhook(settings.googleAppsScriptWebhookUrl, body)
                channels.add("구글 드라이브 웹훅")
            }
        }
        if (channels.isNotEmpty()) {
            DriveSyncReport(true, "${channels.joinToString(" & ")}에 주간 리포트(PNG/PDF) 저장 완료!")
        } else {
            DriveSyncReport(false, "구글 드라이브가 연결되지 않아 기기에만 저장되었습니다.")
        }
    }

    private suspend fun writeDailyToSafFolder(
        treeUriString: String,
        result: DailyInspectionResult,
        pngBytes: ByteArray?,
        pdfBytes: ByteArray?
    ) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString))
            ?: error("연결된 드라이브 폴더를 열 수 없습니다.")
        val studentDir = getOrCreateSubDir(root, folderNameFor(result.studentType))

        // 1. Full DB Backup JSON
        val backupJson = repository.exportAllToBackupJson()
        writeTextFile(root, "planner_backup_db.json", "application/json", backupJson)

        // 2. Student Cumulative CSV (Excel / Google Sheets compatible with UTF-8 BOM)
        val allForStudent = repository.getInspections(result.studentType)
        val csv = buildStudentCsv(allForStudent)
        writeBinaryFile(studentDir, "누적학습기록_${result.studentName}.csv", "text/csv", csv)

        // 3. Report PNG & PDF
        val stem = "일일리포트_${result.dateIso}_${result.id.take(6)}"
        if (pngBytes != null) {
            writeBinaryFile(studentDir, "$stem.png", "image/png", pngBytes)
        }
        if (pdfBytes != null) {
            writeBinaryFile(studentDir, "$stem.pdf", "application/pdf", pdfBytes)
        }
    }

    private suspend fun postDailyToAppsScript(
        webhookUrl: String,
        result: DailyInspectionResult,
        pngBytes: ByteArray?,
        pdfBytes: ByteArray?
    ) {
        val payload = JSONObject().apply {
            put("action", "save_daily_inspection")
            put("studentLabel", folderNameFor(result.studentType))
            put("record", result.toJson())
            put("backupJson", repository.exportAllToBackupJson())
            if (pngBytes != null) put("pngBase64", Base64.encodeToString(pngBytes, Base64.NO_WRAP))
            if (pdfBytes != null) put("pdfBase64", Base64.encodeToString(pdfBytes, Base64.NO_WRAP))
        }
        postJsonWebhook(webhookUrl, payload)
    }

    private fun postJsonWebhook(webhookUrl: String, payload: JSONObject) {
        val conn = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..399) {
            error("HTTP $code")
        }
    }

    private fun buildStudentCsv(records: List<DailyInspectionResult>): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("날짜,학생구분,이름,플래너점수,등급,총공부시간(분),자습(분),인강(분),복습(분),계획수,완료수,달성률(%),부족한습관태그,오늘의약속,수정방향")
        records.sortedBy { it.dateIso }.forEach { r ->
            val cols = listOf(
                r.dateIso,
                r.studentType.displayName,
                r.studentName,
                r.habitScore.toString(),
                r.gradeLabel,
                r.totalStudyMinutes.toString(),
                r.selfStudyMinutes.toString(),
                r.lectureMinutes.toString(),
                r.reviewMinutes.toString(),
                r.plannedTasksCount.toString(),
                r.completedTasksCount.toString(),
                r.completionRatePercent.toString(),
                r.weakHabitTags.joinToString(" "),
                r.copywritingPromise,
                r.improvementSummary
            ).joinToString(",") { escapeCsv(it) }
            sb.appendLine(cols)
        }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        return bom + sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escapeCsv(value: String): String {
        val clean = value.replace("\n", " ").replace("\"", "\"\"")
        return "\"$clean\""
    }

    private fun folderNameFor(studentType: StudentProfileType): String = when (studentType) {
        StudentProfileType.HIGH_SCHOOL -> "고등학생_누적기록"
        StudentProfileType.ELEMENTARY -> "초등학생_누적기록"
    }

    private fun getOrCreateSubDir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: error("하위 폴더 '$name' 생성 실패")

    private fun writeTextFile(dir: DocumentFile, name: String, mimeType: String, content: String) {
        writeBinaryFile(dir, name, mimeType, content.toByteArray(Charsets.UTF_8))
    }

    private fun writeBinaryFile(dir: DocumentFile, name: String, mimeType: String, bytes: ByteArray) {
        dir.findFile(name)?.delete()
        val file = dir.createFile(mimeType, name) ?: error("파일 '$name' 생성 실패")
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            out.write(bytes)
        }
    }
}
