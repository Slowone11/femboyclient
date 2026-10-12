// Femboy Client website: the page itself (account bar, tab switching) and the Coins tab.
// The other tabs are their own files, loaded after this one (see the end of index.html). Needs core.js first.

// ================================================================ page

// ---------------------------------------------------------------- state
let players = [];          // every linked account
let playerUuid = null;     // the account being shown; null means every account together (the default)
let started = false;       // has the first choice (all accounts) been made yet?
let rawRows = [];          // the rows as the website sent them; with every account together, `rows` is these summed up
let sessions = [];         // that account's sessions, newest first
let sessionId = null;      // the session being shown (null while merged)
let merged = false;        // true while "All sessions" is selected
let truncated = false;     // true if the server had more rows than it sent
let rows = [];             // every row being plotted
let stream = null;         // EventSource for live rows
let chart = null;
let view = "total";        // "total" or "delta"

// Each async load takes a ticket, so a slow answer for an account or session
// you have already switched away from cannot overwrite the current one.
let playersTicket = 0, sessionsTicket = 0, rowsTicket = 0;

const ALL = "__all__";          // the dropdown value for "All sessions"
const MAX_MESSAGES = 300;      // newest chat lines shown in the side panel

// --------------------------------------------------------------- helpers

// 1234567 -> "1.23M", so the axis and tiles stay readable.
function short(n) {
  const sign = n < 0 ? "-" : "";
  const a = Math.abs(n);
  if (a >= 1e9) return sign + (a / 1e9).toFixed(2) + "B";
  if (a >= 1e6) return sign + (a / 1e6).toFixed(2) + "M";
  if (a >= 1e3) return sign + (a / 1e3).toFixed(1) + "k";
  return sign + a.toFixed(0);
}
function signed(n) { return (n > 0 ? "+" : "") + short(n); }
function cls(n) { return n > 0 ? "gain" : (n < 0 ? "loss" : "zero"); }
function clock(ms) { return new Date(ms).toLocaleTimeString(); }

// Merged graphs can span days, so their timestamps include the date.
function stamp(ms) {
  if (!merged) return clock(ms);
  return new Date(ms).toLocaleString(undefined,
    { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

// "3h ago", "12m ago", "just now"
function ago(ms) {
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return Math.floor(s / 60) + "m ago";
  if (s < 86400) return Math.floor(s / 3600) + "h ago";
  return Math.floor(s / 86400) + "d ago";
}

// Break a long message into lines for a tooltip, splitting very long words too.
function wrap(text, width) {
  const lines = [];
  let line = "";
  for (let word of String(text).split(/\s+/)) {
    while (word.length > width) {
      if (line) { lines.push(line); line = ""; }
      lines.push(word.slice(0, width));
      word = word.slice(width);
    }
    if (!word) continue;
    if (line && (line + " " + word).length > width) { lines.push(line); line = word; }
    else line = line ? line + " " + word : word;
  }
  if (line) lines.push(line);
  return lines;
}

async function getJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(url + " -> HTTP " + res.status);
  return res.json();
}

function remember(key, value) { try { localStorage.setItem(key, value); } catch (e) { /* private mode */ } }
function recall(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }

// ---------------------------------------------------------------- accounts

function statusOf(p) {
  if (p.online && p.in_skyblock) return { dot: "on",   text: "In SkyBlock" };
  if (p.online)                  return { dot: "idle", text: "Online, not in SkyBlock" };
  return { dot: "", text: "Offline, seen " + ago(p.last_seen_ms) };
}

function drawAccounts() {
  const box = el("accounts");
  drawRemote();
  if (!players.length) {
    box.innerHTML = '<div class="no-accounts">No accounts linked yet. Start the game with the mod once and it links itself.</div>';
    return;
  }

  // "All accounts" first: every tab then shows every account together. Each card after it shows one account.
  const online = players.filter(p => p.online).length;
  const total = players.reduce((n, p) => n + (p.coins || 0), 0);
  const all = '<button class="acct all' + (playerUuid ? "" : " sel") + '" data-uuid="">' +
                '<span class="dot' + (online ? " on" : "") + '"></span>' +
                '<span class="nm">All accounts</span>' +
                '<span class="purse"><small>Purses</small>' + esc(short(total)) + '</span>' +
                '<span class="st">' + online + " of " + players.length + ' online</span>' +
              '</button>';

  box.innerHTML = all + players.map(p => {
    const st = statusOf(p);
    const purse = p.coins === null ? "—" : short(p.coins);
    return '<button class="acct' + (p.player_uuid === playerUuid ? " sel" : "") +
           '" data-uuid="' + esc(p.player_uuid) + '">' +
             '<span class="dot ' + st.dot + '"></span>' +
             '<span class="nm">' + esc(p.player_name) + '</span>' +
             '<span class="purse"><small>Purse</small>' + esc(purse) + '</span>' +
             '<span class="st">' + esc(st.text) + '</span>' +
           '</button>';
  }).join("");
}

// -------------------------------------------------------- remote control
//
// With one account chosen you can make its game say something in chat or quit. The website only takes these with the
// admin token (config.json, "admin_token"). It is asked for the first time and kept in this browser; a wrong one is forgotten.

const TOKEN_KEY = "femboyclient.adminToken";
let remoteBusy = false;
const remoteHere = !!el("remote");     // the game controls are only on the page for the PC the website runs on

function forgetToken() {
  try { localStorage.removeItem(TOKEN_KEY); } catch (e) { /* private mode */ }
}

// Locked (no token in this browser): the token box. Unlocked: the message box and Quit. Offline: shown but greyed out.
function drawRemote() {
  if (!remoteHere) return;
  const chosen = players.find(p => p.player_uuid === playerUuid);
  el("remote").hidden = !chosen;
  if (!chosen) return;
  const unlocked = !!recall(TOKEN_KEY);
  el("remoteState").textContent = chosen.online
    ? chosen.player_name + "'s game is running"
    : chosen.player_name + " is offline. These work while the game is running.";
  el("remoteUnlock").hidden = unlocked;
  el("remoteActions").hidden = !unlocked;
  el("remoteLock").hidden = !unlocked;
  el("remoteMsg").disabled = remoteBusy || !chosen.online;
  el("remoteSend").disabled = remoteBusy || !chosen.online;
  el("remoteQuit").disabled = remoteBusy || !chosen.online;
}

function remoteSay(text, kind) {
  const out = el("remoteOut");
  if (!out) return;
  out.hidden = !text;
  out.textContent = text;
  out.className = "out" + (kind ? " " + kind : "");
}

async function adminFetch(url, options) {
  const token = recall(TOKEN_KEY);
  if (!token) throw new Error("unlock the controls first");
  const res = await fetch(url, { ...options, headers: { ...(options && options.headers), "X-Admin-Token": token } });
  if (res.status === 401) {
    forgetToken();
    drawRemote();
    throw new Error("that admin token is wrong, enter it again");
  }
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(typeof body.detail === "string" ? body.detail : "HTTP " + res.status);
  return body;
}

// Waits for the game to answer, for up to a minute and a half: the website fails a command that is not picked up in time.
async function followCommand(account, id) {
  for (let i = 0; i < 45; i++) {
    await new Promise(r => setTimeout(r, 2000));
    let list;
    try { list = await adminFetch("/api/accounts/" + encodeURIComponent(account) + "/commands?limit=10"); }
    catch (e) { continue; }
    const cmd = list.commands.find(c => c.id === id);
    if (cmd && cmd.status !== "PENDING") return cmd;
  }
  return null;
}

async function sendRemote(type, payload) {
  const chosen = players.find(p => p.player_uuid === playerUuid);
  if (!chosen || remoteBusy) return;
  remoteBusy = true;
  drawRemote();
  const what = type === "QUIT" ? "Quit" : "Message";
  remoteSay("Sending to " + chosen.player_name + "…");
  try {
    const made = await adminFetch("/api/accounts/" + encodeURIComponent(chosen.player_uuid) + "/command", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(type === "CHAT" ? { type, payload } : { type }),
    });
    if (type === "CHAT") el("remoteMsg").value = "";
    remoteSay("Waiting for " + chosen.player_name + "'s game…");
    const done = await followCommand(chosen.player_uuid, made.id);
    if (!done) remoteSay(what + ": no answer from the game yet.", "bad");
    else if (done.status === "DONE") remoteSay(type === "QUIT" ? chosen.player_name + "'s game is quitting." : "Sent: " + payload, "ok");
    else remoteSay(what + " failed: " + (done.reason || "unknown reason"), "bad");
  } catch (e) {
    remoteSay(what + " not sent: " + e.message, "bad");
  } finally {
    remoteBusy = false;
    drawRemote();
  }
}

// The token is checked against the website (a harmless read of the command list) before it is saved.
if (remoteHere) {
el("remoteUnlock").addEventListener("submit", async (event) => {
  event.preventDefault();
  const token = el("remoteTokenInput").value.trim();
  if (!token) { remoteSay("Paste the admin token first.", "bad"); return; }
  remoteSay("Checking the token…");
  try {
    const res = await fetch("/api/accounts/" + encodeURIComponent(playerUuid) + "/commands?limit=1",
                            { headers: { "X-Admin-Token": token } });
    if (res.status === 401) { remoteSay("That admin token is wrong.", "bad"); return; }
    if (!res.ok) { remoteSay("Could not check the token (HTTP " + res.status + ").", "bad"); return; }
  } catch (e) {
    remoteSay("Could not reach the website to check the token.", "bad");
    return;
  }
  remember(TOKEN_KEY, token);
  el("remoteTokenInput").value = "";
  remoteSay("Unlocked.", "ok");
  drawRemote();
});
el("remoteActions").addEventListener("submit", (event) => {
  event.preventDefault();
  const text = el("remoteMsg").value.trim();
  if (!text) { remoteSay("Type a message first.", "bad"); return; }
  if (text.startsWith("/")) { remoteSay("Only chat messages can be sent, not /commands.", "bad"); return; }
  sendRemote("CHAT", text);
});
el("remoteQuit").addEventListener("click", () => {
  const chosen = players.find(p => p.player_uuid === playerUuid);
  if (chosen && window.confirm("Quit " + chosen.player_name + "'s game? It closes Minecraft on that PC.")) sendRemote("QUIT");
});
el("remoteLock").addEventListener("click", () => {
  forgetToken();
  remoteSay("Locked. The admin token is no longer saved in this browser.");
  drawRemote();
});
}

el("accounts").addEventListener("click", (event) => {
  const card = event.target.closest(".acct");
  if (!card) return;
  const uuid = card.dataset.uuid || null;
  selectPlayer(uuid === playerUuid ? null : uuid);       // the chosen card again = back to all accounts
});

async function refreshPlayers() {
  const ticket = ++playersTicket;
  try {
    const list = await getJson("/players");
    if (ticket !== playersTicket) return;
    players = list;
  } catch (e) {
    return;   // keep showing the last known list
  }

  // The first time, and if the chosen account has gone, show every account together.
  if (!started || (playerUuid && !players.some(p => p.player_uuid === playerUuid))) {
    started = true;
    await selectPlayer(null);
    return;
  }
  drawAccounts();
  document.dispatchEvent(new CustomEvent("accounts-updated"));
}

async function selectPlayer(uuid) {
  if ((uuid || null) !== playerUuid && !remoteBusy) remoteSay("");     // an old result belongs to the account it was for
  playerUuid = uuid || null;
  drawAccounts();

  // Tell the other tabs, which follow the same choice (an account, or every account together).
  document.dispatchEvent(new CustomEvent("account-selected", { detail: window.currentAccount() }));

  merged = !playerUuid;    // every account together is one merged view; a single account starts on its newest session
  sessionId = null;
  sessions = [];
  await refreshSessions(true);
}

// The account being shown, for the Lobby Networth tab.
window.currentAccount = () => {
  const p = players.find(x => x.player_uuid === playerUuid);
  if (p) return { uuid: p.player_uuid, name: p.player_name, online: p.online, scanner: p.scanner || "", all: false };
  return { uuid: null, name: "All accounts", online: players.some(x => x.online), scanner: "", all: true };
};

// Every linked account, for tabs that need to pick one themselves (the Player Finder).
window.accounts = () => players.map(p => ({ uuid: p.player_uuid, name: p.player_name, online: p.online, in_skyblock: p.in_skyblock }));

// ---------------------------------------------------------------- sessions

async function refreshSessions(force) {
  const ticket = ++sessionsTicket;

  let list;
  try {
    list = await getJson("/sessions" + (playerUuid ? "?player_uuid=" + encodeURIComponent(playerUuid) : ""));
  } catch (e) {
    return;
  }
  if (ticket !== sessionsTicket) return;

  // If you were looking at the newest session, keep following the newest one.
  const following = !merged && (!sessionId || sessionId === (sessions[0] && sessions[0].session_id));
  sessions = list;

  const select = el("session");
  if (!sessions.length) {
    select.innerHTML = "<option>no sessions yet</option>";
    el("sessionField").hidden = !playerUuid;
    sessionId = null;
    closeStream();
    rawRows = [];
    rows = [];
    truncated = false;
    redraw();
    return;
  }

  // "All sessions" first, then each session, newest first.
  const totalRows = sessions.reduce((n, s) => n + s.row_count, 0);
  const totalNet = sessions.reduce((n, s) => n + s.net, 0);
  const allLabel = [playerUuid ? "All sessions" : "All accounts",
                    sessions.length + (sessions.length === 1 ? " session" : " sessions"),
                    totalRows + " rows", "net " + signed(totalNet)].join("   ·   ");

  el("sessionField").hidden = !playerUuid;      // every account together is one merged view, so no session to pick
  const optionsHtml = '<option value="' + ALL + '">' + esc(allLabel) + "</option>" +
    (!playerUuid ? [] : sessions).map(s => {
      const d = new Date(s.started_ms);
      const when = d.toLocaleDateString(undefined, { month: "short", day: "numeric" }) + " " +
                   d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
      const label = [when, s.row_count + (s.row_count === 1 ? " row" : " rows"), "net " + signed(s.net)].join("   ·   ");
      return '<option value="' + esc(s.session_id) + '">' + esc(label) + "</option>";
    }).join("");
  if (select.dataset.html !== optionsHtml) {     // rebuilding an unchanged list would close an open dropdown
    select.innerHTML = optionsHtml;
    select.dataset.html = optionsHtml;
  }

  if (merged) {
    // New rows arrive through the live stream, so only the label needed updating.
    select.value = ALL;
    if (force) await loadRows();
    return;
  }

  const exists = sessions.some(s => s.session_id === sessionId);
  const next = (!exists || following) ? sessions[0].session_id : sessionId;
  select.value = next;

  if (next !== sessionId || force) {
    sessionId = next;
    await loadRows();
  }
}

// True when there is something to load: an account when merged, else a session.
function haveTarget() { return merged || !!sessionId; }

// Every account together: the purse is the sum of every account's latest purse at each moment.
function combineAccounts(list) {
  const latest = new Map();
  let total = 0;
  return list.map(r => {
    total += r.coins - (latest.get(r.player_uuid) || 0);
    latest.set(r.player_uuid, r.coins);
    return Object.assign({}, r, { coins: total, session_id: "all" });
  });
}

// The rows to plot: as sent for one account, summed up for every account together.
function setRows(list) {
  rawRows = list;
  rows = playerUuid ? list : combineAccounts(list);
}

async function loadRows() {
  const ticket = ++rowsTicket;
  if (!haveTarget()) { setRows([]); truncated = false; redraw(); return; }

  let loaded = [], wasTruncated = false;
  try {
    if (merged) {
      const data = await getJson("/rows" + (playerUuid ? "?player_uuid=" + encodeURIComponent(playerUuid) : ""));
      loaded = data.rows;
      wasTruncated = data.truncated;
    } else {
      loaded = await getJson("/sessions/" + encodeURIComponent(sessionId));
    }
  } catch (e) {
    loaded = [];
  }
  if (ticket !== rowsTicket) return;

  setRows(loaded);
  truncated = wasTruncated;
  redraw();
  startStream();
}

el("session").addEventListener("change", (e) => {
  merged = e.target.value === ALL;
  sessionId = merged ? null : e.target.value;
  loadRows();
});

// ---------------------------------------------------------------- live feed

function closeStream() {
  if (stream) { stream.close(); stream = null; }
}

function setLive(state, text) {
  el("dot").className = "dot " + state;
  el("liveTxt").textContent = text;
}

// What the stream is watching, so a late row for something you have since
// switched away from is ignored.
function streamKey() { return merged ? "account:" + playerUuid : "session:" + sessionId; }

function startStream() {
  closeStream();
  if (!haveTarget()) return;

  const watching = streamKey();
  const filter = merged ? (playerUuid ? "player_uuid=" + encodeURIComponent(playerUuid) : "")
                        : "session_id=" + encodeURIComponent(sessionId);

  // Start after the newest row already loaded, so a row that arrived while the
  // page was loading is not missed.
  const lastId = rows.reduce((max, r) => Math.max(max, r.id), 0);
  stream = new EventSource("/live?" + filter + "&since=" + lastId);

  stream.onopen = () => setLive("on", "live");
  stream.onerror = () => setLive("", "reconnecting…");
  stream.onmessage = (event) => {
    if (watching !== streamKey()) return;
    const row = JSON.parse(event.data);
    if (rows.some(r => r.id === row.id)) return;   // never double count
    const list = rawRows.slice();
    list.push(row);
    list.sort((a, b) => a.ts_ms - b.ts_ms || a.id - b.id);
    setRows(list);
    redraw();
  };
}

// ---------------------------------------------------------------- drawing

function redraw() {
  drawMergeNote();
  drawChart();
  drawStats();
  drawBreakdown();
  drawChat();
}

function drawMergeNote() {
  const note = el("mergeNote");
  note.hidden = !merged;
  if (!merged) return;
  note.textContent = (playerUuid ? "All sessions joined. A dashed line crosses the time between two sessions."
                                  : "All accounts: the line is the sum of every account's purse. Pick one account to choose a session.") +
                     (truncated ? " Only the newest rows are shown." : "");
}

// Bar sizes for the "per period" view. The smallest one that keeps the graph to
// about 120 bars or fewer is used, so a 20 minute session gets minute bars and a
// week of play gets bars per day.
const BUCKETS = [
  [60e3, "minute"], [5 * 60e3, "5 minutes"], [15 * 60e3, "15 minutes"],
  [3600e3, "hour"], [6 * 3600e3, "6 hours"], [86400e3, "day"]
];

function bucketSize() {
  const span = rows.length ? rows[rows.length - 1].ts_ms - rows[0].ts_ms : 0;
  return BUCKETS.find(([ms]) => span / ms <= 120) || BUCKETS[BUCKETS.length - 1];
}

// Start of the bucket a time falls in. Buckets of an hour or more line up with
// your local clock, so a "day" bar is a calendar day where you are.
function bucketStart(ts, ms) {
  if (ms < 3600e3) return Math.floor(ts / ms) * ms;
  const d = new Date(ts);
  const hours = ms / 3600e3;
  d.setMinutes(0, 0, 0);
  d.setHours(Math.floor(d.getHours() / hours) * hours);
  return d.getTime();
}

// Sum the changes inside each bucket.
function perPeriod() {
  const [ms] = bucketSize();
  const buckets = new Map();
  for (const r of rows) {
    if (r.change === 0) continue;
    const start = bucketStart(r.ts_ms, ms);
    buckets.set(start, (buckets.get(start) || 0) + r.change);
  }
  return [...buckets.entries()].sort((a, b) => a[0] - b[0]).map(([x, y]) => ({ x, y }));
}

function drawChart() {
  if (chart) { chart.destroy(); chart = null; }
  el("chartEmpty").hidden = rows.length > 0;
  el("chartTitle").textContent = view === "total" ? "Purse over time" : "Gain or loss per " + bucketSize()[1];
  if (!rows.length) return;

  let config;

  if (view === "total") {
    // Green dot for a gain, red for a loss, grey for the session start.
    const colors = rows.map(r => r.change > 0 ? "#46c46a" : r.change < 0 ? "#e05a5a" : "#8b93a5");
    config = {
      type: "line",
      data: { datasets: [{
        label: "Coins",
        data: rows.map(r => ({ x: r.ts_ms, y: r.coins })),
        borderColor: "#ff4fa6", borderWidth: 2, tension: merged ? 0 : 0.15,
        // Where one session ends and the next begins, the line is dashed.
        segment: { borderDash: (ctx) =>
          rows[ctx.p0DataIndex].session_id !== rows[ctx.p1DataIndex].session_id ? [5, 5] : undefined },
        pointBackgroundColor: colors, pointBorderColor: colors,
        pointRadius: rows.map(r => r.change === 0 ? 3 : 5), pointHoverRadius: 8
      }] }
    };
  } else {
    const points = perPeriod();
    config = {
      type: "bar",
      data: { datasets: [{
        label: "Change", data: points,
        backgroundColor: points.map(p => p.y >= 0 ? "#46c46a" : "#e05a5a")
      }] }
    };
  }

  config.options = {
    responsive: true, maintainAspectRatio: false, animation: false,
    interaction: { mode: "nearest", intersect: false },
    scales: {
      x: { type: "time",
           time: { tooltipFormat: "MMM d, HH:mm:ss",
                   displayFormats: { second: "HH:mm:ss", minute: "HH:mm", hour: "MMM d HH:mm", day: "MMM d" } },
           ticks: { color: "#8b93a5" }, grid: { color: "#242b38" } },
      y: { ticks: { color: "#8b93a5", callback: v => short(v) }, grid: { color: "#242b38" } }
    },
    plugins: {
      legend: { display: false },
      tooltip: { callbacks: {
        // Hovering a marker shows the cause message behind it.
        label: (ctx) => {
          if (view === "delta") return "Change: " + signed(ctx.parsed.y);
          const r = rows[ctx.dataIndex];
          const out = playerUuid ? [] : [r.player_name];         // with every account together, say whose row it is
          out.push("Coins: " + r.coins.toLocaleString());
          if (r.change !== 0) out.push("Change: " + signed(r.change));
          out.push("Cause: " + r.cause_label.replace(/_/g, " "));
          if (r.cause_message) out.push(...wrap(r.cause_message, 52));
          return out;
        }
      } }
    }
  };

  chart = new Chart(el("chart"), config);
}

function drawStats() {
  let gained = 0, lost = 0;
  for (const r of rows) {
    if (r.change > 0) gained += r.change;
    else if (r.change < 0) lost -= r.change;
  }
  const net = gained - lost;
  const has = rows.length > 0;

  // signed() rather than a hand-written sign, so zero shows as "0" not "-0".
  el("sGained").textContent = has ? signed(gained) : "—";
  el("sGained").className = "v " + (has ? cls(gained) : "zero");
  el("sLost").textContent = has ? signed(-lost) : "—";
  el("sLost").className = "v " + (has ? cls(-lost) : "zero");
  el("sNet").textContent = has ? signed(net) : "—";
  el("sNet").className = "v " + (has ? cls(net) : "zero");

  // Coins per hour of play. Under a minute of data would extrapolate to a
  // meaningless number, so show a dash instead.
  el("kRate").textContent = merged ? "Coins per hour played" : "Coins per hour";
  const rate = el("sRate");
  rate.textContent = "—";
  rate.className = "v zero";
  const played = playedMs();
  if (played >= 60000) {
    const perHour = net / (played / 3600000);
    rate.textContent = signed(perHour) + "/h";
    rate.className = "v " + cls(perHour);
  }
}

// Time spent playing: the sum of each session's own length. Merged sessions can
// be days apart, and the hours spent logged out must not drag the rate down.
function playedMs() {
  const spans = new Map();
  for (const r of rows) {
    const span = spans.get(r.session_id);
    if (!span) spans.set(r.session_id, { first: r.ts_ms, last: r.ts_ms });
    else { span.first = Math.min(span.first, r.ts_ms); span.last = Math.max(span.last, r.ts_ms); }
  }
  let total = 0;
  for (const span of spans.values()) total += span.last - span.first;
  return total;
}

function drawBreakdown() {
  const totals = new Map();
  for (const r of rows) {
    if (r.change === 0) continue;
    totals.set(r.cause_label, (totals.get(r.cause_label) || 0) + r.change);
  }

  const box = el("breakdown");
  if (!totals.size) {
    box.innerHTML = '<div class="empty">No coin changes in this session yet.</div>';
    return;
  }

  // Biggest absolute impact first.
  const sorted = [...totals.entries()].sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]));
  box.innerHTML = sorted.map(([label, amount]) =>
    '<div class="bar"><span class="lbl">' + esc(label.replace(/_/g, " ")) + '</span>' +
    '<span class="amt ' + cls(amount) + '">' + signed(amount) + '</span></div>').join("");
}

function drawChat() {
  const box = el("chatlog");
  const withText = rows.filter(r => r.cause_message);
  if (!withText.length) {
    box.innerHTML = '<div class="empty">No messages yet.</div>';
    return;
  }

  // Newest first, and only the latest few hundred so a long merged history stays quick.
  const shown = withText.slice(-MAX_MESSAGES).reverse();
  const cut = withText.length > MAX_MESSAGES
    ? '<div class="empty">Showing the newest ' + MAX_MESSAGES + " of " + withText.length + " messages.</div>" : "";

  box.innerHTML = cut + shown.map(r =>
    '<div class="msg"><div class="head">' +
      '<span class="when">' + esc(stamp(r.ts_ms)) + '</span>' +
      '<span class="amt ' + cls(r.change) + '">' + signed(r.change) + '</span></div>' +
      '<div class="txt">' + esc(r.cause_message) + '</div></div>').join("");
}

// ---------------------------------------------------------------- controls

function pickView(value) {
  view = value;
  el("vTotal").classList.toggle("on", value === "total");
  el("vDelta").classList.toggle("on", value === "delta");
  drawChart();
}
el("vTotal").addEventListener("click", () => pickView("total"));
el("vDelta").addEventListener("click", () => pickView("delta"));

// ---------------------------------------------------------------- tabs

function showTab(name) {
  if (name !== "tracker" && name !== "finder" && name !== "chats" && name !== "frienemys" && !(name === "live" && el("tabLive"))) name = "coins";
  el("tabCoins").hidden = name !== "coins";
  el("tabTracker").hidden = name !== "tracker";
  el("tabFinder").hidden = name !== "finder";
  el("tabChats").hidden = name !== "chats";
  el("tabFrienemys").hidden = name !== "frienemys";
  if (el("tabLive")) {
    el("tabLive").hidden = name !== "live";
    el("tabBtnLive").setAttribute("aria-selected", String(name === "live"));
  }
  el("tabBtnCoins").setAttribute("aria-selected", String(name === "coins"));
  el("tabBtnTracker").setAttribute("aria-selected", String(name === "tracker"));
  el("tabBtnFinder").setAttribute("aria-selected", String(name === "finder"));
  el("tabBtnChats").setAttribute("aria-selected", String(name === "chats"));
  el("tabBtnFrienemys").setAttribute("aria-selected", String(name === "frienemys"));
  remember("femboyclient.tab", name);
  try { history.replaceState(null, "", name === "coins" ? location.pathname + location.search : "#" + name); } catch (e) { /* ignore */ }
  document.dispatchEvent(new CustomEvent("tab-changed", { detail: name }));
  if (name === "coins" && chart) chart.resize();   // a hidden chart has no size until it is shown
}
window.showTab = showTab;

el("tabBtnCoins").addEventListener("click", () => showTab("coins"));
el("tabBtnTracker").addEventListener("click", () => showTab("tracker"));
el("tabBtnFinder").addEventListener("click", () => showTab("finder"));
el("tabBtnChats").addEventListener("click", () => showTab("chats"));
el("tabBtnFrienemys").addEventListener("click", () => showTab("frienemys"));
if (el("tabBtnLive")) el("tabBtnLive").addEventListener("click", () => showTab("live"));
showTab(location.hash === "#live" ? "live" : location.hash === "#finder" ? "finder" : location.hash === "#tracker" ? "tracker" : location.hash === "#chats" ? "chats" : location.hash === "#frienemys" ? "frienemys" : recall("femboyclient.tab") || "coins");

// ---------------------------------------------------------------- start

(async function init() {
  await refreshPlayers();
  // Status and new sessions are picked up without a page refresh.
  // A hidden browser tab skips the polls and catches up as soon as it is shown again.
  setInterval(() => { if (!document.hidden) refreshPlayers(); }, 5000);
  setInterval(() => { if (!document.hidden) refreshSessions(false); }, 10000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) { refreshPlayers(); refreshSessions(false); } });
})();
