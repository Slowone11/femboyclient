// Femboy Client website: Players tab.

// ================================================================ tracker

try {
// Players tab: a map of the SkyBlock worlds you have been in, the players you met sorted into tiers by how
// often you met them, a list of the chosen tier, and a panel for one player.
//
// A meeting is not a sighting: hopping lobbies sees the same player many times in a few minutes, so the website
// folds every sighting less than 4 hours after a meeting starts into that meeting (see /tracker/players). The
// sightings themselves are all kept and shown inside each meeting.
//
// It talks to /tracker/overview (the map and the tier counts), /tracker/players (the list), /tracker/search (a
// player's panel and the suggestions), /tracker/live (new sightings, parties and chat as they happen),
// /tracker/status and /tracker/discord. All times are displayed in America/Toronto; the server stores them in UTC.
//
// The stream and the alerts keep running while another tab is open, so an alert can appear wherever you are on
// the page.
(function () {
  "use strict";

  // ------------------------------------------------------------------ settings

  const TZ = "America/Toronto";
  const PAGE = 50;                  // players asked for at a time for the list
  const PREVIEW = 3;                // names shown on each tier card
  const REFRESH_MS = 2500;          // after live events, the map, tiers and list are refreshed at most this often
  const ALERT_BATCH_MS = 1200;      // returning players arriving together become one alert
  const TOASTS_EACH = 3;            // up to this many are named individually, more are summarised
  const MAX_TOASTS = 5;
  const LOG_LIMIT = 100;
  const KEY_ALERTS = "femboyclient.tracker.alerts";
  const KEY_LOG = "femboyclient.tracker.alertLog";
  const KEY_WORLD = "femboyclient.tracker.world";
  const KEY_TIER = "femboyclient.tracker.tier";
  const KEY_SORT = "femboyclient.tracker.sort";

  // The tier cards, in order. "close" overlaps the others.
  const TIERS = [
    { id: "once",      label: "Strangers",         hint: "met once" },
    { id: "twice",     label: "Seen twice",        hint: "2 meetings" },
    { id: "recurring", label: "Recurring",         hint: "3–4 meetings" },
    { id: "regular",   label: "Regulars",          hint: "5+ meetings" },
    { id: "close",     label: "Partied or tagged", hint: "any meetings" }
  ];

  const $ = (id) => document.getElementById(id);

  // Every world gets its own colour, picked to look as different from the others as possible.
  // The colours below were chosen by measuring how different they look to the eye (CIE Lab),
  // so the first ten stay clearly apart. A world that is not listed takes the next spare colour, and
  // once those run out the colour that is furthest from all the others. It keeps its colour (remembered in the browser).
  const FIXED_WORLD_COLORS = {
    "Hub": "#16f316", "The End": "#f316f3", "Dungeons": "#f31659", "Crystal Hollows": "#16f3c7",
    "Dwarven Mines": "#f35916", "Private Island": "#f17ece", "Dungeon Hub": "#c3f17e",
    "Unknown": "#7a8599"
  };
  const SPARE_COLORS = ["#f1957e", "#1643f3", "#f3f316", "#7ee5f1", "#a17ef1", "#16f385", "#f1ce7e", "#f04c8e"];
  const KEY_WORLD_COLORS = "femboyclient.tracker.worldColors2";

  let assignedColors = {};
  try { assignedColors = JSON.parse(load(KEY_WORLD_COLORS) || "{}") || {}; } catch (e) { assignedColors = {}; }

  // "#rrggbb" or "hsl(h, s%, l%)" to red, green, blue from 0 to 1.
  function toRgb(color) {
    const hex = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(color);
    if (hex) return [1, 2, 3].map(i => parseInt(hex[i], 16) / 255);
    const m = /^hsl\(\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)%\s*,\s*(\d+(?:\.\d+)?)%\)$/.exec(color);
    const h = Number(m ? m[1] : 0), s = (m ? Number(m[2]) : 0) / 100, l = (m ? Number(m[3]) : 50) / 100;
    const a = s * Math.min(l, 1 - l);
    const f = (n) => { const k = (n + h / 30) % 12; return l - a * Math.max(-1, Math.min(k - 3, Math.min(9 - k, 1))); };
    return [f(0), f(8), f(4)];
  }

  // How a colour looks to the eye, as lightness, green-red and blue-yellow (CIE Lab).
  function toLab(color) {
    const [r, g, b] = toRgb(color).map(c => c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
    const x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047, y = r * 0.2126 + g * 0.7152 + b * 0.0722,
          z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883;
    const t = (v) => v > 0.008856 ? Math.cbrt(v) : 7.787 * v + 16 / 116;
    return [116 * t(y) - 16, 500 * (t(x) - t(y)), 200 * (t(y) - t(z))];
  }

  // How different two colours look. Under about 20 they are easy to mix up; over 40 they are clearly apart.
  function colorGap(a, b) {
    const p = toLab(a), q = toLab(b);
    return Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2]);
  }

  // The bright colour that looks most different from every colour in use.
  function generatedColor(used) {
    let best = "#ffffff", bestGap = -1;
    for (let hue = 0; hue < 360; hue += 6) {
      for (const [sat, light] of [[90, 55], [85, 64], [80, 74]]) {
        const candidate = "hsl(" + hue + ", " + sat + "%, " + light + "%)";
        const gap = Math.min(...used.map(u => colorGap(candidate, u)));
        if (gap > bestGap) { bestGap = gap; best = candidate; }
      }
    }
    return best;
  }

  function worldColor(name) {
    if (FIXED_WORLD_COLORS[name]) return FIXED_WORLD_COLORS[name];
    if (assignedColors[name]) return assignedColors[name];

    const used = Object.values(FIXED_WORLD_COLORS).concat(Object.values(assignedColors), "#ff4fa6");
    const color = SPARE_COLORS.find(c => !used.includes(c)) || generatedColor(used);
    assignedColors[name] = color;
    store(KEY_WORLD_COLORS, JSON.stringify(assignedColors));
    return color;
  }

  // ------------------------------------------------------------------- helpers

  // The formatters are built once: toLocaleString with options builds a new one on every call, which is slow in a long list.
  const yearFormat = new Intl.DateTimeFormat("en-US", { timeZone: TZ, year: "numeric" });
  const shortFormat = new Intl.DateTimeFormat("en-US", { timeZone: TZ, month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  const shortYearFormat = new Intl.DateTimeFormat("en-US",
    { timeZone: TZ, month: "short", day: "numeric", hour: "numeric", minute: "2-digit", year: "numeric" });
  const timeFormat = new Intl.DateTimeFormat("en-US", { timeZone: TZ, hour: "numeric", minute: "2-digit" });

  // "Sep 21, 3:41 PM" in Toronto time. The year is added for other years.
  function fmtShort(iso) {
    const when = new Date(iso);
    return (yearFormat.format(when) !== yearFormat.format(new Date()) ? shortYearFormat : shortFormat).format(when);
  }

  // "3:41 PM" in Toronto time.
  function fmtTime(iso) {
    return timeFormat.format(new Date(iso));
  }

  // "just now", "12m ago", "5h ago", "6d ago", then the date for anything older than a month.
  function ago(iso) {
    if (!iso) return "";
    const seconds = (Date.now() - new Date(iso).getTime()) / 1000;
    if (seconds < 60) return "just now";
    if (seconds < 3600) return Math.floor(seconds / 60) + "m ago";
    if (seconds < 86400) return Math.floor(seconds / 3600) + "h ago";
    if (seconds < 86400 * 31) return Math.floor(seconds / 86400) + "d ago";
    return fmtShort(iso).replace(/,? \d+:\d+ [AP]M$/, "");
  }

  // A relative time with the full time on hover.
  function agoHtml(iso) {
    return iso ? '<span title="' + esc(fmtFull(iso)) + '">' + esc(ago(iso)) + "</span>" : "–";
  }

  // "16 min", "3 h 12 min"
  function duration(fromIso, toIso) {
    const minutes = Math.round((new Date(toIso) - new Date(fromIso)) / 60000);
    if (minutes < 1) return "";
    if (minutes < 60) return minutes + " min";
    return Math.floor(minutes / 60) + " h" + (minutes % 60 ? " " + (minutes % 60) + " min" : "");
  }

  const num = (n) => Number(n || 0).toLocaleString("en-US");
  const plural = (n, one, many) => num(n) + " " + (n === 1 ? one : (many || one + "s"));
  const dot = (world) => '<i class="tk-dot" style="background:' + esc(worldColor(world)) + '"></i>';

  function store(key, value) { remember(key, value); }      // core.js; declared as functions so they work above this line
  function load(key) { return recall(key); }

  // --------------------------------------------------------------------- state

  let owner = null;                 // the account being shown, or "all"
  let ownerState = "";              // "loading" or "loaded" once the tab is for owner
  let ownerLabel = "";              // its name, or "All accounts"
  let world = load(KEY_WORLD) || ""; // the world chosen on the map, "" for every world (as the server spells it)
  let tier = load(KEY_TIER) || "regular";
  let sort = load(KEY_SORT) || "meetings";
  if (!TIERS.some(t => t.id === tier) && tier !== "all") tier = "regular";

  let overview = null;              // the last /tracker/overview answer
  let previews = {};                // tier id -> the first few players, for the cards
  let rows = [], rowsTotal = 0;     // the list
  let lastTotal = null;             // "Players met" before the latest refresh (null until the first one arrives)
  let plusAmount = 0, plusTimer = null;
  let loadTicket = 0, listTicket = 0, detailTicket = 0, suggestTicket = 0;
  let refreshTimer = null, refreshDue = 0;

  let profileName = null;           // the player whose panel is open
  let profile = null;               // what was loaded for them (one /tracker/search player)
  let playerTab = "meetings";       // "meetings", "chat" or "parties". Kept as you move from player to player.
  let statusView = null;            // the Hypixel status line: { name, data, failure, loading, receivedAt }
  let meTab = "summary";            // the account panel: "summary" or "chat" (what you said)
  let myChat = null;                // { count, chats } for the account shown, or null until loaded
  let tagBusy = false, tagError = "";
  const DISCORD_TTL_MS = 3600000;   // matches the website's cache: a Discord name is asked for once an hour
  const discordKept = new Map();    // lower-case name -> { data, receivedAt }
  let discordView = null;           // { name, data, loading, failure } for the player whose panel is open
  const statusKept = new Map();     // lower-case name -> { data, receivedAt }, so a second click within 45 s asks nothing
  const STATUS_TTL_MS = 45000;      // matches the website's cache
  const REFRESH_COOLDOWN_MS = 10000;// the status refresh waits this long after a lookup

  let stream = null, reconnectTimer = null, lastEventId = 0;

  let alertQueue = [], alertTimer = null;
  let alertLog = [];
  try { alertLog = JSON.parse(load(KEY_LOG) || "[]"); } catch (e) { alertLog = []; }

  const ownerParam = () => owner ? "&owner=" + encodeURIComponent(owner) : "";
  const worldParam = () => world ? "&world=" + encodeURIComponent(world) : "";

  // ------------------------------------------------------------ the counter

  // The "Players met" counter. It flashes when the number goes up.
  function renderTotal(total) {
    const box = $("tkTotal");
    const shown = num(total);
    if (box.textContent === shown) return;
    const grew = box.textContent !== "0" && box.textContent !== "";
    box.textContent = shown;
    if (grew) {
      const pill = box.parentElement;
      pill.classList.remove("bump");
      void pill.offsetWidth;            // lets the animation start again
      pill.classList.add("bump");
    }
  }

  // A "+22" floats up from the counter and fades. Players arriving together add up in one popup.
  function showPlus(amount) {
    const box = $("tkPlus");
    plusAmount = plusTimer ? plusAmount + amount : amount;
    box.textContent = "+" + plusAmount;
    box.classList.remove("go");
    void box.offsetWidth;               // lets the animation start again
    box.classList.add("go");
    clearTimeout(plusTimer);
    plusTimer = setTimeout(() => { plusTimer = null; box.classList.remove("go"); }, 2400);
  }

  // ---------------------------------------------------------------- the map

  // Lays out boxes with areas in proportion to their values, as square as it can (the "squarified" treemap).
  function squarify(items, x, y, w, h) {
    const out = [];
    const total = items.reduce((s, i) => s + i.value, 0);
    if (!total) return out;
    const scale = (w * h) / total;
    let rest = items.map(i => Object.assign({}, i, { area: i.value * scale }));

    const worst = (row, side) => {
      const sum = row.reduce((s, r) => s + r.area, 0);
      let max = 0, min = Infinity;
      for (const r of row) { max = Math.max(max, r.area); min = Math.min(min, r.area); }
      return Math.max((side * side * max) / (sum * sum), (sum * sum) / (side * side * min));
    };

    while (rest.length) {
      const side = Math.min(w, h);
      const row = [rest[0]];
      let i = 1;
      while (i < rest.length && worst(row.concat(rest[i]), side) <= worst(row, side)) row.push(rest[i++]);
      rest = rest.slice(i);

      const sum = row.reduce((s, r) => s + r.area, 0);
      if (w >= h) {                     // a column on the left
        const cw = sum / h;
        let cy = y;
        for (const r of row) { const rh = r.area / cw; out.push(Object.assign(r, { x, y: cy, w: cw, h: rh })); cy += rh; }
        x += cw; w -= cw;
      } else {                          // a row along the top
        const rh = sum / w;
        let cx = x;
        for (const r of row) { const rw = r.area / rh; out.push(Object.assign(r, { x: cx, y, w: rw, h: rh })); cx += rw; }
        y += rh; h -= rh;
      }
    }
    return out;
  }

  function renderMap() {
    const box = $("tkMap");
    const worlds = overview ? overview.worlds.filter(w => w.players > 0) : [];
    if (!worlds.length) {
      box.innerHTML = '<div class="tk-mapempty">' + (overview ? "No players met yet. The mod reports them as soon as you join a lobby." : "Loading…") + "</div>";
      return;
    }
    const width = box.clientWidth || 800, height = box.clientHeight || 170;
    // A square root keeps a world with 15 players visible next to one with 10,000, while the big ones stay biggest.
    const tiles = squarify(worlds.map(w => ({ item: w, value: Math.sqrt(w.players) })), 0, 0, width, height);
    const chosen = world.toLowerCase();
    box.innerHTML = tiles.map(t => {
      const w = t.item, color = worldColor(w.world);
      const small = t.w < 70 || t.h < 34, tiny = t.w < 34 || t.h < 20;
      const on = chosen && w.world.toLowerCase() === chosen;
      return '<button class="tk-tile' + (on ? " on" : "") + (chosen && !on ? " dim" : "") + (small ? " small" : "") + '"' +
        ' data-world="' + esc(w.world) + '" title="' + esc(w.world + ": " + num(w.players) + " players, " + num(w.visits) + " lobby visits") + '"' +
        ' style="left:' + t.x.toFixed(1) + "px;top:" + t.y.toFixed(1) + "px;width:" + t.w.toFixed(1) + "px;height:" + t.h.toFixed(1) + "px;" +
        "--c:" + esc(color) + '">' +
        (tiny ? "" : '<span class="tk-tname">' + esc(w.world) + '</span><span class="tk-tnum">' + num(w.players) + "</span>") +
        "</button>";
    }).join("");
  }

  function renderScope() {
    const listed = overview && world ? overview.worlds.find(w => w.world.toLowerCase() === world.toLowerCase()) : null;
    const name = world ? (listed ? listed.world : world) : "All worlds";
    $("tkScope").innerHTML =
      (world ? dot(name) : "") + "<b>" + esc(name) + "</b>" +
      (overview ? '<span class="tk-dim"> · ' + plural(overview.players, "player") + " · " + plural(overview.meetings, "meeting") + "</span>" : "") +
      (world ? ' <button class="tk-link" data-world="">Show all worlds</button>' : '<span class="tk-dim"> · click a world to look inside it</span>');
  }

  // ---------------------------------------------------------------- the tiers

  function renderTiers() {
    const counts = overview ? overview.tiers : {};
    $("tkTiers").innerHTML = TIERS.map(t => {
      const n = counts[t.id] || 0;
      const shown = previews[t.id] || [];
      const names = t.id === "once"
        ? '<div class="tk-tnames tk-dim">One-off meetings. Open to list them.</div>'
        : '<div class="tk-tnames">' + (shown.length
            ? shown.map(p => '<span><b>' + esc(p.username) + "</b>" +
                (t.id === "close" ? closeMark(p) : '<em>×' + p.meetings + "</em>") + "</span>").join("")
            : '<span class="tk-dim">' + (overview ? "Nobody yet" : "") + "</span>") + "</div>";
      return '<button class="tk-tier t-' + t.id + (tier === t.id ? " on" : "") + '" data-tier="' + t.id + '">' +
        '<span class="tk-tlabel">' + esc(t.label) + ' <small>' + esc(t.hint) + "</small></span>" +
        '<span class="tk-tcount">' + num(n) + "</span>" + names + "</button>";
    }).join("");
  }

  // What makes a player "close": the tag, or how often they partied with you.
  function closeMark(p) {
    if (p.tag) return '<em class="tag-' + esc(p.tag.tag) + '">' + esc(p.tag.tag) + "</em>";
    return p.party_times ? '<em class="party">party ×' + p.party_times + "</em>" : "";
  }

  async function loadPreviews(ticket) {
    const wanted = TIERS.filter(t => t.id !== "once");
    const answers = await Promise.all(wanted.map(t =>
      getJson("/tracker/players?tier=" + t.id + "&sort=" + (t.id === "close" ? "last" : "meetings") + "&limit=" + PREVIEW + ownerParam() + worldParam())
        .catch(() => null)));
    if (ticket !== loadTicket) return;
    wanted.forEach((t, i) => { if (answers[i]) previews[t.id] = answers[i].players; });
    renderTiers();
  }

  // ---------------------------------------------------------------- the list

  function listTitle() {
    const t = TIERS.find(x => x.id === tier);
    return (t ? t.label : "Everyone") + (world ? " in " + world : "");
  }

  async function loadList(append) {
    const ticket = ++listTicket;
    const offset = append ? rows.length : 0;
    const limit = append ? PAGE : Math.max(PAGE, rows.length);    // a refresh keeps what was already shown
    let page;
    try {
      page = await getJson("/tracker/players?tier=" + tier + "&sort=" + sort + "&offset=" + offset + "&limit=" + Math.min(limit, 500) + ownerParam() + worldParam());
    } catch (e) {
      if (ticket === listTicket) $("tkListNote").textContent = "Could not load the list.";
      return;
    }
    if (ticket !== listTicket) return;
    rows = append ? rows.concat(page.players) : page.players;
    rowsTotal = page.total;
    renderList();
  }

  function renderList() {
    $("tkListTitle").textContent = listTitle();
    $("tkListCount").textContent = rowsTotal ? num(rowsTotal) : "";
    $("tkSort").value = sort;
    const open = profileName ? profileName.toLowerCase() : "";
    $("tkRows").innerHTML = rows.length
      ? rows.map(p => {
          const worlds = Object.entries(p.worlds || {}).sort((a, b) => b[1] - a[1]);
          return '<button class="tk-row' + (p.username.toLowerCase() === open ? " on" : "") + '" data-player="' + esc(p.username) + '">' +
            '<span class="tk-rname">' + (p.tag ? '<i class="tk-tagdot ' + esc(p.tag.tag) + '" title="' + esc(p.tag.tag + (p.tag.notes ? ": " + p.tag.notes : "")) + '"></i>' : "") +
              esc(p.username) + "</span>" +
            '<span class="tk-rmeet" title="' + esc(plural(p.sightings, "sighting")) + '">' + (p.meetings ? "×" + p.meetings : "–") + "</span>" +
            '<span class="tk-rworlds" title="' + esc(worlds.map(([w, n]) => w + " ×" + n).join(", ")) + '">' + worlds.slice(0, 6).map(([w]) => dot(w)).join("") + "</span>" +
            '<span class="tk-rparty">' + (p.party_times ? "party ×" + p.party_times : "") + "</span>" +
            '<span class="tk-rlast">' + (p.meetings ? agoHtml(p.last_seen) : "never met") + "</span>" +
            "</button>";
        }).join("")
      : '<div class="empty">' + (overview ? "Nobody in this group" + (world ? " in " + esc(world) : "") + "." : "Loading…") + "</div>";
    $("tkMore").hidden = rows.length >= rowsTotal;
    $("tkMore").textContent = "Show " + Math.min(PAGE, rowsTotal - rows.length) + " more";
    $("tkListNote").textContent = rows.length && rows.length < rowsTotal ? "Showing " + num(rows.length) + " of " + num(rowsTotal) : "";
  }

  // ------------------------------------------------------------ loading it all

  // The map, the tier counts and names, and the list, for the current account and world.
  async function refreshAll() {
    if (!owner) return null;
    const ticket = ++loadTicket;
    const asking = owner;
    let data;
    try {
      data = await getJson("/tracker/overview?" + ownerParam().slice(1) + worldParam());
    } catch (e) {
      if (ticket === loadTicket) setLive(false, "offline");
      return null;
    }
    if (ticket !== loadTicket || asking !== owner) return null;
    overview = data;
    // The first answer for an account is just the starting total. Later increases are new players.
    if (lastTotal !== null && data.total_players > lastTotal) showPlus(data.total_players - lastTotal);
    lastTotal = data.total_players;
    renderTotal(data.total_players);
    renderMap();
    renderScope();
    renderTiers();
    loadPreviews(ticket);
    loadList(false);
    if (!profile) showMe();
    return data;
  }

  // Live events refresh everything, but at most every REFRESH_MS.
  function scheduleRefresh() {
    const now = Date.now();
    if (refreshTimer) return;
    const wait = Math.max(200, refreshDue - now);
    refreshTimer = setTimeout(() => { refreshTimer = null; refreshDue = Date.now() + REFRESH_MS; refreshAll(); }, wait);
  }

  function chooseWorld(name) {
    world = name && name.toLowerCase() !== world.toLowerCase() ? name : "";
    store(KEY_WORLD, world);
    previews = {};
    rows = [];
    renderMap();
    renderScope();
    refreshAll();
  }

  function chooseTier(id) {
    tier = id;
    store(KEY_TIER, tier);
    rows = [];
    renderTiers();
    renderList();
    loadList(false);
  }

  // ------------------------------------------------------------- scanner status

  // What the mod last reported about its scanner, shown next to the live indicator.
  function updateScanner() {
    const box = $("tkScanner");
    const account = window.currentAccount && window.currentAccount();
    if (!account || account.all) { box.textContent = ""; return; }               // every account together has no single scanner

    let text, on = false;
    if (!account.online) text = "Scanner: unknown (the mod is not connected)";
    else if (!account.scanner) text = "Scanner: unknown";
    else { text = "Scanner: " + account.scanner; on = account.scanner.toUpperCase().startsWith("ON"); }

    box.textContent = text;
    box.className = "tk-scanner" + (on ? " on" : "");
    box.title = "The scanner is always on, in every world, while the mod is enabled";
  }

  // ------------------------------------------------------------------ live feed

  function setLive(on, text) {
    $("tkDot").className = "dot" + (on ? " on" : "");
    $("tkLive").textContent = text;
  }

  function closeStream() {
    clearTimeout(reconnectTimer);
    if (stream) { stream.close(); stream = null; }
  }

  function startStream(cursor) {
    closeStream();
    lastEventId = cursor;
    const watching = owner;

    stream = new EventSource("/tracker/live?owner=" + encodeURIComponent(watching) + "&since=" + cursor);
    stream.onopen = () => { setLive(true, "live"); loadMyChat(); };
    stream.onmessage = (event) => {
      if (watching !== owner) return;
      const ev = JSON.parse(event.data);
      if (ev.kind === "party") { onParty(ev); return; }    // party and chat events have their own ids
      if (ev.kind === "chat") { applyChat(ev); return; }
      if (ev.kind === "mychat") { applyMyChat(ev); return; }
      if (ev.id <= lastEventId) return;          // a repeat after a reconnect
      lastEventId = ev.id;
      if (ev.returning) queueAlert(ev);
      if (profileName && profileName.toLowerCase() === String(ev.username).toLowerCase()) showPlayer(profileName);
      scheduleRefresh();
    };
    stream.onerror = () => {
      // Reconnect from the newest event seen, so nothing is repeated or missed.
      setLive(false, "reconnecting…");
      closeStream();
      reconnectTimer = setTimeout(() => { if (watching === owner) startStream(lastEventId); }, 2000);
    };
  }

  // Someone was just confirmed to be in your party.
  function onParty(ev) {
    if (profileName && profileName.toLowerCase() === String(ev.username).toLowerCase()) showPlayer(profileName);
    scheduleRefresh();
  }

  // ----------------------------------------------------------------------- alerts

  function alertsOn() { return $("tkAlerts").checked; }

  // "in Crystal Hollows" when the last meeting's world is known.
  function whereText(name) {
    return name && name !== "Unknown" ? " in " + name : "";
  }

  // "Hub", "Hub and Dungeon Hub", "Hub, Dungeon Hub and The End"
  function listWorlds(names) {
    const list = names.filter(n => n !== "Unknown");
    if (list.length < 2) return list.join("");
    return list.slice(0, -1).join(", ") + " and " + list[list.length - 1];
  }

  // The worlds a player was met in before that are not the one they are in now.
  function otherWorlds(ev) {
    const now = String(ev.world).toLowerCase();
    return (ev.previous_worlds || []).filter(w => w.toLowerCase() !== now && w !== "Unknown");
  }

  function queueAlert(ev) {
    if (!alertsOn()) return;
    alertQueue.push(ev);
    if (!alertTimer) alertTimer = setTimeout(flushAlerts, ALERT_BATCH_MS);
  }

  function flushAlerts() {
    alertTimer = null;
    const batch = alertQueue;
    alertQueue = [];
    if (!batch.length || !alertsOn()) return;

    const now = Date.now();
    for (const ev of batch) {
      alertLog.unshift({ t: now, name: ev.username, previous: ev.previous_seen_at, world: ev.previous_world || "",
                         now: ev.world, before: ev.previous_worlds || [] });
    }
    alertLog = alertLog.slice(0, LOG_LIMIT);
    store(KEY_LOG, JSON.stringify(alertLog));
    renderAlertLog();

    if (batch.length <= TOASTS_EACH) {
      for (const ev of batch) {
        const others = otherWorlds(ev);
        if (others.length) {
          toast("Seen in another world",
                ev.username + " is in " + ev.world + " now. You saw them before in " + listWorlds(ev.previous_worlds) +
                " (last " + fmtShort(ev.previous_seen_at) + ")", ev.username);
        } else {
          toast("Met before",
                "You've met " + ev.username + " before" + whereText(ev.previous_world) + ": last seen " + fmtShort(ev.previous_seen_at),
                ev.username);
        }
      }
    } else {
      const names = batch.slice(0, 3).map(ev => ev.username).join(", ");
      const crossing = batch.filter(ev => otherWorlds(ev).length).length;
      toast(batch.length + " players you have met before",
            names + " and " + (batch.length - 3) + " more" + (crossing ? " (" + crossing + " from another world)" : "") +
            ". See the alert log.", null);
    }
  }

  function toast(title, body, player) {
    const box = $("toasts");
    const node = document.createElement("div");
    node.className = "toast";
    node.innerHTML = '<div class="toast-title"></div><div class="toast-body"></div>' +
                   (player ? '<button class="toast-view">View profile</button>' : "") +
                   '<button class="toast-x" aria-label="Dismiss">×</button>';
    node.querySelector(".toast-title").textContent = title;
    node.querySelector(".toast-body").textContent = body;
    node.querySelector(".toast-x").onclick = () => node.remove();
    const view = node.querySelector(".toast-view");
    if (view) view.onclick = () => { node.remove(); openPlayer(player); };

    box.appendChild(node);
    while (box.children.length > MAX_TOASTS) box.firstChild.remove();
    setTimeout(() => node.remove(), player ? 9000 : 12000);
  }

  // The alert log only takes room once there is something in it.
  function renderAlertLog() {
    $("tkAlertPanel").hidden = !alertLog.length;
    $("tkAlertCount").textContent = alertLog.length ? String(alertLog.length) : "";
    $("tkAlertLog").innerHTML = alertLog.map(a => {
      const others = (a.before || []).filter(w => w.toLowerCase() !== String(a.now || "").toLowerCase() && w !== "Unknown");
      const detail = a.before
        ? (a.now && a.now !== "Unknown" ? "Now in " + a.now + ". " : "") +
          (others.length ? "Also seen in " + listWorlds(others) + ". " : "") + "Last seen " + fmtShort(a.previous)
        : "Last seen " + fmtShort(a.previous) + whereText(a.world);
      return '<div class="tk-alert"><button data-player="' + esc(a.name) + '">' + esc(a.name) + "</button>" +
        "<small>" + esc(detail) + " · alerted " + esc(ago(new Date(a.t).toISOString())) + "</small></div>";
    }).join("");
  }

  // ---------------------------------------------------------------- player panel

  // explicit is true only when you chose the player (a click in the list, a search result, an alert). That is the
  // ONLY time their Hypixel status is looked up. Redrawing the panel for any other reason never asks Hypixel.
  async function showPlayer(username, explicit) {
    if (explicit) { statusView = null; tagError = ""; }
    else if (statusView && statusView.name.toLowerCase() !== username.toLowerCase()) statusView = null;
    const ticket = ++detailTicket;
    const switching = !profileName || profileName.toLowerCase() !== username.toLowerCase();
    profileName = username;
    if (switching) {
      profile = null;
      $("tkDetailsBody").innerHTML = '<div class="empty">Loading…</div>';
    }
    markRow();

    let data;
    try {
      data = await getJson("/tracker/search?q=" + encodeURIComponent(username) + "&exact=true" + ownerParam());
    } catch (e) {
      if (ticket === detailTicket) $("tkDetailsBody").innerHTML = '<div class="empty">Could not load that player.</div>';
      return;
    }
    if (ticket !== detailTicket) return;

    let p = data.players[0];
    if (!p && isValidName(username)) p = unknownPlayer(username);     // not in the database: still show who they are
    if (!p) { $("tkDetailsBody").innerHTML = '<div class="empty">No record of ' + esc(username) + ".</div>"; return; }

    profile = p;
    renderProfile();                      // your saved history first, so nothing waits on Hypixel
    if (explicit) { loadStatus(p.username, false); loadDiscord(p.username); }
    if (explicit && window.matchMedia("(max-width: 1080px)").matches) $("tkDetails").scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function markRow() {
    const open = profileName ? profileName.toLowerCase() : "";
    document.querySelectorAll("#tkRows .tk-row").forEach(r => r.classList.toggle("on", r.dataset.player.toLowerCase() === open));
  }

  // A player who is not in the database (searched for by name). Nothing is saved for them.
  function unknownPlayer(name) {
    return { username: name, unknown: true, times_seen: 0, meetings: 0, first_seen: null, last_seen: null, party_times: 0,
             parties: [], chat_count: 0, chats: [], sightings: [], meeting_list: [], by_world: {}, tag: null };
  }

  // The face of a player's skin, drawn by a public skin service from their username. Only a valid
  // username is ever put in the address. If the picture cannot load (offline, no such account) it just goes away.
  function faceHtml(name) {
    if (!isValidName(name)) return "";
    return '<img class="tk-face" alt="" width="52" height="52" src="https://mc-heads.net/avatar/' +
           encodeURIComponent(name) + '/104" onerror="this.remove()">';
  }

  function renderProfile() {
    const p = profile;
    if (!p) return;
    const tab = (id, label, n) => '<button class="tk-tab' + (playerTab === id ? " on" : "") + '" data-ptab="' + id + '">' + label +
      (n ? " <small>" + num(n) + "</small>" : "") + "</button>";
    let body;
    if (playerTab === "chat") body = chatHtml(p);
    else if (playerTab === "parties") body = partiesHtml(p);
    else body = meetingsHtml(p);

    $("tkDetailsBody").innerHTML =
      '<div class="tk-head">' + faceHtml(p.username) +
        '<div class="tk-headtext"><div class="tk-name">' + esc(p.username) + "</div>" + tagLine(p) + "</div>" +
        '<button class="tk-x" data-close="1" title="Back to the account summary" aria-label="Close">×</button></div>' +
      presenceHtml(p) +
      (p.unknown
        ? '<div class="tk-unknown"><b>Not in your database.</b> You have never met, partied with or heard from ' + esc(p.username) +
          ". Looking them up does not save anything.</div>"
        : statsHtml(p) + worldsBarHtml(p) + accountsHtml(p)) +
      actionsHtml(p) +
      (p.unknown ? "" :
        '<div class="tk-tabs">' + tab("meetings", "Meetings", p.meetings) + tab("chat", "Chat", p.chat_count) +
          tab("parties", "Parties", p.party_times) + "</div>" + body);
    tickStatusClock();
  }

  // The friendly/enemy tag and its note, under the name.
  function tagLine(p) {
    if (!p.tag) return "";
    return '<div class="tk-tagline"><span class="tk-tagpill ' + esc(p.tag.tag) + '">' + esc(p.tag.tag) + "</span>" +
      (p.tag.notes ? '<span class="tk-tagnote">' + esc(p.tag.notes) + "</span>" : "") + "</div>";
  }

  // Where they were last seen by your own accounts (always known, no key needed), then Hypixel's live status and Discord.
  function presenceHtml(p) {
    const last = (p.sightings || [])[0];
    const seen = last
      ? '<span class="tk-seen">Seen by you ' + agoHtml(last.seen_at) + " · " + dot(last.world) + esc(last.world) +
        (last.account ? '<span class="tk-dim"> · ' + esc(last.account) + "</span>" : "") + "</span>"
      : '<span class="tk-seen tk-dim">' + (p.unknown ? "Never seen by your accounts" : "Not met in a lobby yet") + "</span>";
    return '<div class="tk-presence">' + seen + statusHtml(p) + discordHtml(p) + "</div>";
  }

  function statsHtml(p) {
    const cell = (value, label, title) => '<div class="tk-stat"' + (title ? ' title="' + esc(title) + '"' : "") + "><b>" + value + "</b><span>" + label + "</span></div>";
    return '<div class="tk-stats4">' +
      cell(num(p.meetings), p.meetings === 1 ? "meeting" : "meetings",
           plural(p.times_seen, "sighting") + " in all. Sightings less than 4 hours after a meeting starts are part of it.") +
      cell(p.first_seen ? esc(ago(p.first_seen)) : "–", "first met", p.first_seen ? fmtFull(p.first_seen) : "") +
      cell(num(p.party_times), p.party_times === 1 ? "party" : "parties") +
      cell(num(p.chat_count), p.chat_count === 1 ? "message" : "messages") + "</div>";
  }

  // Meetings per world, as one bar.
  function worldsBarHtml(p) {
    const per = {};
    for (const m of p.meeting_list || []) for (const w of m.worlds) per[w] = (per[w] || 0) + 1;
    const list = Object.entries(per).sort((a, b) => b[1] - a[1]);
    if (!list.length) return "";
    const total = list.reduce((s, [, n]) => s + n, 0);
    return '<div class="tk-sub">Worlds</div><div class="tk-wbar">' +
      list.map(([w, n]) => '<i style="flex:' + n + ";background:" + esc(worldColor(w)) + '" title="' + esc(w + ": " + plural(n, "meeting")) + '"></i>').join("") +
      '</div><div class="tk-wlegend">' +
      list.map(([w, n]) => "<span>" + dot(w) + esc(w) + " <small>" + Math.round((n / total) * 100) + "%</small></span>").join("") + "</div>";
  }

  // Which of your accounts met them, when more than one did.
  function accountsHtml(p) {
    const per = {};
    for (const s of p.sightings || []) per[s.account] = (per[s.account] || 0) + 1;
    const list = Object.entries(per).sort((a, b) => b[1] - a[1]);
    if (list.length < 2) return "";
    return '<div class="tk-sub">Met by</div><div class="tk-accts">' +
      list.map(([a, n]) => "<span>" + esc(a) + " <small>" + plural(n, "sighting") + "</small></span>").join("") + "</div>";
  }

  function actionsHtml(p) {
    const tag = p.tag ? p.tag.tag : "";
    const name = encodeURIComponent(p.username);
    return '<div class="tk-actions">' +
      '<button class="tk-btn tk-friendly' + (tag === "friendly" ? " on" : "") + '" data-tag="friendly"' + (tagBusy ? " disabled" : "") + ">Friendly</button>" +
      '<button class="tk-btn tk-enemy' + (tag === "enemy" ? " on" : "") + '" data-tag="enemy"' + (tagBusy ? " disabled" : "") + ">Enemy</button>" +
      (tag ? '<button class="tk-btn" data-tag=""' + (tagBusy ? " disabled" : "") + ">Untag</button>" : "") +
      '<button class="tk-btn" data-find="1" title="Open the Player Finder for them">Find</button>' +
      '<a class="tk-btn" href="https://sky.shiiyu.moe/stats/' + name + '" target="_blank" rel="noopener noreferrer">SkyCrypt</a>' +
      '<a class="tk-btn" href="https://namemc.com/profile/' + name + '" target="_blank" rel="noopener noreferrer">NameMC</a>' +
      "</div>" + (tagError ? '<div class="tk-error">' + esc(tagError) + "</div>" : "");
  }

  // Each meeting: when, how long, where, which account, and every sighting in it (folded away).
  function meetingsHtml(p) {
    const list = p.meeting_list || [];
    if (!list.length) return '<div class="empty">Not met in a lobby yet' + (p.party_times ? ", only in a party." : ".") + "</div>";
    return '<ul class="tk-meetings">' + list.map(m => {
      const long = duration(m.start, m.end);
      const day = fmtShort(m.start).replace(/,? \d+:\d+ [AP]M$/, "");
      return '<li><details><summary>' +
        '<span class="tk-mday" title="' + esc(fmtFull(m.start)) + '">' + esc(day) + "</span>" +
        '<span class="tk-mtime">' + esc(fmtTime(m.start)) + (long ? "–" + esc(fmtTime(m.end)) : "") + "</span>" +
        '<span class="tk-mwhere">' + m.worlds.map(w => dot(w) + esc(w)).join(", ") + "</span>" +
        '<span class="tk-mcount">' + (m.count > 1 ? "×" + m.count : "") + "</span>" +
        "</summary>" +
        '<div class="tk-mmore">' + esc(m.accounts.join(", ")) + (long ? " · " + esc(long) : "") + " · " + plural(m.count, "sighting") +
        "<ol>" + m.sightings.map(s => "<li>" + esc(fmtTime(s.seen_at)) + " " + dot(s.world) + esc(s.world) +
          (m.accounts.length > 1 ? " <small>" + esc(s.account) + "</small>" : "") + "</li>").join("") + "</ol></div>" +
        "</details></li>";
    }).join("") + "</ul>";
  }

  function partiesHtml(p) {
    if (!p.party_times) return '<div class="empty">Never in a party with you.</div>';
    const accounts = new Set((p.parties || []).map(x => x.account));
    return '<ul class="tk-list">' + p.parties.map(x =>
      "<li>" + agoHtml(x.joined_at) + ' <small>' + esc(fmtShort(x.joined_at)) + "</small>" +
      (accounts.size > 1 ? "<small>" + esc(x.account) + "</small>" : "") + "</li>").join("") + "</ul>";
  }

  // Tags (or untags) the open player, then reloads their panel, the list and the cards.
  async function setTag(tag) {
    if (!profile || tagBusy) return;
    const p = profile;
    tagBusy = true; tagError = "";
    renderProfile();
    try {
      let res;
      if (tag) {
        res = await fetch("/tracker/frienemies", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ name: p.username, tag, notes: p.tag ? p.tag.notes : "" })
        });
      } else {
        res = await fetch("/tracker/frienemies/" + encodeURIComponent(p.id), { method: "DELETE" });
      }
      if (!res.ok) throw new Error("The website could not save that (HTTP " + res.status + ").");
      document.dispatchEvent(new CustomEvent("frienemies-changed"));
    } catch (e) {
      tagError = e.message;
    }
    tagBusy = false;
    await showPlayer(p.username);
    scheduleRefresh();
  }

  function findPlayer(name) {
    if (window.showTab) window.showTab("finder");
    const box = $("fdName"), go = $("fdSearch");
    if (box && !box.disabled) { box.value = name; if (go) go.click(); }
  }

  // ------------------------------------------------------------ Hypixel status

  // Asks the website (never Hypixel directly, and the key never reaches the page) whether a player is
  // online. Called only from showPlayer for a click, and from the refresh button.
  async function loadStatus(name, forced) {
    const lower = name.toLowerCase();
    const kept = statusKept.get(lower);
    const now = Date.now();

    if (kept && now - kept.receivedAt < STATUS_TTL_MS && !forced) {          // a recent answer: no request at all
      statusView = { name, data: kept.data, loading: false, receivedAt: kept.receivedAt };
      return refreshStatusBox();
    }
    if (forced && kept && now - kept.receivedAt < REFRESH_COOLDOWN_MS) return;   // the short cooldown

    statusView = { name, data: kept ? kept.data : null, loading: true, receivedAt: kept ? kept.receivedAt : 0 };
    refreshStatusBox();

    let data = null, failure = "";
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 12000);
    try {
      const res = await fetch("/tracker/status?name=" + encodeURIComponent(name) + (forced ? "&refresh=true" : ""), { signal: abort.signal });
      if (!res.ok) throw new Error("HTTP " + res.status);
      data = await res.json();
    } catch (e) {
      failure = e.name === "AbortError" ? "It took too long to answer." : "The website could not be reached.";
    } finally { clearTimeout(timer); }

    const receivedAt = Date.now();
    if (data && data.state !== "error") statusKept.set(lower, { data, receivedAt });
    if (!statusView || statusView.name.toLowerCase() !== lower) return;       // you moved on to someone else

    // An answer of "error" (no key, not found, rate limited...) is not a status: keep any older real answer instead.
    const good = data && data.state !== "error" ? data : null;
    statusView = { name, data: good || (kept ? kept.data : null), loading: false, receivedAt,
                   failure: failure || (data && data.state === "error" ? data.error.message : "") };
    refreshStatusBox();
  }

  // The badge text and its look for an answer.
  function statusBadge(d) {
    if (d.state === "online" && d.skyblock) {
      // A world the website does not know yet is shown from its raw name, for example "foraging_2" as "Foraging 2".
      const raw = d.mode ? String(d.mode).replace(/_/g, " ").replace(/\b[a-z]/g, c => c.toUpperCase()) : "";
      return { cls: "online", text: "Online · SkyBlock: " + (d.world || raw || "world unknown") };
    }
    if (d.state === "online") return { cls: "online", text: "Online · " + (d.game || "another game") };
    return { cls: "offline", text: "Offline or hidden",
             note: "Hypixel reports a player who hides their status as offline, so this may not be true." };
  }

  // Hypixel's answer as one small line. Without a working key it stays quiet: your own last-seen line above still says
  // where they were.
  function statusHtml(p) {
    const s = statusView;
    if (!s || s.name.toLowerCase() !== p.username.toLowerCase()) return "";
    let badge;
    if (s.data) {
      const b = statusBadge(s.data);
      badge = '<span class="tk-badge ' + b.cls + '"' + (b.note ? ' title="' + esc(b.note) + '"' : "") + ">" + esc(b.text) + "</span>";
    } else if (s.loading) {
      badge = '<span class="tk-badge loading">Checking Hypixel…</span>';
    } else {
      badge = '<span class="tk-badge off" title="' + esc(s.failure || "No answer.") + '">Hypixel status off</span>';
    }
    return '<span class="tk-status" id="tkStatusBox">' + badge +
      '<span id="tkStatusAge" class="tk-sage"></span>' +
      '<button class="tk-icon" id="tkStatusRefresh" data-status-refresh="1" title="Ask Hypixel again" aria-label="Refresh status">↻</button></span>';
  }

  // Redraws only the status line, so the rest of the panel (and its scroll position) is left alone.
  function refreshStatusBox() {
    if (!profile) return;
    const box = $("tkStatusBox");
    if (box) box.outerHTML = statusHtml(profile);
    else renderProfile();               // the first time, there is no line yet
    tickStatusClock();
  }

  // "12s ago" and the refresh cooldown. Only changes text; it never asks anyone for anything.
  function tickStatusClock() {
    const age = $("tkStatusAge"), button = $("tkStatusRefresh");
    if (!age || !button || !statusView) return;
    const s = statusView;
    if (s.loading || !s.receivedAt || !s.data) { age.textContent = ""; button.disabled = !!s.loading; return; }

    const checkedAt = s.receivedAt - ((s.data && s.data.age_seconds) || 0) * 1000;
    const seconds = Math.max(0, Math.round((Date.now() - checkedAt) / 1000));
    age.textContent = seconds < 2 ? "now" : seconds < 90 ? seconds + "s ago" : Math.round(seconds / 60) + "m ago";
    button.disabled = Date.now() - s.receivedAt < REFRESH_COOLDOWN_MS;
  }
  setInterval(tickStatusClock, 1000);

  // ------------------------------------------------------------ linked Discord

  // Asks the website for the Discord a player linked. Only called when you click a player. An answer is kept for an hour.
  async function loadDiscord(name) {
    const lower = name.toLowerCase();
    const kept = discordKept.get(lower);
    if (kept && Date.now() - kept.receivedAt < DISCORD_TTL_MS) {
      discordView = { name, data: kept.data, loading: false };
      return refreshDiscordBox();
    }

    discordView = { name, data: null, loading: true };
    refreshDiscordBox();

    let data = null, failure = "";
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 12000);
    try {
      const res = await fetch("/tracker/discord?name=" + encodeURIComponent(name), { signal: abort.signal });
      if (!res.ok) throw new Error("HTTP " + res.status);
      data = await res.json();
    } catch (e) {
      failure = e.name === "AbortError" ? "It took too long to answer." : "The website could not be reached.";
    } finally { clearTimeout(timer); }

    if (data && data.state !== "error") discordKept.set(lower, { data, receivedAt: Date.now() });
    if (!discordView || discordView.name.toLowerCase() !== lower) return;       // you moved on to someone else
    const good = data && data.state !== "error" ? data : null;
    discordView = { name, data: good, loading: false, failure: failure || (data && data.state === "error" ? data.error.message : "") };
    refreshDiscordBox();
  }

  // Their Discord, only when one is linked. Nothing at all otherwise, so it never takes room.
  function discordHtml(p) {
    const d = discordView;
    const linked = d && d.name.toLowerCase() === p.username.toLowerCase() && d.data && d.data.state === "linked";
    return '<span id="tkDiscordBox">' + (linked ? '<span class="tk-discord" title="Linked Discord">Discord: <b>' + esc(d.data.discord) + "</b></span>" : "") + "</span>";
  }

  function refreshDiscordBox() {
    if (!profile) return;
    const box = $("tkDiscordBox");
    if (box) box.outerHTML = discordHtml(profile);
  }

  // ---------------------------------------------------------------------- chat

  // What they said, newest first: the time, the channel if it was not public chat, and the message.
  function chatHtml(p) {
    const chats = p.chats || [];
    if (!chats.length) {
      return '<div class="empty">No chat from ' + esc(p.username) + " yet. What they say while you are in the same lobby shows up here.</div>";
    }
    return chatList(chats, p.chat_count);
  }

  // A count line and the messages, newest first. Used for a player's chat and for your own.
  function chatList(chats, total) {
    const accounts = new Set(chats.map(c => c.account).filter(Boolean));
    return '<div class="tk-sub">' + plural(total, "message") +
             (total > chats.length ? " (newest " + chats.length + " shown)" : "") + "</div>" +
      '<ul class="tk-chat">' + chats.map(c =>
        '<li><div class="tk-chead"><span class="tk-ctime" title="' + esc(fmtFull(c.said_at)) + '">' + esc(fmtShort(c.said_at)) + "</span>" +
        (c.channel !== "Public" ? '<span class="tk-cchan ch-' + esc(c.channel.replace(/[^A-Za-z]/g, "")) + '">' + esc(c.channel) + "</span>" : "") +
        (c.world && c.world !== "Unknown" ? '<small class="tk-w">' + dot(c.world) + esc(c.world) + "</small>" : "") +
        (accounts.size > 1 ? "<small>" + esc(c.account) + "</small>" : "") + "</div>" +
        '<div class="tk-cmsg">' + esc(c.message) + "</div></li>").join("") + "</ul>";
  }

  // A player just said something. If their panel is open it gains the message straight away.
  function applyChat(ev) {
    if (!profile || profile.username.toLowerCase() !== String(ev.username).toLowerCase()) return;
    profile.chats = [{ said_at: ev.said_at, channel: ev.channel, world: ev.world, message: ev.message, account: ev.account || ownerLabel }]
      .concat(profile.chats || []).slice(0, 300);
    profile.chat_count = (profile.chat_count || 0) + 1;
    renderProfile();
  }

  // What you said, from the website. Shown on the account panel.
  async function loadMyChat() {
    const asking = owner;
    if (!asking) return;
    try {
      const data = await getJson("/tracker/mychat?owner=" + encodeURIComponent(asking));
      if (asking !== owner) return;
      myChat = { count: data.count, chats: data.chats };
      if (!profile && !profileName) showMe();
    } catch (e) { /* keep what is shown */ }
  }

  // You just said something. It is added to the list straight away (a repeat after a reconnect is skipped).
  function applyMyChat(ev) {
    if (!myChat) { loadMyChat(); return; }
    if (myChat.chats.some(c => c.said_at === ev.said_at && c.message === ev.message)) return;
    myChat.chats = [{ said_at: ev.said_at, channel: ev.channel, world: ev.world, message: ev.message, account: ev.account }].concat(myChat.chats).slice(0, 300);
    myChat.count++;
    if (!profile && !profileName) showMe();
  }

  function myChatHtml() {
    if (!myChat) return '<div class="empty">Loading…</div>';
    if (!myChat.count) return '<div class="empty">Nothing you said has been recorded yet. What you say in the game shows up here.</div>';
    return chatList(myChat.chats, myChat.count);
  }

  // ------------------------------------------------------------ account panel

  // The panel when no player is open: the account at a glance, and what you said.
  function showMe() {
    detailTicket++;
    profileName = null;
    profile = null;
    markRow();
    const tab = (id, label) => '<button class="tk-tab' + (meTab === id ? " on" : "") + '" data-metab="' + id + '">' + label + "</button>";
    const o = overview;
    const t = o ? o.tiers : {};
    const busiest = o && o.worlds.length ? o.worlds.slice().sort((a, b) => b.visits - a.visits)[0] : null;
    const summary =
      '<div class="tk-stats4">' +
        '<div class="tk-stat"><b>' + num(o && o.total_players) + "</b><span>players met</span></div>" +
        '<div class="tk-stat"><b>' + num(t.regular) + "</b><span>regulars" + (world ? " here" : "") + "</span></div>" +
        '<div class="tk-stat"><b>' + num(o && o.party_players) + "</b><span>partied with</span></div>" +
        '<div class="tk-stat"><b>' + num(o && o.worlds.length) + "</b><span>worlds</span></div></div>" +
      (busiest ? '<div class="tk-hint">You spend the most lobby visits in ' + dot(busiest.world) + "<b>" + esc(busiest.world) + "</b> (" + num(busiest.visits) + ").</div>" : "") +
      '<div class="tk-hint">Click a player in the list, or search a name, to see every meeting, their chat and their status. ' +
      "Sightings less than 4 hours after a meeting starts count as one meeting.</div>";
    $("tkDetailsBody").innerHTML =
      '<div class="tk-name">' + esc(ownerLabel || "You") + "</div>" +
      '<div class="tk-tabs">' + tab("summary", "Summary") + tab("chat", "My chat" + (myChat && myChat.count ? " <small>" + num(myChat.count) + "</small>" : "")) + "</div>" +
      (meTab === "chat" ? myChatHtml() : summary);
  }

  // Opens the tracker tab and shows a player. Used by alerts.
  function openPlayer(username) {
    if (window.showTab) window.showTab("tracker");
    showPlayer(username, true);
  }

  // ------------------------------------------------------------------------ search

  const input = $("tkSearch");
  const suggestBox = $("tkSuggest");
  let suggestTimer = null, suggestions = [], active = -1;

  function hideSuggest() {
    suggestBox.hidden = true;
    suggestions = [];
    active = -1;
  }

  function renderSuggest(list, query) {
    // A full username that is not among the matches can still be looked up: it may just not be in the database.
    const known = list.some(p => p.username.toLowerCase() === query.toLowerCase());
    const lookup = isValidName(query) && !known;
    suggestions = lookup ? list.concat([{ unknown: true, username: query }]) : list;
    active = -1;
    const lookupRow = lookup
      ? '<button class="tk-sg tk-sg-new" data-i="' + list.length + '"><span>Look up <b>' + esc(query) + "</b></span><small>not in your database</small></button>"
      : "";

    if (!list.length) {
      suggestBox.innerHTML = (lookup ? "" : '<div class="tk-sg-empty">No player matches "' + esc(query) + '".</div>') + lookupRow;
    } else {
      suggestBox.innerHTML = list.map((p, i) =>
        '<button class="tk-sg" data-i="' + i + '">' + "<span>" + esc(p.username) + "</span>" +
        "<small>" + (p.party_times ? '<span class="tk-sgparty">partied ' + p.party_times + "× · </span>" : "") +
        (p.chat_count ? plural(p.chat_count, "message") + " · " : "") +
        "met " + p.meetings + "× · " + esc(ago(p.last_seen)) + "</small></button>").join("") + lookupRow;
    }
    suggestBox.hidden = false;
  }

  async function suggest() {
    const query = input.value.trim();
    const ticket = ++suggestTicket;
    if (!query) { hideSuggest(); return; }
    try {
      const data = await getJson("/tracker/search?q=" + encodeURIComponent(query) + "&sightings=false&limit=8" + ownerParam());
      if (ticket === suggestTicket) renderSuggest(data.players, query);
    } catch (e) {
      if (ticket === suggestTicket) hideSuggest();
    }
  }

  function choose(p) {
    input.value = p.username;
    hideSuggest();
    showPlayer(p.username, true);
  }

  function setActive(i) {
    active = i;
    suggestBox.querySelectorAll(".tk-sg").forEach((b, n) => b.classList.toggle("active", n === i));
  }

  input.addEventListener("input", () => { clearTimeout(suggestTimer); suggestTimer = setTimeout(suggest, 180); });
  input.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown" && suggestions.length) { e.preventDefault(); setActive((active + 1) % suggestions.length); }
    else if (e.key === "ArrowUp" && suggestions.length) { e.preventDefault(); setActive((active - 1 + suggestions.length) % suggestions.length); }
    else if (e.key === "Escape") { hideSuggest(); }
    else if (e.key === "Enter") {
      e.preventDefault();
      if (suggestions.length) choose(suggestions[active >= 0 ? active : 0]);
      else if (input.value.trim()) suggest();
    }
  });
  suggestBox.addEventListener("mousedown", (e) => {
    const btn = e.target.closest(".tk-sg");
    if (btn) { e.preventDefault(); choose(suggestions[Number(btn.dataset.i)]); }
  });
  input.addEventListener("blur", () => setTimeout(hideSuggest, 120));

  // ------------------------------------------------------------------ clearing data

  // Deleting is two steps (choose, then confirm) because it cannot be undone.
  const clearBox = $("tkClearBox");
  let clearStep = 0;            // 0 closed, 1 choosing, 2 asking to confirm
  let clearChoice = "";         // the world to clear, or "" for every world
  let clearError = "", clearNote = "", clearTimer = null;

  function openClear() {
    if (owner === "all") {                                    // clearing is one account at a time, never everything at once
      clearNote = "Choose one account first: data is only cleared for one account at a time.";
      clearStep = 0;
      renderClear();
      return;
    }
    clearStep = 1;
    clearChoice = world;
    clearError = clearNote = "";
    renderClear();
    clearBox.scrollIntoView({ block: "nearest" });
  }

  function closeClear() {
    clearStep = 0;
    clearError = "";
    renderClear();
  }

  function renderClear() {
    clearBox.hidden = clearStep === 0 && !clearNote;
    const who = esc(ownerLabel || "this account");
    const worlds = overview ? overview.worlds : [];

    if (clearStep === 0) {
      clearBox.innerHTML = '<span class="tk-note">' + esc(clearNote) + "</span>";
    } else if (clearStep === 1) {
      const options = ['<option value="">All worlds</option>'].concat(worlds.map(w =>
        '<option value="' + esc(w.world) + '"' + (w.world.toLowerCase() === clearChoice.toLowerCase() ? " selected" : "") + ">" +
        esc(w.world) + " (" + w.players + " players)</option>"));
      clearBox.innerHTML =
        '<div class="tk-sub">Clear player data for ' + who + "</div>" +
        '<div class="tk-clearrow"><select id="tkClearWorld" aria-label="What to clear">' + options.join("") + "</select>" +
        '<button class="tk-btn tk-danger" data-clear="ask">Delete…</button>' +
        '<button class="tk-btn" data-clear="cancel">Cancel</button></div>' +
        (clearError ? '<div class="tk-error">' + esc(clearError) + "</div>" : "");
    } else {
      const listed = worlds.find(w => w.world.toLowerCase() === clearChoice.toLowerCase());
      const what = clearChoice
        ? "every player met in " + esc(clearChoice) + (listed ? " (" + listed.players + " players, " + listed.visits + " lobby visits)" : "")
        : "all players and lobby visits in every world";
      clearBox.innerHTML =
        '<div class="tk-warn">This deletes ' + what + " for " + who + ". It cannot be undone.</div>" +
        '<div class="tk-clearrow"><button class="tk-btn tk-sure" data-clear="do">Yes, delete</button>' +
        '<button class="tk-btn" data-clear="back">Back</button></div>';
    }
  }

  async function doClear() {
    const asking = owner, target = clearChoice;
    clearBox.querySelectorAll("button").forEach(b => { b.disabled = true; });

    try {
      const res = await fetch("/tracker/clear", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(target ? { owner: asking, world: target } : { owner: asking })
      });
      if (res.status === 403) throw new Error("Clearing only works on the PC that runs the website. Open http://127.0.0.1:8000 on that PC.");
      if (!res.ok) throw new Error("The website could not clear the data (HTTP " + res.status + ").");
      const done = await res.json();

      clearStep = 0;
      clearNote = "Deleted " + plural(done.players_removed, "player") + " and " + plural(done.visits_removed, "lobby visit") +
                  (target ? " in " + target : "") + ".";
      clearTimeout(clearTimer);
      clearTimer = setTimeout(() => { clearNote = ""; if (clearStep === 0) renderClear(); }, 8000);

      // A chosen world that no longer exists must not stay chosen.
      if (!target || target.toLowerCase() === world.toLowerCase()) { world = ""; store(KEY_WORLD, ""); }
      if (asking === owner) { rows = []; await refreshAll(); showMe(); }
    } catch (e) {
      clearStep = 1;
      clearError = e.message;
    }
    renderClear();
  }

  clearBox.addEventListener("click", (e) => {
    const action = e.target.closest("button[data-clear]");
    if (!action) return;
    const what = action.dataset.clear;
    if (what === "ask") { clearStep = 2; renderClear(); }
    else if (what === "back") { clearStep = 1; renderClear(); }
    else if (what === "cancel") closeClear();
    else if (what === "do") doClear();
  });
  clearBox.addEventListener("change", (e) => {
    if (e.target.id === "tkClearWorld") clearChoice = e.target.value;
  });
  $("tkClear").addEventListener("click", () => openClear());

  // ----------------------------------------------------------------- page wiring

  $("tkMap").addEventListener("click", (e) => {
    const tile = e.target.closest(".tk-tile");
    if (tile) chooseWorld(tile.dataset.world);
  });
  $("tkScope").addEventListener("click", (e) => {
    if (e.target.closest("button[data-world]")) chooseWorld("");
  });
  $("tkTiers").addEventListener("click", (e) => {
    const card = e.target.closest(".tk-tier");
    if (card) chooseTier(card.dataset.tier);
  });
  $("tkRows").addEventListener("click", (e) => {
    const row = e.target.closest(".tk-row");
    if (row) showPlayer(row.dataset.player, true);
  });
  $("tkSort").addEventListener("change", () => {
    sort = $("tkSort").value;
    store(KEY_SORT, sort);
    rows = [];
    loadList(false);
  });
  $("tkMore").addEventListener("click", () => loadList(true));

  $("tkDetailsBody").addEventListener("click", (e) => {
    if (e.target.closest("button[data-status-refresh]")) { if (profile) loadStatus(profile.username, true); return; }
    const tab = e.target.closest("button[data-ptab]");
    if (tab) { playerTab = tab.dataset.ptab; renderProfile(); return; }
    const meButton = e.target.closest("button[data-metab]");
    if (meButton) { meTab = meButton.dataset.metab; showMe(); return; }
    if (e.target.closest("button[data-close]")) { showMe(); return; }
    const tagButton = e.target.closest("button[data-tag]");
    if (tagButton) { setTag(tagButton.dataset.tag); return; }
    if (e.target.closest("button[data-find]")) { if (profile) findPlayer(profile.username); return; }
  });
  $("tkAlertLog").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-player]");
    if (btn) showPlayer(btn.dataset.player, true);
  });
  $("tkClearLog").addEventListener("click", () => { alertLog = []; store(KEY_LOG, "[]"); renderAlertLog(); });

  $("tkAlerts").checked = load(KEY_ALERTS) !== "off";
  $("tkAlerts").addEventListener("change", () => {
    store(KEY_ALERTS, $("tkAlerts").checked ? "on" : "off");
    if (!$("tkAlerts").checked) { alertQueue = []; }
  });

  let resizeTimer = null;
  window.addEventListener("resize", () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(renderMap, 120); });

  async function loadOwner(account) {
    const wanted = account.uuid || "all";        // "all": every account together
    // This tab and the page both ask for the first account when the page opens: the second ask loads nothing again.
    if (wanted === owner && (ownerState === "loading" || ownerState === "loaded")) { updateScanner(); return; }
    closeStream();
    owner = wanted;
    ownerLabel = account.uuid ? account.name : "All accounts";
    ownerState = "loading";
    overview = null;
    previews = {};
    rows = []; rowsTotal = 0;
    lastTotal = null;
    myChat = null;
    profileName = null; profile = null;
    renderTotal(0);
    clearStep = 0; clearNote = clearError = "";
    renderClear();
    setLive(false, "loading…");
    renderMap(); renderScope(); renderTiers(); renderList();
    showMe();
    updateScanner();
    loadMyChat();

    const data = await refreshAll();
    if (wanted !== owner) return;
    if (!data) { ownerState = ""; return; }      // it could not load: choosing the account again tries again
    ownerState = "loaded";
    startStream(data.latest_event_id);
  }

  // The page tells us when the account changes, and when this tab is shown.
  document.addEventListener("account-selected", (e) => loadOwner(e.detail));
  document.addEventListener("tab-changed", (e) => {
    if (e.detail !== "tracker") return;
    updateScanner();
    setTimeout(renderMap, 30);          // the map has no size while the tab is hidden
  });

  renderAlertLog();
  setInterval(updateScanner, 3000);
  const current = window.currentAccount && window.currentAccount();
  if (current) loadOwner(current);

  // For checking the page from the browser console.
  window.tracker = { fmtShort, fmtFull, worldColor, get world() { return world; }, get tier() { return tier; } };
})();
} catch (e) { console.error("tracker:", e); }
