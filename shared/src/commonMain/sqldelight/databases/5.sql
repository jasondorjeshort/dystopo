CREATE TABLE track (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    -- 1 while a recorder is appending to it.
    recording INTEGER NOT NULL DEFAULT 0,
    description TEXT,
    comment TEXT,
    source TEXT,
    type TEXT,
    extensions TEXT,
    -- Version 2 (T40): TrackStats cache, computed with the default StatsOptions. stats_points is the
    -- point count it was computed from; NULL means no cache (never computed, or a point was appended since).
    stats_points INTEGER,
    stats_segments INTEGER,
    stats_distance_m REAL,
    stats_ascent_m REAL,
    stats_descent_m REAL,
    stats_min_ele_m REAL,
    stats_max_ele_m REAL,
    stats_start_time INTEGER,
    stats_end_time INTEGER,
    stats_duration_s REAL,
    stats_moving_time_s REAL,
    stats_moving_speed_mps REAL,
    stats_moving_pace_s_per_km REAL,
    stats_overall_pace_s_per_km REAL,
    stats_avg_heart_rate REAL,
    stats_max_heart_rate REAL,
    stats_west REAL,
    stats_south REAL,
    stats_east REAL,
    stats_north REAL
);

CREATE TABLE track_point (
    track_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    segment INTEGER NOT NULL,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    ele REAL,
    time INTEGER,
    extensions TEXT,
    PRIMARY KEY (track_id, seq)
);

CREATE TABLE waypoint (
    id TEXT NOT NULL PRIMARY KEY,
    -- The track the waypoint was marked on, or NULL for a free-standing waypoint.
    track_id TEXT,
    seq INTEGER NOT NULL,
    name TEXT NOT NULL,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    ele REAL,
    time INTEGER,
    description TEXT,
    comment TEXT,
    symbol TEXT,
    type TEXT,
    extensions TEXT,
    -- Version 3 (T44): HLC text of the waypoint's first live put; waypoints of a track are listed in this order.
    created_hlc TEXT
);

CREATE TABLE sync_op (
    hlc TEXT NOT NULL PRIMARY KEY,
    device TEXT NOT NULL,
    type INTEGER NOT NULL,
    kind INTEGER NOT NULL,
    entity TEXT NOT NULL,
    parent TEXT,
    digest TEXT,
    body BLOB NOT NULL
);

CREATE TABLE route (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    comment TEXT,
    type TEXT,
    extensions TEXT,
    path TEXT NOT NULL,
    waypoints TEXT NOT NULL,
    created_hlc TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    distance_m REAL NOT NULL,
    ascent_m REAL,
    descent_m REAL,
    time_s REAL NOT NULL,
    via_count INTEGER NOT NULL,
    waypoint_count INTEGER NOT NULL,
    straight_m REAL NOT NULL,
    mode TEXT NOT NULL,
    -- Version 5 (T108): the saved turn directions (RouteDirections) when made for this row's path, else NULL.
    directions TEXT
);

CREATE TABLE sync_vector (
    device TEXT NOT NULL PRIMARY KEY,
    hlc TEXT NOT NULL
);

CREATE TABLE sync_meta (
    key TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE INDEX waypoint_track ON waypoint(track_id, seq);

CREATE INDEX sync_op_entity ON sync_op(kind, entity, hlc);

CREATE INDEX sync_op_device ON sync_op(device, hlc);

CREATE INDEX sync_op_digest ON sync_op(entity, digest);

CREATE INDEX route_order ON route(created_hlc, id);

