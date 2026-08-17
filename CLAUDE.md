# rabosh-memory — working conventions

A `BetaMemoryToolHandler` backed by [rabosh](https://github.com/aoreshkov/rabosh), so a JVM
application can give Claude the Anthropic memory tool over a durable, crash-safe,
retroactively-indexable store instead of a hand-rolled file handler.

The parent `CLAUDE.md`, one directory up in the working tree and not part of this repository, applies
here unchanged: public on GitHub under
**aoreshkov**, squashed public history, `docs/` never committed — and, because this file is itself
published, **never referenced from a committed file either.** Planning documents are kept out of the
repository and out of its prose; anything a reader needs is written where they can see it.

**The specification now lives in the repository.** `CONTRACT.md` carries the key layout, the document
shape, atomicity, durability, lifecycle and limits; `README.md` carries the deliberate divergences
from the tool specification and the measurements behind the options; the reasoning for each rule sits
in the KDoc of the declaration it governs. Read those before changing behaviour. The upstream
contract they implement is
`platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool`, and where it is silent about
behaviour the tie-breaker is the SDK's own `BetaLocalFilesystemMemoryTool` — which is the only
concrete reference implementation, and which disagrees with the specification in a few places. The
specification wins for strings; the SDK wins for algorithms.

## Why this is a separate repository

rabosh's README claims *"no runtime dependencies at all — not a small set, none"*. This module cannot
exist without `com.anthropic:anthropic-java`, and the SDK moves weekly while rabosh moves on a
format-permanence cadence. Putting the handler beside the engine would have made every
`./gradlew build` of the engine resolve an external dependency, and would have dragged an API that
tracks someone else's SDK under the engine's ABI and stability tiers.

So the split is not tidiness — it is what keeps the engine's dependency claim literally true and its
release cadence its own. The cost is the mitigation: **pin an exact `rabosh-api` version**, never a
range and never a snapshot, because there is no shared CI matrix to catch skew.

## Dependencies

- **`com.anthropic:anthropic-java` is the reason this repository exists**, and is the one third-party
  runtime dependency it may have. Pinned at `2.54.0`; do not chase releases — `LiveSmokeTest` is the
  canary, and it compiles against the pinned surface even when it is not run.
- **`app.oreshkov:rabosh-api`** at an exact version, pinned at `0.3.0`.
- JetBrains and Kotlin libraries are pre-approved. **Everything else requires explicit confirmation
  from the user before it is written into `gradle/libs.versions.toml`**, with the trade against
  writing it by hand stated first. JUnit `6.1.2` was confirmed on 2026-08-16, test-only, against a
  hand-rolled harness; that confirmation covers JUnit and nothing else.

## Toolchain

Follows rabosh: JDK 25, versions centralised in `gradle/libs.versions.toml`, latest stable, no
pre-releases without asking. The handler itself maps no memory and needs no native access; whether
the engine underneath it does is answered by rabosh's `INTEGRATION.md`, which this repository links
rather than restates.

## Decisions already taken

These are settled. Reopening one is a decision, not a refactoring.

- **Separate repository.** Argued above.
- **History ships in v1, not v0.** An append-only version record per mutation
  (`h:{scope}:{path}:{inverted seq}`) gives audit, point-in-time read and redaction — and doubles the
  write path. v0 ships without it: `Snapshot` plus `checkpoint()` is an honest rollback *window*, and
  making history opt-in later is additive. `MemoryOptions.history` exists and rejects `true`, so the
  axis is visible; the `h:` key prefix is reserved and must not be spent on anything else.
- **Return the documented strings; do not throw for expected outcomes.** The tool runner cannot do
  both `is_error: true` and a documented message, and the message is worth more to the model.
  `RaboshMemoryToolHandler`'s KDoc has the reasoning and names the manual-loop escape hatch.
- **No search, no embeddings, no server, no new on-disk format.** The module writes ordinary
  documents through rabosh's public API, so `format-permanence.md` does not govern anything here and
  no version bump is ever involved.
- **`create` returns the already-exists error rather than overwriting**, against what the model is
  told, because silent data loss is the one failure this store must not have.
- **The listing index stays off by default, and the reason is measured rather than assumed.** It
  delivers what it promises — a directory `view` reads zero documents, asserted in
  `ListingIndexTest` — and across every size and count measured that buys a tie at best:
  0.51x–0.53x at 5,000 × 64 B, 0.48x–0.57x at 50,000 × 64 B, 0.42x–0.75x at 50,000 × 4 KiB, and
  0.92x–1.10x at 5,000 × 4 KiB, which is parity with the sign unresolved. Spans rather than figures,
  because separate JVMs disagree by more than the spread within one. Memory size helps the index and
  memory count hurts it, and over this range they never combine into a win. Do not "fix" the default
  without re-running the benchmark; `MemoryOptions.listingIndex` carries the table.
- **Re-measure with warm-up on an idle machine, or do not quote the result.** Two ways this
  benchmark has already produced a wrong published number. The first version warmed up three times
  and timed one run of twenty, which timed code the JIT had not finished compiling: the 64 B
  unindexed baseline came out 2x slow, and the table said 0.58x/0.93x/0.50x with a "no crossover"
  story attached that longer runs do not reproduce. The second is load — idle Gradle daemons left
  resident by earlier invocations turned a 1746 µs baseline into 6995 µs, because the listing reads
  through mapped segments and what they cost is page cache. `./gradlew --stop` first; wide ranges or
  a "straddles parity" line in the output mean a busy machine, not an interesting result. Anything
  quoting this benchmark states its warm-up and run count and gives a span across JVMs, never an
  average across them.

## Design rules that must not be quietly broken

Each one is here because breaking it fails **silently** — a divergence the model absorbs rather than
a build that goes red. Each names where the argument for it is written down and what catches a
breach; where nothing catches it, that is said too.

- The reference response strings are reproduced verbatim, inconsistent prefixes and all. Tidying
  `view`'s missing-path string into `insert`'s shape is a silent divergence from every other
  implementation the model was trained against. `MemoryResponses` KDoc; caught by
  `DifferentialMemoryTest` against an independently written oracle, and by
  `RaboshMemoryToolHandlerTest` against the specification.
- A rejected path returns the command's own "does not exist" string, never a distinct error — a
  distinct message is a probe oracle for a malicious prompt. `README.md`; caught by
  `RaboshMemoryToolHandlerTest`.
- Path normalisation is a pure string function. **`java.nio.file.Path` must not be used**: on Windows
  it normalises with backslashes and applies drive-letter semantics, and a platform-dependent key
  layout is a store that means different things on different machines. `MemoryPath` KDoc; caught by
  the `checkNoNioPath` build task, which `check` depends on.
- `str_replace` is not `replaceFirst`. The multiple-occurrence check is the point, and it is the one
  command where a naive implementation silently edits the wrong occurrence. Caught by
  `DifferentialMemoryTest`, whose generators are seeded with duplicate-bearing content on purpose.
- Every command is one commit. A crash mid-`rename` of a subtree leaves all-old or all-new, never a
  mixture, and that is the property worth pointing at. Caught by `CrashSafetyTest`, which asserts the
  child was still alive when it was killed so the instrument cannot pass vacuously.
- `delete` does not call `compact()`. Tombstones are the retention job's business, not the
  interactive command's. **Nothing catches a breach of this** — it would show up as latency, not as
  a failure.
- `scope` is a key prefix, not a security boundary. One directory per end user when the threat model
  needs one. `README.md` and `CONTRACT.md` both say so; `DifferentialMemoryTest` asserts only that
  two scopes cannot see each other, which is isolation and not security.
- Accepted paths are echoed back normalised, rejected ones as given. Changing that changes what the
  model reads back and can reuse.
