// Trip editor for the browser map (T68). Loaded by tools/demo/index.html in live mode (devtool serve --live, or the
// standalone server), because it plans on the server: GET /route and POST /route/edit run the phone's shared router and
// route editor (serverlib RouteRoutes.kt), so a route planned here has the phone's numbers.
//
// Use: "Plan route", click the start and the end (the route follows trails; a dashed straight line where no trail
// connects), click again to add a via point (into the nearest leg when it passes close, else as the new end), drag a
// numbered point to move it (only its two legs are rerouted), right-click a point to remove it. The card shows
// distance, climb, the hike-model time and the elevation profile (hover it to see the place on the map).
// Mode drive (T73) follows the packs' roads (one-ways, roads closed to cars) to and between trailheads; the card says
// it is not a road navigator, and a pack built before the road data says it must be rebuilt (the server's messages).
//
// "Save as trip" writes the route into this browser's own sync replica: an operation log in localStorage
// (dystopo.trips: a device id, a hybrid logical clock and route ops, the same put + path + waypoints ops the phone
// writes), then sends it to the server's sync peer (POST /route/bundle turns the ops into a sync bundle, POST
// /sync/push merges it; with the page's Token when set, so a server with per-user tokens stores it in that user's
// replica). Where the server refuses (no token on a server that wants one, or offline) the trip stays in this browser
// only; "Send to server" retries, ".dsync" saves the same bundle as a file the phone can sync with. A phone syncing
// with the server then has the trip in its Routes list.
//
// Long drives (T77): the server plans across every pack near the route, searching the tiles away from the ends through
// its drive overlay; the card then says how many packs the route crossed and how long the server took
// (`longRoute` in state()). Before navigating such a route offline the phone downloads its corridor (T75).
//
// window.dystopoTrips is the scripting surface (tools/demo-check.py --route and --long-route drive the real mouse and read it).

const KEY = "dystopo.trips";

function uuid4() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16)); // also outside secure contexts
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map(x => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

// The browser replica: this page's own operation log of trips (never pulled from elsewhere; see the header).
function loadReplica() {
  let r = null;
  try { r = JSON.parse(localStorage.getItem(KEY) || "null"); } catch (e) { r = null; }
  if (!r || r.v !== 1 || !r.device) r = { v: 1, device: uuid4(), wall: 0, counter: 0, ops: [], trips: {}, lastPush: null };
  return r;
}
let memoryOnly = false;
function storeReplica(r) {
  try { localStorage.setItem(KEY, JSON.stringify(r)); memoryOnly = false; } catch (e) { memoryOnly = true; } // private mode: this load only
}
// Hybrid logical clock text, as Hlc.text in shared/.../sync: <wall, 12 hex>.<counter, 6 hex>.<device>.
function tick(r) {
  const now = Date.now();
  if (now > r.wall) { r.wall = now; r.counter = 0; } else r.counter++;
  return r.wall.toString(16).padStart(12, "0") + "." + r.counter.toString(16).padStart(6, "0") + "." + r.device;
}

export function install({ map, base, units, panel, token }) {
  const metric = units === "m";
  const fmtDist = m => metric ? (m >= 1000 ? (m / 1000).toFixed(2) + " km" : Math.round(m) + " m") : (m / 1609.344).toFixed(2) + " mi";
  const fmtEle = m => m == null ? "–" : metric ? Math.round(m) + " m" : Math.round(m * 3.28084) + " ft";
  const auth = (opts = {}) => { const t = token ? token() : ""; return t ? { ...opts, headers: { ...(opts.headers || {}), Authorization: "Bearer " + t } } : opts; };
  const postJson = (path, body) => fetch(base + path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

  // ---- UI ----------------------------------------------------------------------------------------------------
  const css = document.createElement("style");
  css.textContent = `
    #trip { margin: 8px 0 2px; } #trip summary { font-size: 13px; font-weight: 600; margin: 0; }
    #trip button { margin: 2px 4px 2px 0; } #trip button[aria-pressed="true"] { background: #e8590c; color: #fff; border-color: #b84808; }
    #trip-list { list-style: none; margin: 4px 0; padding: 0; max-height: 22vh; overflow: auto; font-size: 12px; }
    #trip-list li { display: flex; gap: 4px; align-items: center; } #trip-list li span { flex: 1; }
    #trip-status { font-size: 12px; color: #6b5f55; white-space: pre-wrap; }
    #trip-card { position: absolute; right: 12px; bottom: 40px; width: 300px; background: rgba(255, 252, 245, 0.95); border-radius: 8px;
                 padding: 8px 10px; box-shadow: 0 2px 8px rgba(0,0,0,.2); font-size: 13px; z-index: 3; }
    #trip-card[hidden] { display: none; }
    #trip-card .stats { display: grid; grid-template-columns: 1fr 1fr; gap: 0 10px; margin: 4px 0; }
    #trip-card .stats b { font-weight: 600; }
    #trip-card input { width: 100%; box-sizing: border-box; }
    #trip-card svg { display: block; width: 100%; height: 80px; background: #fffaf0; border: 1px solid #e0d6c4; border-radius: 4px; }
    #trip-card .readout { font-size: 11px; color: #6b5f55; min-height: 1.2em; }
    #trip-card .notes { font-size: 11px; color: #9a4a12; }
    .trip-via { width: 20px; height: 20px; border-radius: 50%; border: 2px solid #fff; box-shadow: 0 1px 3px rgba(0,0,0,.4); color: #fff;
                font: bold 11px/20px system-ui, sans-serif; text-align: center; cursor: grab; background: #e8590c; }
    .trip-via.start { background: #2e7d32; } .trip-via.end { background: #b0281c; }
    .trip-planning .maplibregl-canvas { cursor: crosshair; }
    @media (max-width: 520px) { #trip-card { left: 8px; right: 8px; width: auto; bottom: 30px; } }`;
  document.head.appendChild(css);

  const box = document.createElement("details");
  box.id = "trip";
  box.open = true;
  box.innerHTML = `<summary>Plan a trip</summary>
    <button id="trip-plan" type="button" aria-pressed="false">Plan route</button>
    <label style="display:inline">Mode <select id="trip-mode"><option value="hike">hike</option><option value="bike">bike</option><option value="drive">drive</option></select></label>
    <div class="hint">Click the start and the end; click again to add a point, drag a numbered point to move it, right-click it to remove it.</div>
    <div id="trip-status"></div>
    <div><b>Saved trips</b> <button id="trip-push" type="button" title="Send this browser's trips to the server's sync peer">Send to server</button><button id="trip-dsync" type="button" title="The same as a .dsync file a phone can sync with">.dsync</button></div>
    <ul id="trip-list"></ul>`;
  (panel || document.body).appendChild(box);
  const card = document.createElement("div");
  card.id = "trip-card";
  card.hidden = true;
  card.innerHTML = `<input id="trip-name" placeholder="Trip name" value="Route">
    <div class="stats"><span>Distance <b id="trip-dist"></b></span><span>Time <b id="trip-time"></b></span>
      <span>Climb <b id="trip-up"></b></span><span>Descent <b id="trip-down"></b></span></div>
    <div class="notes" id="trip-notes"></div>
    <svg id="trip-profile" viewBox="0 0 280 80" preserveAspectRatio="none"></svg>
    <div class="readout" id="trip-readout"></div>
    <button id="trip-gpx" type="button">GPX</button><button id="trip-save" type="button">Save as trip</button><button id="trip-clear" type="button">Clear</button>`;
  map.getContainer().appendChild(card);
  const $ = id => document.getElementById(id);
  const say = text => { $("trip-status").textContent = text; };

  // ---- Map layers ----------------------------------------------------------------------------------------------
  const empty = { type: "FeatureCollection", features: [] };
  const addLayers = () => {
    if (map.getSource("trip")) return;
    map.addSource("trip", { type: "geojson", data: empty });
    map.addSource("trip-hover", { type: "geojson", data: empty });
    map.addLayer({ id: "trip-casing", type: "line", source: "trip", layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": "#fffdf7", "line-width": 7, "line-opacity": 0.9 } });
    map.addLayer({ id: "trip-line", type: "line", source: "trip", filter: ["!=", ["get", "kind"], "straight"], layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": "#e8590c", "line-width": 4 } });
    map.addLayer({ id: "trip-straight", type: "line", source: "trip", filter: ["==", ["get", "kind"], "straight"],
      paint: { "line-color": "#e8590c", "line-width": 3.5, "line-dasharray": [1.5, 1.2] } });
    map.addLayer({ id: "trip-hover", type: "circle", source: "trip-hover",
      paint: { "circle-radius": 6, "circle-color": "#1f78c8", "circle-stroke-color": "#fff", "circle-stroke-width": 2 } });
  };
  if (map.isStyleLoaded()) addLayers(); else map.once("load", addLayers);

  // ---- State -------------------------------------------------------------------------------------------------
  const st = { planning: false, mode: "hike", pending: [], reply: null, id: null, edits: 0, error: null, busy: 0, lastPush: null, lastGpx: null, savedPath: null };
  let markers = [];
  let queue = Promise.resolve();
  // Requests run one after another, each on the newest path (a quick second drag must not edit a stale route).
  const enqueue = job => (queue = queue.then(job).catch(e => { st.error = String(e.message || e); say("error: " + st.error); }));

  async function call(promise) {
    st.busy++;
    try {
      const r = await promise;
      const j = await r.json();
      if (!r.ok) throw new Error(j.error || "HTTP " + r.status);
      return j;
    } finally { st.busy--; }
  }

  function show(reply, fit) {
    st.reply = reply;
    st.error = null;
    const fc = { type: "FeatureCollection", features: reply.legs.map((l, i) => ({ type: "Feature", properties: { kind: l.kind, leg: i },
      geometry: { type: "LineString", coordinates: l.coordinates.map(c => [c[0], c[1]]) } })) };
    if (map.getSource("trip")) map.getSource("trip").setData(fc);
    drawMarkers(reply.via.map(([lat, lon]) => [lat, lon]));
    const s = reply.summary;
    $("trip-dist").textContent = fmtDist(s.distanceM);
    $("trip-time").textContent = s.time;
    const signed = (sign, m) => (m > 0 ? sign : "") + fmtEle(m);
    $("trip-up").textContent = signed("+", s.ascentM);
    $("trip-down").textContent = signed("−", s.descentM);
    const notes = [];
    if (s.straightM > 0) notes.push(fmtDist(s.straightM) + (reply.mode === "drive" ? " off road (dashed)" : " off trail (dashed)"));
    if (reply.notes.includes("no_graph")) notes.push("no trail data here: straight lines");
    if (!reply.pack) notes.push("outside every pack");
    const msgs = reply.messages || {};
    if (msgs.drive_unsupported) notes.push(msgs.drive_unsupported); // T73: the pack predates the road data
    else if (reply.mode === "drive" && reply.notes.length) for (const n of reply.notes) if (msgs[n]) notes.push(msgs[n]);
    if (reply.notice) notes.push(reply.notice);
    const long = longRoute(reply);
    if (long && (long.packs.length > 1 || long.overlayTiles > 0)) // T77: a long route across packs
      notes.push("planned by the server across " + long.packs.length + (long.packs.length === 1 ? " pack" : " packs") + " (" + long.packs.join(", ") + ") in " +
                 (long.ms / 1000).toFixed(1) + " s" + (long.overlayTiles ? ", " + long.overlayTiles + " tiles through the drive overlay" : ""));
    $("trip-notes").textContent = notes.join("; ");
    drawProfile(s.profile);
    card.hidden = false;
    if (fit) {
      const b = new maplibregl.LngLatBounds();
      for (const l of reply.legs) for (const c of l.coordinates) b.extend([c[0], c[1]]);
      if (!b.isEmpty()) map.fitBounds(b, { padding: { top: 60, bottom: 220, left: 300, right: 340 }, maxZoom: 15, duration: 0 });
    }
    const n = (k, w) => k + " " + w + (k === 1 ? "" : "s");
    say((reply.pack ? "pack " + reply.pack : "no pack") + ", " + n(reply.via.length, "point") + ", " + n(reply.legs.length, "leg") + (st.savedPath && st.savedPath !== reply.path ? " (unsaved changes)" : ""));
  }

  function drawMarkers(via) {
    for (const m of markers) m.remove();
    markers = via.map(([lat, lon], i) => {
      const el = document.createElement("div");
      el.className = "trip-via" + (i === 0 ? " start" : i === via.length - 1 && via.length > 1 ? " end" : "");
      el.textContent = String(i + 1);
      el.dataset.index = String(i);
      el.title = "Drag to move; right-click to remove";
      el.addEventListener("contextmenu", e => { e.preventDefault(); e.stopPropagation(); api.remove(i); });
      const m = new maplibregl.Marker({ element: el, draggable: true }).setLngLat([lon, lat]).addTo(map);
      m.on("dragend", () => { const p = m.getLngLat(); api.move(i, p.lat, p.lng); });
      return m;
    });
  }

  let profileGeo = null; // cumulative distance along the drawn route, for the hover readout
  function drawProfile(profile) {
    const svg = $("trip-profile");
    const pts = profile.filter(p => p[1] != null);
    if (pts.length < 2) { svg.innerHTML = ""; profileGeo = null; return; }
    const W = 280, H = 80, d1 = profile[profile.length - 1][0] || 1;
    let lo = Math.min(...pts.map(p => p[1])), hi = Math.max(...pts.map(p => p[1]));
    if (hi - lo < 20) { lo -= 10; hi += 10; }
    const x = d => d / d1 * W, y = e => H - 4 - (e - lo) / (hi - lo) * (H - 18);
    const line = pts.map(p => x(p[0]).toFixed(1) + "," + y(p[1]).toFixed(1)).join(" ");
    svg.innerHTML = `<polygon points="0,${H} ${line} ${W},${H}" fill="#f3c9a8" opacity="0.7"/>` +
      `<polyline points="${line}" fill="none" stroke="#b84808" stroke-width="1.5" vector-effect="non-scaling-stroke"/>` +
      `<text x="3" y="11" font-size="9" fill="#6b5f55">${fmtEle(hi)}</text><text x="3" y="${H - 6}" font-size="9" fill="#6b5f55">${fmtEle(lo)}</text>` +
      `<line id="trip-cursor" x1="-5" x2="-5" y1="0" y2="${H}" stroke="#1f78c8" stroke-width="1" vector-effect="non-scaling-stroke"/>`;
    // Cumulative distance of the drawn coordinates (haversine, as TrackStats), to put the hover point on the map.
    const R = 6371008.8, rad = Math.PI / 180, coords = [];
    for (const [i, l] of st.reply.legs.entries()) for (const [k, c] of l.coordinates.entries()) if (i === 0 || k > 0) coords.push(c);
    const cum = [0];
    for (let i = 1; i < coords.length; i++) {
      const [lo1, la1] = coords[i - 1], [lo2, la2] = coords[i];
      const a = Math.sin((la2 - la1) * rad / 2) ** 2 + Math.cos(la1 * rad) * Math.cos(la2 * rad) * Math.sin((lo2 - lo1) * rad / 2) ** 2;
      cum.push(cum[i - 1] + 2 * R * Math.asin(Math.min(1, Math.sqrt(a))));
    }
    profileGeo = { coords, cum, profile, d1 };
  }
  $("trip-profile").addEventListener("mousemove", e => {
    if (!profileGeo) return;
    const r = e.currentTarget.getBoundingClientRect(), f = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    const d = f * profileGeo.d1, { coords, cum, profile } = profileGeo;
    let k = profile.findIndex(p => p[0] >= d); if (k < 0) k = profile.length - 1;
    const ele = profile[k][1];
    let i = cum.findIndex(c => c >= d * cum[cum.length - 1] / profileGeo.d1); if (i < 0) i = coords.length - 1;
    map.getSource("trip-hover")?.setData({ type: "Feature", geometry: { type: "Point", coordinates: [coords[i][0], coords[i][1]] }, properties: {} });
    $("trip-cursor")?.setAttribute("x1", String(f * 280)); $("trip-cursor")?.setAttribute("x2", String(f * 280));
    $("trip-readout").textContent = fmtDist(d) + " · " + fmtEle(ele);
  });
  $("trip-profile").addEventListener("mouseleave", () => { map.getSource("trip-hover")?.setData(empty); $("trip-readout").textContent = ""; });

  // ---- Editing ------------------------------------------------------------------------------------------------
  const edit = (body, fit = false) => enqueue(async () => {
    if (!st.reply) return;
    const j = await call(postJson("/route/edit", { path: st.reply.path, pack: st.reply.pack, ...body }));
    st.edits++;
    show(j, fit);
    return j;
  });
  const api = {
    plan(points, mode = st.mode) {
      return enqueue(async () => {
        const q = new URLSearchParams({ points: points.map(([lat, lon]) => lat.toFixed(7) + "," + lon.toFixed(7)).join(";"), mode });
        const j = await call(fetch(base + "/route?" + q));
        st.pending = [];
        if (!st.id) st.id = uuid4(); // the trip id the GPX and the saved trip carry
        st.edits++;
        show(j, true);
        return j;
      });
    },
    // A map click while planning: the start, then the end (which plans), then via points.
    click(lat, lon) {
      if (!st.reply) {
        st.pending.push([lat, lon]);
        if (st.pending.length === 1) { drawMarkers(st.pending); say("start set; click the end"); return queue; }
        return api.plan(st.pending.slice(0, 2));
      }
      return api.add(lat, lon);
    },
    add: (lat, lon) => edit({ op: "add", lat, lon }),
    move: (index, lat, lon) => edit({ op: "move", index, lat, lon }),
    remove(index) {
      if (st.reply && st.reply.via.length <= 2) { say("a route needs a start and an end (Clear removes it)"); drawMarkers(st.reply.via); return queue; }
      return edit({ op: "remove", index });
    },
    setMode(mode) { st.mode = mode; $("trip-mode").value = mode; return st.reply ? edit({ op: "mode", mode }) : queue; },
    clear() {
      st.reply = null; st.pending = []; st.id = null; st.savedPath = null;
      drawMarkers([]);
      map.getSource("trip")?.setData(empty);
      card.hidden = true;
      say("");
    },
    async gpx(download = true) {
      await queue;
      if (!st.reply) throw new Error("no route");
      const r = await postJson("/route/gpx", { path: st.reply.path, name: $("trip-name").value.trim() || "Route", id: st.id });
      const text = await r.text();
      if (!r.ok) throw new Error(text);
      st.lastGpx = text;
      if (download) save(new Blob([text], { type: "application/gpx+xml" }), (($("trip-name").value.trim() || "route").replace(/[^\w.-]+/g, "_")) + ".gpx");
      return text;
    },
    // Save as trip: the put (first save) or the changed fields, as the phone's store writes them, then a push.
    async save(name) {
      await queue;
      if (!st.reply) throw new Error("no route");
      if (name != null) $("trip-name").value = name;
      const nm = $("trip-name").value.trim() || "Route";
      const rep = loadReplica();
      const id = st.id || (st.id = uuid4());
      const old = rep.trips[id];
      if (!old || old.deleted) {
        rep.ops.push({ hlc: tick(rep), type: "put", id, name: nm });
        rep.ops.push({ hlc: tick(rep), type: "set", id, field: "path", value: st.reply.path });
        rep.ops.push({ hlc: tick(rep), type: "set", id, field: "waypoints", value: st.reply.waypointsValue });
      } else {
        if (old.name !== nm) rep.ops.push({ hlc: tick(rep), type: "set", id, field: "name", value: nm });
        if (old.path !== st.reply.path) rep.ops.push({ hlc: tick(rep), type: "set", id, field: "path", value: st.reply.path });
      }
      const s = st.reply.summary;
      rep.trips[id] = { id, name: nm, path: st.reply.path, pack: st.reply.pack, mode: st.reply.mode, distanceM: s.distanceM, ascentM: s.ascentM, timeS: s.timeS, savedAt: Date.now() };
      storeReplica(rep);
      st.savedPath = st.reply.path;
      renderList();
      const pushed = await api.push();
      say("saved \"" + nm + "\" in this browser" + (memoryOnly ? " (storage blocked: this page load only)" : "") + "; " + pushText(pushed));
      return { id, pushed };
    },
    async del(id) {
      const rep = loadReplica();
      if (!rep.trips[id]) return null;
      rep.ops.push({ hlc: tick(rep), type: "delete", id });
      delete rep.trips[id];
      storeReplica(rep);
      if (st.id === id) { st.id = null; st.savedPath = null; }
      renderList();
      const pushed = await api.push();
      say("deleted; " + pushText(pushed));
      return pushed;
    },
    load(id) {
      const t = loadReplica().trips[id];
      if (!t) return Promise.reject(new Error("no trip " + id));
      return enqueue(async () => {
        const j = await call(postJson("/route/edit", { path: t.path, op: "show", pack: t.pack }));
        st.id = id; st.savedPath = t.path; st.mode = j.mode; $("trip-mode").value = j.mode; $("trip-name").value = t.name;
        show(j, true);
        return j;
      });
    },
    async bundle() {
      const rep = loadReplica();
      const r = await postJson("/route/bundle", { device: rep.device, ops: rep.ops });
      if (!r.ok) throw new Error((await r.json()).error);
      return new Uint8Array(await r.arrayBuffer());
    },
    // Sends every op of this browser's replica to the server's sync peer (idempotent: the peer skips what it holds).
    async push() {
      const rep = loadReplica();
      let result;
      try {
        if (!rep.ops.length) result = { ok: true, received: 0, applied: 0 };
        else {
          const bytes = await api.bundle();
          const r = await fetch(base + "/sync/push", auth({ method: "POST", headers: { "Content-Type": "application/octet-stream" }, body: bytes }));
          const j = await r.json().catch(() => ({}));
          result = r.ok ? { ok: true, received: j.received, applied: j.applied, duplicate: j.duplicate, vector: j.vector }
                        : { ok: false, status: r.status, error: r.status === 401 ? "the server needs its token (Token field)" : (j.error || "HTTP " + r.status) };
        }
      } catch (e) { result = { ok: false, error: String(e.message || e) }; }
      result.at = new Date().toISOString();
      result.ops = rep.ops.length;
      rep.lastPush = result;
      storeReplica(rep);
      st.lastPush = result;
      return result;
    },
    async dsync() {
      const bytes = await api.bundle();
      save(new Blob([bytes], { type: "application/octet-stream" }), "dystopo-trips.dsync");
      return bytes.length;
    },
    trips: () => Object.values(loadReplica().trips).sort((a, b) => b.savedAt - a.savedAt),
    replica: () => { const r = loadReplica(); return { device: r.device, ops: r.ops.length, trips: Object.keys(r.trips).length, lastPush: r.lastPush }; },
    idle: () => queue.then(() => api.state()),
    state: () => ({
      planning: st.planning, mode: st.mode, pending: st.pending.length, id: st.id, edits: st.edits, busy: st.busy, error: st.error,
      saved: !!st.id && st.savedPath === (st.reply && st.reply.path), lastPush: st.lastPush,
      route: st.reply && { pack: st.reply.pack, via: st.reply.via, legs: st.reply.legs.map(l => l.kind), notes: st.reply.notes, messages: st.reply.messages, notice: st.reply.notice, mode: st.reply.mode, path: st.reply.path,
                           summary: Object.fromEntries(Object.entries(st.reply.summary).filter(([k]) => k !== "profile")), profileSamples: st.reply.summary.profile.length,
                           middle: middle(st.reply), longRoute: longRoute(st.reply) },
      markers: markers.map(m => { const p = m.getLngLat(); return [p.lat, p.lng]; }),
    }),
  };
  // T77: which packs the server planned over (those whose graphs it opened), its time, and the overlay tiles its searches used.
  function longRoute(reply) {
    const g = reply && reply.graph;
    if (!g) return null;
    const searches = g.searches || [];
    return { packs: (g.graphs || []).map(x => x.pack), ms: reply.ms, searches: searches.length,
             overlayTiles: searches.reduce((n, x) => n + (x.overlayTiles || 0), 0), searchMs: searches.reduce((n, x) => n + (x.ms || 0), 0),
             tiles: searches.reduce((n, x) => n + (x.tiles || 0), 0), expanded: searches.reduce((n, x) => n + (x.expanded || 0), 0) };
  }
  // The route's middle point [lat, lon] (by point count; for scripted clicks on the line).
  const middle = reply => { const all = reply.legs.flatMap(l => l.coordinates); const c = all[Math.floor(all.length / 2)]; return c ? [c[1], c[0]] : null; };
  const pushText = p => p.ok ? "sent to the server (" + p.applied + " new of " + p.received + " ops)" : "kept in this browser only: " + p.error;
  function save(blob, name) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob); a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }
  function renderList() {
    const ul = $("trip-list");
    ul.innerHTML = "";
    for (const t of api.trips()) {
      const li = document.createElement("li");
      const label = document.createElement("span");
      label.textContent = t.name + " · " + fmtDist(t.distanceM);
      const open = document.createElement("button"); open.type = "button"; open.textContent = "Open"; open.onclick = () => api.load(t.id);
      const del = document.createElement("button"); del.type = "button"; del.textContent = "Delete"; del.onclick = () => api.del(t.id);
      li.append(label, open, del);
      ul.appendChild(li);
    }
    if (!ul.children.length) ul.innerHTML = "<li><span>none yet</span></li>";
  }

  // ---- Wiring --------------------------------------------------------------------------------------------------
  const setPlanning = on => {
    st.planning = on;
    $("trip-plan").setAttribute("aria-pressed", String(on));
    map.getContainer().classList.toggle("trip-planning", on);
    if (on && !st.reply) say("click the start");
  };
  $("trip-plan").onclick = () => setPlanning(!st.planning);
  $("trip-mode").onchange = e => api.setMode(e.target.value);
  $("trip-gpx").onclick = () => api.gpx().catch(e => say("error: " + e.message));
  $("trip-save").onclick = () => api.save().catch(e => say("error: " + e.message));
  $("trip-clear").onclick = () => api.clear();
  $("trip-push").onclick = async () => say(pushText(await api.push()));
  $("trip-dsync").onclick = () => api.dsync().catch(e => say("error: " + e.message));
  map.on("click", e => {
    if (!st.planning) return;
    if (e.originalEvent && e.originalEvent.target.closest && e.originalEvent.target.closest(".trip-via")) return;
    api.click(e.lngLat.lat, e.lngLat.lng);
  });
  renderList();
  const last = loadReplica().lastPush;
  if (last && !last.ok) say("trips not on the server yet: " + last.error);
  window.dystopoTrips = { ...api, setPlanning };
  return window.dystopoTrips;
}
