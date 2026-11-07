# 0059. No built-in server locations: every server is the user's setting or a discovered peer

Date: 2026-11-07
Status: accepted

Record: the owner's words in chat on 2026-11-07, answering an idea that named the owner's home site as the
phone's always-on download agent; no proposal (this records a rule the code already keeps).

## Context

ADR 0052 runs one public instance of the server at the owner's home. An idea of 2026-11-07 proposed that
the phone tell "the home site" which areas it wants and sync from it. The owner: "My site doesn't really
exist though; the entire model of the application is to be distributed and NOT have a centralized server.
Having 'my site' up as one of potentially more decentralized servers is an excellent plan, but we don't
want to hard code our own internal internet locations anywhere (this doesn't include github, osm, usgs,
etc)."

Checked against `main` the same day: a release build's default server is the empty string, offline only
(`androidApp/.../app/AppSettings.kt`, `defaultServer`; a debug build on an emulator alone defaults to the
host's loopback); no source, script or page under `shared/`, `devtool/`, `androidApp/`, `serverlib/`,
`server/` or `tools/` carries a host name of the owner's. The push scan keeps the site's host name out of
the tree (ADR 0018, `privateTerms`).

## Decision

- **No address of the owner's, or of any one instance, is built into the app, the server, the web page,
  the tools or the docs** as a default, a fallback or an example to copy. A server is what the user sets
  (Settings › Sources), what a share link or a QR code carries, or what discovery finds (a nearby phone,
  a federated instance announcing itself).
- **Public data and distribution sources are not servers in this sense** and may be named: GitHub
  (releases, the updater, issues), OpenStreetMap and its mirrors, USGS, the national elevation services
  in `sources.json`, OpenFreeMap. The registry is the list.
- The owner's instance is one of potentially many. A feature that needs an always-on helper (a download
  agent keeping wanted cells fresh, a planning desk) is written for "any instance the user chose or a
  peer", and tested against the sweep's own server, never against the owner's.
- Out of scope: how instances find each other (the federation idea, `docs/ideas/`), and the phone-to-phone
  transport (ADR 0051), which already carries no fixed address.

## Consequences

A fresh install works offline and with the public sources only until the user adds a server, which is
the intended first run (T220's card). Tests and sweeps need a server of their own, which they have. A
reader of the docs never finds an address to type in; the docs say where the setting is instead. Supersede
if the project ever ships a hosted service of its own.

- Prediction, check at every release's `apk-strings` sweep part: the release APK's strings hold no host
  name beyond the public sources' and GitHub's.
- Prediction, check by 2027-05-01: no task has added a default server, and the federation idea, if built,
  carries no seed list of addresses in the tree.
