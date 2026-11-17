(() => {
  "use strict";

  const STORAGE_RECORDS_KEY = "planner_coach_records_v1";
  const STORAGE_SETTINGS_KEY = "planner_coach_settings_v1";
  const STORAGE_BIBLE71_CACHE_KEY = "planner_bible71_cache_v1";
  const GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1/interactions";
  const GEMINI_BETA_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions";
  const NANO_BANANA_MODEL = "gemini-nano-banana-2.1";
  const BIBLE71_SUPABASE_URL = "https://zeitxdnomtweyniblynu.supabase.co";
  const BIBLE71_SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InplaXR4ZG5vbXR3ZXluaWJseW51Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODgyNDE3MzksImV4cCI6MjEwMzgxNzczOX0.oUOB2A1xt2TpY9Z1hoUrdecI2QdmaxfBQHCF9pjq57c";

  const FOCUS_HABIT_LABELS = {
    BIBLE_AND_VOLUME: "1단계: 매일 성경읽기 + [교재명·쪽수] 구체적 분량 쓰기",
    COLOR_TIMETABLE: "2단계: 공부한 시간만큼 과목별 색깔로 타임테이블 칠하기",
    SELF_REVIEW_PAIR: "3단계: 인강/수업 직후 20분 자습·복습 블록 짝짓기",
    NEXT_DAY_RESERVE: "4단계: 하루 마감 & 내일 첫 공부·말씀 시간 미리 예약하기"
  };

  const PROFILES = {
    HIGH_SCHOOL: {
      id: "HIGH_SCHOOL",
      displayName: "고등학생",
      emoji: "🎓",
      slotMinutes: 10,
      startHour: 8,
      endHour: 24,
      subtitle: "10분 텐미닛 · 순공/인강 구분 · bible71 성경읽기 연동",
      legend: [
        { name: "성경", color: "#0D9488" },
        { name: "국어", color: "#3B82F6" },
        { name: "수학", color: "#EF4444" },
        { name: "영어", color: "#F59E0B" },
        { name: "탐구", color: "#10B981" },
        { name: "복습/기타", color: "#8B5CF6" }
      ]
    },
    ELEMENTARY: {
      id: "ELEMENTARY",
      displayName: "초등학생",
      emoji: "🌱",
      slotMinutes: 30,
      startHour: 14,
      endHour: 21,
      subtitle: "30분 블록 · 파스텔 5색 · 종이 플래너 성경읽기",
      legend: [
        { name: "성경", color: "#0D9488" },
        { name: "국어·독서", color: "#60A5FA" },
        { name: "수학·연산", color: "#F87171" },
        { name: "영어", color: "#FBBF24" },
        { name: "학교·숙제", color: "#34D399" }
      ]
    }
  };

  const state = {
    activeStudent: "HIGH_SCHOOL",
    activeTab: "home",
    weekOffset: 0,
    records: [],
    bible71Logs: [],
    bible71LastSyncAt: null,
    settings: {
      geminiApiKey: "",
      geminiModel: "gemini-4-hydrogen",
      useNanoBanana: true,
      highSchoolName: "김희균",
      elementaryName: "초등학생",
      bibleMemberName: "김희균",
      bibleMemberPhone: "01023708994",
      focusHabit: "BIBLE_AND_VOLUME",
      googleAppsScriptWebhookUrl: "",
      autoSyncToDrive: true,
      includeHandwritingPracticeInPdf: true
    },
    uploadedImageDataUrl: null,
    currentDailyResult: null,
    currentDailyCanvas: null,
    currentWeeklyReport: null,
    currentWeeklyCanvas: null,
    driveDirHandle: null
  };

  const $ = (id) => document.getElementById(id);

  function showToast(msg) {
    const box = $("toastBox");
    box.textContent = msg;
    box.classList.add("show");
    clearTimeout(showToast._timer);
    showToast._timer = setTimeout(() => box.classList.remove("show"), 3400);
  }

  function loadPersisted() {
    try {
      const rawSettings = localStorage.getItem(STORAGE_SETTINGS_KEY);
      if (rawSettings) {
        state.settings = { ...state.settings, ...JSON.parse(rawSettings) };
      }
    } catch (_) {}

    try {
      const rawRecords = localStorage.getItem(STORAGE_RECORDS_KEY);
      if (rawRecords) {
        const arr = JSON.parse(rawRecords);
        if (Array.isArray(arr)) state.records = arr;
      }
    } catch (_) {}

    try {
      const rawBible = localStorage.getItem(STORAGE_BIBLE71_CACHE_KEY);
      if (rawBible) {
        const parsed = JSON.parse(rawBible);
        if (Array.isArray(parsed.logs)) state.bible71Logs = parsed.logs;
        if (parsed.lastSyncAt) state.bible71LastSyncAt = parsed.lastSyncAt;
      }
    } catch (_) {}
  }

  function saveBible71Cache() {
    try {
      localStorage.setItem(STORAGE_BIBLE71_CACHE_KEY, JSON.stringify({
        logs: state.bible71Logs,
        lastSyncAt: state.bible71LastSyncAt
      }));
    } catch (_) {}
  }

  function saveRecords() {
    localStorage.setItem(STORAGE_RECORDS_KEY, JSON.stringify(state.records));
  }

  function saveSettings() {
    localStorage.setItem(STORAGE_SETTINGS_KEY, JSON.stringify(state.settings));
  }

  function studentName(profileId = state.activeStudent) {
    return profileId === "HIGH_SCHOOL"
      ? (state.settings.highSchoolName || "김희균").trim()
      : (state.settings.elementaryName || "초등학생").trim();
  }

  function colorForSubject(profileId, subject = "") {
    const s = String(subject);
    if (s.includes("성경") || s.includes("말씀") || s.includes("맥체인") || s.includes("QT") || s.includes("묵상") || s.includes("교리")) {
      return "#0D9488";
    }
    if (profileId === "HIGH_SCHOOL") {
      if (s.includes("국어") || s.includes("문학") || s.includes("독서")) return "#3B82F6";
      if (s.includes("수학") || s.includes("미적") || s.includes("확통")) return "#EF4444";
      if (s.includes("영어")) return "#F59E0B";
      if (s.includes("탐구") || s.includes("과학") || s.includes("사회") || s.includes("물리") || s.includes("화학") || s.includes("생명")) return "#10B981";
      if (s.includes("한국사") || s.includes("역사")) return "#06B6D4";
      return "#8B5CF6";
    }
    if (s.includes("국어") || s.includes("독서") || s.includes("책")) return "#60A5FA";
    if (s.includes("수학") || s.includes("연산")) return "#F87171";
    if (s.includes("영어")) return "#FBBF24";
    if (s.includes("숙제") || s.includes("학교") || s.includes("학원")) return "#34D399";
    return "#A78BFA";
  }

  // ==================== bible71 Supabase Integration (김희균 전용) ====================

  function formatPhoneVariants(rawPhone) {
    const digits = String(rawPhone || "01023708994").replace(/[^0-9]/g, "");
    if (digits.length === 11) {
      return [digits, `${digits.slice(0, 3)}-${digits.slice(3, 7)}-${digits.slice(7)}`];
    }
    return [digits || "01023708994"];
  }

  function enrichBible71Row(row) {
    const category = row.category || "";
    const dateIso = row.date || "";
    let passage = row.title || row.item_id || "";
    let chaptersCount = 1;

    if (category === "mccheyne") {
      const mc = typeof window.lookupMcCheyneByDateIso === "function"
        ? window.lookupMcCheyneByDateIso(dateIso)
        : null;
      if (mc) {
        passage = `맥체인 (${mc.date}): ${mc.passages}`;
        chaptersCount = mc.chaptersCount || 4;
      } else {
        chaptersCount = 4;
      }
    } else if (category === "dailybible") {
      passage = `매일성경: ${row.title || dateIso}`;
      chaptersCount = 1;
    } else if (category === "bible_reading") {
      passage = `성경통독: ${row.title || row.item_id}`;
      const rangeMatch = String(passage).match(/(\d+)\s*[-~]\s*(\d+)/);
      chaptersCount = rangeMatch ? Math.max(1, Number(rangeMatch[2]) - Number(rangeMatch[1]) + 1) : 1;
    } else if (category === "catechism") {
      passage = `날마다 교리: ${row.title || row.item_id}`;
      chaptersCount = 0;
    }

    return {
      id: row.id,
      date: dateIso,
      category,
      itemId: row.item_id,
      title: row.title || "",
      passage,
      chaptersCount,
      completedAt: row.completed_at || ""
    };
  }

  async function fetchBible71LogsFromDb(showFeedback = false) {
    const memberName = (state.settings.bibleMemberName || "김희균").trim();
    const phones = formatPhoneVariants(state.settings.bibleMemberPhone || "01023708994");
    const orFilters = [
      ...phones.map((p) => `member_phone.eq.${encodeURIComponent(p)}`),
      `member_name.eq.${encodeURIComponent(memberName)}`
    ].join(",");

    const url = `${BIBLE71_SUPABASE_URL}/rest/v1/reading_logs?or=(${orFilters})&is_completed=eq.true&select=*&order=date.desc&limit=500`;
    try {
      const res = await fetch(url, {
        headers: {
          apikey: BIBLE71_SUPABASE_ANON_KEY,
          Authorization: `Bearer ${BIBLE71_SUPABASE_ANON_KEY}`
        }
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const rows = await res.json();
      if (Array.isArray(rows)) {
        state.bible71Logs = rows.map(enrichBible71Row);
        state.bible71LastSyncAt = new Date().toISOString();
        saveBible71Cache();
        updateBibleUiPanels();
        refreshAllViews();
        if (showFeedback) {
          showToast(`bible71에서 ${memberName} 성경읽기 기록 ${state.bible71Logs.length}건을 불러왔습니다!`);
        }
        return state.bible71Logs;
      }
    } catch (err) {
      if (showFeedback) {
        showToast(`bible71 DB 조회 실패(캐시 ${state.bible71Logs.length}건 사용): ${err.message}`);
      }
    }
    return state.bible71Logs;
  }

  function getBibleStatusForDate(profileId, dateIso) {
    if (profileId === "HIGH_SCHOOL") {
      const dayLogs = state.bible71Logs.filter((l) => l.date === dateIso);
      const mcToday = typeof window.lookupMcCheyneByDateIso === "function"
        ? window.lookupMcCheyneByDateIso(dateIso)
        : null;
      if (dayLogs.length > 0) {
        const chaptersCount = dayLogs.reduce((s, l) => s + (l.chaptersCount || 0), 0);
        const passageText = dayLogs.map((l) => l.passage).join(" · ");
        return {
          source: "bible71",
          completed: true,
          chaptersCount: Math.max(1, chaptersCount),
          passageText,
          logs: dayLogs,
          suggestedMcCheyne: mcToday
        };
      }
      return {
        source: "bible71",
        completed: false,
        chaptersCount: 0,
        passageText: "오늘 성경읽기 미기록",
        logs: []
      };
    }

    // ELEMENTARY: read from saved record on dateIso or fallback to form state
    const existing = getStudentRecords("ELEMENTARY").find((r) => r.dateIso === dateIso);
    if (existing && typeof existing.bibleCompleted === "boolean") {
      return {
        source: "planner_paper",
        completed: existing.bibleCompleted,
        chaptersCount: Number(existing.bibleChaptersCount) || 0,
        passageText: existing.biblePassageText || (existing.bibleCompleted ? "종이 플래너 성경읽기 완료" : "미기록"),
        logs: []
      };
    }
    return {
      source: "planner_paper",
      completed: false,
      chaptersCount: 0,
      passageText: "종이 플래너 검사 대기",
      logs: []
    };
  }

  function getBiblePatternForStudent(profileId, dateIso) {
    if (typeof window.analyzeBibleReadingPattern !== "function") {
      return {
        patternSummary: "성경읽기 패턴 분석 준비 중",
        nextDaySuggestion: "내일 성경 2장 읽고 기록하기"
      };
    }
    if (profileId === "HIGH_SCHOOL") {
      return window.analyzeBibleReadingPattern(state.bible71Logs, dateIso, "HIGH_SCHOOL");
    }
    const elemEntries = getStudentRecords("ELEMENTARY")
      .filter((r) => r.bibleCompleted)
      .map((r) => ({
        date: r.dateIso,
        passage: r.biblePassageText,
        chaptersCount: r.bibleChaptersCount || 1,
        completedAt: r.createdAtEpochMs ? new Date(r.createdAtEpochMs).toISOString() : null
      }));
    return window.analyzeBibleReadingPattern(elemEntries, dateIso, "ELEMENTARY");
  }

  function updateBibleUiPanels() {
    const dateIso = $("inputInspectDate")?.value || toIsoDate(new Date());
    const mon = getMondayOfWeek(state.weekOffset);
    const weekDates = Array.from({ length: 7 }, (_, i) =>
      toIsoDate(new Date(mon.getFullYear(), mon.getMonth(), mon.getDate() + i))
    );
    const pattern = getBiblePatternForStudent(state.activeStudent, dateIso);

    if (state.activeStudent === "HIGH_SCHOOL") {
      $("bible71InspectBox").style.display = "block";
      $("elemBibleInspectBox").style.display = "none";

      const st = getBibleStatusForDate("HIGH_SCHOOL", dateIso);
      $("bible71InspectBox").classList.toggle("missing", !st.completed);
      $("bible71InspectTitle").textContent = st.completed
        ? `📖 bible71 성경읽기 완료 (${studentName("HIGH_SCHOOL")} · ${st.chaptersCount}장)`
        : `⏳ bible71 성경읽기 미기록 (${dateIso})`;
      $("bible71InspectDetail").textContent = st.completed
        ? st.passageText
        : `선택 날짜(${dateIso})에는 아직 성경읽기 기록이 없습니다.`;
      $("biblePatternDetail").textContent = `📊 날짜별 패턴: ${pattern.patternSummary}\n👉 다음 날 제안: ${pattern.nextDaySuggestion}`;

      // Home summary card
      const weekDoneDays = weekDates.filter((d) => getBibleStatusForDate("HIGH_SCHOOL", d).completed);
      const weekChapters = weekDoneDays.reduce((s, d) => s + getBibleStatusForDate("HIGH_SCHOOL", d).chaptersCount, 0);
      const latestLog = state.bible71Logs[0];
      $("homeBibleTitle").textContent = `📖 ${studentName("HIGH_SCHOOL")} 성경읽기 패턴 & 제안 (bible71 연동)`;
      $("homeBibleStreakPill").textContent = `이번 주 ${weekDoneDays.length}/7일 (${weekChapters}장)`;
      $("homeBibleSummaryBox").classList.toggle("missing", weekDoneDays.length === 0);
      $("homeBibleStatusTitle").textContent = latestLog
        ? `최근 기록: ${latestLog.date} · ${latestLog.passage}`
        : "bible71 DB에서 기록을 조회 중입니다...";
      $("homeBibleStatusDetail").textContent = `📊 패턴: ${pattern.patternSummary}\n👉 ${pattern.nextDaySuggestion}`;
    } else {
      $("bible71InspectBox").style.display = "none";
      $("elemBibleInspectBox").style.display = "block";

      const elemRecs = getStudentRecords("ELEMENTARY");
      const weekDoneDays = weekDates.filter((d) => elemRecs.some((r) => r.dateIso === d && r.bibleCompleted));
      const weekChapters = weekDoneDays.reduce((s, d) => {
        const rec = elemRecs.find((r) => r.dateIso === d && r.bibleCompleted);
        return s + (Number(rec?.bibleChaptersCount) || 1);
      }, 0);
      const todaySt = getBibleStatusForDate("ELEMENTARY", dateIso);

      $("elemBiblePatternDetail").textContent = `📊 패턴: ${pattern.patternSummary}\n👉 다음 날 제안: ${pattern.nextDaySuggestion}`;
      $("homeBibleTitle").textContent = `📖 ${studentName("ELEMENTARY")} 종이 플래너 성경읽기`;
      $("homeBibleStreakPill").textContent = `이번 주 ${weekDoneDays.length}/7일 (${weekChapters}장)`;
      $("homeBibleSummaryBox").classList.toggle("missing", weekDoneDays.length === 0);
      $("homeBibleStatusTitle").textContent = `오늘(${dateIso}) 기록: ${todaySt.completed ? `✅ ${todaySt.passageText}` : "종이 플래너 검사 대기/미기록"}`;
      $("homeBibleStatusDetail").textContent = `📊 패턴: ${pattern.patternSummary}\n👉 ${pattern.nextDaySuggestion}`;
    }
  }

  function stageFromDays(days) {
    if (days <= 3) return { level: 1, title: "씨앗기", badgeText: "Lv.1 씨앗기 (1~3일)" };
    if (days <= 7) return { level: 2, title: "새싹기", badgeText: "Lv.2 새싹기 (4~7일)" };
    if (days <= 20) return { level: 3, title: "성장기", badgeText: "Lv.3 성장기 (8~20일)" };
    return { level: 4, title: "예측기", badgeText: "Lv.4 예측기 (21일+)" };
  }

  function formatMinutes(minutes) {
    const mins = Math.max(0, Math.round(minutes || 0));
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    if (h > 0 && m > 0) return `${h}시간 ${m}분`;
    if (h > 0) return `${h}시간`;
    return `${m}분`;
  }

  function formatShortMinutes(minutes) {
    const mins = Math.max(0, Math.round(minutes || 0));
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    if (h > 0 && m > 0) return `${h}h${m}m`;
    if (h > 0) return `${h}h`;
    return `${m}m`;
  }

  function toIsoDate(date) {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, "0");
    const d = String(date.getDate()).padStart(2, "0");
    return `${y}-${m}-${d}`;
  }

  function getMondayOfWeek(weekOffset = 0) {
    const now = new Date();
    const day = now.getDay(); // 0=Sun..6=Sat
    const diffToMon = day === 0 ? -6 : 1 - day;
    const mon = new Date(now.getFullYear(), now.getMonth(), now.getDate() + diffToMon - weekOffset * 7);
    mon.setHours(0, 0, 0, 0);
    return mon;
  }

  function getStudentRecords(profileId = state.activeStudent) {
    return state.records
      .filter((r) => r.studentType === profileId)
      .sort((a, b) => (b.dateIso || "").localeCompare(a.dateIso || "") || (b.createdAtEpochMs || 0) - (a.createdAtEpochMs || 0));
  }

  function computeSubjectBreakdowns(record) {
    const map = new Map();
    const blocks = record.timeBlocks || [];
    for (const b of blocks) {
      map.set(b.subject, (map.get(b.subject) || 0) + (b.durationMinutes || 0));
    }
    if (map.size === 0) {
      for (const c of record.corrections || []) {
        map.set(c.subject, (map.get(c.subject) || 0) + (c.targetMinutes || 0));
      }
    }
    return [...map.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([subject, minutes]) => ({
        subject,
        minutes,
        colorHex: colorForSubject(record.studentType, subject)
      }));
  }

  function completionRate(record) {
    if (!record.plannedTasksCount || record.plannedTasksCount <= 0) return 0;
    return Math.min(100, Math.max(0, Math.round((record.completedTasksCount * 100) / record.plannedTasksCount)));
  }

  // ==================== Canvas Drawing Helpers ====================

  function roundRectPath(ctx, x, y, w, h, r) {
    const radius = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + radius, y);
    ctx.arcTo(x + w, y, x + w, y + h, radius);
    ctx.arcTo(x + w, y + h, x, y + h, radius);
    ctx.arcTo(x, y + h, x, y, radius);
    ctx.arcTo(x, y, x + w, y, radius);
    ctx.closePath();
  }

  function fillRoundRect(ctx, x, y, w, h, r, color) {
    ctx.fillStyle = color;
    roundRectPath(ctx, x, y, w, h, r);
    ctx.fill();
  }

  function strokeRoundRect(ctx, x, y, w, h, r, color, lineWidth = 2) {
    ctx.strokeStyle = color;
    ctx.lineWidth = lineWidth;
    roundRectPath(ctx, x, y, w, h, r);
    ctx.stroke();
  }

  function wrapTextLines(ctx, text, maxWidth, font) {
    ctx.font = font;
    const paragraphs = String(text || "").split("\n");
    const lines = [];
    for (const para of paragraphs) {
      if (!para) {
        lines.push("");
        continue;
      }
      let current = "";
      for (const ch of para) {
        const test = current + ch;
        if (ctx.measureText(test).width > maxWidth && current.length > 0) {
          lines.push(current);
          current = ch;
        } else {
          current = test;
        }
      }
      if (current) lines.push(current);
    }
    return lines.length > 0 ? lines : [""];
  }

  function drawWrappedLines(ctx, lines, x, y, lineHeight, font, color, strikeThrough = false) {
    ctx.font = font;
    ctx.fillStyle = color;
    ctx.textBaseline = "top";
    lines.forEach((line, idx) => {
      const ly = y + idx * lineHeight;
      ctx.fillText(line, x, ly);
      if (strikeThrough && line.trim()) {
        const w = ctx.measureText(line).width;
        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(x, ly + lineHeight * 0.45);
        ctx.lineTo(x + w, ly + lineHeight * 0.45);
        ctx.stroke();
      }
    });
    return lines.length * lineHeight;
  }

  function drawPillBadge(ctx, x, y, text, bgColor, textColor) {
    const font = '700 22px "Pretendard", "Apple SD Gothic Neo", sans-serif';
    ctx.font = font;
    const tw = ctx.measureText(text).width;
    const pillW = tw + 34;
    const pillH = 38;
    fillRoundRect(ctx, x, y, pillW, pillH, 19, bgColor);
    ctx.fillStyle = textColor;
    ctx.textBaseline = "middle";
    ctx.fillText(text, x + 17, y + pillH / 2 + 1);
    return pillW;
  }

  function drawCard(ctx, x, y, w, h) {
    fillRoundRect(ctx, x + 2, y + 6, w, h, 26, "#E2E8F0");
    fillRoundRect(ctx, x, y, w, h, 26, "#FFFFFF");
  }

  function drawScoreDonut(ctx, cx, cy, radius, percent, topText, bottomText) {
    ctx.lineWidth = 14;
    ctx.strokeStyle = "#E2E8F0";
    ctx.beginPath();
    ctx.arc(cx, cy, radius, 0, Math.PI * 2);
    ctx.stroke();

    const p = Math.max(0, Math.min(100, percent || 0));
    const color = p >= 80 ? "#059669" : p >= 60 ? "#2563EB" : "#D97706";
    ctx.strokeStyle = color;
    ctx.lineCap = "round";
    ctx.beginPath();
    ctx.arc(cx, cy, radius, -Math.PI / 2, -Math.PI / 2 + (p / 100) * Math.PI * 2);
    ctx.stroke();
    ctx.lineCap = "butt";

    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillStyle = "#0F172A";
    ctx.font = '800 28px "Pretendard", "Apple SD Gothic Neo", sans-serif';
    ctx.fillText(topText, cx, cy - 10);
    ctx.fillStyle = "#475569";
    ctx.font = '700 19px "Pretendard", "Apple SD Gothic Neo", sans-serif';
    ctx.fillText(bottomText, cx, cy + 18);
    ctx.textAlign = "left";
  }

  function drawStackedSubjectBar(ctx, x, y, width, height, breakdowns) {
    fillRoundRect(ctx, x, y, width, height, height / 2, "#E2E8F0");
    const total = breakdowns.reduce((acc, b) => acc + b.minutes, 0);
    if (total <= 0) return;
    let curX = x;
    for (const b of breakdowns) {
      const segW = (b.minutes / total) * width;
      if (segW > 3) {
        fillRoundRect(ctx, curX, y, Math.max(4, segW - 2), height, 8, b.colorHex);
        curX += segW;
      }
    }
  }

  function hexToRgba(hex, alpha) {
    const clean = hex.replace("#", "");
    const r = parseInt(clean.slice(0, 2), 16);
    const g = parseInt(clean.slice(2, 4), 16);
    const b = parseInt(clean.slice(4, 6), 16);
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
  }

  // ==================== Daily Report Card Canvas ====================

  function renderDailyReportCanvas(result, includePracticeLines = true, nanoImageEl = null) {
    const CARD_W = 1080;
    const OUTER_PAD = 42;
    const INNER_PAD = 32;
    const contentW = CARD_W - OUTER_PAD * 2;
    const innerW = contentW - INNER_PAD * 2;
    const profile = PROFILES[result.studentType] || PROFILES.HIGH_SCHOOL;

    const measureCanvas = document.createElement("canvas");
    const mctx = measureCanvas.getContext("2d");

    const fontRegular = '400 25px "Pretendard", "Apple SD Gothic Neo", sans-serif';
    const fontBold = '700 26px "Pretendard", "Apple SD Gothic Neo", sans-serif';
    const fontTitle = '800 30px "Pretendard", "Apple SD Gothic Neo", sans-serif';

    const praiseLines = wrapTextLines(mctx, result.praiseOneLiner, innerW - 190, '400 27px "Pretendard", sans-serif');
    const summaryLines = wrapTextLines(mctx, result.improvementSummary, innerW, fontRegular);

    const corrMetrics = (result.corrections || []).map((c) => {
      const bLines = wrapTextLines(mctx, `✕ ${c.beforeText}`, innerW - 44, fontRegular);
      const aLines = wrapTextLines(mctx, `✓ ${c.afterText} (${c.targetMinutes}분)`, innerW - 44, fontBold);
      const boxH = 68 + bLines.length * 33 + aLines.length * 35 + 16;
      return { item: c, bLines, aLines, boxH };
    });
    const correctionsCardH = 96 + Math.max(80, corrMetrics.reduce((s, m) => s + m.boxH + 14, 0)) + 16;

    const totalHours = profile.endHour - profile.startHour;
    const rowH = profile.id === "HIGH_SCHOOL" ? 44 : 62;
    const timetableCardH = 148 + totalHours * rowH + 175;

    const promiseLines = wrapTextLines(mctx, `[오늘의 약속] ${result.copywritingPromise}`, innerW - 44, '700 27px "Pretendard", sans-serif');
    const practiceLines = wrapTextLines(mctx, `[수정 & 내일 예약 연습] ${result.copywritingPractice}`, innerW - 44, '600 25px "Pretendard", sans-serif');
    const extraPracticeH = includePracticeLines ? 200 : 0;
    const copyCardH = 112 + promiseLines.length * 37 + practiceLines.length * 34 + 62 + extraPracticeH;

    const patternInfo = getBiblePatternForStudent(profile.id, result.dateIso);
    const bibleSummaryText = (result.bibleCompleted
      ? `✅ ${result.biblePassageText || "오늘 성경읽기 완료"} (${result.bibleChaptersCount || 1}장)`
      : `⏳ 미완료 — ${result.biblePassageText || "오늘 성경읽기 미기록"}`) +
      `\n📊 날짜별 패턴: ${result.biblePatternSummary || patternInfo.patternSummary}` +
      `\n👉 다음 날 제안: ${result.bibleNextSuggestion || patternInfo.nextDaySuggestion}`;
    const bibleLines = wrapTextLines(mctx, bibleSummaryText, innerW - 36, '600 23px "Pretendard", sans-serif');
    const bibleCardH = 86 + bibleLines.length * 32;

    let nanoDrawH = 0;
    let nanoCardH = 0;
    if (nanoImageEl && nanoImageEl.width > 0 && nanoImageEl.height > 0) {
      nanoDrawH = Math.min(720, Math.round(innerW * (nanoImageEl.height / nanoImageEl.width)));
      nanoCardH = 104 + nanoDrawH + 26;
    }

    const headerCardH = 266 + Math.max(40, praiseLines.length * 36) + summaryLines.length * 34;
    const totalH = Math.round(
      OUTER_PAD + headerCardH + 24 + bibleCardH + 24 +
      (nanoCardH > 0 ? nanoCardH + 24 : 0) +
      correctionsCardH + 24 + timetableCardH + 24 + copyCardH + 68
    );

    const canvas = document.createElement("canvas");
    canvas.width = CARD_W;
    canvas.height = totalH;
    const ctx = canvas.getContext("2d");

    ctx.fillStyle = "#F1F5F9";
    ctx.fillRect(0, 0, CARD_W, totalH);

    let y = OUTER_PAD;

    // 1. Header & Score Gauge Card
    drawCard(ctx, OUTER_PAD, y, contentW, headerCardH);
    const badgeBg = profile.id === "HIGH_SCHOOL" ? "#DBEAFE" : "#D1FAE5";
    const badgeFg = profile.id === "HIGH_SCHOOL" ? "#2563EB" : "#059669";
    const mainBw = drawPillBadge(ctx, OUTER_PAD + INNER_PAD, y + 28, `${profile.emoji} ${result.studentName} · ${result.dateIso} 플래너 리포트`, badgeBg, badgeFg);
    if (result.keptYesterdayPromise) {
      drawPillBadge(ctx, OUTER_PAD + INNER_PAD + mainBw + 10, y + 28, "🎉 어제 약속 이행 (+10점)", "#FEF3C7", "#B45309");
    }

    drawScoreDonut(ctx, CARD_W - OUTER_PAD - INNER_PAD - 72, y + 122, 62, result.habitScore, `${result.habitScore}점`, result.gradeLabel || "A");

    ctx.textBaseline = "top";
    ctx.fillStyle = "#2563EB";
    ctx.font = '700 23px "Pretendard", sans-serif';
    ctx.fillText("오늘의 잘한 점", OUTER_PAD + INNER_PAD, y + 92);
    const praiseH = drawWrappedLines(ctx, praiseLines, OUTER_PAD + INNER_PAD, y + 126, 36, '400 27px "Pretendard", sans-serif', "#0F172A");

    const divY = y + 146 + praiseH;
    ctx.strokeStyle = "#E2E8F0";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(OUTER_PAD + INNER_PAD, divY);
    ctx.lineTo(CARD_W - OUTER_PAD - INNER_PAD, divY);
    ctx.stroke();

    ctx.fillStyle = "#D97706";
    ctx.font = '700 23px "Pretendard", sans-serif';
    ctx.fillText("핵심 수정 방향", OUTER_PAD + INNER_PAD, divY + 20);
    const sumH = drawWrappedLines(ctx, summaryLines, OUTER_PAD + INNER_PAD, divY + 54, 34, fontRegular, "#475569");

    let tagX = OUTER_PAD + INNER_PAD;
    const tagY = divY + 70 + sumH;
    for (const tag of (result.weakHabitTags || []).slice(0, 4)) {
      const w = drawPillBadge(ctx, tagX, tagY, tag, "#FEE2E2", "#DC2626");
      tagX += w + 12;
    }

    y += headerCardH + 24;

    // 1.5 Bible Reading Habit Card (김희균 bible71 DB / 초등 종이 플래너)
    const bibleCardBg = result.bibleCompleted ? "#F0FDFA" : "#FFFBEB";
    const bibleCardBorder = result.bibleCompleted ? "#5EEAD4" : "#FCD34D";
    const bibleTitleColor = result.bibleCompleted ? "#0F766E" : "#B45309";
    fillRoundRect(ctx, OUTER_PAD, y, contentW, bibleCardH, 24, bibleCardBg);
    strokeRoundRect(ctx, OUTER_PAD, y, contentW, bibleCardH, 24, bibleCardBorder, 2.5);
    const bibleSourceLabel = profile.id === "HIGH_SCHOOL" ? "bible71 연동" : "종이 플래너 기록";
    ctx.textBaseline = "top";
    ctx.fillStyle = bibleTitleColor;
    ctx.font = '800 26px "Pretendard", sans-serif';
    ctx.fillText(`📖 매일 성경읽기 습관 · 패턴 & 다음 날 제안 (${bibleSourceLabel})`, OUTER_PAD + INNER_PAD, y + 22);
    drawWrappedLines(ctx, bibleLines, OUTER_PAD + INNER_PAD, y + 62, 32, '600 23px "Pretendard", sans-serif', "#0F172A");

    y += bibleCardH + 24;

    // 1.7 Nano Banana Basic Generated Annotated Planner Card (if available)
    if (nanoCardH > 0 && nanoImageEl) {
      drawCard(ctx, OUTER_PAD, y, contentW, nanoCardH);
      ctx.fillStyle = "#4F46E5";
      ctx.font = fontTitle;
      ctx.textBaseline = "top";
      ctx.fillText("🍌 모범 플래너 색상 첨삭 예시 (나노 바나나 기본형)", OUTER_PAD + INNER_PAD, y + 28);
      ctx.save();
      roundRectPath(ctx, OUTER_PAD + INNER_PAD, y + 84, innerW, nanoDrawH, 18);
      ctx.clip();
      ctx.drawImage(nanoImageEl, OUTER_PAD + INNER_PAD, y + 84, innerW, nanoDrawH);
      ctx.restore();
      y += nanoCardH + 24;
    }

    // 2. Before -> After Corrections Card
    drawCard(ctx, OUTER_PAD, y, contentW, correctionsCardH);
    ctx.fillStyle = "#0F172A";
    ctx.font = fontTitle;
    ctx.textBaseline = "top";
    ctx.fillText("1. 계획 수정안 (Before → After)", OUTER_PAD + INNER_PAD, y + 32);

    let cy = y + 86;
    if (corrMetrics.length === 0) {
      ctx.fillStyle = "#475569";
      ctx.font = fontRegular;
      ctx.fillText("작성된 항목을 바탕으로 아래 시간표를 참고해 계획을 구체화해 보세요.", OUTER_PAD + INNER_PAD, cy);
    } else {
      for (const m of corrMetrics) {
        const subjColor = colorForSubject(profile.id, m.item.subject);
        const bx = OUTER_PAD + INNER_PAD;
        fillRoundRect(ctx, bx, cy, innerW, m.boxH, 18, "#F8FAFC");
        fillRoundRect(ctx, bx, cy, 10, m.boxH, 5, subjColor);

        const bw = drawPillBadge(ctx, bx + 24, cy + 16, m.item.subject, hexToRgba(subjColor, 0.16), subjColor);
        drawPillBadge(ctx, bx + 36 + bw, cy + 16, m.item.missingTag || "#구체화", "#FEF3C7", "#D97706");

        const bh = drawWrappedLines(ctx, m.bLines, bx + 24, cy + 64, 33, fontRegular, "#64748B", true);
        drawWrappedLines(ctx, m.aLines, bx + 24, cy + 72 + bh, 35, fontBold, "#0F172A", false);
        cy += m.boxH + 14;
      }
    }

    y += correctionsCardH + 24;

    // 3. Color-coded Timetable Card
    drawCard(ctx, OUTER_PAD, y, contentW, timetableCardH);
    ctx.fillStyle = "#0F172A";
    ctx.font = fontTitle;
    ctx.textBaseline = "top";
    const ttTitle = profile.id === "HIGH_SCHOOL"
      ? "2. 색상별 모범 타임테이블 (10분 단위 · 순공/인강/복습 구분)"
      : "2. 무지개 색상 모범 시간표 (30분 블록)";
    ctx.fillText(ttTitle, OUTER_PAD + INNER_PAD, y + 32);

    ctx.fillStyle = "#475569";
    ctx.font = '400 22px "Pretendard", sans-serif';
    const guideText = profile.id === "HIGH_SCHOOL"
      ? "진한 색 = 자습·문제풀이  |  연한 색[인강] = 수업·인강  |  노란 테두리 ★ = 오답·복습"
      : "색깔 블록을 보며 한 과목씩 30분씩 칠하고, 끝나면 ★ 표시를 해보세요!";
    ctx.fillText(guideText, OUTER_PAD + INNER_PAD, y + 76);

    const gridTop = y + 122;
    const gridLeft = OUTER_PAD + INNER_PAD + 78;
    const gridRight = CARD_W - OUTER_PAD - INNER_PAD;
    const gridW = gridRight - gridLeft;
    const slotsPerHour = 60 / profile.slotMinutes;

    for (let hi = 0; hi < totalHours; hi++) {
      const hour = profile.startHour + hi;
      const rowTop = gridTop + hi * rowH;
      const rowBottom = rowTop + rowH;

      ctx.fillStyle = "#475569";
      ctx.font = '700 21px "Pretendard", sans-serif';
      ctx.textBaseline = "middle";
      ctx.fillText(`${String(hour).padStart(2, "0")}:00`, OUTER_PAD + INNER_PAD, rowTop + rowH / 2);

      ctx.fillStyle = "#FAFCFF";
      ctx.fillRect(gridLeft, rowTop, gridW, rowH);

      ctx.strokeStyle = "#F1F5F9";
      ctx.lineWidth = 1.5;
      const slotW = gridW / slotsPerHour;
      for (let s = 1; s < slotsPerHour; s++) {
        const sx = gridLeft + s * slotW;
        ctx.beginPath();
        ctx.moveTo(sx, rowTop);
        ctx.lineTo(sx, rowBottom);
        ctx.stroke();
      }

      ctx.strokeStyle = "#E2E8F0";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(gridLeft, rowTop);
      ctx.lineTo(gridRight, rowTop);
      ctx.stroke();
    }

    const gridBottom = gridTop + totalHours * rowH;
    ctx.strokeStyle = "#E2E8F0";
    ctx.lineWidth = 2;
    ctx.strokeRect(gridLeft, gridTop, gridW, totalHours * rowH);

    // Draw TimeBlocks
    for (const block of result.timeBlocks || []) {
      const startTotal = (block.startHour || profile.startHour) * 60 + (block.startMinute || 0);
      const endTotal = startTotal + (block.durationMinutes || profile.slotMinutes);
      let cur = startTotal;
      while (cur < endTotal) {
        const curH = Math.floor(cur / 60);
        const minInH = cur % 60;
        const segEnd = Math.min(endTotal, (curH + 1) * 60);
        const rowIdx = curH - profile.startHour;
        if (rowIdx >= 0 && rowIdx < totalHours) {
          const rTop = gridTop + rowIdx * rowH + 4;
          const rH = rowH - 8;
          const left = gridLeft + (minInH / 60) * gridW + 2;
          const right = gridLeft + ((segEnd - curH * 60) / 60) * gridW - 2;
          const bw = Math.max(6, right - left);
          const baseColor = colorForSubject(profile.id, block.subject);
          const fillCol = block.studyMode === "LECTURE" ? hexToRgba(baseColor, 0.48) : baseColor;

          fillRoundRect(ctx, left, rTop, bw, rH, 8, fillCol);
          if (block.studyMode === "REVIEW") {
            strokeRoundRect(ctx, left, rTop, bw, rH, 8, "#FDE047", 3.5);
          }

          if (bw > 74) {
            const prefix = block.studyMode === "LECTURE" ? "[인강] " : block.studyMode === "REVIEW" ? "★ " : "";
            ctx.fillStyle = block.studyMode === "LECTURE" ? "#0F172A" : "#FFFFFF";
            ctx.font = '700 19px "Pretendard", sans-serif';
            ctx.textBaseline = "middle";
            ctx.fillText(`${prefix}${block.subject}`, left + 8, rTop + rH / 2);
          }
        }
        cur = segEnd;
      }
    }

    const barTop = gridBottom + 26;
    ctx.textBaseline = "top";
    ctx.fillStyle = "#0F172A";
    ctx.font = '700 23px "Pretendard", sans-serif';
    ctx.fillText(
      `총 ${formatMinutes(result.totalStudyMinutes)} (자습 ${formatMinutes(result.selfStudyMinutes)} · 인강 ${formatMinutes(result.lectureMinutes)} · 복습 ${formatMinutes(result.reviewMinutes)})`,
      OUTER_PAD + INNER_PAD,
      barTop
    );

    const breakdowns = computeSubjectBreakdowns(result);
    drawStackedSubjectBar(ctx, OUTER_PAD + INNER_PAD, barTop + 38, innerW, 26, breakdowns);

    let lx = OUTER_PAD + INNER_PAD;
    const ly = barTop + 82;
    for (const item of profile.legend) {
      ctx.fillStyle = item.color;
      ctx.beginPath();
      ctx.arc(lx + 10, ly + 12, 9, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#475569";
      ctx.font = '600 21px "Pretendard", sans-serif';
      ctx.fillText(item.name, lx + 26, ly + 1);
      lx += ctx.measureText(item.name).width + 52;
    }

    y += timetableCardH + 24;

    // 4. Handwriting Copywriting Card
    fillRoundRect(ctx, OUTER_PAD, y, contentW, copyCardH, 26, "#FFFBEB");
    strokeRoundRect(ctx, OUTER_PAD, y, contentW, copyCardH, 26, "#FDE68A", 3);

    ctx.fillStyle = "#D97706";
    ctx.font = '800 28px "Pretendard", sans-serif';
    ctx.fillText("3. ✍️ 플래너 하단에 그대로 베껴쓰기 (1분 습관 각인)", OUTER_PAD + INNER_PAD, y + 30);

    const noteInnerH = copyCardH - 110 - extraPracticeH;
    fillRoundRect(ctx, OUTER_PAD + INNER_PAD, y + 84, innerW, noteInnerH, 18, "#FFFFFF");

    const ph = drawWrappedLines(ctx, promiseLines, OUTER_PAD + INNER_PAD + 22, y + 106, 37, '700 27px "Pretendard", sans-serif', "#0F172A");
    drawWrappedLines(ctx, practiceLines, OUTER_PAD + INNER_PAD + 22, y + 122 + ph, 34, '600 25px "Pretendard", sans-serif', "#2563EB");

    if (includePracticeLines) {
      const lineStart = y + 84 + noteInnerH + 28;
      ctx.fillStyle = "#D97706";
      ctx.font = '700 21px "Pretendard", sans-serif';
      ctx.fillText("✏️ 아래 점선 위에 직접 손글씨로 따라 적어 보세요:", OUTER_PAD + INNER_PAD, lineStart);
      ctx.strokeStyle = "#F59E0B";
      ctx.lineWidth = 2;
      ctx.setLineDash([12, 10]);
      for (let i = 1; i <= 3; i++) {
        const lineY = lineStart + i * 50;
        ctx.beginPath();
        ctx.moveTo(OUTER_PAD + INNER_PAD, lineY);
        ctx.lineTo(CARD_W - OUTER_PAD - INNER_PAD, lineY);
        ctx.stroke();
      }
      ctx.setLineDash([]);
    }

    ctx.fillStyle = "#94A3B8";
    ctx.font = '500 20px "Pretendard", sans-serif';
    ctx.fillText("플래너 코치 AI · Daily Study Habit System", OUTER_PAD, totalH - 42);

    return canvas;
  }

  // ==================== Weekly Report Card Canvas ====================

  function renderWeeklyReportCanvas(report, includePracticeLines = true) {
    const CARD_W = 1080;
    const OUTER_PAD = 42;
    const INNER_PAD = 32;
    const contentW = CARD_W - OUTER_PAD * 2;
    const innerW = contentW - INNER_PAD * 2;
    const profile = PROFILES[report.studentType] || PROFILES.HIGH_SCHOOL;

    const measureCanvas = document.createElement("canvas");
    const mctx = measureCanvas.getContext("2d");

    const fontInsight = '500 25px "Pretendard", sans-serif';
    const bestLines = wrapTextLines(mctx, `💡 이번 주 베스트: ${report.bestPatternInsight}`, innerW - 36, fontInsight);
    const warnLines = wrapTextLines(mctx, `⚠️ 습관 코칭: ${report.warningHabitInsight}`, innerW - 36, fontInsight);
    const overLines = wrapTextLines(mctx, `⚖️ 과욕지수 진단: ${report.overPlanningDiagnosis}`, innerW - 36, fontInsight);
    const predLines = wrapTextLines(mctx, `🔮 데이터 예측: ${report.predictionInsight}`, innerW - 36, '700 25px "Pretendard", sans-serif');

    const insightBoxes = [
      { lines: bestLines, bg: "#ECFDF5", fg: "#0F172A", font: fontInsight },
      { lines: warnLines, bg: "#FEF2F2", fg: "#0F172A", font: fontInsight },
      { lines: overLines, bg: "#FFFBEB", fg: "#0F172A", font: fontInsight },
      { lines: predLines, bg: "#EEF2FF", fg: "#4338CA", font: '700 25px "Pretendard", sans-serif' }
    ];
    const insightCardH = 112 + insightBoxes.reduce((s, b) => s + b.lines.length * 34 + 36, 0);

    const goalMetrics = (report.nextWeekGoals || []).map((g) => {
      const lines = wrapTextLines(mctx, `${g.title} (${g.targetMetric})\n${g.actionTip}`, innerW - 56, '500 24px "Pretendard", sans-serif');
      return { lines, h: lines.length * 33 + 24 };
    });
    const copyLines = wrapTextLines(mctx, `[다음 주 다짐] ${report.weeklyCopywritingPromise}`, innerW - 44, '700 26px "Pretendard", sans-serif');
    const extraPracticeH = includePracticeLines ? 145 : 0;
    const goalsCardH = 112 + goalMetrics.reduce((s, g) => s + g.h + 14, 0) + copyLines.length * 36 + 64 + extraPracticeH;

    const heroCardH = 328;
    const chartCardH = 512;
    const totalH = Math.round(OUTER_PAD + heroCardH + 24 + chartCardH + 24 + insightCardH + 24 + goalsCardH + 68);

    const canvas = document.createElement("canvas");
    canvas.width = CARD_W;
    canvas.height = totalH;
    const ctx = canvas.getContext("2d");

    ctx.fillStyle = "#F1F5F9";
    ctx.fillRect(0, 0, CARD_W, totalH);

    let y = OUTER_PAD;

    // 1. Hero Metrics Card
    drawCard(ctx, OUTER_PAD, y, contentW, heroCardH);
    const bw = drawPillBadge(ctx, OUTER_PAD + INNER_PAD, y + 28, `${profile.emoji} ${report.studentName} · ${report.weekLabel} 주간 분석`, "#EEF2FF", "#4F46E5");
    const bw2 = drawPillBadge(ctx, OUTER_PAD + INNER_PAD + bw + 12, y + 28, report.stage.badgeText, "#ECFDF5", "#059669");
    drawPillBadge(ctx, OUTER_PAD + INNER_PAD + bw + bw2 + 24, y + 28, `📖 성경 ${report.bibleDaysCount || 0}/7일 (${report.bibleChaptersTotal || 0}장)`, "#CCFBF1", "#0F766E");

    const colW = innerW / 3;
    const colTop = y + 98;
    ctx.textBaseline = "top";

    // Col 1
    ctx.fillStyle = "#475569";
    ctx.font = '700 23px "Pretendard", sans-serif';
    ctx.fillText("총 공부 시간", OUTER_PAD + INNER_PAD, colTop);
    ctx.fillStyle = "#0F172A";
    ctx.font = '800 42px "Pretendard", sans-serif';
    ctx.fillText(formatMinutes(report.totalStudyMinutes), OUTER_PAD + INNER_PAD, colTop + 40);
    const deltaTxt = report.prevWeekDeltaMinutes != null
      ? (report.prevWeekDeltaMinutes >= 0 ? `▲ 지난주보다 +${formatMinutes(report.prevWeekDeltaMinutes)}` : `▼ 지난주보다 -${formatMinutes(-report.prevWeekDeltaMinutes)}`)
      : `누적 ${report.totalAccumulatedDays}일차 기록`;
    ctx.fillStyle = (report.prevWeekDeltaMinutes ?? 0) >= 0 ? "#059669" : "#DC2626";
    ctx.font = '700 21px "Pretendard", sans-serif';
    ctx.fillText(deltaTxt, OUTER_PAD + INNER_PAD, colTop + 104);

    // Col 2
    drawScoreDonut(ctx, OUTER_PAD + INNER_PAD + colW * 1.5, colTop + 72, 60, report.completionRatePercent, `${report.completionRatePercent}%`, "계획 달성률");

    // Col 3
    const col3X = OUTER_PAD + INNER_PAD + colW * 2 + 16;
    ctx.textBaseline = "top";
    ctx.fillStyle = "#475569";
    ctx.font = '700 23px "Pretendard", sans-serif';
    ctx.fillText("순공 비율 & 성경읽기", col3X, colTop);
    ctx.fillStyle = "#2563EB";
    ctx.font = '800 40px "Pretendard", sans-serif';
    ctx.fillText(`${report.selfStudyRatioPercent}% · 📖${report.bibleDaysCount || 0}일`, col3X, colTop + 40);
    ctx.fillStyle = "#475569";
    ctx.font = '700 21px "Pretendard", sans-serif';
    ctx.fillText(`평균 플래너 점수 ${report.avgHabitScore}점`, col3X, colTop + 104);

    // Bible streak bar inside hero footer
    ctx.fillStyle = "#0F766E";
    ctx.font = '700 21px "Pretendard", sans-serif';
    ctx.fillText(
      `📖 주간 말씀 습관: ${report.bibleSummaryLine || `이번 주 ${report.bibleDaysCount || 0}일 / 총 ${report.bibleChaptersTotal || 0}장 읽기 완료`}`,
      OUTER_PAD + INNER_PAD,
      colTop + 160
    );

    y += heroCardH + 24;

    // 2. Daily Rhythm Bar Chart + Subject Balance
    drawCard(ctx, OUTER_PAD, y, contentW, chartCardH);
    ctx.fillStyle = "#0F172A";
    ctx.font = '800 30px "Pretendard", sans-serif';
    ctx.fillText("요일별 공부 리듬 (📖=성경읽기) & 과목 색상 밸런스", OUTER_PAD + INNER_PAD, y + 32);

    const chartLeft = OUTER_PAD + INNER_PAD + 20;
    const chartRight = CARD_W - OUTER_PAD - INNER_PAD - 20;
    const chartBottom = y + 308;
    const chartMaxH = 166;
    const maxMins = Math.max(60, ...report.dailyBars.map((d) => d.minutes));
    const slotW = (chartRight - chartLeft) / 7;

    report.dailyBars.forEach((pt, idx) => {
      const barW = slotW * 0.52;
      const cx = chartLeft + idx * slotW + slotW / 2;
      const barH = (pt.minutes / maxMins) * chartMaxH;
      const barTop = Math.min(chartBottom - 8, chartBottom - barH);
      fillRoundRect(ctx, cx - barW / 2, barTop, barW, Math.max(8, chartBottom - barTop), 12, pt.minutes > 0 ? "#2563EB" : "#E2E8F0");

      ctx.textAlign = "center";
      ctx.fillStyle = "#475569";
      ctx.font = '700 20px "Pretendard", sans-serif';
      const labelY = Math.max(y + 92, barTop - 28);
      ctx.fillText(pt.minutes > 0 ? formatShortMinutes(pt.minutes) : "-", cx, labelY);
      if (pt.bibleDone) {
        ctx.fillStyle = "#0D9488";
        ctx.font = '700 18px "Pretendard", sans-serif';
        ctx.fillText("📖", cx, labelY - 24);
      }

      ctx.fillStyle = "#0F172A";
      ctx.font = '700 23px "Pretendard", sans-serif';
      ctx.fillText(pt.dayLabel, cx, chartBottom + 14);
      ctx.textAlign = "left";
    });

    const subjBarY = chartBottom + 74;
    ctx.fillStyle = "#0F172A";
    ctx.font = '700 23px "Pretendard", sans-serif';
    ctx.fillText("과목별 시간 배분", OUTER_PAD + INNER_PAD, subjBarY);
    drawStackedSubjectBar(ctx, OUTER_PAD + INNER_PAD, subjBarY + 36, innerW, 28, report.subjectBreakdowns);

    let sx = OUTER_PAD + INNER_PAD;
    const sy = subjBarY + 80;
    for (const sb of report.subjectBreakdowns.slice(0, 5)) {
      ctx.fillStyle = sb.colorHex;
      ctx.beginPath();
      ctx.arc(sx + 10, sy + 12, 9, 0, Math.PI * 2);
      ctx.fill();
      const txt = `${sb.subject} ${formatShortMinutes(sb.minutes)}`;
      ctx.fillStyle = "#475569";
      ctx.font = '600 21px "Pretendard", sans-serif';
      ctx.fillText(txt, sx + 26, sy + 1);
      sx += ctx.measureText(txt).width + 46;
    }

    y += chartCardH + 24;

    // 3. AI Insights & Predictive Analytics
    drawCard(ctx, OUTER_PAD, y, contentW, insightCardH);
    ctx.fillStyle = "#0F172A";
    ctx.font = '800 30px "Pretendard", sans-serif';
    ctx.fillText(`AI 학습 분석 & 데이터 예측 (${report.stage.title})`, OUTER_PAD + INNER_PAD, y + 32);

    let iy = y + 88;
    for (const box of insightBoxes) {
      const bh = box.lines.length * 34 + 26;
      fillRoundRect(ctx, OUTER_PAD + INNER_PAD, iy, innerW, bh, 16, box.bg);
      drawWrappedLines(ctx, box.lines, OUTER_PAD + INNER_PAD + 18, iy + 13, 34, box.font, box.fg);
      iy += bh + 12;
    }

    y += insightCardH + 24;

    // 4. Next Week Goals & Weekly Copywriting
    drawCard(ctx, OUTER_PAD, y, contentW, goalsCardH);
    ctx.fillStyle = "#059669";
    ctx.font = '800 30px "Pretendard", sans-serif';
    ctx.fillText("다음 1주일 맞춤 학습 목표 & 다짐 필사", OUTER_PAD + INNER_PAD, y + 32);

    let gy = y + 88;
    goalMetrics.forEach((g, idx) => {
      fillRoundRect(ctx, OUTER_PAD + INNER_PAD, gy, innerW, g.h, 16, "#F8FAFC");
      ctx.fillStyle = "#059669";
      ctx.font = '800 24px "Pretendard", sans-serif';
      ctx.fillText(`${idx + 1}`, OUTER_PAD + INNER_PAD + 18, gy + 12);
      drawWrappedLines(ctx, g.lines, OUTER_PAD + INNER_PAD + 48, gy + 12, 33, '500 24px "Pretendard", sans-serif', "#0F172A");
      gy += g.h + 14;
    });

    const noteH = copyLines.length * 36 + 34;
    fillRoundRect(ctx, OUTER_PAD + INNER_PAD, gy + 4, innerW, noteH, 16, "#FFFBEB");
    strokeRoundRect(ctx, OUTER_PAD + INNER_PAD, gy + 4, innerW, noteH, 16, "#FDE68A", 2.5);
    drawWrappedLines(ctx, copyLines, OUTER_PAD + INNER_PAD + 20, gy + 20, 36, '700 26px "Pretendard", sans-serif', "#0F172A");

    if (includePracticeLines) {
      const lineStart = gy + 4 + noteH + 22;
      ctx.fillStyle = "#D97706";
      ctx.font = '700 21px "Pretendard", sans-serif';
      ctx.fillText("✏️ 다음 주 플래너 첫 페이지에 손으로 따라 적어 보세요:", OUTER_PAD + INNER_PAD, lineStart);
      ctx.strokeStyle = "#F59E0B";
      ctx.lineWidth = 2;
      ctx.setLineDash([12, 10]);
      for (let i = 1; i <= 2; i++) {
        const ly = lineStart + i * 48;
        ctx.beginPath();
        ctx.moveTo(OUTER_PAD + INNER_PAD, ly);
        ctx.lineTo(CARD_W - OUTER_PAD - INNER_PAD, ly);
        ctx.stroke();
      }
      ctx.setLineDash([]);
    }

    ctx.fillStyle = "#94A3B8";
    ctx.font = '500 20px "Pretendard", sans-serif';
    ctx.fillText("플래너 코치 AI · Weekly Predictive Report", OUTER_PAD, totalH - 42);

    return canvas;
  }

  // ==================== Offline-Safe Pure JS A4 PDF Builder ====================

  function dataUrlToBytes(dataUrl) {
    const base64 = dataUrl.split(",")[1];
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
      bytes[i] = bin.charCodeAt(i);
    }
    return bytes;
  }

  /**
   * 외부 라이브러리 없이 순수 JS로 표준 A4 다중 페이지 PDF(JPEG DCTDecode 스트림)를 생성한다.
   */
  function buildA4PdfBlobFromCanvas(canvas) {
    const A4_W = 595.28;
    const A4_H = 841.89;
    const MARGIN = 24;
    const USABLE_W = A4_W - MARGIN * 2;
    const USABLE_H = A4_H - MARGIN * 2;

    const scale = USABLE_W / canvas.width;
    const sliceHeightPx = Math.max(200, Math.floor(USABLE_H / scale));

    const slices = [];
    let offsetY = 0;
    while (offsetY < canvas.height) {
      const h = Math.min(sliceHeightPx, canvas.height - offsetY);
      const sliceCanvas = document.createElement("canvas");
      sliceCanvas.width = canvas.width;
      sliceCanvas.height = h;
      const sctx = sliceCanvas.getContext("2d");
      sctx.fillStyle = "#FFFFFF";
      sctx.fillRect(0, 0, sliceCanvas.width, sliceCanvas.height);
      sctx.drawImage(canvas, 0, offsetY, canvas.width, h, 0, 0, canvas.width, h);
      const jpegBytes = dataUrlToBytes(sliceCanvas.toDataURL("image/jpeg", 0.92));
      slices.push({
        widthPx: canvas.width,
        heightPx: h,
        drawW: USABLE_W,
        drawH: h * scale,
        jpegBytes
      });
      offsetY += h;
    }

    const encoder = new TextEncoder();
    const chunks = [];
    let byteCursor = 0;
    const offsets = [0]; // index 0 unused

    function pushBytes(u8) {
      chunks.push(u8);
      byteCursor += u8.length;
    }

    function pushStr(str) {
      pushBytes(encoder.encode(str));
    }

    function beginObj(objNum) {
      offsets[objNum] = byteCursor;
      pushStr(`${objNum} 0 obj\n`);
    }

    function endObj() {
      pushStr("\nendobj\n");
    }

    pushBytes(new Uint8Array([
      0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34, 0x0a,
      0x25, 0xe2, 0xe3, 0xcf, 0xd3, 0x0a
    ]));

    const pageCount = slices.length;
    // Object ID layout:
    // 1: Catalog, 2: Pages
    // For each page i (0..pageCount-1):
    //   pageObj = 3 + i * 3
    //   contentObj = 4 + i * 3
    //   imageObj = 5 + i * 3
    const pageIds = [];
    for (let i = 0; i < pageCount; i++) {
      pageIds.push(3 + i * 3);
    }

    beginObj(1);
    pushStr("<< /Type /Catalog /Pages 2 0 R >>");
    endObj();

    beginObj(2);
    pushStr(`<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(" ")}] /Count ${pageCount} >>`);
    endObj();

    slices.forEach((slice, i) => {
      const pageObj = 3 + i * 3;
      const contentObj = 4 + i * 3;
      const imageObj = 5 + i * 3;
      const imgY = A4_H - MARGIN - slice.drawH;

      beginObj(pageObj);
      pushStr(
        `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${A4_W.toFixed(2)} ${A4_H.toFixed(2)}] ` +
          `/Resources << /XObject << /Im0 ${imageObj} 0 R >> >> /Contents ${contentObj} 0 R >>`
      );
      endObj();

      const contentStream = `q\n${slice.drawW.toFixed(2)} 0 0 ${slice.drawH.toFixed(2)} ${MARGIN.toFixed(2)} ${imgY.toFixed(2)} cm\n/Im0 Do\nQ\n`;
      const contentBytes = encoder.encode(contentStream);
      beginObj(contentObj);
      pushStr(`<< /Length ${contentBytes.length} >>\nstream\n`);
      pushBytes(contentBytes);
      pushStr("endstream");
      endObj();

      beginObj(imageObj);
      pushStr(
        `<< /Type /XObject /Subtype /Image /Width ${slice.widthPx} /Height ${slice.heightPx} ` +
          `/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${slice.jpegBytes.length} >>\nstream\n`
      );
      pushBytes(slice.jpegBytes);
      pushStr("\nendstream");
      endObj();
    });

    const totalObjs = 2 + pageCount * 3;
    const xrefPos = byteCursor;
    pushStr(`xref\n0 ${totalObjs + 1}\n`);
    pushStr("0000000000 65535 f \n");
    for (let i = 1; i <= totalObjs; i++) {
      pushStr(`${String(offsets[i]).padStart(10, "0")} 00000 n \n`);
    }
    pushStr(`trailer\n<< /Size ${totalObjs + 1} /Root 1 0 R >>\nstartxref\n${xrefPos}\n%%EOF`);

    return new Blob(chunks, { type: "application/pdf" });
  }

  function downloadBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 4000);
  }

  async function canvasToPngBlob(canvas) {
    return new Promise((resolve) => canvas.toBlob((b) => resolve(b), "image/png"));
  }

  function blobToBase64(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result).split(",")[1]);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }

  // ==================== Gemini Interactions API ====================

  async function resizeDataUrlToJpegBase64(dataUrl) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => {
        const maxDim = 1440;
        let w = img.width;
        let h = img.height;
        if (w > maxDim || h > maxDim) {
          const ratio = Math.min(maxDim / w, maxDim / h);
          w = Math.round(w * ratio);
          h = Math.round(h * ratio);
        }
        const c = document.createElement("canvas");
        c.width = w;
        c.height = h;
        const ctx = c.getContext("2d");
        ctx.drawImage(img, 0, 0, w, h);
        const jpegUrl = c.toDataURL("image/jpeg", 0.86);
        resolve(jpegUrl.split(",")[1]);
      };
      img.onerror = () => reject(new Error("이미지를 불러오지 못했습니다."));
      img.src = dataUrl;
    });
  }

  function extractInteractionText(resp) {
    if (resp && typeof resp.output_text === "string" && resp.output_text.trim()) {
      return resp.output_text;
    }
    if (Array.isArray(resp?.steps)) {
      let text = "";
      for (const step of resp.steps) {
        if (step.type === "model_output" && Array.isArray(step.content)) {
          for (const part of step.content) {
            if (part.type === "text" && part.text) text += part.text;
          }
        }
      }
      if (text.trim()) return text;
    }
    if (Array.isArray(resp?.outputs)) {
      const text = resp.outputs.map((o) => o.text || "").join("");
      if (text.trim()) return text;
    }
    throw new Error("Gemini 응답에서 JSON 결과를 추출하지 못했습니다.");
  }

  function parseJsonSafe(raw) {
    let s = String(raw || "").trim();
    if (s.startsWith("```")) {
      s = s.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "").trim();
    } else {
      const start = s.indexOf("{");
      const end = s.lastIndexOf("}");
      if (start >= 0 && end > start) s = s.slice(start, end + 1);
    }
    return JSON.parse(s);
  }

  function describeGeminiError(status, rawText) {
    let message = String(rawText || "").slice(0, 400);
    let reason = "";
    try {
      const j = JSON.parse(rawText);
      const e = j.error || j;
      message = e.message || message;
      reason = (e.details || []).map((d) => d.reason || "").filter(Boolean).join(",") || e.status || "";
    } catch (_) {}
    const all = `${reason} ${message}`;
    let hint = "";
    if (/API_KEY_INVALID|API key not valid|API key expired/i.test(all)) {
      hint = "API 키가 올바르지 않거나 만료되었습니다. 설정 탭에서 키를 다시 붙여넣어 주세요(앞뒤 공백·줄바꿈 주의).";
    } else if (/location is not supported/i.test(all)) {
      hint = "현재 위치/네트워크에서 Gemini API를 쓸 수 없습니다. VPN이 켜져 있다면 꺼 주세요.";
    } else if (status === 403) {
      hint = "API 키 사용 제한(웹사이트·앱 제한) 또는 권한 문제입니다. Google AI Studio에서 키 제한을 확인하세요.";
    } else if (status === 404) {
      hint = "선택한 모델을 이 키로 사용할 수 없습니다. 설정에서 다른 모델을 선택해 보세요.";
    } else if (status === 429) {
      hint = "사용량 한도를 넘었습니다. 잠시 후 다시 시도하거나 결제(유료 등급)를 확인하세요.";
    } else if (status >= 500) {
      hint = "Google 서버 일시 장애입니다. 잠시 후 다시 시도해 주세요.";
    }
    return { status, reason, message, hint, isKeyError: /API_KEY|API key/i.test(all) };
  }

  async function postInteraction(endpoint, payload, apiKey) {
    const headers = { "Content-Type": "application/json", "x-goog-api-key": apiKey };
    if (endpoint === GEMINI_BETA_ENDPOINT) headers["Api-Revision"] = "2026-05-20";
    const res = await fetch(endpoint, { method: "POST", headers, body: JSON.stringify(payload) });
    const text = await res.text();
    return { ok: res.ok, status: res.status, text };
  }

  async function callGeminiInteraction(payload) {
    const apiKey = String(state.settings.geminiApiKey || "").replace(/\s+/g, "");
    if (!apiKey) {
      throw new Error("설정 탭에서 Gemini API 키를 먼저 입력해 주세요.");
    }

    // 1차: v1 → 2차: v1beta(같은 요청) → 3차: v1beta(스키마 없이 JSON 지시만)
    const plainPayload = { ...payload };
    delete plainPayload.response_format;
    plainPayload.system_instruction = `${payload.system_instruction || ""}\n반드시 설명 없이 JSON 객체 하나만 출력하세요.`.trim();
    const attempts = [
      [GEMINI_ENDPOINT, payload],
      [GEMINI_BETA_ENDPOINT, payload],
      [GEMINI_BETA_ENDPOINT, plainPayload]
    ];

    let lastErr = null;
    for (const [endpoint, body] of attempts) {
      const r = await postInteraction(endpoint, body, apiKey);
      if (r.ok) {
        return parseJsonSafe(extractInteractionText(JSON.parse(r.text)));
      }
      lastErr = describeGeminiError(r.status, r.text);
      console.error("[Gemini]", endpoint, r.status, r.text);
      // 키·권한·한도 문제는 다른 형식으로 재시도해도 소용없으므로 즉시 중단
      if (r.status !== 400 || lastErr.isKeyError || lastErr.hint) break;
    }
    const err = new Error(
      `Gemini API 오류 (HTTP ${lastErr.status}${lastErr.reason ? ` · ${lastErr.reason}` : ""}): ` +
      `${lastErr.hint ? `${lastErr.hint}\n` : ""}[원문] ${lastErr.message}`
    );
    err.gemini = lastErr;
    throw err;
  }

  function showAiError(boxId, err) {
    const box = $(boxId);
    if (!box) return;
    box.textContent = `⚠️ ${err.message}`;
    box.style.display = "block";
  }

  function clearAiError(boxId) {
    const box = $(boxId);
    if (box) { box.textContent = ""; box.style.display = "none"; }
  }

  async function callNanoBananaBasicImage(base64Jpeg, parsed, profile) {
    const apiKey = state.settings.geminiApiKey.trim();
    if (!apiKey) return null;

    const corrText = (parsed.corrections || [])
      .slice(0, 4)
      .map((c) => `- ${c.subject}: ${c.afterText} (${c.targetMinutes}분)`)
      .join("\n");

    const colorRule = profile.id === "HIGH_SCHOOL"
      ? "Use distinct highlighter color blocks on the timetable: Teal(#0D9488) for Bible reading, Blue(#3B82F6) for Korean, Red(#EF4444) for Math, Amber(#F59E0B) for English, Green(#10B981) for Science/Social, and soft lighter shade with [인강] for lectures vs solid rich color for self-study."
      : "Use bright friendly pastel highlighter blocks on the 30-minute timetable: Teal for Bible reading, Blue for Reading/Korean, Red for Math, Yellow for English, Green for School Homework, and add cheerful star stamps.";

    const editPrompt = [
      "Annotate and transform this student's paper study planner photo into an ideal neat model planner illustration while keeping its planner layout.",
      colorRule,
      "Keep all text clean and readable; show red-pen coaching arrows improving vague plans into specific textbook/page/time targets based on:",
      corrText || "Add specific book names, page counts, and study time color blocks."
    ].join("\n");

    const res = await fetch(GEMINI_BETA_ENDPOINT, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": apiKey,
        "Api-Revision": "2026-05-20"
      },
      body: JSON.stringify({
        model: NANO_BANANA_MODEL,
        input: [
          { type: "text", text: editPrompt },
          { type: "image", mime_type: "image/jpeg", data: base64Jpeg }
        ]
      })
    });
    if (!res.ok) return null;
    const json = await res.json();

    if (json?.output_image?.data) {
      const mime = json.output_image.mime_type || "image/png";
      return `data:${mime};base64,${json.output_image.data}`;
    }
    if (Array.isArray(json?.steps)) {
      for (const step of json.steps) {
        if (step.type === "model_output" && Array.isArray(step.content)) {
          for (const part of step.content) {
            if (part.type === "image" && part.data) {
              const mime = part.mime_type || "image/png";
              return `data:${mime};base64,${part.data}`;
            }
          }
        }
      }
    }
    if (Array.isArray(json?.outputs)) {
      for (const out of json.outputs) {
        if (out.type === "image" && out.data) {
          const mime = out.mime_type || "image/png";
          return `data:${mime};base64,${out.data}`;
        }
      }
    }
    return null;
  }

  async function analyzeDailyPlannerPhoto() {
    if (!state.uploadedImageDataUrl) {
      showToast("먼저 플래너 사진을 촬영하거나 선택해 주세요.");
      return;
    }
    if (!state.settings.geminiApiKey.trim()) {
      switchTab("settings");
      showToast("설정 탭에서 Gemini API 키를 입력해 주세요.");
      return;
    }

    const btn = $("btnRunDailyAi");
    btn.disabled = true;
    btn.textContent = "⏳ AI 분석 및 나노 바나나 기본형 그림 생성 중...";
    clearAiError("dailyAiErrorBox");

    try {
      const profile = PROFILES[state.activeStudent];
      const name = studentName();
      const dateIso = $("inputInspectDate").value || toIsoDate(new Date());
      const memo = $("inputTeacherMemo").value.trim();
      const focusHabitKey = $("selectFocusHabit").value || state.settings.focusHabit || "BIBLE_AND_VOLUME";
      const focusHabitLabel = FOCUS_HABIT_LABELS[focusHabitKey] || focusHabitKey;
      const keptYesterdayPromise = Boolean($("checkboxKeptYesterdayPromise").checked);
      const base64Jpeg = await resizeDataUrlToJpegBase64(state.uploadedImageDataUrl);

      if (profile.id === "HIGH_SCHOOL") {
        await fetchBible71LogsFromDb(false);
      }
      const bibleInfo = getBibleStatusForDate(profile.id, dateIso);
      const biblePattern = getBiblePatternForStudent(profile.id, dateIso);

      const recentRecords = getStudentRecords();
      const yesterdayPromise = recentRecords[0]?.copywritingPromise || "";
      const historySummary = recentRecords
        .slice(0, 5)
        .map((r) => `- ${r.dateIso}: 점수 ${r.habitScore}점, 공부 ${r.totalStudyMinutes}분, 성경읽기 ${r.bibleCompleted ? "완료" : "미완료"}, 부족태그 ${(r.weakHabitTags || []).join(",")}`)
        .join("\n");

      const systemInstruction = profile.id === "HIGH_SCHOOL"
        ? `당신은 고등학생(${name}) 전담 자기주도학습 및 경건 습관 플래너 코치입니다. 관리자가 종이 플래너를 검사하고 교정하는 단계입니다. 5대 원칙(교재/단원, 페이지/문항 분량, 목표시간 매칭, 순공/인강 균형, 오답복습)과 날짜별 성경읽기 패턴 분석을 기준으로 교정하세요. timeBlocks는 08~24시 10분 단위로 studyMode(SELF_STUDY, LECTURE, REVIEW)를 구별하며, 성경읽기 시간(subject: "성경", 20분)도 타임테이블에 배치하세요. copywritingPromise(40~65자)와 copywritingPractice(내일 첫 공부 & 다음 날 성경읽기 제안 예약 문장 포함, 35~65자)는 종이 플래너 여백에 손으로 베껴쓰기 좋게 작성하세요.`
        : `당신은 초등학생(${name}) 자기주도학습 및 성경읽기 습관 코치입니다. 초등학생은 bible71 앱 대신 종이 플래너에 직접 읽은 성경 분량을 기록합니다. 사진을 꼼꼼히 판독해 성경읽기(예: 잠언 1장, 마태복음 3장)가 적혀 있는지 확인하고, 날짜별 패턴을 바탕으로 내일 이어 읽을 성경 장수를 제안하세요. 일반 과목도 '[무엇을 + 몇 장/몇 쪽 + 몇 분]'으로 고쳐주고, timeBlocks는 14~21시 30분 블록으로 배치하세요. copywritingPractice는 내일 첫 공부·성경 약속 문장으로 만드세요.`;

      const bibleContextLine = profile.id === "HIGH_SCHOOL"
        ? `bible71 성경읽기(${dateIso}): ${bibleInfo.completed ? `완료 (${bibleInfo.passageText}, 총 ${bibleInfo.chaptersCount}장)` : "미기록"} | 날짜별 패턴: ${biblePattern.patternSummary} | 다음 날 제안: ${biblePattern.nextDaySuggestion}`
        : `초등 성경읽기 패턴: ${biblePattern.patternSummary} | 다음 날 제안: ${biblePattern.nextDaySuggestion} | 관리자 확인: ${$("inputElemBibleDone").checked ? `읽음 (${$("inputElemBiblePassage").value || "플래너 확인"})` : "사진에서 종이 플래너 성경읽기 분량을 직접 판독하세요"}`;

      const promptText = [
        `학생 구분: ${profile.emoji} ${profile.displayName} (이름: ${name})`,
        `검사 날짜: ${dateIso}`,
        `관리자 이번 주 집중 코칭 습관: ${focusHabitLabel}`,
        yesterdayPromise ? `어제 손글씨 약속: "${yesterdayPromise}" (관리자 이행 확인: ${keptYesterdayPromise ? "지킴" : "미확인/확인필요"})` : "",
        bibleContextLine,
        memo ? `지도자 메모: ${memo}` : "",
        historySummary ? `[최근 누적 기록]\n${historySummary}` : "",
        "플래너 사진을 읽고 Before->After 교정안, 색상별 모범 타임테이블, 성경읽기 판독 결과(bibleReadingCompleted/biblePassageText/bibleChaptersCount), 손글씨 베껴쓰기 및 내일 첫 공부 예약 문장을 JSON으로 반환해 주세요."
      ].filter(Boolean).join("\n");

      const schemaObj = {
        type: "object",
        required: [
          "habitScore", "gradeLabel", "praiseOneLiner", "improvementSummary",
          "weakHabitTags", "corrections", "timeBlocks",
          "totalStudyMinutes", "selfStudyMinutes", "lectureMinutes", "reviewMinutes",
          "plannedTasksCount", "completedTasksCount", "copywritingPromise", "copywritingPractice",
          "bibleReadingCompleted", "biblePassageText", "bibleChaptersCount"
        ],
        properties: {
          habitScore: { type: "integer" },
          gradeLabel: { type: "string" },
          praiseOneLiner: { type: "string" },
          improvementSummary: { type: "string" },
          weakHabitTags: { type: "array", items: { type: "string" } },
          totalStudyMinutes: { type: "integer" },
          selfStudyMinutes: { type: "integer" },
          lectureMinutes: { type: "integer" },
          reviewMinutes: { type: "integer" },
          plannedTasksCount: { type: "integer" },
          completedTasksCount: { type: "integer" },
          copywritingPromise: { type: "string" },
          copywritingPractice: { type: "string" },
          bibleReadingCompleted: { type: "boolean" },
          biblePassageText: { type: "string" },
          bibleChaptersCount: { type: "integer" },
          corrections: {
            type: "array",
            items: {
              type: "object",
              required: ["subject", "beforeText", "afterText", "missingTag", "targetMinutes", "completed"],
              properties: {
                subject: { type: "string" },
                beforeText: { type: "string" },
                afterText: { type: "string" },
                missingTag: { type: "string" },
                targetMinutes: { type: "integer" },
                completed: { type: "boolean" }
              }
            }
          },
          timeBlocks: {
            type: "array",
            items: {
              type: "object",
              required: ["startHour", "startMinute", "durationMinutes", "subject", "studyMode", "label"],
              properties: {
                startHour: { type: "integer" },
                startMinute: { type: "integer" },
                durationMinutes: { type: "integer" },
                subject: { type: "string" },
                studyMode: { type: "string", enum: ["SELF_STUDY", "LECTURE", "REVIEW"] },
                label: { type: "string" }
              }
            }
          }
        }
      };

      const parsed = await callGeminiInteraction({
        model: state.settings.geminiModel || "gemini-4-hydrogen",
        system_instruction: systemInstruction,
        store: false,
        input: [
          { type: "text", text: promptText },
          { type: "image", data: base64Jpeg, mime_type: "image/jpeg" }
        ],
        response_format: {
          type: "text",
          mime_type: "application/json",
          schema: schemaObj
        }
      });

      let nanoBananaDataUrl = null;
      if (state.settings.useNanoBanana && state.settings.geminiApiKey.trim()) {
        nanoBananaDataUrl = await callNanoBananaBasicImage(base64Jpeg, parsed, profile).catch(() => null);
      }

      const finalBibleCompleted = profile.id === "HIGH_SCHOOL"
        ? bibleInfo.completed
        : ($("inputElemBibleDone").checked || Boolean(parsed.bibleReadingCompleted));
      const finalBiblePassage = profile.id === "HIGH_SCHOOL"
        ? bibleInfo.passageText
        : (($("inputElemBiblePassage").value || "").trim() || parsed.biblePassageText || (finalBibleCompleted ? "종이 플래너 성경읽기 확인" : "오늘 읽을 성경 분량(예: 잠언 1장)을 적고 읽어보세요"));
      const finalBibleChapters = profile.id === "HIGH_SCHOOL"
        ? bibleInfo.chaptersCount
        : (Number($("inputElemBibleChapters").value) || Number(parsed.bibleChaptersCount) || (finalBibleCompleted ? 1 : 0));

      const rawScore = Number(parsed.habitScore) || 78;
      const bonusScore = Math.min(100, rawScore + (keptYesterdayPromise ? 10 : 0));

      const record = {
        id: crypto.randomUUID ? crypto.randomUUID() : `rec_${Date.now()}`,
        studentType: state.activeStudent,
        studentName: name,
        dateIso,
        habitScore: bonusScore,
        gradeLabel: bonusScore >= 90 ? "A+" : bonusScore >= 82 ? "A" : parsed.gradeLabel || "B+",
        praiseOneLiner: parsed.praiseOneLiner || "계획을 세우고 기록하려는 태도가 좋습니다!",
        improvementSummary: parsed.improvementSummary || "교재명·쪽수·목표 시간을 함께 적어 실행력을 높이세요.",
        weakHabitTags: (parsed.weakHabitTags || []).map((t) => (String(t).startsWith("#") ? t : `#${t}`)),
        corrections: parsed.corrections || [],
        timeBlocks: (parsed.timeBlocks || []).sort((a, b) => a.startHour * 60 + a.startMinute - (b.startHour * 60 + b.startMinute)),
        totalStudyMinutes: Number(parsed.totalStudyMinutes) || 120,
        selfStudyMinutes: Number(parsed.selfStudyMinutes) || 70,
        lectureMinutes: Number(parsed.lectureMinutes) || 30,
        reviewMinutes: Number(parsed.reviewMinutes) || 20,
        plannedTasksCount: Number(parsed.plannedTasksCount) || Math.max(1, (parsed.corrections || []).length),
        completedTasksCount: Number(parsed.completedTasksCount) || 0,
        copywritingPromise: parsed.copywritingPromise || "교재명과 분량, 목표 시간을 함께 적어 끝까지 실천한다.",
        copywritingPractice: parsed.copywritingPractice || "구체적인 책 이름과 쪽수를 꼭 적어 보세요!",
        bibleCompleted: finalBibleCompleted,
        biblePassageText: finalBiblePassage,
        bibleChaptersCount: finalBibleChapters,
        biblePatternSummary: biblePattern.patternSummary,
        bibleNextSuggestion: biblePattern.nextDaySuggestion,
        nanoBananaImageDataUrl: nanoBananaDataUrl,
        keptYesterdayPromise,
        focusHabitKey,
        syncedToDrive: false,
        createdAtEpochMs: Date.now()
      };

      state.records.unshift(record);
      saveRecords();
      await openDailyResult(record);
      refreshAllViews();

      if (state.settings.autoSyncToDrive && (state.settings.googleAppsScriptWebhookUrl || state.driveDirHandle)) {
        await syncDailyToGoogleDrive(record, state.currentDailyCanvas);
      } else {
        showToast(nanoBananaDataUrl
          ? "분석 + 나노 바나나 기본형 모범 플래너 생성 완료!"
          : "플래너 분석 완료! 수정안 그림과 베껴쓰기 박스를 확인하세요.");
      }
    } catch (err) {
      showToast(`분석 오류: ${err.message}`);
      showAiError("dailyAiErrorBox", err);
    } finally {
      btn.disabled = false;
      btn.textContent = "✨ Gemini AI 플래너 분석 & 모범 그림 생성";
    }
  }

  // ==================== Weekly Statistical + AI Synthesis Engine ====================

  function buildWeeklyReportLocal(useAi = false) {
    const profileId = state.activeStudent;
    const name = studentName(profileId);
    const allRecs = getStudentRecords(profileId);
    const mon = getMondayOfWeek(state.weekOffset);
    const sun = new Date(mon.getFullYear(), mon.getMonth(), mon.getDate() + 6);
    const prevMon = getMondayOfWeek(state.weekOffset + 1);
    const prevSun = new Date(prevMon.getFullYear(), prevMon.getMonth(), prevMon.getDate() + 6);

    const startIso = toIsoDate(mon);
    const endIso = toIsoDate(sun);
    const prevStartIso = toIsoDate(prevMon);
    const prevEndIso = toIsoDate(prevSun);

    const thisWeek = allRecs.filter((r) => r.dateIso >= startIso && r.dateIso <= endIso);
    const prevWeek = allRecs.filter((r) => r.dateIso >= prevStartIso && r.dateIso <= prevEndIso);
    const distinctDays = new Set(allRecs.map((r) => r.dateIso)).size;
    const stage = stageFromDays(distinctDays);

    const dayLabels = ["월", "화", "수", "목", "금", "토", "일"];
    const dailyBars = dayLabels.map((label, idx) => {
      const d = new Date(mon.getFullYear(), mon.getMonth(), mon.getDate() + idx);
      const iso = toIsoDate(d);
      const dayRecs = thisWeek.filter((r) => r.dateIso === iso);
      const minutes = dayRecs.reduce((s, r) => s + (r.totalStudyMinutes || 0), 0);
      let bibleDone = false;
      let bibleChapters = 0;
      if (profileId === "HIGH_SCHOOL") {
        const bStatus = getBibleStatusForDate("HIGH_SCHOOL", iso);
        bibleDone = bStatus.completed || dayRecs.some((r) => r.bibleCompleted);
        bibleChapters = bStatus.completed
          ? bStatus.chaptersCount
          : dayRecs.reduce((s, r) => s + (Number(r.bibleChaptersCount) || 0), 0);
      } else {
        bibleDone = dayRecs.some((r) => r.bibleCompleted);
        bibleChapters = dayRecs.reduce((s, r) => s + (Number(r.bibleChaptersCount) || (r.bibleCompleted ? 1 : 0)), 0);
      }
      return { dayLabel: label, dateIso: iso, minutes, bibleDone, bibleChapters };
    });

    const bibleDaysCount = dailyBars.filter((d) => d.bibleDone).length;
    const bibleChaptersTotal = dailyBars.reduce((s, d) => s + (d.bibleChapters || 0), 0);
    const biblePattern = getBiblePatternForStudent(profileId, endIso);
    const bibleSummaryLine = `${profileId === "HIGH_SCHOOL" ? "bible71 패턴" : "종이 플래너 패턴"}: ${biblePattern.patternSummary}`;

    const totalMinutes = thisWeek.reduce((s, r) => s + (r.totalStudyMinutes || 0), 0);
    const prevMinutes = prevWeek.length > 0 ? prevWeek.reduce((s, r) => s + (r.totalStudyMinutes || 0), 0) : null;
    const deltaMinutes = prevMinutes !== null ? totalMinutes - prevMinutes : null;

    const totalSelf = thisWeek.reduce((s, r) => s + (r.selfStudyMinutes || 0) + (r.reviewMinutes || 0), 0);
    const selfStudyRatio = totalMinutes > 0 ? Math.min(100, Math.round((totalSelf * 100) / totalMinutes)) : 0;

    const totalPlanned = thisWeek.reduce((s, r) => s + (r.plannedTasksCount || 0), 0);
    const totalDone = thisWeek.reduce((s, r) => s + (r.completedTasksCount || 0), 0);
    const completionRatePercent = totalPlanned > 0 ? Math.min(100, Math.round((totalDone * 100) / totalPlanned)) : 0;
    const avgHabitScore = thisWeek.length > 0 ? Math.round(thisWeek.reduce((s, r) => s + (r.habitScore || 0), 0) / thisWeek.length) : 0;

    const subjMap = new Map();
    for (const r of thisWeek) {
      for (const sb of computeSubjectBreakdowns(r)) {
        subjMap.set(sb.subject, (subjMap.get(sb.subject) || 0) + sb.minutes);
      }
    }
    const subjectBreakdowns = [...subjMap.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([subject, minutes]) => ({ subject, minutes, colorHex: colorForSubject(profileId, subject) }));

    const tagCount = new Map();
    for (const r of thisWeek) {
      for (const t of r.weakHabitTags || []) {
        tagCount.set(t, (tagCount.get(t) || 0) + 1);
      }
    }
    const topTag = [...tagCount.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] || "#분량구체화";

    const activeDaysCount = Math.max(1, new Set(thisWeek.map((r) => r.dateIso)).size);
    const avgDaily = thisWeek.length > 0 ? Math.round(totalMinutes / activeDaysCount) : 0;
    const nextDaily = avgDaily > 0
      ? Math.max(profileId === "HIGH_SCHOOL" ? 120 : 40, Math.round((avgDaily * 1.12) / 10) * 10)
      : (profileId === "HIGH_SCHOOL" ? 180 : 60);

    const bestDay = [...dailyBars].sort((a, b) => b.minutes - a.minutes)[0];
    const topSubject = subjectBreakdowns[0]?.subject || "주요 과목";

    const bestPatternInsight = thisWeek.length === 0
      ? "이번 주 기록이 아직 없습니다. 오늘 플래너 사진을 찍어 첫 기록을 쌓아 보세요!"
      : `${bestDay.dayLabel}요일에 ${formatMinutes(bestDay.minutes)}으로 집중력이 가장 높았고, ${topSubject} 중심 학습 리듬이 안정적입니다.`;

    const warningHabitInsight = thisWeek.length === 0
      ? "꾸준히 기록이 쌓일수록 개인별 맞춤 패턴과 예측 정확도가 올라갑니다."
      : `가장 자주 나온 보완점은 '${topTag}'입니다. 할 일 작성 시 교재명·쪽수·예상시간을 함께 적어 보세요.`;

    const overPlanningDiagnosis = thisWeek.length === 0
      ? "누적 기록이 쌓이면 계획 과욕지수(계획량 대비 실제 달성률)를 자동 진단합니다."
      : completionRatePercent < 65
        ? `계획 과욕지수 주의(달성률 ${completionRatePercent}%): 하루 계획을 3가지 핵심 항목으로 줄여 성취감을 높여 보세요.`
        : completionRatePercent < 85
          ? `계획량 적정(달성률 ${completionRatePercent}%): 소화 가능한 계획을 세우고 있습니다. 미완료 항목은 다음 날 복습 칸으로 이월하세요.`
          : `실천 균형 최상(달성률 ${completionRatePercent}%): 세운 계획을 정확히 지키는 실행력이 뛰어납니다! 다음 주는 분량을 10% 늘려보세요.`;

    let predictionInsight = "";
    if (stage.level === 1) {
      predictionInsight = "현재 씨앗기(데이터 수집 중)입니다. 4일 이상 누적되면 요일별 슬럼프 진단과 현실적 목표 예측이 열립니다.";
    } else if (stage.level === 2) {
      predictionInsight = `일평균 ${formatMinutes(avgDaily)} 속도 유지 시 다음 주 총 ${formatMinutes(nextDaily * 6)} 학습 및 순공 비율 ${Math.min(85, selfStudyRatio + 8)}% 달성이 예측됩니다.`;
    } else if (stage.level === 3) {
      const deltaMsg = deltaMinutes !== null
        ? (deltaMinutes >= 0 ? `+${formatMinutes(deltaMinutes)} 증가` : `${formatMinutes(-deltaMinutes)} 감소`)
        : "안정 유지";
      predictionInsight = `전주 대비 ${deltaMsg} 추세입니다. 현재 속도를 유지하면 다음 달 핵심 문제집 1권 완독이 무난합니다.`;
    } else {
      predictionInsight = `장기 누적 예측 완료! 주당 ${formatMinutes(totalMinutes)} 페이스 유지 시 현재 진행 중인 주요 교재·단원을 2~3주 내 마칠 수 있습니다.`;
    }

    const nextWeekGoals = profileId === "HIGH_SCHOOL"
      ? [
          { title: "일일 목표 공부량", targetMetric: `하루 ${formatMinutes(nextDaily)} (주 6일)`, actionTip: "가장 집중력이 떨어지는 요일에 30분 쪼개기 루틴 배치" },
          { title: "순공·복습 황금비율", targetMetric: `자습·오답 ${Math.min(85, Math.max(60, selfStudyRatio + 5))}% 달성`, actionTip: "인강 1시간당 반드시 백지복습·문제풀이 30분 이상 짝짓기" },
          { title: "플래너 구체성 습관", targetMetric: `${topTag} 제로화 (85점+)`, actionTip: "할 일 작성 시 [교재명+쪽수+문항수+예상시간] 4요소 필수 기입" }
        ]
      : [
          { title: "매일 플래너 약속", targetMetric: `하루 ${formatMinutes(nextDaily)} 실천하기`, actionTip: "학교 끝나고 10분 안에 오늘 할 일 3가지 먼저 적기" },
          { title: "무지개 과목 골고루", targetMetric: "국·수·영·독서 5색 채우기", actionTip: "한 과목만 몰아 하지 않고 30분씩 색깔 바꿔가며 공부하기" },
          { title: "칭찬 습관 기르기", targetMetric: "달성률 85% 이상 도전", actionTip: "'숙제' 대신 '무슨 책 몇 쪽'까지 쓰고 다 하면 ★ 도장 찍기" }
        ];

    const weeklyCopywritingPromise = profileId === "HIGH_SCHOOL"
      ? `${name}의 다음 주 약속: 감으로 계획하지 않고 하루 ${formatMinutes(nextDaily)} 분량과 시간을 설계해 85% 이상 완수한다.`
      : `다음 주에는 매일 ${formatMinutes(nextDaily)}씩, 책 이름과 몇 쪽 풀지 꼭 적고 끝까지 해낼게요!`;

    return {
      studentType: profileId,
      studentName: name,
      weekLabel: `${mon.getMonth() + 1}/${mon.getDate()} ~ ${sun.getMonth() + 1}/${sun.getDate()}`,
      startDateIso: startIso,
      endDateIso: endIso,
      totalAccumulatedDays: distinctDays,
      stage,
      totalStudyMinutes: totalMinutes,
      prevWeekDeltaMinutes: deltaMinutes,
      selfStudyRatioPercent: selfStudyRatio,
      completionRatePercent,
      avgHabitScore,
      bibleDaysCount,
      bibleChaptersTotal,
      bibleSummaryLine,
      dailyBars,
      subjectBreakdowns,
      bestPatternInsight,
      warningHabitInsight,
      overPlanningDiagnosis,
      predictionInsight,
      nextWeekGoals,
      weeklyCopywritingPromise,
      thisWeekRecords: thisWeek
    };
  }

  async function refreshWeeklyWithGeminiAi() {
    const baseReport = buildWeeklyReportLocal();
    if (!state.settings.geminiApiKey.trim() || baseReport.thisWeekRecords.length === 0) {
      state.currentWeeklyReport = baseReport;
      renderWeeklyPreview();
      if (!state.settings.geminiApiKey.trim()) {
        showToast("API 키 없이도 통계 리포트는 자동 계산됩니다. AI 심층 코칭은 설정에서 API 키를 입력해 주세요.");
      } else {
        showToast("이번 주 기록이 없어 기본 리포트를 표시했습니다.");
      }
      return;
    }

    const btn = $("btnRunWeeklyAi");
    btn.disabled = true;
    clearAiError("weeklyAiErrorBox");
    btn.textContent = "⏳ Gemini AI가 누적 데이터를 분석하고 진도를 예측하는 중...";

    try {
      const prompt = [
        `학생: ${PROFILES[baseReport.studentType].emoji} ${baseReport.studentName}`,
        `분석 단계: ${baseReport.stage.badgeText} (총 누적 ${baseReport.totalAccumulatedDays}일)`,
        `이번 주 공부시간: ${baseReport.totalStudyMinutes}분 (전주 대비: ${baseReport.prevWeekDeltaMinutes ?? "없음"}분)`,
        `순공 비율: ${baseReport.selfStudyRatioPercent}%, 계획 달성률: ${baseReport.completionRatePercent}%`,
        `성경읽기 현황: ${baseReport.bibleSummaryLine}`,
        `과목 분포: ${baseReport.subjectBreakdowns.map((s) => `${s.subject} ${s.minutes}분`).join(", ")}`,
        `일별 상세:\n${baseReport.thisWeekRecords.map((r) => `- ${r.dateIso}: ${r.totalStudyMinutes}분, 점수 ${r.habitScore}, 성경읽기 ${r.bibleCompleted ? "완료" : "미완료"}, 달성 ${r.completedTasksCount}/${r.plannedTasksCount}, 태그 ${(r.weakHabitTags || []).join(",")}`).join("\n")}`,
        "누적 데이터를 바탕으로 베스트 패턴, 보완 습관, 과욕지수 진단, 향후 학습/완독 예측, 다음 주 학습 및 성경읽기 목표 3가지, 주간 다짐 필사 문장을 JSON으로 만들어 주세요."
      ].join("\n");

      const ai = await callGeminiInteraction({
        model: state.settings.geminiModel || "gemini-4-hydrogen",
        system_instruction: "당신은 누적 학습 데이터를 분석해 학생에게 정확하고 따뜻한 주간 리포트를 제공하는 학습 코치입니다.",
        store: false,
        input: prompt,
        response_format: {
          type: "text",
          mime_type: "application/json",
          schema: {
            type: "object",
            required: ["bestPattern", "warningHabit", "overPlanning", "prediction", "goals", "weeklyCopywriting"],
            properties: {
              bestPattern: { type: "string" },
              warningHabit: { type: "string" },
              overPlanning: { type: "string" },
              prediction: { type: "string" },
              weeklyCopywriting: { type: "string" },
              goals: {
                type: "array",
                minItems: 3,
                maxItems: 3,
                items: {
                  type: "object",
                  required: ["title", "targetMetric", "actionTip"],
                  properties: {
                    title: { type: "string" },
                    targetMetric: { type: "string" },
                    actionTip: { type: "string" }
                  }
                }
              }
            }
          }
        }
      });

      state.currentWeeklyReport = {
        ...baseReport,
        bestPatternInsight: ai.bestPattern || baseReport.bestPatternInsight,
        warningHabitInsight: ai.warningHabit || baseReport.warningHabitInsight,
        overPlanningDiagnosis: ai.overPlanning || baseReport.overPlanningDiagnosis,
        predictionInsight: ai.prediction || baseReport.predictionInsight,
        nextWeekGoals: Array.isArray(ai.goals) && ai.goals.length === 3 ? ai.goals : baseReport.nextWeekGoals,
        weeklyCopywritingPromise: ai.weeklyCopywriting || baseReport.weeklyCopywritingPromise
      };
      renderWeeklyPreview();
      showToast("Gemini AI 주간 분석 · 예측 리포트를 최신화했습니다!");
    } catch (err) {
      showToast(`AI 분석 실패(통계 리포트는 유지됨): ${err.message}`);
      showAiError("weeklyAiErrorBox", err);
    } finally {
      btn.disabled = false;
      btn.textContent = "✨ Gemini AI 심층 주간 분석·진도 예측 업데이트";
    }
  }

  // ==================== Export / Share / Print / Google Drive ====================

  function buildDailyShareMessage(rec) {
    const p = PROFILES[rec.studentType];
    const bibleLine = rec.bibleCompleted
      ? `📖 성경읽기 완료: ${rec.biblePassageText || ""} (${rec.bibleChaptersCount || 1}장)`
      : `📖 성경읽기: ${rec.biblePassageText || "오늘 성경 분량을 플래너에 적고 읽어보세요!"}`;
    return [
      `[${p.emoji} ${rec.studentName} · ${rec.dateIso} 플래너 검사 리포트]`,
      `• 플래너 점수: ${rec.habitScore}점 (${rec.gradeLabel})${rec.keptYesterdayPromise ? " 🎉 어제 약속 이행!" : ""}`,
      `• 총 공부시간: ${formatMinutes(rec.totalStudyMinutes)} (달성률 ${completionRate(rec)}%)`,
      `• ${bibleLine}`,
      `• 잘한 점: ${rec.praiseOneLiner}`,
      `• 수정 방향: ${rec.improvementSummary}`,
      rec.weakHabitTags?.length ? `• 보완 포인트: ${rec.weakHabitTags.join(" ")}` : "",
      "",
      "✍️ [플래너 하단에 그대로 베껴쓰기]",
      `1) 약속: ${rec.copywritingPromise}`,
      `2) 수정/내일예약: ${rec.copywritingPractice}`
    ].filter(Boolean).join("\n");
  }

  function buildWeeklyShareMessage(rep) {
    return [
      `[📊 ${rep.studentName} · ${rep.weekLabel} 주간 공부·말씀 분석 리포트]`,
      `• 누적 단계: ${rep.stage.badgeText}`,
      `• 총 공부시간: ${formatMinutes(rep.totalStudyMinutes)} / 달성률 ${rep.completionRatePercent}% / 순공비율 ${rep.selfStudyRatioPercent}%`,
      `• ${rep.bibleSummaryLine}`,
      `• 💡 베스트: ${rep.bestPatternInsight}`,
      `• ⚠️ 코칭: ${rep.warningHabitInsight}`,
      `• 🔮 예측: ${rep.predictionInsight}`,
      "",
      "🎯 [다음 1주일 목표]",
      ...rep.nextWeekGoals.map((g, i) => `${i + 1}. ${g.title}: ${g.targetMetric} (${g.actionTip})`),
      "",
      "✍️ [다음 주 다짐 베껴쓰기]",
      rep.weeklyCopywritingPromise
    ].join("\n");
  }

  async function shareToKakao(canvas, text, fileName) {
    if (!canvas) {
      showToast("공유할 리포트 이미지가 아직 생성되지 않았습니다.");
      return;
    }
    try {
      await navigator.clipboard?.writeText(text);
    } catch (_) {}

    const pngBlob = await canvasToPngBlob(canvas);
    const pngFile = new File([pngBlob], fileName, { type: "image/png" });

    if (navigator.canShare && navigator.canShare({ files: [pngFile] })) {
      try {
        await navigator.share({
          files: [pngFile],
          title: "플래너 코치 AI 리포트",
          text
        });
        showToast("공유 시트에서 카카오톡을 선택해 전송하세요!");
        return;
      } catch (err) {
        if (err && err.name === "AbortError") return;
      }
    }

    // Desktop browser fallback: download PNG image + copied text ready to paste in KakaoTalk PC
    downloadBlob(pngBlob, fileName);
    showToast("리포트 이미지(PNG) 저장 + 카톡 메시지 복사 완료! 카카오톡에 바로 붙여넣으세요.");
  }

  function saveCanvasAsPdf(canvas, filename) {
    if (!canvas) {
      showToast("저장할 리포트가 없습니다.");
      return;
    }
    const pdfBlob = buildA4PdfBlobFromCanvas(canvas);
    downloadBlob(pdfBlob, filename);
    showToast(`A4 PDF 저장 완료: ${filename}`);
  }

  function printCanvas(canvas) {
    if (!canvas) {
      showToast("인쇄할 리포트가 없습니다.");
      return;
    }
    const img = $("printSheetImg");
    const dataUrl = canvas.toDataURL("image/png");
    if (img.src === dataUrl && img.complete) {
      window.print();
    } else {
      img.onload = () => {
        img.onload = null;
        window.print();
      };
      img.src = dataUrl;
    }
  }

  function buildStudentCsv(records) {
    const header = "날짜,학생구분,이름,플래너점수,등급,총공부시간(분),자습(분),인강(분),복습(분),계획수,완료수,달성률(%),부족습관태그,오늘의약속,수정방향";
    const esc = (v) => `"${String(v ?? "").replace(/\n/g, " ").replace(/"/g, '""')}"`;
    const rows = [...records]
      .sort((a, b) => (a.dateIso || "").localeCompare(b.dateIso || ""))
      .map((r) => [
        r.dateIso,
        PROFILES[r.studentType]?.displayName || r.studentType,
        r.studentName,
        r.habitScore,
        r.gradeLabel,
        r.totalStudyMinutes,
        r.selfStudyMinutes,
        r.lectureMinutes,
        r.reviewMinutes,
        r.plannedTasksCount,
        r.completedTasksCount,
        completionRate(r),
        (r.weakHabitTags || []).join(" "),
        r.copywritingPromise,
        r.improvementSummary
      ].map(esc).join(","));
    return "\uFEFF" + [header, ...rows].join("\n");
  }

  async function writeFileToDirectoryHandle(dirHandle, subDirName, fileName, blobOrText) {
    const targetDir = subDirName
      ? await dirHandle.getDirectoryHandle(subDirName, { create: true })
      : dirHandle;
    const fh = await targetDir.getFileHandle(fileName, { create: true });
    const writable = await fh.createWritable();
    await writable.write(blobOrText);
    await writable.close();
  }

  async function syncDailyToGoogleDrive(record, canvas) {
    const channels = [];
    const pngBlob = canvas ? await canvasToPngBlob(canvas) : null;
    const pdfBlob = canvas ? buildA4PdfBlobFromCanvas(canvas) : null;
    const folderLabel = record.studentType === "HIGH_SCHOOL" ? "고등학생_누적기록" : "초등학생_누적기록";
    const stem = `일일리포트_${record.dateIso}_${String(record.id).slice(0, 6)}`;

    if (state.driveDirHandle) {
      try {
        const backupJson = JSON.stringify({ schemaVersion: 1, exportedAtEpochMs: Date.now(), records: state.records }, null, 2);
        await writeFileToDirectoryHandle(state.driveDirHandle, null, "planner_backup_db.json", backupJson);
        await writeFileToDirectoryHandle(state.driveDirHandle, folderLabel, `누적학습기록_${record.studentName}.csv`, buildStudentCsv(getStudentRecords(record.studentType)));
        if (pngBlob) await writeFileToDirectoryHandle(state.driveDirHandle, folderLabel, `${stem}.png`, pngBlob);
        if (pdfBlob) await writeFileToDirectoryHandle(state.driveDirHandle, folderLabel, `${stem}.pdf`, pdfBlob);
        channels.push("연결된 드라이브 폴더");
      } catch (err) {
        showToast(`폴더 저장 실패: ${err.message}`);
      }
    }

    if (state.settings.googleAppsScriptWebhookUrl.trim()) {
      try {
        const payload = {
          action: "save_daily_inspection",
          studentLabel: folderLabel,
          record,
          backupJson: JSON.stringify({ schemaVersion: 1, exportedAtEpochMs: Date.now(), records: state.records }),
          pngBase64: pngBlob ? await blobToBase64(pngBlob) : null,
          pdfBase64: pdfBlob ? await blobToBase64(pdfBlob) : null
        };
        await fetch(state.settings.googleAppsScriptWebhookUrl.trim(), {
          method: "POST",
          mode: "no-cors",
          headers: { "Content-Type": "text/plain;charset=utf-8" },
          body: JSON.stringify(payload)
        });
        channels.push("구글 드라이브·시트 웹훅");
      } catch (err) {
        showToast(`웹훅 전송 오류: ${err.message}`);
      }
    }

    if (channels.length > 0) {
      record.syncedToDrive = true;
      saveRecords();
      showToast(`${channels.join(" & ")}에 자동 누적 저장되었습니다!`);
    } else {
      showToast("설정 탭에서 구글 드라이브 폴더 또는 Apps Script 웹훅 URL을 연결해 주세요.");
    }
  }

  async function syncWeeklyToGoogleDrive(report, canvas) {
    if (!report || !canvas) return;
    const pngBlob = await canvasToPngBlob(canvas);
    const pdfBlob = buildA4PdfBlobFromCanvas(canvas);
    const folderLabel = report.studentType === "HIGH_SCHOOL" ? "고등학생_누적기록" : "초등학생_누적기록";
    const stem = `주간리포트_${report.startDateIso}_${report.endDateIso}`;
    const channels = [];

    if (state.driveDirHandle) {
      try {
        await writeFileToDirectoryHandle(state.driveDirHandle, folderLabel, `${stem}.png`, pngBlob);
        await writeFileToDirectoryHandle(state.driveDirHandle, folderLabel, `${stem}.pdf`, pdfBlob);
        channels.push("연결된 드라이브 폴더");
      } catch (_) {}
    }

    if (state.settings.googleAppsScriptWebhookUrl.trim()) {
      try {
        await fetch(state.settings.googleAppsScriptWebhookUrl.trim(), {
          method: "POST",
          mode: "no-cors",
          headers: { "Content-Type": "text/plain;charset=utf-8" },
          body: JSON.stringify({
            action: "save_weekly_report",
            studentType: report.studentType,
            studentLabel: folderLabel,
            fileStem: stem,
            pngBase64: await blobToBase64(pngBlob),
            pdfBase64: await blobToBase64(pdfBlob)
          })
        });
        channels.push("구글 드라이브 웹훅");
      } catch (_) {}
    }

    if (channels.length > 0) {
      showToast(`${channels.join(" & ")}에 주간 리포트(PNG/PDF)를 저장했습니다!`);
    } else {
      showToast("설정 탭에서 구글 드라이브 폴더 또는 Apps Script 웹훅을 연결해 주세요.");
    }
  }

  // ==================== UI Rendering & Events ====================

  function switchTab(tabId) {
    state.activeTab = tabId;
    document.querySelectorAll(".tab-panel").forEach((el) => el.classList.remove("active"));
    $(`panel-${tabId}`).classList.add("active");
    document.querySelectorAll(".nav-btn").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.tab === tabId);
    });
  }

  function loadImageAsync(dataUrl) {
    return new Promise((resolve) => {
      if (!dataUrl) {
        resolve(null);
        return;
      }
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => resolve(null);
      img.src = dataUrl;
    });
  }

  async function openDailyResult(record) {
    state.currentDailyResult = record;
    $("dailyResultSection").style.display = "flex";
    $("editSummaryInput").value = record.improvementSummary || "";
    $("editPromiseInput").value = record.copywritingPromise || "";
    $("editPracticeInput").value = record.copywritingPractice || "";
    if (record.studentType === "ELEMENTARY") {
      $("inputElemBibleDone").checked = Boolean(record.bibleCompleted);
      $("inputElemBiblePassage").value = record.biblePassageText || "";
      $("inputElemBibleChapters").value = record.bibleChaptersCount || 0;
    }

    let nanoImgEl = null;
    if (record.nanoBananaImageDataUrl) {
      $("nanoBananaPreviewCard").style.display = "block";
      $("nanoBananaRenderedImg").src = record.nanoBananaImageDataUrl;
      nanoImgEl = await loadImageAsync(record.nanoBananaImageDataUrl);
    } else {
      $("nanoBananaPreviewCard").style.display = "none";
    }

    const canvas = renderDailyReportCanvas(record, state.settings.includeHandwritingPracticeInPdf, nanoImgEl);
    state.currentDailyCanvas = canvas;
    $("dailyRenderedImg").src = canvas.toDataURL("image/png");
  }

  function renderWeeklyPreview() {
    const rep = state.currentWeeklyReport || buildWeeklyReportLocal();
    state.currentWeeklyReport = rep;
    $("weeklyStagePill").textContent = rep.stage.badgeText;
    const canvas = renderWeeklyReportCanvas(rep, state.settings.includeHandwritingPracticeInPdf);
    state.currentWeeklyCanvas = canvas;
    $("weeklyRenderedImg").src = canvas.toDataURL("image/png");
  }

  function refreshAllViews() {
    const profile = PROFILES[state.activeStudent];
    const records = getStudentRecords();
    const distinctDays = new Set(records.map((r) => r.dateIso)).size;
    const stage = stageFromDays(distinctDays);

    $("hsLabelText").textContent = studentName("HIGH_SCHOOL");
    $("elLabelText").textContent = studentName("ELEMENTARY");
    $("headerSubtitle").textContent = `${profile.emoji} ${studentName()} · ${profile.subtitle}`;
    $("accDaysBadge").textContent = `누적 ${distinctDays}일`;
    $("homeWeekTitle").textContent = `${profile.emoji} ${studentName()} 이번 주 누적 현황`;
    $("homeStageBadge").textContent = stage.badgeText;
    $("inspectTitle").textContent = `${profile.emoji} ${studentName()} 플래너 사진 검사`;
    $("inspectSlotModeBadge").textContent = profile.id === "HIGH_SCHOOL" ? "10분 텐미닛" : "30분 블록";
    $("recordCountText").textContent = String(records.length);

    // Week dots
    const mon = getMondayOfWeek(0);
    const dayNames = ["월", "화", "수", "목", "금", "토", "일"];
    const dotsHtml = dayNames.map((name, idx) => {
      const iso = toIsoDate(new Date(mon.getFullYear(), mon.getMonth(), mon.getDate() + idx));
      const done = records.some((r) => r.dateIso === iso);
      return `<div class="day-dot-col"><div class="day-dot ${done ? "done" : ""}">${done ? "✓" : name}</div><span class="day-dot-label">${name}</span></div>`;
    }).join("");
    $("weekDotsGrid").innerHTML = dotsHtml;

    updateBibleUiPanels();

    // Recent records list
    const listBox = $("recordsListContainer");
    if (records.length === 0) {
      listBox.innerHTML = `<p style="font-size:13px; color:#64748B; margin:8px 0 0;">아직 검사 기록이 없습니다. 플래너 사진을 찍거나 우측 상단 <strong>'체험용 7일 샘플 채우기'</strong>를 눌러 바로 확인해 보세요.</p>`;
    } else {
      listBox.innerHTML = records.map((r) => `
        <div class="record-item" data-rec-id="${r.id}">
          <div class="score-box">${r.habitScore}점</div>
          <div class="record-meta">
            <div class="record-title">${r.dateIso} · ${formatMinutes(r.totalStudyMinutes)} · ${r.bibleCompleted ? "📖성경✓" : "📖미완"} (달성 ${completionRate(r)}%)</div>
            <div class="record-sub">${r.copywritingPromise || ""}</div>
          </div>
          <button class="btn btn-ghost" data-del-id="${r.id}" title="삭제">🗑️</button>
        </div>
      `).join("");

      listBox.querySelectorAll(".record-item").forEach((el) => {
        el.addEventListener("click", (e) => {
          const delId = e.target?.dataset?.delId;
          if (delId) {
            e.stopPropagation();
            state.records = state.records.filter((r) => r.id !== delId);
            saveRecords();
            refreshAllViews();
            showToast("기록을 삭제했습니다.");
            return;
          }
          const rec = state.records.find((r) => r.id === el.dataset.recId);
          if (rec) {
            openDailyResult(rec);
            switchTab("inspect");
          }
        });
      });
    }

    state.currentWeeklyReport = buildWeeklyReportLocal();
    renderWeeklyPreview();
    if (state.currentDailyResult && state.currentDailyResult.studentType === state.activeStudent) {
      openDailyResult(state.currentDailyResult);
    } else {
      $("dailyResultSection").style.display = "none";
      state.currentDailyResult = null;
      state.currentDailyCanvas = null;
    }
  }

  function seedDemoWeekForActiveStudent() {
    const profileId = state.activeStudent;
    const name = studentName(profileId);
    const mon = getMondayOfWeek(0);

    const newItems = Array.from({ length: 6 }, (_, offset) => {
      const dateIso = toIsoDate(new Date(mon.getFullYear(), mon.getMonth(), mon.getDate() + offset));
      if (profileId === "HIGH_SCHOOL") {
        const mins = [290, 260, 320, 210, 340, 360][offset];
        return {
          id: `demo_hs_${offset}`,
          studentType: "HIGH_SCHOOL",
          studentName: name,
          dateIso,
          habitScore: [72, 76, 84, 68, 86, 90][offset],
          gradeLabel: ["B", "B+", "A", "C+", "A", "A+"][offset],
          praiseOneLiner: "수·금요일 오답 복습 블록을 꾸준히 배치한 점이 매우 우수합니다.",
          improvementSummary: "과목명만 적는 습관을 버리고 [교재명 + 쪽수/문항수 + 예상시간]을 반드시 함께 기입하세요.",
          corrections: [
            { subject: "성경", beforeText: "성경 읽기", afterText: "맥체인 오늘 분량(4장) 정독 후 bible71 완료 체크하기", missingTag: "#말씀분량기록", targetMinutes: 20, completed: true },
            { subject: "수학", beforeText: "수학 문제집 풀기", afterText: "쎈 수학(상) 이차함수 B단계 p.64~68 (22문항) + 오답", missingTag: "#분량미기재", targetMinutes: 90, completed: true },
            { subject: "영어", beforeText: "영어 단어랑 인강", afterText: "워드마스터 Day 15 암기(30분) + 구문 인강 후 20분 백지복습", missingTag: "#복습누락", targetMinutes: 70, completed: offset !== 3 },
            { subject: "국어", beforeText: "비문학 지문", afterText: "마더텅 비문학 사회 3지문 시간재기(45분) 및 근거 표시", missingTag: "#시간모호", targetMinutes: 50, completed: true }
          ],
          timeBlocks: [
            { startHour: 8, startMinute: 20, durationMinutes: 20, subject: "성경", studyMode: "SELF_STUDY", label: "맥체인 4장" },
            { startHour: 9, startMinute: 0, durationMinutes: 50, subject: "국어", studyMode: "SELF_STUDY", label: "비문학" },
            { startHour: 10, startMinute: 0, durationMinutes: 90, subject: "수학", studyMode: "SELF_STUDY", label: "쎈 B단계" },
            { startHour: 13, startMinute: 30, durationMinutes: 60, subject: "영어", studyMode: "LECTURE", label: "구문 인강" },
            { startHour: 14, startMinute: 30, durationMinutes: 30, subject: "영어", studyMode: "REVIEW", label: "백지복습" },
            { startHour: 19, startMinute: 0, durationMinutes: 60, subject: "탐구", studyMode: "SELF_STUDY", label: "개념완성" }
          ],
          totalStudyMinutes: mins,
          selfStudyMinutes: Math.round(mins * 0.58),
          lectureMinutes: Math.round(mins * 0.25),
          reviewMinutes: Math.round(mins * 0.17),
          plannedTasksCount: 4,
          completedTasksCount: offset === 3 ? 2 : 4,
          weakHabitTags: offset < 2 ? ["#분량미기재", "#인강편중"] : ["#오답시간확보"],
          copywritingPromise: "매일 성경읽기를 마치고, 과목별 교재명·쪽수·목표시간을 적어 인강 후 20분 복습한다.",
          copywritingPractice: "내일 첫 시작: 오전 8시 20분 맥체인 성경읽기 후 쎈 수학 p.69~72 20문제 풀기!",
          bibleCompleted: getBibleStatusForDate("HIGH_SCHOOL", dateIso).completed || offset !== 3,
          biblePassageText: getBibleStatusForDate("HIGH_SCHOOL", dateIso).completed
            ? getBibleStatusForDate("HIGH_SCHOOL", dateIso).passageText
            : `마가복음 ${offset + 1}~${offset + 2}장 (2장)`,
          bibleChaptersCount: getBibleStatusForDate("HIGH_SCHOOL", dateIso).chaptersCount || 2,
          biblePatternSummary: getBiblePatternForStudent("HIGH_SCHOOL", dateIso).patternSummary,
          bibleNextSuggestion: getBiblePatternForStudent("HIGH_SCHOOL", dateIso).nextDaySuggestion,
          keptYesterdayPromise: offset >= 4,
          syncedToDrive: false,
          createdAtEpochMs: Date.now() - (6 - offset) * 86400000
        };
      }
      const mins = [90, 90, 120, 60, 120, 90][offset];
      return {
        id: `demo_el_${offset}`,
        studentType: "ELEMENTARY",
        studentName: name,
        dateIso,
        habitScore: [78, 82, 88, 74, 90, 92][offset],
        gradeLabel: ["B+", "A", "A+", "B", "A+", "A+"][offset],
        praiseOneLiner: "매일 정해진 시간에 책상에 앉아 성경읽기와 플래너 색칠을 끝까지 해낸 점이 최고예요!",
        improvementSummary: "'성경 읽기', '숙제'라고만 쓰지 말고 성경 책 이름(몇 장)과 문제집 몇 쪽 풀지 숫자를 적어요.",
        corrections: [
          { subject: "성경", beforeText: "성경 읽기", afterText: `잠언 ${offset + 1}장 소리 내어 읽고 플래너에 장수 쓰기`, missingTag: "#성경분량기재", targetMinutes: 15, completed: offset !== 3 },
          { subject: "수학·연산", beforeText: "수학 숙제", afterText: "기탄수학 D단계 3장(p.12~14) 풀고 채점하기", missingTag: "#몇쪽인지쓰기", targetMinutes: 30, completed: true },
          { subject: "국어·독서", beforeText: "책 읽기", afterText: "과학 뒤집기 1단원(20쪽) 읽고 한 줄 느낌 쓰기", missingTag: "#분량약속", targetMinutes: 30, completed: true },
          { subject: "영어", beforeText: "영어 공부", afterText: "영어 단어카드 10개 소리 내어 3번씩 읽기", missingTag: "#구체적목표", targetMinutes: 30, completed: offset !== 3 }
        ],
        timeBlocks: [
          { startHour: 14, startMinute: 30, durationMinutes: 30, subject: "성경", studyMode: "SELF_STUDY", label: `잠언 ${offset + 1}장` },
          { startHour: 15, startMinute: 0, durationMinutes: 30, subject: "학교·숙제", studyMode: "SELF_STUDY", label: "알림장" },
          { startHour: 16, startMinute: 0, durationMinutes: 30, subject: "수학·연산", studyMode: "SELF_STUDY", label: "연산 3장" },
          { startHour: 17, startMinute: 0, durationMinutes: 30, subject: "영어", studyMode: "LECTURE", label: "단어 읽기" },
          { startHour: 19, startMinute: 30, durationMinutes: 30, subject: "국어·독서", studyMode: "REVIEW", label: "독서 20쪽" }
        ],
        totalStudyMinutes: mins,
        selfStudyMinutes: Math.round(mins * 0.65),
        lectureMinutes: Math.round(mins * 0.2),
        reviewMinutes: Math.round(mins * 0.15),
        plannedTasksCount: 4,
        completedTasksCount: offset === 3 ? 2 : 4,
        weakHabitTags: ["#성경분량기재", "#몇쪽인지쓰기"],
        copywritingPromise: "성경 몇 장 읽었는지, 무슨 책 몇 쪽 풀지 숫자로 꼭 적을게요!",
        copywritingPractice: `내일 첫 시작: 오후 3시 잠언 ${offset + 2}장 읽고 기탄수학 3장 풀기!`,
        bibleCompleted: offset !== 3,
        biblePassageText: `잠언 ${offset + 1}장 (종이 플래너 기록)`,
        bibleChaptersCount: offset === 3 ? 0 : 1,
        keptYesterdayPromise: offset >= 4,
        syncedToDrive: false,
        createdAtEpochMs: Date.now() - (6 - offset) * 86400000
      };
    });

    const existingIds = new Set(state.records.map((r) => r.id));
    for (const item of newItems) {
      if (!existingIds.has(item.id)) state.records.push(item);
    }
    saveRecords();
    refreshAllViews();
    openDailyResult(newItems[newItems.length - 1]);
    showToast(`${PROFILES[profileId].displayName} 체험용 6일 누적 샘플을 채웠습니다! 주간리포트·PDF·인쇄를 테스트해 보세요.`);
  }

  function handlePhotoInput(file) {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      state.uploadedImageDataUrl = reader.result;
      $("uploadedPhotoImg").src = state.uploadedImageDataUrl;
      $("photoPreviewBox").style.display = "block";
      switchTab("inspect");
      showToast("플래너 사진이 준비되었습니다. [Gemini AI 플래너 분석]을 눌러주세요!");
    };
    reader.readAsDataURL(file);
  }

  function populateSettingsInputs() {
    $("settingApiKey").value = state.settings.geminiApiKey || "";
    $("settingModel").value = state.settings.geminiModel || "gemini-4-hydrogen";
    $("settingUseNanoBanana").checked = state.settings.useNanoBanana !== false;
    $("settingHsName").value = state.settings.highSchoolName || "김희균";
    $("settingElName").value = state.settings.elementaryName || "초등학생";
    $("settingBibleMemberName").value = state.settings.bibleMemberName || "김희균";
    $("settingBibleMemberPhone").value = state.settings.bibleMemberPhone || "01023708994";
    $("selectFocusHabit").value = state.settings.focusHabit || "BIBLE_AND_VOLUME";
    $("settingWebhookUrl").value = state.settings.googleAppsScriptWebhookUrl || "";
    $("settingAutoSync").checked = Boolean(state.settings.autoSyncToDrive);
    $("settingPdfLines").checked = Boolean(state.settings.includeHandwritingPracticeInPdf);
  }

  function applyImportedSettingsObject(rawObj) {
    const src = (rawObj && typeof rawObj.settings === "object" && rawObj.settings) ? rawObj.settings : rawObj;
    if (!src || typeof src !== "object") return false;
    const allowedKeys = [
      "geminiApiKey", "geminiModel", "useNanoBanana",
      "highSchoolName", "elementaryName",
      "bibleMemberName", "bibleMemberPhone",
      "focusHabit", "googleAppsScriptWebhookUrl",
      "autoSyncToDrive", "includeHandwritingPracticeInPdf"
    ];
    let changed = false;
    for (const k of allowedKeys) {
      if (src[k] !== undefined && src[k] !== null) {
        state.settings[k] = typeof src[k] === "string" ? src[k].trim() : src[k];
        changed = true;
      }
    }
    if (!changed) return false;
    saveSettings();
    populateSettingsInputs();
    refreshAllViews();
    fetchBible71LogsFromDb(false);
    return true;
  }

  function bindEvents() {
    $("inputInspectDate").value = toIsoDate(new Date());

    // Populate settings inputs
    populateSettingsInputs();

    $("inputInspectDate").addEventListener("change", updateBibleUiPanels);
    $("btnRefreshBibleHome").addEventListener("click", () => fetchBible71LogsFromDb(true));
    $("btnFetchBibleForDate").addEventListener("click", () => fetchBible71LogsFromDb(true));
    $("btnSyncBibleNow").addEventListener("click", () => fetchBible71LogsFromDb(true));
    $("selectFocusHabit").addEventListener("change", (e) => {
      state.settings.focusHabit = e.target.value;
      saveSettings();
    });

    document.querySelectorAll(".student-tab").forEach((tab) => {
      tab.addEventListener("click", () => {
        state.activeStudent = tab.dataset.student;
        document.querySelectorAll(".student-tab").forEach((t) => t.classList.toggle("active", t === tab));
        refreshAllViews();
      });
    });

    document.querySelectorAll(".nav-btn").forEach((btn) => {
      btn.addEventListener("click", () => switchTab(btn.dataset.tab));
    });

    $("btnQuickCamera").addEventListener("click", () => $("inputCameraFile").click());
    $("btnQuickGallery").addEventListener("click", () => $("inputGalleryFile").click());
    $("btnCapturePhoto").addEventListener("click", () => $("inputCameraFile").click());
    $("btnPickPhoto").addEventListener("click", () => $("inputGalleryFile").click());
    $("btnGoWeekly").addEventListener("click", () => switchTab("weekly"));
    $("btnSeedDemo").addEventListener("click", seedDemoWeekForActiveStudent);

    $("inputCameraFile").addEventListener("change", (e) => handlePhotoInput(e.target.files[0]));
    $("inputGalleryFile").addEventListener("change", (e) => handlePhotoInput(e.target.files[0]));
    $("btnRunDailyAi").addEventListener("click", analyzeDailyPlannerPhoto);

    $("btnApplyCopywritingEdit").addEventListener("click", () => {
      if (!state.currentDailyResult) return;
      state.currentDailyResult.improvementSummary = $("editSummaryInput").value.trim();
      state.currentDailyResult.copywritingPromise = $("editPromiseInput").value.trim();
      state.currentDailyResult.copywritingPractice = $("editPracticeInput").value.trim();
      state.currentDailyResult.keptYesterdayPromise = Boolean($("checkboxKeptYesterdayPromise").checked);
      if (state.currentDailyResult.studentType === "ELEMENTARY") {
        state.currentDailyResult.bibleCompleted = Boolean($("inputElemBibleDone").checked);
        state.currentDailyResult.biblePassageText = $("inputElemBiblePassage").value.trim() || "종이 플래너 성경읽기";
        state.currentDailyResult.bibleChaptersCount = Number($("inputElemBibleChapters").value) || 0;
      }
      saveRecords();
      openDailyResult(state.currentDailyResult);
      refreshAllViews();
      showToast("수정된 필사 문구와 성경·코멘트를 그림 카드에 반영했습니다!");
    });

    $("btnDailyKakao").addEventListener("click", () => {
      if (!state.currentDailyResult) return;
      shareToKakao(
        state.currentDailyCanvas,
        buildDailyShareMessage(state.currentDailyResult),
        `플래너수정안_${state.currentDailyResult.studentName}_${state.currentDailyResult.dateIso}.png`
      );
    });
    $("btnDailyPdf").addEventListener("click", () => {
      if (!state.currentDailyResult) return;
      saveCanvasAsPdf(state.currentDailyCanvas, `플래너수정안_${state.currentDailyResult.studentName}_${state.currentDailyResult.dateIso}.pdf`);
    });
    $("btnDailyPrint").addEventListener("click", () => printCanvas(state.currentDailyCanvas));
    $("btnDailyDrive").addEventListener("click", () => {
      if (!state.currentDailyResult) return;
      syncDailyToGoogleDrive(state.currentDailyResult, state.currentDailyCanvas);
    });

    document.querySelectorAll("[data-week-offset]").forEach((btn) => {
      btn.addEventListener("click", () => {
        state.weekOffset = Number(btn.dataset.weekOffset);
        document.querySelectorAll("[data-week-offset]").forEach((b) => b.classList.toggle("active", b === btn));
        state.currentWeeklyReport = buildWeeklyReportLocal();
        renderWeeklyPreview();
      });
    });

    $("btnRunWeeklyAi").addEventListener("click", refreshWeeklyWithGeminiAi);
    $("btnWeeklyKakao").addEventListener("click", () => {
      if (!state.currentWeeklyReport) return;
      shareToKakao(
        state.currentWeeklyCanvas,
        buildWeeklyShareMessage(state.currentWeeklyReport),
        `주간리포트_${state.currentWeeklyReport.studentName}_${state.currentWeeklyReport.startDateIso}.png`
      );
    });
    $("btnWeeklyPdf").addEventListener("click", () => {
      if (!state.currentWeeklyReport) return;
      saveCanvasAsPdf(state.currentWeeklyCanvas, `주간리포트_${state.currentWeeklyReport.studentName}_${state.currentWeeklyReport.startDateIso}.pdf`);
    });
    $("btnWeeklyPrint").addEventListener("click", () => printCanvas(state.currentWeeklyCanvas));
    $("btnWeeklyDrive").addEventListener("click", () => syncWeeklyToGoogleDrive(state.currentWeeklyReport, state.currentWeeklyCanvas));

    $("btnSaveSettings").addEventListener("click", () => {
      state.settings = {
        geminiApiKey: $("settingApiKey").value.trim(),
        geminiModel: $("settingModel").value,
        useNanoBanana: $("settingUseNanoBanana").checked,
        highSchoolName: $("settingHsName").value.trim() || "김희균",
        elementaryName: $("settingElName").value.trim() || "초등학생",
        bibleMemberName: $("settingBibleMemberName").value.trim() || "김희균",
        bibleMemberPhone: $("settingBibleMemberPhone").value.trim() || "01023708994",
        focusHabit: $("selectFocusHabit").value || "BIBLE_AND_VOLUME",
        googleAppsScriptWebhookUrl: $("settingWebhookUrl").value.trim(),
        autoSyncToDrive: $("settingAutoSync").checked,
        includeHandwritingPracticeInPdf: $("settingPdfLines").checked
      };
      saveSettings();
      refreshAllViews();
      fetchBible71LogsFromDb(false);
      showToast("설정이 저장되었습니다!");
    });

    $("btnConnectDriveFolder").addEventListener("click", async () => {
      if (!window.showDirectoryPicker) {
        showToast("이 브라우저는 직접 폴더 연결을 지원하지 않습니다. 위의 Apps Script 웹훅 URL을 사용하거나 JSON 백업을 내려받으세요.");
        return;
      }
      try {
        state.driveDirHandle = await window.showDirectoryPicker({ mode: "readwrite" });
        showToast(`폴더 '${state.driveDirHandle.name}' 연결 완료! 이제 자동 저장됩니다.`);
      } catch (err) {
        if (err && err.name !== "AbortError") showToast(`폴더 연결 실패: ${err.message}`);
      }
    });

    $("btnSyncFromWebhook").addEventListener("click", async () => {
      const url = $("settingWebhookUrl").value.trim();
      if (!url) {
        showToast("먼저 Google Apps Script 웹훅 URL을 입력해 주세요.");
        return;
      }
      try {
        const res = await fetch(url);
        const data = await res.json();
        if (Array.isArray(data.records)) {
          const map = new Map(state.records.map((r) => [r.id, r]));
          for (const rec of data.records) map.set(rec.id, rec);
          state.records = [...map.values()];
          saveRecords();
          refreshAllViews();
          showToast(`구글 드라이브에서 총 ${data.records.length}건의 기록을 복원했습니다!`);
        } else {
          showToast("구글 드라이브 백업 데이터를 찾을 수 없습니다.");
        }
      } catch (err) {
        showToast(`복원 오류: ${err.message}`);
      }
    });

    $("btnExportBackupJson").addEventListener("click", () => {
      const json = JSON.stringify({ schemaVersion: 1, exportedAtEpochMs: Date.now(), records: state.records }, null, 2);
      downloadBlob(new Blob([json], { type: "application/json" }), `planner_backup_${toIsoDate(new Date())}.json`);
      showToast("전체 JSON 백업 파일을 저장했습니다.");
    });

    $("btnExportStudentCsv").addEventListener("click", () => {
      const csv = buildStudentCsv(getStudentRecords());
      downloadBlob(new Blob([csv], { type: "text/csv;charset=utf-8" }), `누적학습기록_${studentName()}.csv`);
      showToast(`${studentName()} 누적 CSV 파일을 내보냈습니다.`);
    });

    $("btnImportSettingsJson").addEventListener("click", () => $("inputImportSettingsJson").click());
    $("inputImportSettingsJson").addEventListener("change", (e) => {
      const file = e.target.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        try {
          const parsed = JSON.parse(String(reader.result));
          if (applyImportedSettingsObject(parsed)) {
            showToast("설정 JSON(API 키·옵션)을 불러와 기기에 저장했습니다!");
          } else {
            showToast("유효한 설정 JSON 키를 찾지 못했습니다.");
          }
        } catch (err) {
          showToast(`설정 JSON 읽기 실패: ${err.message}`);
        }
      };
      reader.readAsText(file);
      e.target.value = "";
    });
    $("btnExportSettingsJson").addEventListener("click", () => {
      const json = JSON.stringify(state.settings, null, 2);
      downloadBlob(new Blob([json], { type: "application/json" }), "planner_settings.json");
      showToast("설정 JSON 파일(planner_settings.json)을 저장했습니다.");
    });

    $("inputRestoreBackupFile").addEventListener("change", (e) => {
      const file = e.target.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        try {
          const parsed = JSON.parse(String(reader.result));
          let msgParts = [];
          if (Array.isArray(parsed.records)) {
            const map = new Map(state.records.map((r) => [r.id, r]));
            for (const rec of parsed.records) map.set(rec.id, rec);
            state.records = [...map.values()];
            saveRecords();
            refreshAllViews();
            msgParts.push(`기록 ${parsed.records.length}건`);
          }
          if (parsed.settings || parsed.geminiApiKey) {
            if (applyImportedSettingsObject(parsed)) {
              msgParts.push("설정(API 키)");
            }
          }
          showToast(msgParts.length ? `백업에서 ${msgParts.join(" + ")} 복원 완료!` : "복원할 항목을 찾지 못했습니다.");
        } catch (err) {
          showToast(`복원 실패: ${err.message}`);
        }
      };
      reader.readAsText(file);
    });
  }

  function applyKeyFromUrlHash() {
    try {
      const hash = String(window.location?.hash || "");
      if (!hash.startsWith("#")) return false;
      const params = new URLSearchParams(hash.slice(1));
      const key = (params.get("key") || params.get("k") || "").trim();
      if (!key) return false;
      state.settings.geminiApiKey = key;
      saveSettings();
      if (window.history && typeof window.history.replaceState === "function") {
        window.history.replaceState(null, "", window.location.pathname + window.location.search);
      }
      return true;
    } catch (_) {
      return false;
    }
  }

  function init() {
    loadPersisted();
    const keyFromHash = applyKeyFromUrlHash();
    bindEvents();
    refreshAllViews();
    fetchBible71LogsFromDb(false);
    if (keyFromHash) {
      showToast("URL에서 Gemini API 키를 안전하게 저장했습니다!");
    }
  }

  window.addEventListener("DOMContentLoaded", init);
})();
