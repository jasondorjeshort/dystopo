---
date: 2026-11-01
kind: chore
---

# A script that names every listener on the PC with its image, PID and owner

Wave 25 found port 80 held by a java process in the services session that nobody could name (T199's
report; the owner was asked). The port table in `docs/reference/machines.md` lists what the project
uses; nothing lists what actually listens. A `tools/ports.ps1` [no-check: not yet written] that prints
each listening port with its PID, image path, the session it runs in and, where the image is java,
the main class or jar from its command line, would answer the question in one line and let a sweep
refuse a port before binding it (the sweep already refuses 8765, 8686, 8687 and console 5554 by
number).

## Open

- The sandbox hides the listener table's owner column from agents (T191's note in `machines.md`);
  the script may only be useful run by the owner, or must read `Get-NetTCPConnection` as an
  administrator.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.
