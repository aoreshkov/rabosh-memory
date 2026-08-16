# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with two qualifications that matter more
here than the version number does.

**This module makes no format promise of its own, and that is not a weaker claim than the engine's —
it is the engine's.** Nothing here defines an on-disk shape: memories are ordinary documents written
through rabosh's public API, so what protects them is
[COMPATIBILITY.md](https://github.com/aoreshkov/rabosh/blob/main/COMPATIBILITY.md), a store written by
an earlier release opens on every later one. A release of this module never involves a format version
bump, and an entry below claiming one would be a mistake rather than a change.

**The Kotlin API is pre-1.0 and tracks somebody else's SDK.** `BetaMemoryToolHandler` is a beta
surface on a library that moves weekly, and this module's job is to keep implementing it. Signatures
may change in any release; what will not change silently is the committed ABI dump in `api/`, which
`checkKotlinAbi` fails the build over. What *is* held stable deliberately is the thing a model reads:
the six commands' **response strings**, reproduced verbatim from the specification. Those are covered
under [CONTRACT.md](CONTRACT.md), and a change to one is a breaking change however small the diff.

## [Unreleased]

Nothing yet.

## [0.1.1] — 2026-08-16

The first release: the six memory-tool commands over a rabosh store, with the atomicity and the path
handling that a directory of files cannot offer, and with the one measurement that went the other way
written down beside them.

**Why the first release is `0.1.1`.** `v0.1.0` was tagged and its release failed before anything was
uploaded — the bundle check rejected the first bundle this project ever signed, over the checksums
Gradle writes beside a `.asc`. The fix was a commit, and a protected tag cannot move to include one,
so `0.1.0` was spent rather than reused. Nothing was ever published under it; there is no `0.1.0` on
Maven Central and there never will be. The trade is the one `release.yml` states: a tag that can be
moved after people have pinned it is worth less than a version number, and version numbers are free.

### Added

- **The Anthropic memory tool (`memory_20250818`) over a rabosh store.** `RaboshMemoryToolHandler`
  implements `com.anthropic.helpers.BetaMemoryToolHandler`: `view`, `create`, `str_replace`,
  `insert`, `delete`, `rename`. Hand it to `ToolRunnerCreateParams`, or drive the tool-use loop
  yourself — the six methods are the whole surface and nothing here depends on the runner.

- **Every command is one commit.** A recursive `delete` is one `deleteRange`; a recursive `rename` is
  one `WriteBatch` carrying the copies and the tombstones. A crash midway through either leaves the
  store at the acknowledged prefix — all-old or all-new, never a mixture — which a filesystem-backed
  handler cannot promise, since it implements both as walks and a move that is atomic only within a
  device.

- **Path normalisation as a pure string function.** `MemoryPath` turns a model-supplied path into a
  key without touching `java.nio.file.Path`, because on Windows that normalises with backslashes and
  applies drive-letter semantics — a key layout derived from it would mean different things on
  different machines, which is a divergence no test on one platform can see. The `checkNoNioPath`
  build task enforces it mechanically rather than by review habit.

- **No filesystem to escape into.** The path-traversal warning that dominates the memory tool's own
  documentation does not apply: a malformed path addresses a key that does not exist. Validation is
  still there, but it degrades from a security control whose failure leaks the host filesystem to a
  namespace rule whose failure returns "does not exist" — and it returns *the command's own* "does
  not exist" string, never a distinct one, because a distinct message is a probe oracle for a
  malicious prompt.

- **`MemoryOptions`** — `scope`, `maxMemoryBytes`, `viewMaxChars`, `createOverwrites`, `trackAccess`,
  `listingIndex`, `history`. Two of those are worth reading the KDoc for before setting:
  `createOverwrites` is `false` against what the model is told, because an overwrite of a memory the
  model has forgotten it wrote is silent data loss; `history` exists and rejects `true`, so the axis
  is visible while v0 ships without it.

- **`expireBefore(Instant)` and `usage()`** — retention and accounting, outside the tool contract
  because the tool contract has no room for them.

- **`RaboshMemoryToolHandler.open(Path)`** for a host whose only use for rabosh is this handler: it
  opens the store, owns it, and closes it with the handler. The primary constructor takes a `Rabosh`
  you own instead, which is the shape for an application that already has one.

- **[CONTRACT.md](CONTRACT.md)** — key layout, document shape, atomicity, durability, lifecycle,
  limits and the response strings. It links rabosh's `INTEGRATION.md` and `COMPATIBILITY.md` rather
  than restating either.

- **[SECURITY.md](SECURITY.md)**, whose threat model is the one this module actually has: the input
  is model-generated and therefore hostile, `scope` is a key prefix and not a boundary, and there is
  no encryption at rest.

### Measured

- **The listing index does what it promises and is still not worth turning on.** With
  `listingIndex = true` a directory `view` reads **zero documents** — asserted in `ListingIndexTest`,
  not claimed — and that is not a speed-up: `ListingIndexBenchmark` puts it at **0.58× the unindexed
  listing at 5,000 memories and 0.50× at 50,000**, so it degrades with scale rather than crossing
  over. A scan's "document read" is an open rather than a decode, since `Variant` is a view over
  mapped bytes; a listing is not selective, because the key range already bounds the scan to the
  subtree; both paths are linear in the rows returned and the query has the larger constant. The
  option ships **off by default** with the table written where somebody will read it, and the number
  is here because a negative result nobody records gets re-discovered as an optimisation.

### Testing

- **A differential oracle written longhand over a `TreeMap`**, compared against the handler on
  **returned strings** over generated command scripts, plus a model comparison of the store after
  every command and after a reopen. Seeds are printed and replayable with `-Drabosh.memory.seed`.
- **An example-based suite against the published specification** rather than against the oracle —
  two suites holding the same strings up from different sides, because an oracle and an
  implementation that agree can still both be wrong about what the model was trained to read.
- **A child JVM killed with `TerminateProcess`/`SIGKILL` mid-`create` and mid-`rename`**, asserting
  the acknowledged prefix and all-old-or-all-new. Both instruments assert the child was **still
  alive** when it was killed, so neither can pass vacuously.
- **A live smoke test** (`-Prabosh.memory.smoke`) that runs one real conversation. It is excluded
  from `build` and from CI — it costs money and needs a key — and is dispatched by hand from
  `.github/workflows/smoke.yml` before a release. It **fails** rather than skips without a key,
  because a smoke test that skips itself is a green run that proved nothing.

[Unreleased]: https://github.com/aoreshkov/rabosh-memory/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/aoreshkov/rabosh-memory/releases/tag/v0.1.1
