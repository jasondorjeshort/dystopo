// The pull side of the browser's trip replica and waypoint editing (T92). Loaded by tools/demo/index.html in live mode
// after trips.js (T68), whose replica it shares: the operation log in localStorage (dystopo.trips) and window.dystopoTrips.
//
// T107: both parts live in trips.js's Trips box: "Get from server" beside "Send to server", the waypoints in the open
// route's card.
//
// Pull: "Get from server" asks the server's sync peer for this user's routes (GET /sync/routes?full=1, with the
// page's Server token when set) and merges them into the browser replica's trip list: a route the browser does not have, or whose
// server copy changed after the browser last saved it, is taken from the server (so trips made or edited on a phone show
// up here); a trip the browser had pushed (or pulled) that the server no longer lists was deleted elsewhere and goes. The
// operations stay where they were made: the server already holds them, and the browser's next push sends only its own.
//
// Waypoints: for the trip open in the editor (saved first), "Add waypoint" then a click on the map drops a named
// waypoint; drag one to move it, rename or remove it in the list. Each edit is the phone's per-waypoint write (T65: one
// `wpt:<id>` field per waypoint, null when removed), encoded by the server (POST /sync/waypoints/encode), appended to
// the replica's log with its own clock and pushed like any trip edit, so concurrent edits of different waypoints on a
// phone and here both survive. The waypoints of each trip live in localStorage under dystopo.tripWaypoints.
//
// window.dystopoTripSync is the scripting surface (tools/demo-check.py --trip-sync drives it and the real mouse).

const KEY = "dystopo.trips";
const WKEY = "dystopo.tripWaypoints";

function uuid4() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map(x => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
const load = (k, d) => { try { return JSON.parse(localStorage.getItem(k) || "null") || d; } catch (e) { return d; } };
const store = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch (e) { return false; } };
// trips.js writes the replica; read it fresh before every change and write it straight back.
function replica() {
  const r = load(KEY, null);
  return r && r.v === 1 && r.device ? r : null;
}
// The same hybrid logical clock text as trips.js and Hlc.text in shared/.../sync.
function tick(r) {
  const now = Date.now();
  if (now > r.wall) { r.wall = now; r.counter = 0; } else r.counter++;
  return r.wall.toString(16).padStart(12, "0") + "." + r.counter.toString(16).padStart(6, "0") + "." + r.device;
}

export function install({ map, base, panel, token, units }) {
  const metric = units === "m";
  // trips.js's distance rule (T101's) when it is there; this older form only for a page without it.
  const fmtDist = m => window.dystopoTrips && window.dystopoTrips.fmtDist ? window.dystopoTrips.fmtDist(m) : metric ? (m >= 1000 ? (m / 1000).toFixed(2) + " km" : Math.round(m) + " m") : (m / 1609.344).toFixed(2) + " mi";
  const auth = (opts = {}) => { const t = token ? token() : ""; return t ? { ...opts, headers: { ...(opts.headers || {}), Authorization: "Bearer " + t } } : opts; };
  const trips = () => window.dystopoTrips;
  const NEEDS_TOKEN = "the server wants its token (Server token, in the panel on the left)";

  // T107: no box of its own any more. The waypoints of the open trip sit in trips.js's route card (#trip-extra) and
  // "Get from server" beside "Send to server" (#trip-sync-row), with this script's status line under them; without
  // trips.js's box (an older page) it falls back to a box of its own in the panel.
  const css = document.createElement("style");
  css.textContent = `
    #trip-waypoints h4 { font-size: 12px; margin: 8px 0 2px; }
    #trip-waypoints button[aria-pressed="true"] { background: #1f78c8; color: #fff; border-color: #155a96; }
    #trip-waypoints .row { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 6px; margin: 2px 0; }
    #wpt-name { flex: 1; min-width: 8em; }
    #wpt-list { list-style: none; margin: 2px 0; padding: 0; max-height: 18vh; overflow: auto; font-size: 12px; }
    #wpt-list li { display: flex; gap: 4px; align-items: center; } #wpt-list input { flex: 1; min-width: 0; }
    #sync-status { font-size: 12px; color: #6b5f55; white-space: pre-wrap; }
    .trip-wpt { width: 14px; height: 14px; border-radius: 3px 3px 3px 0; transform: rotate(-45deg); background: #1f78c8; border: 2px solid #fff;
                box-shadow: 0 1px 3px rgba(0,0,0,.4); cursor: grab; }
    .trip-adding .maplibregl-canvas { cursor: copy; }`;
  document.head.appendChild(css);
  const wbox = document.createElement("div");
  wbox.id = "trip-waypoints";
  wbox.innerHTML = `<h4>Waypoints <span id="wpt-trip"></span></h4>
    <div class="row"><button id="wpt-add" type="button" aria-pressed="false" disabled title="Then click the map where the waypoint goes">Add waypoint</button>
      <input id="wpt-name" placeholder="Name (optional)" aria-label="Waypoint name"></div>
    <ul id="wpt-list"></ul>`;
  const pullBtn = document.createElement("button");
  pullBtn.id = "sync-pull";
  pullBtn.type = "button";
  pullBtn.title = "Trips saved on a phone or in another browser, from this server";
  pullBtn.textContent = "Get from server";
  const status = document.createElement("div");
  status.id = "sync-status";
  status.setAttribute("role", "status");
  const extra = document.getElementById("trip-extra"), row = document.getElementById("trip-sync-row");
  if (extra && row) {
    extra.appendChild(wbox);
    row.insertBefore(pullBtn, row.firstChild);
    row.parentNode.insertBefore(status, row.nextSibling);
  } else {
    const box = document.createElement("details");
    box.open = true;
    box.innerHTML = "<summary>Trips on the server and waypoints</summary>";
    box.append(pullBtn, wbox, status);
    (panel || document.body).appendChild(box);
  }
  const $ = id => document.getElementById(id);
  const say = text => { $("sync-status").textContent = text; };

  const st = { adding: false, open: null, lastPull: null, busy: 0, error: null, edits: 0 };
  let markers = [];
  let queue = Promise.resolve();
  const enqueue = job => (queue = queue.then(job).catch(e => { st.error = String(e.message || e); say("Error: " + st.error); }));

  // ---- the trip list (the same rows as trips.js draws, refreshed after a pull) ---------------------------------
  function renderTrips() {
    const t = trips();
    if (t && t.renderList) { t.renderList(); return; } // T107: trips.js draws the list (pulled trips say "from the server")
    const ul = document.getElementById("trip-list");
    if (!ul || !t) return;
    ul.innerHTML = "";
    for (const trip of t.trips()) {
      const li = document.createElement("li");
      const label = document.createElement("span");
      label.textContent = trip.name + " · " + fmtDist(trip.distanceM || 0) + (trip.pulled ? " (from the server)" : "");
      const open = document.createElement("button"); open.type = "button"; open.textContent = "Open"; open.onclick = () => t.load(trip.id);
      const del = document.createElement("button"); del.type = "button"; del.textContent = "Delete"; del.onclick = () => t.del(trip.id);
      li.append(label, open, del);
      ul.appendChild(li);
    }
    if (!ul.children.length) ul.innerHTML = "<li><span>No saved trips yet.</span></li>";
  }

  // ---- pull ------------------------------------------------------------------------------------------------------
  async function pull() {
    st.busy++;
    try {
      const r = await fetch(base + "/sync/routes?full=1", auth());
      const j = await r.json().catch(() => ({}));
      if (!r.ok) {
        const result = { ok: false, status: r.status, error: r.status === 401 ? NEEDS_TOKEN : (j.error || "HTTP " + r.status) };
        st.lastPull = result;
        return result;
      }
      const rep = replica() || { v: 1, device: uuid4(), wall: 0, counter: 0, ops: [], trips: {}, lastPush: null };
      const wp = load(WKEY, {});
      const seen = new Set();
      let added = 0, updated = 0, removed = 0;
      const changedIds = new Set();
      for (const route of j.routes || []) {
        seen.add(route.id);
        const local = rep.trips[route.id];
        const remoteAt = route.updatedAt || 0;
        if (local && (local.savedAt || 0) >= remoteAt && local.path === route.path) continue; // ours is as new
        if (local && (local.savedAt || 0) > remoteAt) continue; // edited here since: the browser's next push wins by its clock
        rep.trips[route.id] = { id: route.id, name: route.name, path: route.path, pack: local ? local.pack : null, mode: route.mode,
                                distanceM: route.distanceM, ascentM: route.ascentM, timeS: route.timeS, savedAt: remoteAt || Date.now(), pulled: true };
        wp[route.id] = (route.waypointList || []).map(w => ({ id: w.id, name: w.name, lat: w.lat, lon: w.lon, ele: w.ele ?? null }));
        if (local) { updated++; changedIds.add(route.id); } else added++;
      }
      // Deleted elsewhere: on the server before (pulled, or saved here before the last successful push) and gone now.
      const pushedAt = rep.lastPush && rep.lastPush.ok ? Date.parse(rep.lastPush.at) : 0;
      for (const [id, t] of Object.entries(rep.trips)) {
        if (seen.has(id)) continue;
        if (t.pulled || (t.savedAt || 0) <= pushedAt) { delete rep.trips[id]; delete wp[id]; removed++; }
      }
      store(KEY, rep);
      store(WKEY, wp);
      renderTrips();
      const result = { ok: true, routes: (j.routes || []).length, added, updated, removed, at: new Date().toISOString() };
      st.lastPull = result;
      // The trip open in the editor changed elsewhere: show the server's version (its name, path and waypoints).
      const open = trips() && trips().state().id;
      if (open && changedIds.has(open)) await trips().load(open);
      refresh(true);
      return result;
    } catch (e) {
      st.lastPull = { ok: false, error: String(e.message || e) };
      return st.lastPull;
    } finally { st.busy--; }
  }

  // ---- waypoints -------------------------------------------------------------------------------------------------
  const openTrip = () => {
    const s = trips() && trips().state();
    const rep = replica();
    return s && s.id && rep && rep.trips[s.id] ? s.id : null;
  };
  const listOf = id => (load(WKEY, {})[id] || []);
  function draw() {
    for (const m of markers) m.remove();
    const id = st.open;
    markers = (id ? listOf(id) : []).map(w => {
      const el = document.createElement("div");
      el.className = "trip-wpt";
      el.title = w.name;
      el.dataset.id = w.id;
      el.addEventListener("contextmenu", e => { e.preventDefault(); e.stopPropagation(); api.remove(w.id); });
      const m = new maplibregl.Marker({ element: el, draggable: true, anchor: "bottom-left" }).setLngLat([w.lon, w.lat]).addTo(map);
      m.on("dragend", () => { const p = m.getLngLat(); api.move(w.id, p.lat, p.lng); });
      return m;
    });
    const ul = $("wpt-list");
    ul.innerHTML = "";
    for (const w of id ? listOf(id) : []) {
      const li = document.createElement("li");
      const name = document.createElement("input"); name.value = w.name; name.dataset.id = w.id;
      name.onchange = () => api.rename(w.id, name.value);
      const go = document.createElement("button"); go.type = "button"; go.textContent = "Show"; go.onclick = () => map.easeTo({ center: [w.lon, w.lat], duration: 300 });
      const del = document.createElement("button"); del.type = "button"; del.textContent = "Remove"; del.onclick = () => api.remove(w.id);
      li.append(name, go, del);
      ul.appendChild(li);
    }
    $("wpt-trip").textContent = id ? "(" + listOf(id).length + ")" : "\u2014 save the trip to add them";
    $("wpt-add").disabled = !id;
    if (!id) setAdding(false);
  }
  function refresh(force) {
    const id = openTrip();
    if (force || id !== st.open) { st.open = id; draw(); }
  }
  // One edit: the new list for the open trip, the wpt: writes between old and new (encoded by the server), then a push.
  const edit = change => enqueue(async () => {
    const id = openTrip();
    if (!id) throw new Error("save the trip first: waypoints belong to a saved trip");
    const wp = load(WKEY, {});
    const before = wp[id] || [];
    const after = change(before.map(w => ({ ...w })));
    const old = new Map(before.map(w => [w.id, w]));
    const now = new Map(after.map(w => [w.id, w]));
    const changed = after.filter(w => { const o = old.get(w.id); return !o || o.name !== w.name || o.lat !== w.lat || o.lon !== w.lon; });
    const gone = before.filter(w => !now.has(w.id));
    if (!changed.length && !gone.length) return null;
    st.busy++;
    try {
      let values = {};
      if (changed.length) {
        const r = await fetch(base + "/sync/waypoints/encode", auth({ method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ waypoints: changed.map(w => ({ id: w.id, name: w.name, lat: w.lat, lon: w.lon, ele: w.ele })) }) }));
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(r.status === 401 ? NEEDS_TOKEN : (j.error || "HTTP " + r.status));
        values = j.values;
      }
      const rep = replica();
      for (const w of changed) rep.ops.push({ hlc: tick(rep), type: "set", id, field: "wpt:" + w.id, value: values[w.id] });
      for (const w of gone) rep.ops.push({ hlc: tick(rep), type: "set", id, field: "wpt:" + w.id, value: null });
      rep.trips[id].savedAt = Date.now();
      store(KEY, rep);
      wp[id] = after;
      store(WKEY, wp);
      st.edits++;
      draw();
      const pushed = await trips().push();
      const what = (changed.length ? (changed.length === 1 ? "Waypoint saved" : changed.length + " waypoints saved") : "") +
                   (gone.length ? (changed.length ? ", " : "") + (gone.length === 1 ? "waypoint removed" : gone.length + " waypoints removed") : "");
      say(what.charAt(0).toUpperCase() + what.slice(1) + (pushed.ok ? " here and on the server." : " in this browser only: " + pushed.error + "."));
      return pushed;
    } finally { st.busy--; }
  });
  const round7 = v => Math.round(v * 1e7) / 1e7;
  const api = {
    pull: () => enqueue(pull),
    add(lat, lon, name) {
      return edit(list => [...list, { id: uuid4(), name: (name || $("wpt-name").value || "").trim() || "Waypoint " + (list.length + 1), lat: round7(lat), lon: round7(lon), ele: null }]);
    },
    move: (wid, lat, lon) => edit(list => list.map(w => w.id === wid ? { ...w, lat: round7(lat), lon: round7(lon) } : w)),
    rename: (wid, name) => (name || "").trim() ? edit(list => list.map(w => w.id === wid ? { ...w, name: name.trim() } : w)) : (draw(), queue),
    remove: wid => edit(list => list.filter(w => w.id !== wid)),
    waypoints: () => (st.open ? listOf(st.open) : []),
    idle: () => queue.then(() => api.state()),
    state: () => ({ open: st.open, adding: st.adding, busy: st.busy, error: st.error, edits: st.edits, lastPull: st.lastPull,
                    waypoints: api.waypoints(), markers: markers.map(m => { const p = m.getLngLat(); return [p.lat, p.lng]; }) }),
    setAdding,
    refresh: () => refresh(true),
  };
  function setAdding(on) {
    st.adding = !!on && !!st.open;
    if (st.adding && trips() && trips().state().planning) trips().setPlanning(false); // one tool takes the map's clicks
    $("wpt-add").setAttribute("aria-pressed", String(st.adding));
    map.getContainer().classList.toggle("trip-adding", st.adding);
  }

  // ---- wiring ----------------------------------------------------------------------------------------------------
  $("sync-pull").onclick = async () => {
    const r = await api.pull();
    say(r && r.ok ? `${r.routes} ${r.routes === 1 ? "trip" : "trips"} on the server: ${r.added} new here, ${r.updated} changed, ${r.removed} removed.` : "Could not get trips: " + (r ? r.error : "error") + ".");
  };
  $("wpt-add").onclick = () => setAdding(!st.adding);
  map.on("click", e => {
    if (!st.adding) return;
    if (trips() && trips().state().planning) { setAdding(false); return; } // the route planner took the clicks back
    if (e.originalEvent && e.originalEvent.target.closest && e.originalEvent.target.closest(".trip-wpt, .trip-via")) return;
    api.add(e.lngLat.lat, e.lngLat.lng);
  });
  // trips.js opens and saves trips on its own; follow which one is open.
  setInterval(() => refresh(false), 400);
  refresh(true);
  window.dystopoTripSync = api;
  return api;
}
