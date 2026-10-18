// Trip editor for the browser map (T68). Loaded by tools/demo/index.html in live mode (devtool serve --live, or the
// standalone server), because it plans on the server: GET /route and POST /route/edit run the phone's shared router and
// route editor (serverlib RouteRoutes.kt), so a route planned here has the phone's numbers.
//
// T107: everything about trips is in one "Trips" box at the top right (the layer panel on the left is the map's): the
// planner, the open route (name, numbers, notes, profile, Save trip / GPX file / Clear, and its waypoints from
// trip-sync.js), the saved trips, and Send to server / .dsync file (and Get from server, trip-sync.js). On a phone
// width both boxes start closed and opening one closes the other. Every request carries the page's Server token
// when one is set. The map layers go in whenever the style takes them (the script often arrives after "load").
//
// Use: "Plan a route", click the start and the end (the route follows trails; a dashed straight line where no trail
// connects), click again to add a via point (into the nearest leg when it passes close, else as the new end), drag a
// numbered point to move it (only its two legs are rerouted), right-click a point to remove it. The card shows
// distance, climb, the time and the elevation profile (hover it to see the place on the map). The time is the server's
// summary: the hike or bike model, and for a drive (T89) the router's own sum of each road's time, the number the phone's
// route and trip cards and `devtool route` show for the same drive.
// Mode drive (T73) follows the packs' roads (one-ways, roads closed to cars) to and between trailheads; the card shows
// the server's drive notice (`notice`, Router.DRIVE_NOTICE, ADR 0032), and a pack built before the road data says it
// must be rebuilt (the server's messages).
//
// "Save trip" writes the route into this browser's own sync replica: an operation log in localStorage
// (dystopo.trips: a device id, a hybrid logical clock and route ops, the same put + path + waypoints ops the phone
// writes; T116: and `directions`, the turn directions keyed to that path, so a phone opens the trip with them and no
// graph), then sends it to the server's sync peer (POST /route/bundle turns the ops into a sync bundle, POST
// /sync/push merges it; with the page's Server token when set, so a server with per-user tokens stores it in that
// user's replica). Where the server refuses (no token on a server that wants one, or offline) the trip stays in this
// browser only; "Send to server" retries, ".dsync file" saves the same bundle as a file the phone can sync with. A phone syncing
// with the server then has the trip in its Routes list.
//
// Directions (T111, from T102): the card lists the route's turn directions as the phone's route card does: the server's
// `instructions` (in /route and every /route/edit reply, `show` included) at the default detail level (priority 1 and 2,
// `Directions.shown` in shared/.../routing/Directions.kt), each with the distance to the next one shown in the page's
// units; the first five and a count, the rest unfolded by "Show N more". T116: the texts carry the server's "then" cues
// ("Head south on the service road, then turn right", Directions.cued), as the phone's lists do. Saving writes the
// reply's `directionsValue` (the `directions` field value the server encodes for the reply's path, RouteDirections in
// shared/.../model/RoutePlan.kt; this page keeps no copy of the format) on the first save and whenever the path changed;
// null (no op) when the server had no graph open for a trail leg, so a frame-only list never replaces a phone's.
//
// Long drives (T77): the server plans across every pack near the route, searching the tiles away from the ends through
// its drive overlay; the card then says how many packs the route crossed and how long the server took
// (`longRoute` in state(); T89: `level2Tiles`, the z8 overlay cells the searches went through). Before navigating such a route offline the phone downloads its corridor (T75).
//
// window.dystopoTrips is the scripting surface (tools/demo-check.py --route and --long-route drive the real mouse and read it).

const KEY = "dystopo.trips";
// T111: the line under the mode picker when crow is chosen: TravelMode.CROW_NOTICE in shared/.../routing/Router.kt
// (BrowserTripsTextTest keeps the two equal; the server also sends it as the crow route's `notice`).
const CROW_NOTICE = "A crow route is a straight line between the points, not a path to walk.";

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

// T107: a distance as the phone writes it (T101's distance rule, UnitFormat.distance in shared/.../render/UnitFormat.kt;
// keep the two in step): metres below 1 km, then kilometres with two decimals below 10, one below 100, none above;
// feet below a tenth of a mile (528 ft), then miles with one decimal below 100 and none above. The unit is judged on
// the value as shown, so 999.6 m reads "1.00 km".
export function distanceText(m, metric) {
  const digits = (v, most, step) => { let d = most; while (d > 0 && Math.abs(v) >= step - 0.5 / 10 ** d) { d--; step *= 10; } return d; };
  if (metric) {
    if (Math.abs(m) < 999.5) return Math.round(m) + " m";
    const v = m / 1000;
    return v.toFixed(digits(v, 2, 10)) + " km";
  }
  if (Math.abs(m) / 0.3048 < 0.1 * 1609.344 / 0.3048 - 0.5) return Math.round(m / 0.3048) + " ft";
  const v = m / 1609.344;
  return v.toFixed(digits(v, 1, 100)) + " mi";
}

// T111: the eight compass words of Directions.compass in shared/.../routing/Directions.kt (keep the two in step).
const COMPASS = ["north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"];
export function compass(deg) { return COMPASS[Math.floor((((deg % 360) + 360) % 360 + 22.5) / 45) % 8]; }

export function install({ map, base, units, panel, token }) {
  const metric = units === "m";
  const fmtDist = m => distanceText(m, metric);
  const fmtEle = m => m == null ? "–" : metric ? Math.round(m) + " m" : Math.round(m * 3.28084) + " ft";
  // T107: every request to the server carries the page's token when one is set (a server with users keeps /route*
  // behind it, T92), so the page needs no fetch wrapper.
  const auth = (opts = {}) => { const t = token ? token() : ""; return t ? { ...opts, headers: { ...(opts.headers || {}), Authorization: "Bearer " + t } } : opts; };
  const postJson = (path, body) => fetch(base + path, auth({ method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }));
  const NEEDS_TOKEN = "the server wants its token (Server token, in the panel on the left)";

  // ---- UI ----------------------------------------------------------------------------------------------------
  // T107: one Trips box on the right (the layer panel on the left is the map's): planning, the open route with its
  // numbers, profile and waypoints, then the saved trips and the server buttons. trip-sync.js fills #trip-extra (the
  // open trip's waypoints) and #trip-sync-row (Get from server).
  const css = document.createElement("style");
  css.textContent = `
    #trips { position: absolute; top: 46px; right: 52px; width: 300px; max-height: calc(100% - 96px); overflow-y: auto; box-sizing: border-box;
             background: var(--panel, rgba(255, 252, 245, 0.95)); border-radius: 8px; padding: 8px 10px; box-shadow: 0 2px 8px rgba(0,0,0,.15); font-size: 13px; z-index: 3; }
    #trips > summary { font-size: 15px; font-weight: 600; cursor: pointer; }
    #trips h3 { font-size: 13px; margin: 10px 0 2px; }
    #trips .row { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 6px; margin: 4px 0; }
    #trips .hint { color: #6b5f55; font-size: 12px; margin: 2px 0; }
    #trips button[aria-pressed="true"] { background: #e8590c; color: #fff; border-color: #b84808; }
    #trip-list { list-style: none; margin: 2px 0; padding: 0; max-height: 22vh; overflow: auto; font-size: 12px; }
    #trip-list li { display: flex; gap: 4px; align-items: center; margin: 1px 0; } #trip-list li span { flex: 1; }
    #trip-list li.open span { font-weight: 600; }
    #trip-status { font-size: 12px; color: #6b5f55; white-space: pre-wrap; min-height: 1.2em; }
    #trip-card { border-top: 1px solid #e0d6c4; border-bottom: 1px solid #e0d6c4; margin: 6px 0; padding: 6px 0; }
    #trip-card[hidden] { display: none; }
    #trip-card .stats { display: grid; grid-template-columns: 1fr 1fr; gap: 0 10px; margin: 4px 0; }
    #trip-card .stats b { font-weight: 600; }
    #trip-name { width: 100%; box-sizing: border-box; font-weight: 600; }
    #trip-card svg { display: block; width: 100%; height: 80px; background: #fffaf0; border: 1px solid #e0d6c4; border-radius: 4px; }
    #trip-card .readout { font-size: 11px; color: #6b5f55; min-height: 1.2em; }
    #trip-card .notes { font-size: 11px; color: #9a4a12; }
    #trip-dirs[hidden] { display: none; }
    #trip-dirs h4 { font-size: 12px; font-weight: 600; margin: 6px 0 2px; }
    #trip-dirs ol { margin: 0 0 2px; padding-left: 22px; font-size: 12px; }
    #trip-dirs li { margin: 1px 0; } #trip-dirs li .then { margin-left: 6px; color: #6b5f55; white-space: nowrap; }
    .trip-via { width: 20px; height: 20px; border-radius: 50%; border: 2px solid #fff; box-shadow: 0 1px 3px rgba(0,0,0,.4); color: #fff;
                font: bold 11px/20px system-ui, sans-serif; text-align: center; cursor: grab; background: #e8590c; }
    .trip-via.start { background: #2e7d32; } .trip-via.end { background: #b0281c; }
    .trip-planning .maplibregl-canvas { cursor: crosshair; }
    @media (max-width: 520px) { #trips { top: 12px; width: auto; left: 8px; right: 52px; max-height: calc(100% - 120px); padding: 6px 8px; }
                                #trips[open] { top: 56px; max-height: calc(100% - 164px); } #trips:not([open]) { left: auto; } }`;
  document.head.appendChild(css);

  const box = document.createElement("details");
  box.id = "trips";
  box.innerHTML = `<summary>Trips</summary>
    <div class="row"><button id="trip-plan" type="button" aria-pressed="false">Plan a route</button>
      <label>Mode <select id="trip-mode"><option value="hike">Hike</option><option value="bike">Bike</option><option value="drive">Drive</option><option value="crow">Crow</option></select></label></div>
    <div class="hint" id="trip-mode-note" hidden>${CROW_NOTICE}</div>
    <div class="hint">Click the start, then the end. Click again to add a stop; drag a numbered point to move it, right-click it to remove it.</div>
    <div id="trip-status" role="status"></div>
    <section id="trip-card" hidden>
      <input id="trip-name" placeholder="Trip name" value="Route" aria-label="Trip name">
      <div class="stats"><span>Distance <b id="trip-dist"></b></span><span>Time <b id="trip-time"></b></span>
        <span>Climb <b id="trip-up"></b></span><span>Descent <b id="trip-down"></b></span></div>
      <div class="hint" id="trip-bearing" hidden></div>
      <div class="notes" id="trip-notes"></div>
      <svg id="trip-profile" viewBox="0 0 280 80" preserveAspectRatio="none" aria-label="Elevation profile"></svg>
      <div class="readout" id="trip-readout"></div>
      <section id="trip-dirs" hidden><h4 id="trip-dirs-title"></h4><ol id="trip-dirs-list"></ol><button id="trip-dirs-more" type="button" hidden></button></section>
      <div class="row"><button id="trip-save" type="button">Save trip</button><button id="trip-gpx" type="button" title="Download the route as a GPX file">GPX file</button><button id="trip-clear" type="button" title="Remove the route from the map (a saved trip stays saved)">Clear</button></div>
      <div id="trip-extra"></div>
    </section>
    <h3>Saved trips</h3>
    <ul id="trip-list"></ul>
    <div class="row" id="trip-sync-row"><button id="trip-push" type="button" title="Send the trips saved in this browser to this server, where a phone that syncs with it finds them">Send to server</button><button id="trip-dsync" type="button" title="Download this browser's trips as a .dsync file a phone can sync with">.dsync file</button></div>`;
  document.body.appendChild(box);
  // Open on a wide screen; on a phone both boxes start closed and opening one closes the other (they would overlap).
  const phone = matchMedia("(max-width: 520px)");
  box.open = !phone.matches;
  box.addEventListener("toggle", () => { if (box.open && phone.matches && panel) panel.open = false; });
  if (panel) panel.addEventListener("toggle", () => { if (panel.open && phone.matches) box.open = false; });
  const card = box.querySelector("#trip-card");
  const $ = id => document.getElementById(id);
  const say = text => { $("trip-status").textContent = text; };

  // ---- Map layers ----------------------------------------------------------------------------------------------
  // T107: the layers go in as soon as the style takes them. trips.js loads after the page script, often after the
  // map's "load" event, and isStyleLoaded() is false while any tile is loading, so the old "added now, else on the
  // next load" missed them for good when the script arrived mid-pan: no route line ever drew. Now: try at once,
  // else on every style or idle event until it works; a route that arrives first waits in `drawn`.
  const empty = { type: "FeatureCollection", features: [] };
  let drawn = empty;
  let layersAt = null; // how the layers got in (window.dystopoTrips.state().layers): "install" or the event's type
  const addLayers = how => {
    if (map.getSource("trip")) return true;
    try {
      map.addSource("trip", { type: "geojson", data: drawn });
      map.addSource("trip-hover", { type: "geojson", data: empty });
      map.addLayer({ id: "trip-casing", type: "line", source: "trip", layout: { "line-join": "round", "line-cap": "round" },
        paint: { "line-color": "#fffdf7", "line-width": 7, "line-opacity": 0.9 } });
      map.addLayer({ id: "trip-line", type: "line", source: "trip", filter: ["!=", ["get", "kind"], "straight"], layout: { "line-join": "round", "line-cap": "round" },
        paint: { "line-color": "#e8590c", "line-width": 4 } });
      map.addLayer({ id: "trip-straight", type: "line", source: "trip", filter: ["==", ["get", "kind"], "straight"],
        paint: { "line-color": "#e8590c", "line-width": 3.5, "line-dasharray": [1.5, 1.2] } });
      map.addLayer({ id: "trip-hover", type: "circle", source: "trip-hover",
        paint: { "circle-radius": 6, "circle-color": "#1f78c8", "circle-stroke-color": "#fff", "circle-stroke-width": 2 } });
      layersAt = how;
      return true;
    } catch (e) { // the style is not ready yet ("Style is not done loading"); undo a half-added set and wait
      for (const id of ["trip-hover", "trip-straight", "trip-line", "trip-casing"]) if (map.getLayer(id)) map.removeLayer(id);
      for (const id of ["trip-hover", "trip"]) if (map.getSource(id)) map.removeSource(id);
      return false;
    }
  };
  if (!addLayers("install")) {
    const retry = e => { if (addLayers(e.type)) for (const t of ["styledata", "load", "idle"]) map.off(t, retry); };
    for (const t of ["styledata", "load", "idle"]) map.on(t, retry);
  }
  const setLine = fc => { drawn = fc; const s = map.getSource("trip"); if (s) s.setData(fc); else addLayers("draw"); };

  // ---- State -------------------------------------------------------------------------------------------------
  const st = { planning: false, mode: "hike", pending: [], reply: null, id: null, edits: 0, error: null, busy: 0, lastPush: null, lastGpx: null, savedPath: null, dirsOpen: false };
  let markers = [];
  let queue = Promise.resolve();
  // Requests run one after another, each on the newest path (a quick second drag must not edit a stale route).
  const enqueue = job => (queue = queue.then(job).catch(e => { st.error = String(e.message || e); say("Error: " + st.error); }));

  async function call(promise) {
    st.busy++;
    try {
      const r = await promise;
      const j = await r.json();
      if (!r.ok) throw new Error(r.status === 401 ? NEEDS_TOKEN : (j.error || "HTTP " + r.status));
      return j;
    } finally { st.busy--; }
  }

  function show(reply, fit) {
    st.reply = reply;
    st.error = null;
    const fc = { type: "FeatureCollection", features: reply.legs.map((l, i) => ({ type: "Feature", properties: { kind: l.kind, leg: i },
      geometry: { type: "LineString", coordinates: l.coordinates.map(c => [c[0], c[1]]) } })) };
    setLine(fc);
    drawMarkers(reply.via.map(([lat, lon]) => [lat, lon]));
    const s = reply.summary;
    $("trip-dist").textContent = fmtDist(s.distanceM);
    $("trip-time").textContent = s.time;
    const signed = (sign, m) => (m > 0 ? sign : "") + fmtEle(m);
    $("trip-up").textContent = signed("+", s.ascentM);
    $("trip-down").textContent = signed("−", s.descentM);
    const notes = [];
    // T111: a crow route is straight by definition (T108); its notice says so instead of an "off trail" count.
    if (s.straightM > 0 && reply.mode !== "crow") notes.push(fmtDist(s.straightM) + (reply.mode === "drive" ? " off road (dashed)" : " off trail (dashed)"));
    if (reply.notes.includes("no_graph")) notes.push("No trail data here: straight lines");
    if (!reply.pack) notes.push("Outside every pack");
    const msgs = reply.messages || {};
    if (msgs.drive_unsupported) notes.push(msgs.drive_unsupported); // T73: the pack predates the road data
    else if (reply.mode === "drive" && reply.notes.length) for (const n of reply.notes) if (msgs[n]) notes.push(msgs[n]);
    if (reply.notice && reply.mode !== "crow") notes.push(reply.notice); // T111: crow's is the line under the select
    const long = longRoute(reply);
    if (long && (long.packs.length > 1 || long.overlayTiles > 0)) // T77: a long route across packs
      notes.push("Planned across " + long.packs.length + (long.packs.length === 1 ? " pack" : " packs") + " (" + long.packs.join(", ") + ") in " +
                 (long.ms / 1000).toFixed(1) + " s" + (long.overlayTiles ? "; " + long.overlayTiles + " tiles through the drive overlay" : "") +
                 (long.level2Tiles ? ", " + long.level2Tiles + " z8 cells" : ""));
    $("trip-notes").textContent = notes.join("; ");
    // T111: a crow route's bearing, as the phone's route card has it (T108), one per leg from the server's summary.
    const bearings = reply.mode === "crow" ? (s.bearings || []).map(b => b.bearingDeg + "\u00b0 (" + compass(b.bearingDeg) + ")") : [];
    $("trip-bearing").hidden = !bearings.length;
    $("trip-bearing").textContent = (bearings.length === 1 ? "Bearing " : "Bearings ") + bearings.join(", ");
    drawProfile(s.profile);
    drawDirections(reply);
    card.hidden = false;
    if (fit) {
      const b = new maplibregl.LngLatBounds();
      for (const l of reply.legs) for (const c of l.coordinates) b.extend([c[0], c[1]]);
      if (!b.isEmpty()) map.fitBounds(b, { padding: fitPadding(), maxZoom: 15, duration: 0 });
    }
    const n = (k, w) => k + " " + w + (k === 1 ? "" : "s");
    say((reply.pack ? "On pack " + reply.pack : "Outside every pack") + ": " + n(reply.via.length, "point") + ", " + n(reply.legs.length, "leg") + "." +
        (st.savedPath && st.savedPath !== reply.path ? " Changed since it was saved." : ""));
    renderList();
  }

  // Room around a route fitted to the view: clear of the layer panel on the left and this box on the right.
  function fitPadding() {
    const c = map.getContainer().getBoundingClientRect(), pad = { top: 40, bottom: 70, left: 40, right: 40 }; // bottom: the credits line
    const p = panel && panel.getBoundingClientRect(), r = box.getBoundingClientRect();
    if (p && p.width && p.right < c.left + c.width / 2) pad.left = Math.round(p.right - c.left + 20);
    if (r.width && r.left > c.left + c.width / 2) pad.right = Math.round(c.right - r.left + 20);
    // A small window: shrink the side margins until the route has at least a third of the width.
    while (pad.left + pad.right > c.width * 0.66 && pad.left + pad.right > 80) { pad.left = Math.max(40, pad.left * 0.8); pad.right = Math.max(40, pad.right * 0.8); }
    if (pad.top + pad.bottom > c.height * 0.66) { pad.top = 20; pad.bottom = 20; }
    return pad;
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

  // T111: the turn directions (T102's `instructions`) at the default detail level, each with the distance to the next
  // one shown (a port of Directions.shown: keep the two in step), the first DIRS_FIRST and a count, the rest on click.
  const DIRS_FIRST = 5;
  function shownDirections(list) {
    const s = (list || []).filter(i => (i.priority == null ? 2 : i.priority) <= 2);
    return s.map((i, k) => ({ ...i, stretchM: k + 1 < s.length ? s[k + 1].alongM - i.alongM : 0 }));
  }
  function drawDirections(reply) {
    const shown = shownDirections(reply.instructions);
    $("trip-dirs").hidden = shown.length === 0;
    $("trip-dirs-title").textContent = shown.length === 1 ? "1 direction" : shown.length + " directions";
    const ol = $("trip-dirs-list");
    ol.innerHTML = "";
    for (const i of shown.slice(0, st.dirsOpen ? shown.length : DIRS_FIRST)) {
      const li = document.createElement("li");
      li.textContent = i.text;
      if (i.stretchM > 0) {
        const then = document.createElement("span");
        then.className = "then";
        then.textContent = " " + fmtDist(i.stretchM);
        li.append(then);
      }
      ol.appendChild(li);
    }
    const more = $("trip-dirs-more"), rest = shown.length - DIRS_FIRST;
    more.hidden = rest <= 0;
    more.textContent = st.dirsOpen ? "Show fewer" : "Show " + rest + " more";
  }
  $("trip-dirs-more").addEventListener("click", () => { st.dirsOpen = !st.dirsOpen; if (st.reply) drawDirections(st.reply); });

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
        const j = await call(fetch(base + "/route?" + q, auth()));
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
        if (st.pending.length === 1) { drawMarkers(st.pending); say("Start set: click the end."); return queue; }
        return api.plan(st.pending.slice(0, 2));
      }
      return api.add(lat, lon);
    },
    add: (lat, lon) => edit({ op: "add", lat, lon }),
    move: (index, lat, lon) => edit({ op: "move", index, lat, lon }),
    remove(index) {
      if (st.reply && st.reply.via.length <= 2) { say("A route needs a start and an end; Clear removes the whole route."); drawMarkers(st.reply.via); return queue; }
      return edit({ op: "remove", index });
    },
    setMode(mode) { st.mode = mode; $("trip-mode").value = mode; showModeNote(); return st.reply ? edit({ op: "mode", mode }) : queue; },
    // T111: unfold (true) or fold (false) the card's directions list, as "Show N more" and "Show fewer" do.
    directions(open = !st.dirsOpen) { st.dirsOpen = !!open; if (st.reply) drawDirections(st.reply); return api.state().directions; },
    clear() {
      st.reply = null; st.pending = []; st.id = null; st.savedPath = null;
      drawMarkers([]);
      setLine(empty);
      card.hidden = true;
      say("");
      renderList();
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
      // T116: the directions go with the path they were made for (the value carries its key), after it.
      const dirs = st.reply.directionsValue;
      if (!old || old.deleted) {
        rep.ops.push({ hlc: tick(rep), type: "put", id, name: nm });
        rep.ops.push({ hlc: tick(rep), type: "set", id, field: "path", value: st.reply.path });
        rep.ops.push({ hlc: tick(rep), type: "set", id, field: "waypoints", value: st.reply.waypointsValue });
        if (dirs) rep.ops.push({ hlc: tick(rep), type: "set", id, field: "directions", value: dirs });
      } else {
        if (old.name !== nm) rep.ops.push({ hlc: tick(rep), type: "set", id, field: "name", value: nm });
        if (old.path !== st.reply.path) {
          rep.ops.push({ hlc: tick(rep), type: "set", id, field: "path", value: st.reply.path });
          if (dirs) rep.ops.push({ hlc: tick(rep), type: "set", id, field: "directions", value: dirs });
        }
      }
      const s = st.reply.summary;
      rep.trips[id] = { id, name: nm, path: st.reply.path, pack: st.reply.pack, mode: st.reply.mode, distanceM: s.distanceM, ascentM: s.ascentM, timeS: s.timeS, savedAt: Date.now() };
      storeReplica(rep);
      st.savedPath = st.reply.path;
      renderList();
      const pushed = await api.push();
      say("Saved \u201c" + nm + "\u201d " + (pushed.ok ? "in this browser and on the server." : "in this browser only: " + pushed.error + ". Send to server tries again.") +
          (memoryOnly ? " This browser blocks storage: the trip is kept until the page closes." : ""));
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
      say(pushed.ok ? "Deleted here and on the server." : "Deleted here; the server still has it: " + pushed.error + ".");
      return pushed;
    },
    load(id) {
      const t = loadReplica().trips[id];
      if (!t) return Promise.reject(new Error("no trip " + id));
      return enqueue(async () => {
        const j = await call(postJson("/route/edit", { path: t.path, op: "show", pack: t.pack }));
        st.id = id; st.savedPath = t.path; st.mode = j.mode; $("trip-mode").value = j.mode; showModeNote(); $("trip-name").value = t.name;
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
                        : { ok: false, status: r.status, error: r.status === 401 ? NEEDS_TOKEN : (j.error || "HTTP " + r.status) };
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
    // T116: the last value this browser wrote for each field of trip `id` (put's name included), as its ops hold them.
    fields(id) {
      const f = {};
      for (const o of loadReplica().ops) if (o.id === id) { if (o.type === "put") f.name = o.name; else if (o.type === "set") f[o.field] = o.value; else if (o.type === "delete") f.deleted = true; }
      return f;
    },
    replica: () => { const r = loadReplica(); return { device: r.device, ops: r.ops.length, trips: Object.keys(r.trips).length, lastPush: r.lastPush }; },
    idle: () => queue.then(() => api.state()),
    state: () => ({
      planning: st.planning, mode: st.mode, pending: st.pending.length, id: st.id, edits: st.edits, busy: st.busy, error: st.error, layers: layersAt,
      saved: !!st.id && st.savedPath === (st.reply && st.reply.path), lastPush: st.lastPush,
      bearing: $("trip-bearing").hidden ? null : $("trip-bearing").textContent, // T111: crow only
      route: st.reply && { pack: st.reply.pack, via: st.reply.via, legs: st.reply.legs.map(l => l.kind), notes: st.reply.notes, messages: st.reply.messages, notice: st.reply.notice, mode: st.reply.mode, path: st.reply.path,
                           directionsValue: st.reply.directionsValue || null, // T116: what Save writes as the trip's `directions`
                           summary: Object.fromEntries(Object.entries(st.reply.summary).filter(([k]) => k !== "profile")), profileSamples: st.reply.summary.profile.length,
                           middle: middle(st.reply), longRoute: longRoute(st.reply) },
      // T111: the reply's directions (every priority), those the card counts (the default level), the lines listed now.
      directions: st.reply && { count: (st.reply.instructions || []).length, shown: shownDirections(st.reply.instructions).length, open: st.dirsOpen,
                                listed: document.querySelectorAll("#trip-dirs-list li").length, texts: shownDirections(st.reply.instructions).map(i => i.text) },
      markers: markers.map(m => { const p = m.getLngLat(); return [p.lat, p.lng]; }),
    }),
  };
  // T77: which packs the server planned over (those whose graphs it opened), its time, and the overlay tiles its searches used.
  function longRoute(reply) {
    const g = reply && reply.graph;
    if (!g) return null;
    const searches = g.searches || [];
    return { packs: (g.graphs || []).map(x => x.pack), ms: reply.ms, searches: searches.length,
             overlayTiles: searches.reduce((n, x) => n + (x.overlayTiles || 0), 0),
             level2Tiles: searches.reduce((n, x) => n + (x.level2Tiles || 0), 0), searchMs: searches.reduce((n, x) => n + (x.ms || 0), 0),
             tiles: searches.reduce((n, x) => n + (x.tiles || 0), 0), expanded: searches.reduce((n, x) => n + (x.expanded || 0), 0) };
  }
  // The route's middle point [lat, lon] (by point count; for scripted clicks on the line).
  const middle = reply => { const all = reply.legs.flatMap(l => l.coordinates); const c = all[Math.floor(all.length / 2)]; return c ? [c[1], c[0]] : null; };
  const pushText = p => p.ok ? (p.applied ? "Sent to the server (" + p.applied + (p.applied === 1 ? " change" : " changes") + " it did not have)." : "The server already had every trip from this browser.")
                             : "Not sent: " + p.error + ".";
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
      if (t.id === st.id) li.className = "open";
      const label = document.createElement("span");
      label.textContent = t.name + " · " + fmtDist(t.distanceM || 0) + (t.pulled ? " (from the server)" : "");
      label.title = t.mode ? t.mode + (t.pack ? ", pack " + t.pack : "") : "";
      const open = document.createElement("button"); open.type = "button"; open.textContent = "Open"; open.onclick = () => api.load(t.id);
      const del = document.createElement("button"); del.type = "button"; del.textContent = "Delete"; del.onclick = () => api.del(t.id);
      li.append(label, open, del);
      ul.appendChild(li);
    }
    if (!ul.children.length) ul.innerHTML = "<li><span>No saved trips yet.</span></li>";
  }

  // T111: crow (T108) says under the select what it is, as the phone's mode picker does.
  function showModeNote() { $("trip-mode-note").hidden = $("trip-mode").value !== "crow"; }

  // ---- Wiring --------------------------------------------------------------------------------------------------
  const setPlanning = on => {
    st.planning = on;
    $("trip-plan").setAttribute("aria-pressed", String(on));
    map.getContainer().classList.toggle("trip-planning", on);
    if (on && !st.reply) say("Click the start.");
    // T107: on a phone the open box covers the map, so planning closes it; its title says planning is on.
    box.querySelector("summary").textContent = on && phone.matches ? "Trips (planning)" : "Trips";
    if (on && phone.matches) box.open = false;
  };
  $("trip-plan").onclick = () => setPlanning(!st.planning);
  $("trip-mode").onchange = e => api.setMode(e.target.value);
  $("trip-gpx").onclick = () => api.gpx().catch(e => say("Error: " + e.message));
  $("trip-save").onclick = () => api.save().catch(e => say("Error: " + e.message));
  $("trip-clear").onclick = () => api.clear();
  $("trip-push").onclick = async () => say(pushText(await api.push()));
  $("trip-dsync").onclick = () => api.dsync().catch(e => say("Error: " + e.message));
  map.on("click", e => {
    if (!st.planning) return;
    if (e.originalEvent && e.originalEvent.target.closest && e.originalEvent.target.closest(".trip-via")) return;
    api.click(e.lngLat.lat, e.lngLat.lng);
  });
  renderList();
  const last = loadReplica().lastPush;
  if (last && !last.ok) say("Some trips are only in this browser: " + last.error + ". Send to server tries again.");
  window.dystopoTrips = { ...api, setPlanning, renderList, fmtDist, distanceText };
  return window.dystopoTrips;
}
