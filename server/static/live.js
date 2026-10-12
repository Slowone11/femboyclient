// Femboy Client website: Live tab.

try {
// Live tab: the game screen of one account, and clicks, keys and typing sent back to it. Only the PC the website runs on is given
// this tab (the server cuts it from the page for anyone else). Nothing is stored in the browser except which account was last watched.
//
// Two loops run while the tab is open. Every 350 ms the newest picture is asked for and only downloaded when it is a new one (the
// server answers 204 for the picture already shown). Every second the status is read: which accounts have a picture, what each is
// holding and where, whether a menu is open, whether input is allowed. Input is sent one at a time, in order.
(function () {
  "use strict";

  if (!document.getElementById("tabLive")) return;      // any PC but the one the website runs on gets a page without this tab

  const FRAME_MS = 350, STATUS_MS = 1000, ACCOUNT_KEY = "femboyclient.liveAccount";
  const $ = (id) => document.getElementById(id);
  let frameTimer = null, statusTimer = null, chosen = recall(ACCOUNT_KEY) || "";
  let status = { accounts: {}, disabled: [] }, denied = false, lastSeq = 0, framesAt = [], lastBytes = 0, synced = false, statusBusy = false;
  let queue = Promise.resolve(), typed = "", typedTimer = null, notice = "", noticeUntil = 0;

  const accountsNow = () => (window.accounts ? window.accounts() : []);
  const me = () => status.accounts[chosen] || null;
  const isOff = () => status.disabled.includes(chosen);
  const unlocked = () => !!recall(TOKEN_KEY);

  // What can be done right now, and if not, why not. One place, so the buttons, the cursor and the words always agree.
  function ability() {
    const s = me();
    if (!chosen || !s) return { ok: false, why: "" };
    if (isOff()) return { ok: false, why: "Input is switched off for this account. Turn the Input switch on to use it." };
    if (!s.input) return { ok: false, why: "Input is off in that game. Type /liveinput on there." };
    if (!unlocked()) return { ok: false, why: "Unlock the game controls below (the admin token) to click and type.", unlock: true };
    return { ok: true, why: "" };
  }

  // ------------------------------------------------------------------------------------------------ drawing

  function hotbar() {
    const holder = $("lvHotbar");
    if (holder.children.length) return;
    for (let n = 1; n <= 9; n++) {
      const b = document.createElement("button");
      b.type = "button"; b.className = "lv-slot"; b.dataset.slot = n; b.textContent = n; b.title = "Hold slot " + n + " (key " + n + ")";
      holder.appendChild(b);
    }
  }

  function drawAccounts() {
    const list = accountsNow();
    const box = $("lvAccounts");
    const html = list.map((a) => {
      const has = a.uuid in status.accounts;
      const cls = "lv-chip" + (a.uuid === chosen ? " on" : "") + (has ? " live" : a.online ? " wait" : " off");
      const tip = has ? "Has a picture" : a.online ? "Online, no picture yet" : "Offline";
      return '<button type="button" class="' + cls + '" data-uuid="' + esc(a.uuid) + '" title="' + tip + '"><i></i>' + esc(a.name) + "</button>";
    }).join("");
    if (box.dataset.html !== html) { box.dataset.html = html; box.innerHTML = html || '<span class="lv-none">No linked accounts yet.</span>'; }
  }

  function say(text, ms) { notice = text; noticeUntil = Date.now() + (ms || 2500); draw(); }

  function draw() {
    hotbar();
    drawAccounts();
    const s = me(), a = accountsNow().find((x) => x.uuid === chosen), can = ability();
    const box = $("lvBox");

    // headline pill
    const now = Date.now();
    framesAt = framesAt.filter((t) => now - t < 5000);
    const fps = framesAt.length ? framesAt.length / Math.max(1, Math.min(5, (now - framesAt[0]) / 1000)) : 0;
    let pill = "Pick an account", pillCls = "";
    if (denied) { pill = "This PC only"; pillCls = "bad"; }
    else if (notice && now < noticeUntil) { pill = notice; pillCls = "note"; }
    else if (s) { pill = s.age > 8 ? "Paused (" + s.age.toFixed(0) + "s old)" : "Live" + (fps ? " · " + fps.toFixed(1) + " pictures/s" : ""); pillCls = s.age > 8 ? "wait" : "ok"; }
    else if (chosen) { pill = a && a.online ? "Waiting for a picture" : "Offline"; pillCls = "wait"; }
    $("lvPill").textContent = pill;
    $("lvPill").className = "lv-pill " + pillCls;

    // empty state, in words that say what to do
    let empty = "";
    if (denied) empty = "The live view only opens on the PC the website runs on.";
    else if (!chosen) empty = accountsNow().length ? "Pick an account above." : "No linked account yet.";
    else if (!s) empty = a && a.online
      ? "No picture from " + a.name + " yet. It arrives within a few seconds. If it does not, type /liveview on in the game."
      : (a ? a.name : "That account") + " is offline. Start the game with the mod to see it here.";
    $("lvEmpty").hidden = !empty;
    $("lvEmpty").textContent = empty;
    if (empty) $("lvImg").hidden = true;

    // what the game says about itself
    const menu = !!(s && s.menu);
    $("lvMode").textContent = !s ? "Waiting for a picture…"
      : menu ? "A menu is open. Click anywhere on the picture, and type to fill boxes."
      : "In the world. A click goes where the crosshair points (the ring in the middle).";
    $("lvMode").className = "lv-card lv-mode" + (menu ? " menu" : "");
    $("lvCross").hidden = !(s && !menu && can.ok && !$("lvImg").hidden);
    $("lvHeld").textContent = s ? (s.held ? s.held + "  (slot " + (s.slot + 1) + ")" : s.slot != null ? "nothing  (slot " + (s.slot + 1) + ")" : "–") : "–";
    $("lvPos").textContent = s && s.pos ? s.pos.join("  ") : "–";
    $("lvPic").textContent = s ? (fps ? fps.toFixed(1) + "/s · " : "") + Math.max(1, Math.round((lastBytes || s.bytes || 0) / 1024)) + " KB · " + s.age.toFixed(1) + "s old" : "–";

    // the switch and what it means right now
    $("lvInputSwitch").checked = !isOff();
    $("lvInputSwitch").disabled = !chosen || denied;
    $("lvInputWord").textContent = isOff() ? "off" : "on";
    const note = $("lvInputNote");
    note.innerHTML = !chosen ? "" : can.ok ? "Ready. Click the picture."
      : esc(can.why) + (can.unlock ? ' <button type="button" class="lv-link" id="lvUnlock">Unlock now</button>' : "");
    note.className = "lv-note" + (can.ok ? " ok" : "");

    // hotbar, keys, cursor
    document.querySelectorAll("#lvHotbar .lv-slot").forEach((b) => {
      b.classList.toggle("on", !!s && s.slot === Number(b.dataset.slot) - 1);
      b.disabled = !can.ok;
    });
    $("lvEsc").disabled = !can.ok || !menu;
    const canType = can.ok && menu;
    ["lvText", "lvTypeGo", "lvEnter", "lvBack"].forEach((id) => { $(id).disabled = !canType; });
    $("lvText").placeholder = canType ? "Type into the open menu (search, sign, anvil)" : "Open a menu to type into it";
    box.classList.toggle("can", can.ok);
  }

  // ------------------------------------------------------------------------------------------------ loops

  async function readStatus() {
    if (statusBusy || document.hidden || $("tabLive").hidden) return;
    statusBusy = true;
    try {
      const reply = await fetch("/liveview/status", { cache: "no-store" });
      if (reply.status === 403) { denied = true; draw(); return; }
      denied = false;
      const answer = await reply.json();
      status = { accounts: answer.accounts || {}, disabled: answer.disabled || [] };
      const list = accountsNow();
      if (!list.some((a) => a.uuid === chosen)) {
        const current = window.currentAccount ? window.currentAccount() : null;
        chosen = (current && current.uuid) || (list.find((a) => a.uuid in status.accounts) || list[0] || {}).uuid || "";
      }
      if (!synced && chosen) { synced = true; follow(); }        // once each time the tab is opened
      draw();
    } catch (e) { /* the next round tries again */ }
    finally { statusBusy = false; }
  }

  let frameBusy = false;
  async function readFrame() {
    if (frameBusy || document.hidden || $("tabLive").hidden || denied || !chosen || !(chosen in status.accounts)) return;
    frameBusy = true;
    try {
      const reply = await fetch("/liveview/" + encodeURIComponent(chosen) + "/frame.jpg?since=" + lastSeq, { cache: "no-store" });
      if (reply.status === 204 || !reply.ok) return;                 // nothing new (still screen), or no picture
      lastSeq = Number(reply.headers.get("X-Frame-Seq")) || 0;
      const blob = await reply.blob();
      lastBytes = blob.size;
      const image = $("lvImg"), old = image.src, url = URL.createObjectURL(blob);
      image.onload = () => { if (old && old.startsWith("blob:")) URL.revokeObjectURL(old); };
      image.src = url;
      image.hidden = false;
      framesAt.push(Date.now());
      draw();
    } catch (e) { /* the next round tries again */ }
    finally { frameBusy = false; }
  }

  // ------------------------------------------------------------------------------------------------ input

  // The Live tab and the game controls under it act on the same account, so the two are kept the same.
  function follow() {
    const now = window.currentAccount ? window.currentAccount() : null;
    if (chosen && (!now || now.uuid !== chosen) && typeof selectPlayer === "function") selectPlayer(chosen);
  }

  function send(event) {
    const token = recall(TOKEN_KEY);
    if (!token || !chosen) return Promise.resolve(false);
    const account = chosen;
    queue = queue.then(async () => {
      try {
        const reply = await fetch("/liveview/" + encodeURIComponent(account) + "/input", {
          method: "POST", headers: { "Content-Type": "application/json", "X-Admin-Token": token }, body: JSON.stringify(event)
        });
        if (reply.status === 401) { forgetToken(); drawRemote(); say("Wrong admin token. Enter it again below.", 4000); }
        else if (reply.status === 409) { say((await reply.json().catch(() => ({}))).detail || "That game does not take input.", 4000); readStatus(); }
        else if (!reply.ok) say("Refused (HTTP " + reply.status + ")", 4000);
      } catch (e) { say("Could not reach the website.", 4000); }
    });
    return queue;
  }

  function guard() {
    const can = ability();
    if (!can.ok) { if (can.why) say(can.why, 3500); return false; }
    return true;
  }

  function ping(clientX, clientY) {
    const box = $("lvBox"), at = box.getBoundingClientRect(), dot = document.createElement("div");
    dot.className = "lv-ping";
    dot.style.left = (clientX - at.left) + "px";
    dot.style.top = (clientY - at.top) + "px";
    box.appendChild(dot);
    setTimeout(() => dot.remove(), 500);
  }

  // Where on the picture, 0 to 1 each way, or null when it is not on the picture.
  function spot(e) {
    const image = $("lvImg"), r = image.getBoundingClientRect();
    if (image.hidden || !r.width || !r.height) return null;
    const x = (e.clientX - r.left) / r.width, y = (e.clientY - r.top) / r.height;
    if (x < 0 || x > 1 || y < 0 || y > 1) return null;
    return { x: Math.round(x * 10000) / 10000, y: Math.round(y * 10000) / 10000 };
  }

  function click(e, button) {
    const at = spot(e);
    if (!at) return;
    $("lvBox").focus({ preventScroll: true });
    if (!guard()) return;
    ping(e.clientX, e.clientY);
    send({ type: "click", x: at.x, y: at.y, button, shift: e.shiftKey });
  }

  $("lvImg").addEventListener("click", (e) => click(e, 0));
  $("lvImg").addEventListener("contextmenu", (e) => { e.preventDefault(); click(e, 1); });
  let wheelAt = 0;
  $("lvImg").addEventListener("wheel", (e) => {
    e.preventDefault();
    const now = Date.now(), at = spot(e);
    if (now - wheelAt < 120 || !at || !guard()) return;
    wheelAt = now;
    send({ type: "scroll", x: at.x, y: at.y, dy: e.deltaY > 0 ? -1 : 1 });
  }, { passive: false });

  $("lvHotbar").addEventListener("click", (e) => {
    const b = e.target.closest("[data-slot]");
    if (b && guard()) send({ type: "slot", n: Number(b.dataset.slot) });
  });
  $("lvEsc").addEventListener("click", () => { if (guard()) send({ type: "key", key: "escape" }); });
  $("lvEnter").addEventListener("click", () => { if (guard()) send({ type: "key", key: "enter" }); });
  $("lvBack").addEventListener("click", () => { if (guard()) send({ type: "key", key: "backspace" }); });
  $("lvTypeForm").addEventListener("submit", (e) => {
    e.preventDefault();
    const text = $("lvText").value;
    if (!text || !guard()) return;
    send({ type: "text", text });
    $("lvText").value = "";
  });

  // Keys while the picture is selected: with a menu open what is typed goes into it, otherwise the digits pick a hotbar slot.
  function flushTyped() {
    typedTimer = null;
    const text = typed.slice(0, 64);
    typed = "";
    if (text) send({ type: "text", text });
  }
  $("lvBox").addEventListener("keydown", (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey || e.target !== $("lvBox")) return;
    const s = me(), menu = !!(s && s.menu);
    let handled = true;
    if (e.key === "Escape") { if (guard()) send({ type: "key", key: "escape" }); }
    else if (menu && e.key === "Enter") { if (guard()) send({ type: "key", key: "enter" }); }
    else if (menu && e.key === "Backspace") { if (guard()) send({ type: "key", key: "backspace" }); }
    else if (menu && e.key.length === 1) {
      if (guard()) { typed += e.key; if (!typedTimer) typedTimer = setTimeout(flushTyped, 120); }
    }
    else if (!menu && /^[1-9]$/.test(e.key)) { if (guard()) send({ type: "slot", n: Number(e.key) }); }
    else handled = false;
    if (handled) e.preventDefault();
  });

  $("lvFull").addEventListener("click", () => {
    const box = $("lvBox");
    if (document.fullscreenElement) document.exitFullscreen(); else if (box.requestFullscreen) box.requestFullscreen();
  });

  // Choosing an account, and the Input switch.
  $("lvAccounts").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-uuid]");
    if (!chip || chip.dataset.uuid === chosen) return;
    chosen = chip.dataset.uuid;
    remember(ACCOUNT_KEY, chosen);
    lastSeq = 0; framesAt = []; $("lvImg").hidden = true;
    follow();
    draw();
    readStatus();
  });

  $("lvInputSwitch").addEventListener("change", async () => {
    const want = $("lvInputSwitch").checked, token = recall(TOKEN_KEY);
    if (!token) { $("lvInputSwitch").checked = !want; say("Unlock the game controls below first (the admin token).", 3500); return; }
    try {
      const reply = await fetch("/liveview/" + encodeURIComponent(chosen) + "/input-enabled", {
        method: "POST", headers: { "Content-Type": "application/json", "X-Admin-Token": token }, body: JSON.stringify({ enabled: want })
      });
      if (reply.status === 401) { forgetToken(); drawRemote(); $("lvInputSwitch").checked = !want; say("Wrong admin token. Enter it again below.", 4000); return; }
      if (!reply.ok) { $("lvInputSwitch").checked = !want; say("Could not change it (HTTP " + reply.status + ").", 4000); return; }
      say(want ? "Input on" : "Input off");
      readStatus();
    } catch (e) { $("lvInputSwitch").checked = !want; say("Could not reach the website.", 4000); }
  });

  document.addEventListener("click", (e) => {
    if (e.target.id !== "lvUnlock") return;
    const field = document.getElementById("remoteTokenInput") || document.getElementById("remote");
    if (field) { field.scrollIntoView({ block: "center", behavior: "smooth" }); if (field.focus) field.focus({ preventScroll: true }); }
  });

  document.addEventListener("account-selected", (e) => {
    const uuid = e.detail && e.detail.uuid;
    if (uuid && uuid !== chosen) { chosen = uuid; lastSeq = 0; framesAt = []; $("lvImg").hidden = true; draw(); }
  });

  // Unlocking or locking the controls changes what can be done.
  ["remoteUnlock", "remoteLock"].forEach((id) => { const f = document.getElementById(id); if (f) f.addEventListener(id === "remoteLock" ? "click" : "submit", () => setTimeout(draw, 400)); });

  function start() {
    stop();
    synced = false; lastSeq = 0;
    readStatus(); readFrame();
    statusTimer = setInterval(readStatus, STATUS_MS);
    frameTimer = setInterval(readFrame, FRAME_MS);
  }
  function stop() { clearInterval(statusTimer); clearInterval(frameTimer); statusTimer = frameTimer = null; }

  document.addEventListener("tab-changed", (e) => { if (e.detail === "live") start(); else stop(); });
  document.addEventListener("visibilitychange", () => { if (!document.hidden && !$("tabLive").hidden) { readStatus(); readFrame(); } });

  hotbar();
  draw();
  // The page can open straight on this tab (a remembered tab or #live), before this part was listening for the tab change.
  if (!$("tabLive").hidden) start();
})();
} catch (e) { console.error("live:", e); }
