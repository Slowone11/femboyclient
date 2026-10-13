// Femboy Client website: Frienemys tab.

try {
// Frienemys tab: tag a player friendly or enemy and write a note on why. Shared across every account,
// like the rest of the tracker database. The game's own Frienemys overlay shows any tagged player who
// is in your current lobby, so tagging someone here is what makes them show up there.
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);


  function faceHtml(name) {
    if (!isValidName(name)) return "";
    return '<img class="fe-face" alt="" width="32" height="32" src="https://mc-heads.net/avatar/' +
           encodeURIComponent(name) + '/64" onerror="this.remove()">';
  }

  let items = [];              // every tagged player, as the website last fetched them
  let query = "";               // the search box
  let tag = "";                 // the tag picked in the form: "friendly" or "enemy"
  let editingId = null;         // the id being edited, or null while tagging someone new
  let editingUuid = null;       // that player's uuid, if known, so a renamed player is not duplicated
  let saving = false;
  let loaded = false;
  let error = "";

  async function load() {
    try {
      const res = await fetch("/tracker/frienemies");
      if (!res.ok) throw new Error("HTTP " + res.status);
      items = await res.json();
      loaded = true;
      error = "";
    } catch (e) {
      error = "Could not load the list (" + e.message + ").";
    }
    render();
  }

  function pickTag(next) {
    tag = tag === next ? "" : next;
    $("feFriendly").classList.toggle("on", tag === "friendly");
    $("feEnemy").classList.toggle("on", tag === "enemy");
  }

  function startEdit(item) {
    editingId = item.id;
    editingUuid = item.uuid;
    $("feName").value = item.name;
    $("feNotes").value = item.notes;
    tag = "";
    pickTag(item.tag);
    $("feSave").textContent = "Update tag";
    $("feCancel").hidden = false;
    $("feName").focus();
  }

  function stopEdit() {
    editingId = null;
    editingUuid = null;
    $("feForm").reset();
    tag = "";
    pickTag("");
    $("feSave").textContent = "Save tag";
    $("feCancel").hidden = true;
  }

  async function save(e) {
    e.preventDefault();
    const name = $("feName").value.trim();
    const notes = $("feNotes").value.trim();
    if (!isValidName(name)) { error = "That is not a valid Minecraft username."; render(); return; }
    if (!tag) { error = "Pick Friendly or Enemy."; render(); return; }

    saving = true;
    error = "";
    render();
    try {
      const res = await fetch("/tracker/frienemies", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, uuid: editingUuid || undefined, tag, notes }),
      });
      if (!res.ok) throw new Error((await res.json().catch(() => ({}))).detail || ("HTTP " + res.status));
      stopEdit();
      saving = false;
      await load();
    } catch (e) {
      error = "Could not save (" + e.message + ").";
      saving = false;
      render();
    }
  }

  async function untag(id) {
    if (!confirm("Remove this tag?")) return;
    try {
      const res = await fetch("/tracker/frienemies/" + id, { method: "DELETE" });
      if (!res.ok && res.status !== 404) throw new Error("HTTP " + res.status);
      if (editingId === id) stopEdit();
      await load();
    } catch (e) {
      error = "Could not remove the tag (" + e.message + ").";
      render();
    }
  }

  function rowHtml(item) {
    const badge = '<span class="cl-badge fe-b-' + item.tag + '">' + (item.tag === "friendly" ? "Friendly" : "Enemy") + "</span>";
    const notes = item.notes ? '<div class="fe-notes">' + esc(item.notes) + "</div>" : "";
    return '<div class="fe-row" data-id="' + item.id + '">' +
      faceHtml(item.name) +
      '<div class="fe-main"><div class="fe-name">' + esc(item.name) + " " + badge + "</div>" + notes + "</div>" +
      '<div class="fe-actions">' +
        '<button class="cl-btn fe-edit" type="button">Edit</button>' +
        '<button class="cl-btn fe-untag" type="button">Untag</button>' +
      "</div></div>";
  }

  function render() {
    $("feError").hidden = !error;
    $("feError").textContent = error;
    $("feSave").disabled = saving;

    const needle = query.trim().toLowerCase();
    const shown = needle ? items.filter(i => i.name.toLowerCase().includes(needle)) : items;

    $("feCount").textContent = loaded ? (items.length === 1 ? "1 tagged player" : items.length + " tagged players") : "";

    if (!loaded) { $("feList").innerHTML = '<div class="cl-state">Loading…</div>'; return; }
    if (!items.length) { $("feList").innerHTML = '<div class="cl-state">No one tagged yet. Tag a player above.</div>'; return; }
    if (!shown.length) { $("feList").innerHTML = '<div class="cl-state">No tagged player matches.</div>'; return; }
    $("feList").innerHTML = shown.map(rowHtml).join("");
  }

  $("feForm").addEventListener("submit", save);
  $("feFriendly").addEventListener("click", () => pickTag("friendly"));
  $("feEnemy").addEventListener("click", () => pickTag("enemy"));
  $("feCancel").addEventListener("click", stopEdit);
  $("feSearch").addEventListener("input", (e) => { query = e.target.value; render(); });
  $("feList").addEventListener("click", (e) => {
    const row = e.target.closest(".fe-row");
    if (!row) return;
    const id = Number(row.dataset.id);
    const item = items.find(i => i.id === id);
    if (!item) return;
    if (e.target.closest(".fe-edit")) startEdit(item);
    else if (e.target.closest(".fe-untag")) untag(id);
  });

  document.addEventListener("tab-changed", (e) => { if (e.detail === "frienemys" && !loaded) load(); });
  // A tag set from a player's panel on the Players tab: load the list again the next time this tab is opened.
  document.addEventListener("frienemies-changed", () => { if (!$("tabFrienemys").hidden) load(); else loaded = false; });
  if (!$("tabFrienemys").hidden) load(); else render();
})();
} catch (e) { console.error("frienemys:", e); }
