// Femboy Client website: helpers every tab shares. Loaded before app.js.

const el = (id) => document.getElementById(id);

function esc(text) {
  return String(text).replace(/[&<>"']/g, c => (
    { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function isValidName(text) { return /^[A-Za-z0-9_]{3,16}$/.test(text); }

// "Sep 21, 2026, 3:41:03 PM EDT". The formatter is built once: making one per call is slow in a long list.
const fullFormat = new Intl.DateTimeFormat("en-US", {
  timeZone: "America/Toronto", month: "short", day: "numeric", year: "numeric",
  hour: "numeric", minute: "2-digit", second: "2-digit", timeZoneName: "short"
});

function fmtFull(iso) { return fullFormat.format(new Date(iso)); }
