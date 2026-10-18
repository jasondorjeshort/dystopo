## Licensing

Fully open source. The licence is `LICENSE`; the base default is GPL-3.0-or-later. A component that is meant to be
linked into other people's software may be LGPL-3.0-or-later instead; such a component carries its
own `LICENSE` file in its directory and is listed in `docs/design/decisions/0001-licensing.md`.
Every source file starts with an SPDX header: `SPDX-License-Identifier: GPL-3.0-or-later` (or `LGPL-3.0-or-later`).
Dependencies must be GPL-compatible. See the ADR for the reasoning.
