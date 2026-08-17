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

### Added

- **`checkDeleteDoesNotCompact`, a build task for the one design rule nothing was watching.** `delete`
  writes its batch and stops; reclaiming tombstones belongs to `expireBefore`, which pins a snapshot,
  decides the set, deletes and only then compacts. A breach of that would make a command the model
  issues mid-conversation unpredictably slow — latency rather than a wrong answer, which is precisely
  what no assertion about behaviour can see. "Delete should free space" is also the intuitive
  position, and the correct code for it lives in the same file as the code that must not do it. The
  task fails `check` if `compact()` appears anywhere but `expireBefore`, and is narrow on purpose: one
  that also forbade the correct call would be worse than none.

- **How to pair the handler with context editing, in the README.** `clear_tool_uses_20250919`
  alongside the memory tool is the configuration this store is built for, and enabling it is a change
  to the message parameters rather than to the handler. Documented with the three consequences that
  are specific to this implementation: the pre-clearing flush is bursty and will meet
  `createOverwrites = false` head-on, `memory` does not belong in `exclude_tools` because its results
  are the cheapest thing in a transcript to discard, and the burst is still one writing thread under
  the same `maxMemoryBytes` cap.

- **The manual tool-use loop, written out rather than referred to.** The README has always named
  driving the loop yourself as the escape hatch for `is_error` fidelity, and never showed it. It now
  carries the dispatch — a `when` over `command` onto the six methods, one `ToolResultBlockParam`
  each — together with what keying `is_error` off the `Error: ` prefix does *not* catch, since
  `view`'s missing-path string deliberately has no prefix.

- **`-Drabosh.memory.smoke.model`.** `LiveSmokeTest` still defaults to the cheapest model that can
  drive the tool, which is right for a canary whose subject is the SDK rather than anyone's
  reasoning. A release that wants one run against the model the README recommends can now pass the
  dial instead of editing the file.

- **A project `.claude/settings.json`** allowlisting the `./gradlew` invocations this repository
  documents. The live smoke test and `bundleForCentral` are deliberately *not* on that list — one
  spends money and the other is the last step before an irreversible upload, and neither should
  become frictionless by default.

No behaviour changed and no public signature moved.

### Changed

- **The README now says which half of the tool is beta.** The tool itself is generally available;
  what lives in the SDK's beta namespace is the *helper* surface this module implements —
  `BetaMemoryToolHandler`, the runner, and the beta `MessageCreateParams` they need. A reader
  looking at the example could reasonably have concluded that adopting this handler meant adopting a
  beta API, and the non-beta `MemoryTool20250818` declaration is now named beside the manual loop
  that can use it.

- **A `.png` path is documented as a text memory**, under Deliberate divergences. Claude's tool
  description promises that `view` renders image files; `create` takes `file_text`, so there are
  none here and the model reads back what it wrote, with line numbers.

- **JUnit 6.1.2 → 6.1.3.** Test-only, and inside the confirmation the catalogue already records —
  which is of the dependency, not of a patch number.

### Fixed

- **The listing-index figures published in 0.1.1 were measured before the JIT had finished, and are
  corrected here.** `ListingIndexBenchmark` warmed up three times and timed a single run of twenty,
  which is not steady state: the 64 B unindexed baseline came out roughly twice as slow as it is, and
  the tidy conclusion drawn from it — that memory size barely moves the unindexed listing, so there
  is no crossover to find — is not reproducible. The benchmark now warms up, takes several timed runs
  per case, and reports a median with its range. **These figures supersede the 0.1.1 entry below**,
  which is left as written because it records what was believed at the time.

  On one developer machine, as the span of every JVM each cell was run in: **0.51x–0.53x** at
  5,000 × 64 B, **0.92x–1.10x** at 5,000 × 4 KiB, **0.48x–0.57x** at 50,000 × 64 B, **0.42x–0.75x**
  at 50,000 × 4 KiB. A span rather than a figure because separate JVMs disagree by more than the
  spread within any one of them — which is why the 4 KiB row at 5,000 is reported as parity with the
  sign unresolved rather than as the 1.10x win the first process suggested.

- **A second way to publish a wrong number, found while re-measuring.** Idle Gradle daemons left
  resident by earlier invocations turned a 1746 µs baseline into 6995 µs, because a listing reads
  through mapped segments and what those daemons cost is page cache rather than CPU. `./gradlew
  --stop` before a run is now documented in the README and the benchmark's KDoc, together with the
  tell: wide ranges, or a case the report flags as straddling parity, mean a busy machine.

  The conclusion survives and its reasoning does not. Memory *size* helps the index and memory
  *count* hurts it, and over the measured range they never combine into a win — the best cell is a
  tie — so the option still ships **off by default**. No behaviour changed and no public signature
  moved; this is a documentation correction with a benchmark fix behind it.

- **A benchmark or smoke run could be `UP-TO-DATE`, print nothing, and look like it had run.** The
  dials are applied in `doFirst` so they are not task inputs; the `Test` task now opts out of
  up-to-date checking whenever one is present.

- **Three benchmark dials were not forwarded into the test JVM.**
  `-Drabosh.memory.bench.warmup`, `-Drabosh.memory.bench.iterations` and
  `-Drabosh.memory.bench.runs` reached the daemon and stopped there, which is the same silent-failure
  shape the existing forwarding list was written to prevent.

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
  over. *(The figures and the crossover reasoning in this paragraph were measured without adequate
  warm-up and are corrected under [Unreleased]. The default did not change.)* A scan's "document read" is an open rather than a decode, since `Variant` is a view over
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
