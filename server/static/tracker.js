// Femboy Client website: Lobby Networth tab. Needs graph.js (GraphKit) loaded before it.

// ================================================================ tracker

try {
// Lobby Networth tab: a live graph of the SkyBlock worlds you have been in and the players you met.
//
// The graph is you in the middle, one node per world, and the players around their world.
// A player met in more than one world is joined to each of those worlds, so the same
// name showing up around two worlds is easy to see.
//
// It talks to /tracker/tree (history), /tracker/live (new players as they are seen),
// /tracker/search and /tracker/worlds. Only usernames, times and SkyBlock world names
// are shown. All times are displayed in America/Toronto; the server stores them in UTC.
//
// The world filter (chips above the graph) is one setting shared by the graph, the
// search and the player profile, and it is remembered between visits.
//
// The stream and the alerts keep running while the Coins tab is open, so an alert
// can appear wherever you are on the page.
(function () {
  "use strict";

  // ------------------------------------------------------------------ settings

  const TZ = "America/Toronto";
  const PLAYERS_PER_PAGE = 5000;    // players asked for at a time (the server's own cap on /tracker/tree).
  // A last-resort ceiling on auto-loading, so a runaway account cannot lock the tab up forever; ordinary
  // tracker sizes never come close. Everyone loads automatically — see LAYOUT_THROTTLE_MS for how that stays
  // smooth instead of laying the whole graph out again after every page.
  const MAX_AUTO_PLAYERS = 200000;
  // While pages are still arriving in the background, the expensive part (packing bubbles and redrawing the
  // canvas) runs at most this often instead of once per page, so loading thousands of players in a few big
  // pages does not re-pack and redraw everyone loaded so far, again, after every page. The first page and the
  // final page always draw immediately.
  const LAYOUT_THROTTLE_MS = 350;
  const BUBBLE_GAP = 5;             // clear space between two bubbles
  const BUBBLE_CELL = 60;           // the packing looks for neighbours in squares this big
  const ROOM_STEP = 100;             // a world's room grows in steps of this, so the layout is not redone for every player
  const ROOM_PAD = 30;              // spare room round every world
  const ME_CLEAR = 170;             // the smallest gap between you and the edge of a world's room
  const FLASH_MS = 5000;            // how long a new player stays highlighted
  const BATCH_MS = 60;              // live events arriving together are drawn together
  const ALERT_BATCH_MS = 1200;      // returning players arriving together become one alert
  const TOASTS_EACH = 3;            // up to this many are named individually, more are summarised
  const MAX_TOASTS = 5;
  const LOG_LIMIT = 100;
  const WORLD_REFRESH_MS = 500;     // after live events, the world counts and the total are refreshed
  const KEY_ALERTS = "femboyclient.tracker.alerts";
  const KEY_LOG = "femboyclient.tracker.alertLog";
  const KEY_WORLDS = "femboyclient.tracker.worlds";
  const KEY_PARTY = "femboyclient.tracker.partyGlow";
  const PARTY_BLUE = "#38a0ff";      // the glow round players you have partied with

  const COLORS = {
    me:       { background: "#ff4fa6", border: "#b8306e" },
    flashNew: { background: "#46c46a", border: "#2c8f4b" },
    flashOld: { background: "#ff9f43", border: "#c26a14" }
  };
  const MULTI_PINK = "#ff6f91";      // the ring round a player met in more than one world
  const MEET_HUE = 45;               // players get brighter and more golden the more you have met them
  const MEET_FULL = 16;              // this many meetings (or more) is the brightest

  // Every world gets its own colour, picked to look as different from the others as possible.
  // The colours below were chosen by measuring how different they look to the eye (CIE Lab),
  // so the first ten stay clearly apart. Blue is left for Parties, and yellow for you.
  // A world that is not listed takes the next spare colour, and once those run out the
  // colour that is furthest from all the others. It keeps its colour (remembered in the browser).
  const FIXED_WORLD_COLORS = {
    "Hub": "#16f316", "The End": "#f316f3", "Dungeons": "#f31659", "Crystal Hollows": "#16f3c7",
    "Dwarven Mines": "#f35916", "Private Island": "#f17ece", "Dungeon Hub": "#c3f17e",
    "Unknown": "#7a8599", "Parties": PARTY_BLUE
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

    const used = Object.values(FIXED_WORLD_COLORS).concat(Object.values(assignedColors), COLORS.me.background);
    const color = SPARE_COLORS.find(c => !used.includes(c)) || generatedColor(used);
    assignedColors[name] = color;
    store(KEY_WORLD_COLORS, JSON.stringify(assignedColors));
    return color;
  }

  // The colour of a player: dim when you have met them once, brighter and more golden every time after.
  // A player met in several worlds gets a pink ring; one you have partied with gets a blue ring and glow.
  function meetColor(times) {
    const t = Math.min(1, Math.log2(Math.max(1, times)) / Math.log2(MEET_FULL));   // 1, 2, 4, 8, 16 meetings
    const sat = Math.round(12 + 88 * t), light = Math.round(30 + 46 * t);
    return {
      fill: "hsl(" + MEET_HUE + ", " + sat + "%, " + light + "%)",
      edge: "hsl(" + MEET_HUE + ", " + sat + "%, " + Math.max(12, light - 16) + "%)"
    };
  }

  const $ = (id) => document.getElementById(id);

  // ------------------------------------------------------------------- helpers

  // "Sep 21, 3:41 PM" in Toronto time. The year is added for other years.
  function fmtShort(iso) {
    const when = new Date(iso);
    const opts = { timeZone: TZ, month: "short", day: "numeric", hour: "numeric", minute: "2-digit" };
    const year = (d) => d.toLocaleString("en-US", { timeZone: TZ, year: "numeric" });
    if (year(when) !== year(new Date())) opts.year = "numeric";
    return when.toLocaleString("en-US", opts);
  }

  async function getJson(url) {
    const res = await fetch(url);
    if (!res.ok) throw new Error(url + " -> HTTP " + res.status);
    return res.json();
  }

  function store(key, value) { try { localStorage.setItem(key, value); } catch (e) { /* private mode */ } }
  function load(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }

  // --------------------------------------------------------------------- state

  const nodes = new GraphKit.DataSet();
  const edges = new GraphKit.DataSet();
  let network = null;

  let owner = null;                 // the account being shown
  let hasMore = false;
  let nextBefore = null;            // where "Load more players" continues from
  let loadTicket = 0, detailTicket = 0, suggestTicket = 0;
  let fitted = false;

  // The world filter. Empty means every world. Names are as the server spells them.
  let filterWorlds = [];
  try { filterWorlds = JSON.parse(load(KEY_WORLDS) || "[]").filter(w => typeof w === "string"); } catch (e) { filterWorlds = []; }
  let worldsList = [];              // [{ world, players, visits }] from /tracker/worlds
  let totalPlayers = 0;             // different players met in any world, from /tracker/worlds
  let lastTotal = null;             // the total before the latest refresh (null until the first one arrives)
  let partyPlayers = 0;             // different players you have partied with, from /tracker/worlds
  let partyGlow = load(KEY_PARTY) !== "off";   // glow blue round players you have partied with
  let plusAmount = 0, plusTimer = null;
  let worldTimer = null;
  let profileName = null;           // the player whose profile is open
  let profile = null;               // what was loaded for them: { p, filtered }
  let playerTab = "details";        // "details" or "chat". Kept as you move from player to player.
  let statusView = null;            // the Hypixel status card: { name, data, error, loading, receivedAt }
  let meTab = "summary";            // the middle node's panel: "summary" or "chat" (what you said)
  let myChat = null;                // { count, chats } for the account shown, or null until loaded
  const DISCORD_TTL_MS = 3600000;   // matches the website's cache: a Discord name is asked for once an hour
  const discordKept = new Map();    // lower-case name -> { data, receivedAt }
  let discordView = null;           // { name, data, loading, failure } for the player whose panel is open
  const statusKept = new Map();     // lower-case name -> { data, receivedAt }, so a second click within 45 s asks nothing
  const STATUS_TTL_MS = 45000;      // matches the website's cache
  const REFRESH_COOLDOWN_MS = 10000;// the Refresh button waits this long after a lookup
  let panelIsMe = true;             // the side panel shows your summary (so it is kept up to date)

  // The layout. Every world (and the Parties branch) is a "branch". A player sits round the
  // branch they were first met in, their "home"; their links to other worlds are drawn faint.
  const branchOrder = [];           // branch ids in the order they appeared, so nothing swaps places
  const members = new Map();        // branch id -> the player node ids whose home it is, in order
  const home = new Map();           // player node id -> the branch id it sits round
  const spots = new Map();          // node id -> { x, y }, where the layout last put it
  const worldPlayers = new Map();   // world node id -> Set of player node ids
  const playerWorlds = new Map();   // player node id -> Set of world node ids

  let layoutTimer = null;           // the pending throttled layout, if one is waiting to run

  let stream = null, reconnectTimer = null, lastEventId = 0;
  let pendingEvents = [], batchTimer = null;
  const flashTimers = new Map();

  let alertQueue = [], alertTimer = null;
  let alertLog = [];
  try { alertLog = JSON.parse(load(KEY_LOG) || "[]"); } catch (e) { alertLog = []; }

  // ------------------------------------------------------------- the world filter

  function inFilter(world) {
    return filterWorlds.length === 0 || filterWorlds.some(w => w.toLowerCase() === String(world).toLowerCase());
  }

  function worldParams() {
    return filterWorlds.map(w => "&world=" + encodeURIComponent(w)).join("");
  }

  function saveFilter() { store(KEY_WORLDS, JSON.stringify(filterWorlds)); }

  // Party information is per account, so searches say which account they are for.
  function ownerParam() { return owner ? "&owner=" + encodeURIComponent(owner) : ""; }

  // Adds or removes a world from the filter. Empty name means "All worlds".
  function toggleWorld(name) {
    if (!name) {
      filterWorlds = [];
    } else if (inFilter(name) && filterWorlds.length) {
      filterWorlds = filterWorlds.filter(w => w.toLowerCase() !== name.toLowerCase());
    } else {
      filterWorlds = filterWorlds.concat(name);
    }
    saveFilter();
    onFilterChanged();
  }

  async function onFilterChanged() {
    renderWorldBar();
    await reloadGraph();
    if (profileName) showPlayer(profileName);      // the profile follows the same filter
    else showMe();
  }

  function renderWorldBar() {
    const known = worldsList.map(w => w.world);
    const extra = filterWorlds.filter(w => !known.some(k => k.toLowerCase() === w.toLowerCase()));   // chosen but not seen for this account
    const chips = worldsList.concat(extra.map(w => ({ world: w, players: 0 })));

    $("tkWorlds").innerHTML =
      '<button class="tk-wchip' + (filterWorlds.length === 0 ? " on" : "") + '" data-world="">All worlds</button>' +
      chips.map(w =>
        '<button class="tk-wchip' + (filterWorlds.length && inFilter(w.world) ? " on" : "") + '" data-world="' + esc(w.world) + '">' +
        '<i style="background:' + esc(worldColor(w.world)) + '"></i>' + esc(w.world) +
        " <small>" + w.players + "</small></button>").join("") +
      '<span class="tk-wsep"></span>' +
      '<button class="tk-wchip tk-partychip' + (partyGlow ? " on" : "") + '" data-party="1" ' +
        'title="Glow blue round every player you have been in a party with, in any world">' +
        '<i class="glow"></i>Partied with <small>' + partyPlayers + "</small></button>";
  }

  // Turns the blue glow on or off for everyone drawn.
  function toggleParty() {
    partyGlow = !partyGlow;
    store(KEY_PARTY, partyGlow ? "on" : "off");
    renderWorldBar();
    nodes.update(nodes.get({ filter: n => n.type === "player" })
      .map(n => Object.assign({ id: n.id }, playerStyle(n.times, n.worlds, n.party))));
    renderLegend();
  }

  // The "Players met" counter. It flashes when the number goes up.
  function renderTotal() {
    const box = $("tkTotal");
    const shown = totalPlayers.toLocaleString("en-US");
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

  async function refreshWorlds() {
    if (!owner) return;
    const asking = owner;
    try {
      const data = await getJson("/tracker/worlds?owner=" + encodeURIComponent(asking));
      if (asking !== owner) return;
      worldsList = data.worlds;
      totalPlayers = data.total_players;
      partyPlayers = data.party_players || 0;
      // The first answer for an account is just the starting total. Later increases are new players.
      if (lastTotal !== null && totalPlayers > lastTotal) showPlus(totalPlayers - lastTotal);
      lastTotal = totalPlayers;
      renderTotal();
      renderWorldBar();
    } catch (e) { /* keep the last list */ }
  }

  function renderLegend() {
    const seen = nodes.get({ filter: n => n.type === "world" }).map(n => n.world);
    $("tkLegend").innerHTML =
      '<span><i style="background:' + COLORS.me.background + '"></i>You</span>' +
      seen.map(w => '<span><i style="background:' + esc(worldColor(w)) + '"></i>' + esc(w) + "</span>").join("") +
      (nodes.get("party") ? '<span><i style="background:' + PARTY_BLUE + '"></i>Parties</span>' : "") +
      '<span><i class="gradbar"></i>Met once → many times</span>' +
      '<span><i class="ring"></i>In more than one world</span>' +
      (partyGlow ? '<span><i class="glow"></i>Partied with you</span>' : "");
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
    box.title = "Set with /scanner true, /scanner true <world> or /scanner false in the game";
  }

  // ------------------------------------------------------------------ the graph

  // The graph is drawn by the graph engine above. A selected bubble grows and pushes its neighbours aside, and
  // everything stays smooth however many players there are.
  function ensureNetwork() {
    if (network) return;
    if (typeof GraphKit === "undefined") {
      overlay("The graph could not be loaded.");
      return;
    }
    network = new GraphKit.FastNetwork($("tkGraph"), nodes, edges, {});
    network.on("click", onGraphClick);
    updateStats();
  }

  function overlay(text) {
    let box = document.querySelector("#tkGraph .tk-overlay");
    if (!text) { if (box) box.remove(); return; }
    if (!box) {
      box = document.createElement("div");
      box.className = "tk-overlay";
      $("tkGraph").appendChild(box);
    }
    box.textContent = text;
  }

  function meNode(name) {
    return { id: "me", type: "me", label: name, title: name, size: 34, x: 0, y: 0, fixed: true, borderWidth: 6,
             color: { background: "#12151c", border: COLORS.me.background, highlight: { background: "#222836", border: COLORS.me.background } },
             font: { size: 22, color: "#e6e9ef", strokeWidth: 4, strokeColor: "#12151c" } };
  }

  // The size of a world node grows a little with the players around it.
  function worldSize(players) { return 18 + Math.min(Math.sqrt(players) * 1.3, 14); }

  function worldNode(w, pos) {
    const c = worldColor(w.world);
    return { id: w.id, type: "world", world: w.world, players: w.players, visits: w.visits, label: w.world,
             size: worldSize(w.players), title: w.world, borderWidth: 5,
             color: { background: "#12151c", border: c, highlight: { background: "#222836", border: c }, hover: { background: "#222836", border: c } },
             x: pos.x, y: pos.y, font: { size: 17, color: "#e6e9ef", strokeWidth: 4, strokeColor: "#12151c" } };
  }

  // A branch is drawn in its world's colour, from you to the world and on to its players.
  // A faint edge joins a player to a branch that is not their home (someone met in two worlds),
  // so those links are there to find but do not cross the graph in bold lines.
  function edge(from, to, world, faint) {
    const e = { id: from + ">" + to, from, to, color: { color: worldColor(world), opacity: faint ? 0.16 : 0.45 } };
    if (faint) e.dashes = [3, 7];
    return e;
  }

  // Brighter and more golden the more you have met them. A pink ring means more than one world.
  // A player you have been in a party with also gets a blue ring and glow (when that is switched on).
  const styleCache = new Map();        // a handful of distinct looks, shared between nodes (nothing edits them in place)

  function playerStyle(times, worlds, party) {
    const glow = partyGlow && party > 0;
    const key = times + "|" + (worlds > 1) + "|" + glow;
    let style = styleCache.get(key);
    if (!style) { style = buildPlayerStyle(times, worlds, glow); styleCache.set(key, style); }
    return style;
  }

  function buildPlayerStyle(times, worlds, glow) {
    const c = meetColor(times);
    const ring = glow ? PARTY_BLUE : worlds > 1 ? MULTI_PINK : c.edge;
    // The bubble grows with how often you have met them: 1 time is small, 3 times about three times as wide, 10 times the biggest.
    const size = Math.min(40, 6 + 6.5 * Math.pow(Math.max(0, times - 1), 0.75)) + (worlds > 1 ? 1 : 0) + (glow ? 2 : 0);
    return {
      size, base: size,                // base is the size the layout uses, so a flash or a selection never moves anything
      borderWidth: glow ? 3 : worlds > 1 ? 2 : 1,
      color: { background: c.fill, border: ring, highlight: { background: c.fill, border: ring }, hover: { background: c.fill, border: ring } },
      shadow: glow ? { enabled: true, color: "rgba(56, 160, 255, 0.95)", size: 22, x: 0, y: 0 } : false
    };
  }

  function playerNode(p, worlds, pos) {
    const party = p.party_times || 0;
    return Object.assign({
      id: p.id, type: "player", label: p.label, times: p.times_seen, worlds, party, font: { size: 10 },
      title: p.label + (p.times_seen ? " - met " + p.times_seen + (p.times_seen === 1 ? " time" : " times") : " - only met in a party") +
             (party ? ", partied " + party + (party === 1 ? " time" : " times") : ""),
      x: pos.x, y: pos.y
    }, playerStyle(p.times_seen, worlds, party));
  }

  // The "Parties" branch: everyone you have been in a party with, like a world node.
  function partyNode(n, pos) {
    return Object.assign(worldNode({ id: "party", world: "Parties", players: n.players, visits: 0 }, pos), { type: "party" });
  }

  // Records that a branch exists. Nothing is positioned here: layout() does that.
  function placeWorld(id) {
    if (!branchOrder.includes(id)) branchOrder.push(id);
    return { x: 0, y: 0 };
  }

  // Records which branch a player sits round (the first one they were met in).
  function placePlayer(branchId, playerId) {
    if (!home.has(playerId)) {
      home.set(playerId, branchId);
      if (!members.has(branchId)) members.set(branchId, []);
      members.get(branchId).push(playerId);
    }
    return { x: 0, y: 0 };
  }

  const packed = new Map();         // branch id -> { sig, spots: [{ id, dx, dy }], radius }, so unchanged branches are not packed again

  // Packs a branch's players as bubbles round its centre. The biggest (the players you have met
  // most) go first, so they sit closest to the middle, and the smaller ones fill in outward. Each one
  // takes the first free spot on a spiral where it does not touch another.
  function packBranch(id, centre) {
    const items = (members.get(id) || []).map((pid, order) => ({ pid, order, node: nodes.get(pid) }))
      .filter(i => i.node)
      .map(i => ({ id: i.pid, order: i.order, r: (i.node.base || i.node.size || 6) + 3 }));

    const sig = centre + "|" + items.map(i => i.id + ":" + i.r.toFixed(1)).join(",");
    const kept = packed.get(id);
    if (kept && kept.sig === sig) return kept;

    items.sort((a, b) => (b.r - a.r) || (a.order - b.order));      // biggest first, then in the order they arrived

    const grid = new Map();
    const cellKey = (gx, gy) => (gx + 5000) * 10000 + (gy + 5000);
    const biggest = items.length ? items[0].r : 0;
    const spots = [];
    let radius = centre, start = 0;

    const fits = (x, y, r) => {
      if (Math.hypot(x, y) < centre + r + BUBBLE_GAP) return false;               // clear of the world's own ring
      const reach = Math.ceil((r + biggest + BUBBLE_GAP) / BUBBLE_CELL);
      const cx = Math.floor(x / BUBBLE_CELL), cy = Math.floor(y / BUBBLE_CELL);
      for (let gx = cx - reach; gx <= cx + reach; gx++) {
        for (let gy = cy - reach; gy <= cy + reach; gy++) {
          const cell = grid.get(cellKey(gx, gy));
          if (!cell) continue;
          for (const o of cell) if (Math.hypot(x - o.x, y - o.y) < r + o.r + BUBBLE_GAP) return false;
        }
      }
      return true;
    };

    for (const item of items) {
      let k = Math.max(0, start - 80);                    // small ones may fill gaps a little behind the frontier
      for (; k < 300000; k++) {
        const turn = k * 2.399963, out = centre + 6 + 5.5 * Math.sqrt(k);
        const x = Math.cos(turn) * out, y = Math.sin(turn) * out;
        if (!fits(x, y, item.r)) continue;

        const key = cellKey(Math.floor(x / BUBBLE_CELL), Math.floor(y / BUBBLE_CELL));
        if (!grid.has(key)) grid.set(key, []);
        grid.get(key).push({ x, y, r: item.r });
        spots.push({ id: item.id, dx: x, dy: y });
        radius = Math.max(radius, Math.hypot(x, y) + item.r);
        break;
      }
      start = Math.max(start, k);
    }

    const result = { sig, spots, radius };
    packed.set(id, result);
    return result;
  }

  // Lays out, retitles and re-counts the graph, but not more often than LAYOUT_THROTTLE_MS: while a run of
  // pages is arriving in the background, each one would otherwise re-pack and redraw every player loaded so
  // far, not just the new ones, which is what made loading a big tracker database feel slow. immediate=true
  // (the first page, "Load more players", and the last page of a run) skips the wait and draws right away.
  function scheduleLayout(immediate) {
    if (immediate) {
      if (layoutTimer) { clearTimeout(layoutTimer); layoutTimer = null; }
      layout();
      refreshWorldTitles();
      updateStats();
      return;
    }
    if (layoutTimer) return;
    layoutTimer = setTimeout(() => {
      layoutTimer = null;
      layout();
      refreshWorldTitles();
      updateStats();
    }, LAYOUT_THROTTLE_MS);
  }

  // Puts everything where it belongs. You are in the middle. Each branch gets a circle of room
  // big enough for its players, and the circles are spaced round a ring that is wide enough
  // that none of them touches another or comes near you. Players fill their circle in a spiral.
  // Only nodes whose spot changed are moved, so a node you dragged stays where you put it.
  function layout() {
    const ids = branchOrder.filter(id => nodes.get(id));
    if (!ids.length) return;

    const packs = ids.map(id => packBranch(id, (nodes.get(id).size || 22) + 8));
    const rooms = packs.map(p => Math.ceil((p.radius + ROOM_PAD) / ROOM_STEP) * ROOM_STEP);
    const span = (ring) => rooms.reduce((sum, room) => sum + 2 * Math.asin(Math.min(1, (room + 20) / ring)), 0);

    let ring = Math.max(300, ...rooms.map(room => room + ME_CLEAR));
    while (span(ring) > 2 * Math.PI && ring < 1e6) ring += 40;

    // Spread any spare angle evenly between the branches.
    const slack = (2 * Math.PI - span(ring)) / ids.length;
    const moves = [];
    const put = (id, x, y) => {
      const old = spots.get(id);
      if (!old || Math.abs(old.x - x) > 0.5 || Math.abs(old.y - y) > 0.5) {
        spots.set(id, { x, y });
        moves.push({ id, x, y, fresh: !old });
      }
    };

    put("me", 0, 0);
    let angle = -Math.PI / 2;
    ids.forEach((id, i) => {
      const half = Math.asin(Math.min(1, (rooms[i] + 20) / ring));
      angle += slack / 2 + half;
      const cx = Math.cos(angle) * ring, cy = Math.sin(angle) * ring;
      angle += half + slack / 2;

      put(id, cx, cy);
      packs[i].spots.forEach(s => put(s.id, cx + s.dx, cy + s.dy));
    });

    if (moves.length) applyMoves(moves);
    fadeCrowdedEdges(ids);
  }

  // A world with hundreds of players has hundreds of lines, and at full strength they hide the bubbles.
  // The more players a branch has, the fainter its lines are. (They light up when you select a bubble.)
  function fadeCrowdedEdges(ids) {
    const changes = [];
    for (const id of ids) {
      const count = (members.get(id) || []).length;
      const want = count > 300 ? 0.05 : count > 80 ? 0.1 : count > 25 ? 0.22 : 0.45;
      for (const e of edges.get({ filter: x => x.from === id && !x.dashes })) {
        if (e.color && Math.abs((e.color.opacity || 0) - want) > 0.001) {
          changes.push({ id: e.id, color: { color: e.color.color, opacity: want } });
        }
      }
    }
    if (changes.length) edges.update(changes);
  }

  // Moves nodes to their spots. New nodes appear where they belong. Nodes that were already
  // drawn glide there, so when a world's room grows and the ring widens, the graph slides
  // smoothly instead of jumping.
  function applyMoves(moves) {
    const fresh = moves.filter(m => m.fresh || !network);
    const shifting = moves.filter(m => !m.fresh && network);
    if (fresh.length) nodes.update(fresh.map(m => ({ id: m.id, x: m.x, y: m.y })));

    if (shifting.length < 15) {
      if (shifting.length) nodes.update(shifting.map(m => ({ id: m.id, x: m.x, y: m.y })));
    } else {
      network.animateTo(shifting, 450, () => nodes.update(shifting.map(m => ({ id: m.id, x: m.x, y: m.y }))));
    }
  }

  function resetGraph() {
    nodes.clear();
    edges.clear();
    branchOrder.length = 0;
    packed.clear();
    members.clear();
    home.clear();
    spots.clear();
    worldPlayers.clear();
    playerWorlds.clear();
    flashTimers.forEach(t => clearTimeout(t));
    flashTimers.clear();
    if (layoutTimer) { clearTimeout(layoutTimer); layoutTimer = null; }
    fitted = false;
    overlay(null);
  }

  // Remembers that a player is around a world.
  function link(worldId, playerId) {
    if (!worldPlayers.has(worldId)) worldPlayers.set(worldId, new Set());
    worldPlayers.get(worldId).add(playerId);
    if (!playerWorlds.has(playerId)) playerWorlds.set(playerId, new Set());
    playerWorlds.get(playerId).add(worldId);
  }

  // Adds a page of history (the answer from /tracker/tree) to the graph. immediate=false (the default) lets
  // a run of background pages coalesce their layout and redraw instead of paying for it after every page;
  // pass true for a page that should appear right away (the first one, and "Load more players").
  function addTree(tree, immediate) {
    if (!nodes.get("me")) nodes.update(meNode(tree.nodes[0].label));

    const firstWorld = new Map();      // player node id -> the first world (or the party branch) it appears in
    for (const l of tree.links) {
      if (l.source === "party") {
        // Being in a party does not make someone "in more than one world", so it is kept apart.
        if (!worldPlayers.has("party")) worldPlayers.set("party", new Set());
        worldPlayers.get("party").add(l.target);
        if (!firstWorld.has(l.target)) firstWorld.set(l.target, "party");
        continue;
      }
      if (l.source.charAt(0) !== "w") continue;
      if (!firstWorld.has(l.target) || firstWorld.get(l.target) === "party") firstWorld.set(l.target, l.source);
      link(l.source, l.target);
    }

    // Each player sits round the world they were met in most (then the first alphabetically),
    // or round Parties if they were never met in a lobby.
    const homeOf = new Map();
    for (const p of tree.nodes.filter(n => n.type === "player")) {
      const met = Object.entries(p.worlds || {}).sort((x, y) => (y[1] - x[1]) || x[0].localeCompare(y[0]));
      homeOf.set(p.id, met.length ? "w:" + met[0][0] : "party");
    }

    const newNodes = [], newEdges = [];

    for (const n of tree.nodes.filter(n => n.type === "party")) {
      const old = nodes.get(n.id);
      if (old) newNodes.push({ id: n.id, players: Math.max(old.players, n.players) });
      else newNodes.push(partyNode(n, placeWorld(n.id)));
    }

    for (const w of tree.nodes.filter(n => n.type === "world")) {
      const old = nodes.get(w.id);
      if (old) newNodes.push({ id: w.id, players: Math.max(old.players, w.players), visits: w.visits });
      else newNodes.push(worldNode(w, placeWorld(w.id)));
    }
    for (const p of tree.nodes.filter(n => n.type === "player")) {
      const worlds = (playerWorlds.get(p.id) || new Set()).size;
      if (nodes.get(p.id)) {
        newNodes.push(Object.assign({ id: p.id, times: p.times_seen, worlds, party: p.party_times || 0 },
                                    playerStyle(p.times_seen, worlds, p.party_times || 0)));
      } else {
        newNodes.push(playerNode(p, worlds, placePlayer(homeOf.get(p.id) || firstWorld.get(p.id), p.id)));
      }
    }
    for (const l of tree.links) {
      const world = l.source === "me" ? l.target : l.source;
      const faint = l.source !== "me" && home.get(l.target) !== l.source;
      newEdges.push(edge(l.source, l.target, (nodes.get(world) || newNodes.find(n => n.id === world) || {}).world || "Unknown", faint));
    }

    nodes.update(newNodes);
    edges.update(newEdges);
    scheduleLayout(immediate);
    if (panelIsMe) showMe();               // the summary in the side panel follows the pages as they load
  }

  // Player counts change as people arrive: keep the world nodes' size and tooltip up to date.
  function refreshWorldTitles() {
    const updates = [];
    worldPlayers.forEach((set, id) => {
      const w = nodes.get(id);
      if (!w) return;
      const players = Math.max(w.players || 0, set.size);
      updates.push({ id, players, size: worldSize(players), title: w.world + " - " + players + (players === 1 ? " player" : " players") });
    });
    nodes.update(updates);
  }

  function updateStats() {
    const worlds = nodes.get({ filter: n => n.type === "world" }).length;
    const players = nodes.get({ filter: n => n.type === "player" }).length;
    const shared = nodes.get({ filter: n => n.type === "player" && n.worlds > 1 }).length;
    const branches = worlds + nodes.get({ filter: n => n.type === "party" }).length;
    const where = filterWorlds.length ? " in " + filterWorlds.join(", ") : "";
    $("tkStats").textContent = worlds + (worlds === 1 ? " world" : " worlds") + " · " +
                               players + (players === 1 ? " player" : " players") + " shown" + where +
                               (shared ? " · " + shared + " in more than one world" : "");
    $("tkMore").hidden = !hasMore;
    renderLinks();
    overlay(branches === 0
      ? (filterWorlds.length
          ? "No players in the chosen worlds yet. Pick another world, or All worlds."
          : "No players recorded yet. Turn the scanner on in the game with /scanner true, join a lobby, and the players will appear here.")
      : null);
    renderLegend();
  }

  // ------------------------------------------------------- connections between worlds

  // The world nodes for the chosen filter, matched ignoring capitalisation.
  function chosenWorldNodes() {
    const found = [];
    for (const name of filterWorlds) {
      const n = nodes.get({ filter: x => x.type === "world" && x.world.toLowerCase() === name.toLowerCase() })[0];
      if (n) found.push(n);
    }
    return found;
  }

  // The players who were met in every one of these worlds.
  function playersInAll(worldIds) {
    const out = [];
    playerWorlds.forEach((set, pid) => { if (worldIds.every(id => set.has(id))) out.push(pid); });
    return out;
  }

  // With two or more worlds chosen, says how many players connect them. The lines on the graph
  // are hard to count, so this counts them: someone met in both worlds is one connection.
  function renderLinks() {
    const box = $("tkLinks");
    const chosen = chosenWorldNodes();
    if (filterWorlds.length < 2 || chosen.length < 2) { box.hidden = true; box.innerHTML = ""; return; }

    const pairs = [];
    for (let i = 0; i < chosen.length; i++) {
      for (let j = i + 1; j < chosen.length; j++) {
        pairs.push({ a: chosen[i], b: chosen[j], n: playersInAll([chosen[i].id, chosen[j].id]).length });
      }
    }
    pairs.sort((x, y) => y.n - x.n);

    const dot = (w) => '<i style="background:' + esc(worldColor(w.world)) + '"></i>';
    let html = pairs.slice(0, 12).map(p =>
      '<button class="tk-link" data-a="' + esc(p.a.id) + '" data-b="' + esc(p.b.id) + '" title="Show who they are">' +
      dot(p.a) + esc(p.a.world) + ' <span class="tk-arrow">↔</span> ' + dot(p.b) + esc(p.b.world) +
      " <b>" + p.n.toLocaleString() + "</b><small>" + (p.n === 1 ? "player" : "players") + " in both</small></button>").join("");

    if (chosen.length > 2) {
      html += '<button class="tk-link" data-all="1" title="Show who they are"><span class="tk-arrow">All ' + chosen.length + " worlds</span> <b>" +
              playersInAll(chosen.map(w => w.id)).length.toLocaleString() + "</b><small>players in every one</small></button>";
    }
    if (hasMore) html += '<span class="tk-note">Still loading players, so these counts can go up.</span>';

    box.innerHTML = html;
    box.hidden = false;
  }

  // The names behind a count, in the side panel.
  function showConnections(worldIds) {
    detailTicket++;
    profileName = null;
    profile = null;
    panelIsMe = false;
    const worlds = worldIds.map(id => nodes.get(id)).filter(Boolean);
    const players = playersInAll(worldIds).map(pid => nodes.get(pid)).filter(Boolean)
      .sort((a, b) => a.label.toLowerCase().localeCompare(b.label.toLowerCase()));

    $("tkDetailsBody").innerHTML =
      '<div class="tk-name">Connections</div>' +
      '<dl class="tk-kv"><dt>Worlds</dt><dd>' + worlds.map(w => '<span class="tk-w"><i style="background:' + esc(worldColor(w.world)) + '"></i>' + esc(w.world) + "</span>").join("<br>") +
      "</dd><dt>Players in every one</dt><dd>" + players.length.toLocaleString() + "</dd></dl>" +
      '<div class="tk-sub">Players (' + players.length.toLocaleString() + ")</div>" +
      (players.length
        ? '<div class="tk-chips">' + players.map(p => '<button class="tk-chip" data-node="' + esc(p.id) + '" data-player="' + esc(p.label) + '">' + esc(p.label) + "</button>").join("") + "</div>"
        : '<div class="empty">Nobody has been met in all of these worlds.</div>');
  }

  // A newly seen player flashes green, a returning one orange, then returns to normal.
  function flash(id, returning) {
    const node = nodes.get(id);
    if (!node) return;
    clearTimeout(flashTimers.get(id));
    nodes.update({ id, size: (node.base || 8) + 6, borderWidth: 3, color: returning ? COLORS.flashOld : COLORS.flashNew });
    flashTimers.set(id, setTimeout(() => {
      flashTimers.delete(id);
      const current = nodes.get(id);
      if (current) nodes.update(Object.assign({ id }, playerStyle(current.times, current.worlds, current.party)));
    }, FLASH_MS));
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
    stream.onopen = () => { setLive(true, "live"); refreshParty(); loadMyChat(); };
    stream.onmessage = (event) => {
      if (watching !== owner) return;
      const ev = JSON.parse(event.data);
      if (ev.kind === "party") { applyParty(ev); return; }   // party and chat events have their own ids
      if (ev.kind === "chat") { applyChat(ev); return; }
      if (ev.kind === "mychat") { applyMyChat(ev); return; }
      if (ev.id <= lastEventId) return;          // a repeat after a reconnect
      lastEventId = ev.id;
      pendingEvents.push(ev);
      if (!batchTimer) batchTimer = setTimeout(applyEvents, BATCH_MS);
    };
    stream.onerror = () => {
      // Reconnect from the newest event seen, so nothing is repeated or missed.
      setLive(false, "reconnecting…");
      closeStream();
      reconnectTimer = setTimeout(() => { if (watching === owner) startStream(lastEventId); }, 2000);
    };
  }

  // ------------------------------------------------------------------- party

  // Someone was just confirmed to be in your party. They go on the Parties branch (which is
  // made if it is new), their dot gets the glow, and the counts are refreshed. With a world
  // filter on, the branch is not shown, so only a player already drawn is updated.
  function applyParty(ev) {
    const pId = "p" + ev.player_id;
    const known = nodes.get(pId);
    const newNodes = [], newEdges = [];

    if (!filterWorlds.length) {
      if (!nodes.get("party")) {
        newNodes.push(partyNode({ players: 0 }, placeWorld("party")));
        newEdges.push(edge("me", "party", "Parties"));
      }
      if (!worldPlayers.has("party")) worldPlayers.set("party", new Set());
      worldPlayers.get("party").add(pId);
      if (known) {
        newNodes.push(Object.assign({ id: pId, party: ev.party_times }, playerStyle(known.times, known.worlds, ev.party_times)));
      } else {
        newNodes.push(playerNode({ id: pId, label: ev.username, times_seen: 0, party_times: ev.party_times }, 0, placePlayer("party", pId)));
      }
      newEdges.push(edge("party", pId, "Parties", home.get(pId) !== "party"));
    } else if (known) {
      newNodes.push(Object.assign({ id: pId, party: ev.party_times }, playerStyle(known.times, known.worlds, ev.party_times)));
    }

    nodes.update(newNodes);
    edges.update(newEdges);
    layout();
    refreshWorldTitles();
    updateStats();
    if (panelIsMe) showMe();
    if (profileName && profileName.toLowerCase() === String(ev.username).toLowerCase()) showPlayer(profileName);
    clearTimeout(worldTimer);
    worldTimer = setTimeout(refreshWorlds, 200);
  }

  // Everyone this account has partied with, from the website. Used when the live feed (re)connects,
  // in case a party event was missed while it was down.
  async function refreshParty() {
    const asking = owner;
    if (!asking) return;
    try {
      const data = await getJson("/tracker/party?owner=" + encodeURIComponent(asking));
      if (asking !== owner) return;
      for (const p of data.players) {
        const node = nodes.get("p" + p.id);
        const linked = filterWorlds.length || edges.get("party>p" + p.id);
        if (!node || node.party !== p.times || !linked) applyParty({ player_id: p.id, username: p.username, party_times: p.times });
      }
    } catch (e) { /* keep what is drawn */ }
  }

  // Draws the events that arrived together in one go.
  function applyEvents() {
    batchTimer = null;
    const events = pendingEvents;
    pendingEvents = [];

    const newNodes = [], newEdges = [];
    const touched = [];
    let newWorld = false;

    for (const ev of events) {
      // Alerts are about everyone the scanner saw, whatever the graph is filtered to.
      if (ev.returning) queueAlert(ev);
      if (!worldsList.some(w => w.world.toLowerCase() === ev.world.toLowerCase())) newWorld = true;

      // The graph only shows what matches the world filter.
      if (!inFilter(ev.world)) continue;

      const wId = "w:" + ev.world, pId = "p" + ev.player_id;

      if (!nodes.get(wId) && !newNodes.some(n => n.id === wId)) {
        newNodes.push(worldNode({ id: wId, world: ev.world, players: 0, visits: 1 }, placeWorld(wId)));
        newEdges.push(edge("me", wId, ev.world));
      }
      link(wId, pId);
      const worlds = playerWorlds.get(pId).size;
      placePlayer(wId, pId);          // a new player sits round this world (a known one keeps its home)

      const pending = newNodes.find(n => n.id === pId);
      if (pending) {
        pending.times = ev.times_seen;
        pending.worlds = worlds;
        pending.party = ev.party_times || 0;
        Object.assign(pending, playerStyle(ev.times_seen, worlds, pending.party));
      } else if (nodes.get(pId)) {
        const party = ev.party_times || 0;
        newNodes.push(Object.assign({ id: pId, times: ev.times_seen, worlds, party }, playerStyle(ev.times_seen, worlds, party)));
      } else {
        newNodes.push(playerNode({ id: pId, label: ev.username, times_seen: ev.times_seen, party_times: ev.party_times }, worlds, { x: 0, y: 0 }));
      }
      newEdges.push(edge(wId, pId, ev.world, home.get(pId) !== wId));
      touched.push({ id: pId, returning: ev.returning });
    }

    nodes.update(newNodes);
    edges.update(newEdges);
    layout();
    refreshWorldTitles();
    updateStats();
    if (panelIsMe) showMe();

    for (const t of touched) flash(t.id, t.returning);

    // Counts in the world chips change as players arrive. Ask again once things settle.
    clearTimeout(worldTimer);
    worldTimer = setTimeout(refreshWorlds, newWorld ? 200 : WORLD_REFRESH_MS);

  }

  // ----------------------------------------------------------------------- alerts

  function alertsOn() { return $("tkAlerts").checked; }

  // "in Crystal Hollows" when the last meeting's world is known.
  function whereText(world) {
    return world && world !== "Unknown" ? " in " + world : "";
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
    const el = document.createElement("div");
    el.className = "toast";
    el.innerHTML = '<div class="toast-title"></div><div class="toast-body"></div>' +
                   (player ? '<button class="toast-view">View profile</button>' : "") +
                   '<button class="toast-x" aria-label="Dismiss">×</button>';
    el.querySelector(".toast-title").textContent = title;
    el.querySelector(".toast-body").textContent = body;
    el.querySelector(".toast-x").onclick = () => el.remove();
    const view = el.querySelector(".toast-view");
    if (view) view.onclick = () => { el.remove(); openPlayer(player); };

    box.appendChild(el);
    while (box.children.length > MAX_TOASTS) box.firstChild.remove();
    setTimeout(() => el.remove(), player ? 9000 : 12000);
  }

  function renderAlertLog() {
    const box = $("tkAlertLog");
    if (!alertLog.length) {
      box.innerHTML = '<div class="empty">No alerts yet. You are told here when a player you have met before shows up in your lobby, and when they were in another world.</div>';
      return;
    }
    box.innerHTML = alertLog.map(a => {
      const others = (a.before || []).filter(w => w.toLowerCase() !== String(a.now || "").toLowerCase() && w !== "Unknown");
      const detail = a.before
        ? (a.now && a.now !== "Unknown" ? "Now in " + a.now + ". " : "") +
          (others.length ? "Also seen in " + listWorlds(others) + ". " : "") + "Last seen " + fmtShort(a.previous)
        : "Last seen " + fmtShort(a.previous) + whereText(a.world);
      return '<div class="tk-alert"><button data-player="' + esc(a.name) + '">' + esc(a.name) + "</button>" +
        "<small>" + esc(detail) + " · alerted " + esc(fmtShort(new Date(a.t).toISOString())) + "</small></div>";
    }).join("");
  }

  // ---------------------------------------------------------------- details panel

  function worldChips(byWorld) {
    const entries = Object.entries(byWorld);
    return '<div class="tk-sub">Worlds</div><div class="tk-wchips">' +
      '<button class="tk-wchip' + (filterWorlds.length === 0 ? " on" : "") + '" data-world="">All</button>' +
      entries.map(([world, n]) =>
        '<button class="tk-wchip' + (filterWorlds.length && inFilter(world) ? " on" : "") + '" data-world="' + esc(world) + '">' +
        '<i style="background:' + esc(worldColor(world)) + '"></i>' + esc(world) + ": " + n + "</button>").join("") +
      "</div>";
  }

  // explicit is true only when you chose the player (a click on their dot, a search result, an
  // alert or a chip). That is the ONLY time their Hypixel status is looked up. Redrawing the panel
  // for any other reason (a filter change, a new party or chat event) never asks Hypixel.
  async function showPlayer(username, nodeId, explicit) {
    if (explicit) statusView = null;
    else if (statusView && statusView.name.toLowerCase() !== username.toLowerCase()) statusView = null;
    const ticket = ++detailTicket;
    profileName = username;
    profile = null;
    panelIsMe = false;
    const body = $("tkDetailsBody");
    body.innerHTML = '<div class="empty">Loading…</div>';

    let data;
    try {
      data = await getJson("/tracker/search?q=" + encodeURIComponent(username) + "&exact=true" + worldParams() + ownerParam());
    } catch (e) {
      if (ticket === detailTicket) body.innerHTML = '<div class="empty">Could not load that player.</div>';
      return;
    }
    if (ticket !== detailTicket) return;

    let p = data.players[0];
    if (!p && filterWorlds.length) {
      // Not met in the chosen worlds. Show them anyway, with the worlds they were met in.
      try {
        p = (await getJson("/tracker/search?q=" + encodeURIComponent(username) + "&exact=true" + ownerParam())).players[0];
        if (ticket !== detailTicket) return;
        if (p) { p.sightings = []; p.matching = 0; }
      } catch (e) { /* fall through */ }
    }
    if (!p && isValidName(username)) {                   // not in the database: still show who they are, and whether they are online
      profile = { p: unknownPlayer(username), filtered: false };
      renderProfile();
      if (explicit) { loadStatus(username, false); loadDiscord(username); }
      return;
    }
    if (!p) { body.innerHTML = '<div class="empty">No record of ' + esc(username) + ".</div>"; return; }

    profile = { p, filtered: filterWorlds.length > 0 };
    renderProfile();                      // your saved history first, so nothing waits on Hypixel
    if (explicit) { loadStatus(p.username, false); loadDiscord(p.username); }

    highlight(nodeId || "p" + p.id);
  }

  // The face of a player's skin, drawn by a public skin service from their username. Only a valid
  // username is ever put in the address. If the picture cannot load (offline, no such account) it just goes away.
  function faceHtml(name) {
    if (!isValidName(name)) return "";
    return '<img class="tk-face" alt="" width="56" height="56" src="https://mc-heads.net/avatar/' +
           encodeURIComponent(name) + '/112" onerror="this.remove()">';
  }

  // The panel for one player: a Details tab and a Chat tab.
  function renderProfile() {
    if (!profile) return;
    const { p } = profile;
    const chats = p.chat_count || 0;
    const tab = (id, label) => '<button class="tk-tab' + (playerTab === id ? " on" : "") + '" data-ptab="' + id + '">' + label + "</button>";

    $("tkDetailsBody").innerHTML =
      '<div class="tk-head">' + faceHtml(p.username) + '<div class="tk-name">' + esc(p.username) + "</div></div>" +
      '<div class="tk-tabs">' + tab("details", "Details") + tab("chat", "Chat" + (chats ? " <small>" + chats + "</small>" : "")) + "</div>" +
      (playerTab === "chat" ? chatHtml(p) : detailsHtml(p, profile.filtered));
    tickStatusClock();
  }

  // A player who is not in the database (searched for by name). Nothing is saved for them.
  function unknownPlayer(name) {
    return { username: name, unknown: true, times_seen: 0, first_seen: null, last_seen: null, matching: 0, party_times: 0,
             parties: [], chat_count: 0, chats: [], sightings: [], by_world: {} };
  }


  function detailsHtml(p, filtered) {
    if (p.unknown) {
      return statusHtml(p) + discordHtml(p) +
        '<div class="tk-unknown"><b>Not in your database.</b> You have never met, partied with or heard from ' + esc(p.username) +
        ". Looking them up does not save anything. If you meet them in a lobby while the scanner is on, they are added then.</div>";
    }
    const accounts = new Set((p.sightings || []).map(s => s.account));
    return statusHtml(p) + discordHtml(p) + '<div class="tk-counter"><b>' + p.times_seen + "</b><span>" + (p.times_seen === 1 ? "time" : "times") + " met</span></div>" +
      (filtered ? '<div class="tk-note">' + p.matching + " of them in " + esc(filterWorlds.join(", ")) + "</div>" : "") +
      '<dl class="tk-kv">' +
        "<dt>First seen</dt><dd>" + esc(fmtFull(p.first_seen)) + "</dd>" +
        "<dt>Last seen</dt><dd>" + esc(fmtFull(p.last_seen)) + "</dd>" +
        "<dt>Partied with you</dt><dd" + (p.party_times ? ' class="tk-partyrow"' : "") + ">" +
          (p.party_times ? "Yes, " + p.party_times + (p.party_times === 1 ? " time" : " times") : "Never") + "</dd>" +
      "</dl>" +
      (p.party_times
        ? '<div class="tk-sub">Every time you partied (' + p.party_times + ')</div><ul class="tk-list">' +
          p.parties.map(x => "<li>" + esc(fmtFull(x.joined_at)) + (accounts.size > 1 ? "<small>" + esc(x.account) + "</small>" : "") + "</li>").join("") + "</ul>"
        : "") +
      worldChips(p.by_world) +
      '<div class="tk-sub">' + (filtered
        ? "Encounters in " + esc(filterWorlds.join(", ")) + " (" + p.matching + " of " + p.times_seen + ")"
        : "Every time you met them (" + p.sightings.length + ")") + "</div>" +
      (p.sightings.length
        ? '<ul class="tk-list">' + p.sightings.map(s =>
            "<li>" + esc(fmtFull(s.seen_at)) +
            '<small class="tk-w"><i style="background:' + esc(worldColor(s.world)) + '"></i>' + esc(s.world) + "</small>" +
            (accounts.size > 1 ? "<small>" + esc(s.account) + "</small>" : "") + "</li>").join("") + "</ul>"
        : '<div class="empty">' + (filtered ? "Not met in the chosen worlds." : "Not met in a lobby yet, only in a party.") + "</div>");
  }

  // ------------------------------------------------------------ Hypixel status

  // Asks the website (never Hypixel directly, and the key never reaches the page) whether a player is
  // online. Called only from showPlayer for a click, and from the Refresh button.
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

  // ------------------------------------------------------------ linked Discord

  // Asks the website (which asks Hypixel, the same place SkyCrypt reads it from) for the Discord a player linked.
  // Only called when you click a player. An answer is kept for an hour.
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

  // One line under the status card: their Discord, or that they have none linked.
  function discordHtml(p) {
    const d = discordView;
    if (!d || d.name.toLowerCase() !== p.username.toLowerCase()) return "";
    let value;
    if (d.loading) value = '<span class="tk-dim">checking…</span>';
    else if (d.data && d.data.state === "linked") value = '<b class="tk-discord">' + esc(d.data.discord) + "</b>";
    else if (d.data) value = '<span class="tk-dim" title="Hypixel gives no way to tell these apart">None linked (or hidden)</span>';
    else value = '<span class="tk-dim">Unavailable. ' + esc(d.failure || "") + "</span>";
    return '<dl class="tk-kv tk-dline" id="tkDiscordBox"><dt>Discord</dt><dd>' + value + "</dd></dl>";
  }

  // Redraws only that line, so the rest of the panel is left alone.
  function refreshDiscordBox() {
    if (!profile || playerTab !== "details") return;
    const box = $("tkDiscordBox");
    if (box) box.outerHTML = discordHtml(profile.p);
    else renderProfile();               // the first time, there is no line yet
  }

  // The badge text and its look for an answer.
  function statusBadge(d) {
    if (d.state === "online" && d.skyblock) {
      // A world the website does not know yet is shown from its raw name, for example "foraging_2" as "Foraging 2".
      const raw = d.mode ? String(d.mode).replace(/_/g, " ").replace(/\b[a-z]/g, c => c.toUpperCase()) : "";
      return { cls: "online", text: "Online, SkyBlock: " + (d.world || raw || "world unknown") };
    }
    if (d.state === "online") return { cls: "online", text: "Online, other game", note: d.game || "" };
    return { cls: "offline", text: "Offline or status hidden",
             note: "Hypixel reports a player who hides their status as offline, so this may not be true. Your own last-seen record is next to it." };
  }

  // "Sep 21, 2026, 12:04 PM EDT in The End" from your own database.
  function lastSeenText(p) {
    if (p.unknown) return "";
    const s = (p.sightings || [])[0];
    if (s) return fmtFull(s.seen_at) + " in " + s.world;
    if (p.times_seen) return fmtFull(p.last_seen);
    return "";
  }

  function statusHtml(p) {
    const s = statusView;
    if (!s || s.name.toLowerCase() !== p.username.toLowerCase()) return "";

    let badge = "", note = "";
    if (s.data) {
      const b = statusBadge(s.data);
      badge = '<span class="tk-badge ' + b.cls + '">' + esc(b.text) + "</span>";
      note = b.note ? '<div class="tk-snote">' + esc(b.note) + "</div>" : "";
      if (s.failure) note += '<div class="tk-snote warn">Showing an older answer. ' + esc(s.failure) + "</div>";
    } else if (s.loading) {
      badge = '<span class="tk-badge loading">Checking status…</span>';
    } else {
      badge = '<span class="tk-badge problem">Status unavailable</span>';
      note = '<div class="tk-snote warn">' + esc(s.failure || "No answer.") + "</div>";
    }

    const seen = lastSeenText(p);
    return '<div class="tk-status" id="tkStatusBox">' +
      '<div class="tk-srow">' + badge +
        '<span class="tk-slast">' + (seen ? "Last seen " + esc(seen) : p.unknown ? "Not in your database" : "Not met in a lobby yet") + "</span></div>" + note +
      '<div class="tk-srow tk-sfoot"><span id="tkStatusAge" class="tk-sage"></span>' +
        '<button class="tk-btn" id="tkStatusRefresh" data-status-refresh="1">Refresh</button></div></div>';
  }

  // Redraws only the card, so the rest of the panel (and its scroll position) is left alone.
  function refreshStatusBox() {
    if (!profile || playerTab !== "details") return;
    const box = $("tkStatusBox");
    if (box) box.outerHTML = statusHtml(profile.p);
    else renderProfile();               // the first time, there is no card yet
    tickStatusClock();
  }

  // "checked 12 seconds ago" and the Refresh countdown. Only changes text; it never asks anyone for anything.
  function tickStatusClock() {
    const age = $("tkStatusAge"), button = $("tkStatusRefresh");
    if (!age || !button || !statusView) return;
    const s = statusView;

    if (s.loading) { age.textContent = "checking…"; button.disabled = true; button.textContent = "Refresh"; return; }
    if (!s.receivedAt) { age.textContent = ""; button.disabled = false; button.textContent = "Try again"; return; }

    const checkedAt = s.receivedAt - ((s.data && s.data.age_seconds) || 0) * 1000;
    const seconds = Math.max(0, Math.round((Date.now() - checkedAt) / 1000));
    age.textContent = "checked " + (seconds < 2 ? "just now" : seconds < 90 ? seconds + " seconds ago" : Math.round(seconds / 60) + " minutes ago");

    const wait = Math.ceil((REFRESH_COOLDOWN_MS - (Date.now() - s.receivedAt)) / 1000);
    button.disabled = wait > 0;
    button.textContent = wait > 0 ? "Refresh (" + wait + "s)" : "Refresh";
  }
  setInterval(tickStatusClock, 1000);

  // What they said, newest first: the time, the channel if it was not public chat, and the message.
  function chatHtml(p) {
    const chats = p.chats || [];
    if (!chats.length) {
      return '<div class="empty">No chat from ' + esc(p.username) + " yet. Whatever they say while the scanner is on and you are in the same lobby shows up here.</div>";
    }
    return chatList(chats, p.chat_count);
  }

  // A count line and the messages, newest first. Used for a player's chat and for your own.
  function chatList(chats, total) {
    const accounts = new Set(chats.map(c => c.account).filter(Boolean));
    return '<div class="tk-sub">' + total + (total === 1 ? " message" : " messages") +
             (total > chats.length ? " (newest " + chats.length + " shown)" : "") + "</div>" +
      '<ul class="tk-chat">' + chats.map(c =>
        '<li><div class="tk-chead"><span class="tk-ctime" title="' + esc(fmtFull(c.said_at)) + '">' + esc(fmtShort(c.said_at)) + "</span>" +
        (c.channel !== "Public" ? '<span class="tk-cchan ch-' + esc(c.channel.replace(/[^A-Za-z]/g, "")) + '">' + esc(c.channel) + "</span>" : "") +
        (c.world && c.world !== "Unknown" ? '<small class="tk-w"><i style="background:' + esc(worldColor(c.world)) + '"></i>' + esc(c.world) + "</small>" : "") +
        (accounts.size > 1 ? "<small>" + esc(c.account) + "</small>" : "") + "</div>" +
        '<div class="tk-cmsg">' + esc(c.message) + "</div></li>").join("") + "</ul>";
  }

  // A player just said something. If their panel is open it gains the message straight away.
  function applyChat(ev) {
    if (!profile || profile.p.username.toLowerCase() !== String(ev.username).toLowerCase()) return;
    const me = nodes.get("me");
    profile.p.chats = [{ said_at: ev.said_at, channel: ev.channel, world: ev.world, message: ev.message, account: ev.account || (me ? me.label : "") }]
      .concat(profile.p.chats || []).slice(0, 300);
    profile.p.chat_count = (profile.p.chat_count || 0) + 1;
    renderProfile();
  }

  // What you said, from the website. Shown on the middle node.
  async function loadMyChat() {
    const asking = owner;
    if (!asking) return;
    try {
      const data = await getJson("/tracker/mychat?owner=" + encodeURIComponent(asking));
      if (asking !== owner) return;
      myChat = { count: data.count, chats: data.chats };
      if (panelIsMe) showMe();
    } catch (e) { /* keep what is shown */ }
  }

  // You just said something. It is added to the list straight away (a repeat after a reconnect is skipped).
  function applyMyChat(ev) {
    if (!myChat) { loadMyChat(); return; }
    if (myChat.chats.some(c => c.said_at === ev.said_at && c.message === ev.message)) return;
    myChat.chats = [{ said_at: ev.said_at, channel: ev.channel, world: ev.world, message: ev.message, account: ev.account }].concat(myChat.chats).slice(0, 300);
    myChat.count++;
    if (panelIsMe) showMe();
  }

  function myChatHtml() {
    if (!myChat) return '<div class="empty">Loading…</div>';
    if (!myChat.count) return '<div class="empty">Nothing you said has been recorded yet. What you say in chat while the scanner is on shows up here.</div>';
    return chatList(myChat.chats, myChat.count);
  }

  // A world: how many players, and who has also been in another world.
  function showWorld(id) {
    const w = nodes.get(id);
    if (!w) return;
    detailTicket++;
    profileName = null;
    profile = null;
    panelIsMe = false;

    const listed = worldsList.find(x => x.world.toLowerCase() === w.world.toLowerCase());
    const players = edges.get({ filter: e => e.from === id })
      .map(e => nodes.get(e.to)).filter(Boolean)
      .sort((a, b) => a.label.toLowerCase().localeCompare(b.label.toLowerCase()));
    const shared = players.filter(p => p.worlds > 1);

    // A chip for each player. The tooltip lists the other worlds a shared player was met in.
    const chip = (p) => {
      const others = [...(playerWorlds.get(p.id) || [])].filter(x => x !== id).map(x => (nodes.get(x) || {}).world).filter(Boolean);
      return '<button class="tk-chip' + (partyGlow && p.party > 0 ? " party" : "") + '" data-node="' + esc(p.id) + '" data-player="' + esc(p.label) + '"' +
             (others.length ? ' title="Also in ' + esc(others.join(", ")) + '"' : "") + ">" + esc(p.label) + "</button>";
    };

    $("tkDetailsBody").innerHTML =
      '<div class="tk-name">World</div>' +
      '<dl class="tk-kv"><dt>World</dt><dd><span class="tk-w"><i style="background:' + esc(worldColor(w.world)) + '"></i>' + esc(w.world) + "</span></dd>" +
      "<dt>Players met</dt><dd>" + (listed ? listed.players : w.players) + "</dd>" +
      "<dt>Lobby visits</dt><dd>" + (listed ? listed.visits : w.visits) + "</dd></dl>" +
      (shared.length
        ? '<div class="tk-sub">Also seen in another world (' + shared.length + ')</div><div class="tk-chips">' + shared.map(chip).join("") + "</div>"
        : "") +
      '<div class="tk-sub">Players (' + players.length + ")</div>" +
      '<div class="tk-chips">' + players.map(chip).join("") + "</div>" +
      '<div class="tk-sub">Clear</div><button class="tk-btn tk-danger" data-clear-world="' + esc(w.world) + '">Clear this world…</button>';
  }

  // The Parties branch: everyone you have partied with, and how many times.
  function showParty() {
    const branch = nodes.get("party");
    if (!branch) return;
    detailTicket++;
    profileName = null;
    profile = null;
    panelIsMe = false;

    const players = edges.get({ filter: e => e.from === "party" })
      .map(e => nodes.get(e.to)).filter(Boolean)
      .sort((a, b) => (b.party - a.party) || a.label.toLowerCase().localeCompare(b.label.toLowerCase()));

    $("tkDetailsBody").innerHTML =
      '<div class="tk-name">Parties</div>' +
      '<dl class="tk-kv"><dt>Players partied with</dt><dd>' + (partyPlayers || players.length) + "</dd></dl>" +
      '<div class="tk-sub">Players (' + players.length + ")</div>" +
      '<div class="tk-chips">' + players.map(p =>
        '<button class="tk-chip' + (partyGlow ? " party" : "") + '" data-node="' + esc(p.id) + '" data-player="' + esc(p.label) + '">' +
        esc(p.label) + (p.party > 1 ? " ×" + p.party : "") + "</button>").join("") + "</div>";
  }

  function showMe() {
    detailTicket++;
    profileName = null;
    profile = null;
    panelIsMe = true;
    const worlds = nodes.get({ filter: n => n.type === "world" }).length;
    const players = nodes.get({ filter: n => n.type === "player" }).length;
    const shared = nodes.get({ filter: n => n.type === "player" && n.worlds > 1 }).length;
    const me = nodes.get("me");
    const tab = (id, label) => '<button class="tk-tab' + (meTab === id ? " on" : "") + '" data-metab="' + id + '">' + label + "</button>";
    const summary = meTab !== "chat";
    $("tkDetailsBody").innerHTML =
      '<div class="tk-name">' + esc(me ? me.label : "You") + "</div>" +
      '<div class="tk-tabs">' + tab("summary", "Summary") + tab("chat", "My chat" + (myChat && myChat.count ? " <small>" + myChat.count + "</small>" : "")) + "</div>" +
      (summary ? '<dl class="tk-kv"><dt>Worlds shown</dt><dd>' + worlds + "</dd><dt>Players shown</dt><dd>" + players +
      "</dd><dt>In more than one world</dt><dd>" + shared + "</dd><dt>Partied with</dt><dd>" + partyPlayers + "</dd></dl>" +
      '<div class="empty">Click a world to see who was there, or a player to see every time you met them. Players with a pink ring have been in more than one world.</div>' : myChatHtml());
  }

  function highlight(nodeId) {
    ensureNetwork();
    if (!network) return;
    if (nodes.get(nodeId)) {
      network.selectNodes([nodeId]);
      network.focusOn(nodeId, { scale: 1.3, animation: { duration: 500 } });
      $("tkNote").textContent = "";
    } else {
      network.unselectAll();
      $("tkNote").textContent = hasMore
        ? "That player is not in the part of the graph that is loaded. Use Load more players."
        : (filterWorlds.length ? "That player is not in the chosen worlds." : "");
    }
  }

  function onGraphClick(params) {
    if (!params.nodes.length) return;      // a click on empty space: the graph deselects, so the bubble shrinks back
    const node = nodes.get(params.nodes[0]);
    if (!node) return;
    if (node.type === "player") showPlayer(node.label, node.id, true);
    else if (node.type === "world") showWorld(node.id);
    else if (node.type === "party") showParty();
    else showMe();
  }

  // Opens the tracker tab and shows a player. Used by alerts.
  function openPlayer(username) {
    if (window.showTab) window.showTab("tracker");
    showPlayer(username, undefined, true);
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
      suggestBox.innerHTML = (lookup ? "" : '<div class="tk-sg-empty">No player matches "' + esc(query) + '"' +
        (filterWorlds.length ? " in the chosen worlds" : "") + ".</div>") + lookupRow;
    } else {
      suggestBox.innerHTML = list.map((p, i) =>
        '<button class="tk-sg" data-i="' + i + '">' + "<span>" + esc(p.username) + "</span>" +
        "<small>" + (p.party_times ? '<span class="tk-sgparty">partied ' + p.party_times + "× · </span>" : "") +
        (p.chat_count ? p.chat_count + (p.chat_count === 1 ? " message · " : " messages · ") : "") +
        "met " + (filterWorlds.length ? p.matching : p.times_seen) + "× · last " + esc(fmtShort(p.last_seen)) + "</small></button>").join("") + lookupRow;
    }
    suggestBox.hidden = false;
  }

  async function suggest() {
    const query = input.value.trim();
    const ticket = ++suggestTicket;
    if (!query) { hideSuggest(); return; }
    try {
      const data = await getJson("/tracker/search?q=" + encodeURIComponent(query) + "&sightings=false&limit=8" + worldParams() + ownerParam());
      if (ticket === suggestTicket) renderSuggest(data.players, query);
    } catch (e) {
      if (ticket === suggestTicket) hideSuggest();
    }
  }

  function choose(p) {
    input.value = p.username;
    hideSuggest();
    if (p.unknown) showPlayer(p.username, undefined, true);        // not in the database: the profile says so
    else showPlayer(p.username, "p" + p.id, true);
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

  function openClear(world) {
    if (owner === "all") {                                    // clearing is one account at a time, never everything at once
      clearNote = "Choose one account first: data is only cleared for one account at a time.";
      clearStep = 0;
      renderClear();
      return;
    }
    clearStep = 1;
    clearChoice = world || (filterWorlds.length === 1 ? filterWorlds[0] : "");
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
    const me = nodes.get("me");
    const who = esc(me ? me.label : "this account");

    if (clearStep === 0) {
      clearBox.innerHTML = '<span class="tk-note">' + esc(clearNote) + "</span>";
    } else if (clearStep === 1) {
      const options = ['<option value="">All worlds</option>'].concat(worldsList.map(w =>
        '<option value="' + esc(w.world) + '"' + (w.world.toLowerCase() === clearChoice.toLowerCase() ? " selected" : "") + ">" +
        esc(w.world) + " (" + w.players + " players)</option>"));
      clearBox.innerHTML =
        '<div class="tk-sub">Clear player data for ' + who + "</div>" +
        '<div class="tk-clearrow"><select id="tkClearWorld" aria-label="What to clear">' + options.join("") + "</select>" +
        '<button class="tk-btn tk-danger" data-clear="ask">Delete…</button>' +
        '<button class="tk-btn" data-clear="cancel">Cancel</button></div>' +
        '<div class="tk-note" style="margin-top:8px">Your coin data is not touched.</div>' +
        (clearError ? '<div class="tk-error">' + esc(clearError) + "</div>" : "");
    } else {
      const listed = worldsList.find(w => w.world.toLowerCase() === clearChoice.toLowerCase());
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
    const asking = owner, world = clearChoice;
    clearBox.querySelectorAll("button").forEach(b => { b.disabled = true; });

    try {
      const res = await fetch("/tracker/clear", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(world ? { owner: asking, world } : { owner: asking })
      });
      if (res.status === 403) throw new Error("Clearing only works on the PC that runs the website. Open http://127.0.0.1:8000 on that PC.");
      if (!res.ok) throw new Error("The website could not clear the data (HTTP " + res.status + ").");
      const done = await res.json();

      clearStep = 0;
      clearNote = "Deleted " + done.players_removed + (done.players_removed === 1 ? " player" : " players") +
                  " and " + done.visits_removed + (done.visits_removed === 1 ? " lobby visit" : " lobby visits") +
                  (world ? " in " + world : "") + ".";
      clearTimeout(clearTimer);
      clearTimer = setTimeout(() => { clearNote = ""; if (clearStep === 0) renderClear(); }, 8000);

      // A chosen world that no longer exists must not stay in the filter.
      filterWorlds = world ? filterWorlds.filter(w => w.toLowerCase() !== world.toLowerCase()) : [];
      saveFilter();
      if (asking === owner) {
        renderWorldBar();
        await Promise.all([reloadGraph(), refreshWorlds()]);
        showMe();
      }
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

  // The chips above the graph and the chips in a player profile are the same filter.
  for (const box of [$("tkWorlds"), $("tkDetailsBody")]) {
    box.addEventListener("click", (e) => {
      const chip = e.target.closest(".tk-wchip");
      if (!chip) return;
      if (chip.dataset.party) toggleParty();
      else toggleWorld(chip.dataset.world);
    });
  }
  $("tkDetailsBody").addEventListener("click", (e) => {
    const refresh = e.target.closest("button[data-status-refresh]");
    if (refresh) { if (profile) loadStatus(profile.p.username, true); return; }
    const tab = e.target.closest("button[data-ptab]");
    if (tab) { playerTab = tab.dataset.ptab; renderProfile(); return; }
    const meButton = e.target.closest("button[data-metab]");
    if (meButton) { meTab = meButton.dataset.metab; showMe(); return; }
    const clearing = e.target.closest("button[data-clear-world]");
    if (clearing) { openClear(clearing.dataset.clearWorld); return; }
    const chip = e.target.closest(".tk-chip");
    if (chip) showPlayer(chip.dataset.player, chip.dataset.node, true);
  });
  $("tkAlertLog").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-player]");
    if (btn) showPlayer(btn.dataset.player, undefined, true);
  });
  $("tkClearLog").addEventListener("click", () => { alertLog = []; store(KEY_LOG, "[]"); renderAlertLog(); });

  $("tkAlerts").checked = load(KEY_ALERTS) !== "off";
  $("tkAlerts").addEventListener("change", () => {
    store(KEY_ALERTS, $("tkAlerts").checked ? "on" : "off");
    if (!$("tkAlerts").checked) { alertQueue = []; }
  });

  $("tkLinks").addEventListener("click", (e) => {
    const b = e.target.closest(".tk-link");
    if (!b) return;
    if (b.dataset.all) showConnections(chosenWorldNodes().map(w => w.id));
    else showConnections([b.dataset.a, b.dataset.b]);
  });
  $("tkFit").addEventListener("click", () => { ensureNetwork(); if (network) network.fit({ animation: true }); });
  $("tkMore").addEventListener("click", loadMore);

  function treeUrl(extra) {
    return "/tracker/tree?owner=" + encodeURIComponent(owner) + "&players=" + PLAYERS_PER_PAGE + (extra || "") + worldParams();
  }

  async function loadMore() {
    const ticket = loadTicket;
    const button = $("tkMore");
    button.disabled = true;
    try {
      const tree = await getJson(treeUrl("&before=" + nextBefore));
      if (ticket !== loadTicket) return;
      hasMore = tree.has_more;
      if (tree.next_before !== null) nextBefore = tree.next_before;
      addTree(tree, true);
    } catch (e) {
      $("tkNote").textContent = "Could not load more players.";
    } finally {
      button.disabled = false;
    }
  }

  // Loads the most recently met players for the current account and filter, replacing what is drawn.
  // The live feed carries on underneath it.
  async function reloadGraph() {
    const ticket = ++loadTicket;
    resetGraph();
    hasMore = false;
    nextBefore = null;
    $("tkNote").textContent = "";

    let tree;
    try {
      tree = await getJson(treeUrl());
    } catch (e) {
      if (ticket === loadTicket) { setLive(false, "offline"); overlay("Could not load the tracker data."); }
      return null;
    }
    if (ticket !== loadTicket) return null;

    hasMore = tree.has_more;
    nextBefore = tree.next_before;
    addTree(tree, true);
    if (network) setTimeout(() => network.fit({ animation: true }), 300);
    loadRest(ticket);                       // the rest arrive in the background, a page at a time
    return tree;
  }

  // The first page is drawn straight away. The remaining players are fetched a page at a time, so
  // "All worlds" ends up with everyone, not just the newest ones.
  async function loadRest(ticket) {
    const count = () => nodes.get({ filter: n => n.type === "player" }).length;

    while (hasMore && ticket === loadTicket && count() < MAX_AUTO_PLAYERS) {
      $("tkNote").textContent = "Loading the rest of your players… " + count().toLocaleString() +
                                (totalPlayers ? " of " + totalPlayers.toLocaleString() : "");
      let tree;
      try {
        tree = await getJson(treeUrl("&before=" + nextBefore));
      } catch (e) {
        if (ticket === loadTicket) $("tkNote").textContent = "Could not load all players. Use Load more players to try again.";
        return;
      }
      if (ticket !== loadTicket) return;

      hasMore = tree.has_more;
      if (tree.next_before !== null) nextBefore = tree.next_before;
      addTree(tree, !hasMore);        // the last page draws right away instead of waiting on the throttle
      await new Promise(resolve => setTimeout(resolve, 0));      // let the browser draw between pages
    }

    if (ticket !== loadTicket) return;
    scheduleLayout(true);             // flush a still-pending throttled layout before fitting to it
    $("tkNote").textContent = hasMore ? "Showing the " + count().toLocaleString() + " newest players. Use Load more players for the rest." : "";
    if (network) network.fit({ animation: true });
  }

  // Follows a different account: its history, its worlds, and a fresh live feed.
  async function loadOwner(account) {
    closeStream();
    pendingEvents = [];
    owner = account.uuid || "all";               // "all": every account together
    worldsList = [];
    totalPlayers = 0;
    partyPlayers = 0;
    lastTotal = null;
    myChat = null;
    renderTotal();
    clearStep = 0; clearNote = clearError = "";
    renderClear();
    setLive(false, "loading…");
    showMe();
    updateScanner();
    refreshWorlds();
    loadMyChat();

    const tree = await reloadGraph();
    if (!tree || (account.uuid || "all") !== owner) return;
    showMe();
    startStream(tree.latest_event_id);
  }

  // The page tells us when the account changes, and when this tab is shown.
  document.addEventListener("account-selected", (e) => loadOwner(e.detail));
  document.addEventListener("tab-changed", (e) => {
    if (e.detail !== "tracker") return;
    ensureNetwork();
    updateScanner();
    if (!network) return;
    // The canvas has no size while the tab is hidden, so it is fitted once it is shown.
    setTimeout(() => {
      network.redraw();
      if (!fitted && nodes.length > 1) { network.fit(); fitted = true; }
    }, 50);
  });

  renderAlertLog();
  renderWorldBar();
  renderLegend();
  setInterval(updateScanner, 3000);
  const current = window.currentAccount && window.currentAccount();
  if (current) loadOwner(current);

  // The page may already be on this tab, for example when it was opened at #tracker.
  if (!$("tabTracker").hidden) document.dispatchEvent(new CustomEvent("tab-changed", { detail: "tracker" }));

  // For checking the page from the browser console.
  window.tracker = { nodes, edges, get network() { return network; }, fmtShort, fmtFull, worldColor,
                     get filter() { return filterWorlds.slice(); } };
})();
} catch (e) { console.error("tracker:", e); }
