package com.plannercoach.inspector.export

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.plannercoach.inspector.model.DailyInspectionResult
import com.plannercoach.inspector.model.WeeklyInsightReport
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 카카오톡 공유, A4 PDF 저장, 무선 프린터 직접 인쇄를 담당하는 유틸리티.
 */
class PdfPrintAndShareManager(private val context: Context) {

    companion object {
        private const val A4_WIDTH_PT = 595
        private const val A4_HEIGHT_PT = 842
        private const val PDF_MARGIN_PT = 24f
    }

    fun buildDailyShareMessage(result: DailyInspectionResult): String = buildString {
        val bibleLine = if (result.bibleCompleted) {
            "📖 성경읽기 완료: ${result.biblePassageText} (${result.bibleChaptersCount}장)"
        } else {
            "📖 성경읽기: ${result.biblePassageText.ifBlank { "오늘 성경 분량을 플래너에 적고 읽어보세요!" }}"
        }
        appendLine("[${result.studentType.badgeEmoji} ${result.studentName} · ${result.dateIso} 플래너 검사 리포트]")
        appendLine("• 플래너 점수: ${result.habitScore}점 (${result.gradeLabel})${if (result.keptYesterdayPromise) " 🎉 어제 약속 이행!" else ""}")
        appendLine("• 총 공부시간: ${formatMinutes(result.totalStudyMinutes)} (달성률 ${result.completionRatePercent}%)")
        appendLine("• $bibleLine")
        appendLine("• 잘한 점: ${result.praiseOneLiner}")
        appendLine("• 수정 방향: ${result.improvementSummary}")
        if (result.weakHabitTags.isNotEmpty()) {
            appendLine("• 보완 포인트: ${result.weakHabitTags.joinToString(" ")}")
        }
        appendLine()
        appendLine("✍️ [플래너 하단에 그대로 베껴쓰기]")
        appendLine("1) 약속: ${result.copywritingPromise}")
        appendLine("2) 수정/내일예약: ${result.copywritingPractice}")
    }

    fun buildWeeklyShareMessage(report: WeeklyInsightReport): String = buildString {
        appendLine("[📊 ${report.studentName} · ${report.weekLabel} 주간 공부·말씀 분석 리포트]")
        appendLine("• 누적 분석 단계: ${report.stage.badgeText}")
        appendLine("• 총 공부시간: ${formatMinutes(report.totalStudyMinutes)} / 실행률 ${report.completionRatePercent}% / 순공비율 ${report.selfStudyRatioPercent}%")
        appendLine("• ${report.bibleSummaryLine}")
        appendLine("• 💡 베스트: ${report.bestPatternInsight}")
        appendLine("• ⚠️ 코칭: ${report.warningHabitInsight}")
        appendLine("• 🔮 예측: ${report.predictionInsight}")
        appendLine()
        appendLine("🎯 [다음 1주일 목표]")
        report.nextWeekGoals.forEachIndexed { i, g ->
            appendLine("${i + 1}. ${g.title}: ${g.targetMetric} (${g.actionTip})")
        }
        appendLine()
        appendLine("✍️ [다음 주 다짐 베껴쓰기]")
        appendLine(report.weeklyCopywritingPromise)
    }

    fun shareToKakaoTalk(bitmap: Bitmap, messageText: String, filePrefix: String) {
        copyTextToClipboard(messageText)
        val imageFile = saveBitmapToCachePng(bitmap, filePrefix)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, messageText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val kakaoInstalled = runCatching {
            context.packageManager.getPackageInfo("com.kakao.talk", 0)
            true
        }.getOrDefault(false)

        if (kakaoInstalled) {
            shareIntent.`package` = "com.kakao.talk"
            runCatching {
                context.startActivity(shareIntent)
                return
            }
        }

        val chooser = Intent.createChooser(
            shareIntent.apply { `package` = null },
            "카카오톡 또는 메신저로 리포트 보내기"
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * 고해상도 리포트 이미지를 A4 규격 PDF 바이트로 변환하고 다운로드/공유 가능한 파일로 저장한다.
     */
    fun generateA4PdfBytes(bitmap: Bitmap): ByteArray {
        val doc = PdfDocument()
        val usableW = A4_WIDTH_PT - PDF_MARGIN_PT * 2
        val usableH = A4_HEIGHT_PT - PDF_MARGIN_PT * 2
        val scale = usableW / bitmap.width.toFloat()
        val scaledTotalHeight = bitmap.height * scale

        if (scaledTotalHeight <= usableH) {
            val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, 1).create()
            val page = doc.startPage(pageInfo)
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(PDF_MARGIN_PT, PDF_MARGIN_PT)
            }
            page.canvas.drawBitmap(bitmap, matrix, null)
            doc.finishPage(page)
        } else {
            // Split cleanly across multiple A4 pages when content is very tall
            val sliceHeightPx = (usableH / scale).toInt().coerceAtLeast(200)
            var offsetY = 0
            var pageNum = 1
            while (offsetY < bitmap.height) {
                val h = minOf(sliceHeightPx, bitmap.height - offsetY)
                val slice = Bitmap.createBitmap(bitmap, 0, offsetY, bitmap.width, h)
                val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, pageNum++).create()
                val page = doc.startPage(pageInfo)
                val destRect = RectF(
                    PDF_MARGIN_PT,
                    PDF_MARGIN_PT,
                    PDF_MARGIN_PT + usableW,
                    PDF_MARGIN_PT + h * scale
                )
                page.canvas.drawBitmap(slice, null, destRect, null)
                doc.finishPage(page)
                slice.recycle()
                offsetY += h
            }
        }

        val baos = ByteArrayOutputStream()
        doc.writeTo(baos)
        doc.close()
        return baos.toByteArray()
    }

    fun saveA4PdfToDevice(bitmap: Bitmap, fileName: String): Pair<Uri, ByteArray> {
        val pdfBytes = generateA4PdfBytes(bitmap)
        val safeName = if (fileName.endsWith(".pdf")) fileName else "$fileName.pdf"

        // Save to Downloads via MediaStore on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PlannerCoach")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pdfBytes) }
                return uri to pdfBytes
            }
        }

        // Fallback to app internal files/reports + FileProvider URI
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val file = File(dir, safeName)
        FileOutputStream(file).use { it.write(pdfBytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return uri to pdfBytes
    }

    fun openOrSharePdf(pdfUri: Uri, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, pdfUri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "PDF 내보내기 / 열기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * 안드로이드 PrintManager를 통해 무선 프린터 인쇄 또는 시스템 'PDF로 저장' UI를 즉시 호출한다.
     */
    fun printReportBitmap(activity: Activity, jobName: String, bitmap: Bitmap) {
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = object : PrintDocumentAdapter() {
            private var pdfDocument: PrintedPdfDocument? = null
            private var pageCount = 1

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                pdfDocument = PrintedPdfDocument(activity, newAttributes)
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val pageW = (newAttributes.mediaSize?.widthMils ?: 8268) * 72f / 1000f
                val pageH = (newAttributes.mediaSize?.heightMils ?: 11693) * 72f / 1000f
                val usableW = (pageW - 48f).coerceAtLeast(200f)
                val usableH = (pageH - 48f).coerceAtLeast(200f)
                val scaledH = bitmap.height * (usableW / bitmap.width.toFloat())
                pageCount = kotlin.math.ceil((scaledH / usableH).toDouble()).toInt().coerceAtLeast(1)

                val info = PrintDocumentInfo.Builder(jobName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal,
                callback: WriteResultCallback
            ) {
                val doc = pdfDocument ?: return
                val samplePage = doc.startPage(0)
                val pageW = samplePage.info.pageWidth.toFloat()
                val pageH = samplePage.info.pageHeight.toFloat()
                doc.finishPage(samplePage)
                doc.close()

                val freshDoc = PdfDocument()
                val usableW = pageW - 48f
                val usableH = pageH - 48f
                val scale = usableW / bitmap.width.toFloat()
                val slicePx = (usableH / scale).toInt().coerceAtLeast(200)

                var offsetY = 0
                var idx = 1
                while (offsetY < bitmap.height) {
                    if (cancellationSignal.isCanceled) {
                        freshDoc.close()
                        callback.onWriteCancelled()
                        return
                    }
                    val h = minOf(slicePx, bitmap.height - offsetY)
                    val slice = Bitmap.createBitmap(bitmap, 0, offsetY, bitmap.width, h)
                    val pageInfo = PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), idx++).create()
                    val page = freshDoc.startPage(pageInfo)
                    page.canvas.drawBitmap(
                        slice,
                        null,
                        RectF(24f, 24f, 24f + usableW, 24f + h * scale),
                        null
                    )
                    freshDoc.finishPage(page)
                    slice.recycle()
                    offsetY += h
                }

                runCatching {
                    FileOutputStream(destination.fileDescriptor).use { out ->
                        freshDoc.writeTo(out)
                    }
                    freshDoc.close()
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }.onFailure { err ->
                    freshDoc.close()
                    callback.onWriteFailed(err.message)
                }
            }
        }

        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        printManager.print(jobName, adapter, attrs)
    }

    fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 95, baos)
        return baos.toByteArray()
    }

    private fun saveBitmapToCachePng(bitmap: Bitmap, prefix: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
        return file
    }

    private fun copyTextToClipboard(text: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("플래너 코치 리포트", text))
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
