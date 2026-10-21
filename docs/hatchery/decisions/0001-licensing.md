# 0001. Licensing: GPL-3.0-or-later by default, LGPL-3.0-or-later for linkable components

Date: 2026-10-11
Status: accepted

## Context

The project is fully open source. The owner wants anyone to be able to use the code, with
copyleft as the governing principle: improvements should flow back. Some parts of the project
may be libraries that others would want to link into their own software; a strict GPL on those
would stop non-GPL projects from using them at all, which is not the intent.

## Decision

- The repository as a whole, and every component by default, is licensed GPL-3.0-or-later.
  The text is in `LICENSE` at the root.
- A component may be licensed LGPL-3.0-or-later when it is designed to be linked or embedded
  (a client library, an SDK, a protocol implementation). Such a component carries its own
  `LICENSE` file in its directory and is listed in the table below. Choosing LGPL for a
  component is itself a decision recorded by updating this ADR.
- Every source file carries an SPDX header on its first line so the license of any file is
  unambiguous even when copied out of the tree.
- Third-party dependencies must be GPL-3.0 compatible (MIT, BSD, Apache-2.0, LGPL, MPL-2.0,
  GPL-3.0). Dependencies under incompatible licenses are not used.
- "or-later" is chosen so the project can move to a future GPL version without re-licensing.

## Components and their licenses

| Path | License | Reason |
|---|---|---|
| `/` (everything not listed below) | GPL-3.0-or-later | Default. |
| vendored copies (none in the base) | the upstream licence, kept | A file copied in under ADR 0012 keeps its own SPDX identifier and origin line; one row per file here. |

## Consequences

- Anyone can use, modify, and redistribute the code; redistributed modifications of GPL parts
  must stay GPL.
- Contributors keep their copyright. No contributor license agreement for now; if one becomes
  necessary this ADR is superseded.
- When a new linkable component is created, the architect decides GPL vs LGPL and updates the
  table above.
- License texts for both options live in `LICENSES/` so a component can copy the right one.
