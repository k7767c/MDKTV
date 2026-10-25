const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => document.querySelectorAll(selector);

// P0-1：API token。车机端在页面注入 window.__KTV_TOKEN__，所有 /api/v1/* 请求都带它
// （query ?token= 或 X-Ktv-Token 头任一即可）。旧版车机不注入 → token 为空，请求仍走老逻辑。
// let 而非 const：401 自愈时需要用车机回传的新 token 覆盖它。
let KTV_TOKEN = (window.__KTV_TOKEN__ || '').trim();
const tokenQuery = () => KTV_TOKEN ? ('&token=' + KTV_TOKEN) : '';
const apiHeaders = (extra = {}) => {
  const h = { "Content-Type": "application/json", ...extra };
  if (KTV_TOKEN) h["X-Ktv-Token"] = KTV_TOKEN;
  return h;
};

const uiState = {
  // 默认"歌曲"：老板反馈"手机搜歌搜不到、车机能搜到" —— 根因就是这里默认按歌手搜，
  // 用户输歌名直接空结果。手机端和车机端一致：默认走智能匹配（歌名+歌手+拼音）。
  page: 1, query: "", searchMode: "song", busy: false,
  hotPage: 1, hotPageSize: 10, hotBusy: false,
  visible: true, connected: false
};

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"
  }[char]));
}

function setConnected(connected) {
  uiState.connected = connected;
  $("#offline").classList.toggle("hidden", connected);
  $("#connection").innerHTML = connected
    ? "<span></span>已连接风火轮 K歌版"
    : "<span></span>正在连接风火轮 K歌版…";
}

async function api(path, options = {}) {
  // P0-1：必须带 token，否则车机一律 401（点歌/搜歌/取话筒地址全挂）。
  // token 同时走 query 与 header：header 为主，query 兜底（部分中间层会吃掉自定义头）。
  const sep = path.indexOf("?") >= 0 ? "&" : "?";
  const url = KTV_TOKEN ? path + sep + "token=" + encodeURIComponent(KTV_TOKEN) : path;
  const response = await fetch(url, {
    cache: "no-store",
    ...options,
    headers: apiHeaders(options.headers)
  });
  // 401 自愈：车机会在 401 响应里回传当前 token（页面被浏览器缓存成旧 HTML、
  // 或车机重启换了 token 时用），拿到后更新并重试一次，避免手机端直接报废。
  if (response.status === 401) {
    let body = null;
    try { body = await response.json(); } catch (_) { body = null; }
    const fresh = body && body.token;
    if (fresh && fresh !== KTV_TOKEN && !options._retried) {
      KTV_TOKEN = fresh;
      return api(path, { ...options, _retried: true });
    }
    throw new Error((body && body.error) || "登录已失效，请重新扫码");
  }
  const data = await response.json();
  if (!response.ok || data.ok === false) throw new Error(data.error || "操作失败");
  setConnected(true);
  return data;
}

function toast(text) {
  const element = $("#toast");
  element.textContent = text;
  element.classList.add("show");
  // 长文本给足阅读时间：每 40 个字加 1 秒，最短 1.6s 最长 8s
  const ms = Math.min(8000, Math.max(1600, 1600 + text.length * 25));
  window.clearTimeout(toast._t);
  toast._t = window.setTimeout(() => element.classList.remove("show"), ms);
}

/**
 * 原生弹窗：toast 只显示 1.6 秒，不够放"请点高级继续前往"这类引导。
 * 用 alert 保证用户看完，点确定后跳 https 页面。
 */
function noticeAndGo(title, text, url) {
  window.alert(text);
  window.location.href = url;
  void title;
}

function songRow(song, queue = false, current = false, index = 0, ranking = false) {
  const number = current ? "♪" : String(index + 1).padStart(2, "0");
  const actions = queue && !current
    ? '<button class="top">置顶</button><button class="delete" aria-label="删除">×</button>'
    : !queue ? '<button class="order">点歌</button>' : "";
  return `<div class="song${ranking && index < 3 ? " podium" : ""}" data-id="${escapeHtml(song.id)}">
    <div class="song-index">${number}</div>
    <div class="info">${current ? '<span class="playing">正在播放</span>' : ""}<b>${escapeHtml(song.title)}</b><p>${escapeHtml(song.singer)}${song.language ? " · " + escapeHtml(song.language) : ""}</p></div>
    ${actions}
  </div>`;
}

function openPage(name) {
  $$("nav button,.page").forEach((element) => element.classList.remove("active"));
  $(`nav button[data-page="${name}"]`)?.classList.add("active");
  $("#" + name)?.classList.add("active");
  if (name === "search" && !$("#hotList").children.length) loadHot(1);
  // 已点歌单已整合进遥控页/话筒页，切页不再单独加载；refresh() 每秒会更新
}

async function refresh() {
  try {
    const data = await api("/api/v1/state");
    const current = data.current || {};
    const count = data.queueSize || 0;
    $("#nowTitle").textContent = current.title || "暂无歌曲";
    $("#nowSinger").textContent = current.singer || "请先点一首喜欢的歌";
    $("#queueBadge b").textContent = count;
    $("#navCount").textContent = count;
    $("#navCount").classList.toggle("hidden", count === 0);
    $("#playButton span:last-child").textContent = data.playing ? "暂停" : "继续";
    $("#playButton").dataset.action = data.playing ? "pause" : "resume";
    $("#playButton .control-icon").textContent = data.playing ? "Ⅱ" : "▶";
    $("#musicVolume").value = data.musicVolume ?? 70;
    $("#micVolume").value = data.micVolume ?? 70;
    $("#musicValue").textContent = data.musicVolume ?? 70;
    $("#micValue").textContent = data.micVolume ?? 70;
    $("#original").classList.toggle("active", data.vocalMode === "original");
    $("#accompany").classList.toggle("active", data.vocalMode !== "original");
    $("#nextSong").textContent = data.next?.title || "队列为空";
  } catch (_) {
    setConnected(false);
  }
}

async function playerAction(action) {
  try {
    await api("/api/v1/player/actions", { method: "POST", body: JSON.stringify({ action }) });
    await refresh();
  } catch (error) { toast(error.message); }
}

async function updateAudio(values) {
  try {
    await api("/api/v1/player/audio", { method: "PATCH", body: JSON.stringify(values) });
    await refresh();
  } catch (error) { toast(error.message); }
}

async function search(reset = true) {
  if (uiState.busy) return;
  if (reset) {
    uiState.page = 1;
    uiState.query = $("#query").value.trim();
    if (!uiState.query) {
      toast(`请输入${uiState.searchMode === "singer" ? "歌手" : "歌曲"}名称`);
      $("#query").focus();
      return;
    }
    $("#results").innerHTML = "";
    $("#searchResultBlock").classList.remove("hidden");
    $("#resultTitle").textContent = uiState.searchMode === "singer" ? "歌手搜索结果" : "歌曲搜索结果";
    $("#resultSummary").textContent = `正在搜索“${uiState.query}”`;
  }
  uiState.busy = true;
  try {
    const data = await api(`/api/v1/songs?q=${encodeURIComponent(uiState.query)}&type=${uiState.searchMode}&page=${uiState.page}&pageSize=20`);
    let songs = data.songs || [];
    // 兜底：选了"歌手"却按歌名搜（用户最常见的情况），自动改用歌曲匹配重搜一次。
    // 车机端搜索框本身就是歌名+歌手+拼音全匹配，手机端没理由让人先判断该点哪个 tab。
    if (reset && songs.length === 0 && uiState.searchMode === "singer") {
      uiState.busy = false;
      uiState.page = 1;
      const retry = await api(`/api/v1/songs?q=${encodeURIComponent(uiState.query)}&type=song&page=1&pageSize=20`);
      songs = retry.songs || [];
      if (songs.length > 0) {
        $("#resultTitle").textContent = "歌曲搜索结果";
        $("#results").innerHTML = "";
        $("#results").insertAdjacentHTML("beforeend",
          songs.map((song, index) => songRow(song, false, false, index)).join(""));
        $("#resultSummary").textContent = `“${uiState.query}”${retry.hasMore ? "，还有更多" : ""}`;
        $("#more").classList.toggle("hidden", !retry.hasMore);
        uiState.page = 2;
        uiState.busy = true;
        return;
      }
      uiState.busy = true;
    }
    const rows = songs.map((song, index) => songRow(song, false, false, (uiState.page - 1) * 20 + index)).join("");
    if (rows) $("#results").insertAdjacentHTML("beforeend", rows);
    else if (reset) $("#results").innerHTML = '<div class="empty">没有找到相关歌曲<br><small>请更换关键词后重试</small></div>';
    $("#resultSummary").textContent = `“${uiState.query}”${data.hasMore ? "，还有更多" : ""}`;
    $("#more").classList.toggle("hidden", !data.hasMore);
    uiState.page += 1;
  } catch (error) { toast(error.message); }
  finally { uiState.busy = false; }
}

async function loadHot(page) {
  if (uiState.hotBusy || page < 1) return;
  uiState.hotBusy = true;
  $("#hotPrev").disabled = true;
  $("#hotNext").disabled = true;
  $("#hotSummary").textContent = "正在加载…";
  try {
    const data = await api(`/api/v1/hot?page=${page}&pageSize=${uiState.hotPageSize}`);
    uiState.hotPage = data.page || page;
    const songs = data.songs || [];
    $("#hotList").innerHTML = songs.map((song, index) =>
      songRow(song, false, false, (uiState.hotPage - 1) * uiState.hotPageSize + index, true)
    ).join("") || '<div class="empty compact">暂无热歌排行</div>';
    $("#hotPage").textContent = `第 ${uiState.hotPage} 页`;
    $("#hotSummary").textContent = songs.length ? `第 ${(uiState.hotPage - 1) * uiState.hotPageSize + 1}-${(uiState.hotPage - 1) * uiState.hotPageSize + songs.length} 名` : "暂无数据";
    $("#hotPrev").disabled = !data.hasPrevious;
    $("#hotNext").disabled = !data.hasMore;
    if (page > 1) $("#hot").scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (error) {
    $("#hotList").innerHTML = '<div class="empty compact">热歌排行加载失败</div>';
    $("#hotSummary").textContent = "请点击翻页重试";
    $("#hotPrev").disabled = uiState.hotPage <= 1;
    $("#hotNext").disabled = false;
    toast(error.message);
  } finally { uiState.hotBusy = false; }
}

// 已点歌单同时渲染到"遥控"页和"话筒"页（老板要求已点整合进遥控、不再单开页签）
function renderQueue(queue) {
  const html = queue.map((song, index) => songRow(song, true, index === 0, index)).join("")
    || '<div class="empty">还没有点歌<br><small>去曲库选一首喜欢的歌吧</small></div>';
  ["#queueListInline", "#queueListInline2"].forEach((sel) => {
    const el = $(sel);
    if (el) el.innerHTML = html;
  });
  const cnt = $("#queueCountInline");
  if (cnt) cnt.textContent = `${queue.length} 首已点`;
  const cnt2 = $("#queueCountInline2");
  if (cnt2) cnt2.textContent = `${queue.length} 首`;
}

async function loadQueue() {
  try {
    const data = await api("/api/v1/queue");
    renderQueue(data.queue || []);
  } catch (error) { toast(error.message); }
}

document.addEventListener("click", async (event) => {
  const button = event.target.closest("button");
  if (!button) return;
  if (button.dataset.page) openPage(button.dataset.page);
  if (button.dataset.action) playerAction(button.dataset.action);
  if (button.dataset.vocal) updateAudio({ vocalMode: button.dataset.vocal });
  if (button.dataset.searchMode) {
    uiState.searchMode = button.dataset.searchMode;
    $$('[data-search-mode]').forEach((item) => item.classList.toggle("active", item === button));
    $("#query").placeholder = uiState.searchMode === "singer" ? "输入歌手名或拼音" : "输入歌曲名或拼音";
    $("#query").value = "";
    $("#results").innerHTML = "";
    $("#searchResultBlock").classList.add("hidden");
    $("#more").classList.add("hidden");
    $("#query").focus();
  }
  const row = button.closest(".song");
  if (row && button.classList.contains("order")) {
    try {
      await api("/api/v1/queue", { method: "POST", body: JSON.stringify({ songId: row.dataset.id }) });
      button.textContent = "已点"; button.disabled = true; toast("已加入歌单"); refresh();
    } catch (error) { toast(error.message); }
  }
  if (row && button.classList.contains("top")) {
    try {
      await api("/api/v1/queue/" + encodeURIComponent(row.dataset.id), { method: "PATCH", body: "{}" });
      toast("已置顶为下一首"); loadQueue();
    } catch (error) { toast(error.message); }
  }
  if (row && button.classList.contains("delete")) {
    try {
      await api("/api/v1/queue/" + encodeURIComponent(row.dataset.id), { method: "DELETE" });
      toast("已从歌单移除"); loadQueue(); refresh();
    } catch (error) { toast(error.message); }
  }
});

$("#searchForm").addEventListener("submit", (event) => { event.preventDefault(); search(); });
$("#more").addEventListener("click", () => search(false));
$("#hotPrev").addEventListener("click", () => loadHot(uiState.hotPage - 1));
$("#hotNext").addEventListener("click", () => loadHot(uiState.hotPage + 1));
["music", "mic"].forEach((kind) => {
  $("#" + kind + "Volume").addEventListener("input", (event) => { $("#" + kind + "Value").textContent = event.target.value; });
  $("#" + kind + "Volume").addEventListener("change", (event) => updateAudio({ [kind + "Volume"]: Number(event.target.value) }));
});
document.addEventListener("visibilitychange", () => { uiState.visible = !document.hidden; if (uiState.visible) refresh(); });
window.setInterval(() => { if (uiState.visible) refresh(); }, 1000);
// 已点歌单已并入遥控页/话筒页，需要常驻可见 —— 2 秒拉一次（每秒全量拉太费手机流量）
window.setInterval(() => { if (uiState.visible) loadQueue(); }, 2000);
refresh();
loadHot(1);

/* ============================================================
   手机当话筒（与点歌同一个页面，扫一次码两样都能用）
   音频走后端 MicStreamServer：点歌服务 8765-8775，话筒服务 8780-8799（区间不重叠），
   两个端口不同，所以话筒地址必须由车机提供。获取顺序：
     1) 运行时调 /api/v1/mic 拿（最可靠，能自愈）
     2) 页面注入的 window.__MIC_BASE__ 兜底（老页面缓存时仍可用）
   不用注入单条路的原因：注入是页面加载那一刻的快照，车机若那一刻还没拿到
   局域网 IP 就是空串；且微信/浏览器会缓存 HTML，车机后来起好服务手机也读不到。
   ============================================================ */
(function () {
  const MIC_RATE = 16000, MIC_FRAME_MS = 40;
  let micBase = (window.__MIC_BASE__ || '');
  let micReady = !!micBase;
  /** 车机给的 https 话筒地址；当前页面是 http 时用它引导用户跳过去。 */
  let micHttpsBase = '';
  // v1.2.13：CLIENT_ID 改为持久化（localStorage 同手机同域固定一个 id），刷新/切后台回来
  // 不再重建车机端 AudioTrack 冷启动 —— 直接消除"一下有一下没有"的断流主因。
  function getStableId() {
    try {
      let id = localStorage.getItem('ktv_mic_id');
      if (!id) {
        id = 'mic-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
        localStorage.setItem('ktv_mic_id', id);
      }
      return id;
    } catch (e) {
      return 'mic-' + Math.random().toString(36).slice(2, 10);
    }
  }
  const CLIENT_ID = getStableId();

  let ctx = null, src = null, proc = null, stream = null, gainNode = null;
  let on = false, pending = new Int16Array(0), sent = 0, lastAt = 0, lastBytes = 0;
  // v1.2.13：断流补帧用静音帧 + 最后推送时间
  const NEED = MIC_RATE * MIC_FRAME_MS / 1000;
  const silenceFrame = new Int16Array(NEED);
  let lastPushAt = 0;

  const $ = id => document.getElementById(id);

  function setState(txt, cls) {
    const el = $('micState');
    if (el) el.className = cls || '', el.textContent = txt;
  }

  // 页面初始是"待检测"而不是直接判定失败 —— 运行时拿到地址后才启用按钮
  setState('检测话筒服务…');

  async function resolveMicBase() {
    try {
      const r = await api('/api/v1/mic');
      if (r && r.available && r.base) {
        // 车机优先给 https 地址：只有安全上下文浏览器才提供麦克风接口。
        // 若当前页面是 http 而话筒服务是 https，浏览器会按混合内容拦截 fetch，
        // 所以这种情况必须提示用户改用 https 页面打开。
        const base = String(r.base);
        micBase = base;
        micReady = true;
        const httpsBase = base.indexOf('https://') === 0;
        const pageIsHttps = location.protocol === 'https:';
        if (httpsBase && !pageIsHttps) {
          // 记下 https 地址，让用户重定向过去
          micHttpsBase = base;
          setState('需用安全连接打开', 'bad');
          const btn = $('micToggle');
          if (btn) { btn.disabled = false; btn.style.opacity = ''; }
          return true;
        }
        if (!httpsBase && pageIsHttps) {
          // https 页面不能请求 http 明文接口（混合内容），换回 https 地址
          setState('话筒地址不匹配', 'bad');
          return false;
        }
        setState('待开启', '');
        const btn = $('micToggle');
        if (btn) { btn.disabled = false; btn.style.opacity = ''; }
        return true;
      }
      setState('车机话筒服务未就绪' + (r && r.error ? '（' + r.error + '）' : ''), 'bad');
    } catch (_) {
      // 车机老版本没有这个接口：若页面注入了地址仍可用
      if (micBase) { micReady = true; setState('待开启', ''); return true; }
      setState('取不到话筒服务地址', 'bad');
    }
    return false;
  }

  async function start() {
    if (on) return;
    if (!micReady && !(await resolveMicBase())) return toast('话筒服务未就绪');
    if (!micBase) return toast('话筒服务未就绪');
    // ⚠️ 浏览器只在"安全上下文"提供麦克风接口（https / localhost / 127.0.0.1），
    // 内网私网 IP 的 http 下 navigator.mediaDevices 整个是 undefined，
    // 直接调 getUserMedia 会抛 "reading 'getUserMedia'"，用户只看到天书。
    // 车机已经起了自签证书的 HTTPS 服务（8890+），这里引导过去而不是报错。
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      if (micHttpsBase) {
        setState('需在安全连接下开麦', 'bad');
        noticeAndGo(
          '需要安全连接',
          '浏览器只在 https 安全连接下允许使用麦克风。\n\n' +
          '点"确定"跳转到车机的 https 页面。\n' +
          '首次打开会提示"证书不安全"，点"高级 → 继续前往"即可，之后就能正常开麦。',
          micHttpsBase + '/mic'
        );
        return;
      }
      setState('浏览器禁止使用麦克风', 'bad');
      toast('当前浏览器不提供麦克风接口。\n\n' +
        '原因：用 http 打开局域网地址属于不安全连接，浏览器不给麦克风权限' +
        '（安卓和 iPhone 都一样，不是手机的问题）。');
      return;
    }
    try {
      setState('请求麦克风权限…');
      stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,     // 关键：抵消车机喇叭漏回来的伴奏，防啸叫
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1
        }
      });
      ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: MIC_RATE });
      if (ctx.state === 'suspended') await ctx.resume();
      src = ctx.createMediaStreamSource(stream);
      gainNode = ctx.createGain();
      gainNode.gain.value = 1.0;
      src.connect(gainNode);
      // 延迟优化：采集缓冲从 4096 降到 1024（256ms → 64ms，砍掉近 200ms 单向延迟）。
      // 仍用 ScriptProcessor 而非 AudioWorklet：微信 X5 内核对 AudioWorklet 支持不稳，
      // 而 1024 已是 ScriptProcessor 在弱机上兼顾 CPU 与延迟的合理下限（再小回调过于频繁）。
      proc = ctx.createScriptProcessor(1024, 1, 1);
      gainNode.connect(proc);
      // ScriptProcessor 必须连到 destination 才会跑，但目的地要静音（不然自己听到自己）
      const mute = ctx.createGain(); mute.gain.value = 0;
      proc.connect(mute); mute.connect(ctx.destination);

      proc.onaudioprocess = e => {
        if (!on) return;
        const input = e.inputBuffer.getChannelData(0);
        let peak = 0;
        const out = new Int16Array(input.length);
        for (let i = 0; i < input.length; i++) {
          let v = input[i];
          if (v > peak) peak = v;
          out[i] = v < 0 ? v * 0x8000 : v * 0x7FFF;
        }
        levelBar(peak);
        enqueue(out);
      };

      on = true; sent = 0; lastAt = 0; lastBytes = 0; pending = new Int16Array(0);
      $('micToggle').classList.add('on');
      $('micToggleText').textContent = '关闭话筒';
      setState('传输中', 'ok');
      report('on');
      window.__micTimer = setInterval(rateTick, 1000);
      // v1.2.13：心跳保活 —— 每 2s 报一次 status，断流回来自动续推
      startHeartbeat();
    } catch (err) {
      setState('麦克风打不开', 'bad');
      toast('无法使用麦克风：' + (err && err.message ? err.message : err)
        + '\n\n若提示权限被拒绝，请到浏览器设置里允许本页面使用麦克风。');
    }
  }

  function stop() {
    on = false;
    clearInterval(window.__micTimer);
    clearInterval(window.__micHb);   // v1.2.13：停心跳
    try { if (proc) { proc.onaudioprocess = null; proc.disconnect(); } } catch (e) {}
    try { if (src) src.disconnect(); } catch (e) {}
    try { if (stream) stream.getTracks().forEach(t => t.stop()); } catch (e) {}
    try { if (ctx) ctx.close(); } catch (e) {}
    ctx = src = proc = stream = gainNode = null;
    pending = new Int16Array(0); sent = 0;
    const btn = $('micToggle');
    if (btn) { btn.classList.remove('on'); $('micToggleText').textContent = '开启话筒'; }
    levelBar(0);
    setState('已关闭');
    $('micRate').textContent = '—'; $('micHowl').textContent = '正常';
    report('off');
  }

  const need = MIC_RATE * MIC_FRAME_MS / 1000;   // 640 采样 = 40ms
  function enqueue(chunk) {
    const merged = new Int16Array(pending.length + chunk.length);
    merged.set(pending); merged.set(chunk, pending.length);
    let off = 0;
    while (merged.length - off >= need) {
      const frame = merged.slice(off, off + need);
      off += need;
      push(frame);
    }
    pending = merged.slice(off);
  }

  function push(pcm) {
    sent += pcm.length;
    lastPushAt = Date.now();
    // v1.2.13：断流补帧 —— 若距上次推送已超 3 帧（120ms），先补发静音帧对齐时间轴，
    // 再发真实数据。弱网丢包时这能避免 AudioTrack 抠出硬缺口爆音，比"等下一包"稳。
    const gap = Date.now() - lastPushAt;
    if (lastPushAt && gap > 120) {
      const padCount = Math.min(Math.floor(gap / MIC_FRAME_MS), 5);
      for (let k = 0; k < padCount; k++) pushSilence();
    }
    fetch(micBase + '/mic/push?id=' + CLIENT_ID, { method: 'POST', body: pcm.buffer })
      .then(r => r.json())
      .then(j => {
        if (j && j.ok === false) {
          // 车机话筒位已满/拒绝本连接：停推并明示，别再无声占带宽
          stop();
          setState(j.error || '车机已拒绝话筒连接', 'bad');
          return;
        }
        if (j && typeof j.gain === 'number') {
          if (gainNode) gainNode.gain.setTargetAtTime(j.gain, ctx.currentTime, 0.05);
          const h = $('micHowl');
          if (j.gain < 1) { h.textContent = '啸叫保护已压低'; h.style.color = '#f59e0b'; }
          else { h.textContent = '正常'; h.style.color = ''; }
        }
      })
      .catch(() => { if (on) setState('传输中断（检查是否还在同一网络）', 'bad'); });
  }

  /** v1.2.13：补发一帧静音（对齐时间轴，弱网丢包时防硬缺口爆音）。 */
  function pushSilence() {
    if (!on) return;
    fetch(micBase + '/mic/push?id=' + CLIENT_ID, { method: 'POST', body: silenceFrame.buffer })
      .catch(() => {});
  }

  /** v1.2.13：心跳保活 —— 每 2s 报一次 status，断流回来自动续推（切后台/锁屏恢复）。 */
  function startHeartbeat() {
    clearInterval(window.__micHb);
    window.__micHb = setInterval(() => {
      if (!on) return;
      report('alive');   // 保持车机端 lastSeen 更新，不触发 12s 宽限
    }, 2000);
  }

  function levelBar(peak) {
    const bar = $('micLevelBar'), txt = $('micLevel');
    if (bar) bar.style.width = Math.min(100, (peak * 100).toFixed(0)) + '%';
    if (txt) txt.textContent = (peak * 100).toFixed(0) + '%';
  }

  function rateTick() {
    const now = Date.now(), bytes = sent * 2;
    if (lastAt > 0) {
      const kbps = ((bytes - lastBytes) / 1024) / ((now - lastAt) / 1000);
      $('micRate').textContent = kbps.toFixed(0) + ' KB/s';
    }
    lastAt = now; lastBytes = bytes;
  }

  function report(state) {
    if (!micBase) return;
    // v1.2.13：/mic/status（非 probe）是受保护路径，必须带 token，否则车机 401 吞掉状态上报
    // （心跳 report('alive') 不带 token 就等于没做）。token 走 query + header 双保险。
    const sep = micBase.indexOf('?') >= 0 ? '&' : '?';
    const url = micBase + '/mic/status?id=' + CLIENT_ID + '&state=' + state +
      (KTV_TOKEN ? (sep + 'token=' + encodeURIComponent(KTV_TOKEN)) : '');
    fetch(url, {
      method: 'POST',
      headers: KTV_TOKEN ? { 'X-Ktv-Token': KTV_TOKEN } : {}
    }).catch(() => {});
  }

  // 人声音量：走点歌服务已有的 /api/v1/player/audio（micVolume），车机统一控制
  const vol = $('phoneMicVolume');
  if (vol) {
    vol.addEventListener('input', () => {
      $('phoneMicValue').textContent = vol.value;
    });
    vol.addEventListener('change', () => {
      api('/api/v1/player/audio', {
        method: 'PATCH',
        body: JSON.stringify({ micVolume: parseInt(vol.value, 10) })
      }).catch(() => {});
    });
  }

  const toggle = $('micToggle');
  if (toggle) toggle.addEventListener('click', () => { on ? stop() : start(); });

  // 页面加载即取一次话筒地址（幂等）；顺带 15 秒复查一次，
  // 车机起服务稍慢时用户不用手动刷新页面
  resolveMicBase();
  window.setInterval(() => { if (!on && !micReady) resolveMicBase(); }, 15000);

  // 离开话筒页不自动停（车机端 3 秒无包会自动释放 AudioTrack），
  // 但页面切走时降采样显示，避免后台跑无意义 UI 刷新
  window.addEventListener('pagehide', () => { if (on) report('hide'); });
})();
