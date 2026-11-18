(() => {
  "use strict";

  const SB = window.PLANNER_SUPABASE;
  const sb = window.supabase.createClient(SB.url, SB.publishableKey);
  const $ = (id) => document.getElementById(id);
  const core = window.PlannerCore;

  const STATUS_LABEL = {
    submitted: "보냄 · 확인 대기",
    analyzing: "선생님 분석 중",
    analyzed: "선생님 확인 중",
    sent: "리포트 도착",
    failed: "다시 보내 주세요"
  };
  const HABIT_LABEL = {
    VOLUME_SPECIFIED: "교재·쪽수 쓰기",
    COLOR_TIMETABLE: "색칠 타임테이블",
    REVIEW_PAIR: "수업 뒤 복습 짝짓기",
    NEXT_DAY_RESERVE: "내일 예약",
    BIBLE: "성경읽기",
    CLASS_SUMMARY: "수업 요약"
  };

  let me = null;
  let profile = null;
  let photoBlob = null;

  function toast(msg) {
    const box = $("toastBox");
    box.textContent = msg;
    box.classList.add("show");
    clearTimeout(toast._t);
    toast._t = setTimeout(() => box.classList.remove("show"), 3200);
  }

  function esc(s) {
    return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  function todayIso() {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  }

  function mondayIso() {
    const d = new Date();
    const day = d.getDay() || 7;
    d.setDate(d.getDate() - day + 1);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  }

  function showLogin(msg = "") {
    $("loginSection").style.display = "block";
    $("appSection").style.display = "none";
    $("btnLogout").style.display = "none";
    $("stuSubtitle").textContent = "로그인해 주세요";
    $("loginMsg").textContent = msg;
  }

  async function login() {
    const id = $("loginId").value.trim();
    const pw = $("loginPw").value;
    if (!id || !pw) { $("loginMsg").textContent = "아이디와 비밀번호를 입력해 주세요."; return; }
    const email = id.includes("@") ? id : `${id}@${SB.studentEmailDomain}`;
    $("btnLogin").disabled = true;
    const { error } = await sb.auth.signInWithPassword({ email, password: pw });
    $("btnLogin").disabled = false;
    if (error) { $("loginMsg").textContent = "아이디 또는 비밀번호가 맞지 않아요."; return; }
    $("loginPw").value = "";
    await afterLogin();
  }

  async function afterLogin() {
    const { data: { user } } = await sb.auth.getUser();
    if (!user) { showLogin(); return; }
    me = user;
    const { data: prof, error } = await sb.from("profiles").select("*").eq("id", user.id).single();
    if (error || !prof) { await sb.auth.signOut(); showLogin("프로필을 찾지 못했어요. 선생님께 문의해 주세요."); return; }
    profile = prof;
    $("loginSection").style.display = "none";
    $("appSection").style.display = "flex";
    $("btnLogout").style.display = "inline-flex";
    const emoji = profile.student_type === "ELEMENTARY" ? "🌱" : "🎓";
    $("stuSubtitle").textContent = `${emoji} ${profile.display_name || "학생"} · 플래너 쓰고 사진 보내기`;
    $("inputPlanDate").value = todayIso();
    await refreshAll();
  }

  async function refreshAll() {
    await Promise.all([loadReports(), loadSubmissions(), loadProgress(), loadHabits()]);
  }

  // ---------- 제출 ----------
  function resizeToJpegBlob(file, maxDim = 1800) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => {
        let w = img.naturalWidth, h = img.naturalHeight;
        if (Math.max(w, h) > maxDim) { const r = maxDim / Math.max(w, h); w = Math.round(w * r); h = Math.round(h * r); }
        const c = document.createElement("canvas");
        c.width = w; c.height = h;
        c.getContext("2d").drawImage(img, 0, 0, w, h);
        URL.revokeObjectURL(url);
        c.toBlob((b) => (b ? resolve(b) : reject(new Error("사진 변환 실패"))), "image/jpeg", 0.85);
      };
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("이 사진 형식은 열 수 없어요. 다른 사진으로 해 주세요.")); };
      img.src = url;
    });
  }

  async function onPickPhoto(file) {
    if (!file) return;
    try {
      photoBlob = await resizeToJpegBlob(file);
      $("previewImg").src = URL.createObjectURL(photoBlob);
      $("previewBox").style.display = "block";
      $("btnSubmit").disabled = false;
    } catch (e) {
      toast(e.message);
    }
  }

  async function submitPlanner() {
    if (!photoBlob) { toast("먼저 플래너 사진을 찍어 주세요."); return; }
    const planDate = $("inputPlanDate").value || todayIso();
    const btn = $("btnSubmit");
    btn.disabled = true;
    btn.textContent = "⏳ 보내는 중...";
    try {
      const path = `${me.id}/${planDate}_${Date.now()}.jpg`;
      const up = await sb.storage.from(SB.photoBucket).upload(path, photoBlob, { contentType: "image/jpeg", upsert: false });
      if (up.error) throw up.error;
      const ins = await sb.from("submissions").insert({
        student_id: me.id,
        plan_date: planDate,
        photo_path: path,
        student_memo: $("inputMemo").value.trim()
      });
      if (ins.error) {
        await sb.storage.from(SB.photoBucket).remove([path]);
        throw ins.error;
      }
      photoBlob = null;
      $("previewBox").style.display = "none";
      $("inputMemo").value = "";
      toast("선생님께 플래너를 보냈어요! 리포트가 오면 여기에서 볼 수 있어요.");
      await loadSubmissions();
    } catch (e) {
      toast(`보내지 못했어요: ${e.message || e}`);
    } finally {
      btn.textContent = "📤 선생님께 보내기";
      btn.disabled = !photoBlob;
    }
  }

  async function loadSubmissions() {
    const { data, error } = await sb.from("submissions")
      .select("id, plan_date, status, student_memo, photo_path, created_at")
      .order("created_at", { ascending: false }).limit(20);
    const box = $("submissionList");
    if (error) { box.innerHTML = `<p class="muted">불러오지 못했어요: ${esc(error.message)}</p>`; return; }
    if (!data.length) { box.innerHTML = `<p class="muted">보낸 플래너가 없어요.</p>`; return; }
    box.innerHTML = data.map((s) => `
      <div class="list-row">
        <div class="list-main">
          <div class="list-title">${esc(s.plan_date)} 플래너</div>
          <div class="list-sub">${esc(s.student_memo || "")}</div>
        </div>
        <span class="status-pill st-${esc(s.status)}">${esc(STATUS_LABEL[s.status] || s.status)}</span>
        ${s.status === "submitted" ? `<button class="btn btn-ghost" data-cancel="${esc(s.id)}" data-path="${esc(s.photo_path || "")}">취소</button>` : ""}
      </div>`).join("");
    box.querySelectorAll("[data-cancel]").forEach((b) => b.addEventListener("click", async () => {
      if (!confirm("보낸 플래너를 취소할까요?")) return;
      const del = await sb.from("submissions").delete().eq("id", b.dataset.cancel);
      if (del.error) { toast(`취소하지 못했어요: ${del.error.message}`); return; }
      if (b.dataset.path) await sb.storage.from(SB.photoBucket).remove([b.dataset.path]);
      toast("취소했어요.");
      loadSubmissions();
    }));
  }

  // ---------- 리포트 ----------
  let reportsCache = [];

  async function loadReports() {
    const { data, error } = await sb.from("reports")
      .select("id, plan_date, result, image_path, report_mode, sent_at, student_read_at")
      .eq("status", "sent")
      .order("plan_date", { ascending: false }).limit(60);
    const box = $("reportList");
    if (error) { box.innerHTML = `<p class="muted">불러오지 못했어요: ${esc(error.message)}</p>`; return; }
    reportsCache = data || [];
    if (!reportsCache.length) { box.innerHTML = `<p class="muted">아직 받은 리포트가 없어요.</p>`; return; }
    box.innerHTML = reportsCache.map((r) => {
      const res = r.result || {};
      return `
      <div class="record-item" data-report="${esc(r.id)}">
        <div class="score-box">${esc(res.habitScore ?? "-")}점</div>
        <div class="record-meta">
          <div class="record-title">${esc(r.plan_date)} 리포트${r.student_read_at ? "" : '<span class="new-dot"></span>'}</div>
          <div class="record-sub">${esc(res.copywritingPromise || res.praiseOneLiner || "")}</div>
        </div>
      </div>`;
    }).join("");
    box.querySelectorAll("[data-report]").forEach((el) => el.addEventListener("click", () => openReport(el.dataset.report)));
  }

  function loadImage(src) {
    return new Promise((resolve) => {
      if (!src) { resolve(null); return; }
      const img = new Image();
      img.crossOrigin = "anonymous";
      img.onload = () => resolve(img);
      img.onerror = () => resolve(null);
      img.src = src;
    });
  }

  async function openReport(id) {
    const r = reportsCache.find((x) => x.id === id);
    if (!r) return;
    $("viewer").style.display = "flex";
    $("viewerTitle").textContent = `${r.plan_date} 리포트`;
    $("viewerImg").removeAttribute("src");
    $("viewerLoading").style.display = "block";
    let imgEl = null;
    if (r.image_path && r.report_mode === "image") {
      const signed = await sb.storage.from(SB.imageBucket).createSignedUrl(r.image_path, 600);
      if (!signed.error) imgEl = await loadImage(signed.data.signedUrl);
    }
    const record = {
      ...(r.result || {}),
      studentType: profile.student_type || "HIGH_SCHOOL",
      studentName: profile.display_name || "학생",
      dateIso: r.plan_date
    };
    try {
      const canvas = core.renderDailyReportCanvas(record, false, imgEl);
      $("viewerImg").src = canvas.toDataURL("image/png");
    } catch (e) {
      toast(`리포트를 그리지 못했어요: ${e.message}`);
    }
    $("viewerLoading").style.display = "none";
    if (!r.student_read_at) {
      await sb.rpc("mark_report_read", { p_report_id: r.id });
      r.student_read_at = new Date().toISOString();
      loadReports();
    }
  }

  // ---------- 진도·습관 ----------
  async function loadProgress() {
    const { data, error } = await sb.from("material_progress")
      .select("title, subject_name, unit_type, total_units, current_position, units_done, progress_pct, pace_per_day_14d, predicted_finish, target_date, last_studied_on, status")
      .order("last_studied_on", { ascending: false, nullsFirst: false });
    const box = $("progressList");
    if (error) { box.innerHTML = `<p class="muted">불러오지 못했어요: ${esc(error.message)}</p>`; return; }
    if (!data || !data.length) { box.innerHTML = `<p class="muted">리포트가 쌓이면 교재별 진도가 자동으로 기록돼요.</p>`; return; }
    box.innerHTML = data.map((m) => {
      const pct = m.progress_pct != null ? Number(m.progress_pct) : null;
      const pos = m.current_position != null ? `${Number(m.current_position)}${esc(m.unit_type)}까지` : "아직 완료 기록 없음";
      const total = m.total_units ? ` / 전체 ${Number(m.total_units)}${esc(m.unit_type)}` : "";
      const pace = Number(m.pace_per_day_14d) > 0 ? ` · 최근 하루 ${Number(m.pace_per_day_14d)}${esc(m.unit_type)}` : "";
      const finish = m.predicted_finish ? ` · 예상 완료 ${esc(m.predicted_finish)}` : "";
      const late = m.predicted_finish && m.target_date && m.predicted_finish > m.target_date ? ` <span class="status-pill st-failed">목표보다 늦음</span>` : "";
      return `
        <div class="list-row" style="display:block;">
          <div class="list-title">${esc(m.subject_name || "과목")} · ${esc(m.title)} ${pct != null ? `<span class="status-pill st-sent">${pct}%</span>` : ""}${late}</div>
          <div class="list-sub" style="white-space:normal;">${pos}${total}${pace}${finish}</div>
          ${pct != null ? `<div class="progress-bar"><div style="width:${Math.min(100, pct)}%"></div></div>` : ""}
        </div>`;
    }).join("");
  }

  async function loadHabits() {
    const { data, error } = await sb.from("habit_weekly_stats")
      .select("habit_key, checks, passed").eq("week_start", mondayIso());
    const box = $("habitList");
    if (error) { box.innerHTML = `<p class="muted">불러오지 못했어요: ${esc(error.message)}</p>`; return; }
    if (!data || !data.length) { box.innerHTML = `<p class="muted">이번 주 기록이 아직 없어요.</p>`; return; }
    box.innerHTML = data.map((h) => {
      const pct = h.checks ? Math.round((100 * h.passed) / h.checks) : 0;
      return `
        <div class="list-row" style="display:block;">
          <div class="list-title">${esc(HABIT_LABEL[h.habit_key] || h.habit_key)} · ${h.passed}/${h.checks}일</div>
          <div class="progress-bar"><div style="width:${pct}%"></div></div>
        </div>`;
    }).join("");
  }

  // ---------- 이벤트 ----------
  $("btnLogin").addEventListener("click", login);
  $("loginPw").addEventListener("keydown", (e) => { if (e.key === "Enter") login(); });
  $("btnLogout").addEventListener("click", async () => { await sb.auth.signOut(); me = null; profile = null; showLogin(); });
  $("btnCamera").addEventListener("click", () => $("inputCamera").click());
  $("btnGallery").addEventListener("click", () => $("inputGallery").click());
  $("inputCamera").addEventListener("change", (e) => onPickPhoto(e.target.files[0]));
  $("inputGallery").addEventListener("change", (e) => onPickPhoto(e.target.files[0]));
  $("btnSubmit").addEventListener("click", submitPlanner);
  $("btnRefresh").addEventListener("click", refreshAll);
  $("btnCloseViewer").addEventListener("click", () => { $("viewer").style.display = "none"; });

  (async () => {
    const { data: { session } } = await sb.auth.getSession();
    if (session) await afterLogin(); else showLogin();
  })();
})();
