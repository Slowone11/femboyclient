// Femboy Client website: the page itself (the account bar and tab switching).
// Each tab is its own file, loaded after this one (see the end of index.html). Needs core.js first.

// ================================================================ page

// ---------------------------------------------------------------- state
let players = [];          // every linked account
let playerUuid = null;     // the account being shown; null means every account together (the default)
let started = false;       // has the first choice (all accounts) been made yet?

// Each load takes a ticket, so a slow answer cannot overwrite a newer one.
let playersTicket = 0;

// --------------------------------------------------------------- helpers

// "3h ago", "12m ago", "just now"
function ago(ms) {
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return Math.floor(s / 60) + "m ago";
  if (s < 86400) return Math.floor(s / 3600) + "h ago";
  return Math.floor(s / 86400) + "d ago";
}

async function getJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(url + " -> HTTP " + res.status);
  return res.json();
}

function remember(key, value) { try { localStorage.setItem(key, value); } catch (e) { /* private mode */ } }
function recall(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }

// The dot and word in the top corner: whether the website is answering.
function setLive(state, text) {
  el("dot").className = "dot " + state;
  el("liveTxt").textContent = text;
}

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
  const all = '<button class="acct all' + (playerUuid ? "" : " sel") + '" data-uuid="">' +
                '<span class="dot' + (online ? " on" : "") + '"></span>' +
                '<span class="nm">All accounts</span>' +
                '<span class="st">' + online + " of " + players.length + ' online</span>' +
              '</button>';

  box.innerHTML = all + players.map(p => {
    const st = statusOf(p);
    return '<button class="acct' + (p.player_uuid === playerUuid ? " sel" : "") +
           '" data-uuid="' + esc(p.player_uuid) + '">' +
             '<span class="dot ' + st.dot + '"></span>' +
             '<span class="nm">' + esc(p.player_name) + '</span>' +
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
    setLive("on", "connected");
  } catch (e) {
    if (ticket === playersTicket) setLive("", "reconnecting…");
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

  // Tell the tabs, which follow the same choice (an account, or every account together).
  document.dispatchEvent(new CustomEvent("account-selected", { detail: window.currentAccount() }));
}

// The account being shown, for the Players tab.
window.currentAccount = () => {
  const p = players.find(x => x.player_uuid === playerUuid);
  if (p) return { uuid: p.player_uuid, name: p.player_name, online: p.online, scanner: p.scanner || "", all: false };
  return { uuid: null, name: "All accounts", online: players.some(x => x.online), scanner: "", all: true };
};

// Every linked account, for tabs that need to pick one themselves (the Player Finder).
window.accounts = () => players.map(p => ({ uuid: p.player_uuid, name: p.player_name, online: p.online, in_skyblock: p.in_skyblock }));

// ---------------------------------------------------------------- tabs

const TABS = ["tracker", "finder", "chats", "frienemys", "live"];     // the first one is where the page opens

function showTab(name) {
  if (!TABS.includes(name) || (name === "live" && !el("tabLive"))) name = TABS[0];
  el("tabTracker").hidden = name !== "tracker";
  el("tabFinder").hidden = name !== "finder";
  el("tabChats").hidden = name !== "chats";
  el("tabFrienemys").hidden = name !== "frienemys";
  if (el("tabLive")) {
    el("tabLive").hidden = name !== "live";
    el("tabBtnLive").setAttribute("aria-selected", String(name === "live"));
  }
  el("tabBtnTracker").setAttribute("aria-selected", String(name === "tracker"));
  el("tabBtnFinder").setAttribute("aria-selected", String(name === "finder"));
  el("tabBtnChats").setAttribute("aria-selected", String(name === "chats"));
  el("tabBtnFrienemys").setAttribute("aria-selected", String(name === "frienemys"));
  remember("femboyclient.tab", name);
  try { history.replaceState(null, "", name === TABS[0] ? location.pathname + location.search : "#" + name); } catch (e) { /* ignore */ }
  document.dispatchEvent(new CustomEvent("tab-changed", { detail: name }));
}
window.showTab = showTab;

el("tabBtnTracker").addEventListener("click", () => showTab("tracker"));
el("tabBtnFinder").addEventListener("click", () => showTab("finder"));
el("tabBtnChats").addEventListener("click", () => showTab("chats"));
el("tabBtnFrienemys").addEventListener("click", () => showTab("frienemys"));
if (el("tabBtnLive")) el("tabBtnLive").addEventListener("click", () => showTab("live"));
showTab(TABS.includes(location.hash.slice(1)) ? location.hash.slice(1) : recall("femboyclient.tab") || TABS[0]);

// ---------------------------------------------------------------- start

(async function init() {
  await refreshPlayers();
  // Status is picked up without a page refresh.
  // A hidden browser tab skips the polls and catches up as soon as it is shown again.
  setInterval(() => { if (!document.hidden) refreshPlayers(); }, 5000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) refreshPlayers(); });
})();
