-- Frozen TrackDb schema, version 1 (T31): the exact statements a version-1 database was created
-- with (read back from sqlite_master). Never edit; TrackDbMigrationTest builds a database from it,
-- applies the migrations and compares the result with Track.sq. Text, not SQLDelight's .db form,
-- because a .db in the sqldelight source set makes code generation load sqlite-jdbc in a Gradle
-- worker, which fails on Windows (see shared/build.gradle.kts).

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
    extensions TEXT
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
    extensions TEXT
);

CREATE INDEX waypoint_track ON waypoint(track_id, seq);

