/**
 * 플래너 코치 AI - 내 구글 드라이브 & 구글 스프레드시트 자동 누적 스크립트
 *
 * [설치 방법 (약 2분)]
 * 1. https://script.google.com 접속 → [새 프로젝트] 클릭
 * 2. 기존 코드를 모두 지우고 아래 코드를 그대로 붙여넣기
 * 3. 상단 [배포] → [새 배포] → 유형: [웹 앱]
 *    - 실행 주체: 나(본인)
 *    - 액세스 권한이 있는 사용자: 모든 사용자 (비밀 토큰 또는 본인 앱에서만 요청 전송)
 * 4. 생성된 '웹 앱 URL'을 복사하여 플래너 코치 AI 앱의 [설정 → Apps Script 웹훅 URL]에 붙여넣기
 *
 * 자동으로 내 구글 드라이브에 [플래너검사기_데이터] 폴더와
 * [고등학생_누적기록], [초등학생_누적기록] 하위 폴더 및 구글 스프레드시트가 생성됩니다.
 */

var ROOT_FOLDER_NAME = "플래너검사기_데이터";
var SHEET_FILE_NAME = "누적_플래너_학습기록";

function doPost(e) {
  try {
    var payload = JSON.parse(e.postData.contents);
    var action = payload.action || "save_daily_inspection";
    var rootFolder = getOrCreateFolder_(DriveApp.getRootFolder(), ROOT_FOLDER_NAME);
    var studentLabel = payload.studentLabel || "고등학생_누적기록";
    var studentFolder = getOrCreateFolder_(rootFolder, studentLabel);

    if (action === "save_daily_inspection") {
      var rec = payload.record;
      appendRecordToSheet_(rootFolder, rec);

      if (payload.backupJson) {
        upsertTextFile_(rootFolder, "planner_backup_db.json", payload.backupJson, MimeType.PLAIN_TEXT);
      }

      var stem = "일일리포트_" + rec.dateIso + "_" + String(rec.id || "").slice(0, 6);
      if (payload.pngBase64) {
        saveBase64File_(studentFolder, stem + ".png", payload.pngBase64, "image/png");
      }
      if (payload.pdfBase64) {
        saveBase64File_(studentFolder, stem + ".pdf", payload.pdfBase64, "application/pdf");
      }

      return jsonResponse_({ ok: true, message: "구글 드라이브 및 시트에 일일 기록 저장 완료", fileStem: stem });
    }

    if (action === "save_weekly_report") {
      var weekStem = payload.fileStem || ("주간리포트_" + Utilities.formatDate(new Date(), "Asia/Seoul", "yyyy-MM-dd"));
      if (payload.pngBase64) {
        saveBase64File_(studentFolder, weekStem + ".png", payload.pngBase64, "image/png");
      }
      if (payload.pdfBase64) {
        saveBase64File_(studentFolder, weekStem + ".pdf", payload.pdfBase64, "application/pdf");
      }
      return jsonResponse_({ ok: true, message: "구글 드라이브에 주간 리포트(PNG/PDF) 저장 완료", fileStem: weekStem });
    }

    return jsonResponse_({ ok: false, message: "지원하지 않는 action: " + action });
  } catch (err) {
    return jsonResponse_({ ok: false, error: String(err) });
  }
}

function doGet(e) {
  try {
    var rootFolder = getOrCreateFolder_(DriveApp.getRootFolder(), ROOT_FOLDER_NAME);
    var files = rootFolder.getFilesByName("planner_backup_db.json");
    if (files.hasNext()) {
      var content = files.next().getBlob().getDataAsString("UTF-8");
      return ContentService.createTextOutput(content).setMimeType(ContentService.MimeType.JSON);
    }
    return jsonResponse_({ schemaVersion: 1, records: [] });
  } catch (err) {
    return jsonResponse_({ ok: false, error: String(err) });
  }
}

function appendRecordToSheet_(rootFolder, rec) {
  var ss = getOrCreateSpreadsheet_(rootFolder, SHEET_FILE_NAME);
  var sheetName = rec.studentType === "ELEMENTARY" ? "초등학생_누적" : "고등학생_누적";
  var sheet = ss.getSheetByName(sheetName);
  if (!sheet) {
    sheet = ss.insertSheet(sheetName);
    sheet.appendRow([
      "기록ID", "날짜", "학생구분", "이름", "플래너점수", "등급",
      "총공부시간(분)", "자습(분)", "인강(분)", "복습(분)",
      "계획수", "완료수", "부족습관태그", "오늘의약속(필사)", "수정방향"
    ]);
    sheet.setFrozenRows(1);
  }

  // 동일한 기록ID가 있으면 업데이트, 없으면 새 행 추가
  var data = sheet.getDataRange().getValues();
  var targetRow = -1;
  for (var i = 1; i < data.length; i++) {
    if (String(data[i][0]) === String(rec.id)) {
      targetRow = i + 1;
      break;
    }
  }

  var rowValues = [
    rec.id,
    rec.dateIso,
    rec.studentType,
    rec.studentName,
    rec.habitScore,
    rec.gradeLabel,
    rec.totalStudyMinutes,
    rec.selfStudyMinutes,
    rec.lectureMinutes,
    rec.reviewMinutes,
    rec.plannedTasksCount,
    rec.completedTasksCount,
    (rec.weakHabitTags || []).join(" "),
    rec.copywritingPromise,
    rec.improvementSummary
  ];

  if (targetRow > 0) {
    sheet.getRange(targetRow, 1, 1, rowValues.length).setValues([rowValues]);
  } else {
    sheet.appendRow(rowValues);
  }
}

function getOrCreateFolder_(parent, name) {
  var it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}

function getOrCreateSpreadsheet_(folder, name) {
  var it = folder.getFilesByName(name);
  if (it.hasNext()) {
    return SpreadsheetApp.openById(it.next().getId());
  }
  var ss = SpreadsheetApp.create(name);
  var file = DriveApp.getFileById(ss.getId());
  file.moveTo(folder);
  return ss;
}

function upsertTextFile_(folder, fileName, text, mimeType) {
  var existing = folder.getFilesByName(fileName);
  while (existing.hasNext()) {
    existing.next().setTrashed(true);
  }
  folder.createFile(fileName, text, mimeType);
}

function saveBase64File_(folder, fileName, base64Str, mimeType) {
  var existing = folder.getFilesByName(fileName);
  while (existing.hasNext()) {
    existing.next().setTrashed(true);
  }
  var bytes = Utilities.base64Decode(base64Str);
  var blob = Utilities.newBlob(bytes, mimeType, fileName);
  folder.createFile(blob);
}

function jsonResponse_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
