// Femboy Client website: Player Finder tab.

// ================================================================ finder

try {
// Player Finder tab: look a player up, and have the mod hop lobbies until they are in the tab list.
//
// The status lookup uses the website's own /tracker/status endpoint (the Hypixel key stays on the
// website and never reaches this page). The hop search is /finder/start, /finder/stop and /finder/state.
// The mod is told the target through the website and reports back through it. This page never talks to
// the game directly, and it only asks Hypixel when you press Search or Refresh.
(function () {
  "use strict";

  const TZ = "America/Toronto";
  const POLL_MS = 1000;               // how often the running search is redrawn (the website, not Hypixel)
  const STATUS_TTL_MS = 45000;        // matches the website's cache
  const REFRESH_COOLDOWN_MS = 10000;  // Refresh waits this long after a lookup
  const GAME_WAIT_HINT_MS = 12000;    // after this long with no word from the game, say what to check

  const $ = (id) => document.getElementById(id);

  // "45s", "1m 12s"
  function duration(seconds) {
    seconds = Math.max(0, Math.round(seconds));
    return seconds < 60 ? seconds + "s" : Math.floor(seconds / 60) + "m " + (seconds % 60) + "s";
  }

  // ------------------------------------------------------------------- state

  let owner = null;                 // the account whose mod does the hopping
  let hoppable = {};                // world -> "/warp name", from /finder/worlds
  let maxHops = 30;
  let looked = null;                // the searched player: { name, data, receivedAt, failure, loading }
  let view = null;                  // the last /finder/state answer
  let pollTimer = null;
  let busy = false;                 // a start or stop request is in flight
  let lookTicket = 0;

  // ------------------------------------------------------------- the status card

  function badge(d) {
    if (d.state === "online" && d.skyblock) {
      const raw = d.mode ? String(d.mode).replace(/_/g, " ").replace(/\b[a-z]/g, c => c.toUpperCase()) : "";
      return { cls: "online", text: "Online, SkyBlock: " + (d.world || raw || "world unknown") };
    }
    if (d.state === "online") return { cls: "online", text: "Online, other game" + (d.game ? " (" + d.game + ")" : "") };
    return { cls: "offline", text: "Status hidden or offline" };
  }

  // Why the toggle cannot be used, or "" if it can.
  function toggleBlock() {
    if (!looked || !looked.data) return "Search for a player first.";
    if (!owner) return "No account is online to do the hopping. Start the game with the mod, or pick an account card.";
    const d = looked.data;
    if (d.state === "error") return "";                                          // shown as an error above, toggle stays off
    if (d.state !== "online") return "Status hidden or offline. The finder needs the player to be online in SkyBlock.";
    if (!d.skyblock) return "They are not in SkyBlock.";
    if (!d.world) return "Their SkyBlock world is not known, so there is no warp for it.";
    if (!hoppable[d.world]) return "There is no warp command for " + d.world + ".";
    return "";
  }

  function canToggle() {
    return !!looked && !!looked.data && looked.data.state === "online" && toggleBlock() === "";
  }

  function renderStatus() {
    const box = $("fdStatus");
    if (!looked) { box.innerHTML = ""; return; }

    if (looked.loading && !looked.data) {
      box.innerHTML = '<span class="fd-badge loading">Checking status…</span>';
    } else if (!looked.data) {
      box.innerHTML = '<span class="fd-badge problem">Status unavailable</span><div class="fd-note warn">' + esc(looked.failure || "No answer.") + "</div>" + refreshRow();
    } else if (looked.data.state === "error") {
      box.innerHTML = '<span class="fd-badge problem">Status unavailable</span><div class="fd-note warn">' + esc(looked.data.error.message) + "</div>" + refreshRow();
    } else {
      const b = badge(looked.data);
      const world = looked.data.skyblock && looked.data.world
        ? '<div class="fd-world">World: <b>' + esc(looked.data.world) + "</b>" +
          (hoppable[looked.data.world] ? ' <small>hops with ' + esc(hoppable[looked.data.world]) + "</small>" : "") + "</div>"
        : "";
      const note = looked.data.state !== "online"
        ? '<div class="fd-note">Hypixel reports a player who hides their status as offline, so the two cannot be told apart. The finder is not used for them.</div>'
        : "";
      box.innerHTML = '<span class="fd-badge ' + b.cls + '">' + esc(looked.data.name) + ": " + esc(b.text) + "</span>" + world + note + refreshRow();
    }
    tickClock();
  }

  function refreshRow() {
    return '<div class="fd-row fd-foot"><span id="fdAge" class="fd-age"></span>' +
           '<button class="fd-btn" id="fdRefresh">Refresh</button></div>';
  }

  // "checked 12 seconds ago" and the Refresh countdown. Only text: it never asks anyone for anything.
  function tickClock() {
    const age = $("fdAge"), button = $("fdRefresh");
    if (!age || !button || !looked) return;

    if (looked.loading) { age.textContent = "checking…"; button.disabled = true; return; }
    if (!looked.receivedAt) { age.textContent = ""; button.disabled = false; button.textContent = "Try again"; return; }

    const checkedAt = looked.receivedAt - ((looked.data && looked.data.age_seconds) || 0) * 1000;
    const seconds = Math.max(0, Math.round((Date.now() - checkedAt) / 1000));
    age.textContent = "checked " + (seconds < 2 ? "just now" : seconds < 90 ? seconds + " seconds ago" : Math.round(seconds / 60) + " minutes ago");

    const wait = Math.ceil((REFRESH_COOLDOWN_MS - (Date.now() - looked.receivedAt)) / 1000);
    button.disabled = wait > 0 || (view && view.active);
    button.textContent = wait > 0 ? "Refresh (" + wait + "s)" : "Refresh";
  }
  setInterval(tickClock, 1000);

  // Looks the player up. Called only from Search and Refresh.
  async function lookUp(name, forced) {
    const ticket = ++lookTicket;
    const kept = looked && looked.name.toLowerCase() === name.toLowerCase() ? looked : null;

    if (forced && kept && Date.now() - kept.receivedAt < REFRESH_COOLDOWN_MS) return;
    if (!forced && kept && kept.data && Date.now() - kept.receivedAt < STATUS_TTL_MS) { renderAll(); return; }   // a recent answer: no request

    looked = { name, data: kept ? kept.data : null, receivedAt: kept ? kept.receivedAt : 0, loading: true, failure: "" };
    renderAll();

    let data = null, failure = "";
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 12000);
    try {
      const res = await fetch("/tracker/status?name=" + encodeURIComponent(name) + (forced ? "&refresh=true" : ""), { signal: abort.signal });
      if (res.status === 422) throw new Error("That is not a valid Minecraft username.");
      if (!res.ok) throw new Error("HTTP " + res.status);
      data = await res.json();
    } catch (e) {
      failure = e.name === "AbortError" ? "It took too long to answer." : (e.message && e.message.startsWith("That") ? e.message : "The website could not be reached.");
    } finally { clearTimeout(timer); }

    if (ticket !== lookTicket) return;                       // a newer search replaced this one
    looked = { name, data: data || (kept ? kept.data : null), receivedAt: Date.now(), loading: false, failure };
    renderAll();
  }

  // ----------------------------------------------------------- the toggle and the search

  function renderToggle() {
    const toggle = $("fdToggle"), note = $("fdToggleNote");
    const running = !!(view && view.active);
    const block = toggleBlock();

    toggle.checked = running;
    toggle.disabled = busy || (!running && !canToggle());
    $("fdToggleRow").classList.toggle("on", running);

    if (running) note.textContent = "Turn it off to stop the search.";
    else if (block) note.textContent = block;
    else if (looked && looked.data && looked.data.state === "error") note.textContent = "The status could not be read, so the finder cannot start.";
    else note.textContent = "Ready. The mod will use " + hoppable[looked.data.world] + ", then /is, then " + hoppable[looked.data.world] + " again, until " + looked.data.name + " shows up in the tab list.";

    // While a search runs, the player being searched for cannot be changed.
    $("fdName").disabled = running;
    $("fdSearch").disabled = running;
    $("fdHint").hidden = !running;
  }

  function renderLive() {
    const box = $("fdLive");
    if (!view || !view.active) { box.hidden = true; return; }

    box.hidden = false;
    let line = "Searching for <b>" + esc(view.target) + "</b> in <b>" + esc(view.world) + "</b> · " + duration(view.elapsed_seconds);
    if (view.waiting_for_game) {
      line = view.elapsed_seconds * 1000 > GAME_WAIT_HINT_MS
        ? "Waiting for the game… Is Minecraft open with the mod, and are you on Hypixel?"
        : "Waiting for the game to pick this up…";
    }
    $("fdHops").textContent = view.hops;
    $("fdLiveText").innerHTML = line;
  }

  function renderResult() {
    const box = $("fdResult");
    const r = view && view.result;
    if (!r || (view && view.active)) { box.hidden = true; return; }

    box.hidden = false;
    box.className = "fd-result " + (r.outcome === "found" ? "found" : "notfound");
    const hops = r.hops + (r.hops === 1 ? " hop" : " hops");

    if (r.outcome === "found") {
      box.innerHTML = '<div class="fd-rmain">Found ' + esc(r.target) + " in " + esc(r.world) + " after " + hops +
                      " (" + esc(duration(r.elapsed_seconds)) + ")</div>" +
                      '<div class="fd-rsub">' + esc(fmtFull(r.finished_at)) + "</div>";
    } else {
      box.innerHTML = '<div class="fd-rmain">Not found after ' + hops + "</div>" +
                      '<div class="fd-rsub">Searched for ' + esc(r.target) + " in " + esc(r.world) + ". Reason: <b>" + esc(r.reason_text || "unknown") + "</b> · " +
                      esc(fmtFull(r.finished_at)) + "</div>";
    }
  }

  function renderAll() {
    renderStatus();
    renderToggle();
    renderLive();
    renderResult();
  }

  async function start() {
    if (!canToggle()) return;
    busy = true;
    $("fdError").textContent = "";
    renderToggle();
    try {
      const res = await fetch("/finder/start", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ owner, target: looked.data.name, world: looked.data.world })
      });
      if (res.status === 403) throw new Error("The finder can only be started from the PC that runs the website (open http://127.0.0.1:8000 there).");
      if (!res.ok) throw new Error("The website refused to start the search (HTTP " + res.status + ").");
      view = await res.json();
      beginPolling();
    } catch (e) {
      $("fdError").textContent = e.message;
    } finally {
      busy = false;
      renderAll();
    }
  }

  async function stop() {
    busy = true;
    renderToggle();
    try {
      const res = await fetch("/finder/stop", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ owner })
      });
      if (!res.ok) throw new Error("The website could not stop the search (HTTP " + res.status + ").");
      view = await res.json();
    } catch (e) {
      $("fdError").textContent = e.message;
    } finally {
      busy = false;
      renderAll();
    }
  }

  // ------------------------------------------------------------------ polling

  // While a search runs, the page asks the website for the hop count every second. This is the
  // website only, never Hypixel, and it stops as soon as the search ends.
  function beginPolling() {
    if (pollTimer || !owner) return;
    pollTimer = setInterval(poll, POLL_MS);
  }

  function stopPolling() {
    clearInterval(pollTimer);
    pollTimer = null;
  }

  async function poll() {
    const asking = owner;
    try {
      const res = await fetch("/finder/state?owner=" + encodeURIComponent(asking));
      if (!res.ok || asking !== owner) return;
      view = await res.json();
    } catch (e) { return; }

    renderAll();
    if (!view.active) stopPolling();
  }

  async function loadState() {
    stopPolling();
    view = null;
    if (!owner) { renderAll(); return; }

    const asking = owner;
    try {
      const res = await fetch("/finder/state?owner=" + encodeURIComponent(asking));
      if (res.ok && asking === owner) view = await res.json();
    } catch (e) { /* leave it empty */ }

    renderAll();
    if (view && view.active) beginPolling();               // a search that was already running carries on
  }

  async function loadWorlds() {
    try {
      const data = await (await fetch("/finder/worlds")).json();
      hoppable = data.worlds || {};
      maxHops = data.max_hops || 30;
      $("fdLimit").textContent = maxHops;
    } catch (e) { /* the toggle stays off */ }
    renderAll();
  }

  // ------------------------------------------------------------------ wiring

  function search() {
    const name = $("fdName").value.trim();
    if (!name) return;
    lookUp(name, false);
  }

  $("fdSearch").addEventListener("click", search);
  $("fdName").addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); search(); } });
  $("fdStatus").addEventListener("click", (e) => {
    if (e.target.closest("#fdRefresh") && looked && !(view && view.active)) lookUp(looked.name, true);
  });
  $("fdToggle").addEventListener("change", () => { $("fdToggle").checked ? start() : stop(); });

  // The finder makes one game hop lobbies, so it needs one account. With every account together it uses the one that is
  // online (in SkyBlock first); pick a card to choose another. A search that is already running is never switched away.
  let chosen = null;                // the account card chosen on the page, or null for every account together

  function pickOwner() {
    if (chosen) return chosen;
    if (view && view.active && owner) return owner;
    const list = window.accounts ? window.accounts() : [];
    const online = list.find(a => a.online && a.in_skyblock) || list.find(a => a.online);
    return online ? online.uuid : null;
  }

  function describeOwner() {
    const list = window.accounts ? window.accounts() : [];
    const account = list.find(a => a.uuid === owner);
    if (chosen) { $("fdAccount").textContent = ""; return; }
    $("fdAccount").textContent = account
      ? "Hops with " + account.name + ", the account that is online. Pick an account card to use a different one."
      : "No account is online. Start the game with the mod first, or pick an account card.";
  }

  function follow(detail) {
    chosen = detail && detail.uuid ? detail.uuid : null;
    const next = pickOwner();
    if (next !== owner) { owner = next; loadState(); } else { renderAll(); }
    describeOwner();
  }

  document.addEventListener("account-selected", (e) => follow(e.detail));
  document.addEventListener("accounts-updated", () => { if (!chosen && !(view && view.active)) follow(null); });
  const current = window.currentAccount && window.currentAccount();
  if (current) { chosen = current.uuid || null; owner = pickOwner(); describeOwner(); }

  loadWorlds().then(loadState);
  renderAll();
})();
} catch (e) { console.error("finder:", e); }
