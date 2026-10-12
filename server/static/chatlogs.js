// Femboy Client website: Chat Logs tab.

// ================================================================ chatlogs

try {
// Chat Logs tab: the chats the mod has recorded, filed in one folder per day (in the site's time zone).
//
// The folder list comes from /tracker/chats/days (chat counts per UTC hour, grouped into days here). Opening a
// folder reads /tracker/chats for just that day, with search, type filters and paging. Nothing is sent from here
// and nothing is stored in the browser. The filters apply to the folder list too, so searching shows which days
// have a match. A click on a player's name shows only that player, and "Load more" fetches 100 older chats.
(function () {
  "use strict";

  const TZ = "America/Toronto";
  const PAGE = 100;
  const SEARCH_DELAY_MS = 250;        // wait for a pause in typing before asking the website
  const REFRESH_MS = 10000;           // the newest page is refreshed this often while the tab is shown

  // What each type is called on its badge, and what it is called in the filter.
  const BADGE = { PUBLIC: "Public", PARTY: "Party", PRIVATE_FROM: "Private from", PRIVATE_TO: "Private to", GUILD: "Guild", COOP: "Co-op" };
  const CHIPS = [["", "All"], ["PUBLIC", "Public"], ["PARTY", "Party"], ["PRIVATE", "Private"]];

  const $ = (id) => document.getElementById(id);

  const shortFormat = new Intl.DateTimeFormat("en-US", { timeZone: TZ, month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  function fmtShort(iso) { return shortFormat.format(new Date(iso)); }

  // ------------------------------------------------------------------- days

  const dayFmt = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit" });
  const localKey = (ms) => dayFmt.format(ms);          // "2026-09-21": the day it is in the site's time zone

  // The chats of one day are those from its first instant (since) up to the next day's (until), both in UTC.
  function dayBounds(key) {
    const [y, m, d] = key.split("-").map(Number);
    let start = Date.UTC(y, m - 1, d);
    for (let h = -14; h <= 14; h++) {
      const t = Date.UTC(y, m - 1, d, h);
      if (localKey(t) === key && localKey(t - 1) !== key) { start = t; break; }
    }
    let end = start + 3600000;
    while (localKey(end) === key) end += 3600000;
    const iso = (t) => new Date(t).toISOString().replace(".000Z", "Z");
    return { since: iso(start), until: iso(end) };
  }

  // Chat counts per UTC hour become chat counts per day, newest day first.
  function groupDays(hours) {
    const byDay = new Map();
    for (const h of hours) {
      const [date, hh] = h.hour.split("T");
      const [y, m, d] = date.split("-").map(Number);
      const key = localKey(Date.UTC(y, m - 1, d, Number(hh)));
      byDay.set(key, (byDay.get(key) || 0) + h.count);
    }
    return [...byDay].map(([key, count]) => ({ key, count })).sort((a, b) => (a.key < b.key ? 1 : -1));
  }

  function dayLabel(key) {
    const [y, m, d] = key.split("-").map(Number);
    return new Date(Date.UTC(y, m - 1, d, 12)).toLocaleDateString("en-US",
      { timeZone: "UTC", weekday: "long", month: "long", day: "numeric", year: "numeric" });
  }

  function dayTag(key) {
    const today = localKey(Date.now());
    if (key === today) return "Today";
    return key === localKey(new Date(dayBounds(today).since).getTime() - 1) ? "Yesterday" : "";
  }

  const FOLDER_ICON = '<svg class="cl-folder-icon" viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">' +
    '<path fill="currentColor" d="M3 6.5A2.5 2.5 0 0 1 5.5 4h4.1c.5 0 1 .2 1.3.6L12.2 6H18.5A2.5 2.5 0 0 1 21 8.5v9A2.5 2.5 0 0 1 18.5 20h-13A2.5 2.5 0 0 1 3 17.5z"/></svg>';

  // ------------------------------------------------------------------- state

  let owner = null;                   // the account whose chats are shown
  let query = "";                     // what is typed in the search box
  let type = "";                      // "", PUBLIC, PARTY or PRIVATE
  let player = "";                    // one player, chosen by clicking a name
  let day = null;                     // the open folder ("2026-09-21"), or null while the folders are shown
  let days = [];                      // the folders: { key, count }, newest first
  let daysLoaded = false;
  let chats = [];                     // the rows shown, newest first
  let nextBefore = null;              // where the next page starts, or null when there is nothing older
  let loaded = false;                 // has the first page for the current filters been fetched?
  let loading = false;
  let failure = "";
  let ticket = 0;                     // an answer for an older search is thrown away
  let searchTimer = null;
  let refreshTimer = null;

  // ------------------------------------------------------------------- loading

  function params(before) {
    const p = new URLSearchParams({ owner });
    if (query.trim()) p.set("q", query.trim());
    if (type) p.set("type", type);
    if (player) p.set("player", player);
    if (day) {
      const b = dayBounds(day);
      p.set("since", b.since);
      p.set("until", b.until);
      p.set("limit", String(PAGE));
      if (before) p.set("before", before);
    }
    return p.toString();
  }

  // Fetches the folder list for the current filters.
  async function loadDays(quiet) {
    if (!owner) return;
    const mine = ++ticket;
    if (!quiet) { loading = true; failure = ""; render(); }
    try {
      const res = await fetch("/tracker/chats/days?" + params(null));
      if (!res.ok) throw new Error("HTTP " + res.status);
      const data = await res.json();
      if (mine !== ticket) return;
      days = groupDays(data.hours);
      daysLoaded = true;
      failure = "";
    } catch (e) {
      if (mine !== ticket) return;
      if (!quiet) failure = "The days could not be loaded (" + (e.message || "no answer") + ").";
    }
    loading = false;
    render();
  }

  // Fetches the first page (replacing the list) or the next one (adding to it).
  async function load(more, quiet) {
    if (!owner) return;
    const mine = ++ticket;
    if (!quiet) { loading = true; failure = ""; render(); }
    try {
      const res = await fetch("/tracker/chats?" + params(more ? nextBefore : null));
      if (!res.ok) throw new Error("HTTP " + res.status);
      const data = await res.json();
      if (mine !== ticket) return;                         // the filters changed while this was on its way
      chats = more ? chats.concat(data.chats) : data.chats;
      nextBefore = data.has_more ? data.next_before : null;
      loaded = true;
      failure = "";
    } catch (e) {
      if (mine !== ticket) return;
      if (!quiet) failure = "The chats could not be loaded (" + (e.message || "no answer") + ").";
    }
    loading = false;
    render();
  }

  function reload() {
    if (day) { loaded = false; load(false, false); }
    else { daysLoaded = false; loadDays(false); }
  }

  function openDay(key) {
    day = key;
    chats = [];
    nextBefore = null;
    loaded = false;
    load(false, false);
  }

  function closeDay() {
    day = null;
    chats = [];
    nextBefore = null;
    loaded = false;
    reload();
  }

  // ------------------------------------------------------------------- drawing

  function renderChips() {
    $("clChips").innerHTML = CHIPS.map(c =>
      '<button class="cl-chip' + (type === c[0] ? " on" : "") + '" data-type="' + c[0] + '">' + c[1] + "</button>").join("");
  }

  function rowHtml(c) {
    const kind = BADGE[c.type] ? c.type : "PUBLIC";
    return '<div class="cl-row">' +
      '<span class="cl-time" title="' + esc(fmtFull(c.at)) + (c.world && c.world !== "Unknown" ? " in " + esc(c.world) : "") + '">' + esc(fmtShort(c.at)) + "</span>" +
      '<span class="cl-badge cl-b-' + kind + '">' + esc(BADGE[kind]) + "</span>" +
      '<button class="cl-player" data-player="' + esc(c.player) + '" title="Show only ' + esc(c.player) + '">' + esc(c.player) +
        (c.mine ? '<span class="cl-you">you</span>' : "") + "</button>" +
      '<span class="cl-msg">' + esc(c.message) + (c.world && c.world !== "Unknown" ? "<small>" + esc(c.world) + "</small>" : "") +
        (owner === "all" && c.account ? '<small class="cl-via" title="The account that recorded this chat">via ' + esc(c.account) + "</small>" : "") + "</span></div>";
  }

  function folderHtml(d) {
    const tag = dayTag(d.key);
    return '<button class="cl-folder" data-day="' + esc(d.key) + '" title="Open ' + esc(dayLabel(d.key)) + '">' + FOLDER_ICON +
      '<span class="cl-folder-name">' + esc(dayLabel(d.key)) + (tag ? '<em>' + tag + "</em>" : "") + "</span>" +
      '<span class="cl-folder-count">' + d.count.toLocaleString() + (d.count === 1 ? " chat" : " chats") + "</span></button>";
  }

  function renderDays() {
    const box = $("clDays");
    if (failure) {
      box.innerHTML = '<div class="cl-state warn">' + esc(failure) + "</div>";
    } else if (!daysLoaded) {
      box.innerHTML = '<div class="cl-state">Loading days…</div>';
    } else if (!days.length) {
      box.innerHTML = '<div class="cl-state">No chats found</div>';
    } else {
      box.innerHTML = days.map(folderHtml).join("");
    }
    const total = days.reduce((sum, d) => sum + d.count, 0);
    $("clCount").textContent = daysLoaded && days.length
      ? days.length.toLocaleString() + (days.length === 1 ? " day · " : " days · ") + total.toLocaleString() + (total === 1 ? " chat" : " chats") : "";
    $("clMore").hidden = true;
  }

  function render() {
    renderChips();

    const who = $("clWho");
    who.hidden = !player;
    if (player) $("clWhoName").textContent = player;

    $("clCrumb").hidden = !day;
    $("clDays").hidden = !!day;
    $("clList").hidden = !day;
    if (!day) { renderDays(); return; }
    $("clDayName").textContent = dayLabel(day);

    const box = $("clList");
    if (failure) {
      box.innerHTML = '<div class="cl-state warn">' + esc(failure) + "</div>";
    } else if (!loaded) {
      box.innerHTML = '<div class="cl-state">Loading chats…</div>';
    } else if (!chats.length) {
      box.innerHTML = '<div class="cl-state">No chats found</div>';
    } else {
      box.innerHTML = chats.map(rowHtml).join("");
    }

    $("clCount").textContent = loaded && chats.length ? chats.length.toLocaleString() + (nextBefore ? "+" : "") + (chats.length === 1 ? " chat" : " chats") : "";
    const more = $("clMore");
    more.hidden = !nextBefore || !!failure;
    more.disabled = loading;
    more.textContent = loading && loaded ? "Loading…" : "Load more";
  }

  // ------------------------------------------------------------------- events

  $("clSearch").addEventListener("input", (e) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { query = e.target.value; reload(); }, SEARCH_DELAY_MS);
  });

  $("clChips").addEventListener("click", (e) => {
    const chip = e.target.closest(".cl-chip");
    if (!chip || chip.dataset.type === type) return;
    type = chip.dataset.type;
    reload();
  });

  $("clList").addEventListener("click", (e) => {
    const name = e.target.closest(".cl-player");
    if (!name) return;
    player = name.dataset.player;
    reload();
  });

  $("clDays").addEventListener("click", (e) => {
    const folder = e.target.closest(".cl-folder");
    if (folder) openDay(folder.dataset.day);
  });

  $("clBack").addEventListener("click", closeDay);
  $("clWhoClear").addEventListener("click", () => { player = ""; reload(); });
  $("clMore").addEventListener("click", () => { if (!loading && nextBefore) load(true, false); });

  // A different account has different chats, so it starts again from the folders.
  document.addEventListener("account-selected", (e) => {
    owner = e.detail.uuid || "all";               // "all": every account together
    day = null;
    days = [];
    daysLoaded = false;
    chats = [];
    nextBefore = null;
    loaded = false;
    if (!$("tabChats").hidden) { reload(); startRefresh(); } else render();        // the page can open straight on this tab
  });

  // Showing the tab loads the newest chats; while it stays open, the first page is kept fresh.
  function stopRefresh() { clearInterval(refreshTimer); refreshTimer = null; }

  function startRefresh() {
    stopRefresh();
    refreshTimer = setInterval(() => {
      if (loading || document.hidden) return;
      // The folders are refreshed as they are. In an open folder only the first page is, and only when nobody has asked for older chats.
      if (!day) { if (daysLoaded) loadDays(true); }
      else if (loaded && chats.length <= PAGE) load(false, true);
    }, REFRESH_MS);
  }

  document.addEventListener("tab-changed", (e) => {
    stopRefresh();
    if (e.detail !== "chats") return;
    if (owner) reload();
    startRefresh();
  });

  render();
})();
} catch (e) { console.error("chatlogs:", e); }
