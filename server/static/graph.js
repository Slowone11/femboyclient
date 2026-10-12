// Femboy Client website: the graph engine (window.GraphKit) the Lobby Networth tab draws with.
// A failure here is logged and cannot stop the other tabs.

// ================================================================ graph

try {
// A small, fast graph renderer for the Lobby Networth.
//
// Why it exists: a general graph library redraws every node and edge on every pan, zoom and hover,
// which gets slow with thousands of bubbles. This one keeps a picture of the whole graph and only
// moves that picture while you pan or zoom (under a millisecond), then draws the graph sharp again
// once you stop. Drawing is batched (one path per colour, not one per bubble), off-screen things are
// skipped, and clicks and hovers use a grid so they cost the same with 100 nodes or 100,000.
//
// It also keeps bubbles from overlapping: a selected bubble grows and the ones around it are pushed
// out of the way (and slide back when it loses focus), and a bubble you drop on top of others pushes
// them aside.
//
// It reads two DataSets (nodes and edges) and never draws anything else. Node fields used:
//   id, type, size (radius), x, y, fixed, color { background, border }, borderWidth, shadow { enabled },
//   label, title, font { size }.   Edge fields used: from, to, color { color, opacity }, dashes.
(function () {
  "use strict";

  const TAU = Math.PI * 2;

  // ------------------------------------------------------------------ data set

  /** Just enough of a data set: get, update, clear, length, and a change callback. */
  class DataSet {
    constructor() {
      this.map = new Map();
      this.listeners = [];
    }

    get length() { return this.map.size; }

    /** get() = all, get(id) = one (or null), get({ filter }) = the ones the filter accepts. */
    get(arg) {
      if (arg === undefined) return [...this.map.values()];
      if (arg !== null && typeof arg === "object") {
        const out = [];
        for (const item of this.map.values()) if (!arg.filter || arg.filter(item)) out.push(item);
        return out;
      }
      return this.map.get(arg) || null;
    }

    /** Adds or changes items. Only the fields given are changed. */
    update(items) {
      const given = Array.isArray(items) ? items : [items];
      const list = [];                          // only what really changed, so a no-op update redraws nothing
      for (const item of given) {
        const old = this.map.get(item.id);
        if (!old) { this.map.set(item.id, Object.assign({}, item)); list.push(item); continue; }
        let changed = false;
        for (const key in item) if (old[key] !== item[key]) { changed = true; break; }
        if (!changed) continue;
        Object.assign(old, item);
        list.push(item);
      }
      if (!list.length) return;
      for (const fn of this.listeners) fn(list);
    }

    clear() {
      this.map.clear();
      for (const fn of this.listeners) fn(null);
    }

    onChange(fn) { this.listeners.push(fn); }
  }

  // ------------------------------------------------------------------- helpers

  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const easeOut = (k) => 1 - Math.pow(1 - k, 3);
  const easeInOut = (k) => (k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2);

  // -------------------------------------------------------------------- network

  class FastNetwork {
    /**
     * @param container the element to draw in (it should be position: relative)
     * @param options   { minScale, maxScale, growth, growMs, maxLabels, labelMinPx, gap }
     */
    constructor(container, nodes, edges, options) {
      this.container = container;
      this.nodes = nodes;
      this.edges = edges;
      this.o = Object.assign({
        minScale: 0.015, maxScale: 8,
        growth: 0.9, growMs: 240,          // a selected bubble ends up this much bigger, over this long
        gap: 6,                            // clear space kept between bubbles when pushing
        maxLabels: 420, labelMinPx: 9,     // names appear once they would be at least this tall, at most this many
        idleMs: 110                        // the picture is redrawn this long after you stop moving
      }, options || {});

      this.pos = new Map();                // id -> { x, y }: where each bubble is
      this.view = { scale: 1, tx: 0, ty: 0 };
      this.W = 0; this.H = 0; this.dpr = 1;

      this.selected = null;
      this.hovered = null;
      this.handlers = {};
      this.focus = new Map();              // id -> { v, target, t0, v0, push } for the growing / shrinking bubbles
      this.moving = new Map();             // id -> { from, to } for bubbles gliding to a new spot
      this.glide = null;                   // { t0, ms, done }
      this.viewAnim = null;
      this.dragging = null;
      this.drag = null;

      this.cache = document.createElement("canvas");
      this.cacheView = null;               // the view the cached picture was drawn at
      this.sceneDirty = true;
      this.indexDirty = true;
      this.index = new Map();
      this.motionAt = 0;
      this.lastScene = 0;
      this.frameQueued = false;
      this.stats = { sceneMs: 0, blitMs: 0, overlayMs: 0, scenes: 0, frames: 0 };

      this.canvas = document.createElement("canvas");
      this.canvas.style.cssText = "position:absolute;inset:0;width:100%;height:100%;display:block;touch-action:none;z-index:0";
      this.canvas.setAttribute("aria-label", "Player graph");
      container.prepend(this.canvas);
      this.ctx = this.canvas.getContext("2d");

      this.tip = document.createElement("div");
      this.tip.className = "tk-tip";
      this.tip.hidden = true;
      container.appendChild(this.tip);

      nodes.onChange((changed) => this._nodesChanged(changed));
      edges.onChange(() => this._sceneChanged());

      this._bindPointer();
      this._resize();
      if (typeof ResizeObserver !== "undefined") new ResizeObserver(() => this._resize()).observe(container);

      // If animation frames are paused (a hidden tab), a timer keeps animations and redraws moving.
      this.watchdog = setInterval(() => { if (performance.now() - this.lastFrame > 200 && this._busy()) this._frame(performance.now()); }, 100);
      this.lastFrame = performance.now();
    }

    // ------------------------------------------------------------- public API

    on(name, fn) { (this.handlers[name] = this.handlers[name] || []).push(fn); }

    emit(name, params) { (this.handlers[name] || []).forEach(fn => fn(params)); }

    getSelectedNodes() { return this.selected !== null ? [this.selected] : []; }

    redraw() { this.sceneDirty = true; this._wake(); }

    selectNodes(ids) {
      this._select(ids && ids.length ? ids[0] : null);
    }

    unselectAll() { this._select(null); }

    /** Zooms so everything is in view. */
    fit(options) {
      const all = this.nodes.get();
      if (!all.length || !this.W) return;

      let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
      for (const n of all) {
        const p = this.pos.get(n.id); if (!p) continue;
        const r = (n.size || 6) + 8;
        x0 = Math.min(x0, p.x - r); y0 = Math.min(y0, p.y - r); x1 = Math.max(x1, p.x + r); y1 = Math.max(y1, p.y + r + 30);
      }
      if (!isFinite(x0)) return;

      const scale = clamp(Math.min(this.W / Math.max(1, x1 - x0), this.H / Math.max(1, y1 - y0)) * 0.92, this.o.minScale, 1.2);
      this._viewTo({ scale, tx: this.W / 2 - ((x0 + x1) / 2) * scale, ty: this.H / 2 - ((y0 + y1) / 2) * scale }, options && options.animation ? 450 : 0);
    }

    /** Centres a bubble on screen. */
    focusOn(id, options) {
      const p = this._at(id);
      if (!p) return;
      const scale = clamp((options && options.scale) || this.view.scale, this.o.minScale, this.o.maxScale);
      const ms = options && options.animation ? (options.animation.duration || 450) : 0;
      this._viewTo({ scale, tx: this.W / 2 - p.x * scale, ty: this.H / 2 - p.y * scale }, ms);
    }

    /**
     * Slides bubbles to new spots. New bubbles are not slid (they are just placed by the caller).
     * @param moves [{ id, x, y }]
     * @param done  called once they have arrived
     */
    animateTo(moves, ms, done) {
      this._finishGlide();
      for (const m of moves) {
        const from = this.pos.get(m.id);
        if (from) this.moving.set(m.id, { from: { x: from.x, y: from.y }, to: { x: m.x, y: m.y }, now: { x: from.x, y: from.y } });
      }
      this.glide = { t0: performance.now(), ms, done };
      this.sceneDirty = true;
      this._wake();
    }

    // ---------------------------------------------------------------- changes

    _nodesChanged(changed) {
      if (changed === null) {                       // cleared
        this.pos.clear(); this.focus.clear(); this.moving.clear(); this.glide = null;
        this.selected = null; this.hovered = null; this.tip.hidden = true;
      } else {
        for (const item of changed) {
          const n = this.nodes.map.get(item.id);
          const old = this.pos.get(item.id);
          if (!old) this.pos.set(item.id, { x: n.x || 0, y: n.y || 0 });
          else if (item.x !== undefined || item.y !== undefined) this.pos.set(item.id, { x: n.x !== undefined ? n.x : old.x, y: n.y !== undefined ? n.y : old.y });
        }
        if (this.selected !== null && !this.nodes.map.has(this.selected)) this.selected = null;
      }
      this._geometryChanged();
    }

    _geometryChanged() { this.indexDirty = true; this._sceneChanged(); }

    _sceneChanged() { this.sceneDirty = true; this._wake(); }

    // ----------------------------------------------------------- sizes, spots

    _resize() {
      const w = this.container.clientWidth, h = this.container.clientHeight;
      if (!w || !h) return;
      const first = !this.W;
      this.dpr = Math.min(2, window.devicePixelRatio || 1);
      this.W = w; this.H = h;
      this.canvas.width = Math.round(w * this.dpr);
      this.canvas.height = Math.round(h * this.dpr);
      this.cacheView = null;
      if (first && this.nodes.length) this.fit();
      this._sceneChanged();
    }

    /** Where a bubble is drawn: its spot plus any push from a selected neighbour. */
    _at(id) {
      const p = this.pos.get(id);
      if (!p) return null;
      const m = this.moving.get(id);
      let x = m ? m.now.x : p.x, y = m ? m.now.y : p.y;
      for (const f of this.focus.values()) {
        const push = f.push.get(id);
        if (push && f.v > 0) { x += push.dx * f.v; y += push.dy * f.v; }
      }
      return { x, y };
    }

    /** Ids drawn on the live layer instead of in the cached picture. */
    _live() {
      const live = new Set();
      for (const [id, f] of this.focus) { live.add(id); for (const nid of f.push.keys()) live.add(nid); }
      for (const id of this.moving.keys()) live.add(id);
      if (this.dragging) live.add(this.dragging.id);
      return live;
    }

    _radius(id, n) {
      let r = (n.size || 6);
      const f = this.focus.get(id);
      if (f && f.v > 0) r *= 1 + this.o.growth * f.v;
      return r;
    }

    _buildIndex() {
      const CELL = 64;
      this.index = new Map();
      for (const n of this.nodes.map.values()) {
        const p = this.pos.get(n.id); if (!p) continue;
        const key = Math.floor(p.x / CELL) * 100003 + Math.floor(p.y / CELL);
        let cell = this.index.get(key);
        if (!cell) this.index.set(key, cell = []);
        cell.push({ id: n.id, x: p.x, y: p.y, r: (n.size || 6) + 3 });
      }
      this.indexDirty = false;
    }

    /** Bubbles whose centre is within `reach` of a point (their resting spots). */
    _near(x, y, reach) {
      if (this.indexDirty) this._buildIndex();
      const CELL = 64, out = [];
      const c0x = Math.floor((x - reach) / CELL), c1x = Math.floor((x + reach) / CELL);
      const c0y = Math.floor((y - reach) / CELL), c1y = Math.floor((y + reach) / CELL);
      for (let cx = c0x; cx <= c1x; cx++) {
        for (let cy = c0y; cy <= c1y; cy++) {
          const cell = this.index.get(cx * 100003 + cy);
          if (cell) for (const o of cell) if (Math.hypot(o.x - x, o.y - y) <= reach) out.push(o);
        }
      }
      return out;
    }

    /** The topmost bubble at a page point (x, y in pixels), or null. */
    getNodeAt(sx, sy) {
      const s = this.view.scale;
      const wx = (sx - this.view.tx) / s, wy = (sy - this.view.ty) / s;
      const slack = 3 / s;
      let best = null, bestScore = Infinity;

      const consider = (id, x, y, r) => {
        const d = Math.hypot(wx - x, wy - y);
        if (d <= r + slack) { const score = r; if (score < bestScore) { bestScore = score; best = id; } }
      };

      for (const id of this._live()) {                         // bubbles that are away from their spot
        const n = this.nodes.map.get(id), p = this._at(id);
        if (n && p) consider(id, p.x, p.y, this._radius(id, n));
      }
      const live = this._live();
      for (const o of this._near(wx, wy, 60 + slack)) {
        if (live.has(o.id)) continue;
        const n = this.nodes.map.get(o.id);
        if (n) consider(o.id, o.x, o.y, (n.size || 6));
      }
      return best;
    }

    // ----------------------------------------------------------------- pushing

    /**
     * Where the bubbles around one must go so nothing overlaps once it has grown (or been dropped).
     * Bubbles are moved away from it, and away from each other, a few passes; the ones just past the
     * edge of the area stay put and act as walls.
     * @returns Map id -> { dx, dy } (only the bubbles that have to move)
     */
    _pushFor(cx, cy, radius, ignoreId) {
      const reach = radius + 150;
      const near = this._near(cx, cy, reach + 80).filter(o => o.id !== ignoreId);
      const items = near.map(o => ({ id: o.id, ox: o.x, oy: o.y, x: o.x, y: o.y, r: o.r, fixed: Math.hypot(o.x - cx, o.y - cy) > reach }));
      const gap = this.o.gap;

      for (let pass = 0; pass < 14; pass++) {
        let moved = false;

        for (const a of items) {                                      // away from the growing bubble
          if (a.fixed) continue;
          const dx = a.x - cx, dy = a.y - cy, d = Math.hypot(dx, dy) || 0.001, need = radius + a.r + gap;
          if (d < need) { a.x = cx + (dx / d) * need; a.y = cy + (dy / d) * need; moved = true; }
        }

        const cells = new Map();                                       // then out of each other's way
        for (const a of items) {
          const key = Math.floor(a.x / 48) * 100003 + Math.floor(a.y / 48);
          let c = cells.get(key); if (!c) cells.set(key, c = []); c.push(a);
        }
        for (const a of items) {
          const gx = Math.floor(a.x / 48), gy = Math.floor(a.y / 48);
          for (let ox = -1; ox <= 1; ox++) for (let oy = -1; oy <= 1; oy++) {
            const c = cells.get((gx + ox) * 100003 + (gy + oy)); if (!c) continue;
            for (const b of c) {
              if (b === a || (a.fixed && b.fixed) || a.id > b.id) continue;
              let dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy);
              const need = a.r + b.r + gap;
              if (d >= need) continue;
              if (d < 0.001) { dx = 1; dy = 0; d = 1; }
              const push = need - d, ux = dx / d, uy = dy / d;
              if (a.fixed) { b.x += ux * push; b.y += uy * push; }
              else if (b.fixed) { a.x -= ux * push; a.y -= uy * push; }
              else { a.x -= ux * push / 2; a.y -= uy * push / 2; b.x += ux * push / 2; b.y += uy * push / 2; }
              moved = true;
            }
          }
        }
        if (!moved) break;
      }

      const out = new Map();
      for (const a of items) if (!a.fixed && Math.hypot(a.x - a.ox, a.y - a.oy) > 0.5) out.set(a.id, { dx: a.x - a.ox, dy: a.y - a.oy });
      return out;
    }

    // --------------------------------------------------------------- selection

    _select(id) {
      if (id === this.selected) return;
      const now = performance.now();

      if (this.selected !== null) {                                   // the old one shrinks back and its neighbours slide home
        const f = this.focus.get(this.selected);
        if (f) { f.v0 = f.v; f.target = 0; f.t0 = now; }
      }

      this.selected = id;
      if (id !== null) {
        const n = this.nodes.map.get(id), p = this.pos.get(id);
        if (n && p) {
          const old = this.focus.get(id);
          const push = this._pushFor(p.x, p.y, (n.size || 6) * (1 + this.o.growth) + 3, id);
          this.focus.set(id, { v: old ? old.v : 0, v0: old ? old.v : 0, target: 1, t0: now, push });
        }
      }
      this._sceneChanged();
      this.emit("select", { nodes: this.getSelectedNodes(), edges: [] });
    }

    // --------------------------------------------------------------- the view

    _viewTo(target, ms) {
      if (!ms || !this.W) {
        Object.assign(this.view, target);
        this.viewAnim = null;
        this.motionAt = performance.now();
        this._wake();
        return;
      }
      this.viewAnim = { t0: performance.now(), ms, from: Object.assign({}, this.view), to: target };
      this._wake();
    }

    _zoomAt(sx, sy, factor) {
      const v = this.view, old = v.scale;
      const scale = clamp(old * factor, this.o.minScale, this.o.maxScale);
      const wx = (sx - v.tx) / old, wy = (sy - v.ty) / old;
      v.scale = scale; v.tx = sx - wx * scale; v.ty = sy - wy * scale;
      this.viewAnim = null;
      this.motionAt = performance.now();
      this._wake();
    }

    // --------------------------------------------------------------- pointers

    _bindPointer() {
      const c = this.canvas;
      const pointers = new Map();
      let pinch = null;

      const local = (e) => { const r = c.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };

      c.addEventListener("pointerdown", (e) => {
        c.setPointerCapture(e.pointerId);
        const p = local(e);
        pointers.set(e.pointerId, p);
        this.tip.hidden = true;

        if (pointers.size === 2) {
          const [a, b] = [...pointers.values()];
          pinch = { d: Math.hypot(a.x - b.x, a.y - b.y) || 1 };
          this.drag = null;
          return;
        }

        const id = this.getNodeAt(p.x, p.y);
        const n = id !== null ? this.nodes.map.get(id) : null;
        this.drag = { start: p, last: p, moved: false, id, node: n && !n.fixed ? n : null, grab: null };
        if (this.drag.node) {
          const at = this.pos.get(id), s = this.view.scale;
          this.drag.grab = { x: at.x - (p.x - this.view.tx) / s, y: at.y - (p.y - this.view.ty) / s };
        }
      });

      c.addEventListener("pointermove", (e) => {
        const p = local(e);
        if (!pointers.has(e.pointerId)) { this._hover(p); return; }
        pointers.set(e.pointerId, p);

        if (pointers.size === 2 && pinch) {
          const [a, b] = [...pointers.values()];
          const d = Math.hypot(a.x - b.x, a.y - b.y) || 1;
          this._zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, d / pinch.d);
          pinch.d = d;
          return;
        }

        const d = this.drag;
        if (!d) return;
        if (!d.moved && Math.hypot(p.x - d.start.x, p.y - d.start.y) > 4) d.moved = true;
        if (!d.moved) return;

        if (d.node) {                                                 // dragging a bubble
          const s = this.view.scale;
          this.dragging = { id: d.id };
          this.pos.set(d.id, { x: (p.x - this.view.tx) / s + d.grab.x, y: (p.y - this.view.ty) / s + d.grab.y });
          this.indexDirty = true;
          this.sceneDirty = true;
          c.style.cursor = "grabbing";
        } else {                                                      // dragging the background
          this.view.tx += p.x - d.last.x; this.view.ty += p.y - d.last.y;
          this.viewAnim = null;
          this.motionAt = performance.now();
          c.style.cursor = "grabbing";
        }
        d.last = p;
        this._wake();
      });

      const up = (e) => {
        const p = local(e);
        pointers.delete(e.pointerId);
        if (pointers.size < 2) pinch = null;
        const d = this.drag;
        this.drag = null;
        c.style.cursor = this.hovered !== null ? "pointer" : "default";
        if (!d) return;

        if (!d.moved) {                                               // a click
          this._select(d.id);
          this.emit("click", { nodes: d.id !== null ? [d.id] : [], edges: [], pointer: p });
        } else if (d.node) {                                          // a bubble was dropped: make room for it
          this.dragging = null;
          const at = this.pos.get(d.id);
          const push = this._pushFor(at.x, at.y, (d.node.size || 6) + 3, d.id);
          const moves = [...push].map(([id, o]) => { const q = this.pos.get(id); return { id, x: q.x + o.dx, y: q.y + o.dy }; });
          this.indexDirty = true;
          if (moves.length) this.animateTo(moves, 260, () => { this.indexDirty = true; });
          else this._sceneChanged();
        }
      };
      c.addEventListener("pointerup", up);
      c.addEventListener("pointercancel", up);

      c.addEventListener("pointerleave", () => { this._setHover(null); });

      c.addEventListener("wheel", (e) => {
        e.preventDefault();
        const p = local(e);
        const delta = e.deltaMode === 1 ? e.deltaY * 16 : e.deltaY;
        this._zoomAt(p.x, p.y, Math.exp(-clamp(delta, -300, 300) * 0.0014));
      }, { passive: false });
    }

    _hover(p) {
      if (this.hoverQueued) { this.hoverPoint = p; return; }
      this.hoverPoint = p;
      this.hoverQueued = true;
      requestAnimationFrame(() => {
        this.hoverQueued = false;
        const id = this.getNodeAt(this.hoverPoint.x, this.hoverPoint.y);
        this._setHover(id, this.hoverPoint);
      });
    }

    _setHover(id, p) {
      if (id !== this.hovered) { this.hovered = id; this.canvas.style.cursor = id !== null ? "pointer" : "default"; this._wake(); }
      const n = id !== null ? this.nodes.map.get(id) : null;
      if (n && n.title && p) {
        this.tip.textContent = n.title;
        this.tip.hidden = false;
        this.tip.style.left = Math.min(p.x + 14, this.W - 20) + "px";
        this.tip.style.top = (p.y + 16) + "px";
      } else {
        this.tip.hidden = true;
      }
    }

    // ------------------------------------------------------------------ frames

    _busy() {
      if (this.viewAnim || this.glide || this.sceneDirty || performance.now() - this.motionAt < this.o.idleMs + 40) return true;
      for (const f of this.focus.values()) if (Math.abs(f.v - f.target) > 0.001) return true;     // still growing or shrinking
      return false;
    }

    _wake() {
      if (this.frameQueued) return;
      this.frameQueued = true;
      requestAnimationFrame((t) => { this._frame(performance.now()); });
    }

    _finishGlide() {
      if (!this.glide) return;
      for (const [id, m] of this.moving) this.pos.set(id, { x: m.to.x, y: m.to.y });
      const done = this.glide.done;
      this.moving.clear(); this.glide = null;
      this.indexDirty = true; this.sceneDirty = true;
      if (done) done();
    }

    /** Moves every animation on to the time `now`. */
    _advance(now) {
      if (this.viewAnim) {
        const a = this.viewAnim, k = clamp((now - a.t0) / a.ms, 0, 1), e = easeInOut(k);
        this.view.scale = a.from.scale + (a.to.scale - a.from.scale) * e;
        this.view.tx = a.from.tx + (a.to.tx - a.from.tx) * e;
        this.view.ty = a.from.ty + (a.to.ty - a.from.ty) * e;
        this.motionAt = now;
        if (k >= 1) this.viewAnim = null;
      }

      if (this.glide) {
        const k = clamp((now - this.glide.t0) / this.glide.ms, 0, 1), e = easeInOut(k);
        for (const m of this.moving.values()) m.now = { x: m.from.x + (m.to.x - m.from.x) * e, y: m.from.y + (m.to.y - m.from.y) * e };
        if (k >= 1) this._finishGlide();
      }

      for (const [id, f] of this.focus) {
        const k = clamp((now - f.t0) / this.o.growMs, 0, 1), e = easeOut(k);
        f.v = k >= 1 ? f.target : f.v0 + (f.target - f.v0) * e;
        if (k >= 1 && f.target === 0) { this.focus.delete(id); this.sceneDirty = true; }
      }
    }

    _frame(now) {
      this.frameQueued = false;
      this.lastFrame = now;
      if (!this.W) return;

      this._advance(now);

      // Redraw the cached picture when something changed and the view has been still for a moment.
      const still = now - this.motionAt >= this.o.idleMs;
      if (this.sceneDirty && (still || !this.cacheView) && (!this.cacheView || now - this.lastScene > 45)) {
        const t0 = performance.now();
        this._renderScene();
        this.stats.sceneMs = performance.now() - t0; this.stats.scenes++;
        this.lastScene = performance.now();
      } else if (this.cacheView && this._viewFarFromCache() && still) {
        this.sceneDirty = true;
      }

      const t1 = performance.now();
      this._draw();
      this.stats.blitMs = performance.now() - t1; this.stats.frames++;

      if (this._busy()) {
        if (this.sceneDirty && !still) setTimeout(() => this._wake(), this.o.idleMs);   // redraw the picture once you stop
        else this._wake();
      }
    }

    _viewFarFromCache() {
      const c = this.cacheView, v = this.view;
      return !c || Math.abs(Math.log(v.scale / c.scale)) > 0.02 || Math.abs(v.tx - c.tx) > 1 || Math.abs(v.ty - c.ty) > 1;
    }

    // ----------------------------------------------------------- drawing: scene

    _style(n) {
      const c = n.color || {};
      return { fill: c.background || "#7a8599", ring: c.border || "#4d566a", bw: n.borderWidth === undefined ? 1 : n.borderWidth };
    }

    /**
     * Draws bubbles in batches. Bubbles are grouped by look, so a thousand of the same colour is one
     * path and one fill. `at(id)` says where each is; `radius(id, n)` how big.
     */
    _drawNodes(ctx, list, ox, oy, s, at, radius) {
      const groups = new Map();
      for (const n of list) {
        const p = at(n.id); if (!p) continue;
        const st = this._style(n);
        const key = st.fill + "|" + st.ring + "|" + st.bw;
        let g = groups.get(key);
        if (!g) groups.set(key, g = { st, items: [] });
        g.items.push(p.x * s + ox, p.y * s + oy, Math.max(1.3, radius(n) * s));
      }

      for (const g of groups.values()) {
        const items = g.items, ring = g.st.bw > 0;
        ctx.beginPath();
        for (let i = 0; i < items.length; i += 3) { ctx.moveTo(items[i] + items[i + 2], items[i + 1]); ctx.arc(items[i], items[i + 1], items[i + 2], 0, TAU); }
        ctx.fillStyle = g.st.fill; ctx.fill();
        if (ring) { ctx.lineWidth = clamp(g.st.bw * s, 0.8, 7); ctx.strokeStyle = g.st.ring; ctx.stroke(); }
      }
    }

    _drawLabels(ctx, list, ox, oy, s, at, radius, always) {
      ctx.textAlign = "center";
      ctx.lineJoin = "round";
      for (const n of list) {
        if (!n.label) continue;
        const p = at(n.id); if (!p) continue;
        const isBig = n.type !== "player";
        const base = (n.font && n.font.size) || (isBig ? 17 : 10);
        const px = isBig ? clamp(base * Math.sqrt(s) * 1.1, 12, 26) : clamp(base * s, this.o.labelMinPx, 15);
        const X = p.x * s + ox, Y = p.y * s + oy + radius(n) * s + px * 0.95;
        ctx.font = (isBig ? "600 " : "") + px.toFixed(1) + "px Segoe UI, system-ui, sans-serif";
        ctx.lineWidth = 3; ctx.strokeStyle = "#12151c"; ctx.strokeText(n.label, X, Y);
        ctx.fillStyle = "#e6e9ef"; ctx.fillText(n.label, X, Y);
      }
    }

    _renderScene() {
      const W = this.W, H = this.H, dpr = this.dpr, v = this.view;
      const mx = Math.round(W * 0.3), my = Math.round(H * 0.3);
      const cw = Math.round((W + 2 * mx) * dpr), ch = Math.round((H + 2 * my) * dpr);
      if (this.cache.width !== cw || this.cache.height !== ch) { this.cache.width = cw; this.cache.height = ch; }

      const ctx = this.cache.getContext("2d");
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, cw / dpr, ch / dpr);

      const s = v.scale, ox = v.tx + mx, oy = v.ty + my;
      const x0 = (-mx - v.tx) / s - 30, x1 = (W + mx - v.tx) / s + 30, y0 = (-my - v.ty) / s - 30, y1 = (H + my - v.ty) / s + 30;
      const live = this._live();
      const rest = (id) => this.pos.get(id);

      // Edges, one path per look.
      const styles = new Map();
      for (const e of this.edges.map.values()) {
        if (live.has(e.from) || live.has(e.to)) continue;
        const a = rest(e.from), b = rest(e.to);
        if (!a || !b) continue;
        if ((a.x < x0 && b.x < x0) || (a.x > x1 && b.x > x1) || (a.y < y0 && b.y < y0) || (a.y > y1 && b.y > y1)) continue;
        const col = (e.color && e.color.color) || "#3a4358", alpha = (e.color && e.color.opacity) !== undefined ? e.color.opacity : 0.45;
        const key = col + "|" + alpha + "|" + (e.dashes ? 1 : 0);
        let g = styles.get(key);
        if (!g) styles.set(key, g = { col, alpha, dashed: !!e.dashes, lines: [] });
        g.lines.push(a.x * s + ox, a.y * s + oy, b.x * s + ox, b.y * s + oy);
      }
      ctx.lineWidth = 1;
      for (const g of styles.values()) {
        ctx.globalAlpha = g.alpha; ctx.strokeStyle = g.col; ctx.setLineDash(g.dashed ? [3, 7] : []);
        ctx.beginPath();
        for (let i = 0; i < g.lines.length; i += 4) { ctx.moveTo(g.lines[i], g.lines[i + 1]); ctx.lineTo(g.lines[i + 2], g.lines[i + 3]); }
        ctx.stroke();
      }
      ctx.globalAlpha = 1; ctx.setLineDash([]);

      // Bubbles that are on screen, and not on the live layer.
      const players = [], others = [], glowing = [];
      for (const n of this.nodes.map.values()) {
        if (live.has(n.id)) continue;
        const p = this.pos.get(n.id); if (!p) continue;
        const r = n.size || 6;
        if (p.x + r < x0 || p.x - r > x1 || p.y + r < y0 || p.y - r > y1) continue;
        if (n.shadow && n.shadow.enabled) glowing.push(n);
        (n.type === "player" ? players : others).push(n);
      }

      const at = (id) => this.pos.get(id), radius = (n) => n.size || 6;
      this._drawNodes(ctx, players, ox, oy, s, at, radius);
      for (const n of glowing) {                       // the blue glow round players you have partied with
        if (n.type !== "player") continue;
        const p = this.pos.get(n.id);
        ctx.save(); ctx.shadowColor = n.shadow.color || "#38a0ff"; ctx.shadowBlur = clamp(20 * Math.sqrt(s), 6, 26);
        ctx.beginPath(); ctx.arc(p.x * s + ox, p.y * s + oy, Math.max(2, (n.size || 6) * s), 0, TAU);
        const st = this._style(n); ctx.fillStyle = st.fill; ctx.fill(); ctx.lineWidth = clamp(st.bw * s, 1, 5); ctx.strokeStyle = st.ring; ctx.stroke(); ctx.restore();
      }
      this._drawNodes(ctx, others, ox, oy, s, at, radius);

      // Names: the world names always, and the biggest bubbles' names once zoomed in enough to read them.
      this._drawLabels(ctx, others, ox, oy, s, at, radius, true);
      if (s * 10 >= this.o.labelMinPx) {
        players.sort((a, b) => (b.size || 0) - (a.size || 0));
        this._drawLabels(ctx, players.slice(0, this.o.maxLabels), ox, oy, s, at, radius, false);
      }

      this.cacheView = { scale: s, tx: v.tx, ty: v.ty, mx, my };
      this.sceneDirty = false;
    }

    // ---------------------------------------------------- drawing: each frame

    _draw() {
      const ctx = this.ctx, dpr = this.dpr, v = this.view, c = this.cacheView;
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

      // 1. The cached picture, moved and scaled to match the current view.
      if (c) {
        const k = v.scale / c.scale;
        ctx.setTransform(k, 0, 0, k, dpr * ((-c.mx - c.tx) * k + v.tx), dpr * ((-c.my - c.ty) * k + v.ty));
        ctx.drawImage(this.cache, 0, 0);
      }

      // 2. The live layer: bubbles that are moving, the selected one, the hovered one.
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const s = v.scale, ox = v.tx, oy = v.ty;
      const live = this._live();

      if (live.size) {
        const list = [];
        for (const id of live) { const n = this.nodes.map.get(id); if (n && n.id !== this.selected) list.push(n); }
        const at = (id) => this._at(id), radius = (n) => this._radius(n.id, n);

        const edgeGroups = new Map();
        for (const e of this.edges.map.values()) {
          if (!(live.has(e.from) || live.has(e.to))) continue;
          const a = at(e.from), b = at(e.to); if (!a || !b) continue;
          const col = (e.color && e.color.color) || "#3a4358", alpha = (e.color && e.color.opacity) !== undefined ? e.color.opacity : 0.45;
          const key = col + "|" + alpha; let g = edgeGroups.get(key);
          if (!g) edgeGroups.set(key, g = { col, alpha, lines: [] });
          g.lines.push(a.x * s + ox, a.y * s + oy, b.x * s + ox, b.y * s + oy);
        }
        ctx.lineWidth = 1;
        for (const g of edgeGroups.values()) {
          ctx.globalAlpha = g.alpha; ctx.strokeStyle = g.col; ctx.beginPath();
          for (let i = 0; i < g.lines.length; i += 4) { ctx.moveTo(g.lines[i], g.lines[i + 1]); ctx.lineTo(g.lines[i + 2], g.lines[i + 3]); }
          ctx.stroke();
        }
        ctx.globalAlpha = 1;

        this._drawNodes(ctx, list.filter(n => n.type === "player"), ox, oy, s, at, radius);
        this._drawNodes(ctx, list.filter(n => n.type !== "player"), ox, oy, s, at, radius);
        if (s * 10 >= this.o.labelMinPx) this._drawLabels(ctx, list.filter(n => n.type === "player").slice(0, 200), ox, oy, s, at, radius, false);
        this._drawLabels(ctx, list.filter(n => n.type !== "player"), ox, oy, s, at, radius, true);
      }

      // The selected bubble goes on top: its own lines light up, it is drawn larger, and it is always named.
      if (this.selected !== null) {
        const n = this.nodes.map.get(this.selected), p = n && this._at(this.selected);
        if (n && p) {
          const own = [];
          for (const e of this.edges.map.values()) if (e.from === n.id || e.to === n.id) { own.push(e); if (own.length > 60) break; }
          if (own.length <= 60) {
            ctx.strokeStyle = "#ff4fa6"; ctx.globalAlpha = 0.85; ctx.lineWidth = 1.6; ctx.beginPath();
            for (const e of own) { const a = this._at(e.from), b = this._at(e.to); if (a && b) { ctx.moveTo(a.x * s + ox, a.y * s + oy); ctx.lineTo(b.x * s + ox, b.y * s + oy); } }
            ctx.stroke(); ctx.globalAlpha = 1;
          }

          const r = this._radius(n.id, n) * s, st = this._style(n);
          ctx.beginPath(); ctx.arc(p.x * s + ox, p.y * s + oy, Math.max(2, r), 0, TAU);
          ctx.fillStyle = st.fill; ctx.fill();
          ctx.lineWidth = Math.max(3, clamp(st.bw * s, 1, 7)); ctx.strokeStyle = "#ff4fa6"; ctx.stroke();
          if (n.label) {
            const px = clamp(13 * Math.sqrt(Math.max(s, 0.5)), 12, 20);
            ctx.textAlign = "center"; ctx.font = "600 " + px.toFixed(1) + "px Segoe UI, system-ui, sans-serif";
            const X = p.x * s + ox, Y = p.y * s + oy + r + px + 2;
            ctx.lineWidth = 4; ctx.strokeStyle = "#12151c"; ctx.strokeText(n.label, X, Y); ctx.fillStyle = "#fff"; ctx.fillText(n.label, X, Y);
          }
        }
      }

      if (this.hovered !== null && this.hovered !== this.selected) {
        const n = this.nodes.map.get(this.hovered), p = n && this._at(this.hovered);
        if (n && p) {
          ctx.beginPath(); ctx.arc(p.x * s + ox, p.y * s + oy, Math.max(3, this._radius(n.id, n) * s + 2), 0, TAU);
          ctx.lineWidth = 2; ctx.strokeStyle = "rgba(255,255,255,.85)"; ctx.stroke();
        }
      }
    }
  }

  window.GraphKit = { DataSet, FastNetwork };
})();
} catch (e) { console.error("graph:", e); }
