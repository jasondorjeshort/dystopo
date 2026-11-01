---
verified: 2026-11-01
source: observed on the second PC (T199, Windows 11, PowerShell 5.1, Caddy 2.11.7); https://caddyserver.com/docs/caddyfile/options
---

# The home site

One instance of the standalone server (`docs/SERVER.md`) running all the time on the owner's PC behind a
Caddy TLS proxy, as two Windows scheduled tasks that start with the PC (ADR 0052). Scripts in `tools/site/`:
`install.ps1` (install, `-Update`, `-Firewall`, `-Uninstall`), `status.ps1`, and the templates
`server.example.json` and `Caddyfile`. Each script's header comment has every option.

The site is separate from development: the server and tilebuild distributions are copied out of the build
tree into the site folder, the site has its own `cache/` and `out/` (public data only; `out/` starts empty,
so every view renders live), the server binds `127.0.0.1` and only Caddy faces the network. A Gradle run in
any checkout never touches it.

## Install

From the checkout, in a PowerShell with `JAVA_HOME` naming a JDK 21 (`machines.md`):

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools\site\install.ps1 -Host <the site's name>
```

The host name is the dynamic-DNS name; it is a host name under `.claude/rules/docs.md` › Personal data, so
it lives only in the site folder's `site.env` and never in a tracked file. Defaults: folder
`~\.dystopo\site`, port 8801, host `localhost`, no task-name suffix. An argument given once is recorded in
`site.env` and reused by later runs.

**Elevation.** Task Scheduler refuses a plain PowerShell both logon type S4U and an at-startup trigger
("Access is denied", measured 2026-11-01; an interactive task with an at-logon trigger is allowed). The
first run therefore builds, copies, downloads Caddy and writes the configs, then stops with exit 3 and
prints the one command to run as administrator, in the checkout:

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools\site\install.ps1 -Firewall
```

That run registers both tasks, adds the firewall rule (inbound TCP 80 and 443 to the site's `caddy.exe`,
any network profile), starts both and waits for `/healthz` on the server and through the proxy. A
`-Root`/`-Name` given on the first run goes on this command too (the script prints it complete).

What it registers, for the current user, logon type S4U (no stored password, no window, runs before anyone
logs on; no access to network shares, which the site does not need):

| Task | Action |
|---|---|
| `Dystopo site server<suffix>` | `javaw.exe` of the JDK recorded in `site.env`, `JAVA_OPTS`, `-cp <folder>\server\lib\*` on the server's main class, `--config <folder>\server.json`. Not `server.bat`, whose `cmd` leaves java running when the task is stopped; `javaw`, not `java`, so the at-logon test mode opens no console window. |
| `Dystopo site proxy<suffix>` | `<folder>\caddy\caddy.exe run --config Caddyfile --envfile <folder>\site.env` |

Both: an at-startup trigger plus a trigger every minute (from a minute after the install, ignored while the
task runs), no execution time limit, allowed on battery and not stopped on battery, restart on failure
every minute, normal priority. The minute trigger is what brings a dead process back: restart-on-failure
only covers a task that fails to start, and a killed action stayed down for 150 s without it. With it, a
killed server answered `/healthz` again after 4 s and 31 s in two tries (at most a minute plus the server's
start, about 2 s).

A `Stop-ScheduledTask` (and `-Update`, `-Uninstall`) is a hard kill: no graceful shutdown, so a build in
progress leaves its `*.building` file, which the next build of that name replaces.

**Test instances** (agents, verifiers): `-Root <scratch> -Name " <id>" -Port <port> -AtLogon`. `-AtLogon`
registers interactive tasks with an at-logon trigger, which needs no elevation; they run only while the
user is logged on, and Caddy's task opens a console window (closing it stops Caddy until the next minute).
A `localhost` instance binds only the loopback addresses and uses no port 80. Only one proxy at a time can
hold 443, so a test instance and the real site do not run together.

## The folder

| Path | What |
|---|---|
| `site.env` | Host, port, token, the JDK path snapshotted at install, `JAVA_OPTS` (`-Xmx2g`), Caddy's folders, and two whole Caddyfile lines (`DYSTOPO_SITE_BIND`, `DYSTOPO_SITE_AUTO_HTTPS`). Caddy reads it at start; `install.ps1` reads it back. Readable only by the user, SYSTEM and Administrators. |
| `server.json` | The server's config, from `tools/site/server.example.json`; `install.ps1` sets bind, port, `publicUrl`, cache, out, tilebuild, token and the log file and keeps any other key you add. Same permissions. |
| `server/`, `tilebuild/` | The snapshot of the distributions (replaced by `-Update`). |
| `cache/`, `out/` | The site's own downloads and tiles, and its packs. |
| `logs/server.log` | The JSON log, rotated at 64 MB, 5 kept. |
| `caddy/` | `caddy.exe`, its `LICENSE`, `version.txt`, `Caddyfile`, `caddy.log` (rotated at 10 MiB, 3 kept), `data/` (certificates and keys). |

**The token** is `DYSTOPO_SITE_TOKEN` in `site.env` (and `token` in `server.json`). Open the site once as
`https://<host>/?token=<token>`: the page keeps it and can then build packs (`docs/SERVER.md`, The token).

## Caddy

Downloaded at install time from the latest GitHub release of `caddyserver/caddy` (Apache-2.0): the
`windows_amd64` zip, whose SHA-512 must match the release's checksums file. That proves the download is
intact, not who made it. It is a runtime download, so `licenseCheck` does not see it. `-Update` takes a
newer release when there is one.

The Caddyfile (`tools/site/Caddyfile`) reads host, port and folder from `site.env`, turns the admin
endpoint off (each install is one Caddy), sets `skip_install_trust` (a hidden task cannot answer the
prompt to trust Caddy's internal CA), answers `/metrics*` with 403 (the server's `/metrics` context also
answers any longer path) and proxies to `127.0.0.1:<port>`. A public name gets a certificate from Let's
Encrypt or ZeroSSL once the name points here and 443 is forwarded; `localhost` gets one from Caddy's
internal CA (12 hours, renewed by Caddy).

**Port 80.** By default a public site also listens on 80 to redirect to HTTPS. If another program holds
port 80 (on the second PC one does, `machines.md`), Caddy does not start: set
`DYSTOPO_SITE_AUTO_HTTPS=auto_https disable_redirects` in `site.env` and restart the proxy task; certificates
then come through the TLS challenge on 443 alone. `-Update` keeps the line.

## Every day

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools\site\status.ps1     # exit 0 when all is well
powershell -NoProfile -ExecutionPolicy Bypass -File tools\site\install.ps1 -Update   # after a merge to main
```

`status.ps1` prints one line per check, status words and counts only (no URL, host, path or log line):
both tasks' state, last result and logon type; `/healthz` direct (time, uptime, packs, catalog state);
`/healthz` through Caddy on 127.0.0.1:443 under the site's name, the certificate verified (so the router
and DNS are not in the path); `/metrics` through Caddy must be 403; the certificate Caddy serves (issuer,
time left, trusted by Windows; a public one with 10 days or less left fails); requests, 5xx, tiles served,
live renders and tile errors since the server started. Measured on a test instance: `/healthz` 3 to 10 ms
direct, 19 to 71 ms through Caddy.

`-Update` builds in the checkout it runs from (pull `main` first), disables and stops both tasks, replaces
the snapshot and Caddy if newer, rewrites the configs (token, host, port and logon mode kept), registers
the tasks again and starts them: 76 s on a test instance, the build first while the site still runs, so it
is down only from the stop to the restart (the copy and the start). With S4U tasks run it as
administrator, since registering S4U tasks needs it.

`-Uninstall` (same `-Root` and `-Name`) stops and removes both tasks and the firewall rule (as
administrator for S4U tasks and the rule) and keeps the folder: delete it by hand to drop the token,
cache and packs.

## The owner's three steps outside the script

1. **The router:** forward TCP 443 (and 80, unless another program holds it here) to this PC's LAN
   address, and give the PC a fixed lease so the forward keeps pointing at it.
2. **Dynamic DNS:** keep the name pointing at the home address with the provider's updater (the router's
   built-in client, or the provider's Windows client); the site name must resolve before Caddy can get a
   certificate.
3. **Power:** the PC must never sleep or hibernate on mains power (Settings › System › Power, or
   `powercfg /change standby-timeout-ac 0` and `powercfg /change hibernate-timeout-ac 0`). A reboot is
   fine: the tasks start at boot, before anyone logs on.

## The always-on cost

After two live views (Rainier and Mont Blanc at z12): the server's `javaw` 425 MB working set (486 MB
private), Caddy 37 MB (60 MB private). The heap is capped at 2 GB by `JAVA_OPTS`; live rendering for
visitors grows it towards that and the cache on disk by `cache.*` (about 7 GB at the defaults,
`docs/SERVER.md`, Config file) plus the source downloads. A pack build adds a tilebuild process with its
own 3 GB heap while it runs.
