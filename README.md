# rabosh-memory

[![Maven Central](https://img.shields.io/maven-central/v/app.oreshkov/rabosh-memory?color=blue)](https://central.sonatype.com/artifact/app.oreshkov/rabosh-memory)
[![CI](https://github.com/aoreshkov/rabosh-memory/actions/workflows/ci.yml/badge.svg)](https://github.com/aoreshkov/rabosh-memory/actions/workflows/ci.yml)
[![JDK 25](https://img.shields.io/badge/JDK-25-437291)](#requirements)
[![Licence: Apache 2.0](https://img.shields.io/badge/Licence-Apache%202.0-blue)](LICENSE)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/aoreshkov/rabosh-memory/badge)](https://scorecard.dev/viewer/?uri=github.com/aoreshkov/rabosh-memory)

The Anthropic **memory tool**, backed by [rabosh](https://github.com/aoreshkov/rabosh) instead of a
directory of files.

Declare `memory_20250818` on a message, hand the tool runner a `RaboshMemoryToolHandler`, and
Claude's memory lives in an embedded, crash-safe, MVCC store: every command is one commit, and a
recursive `rename` is all-or-nothing across a crash.

```kotlin
Rabosh.open(Path.of("memories")).use { db ->
    val createParams = MessageCreateParams.builder()          // com.anthropic.models.beta.messages
        .model(Model.CLAUDE_OPUS_5)
        .maxTokens(1024L)
        .addTool(BetaMemoryTool20250818.builder().build())
        .addUserMessage("Remember that Acme Corp prefers email follow-ups.")
        .build()

    val runnerParams = ToolRunnerCreateParams.builder()
        .betaMemoryToolHandler(RaboshMemoryToolHandler(db))
        .initialMessageParams(createParams)
        .maxIterations(10)
        .build()

    for (message in client.beta().messages().toolRunner(runnerParams)) { … }
}
```

`RaboshMemoryToolHandler.open(Path.of("memories"))` is the one-liner for a host whose only use for
rabosh is this handler; it opens the store, owns it, and closes it with the handler.

**The `Beta` prefixes above belong to the tool runner, not to the memory tool.** The tool itself is
generally available and needs no beta header — declared on an ordinary `client.messages()` call it is
`com.anthropic.models.messages.MemoryTool20250818`. What lives in the SDK's beta namespace is the
*helper* surface: `BetaMemoryToolHandler`, `BetaToolRunner` and the beta `MessageCreateParams` they
require. Using this handler with the runner therefore means depending on a beta helper, and driving
the loop yourself means not depending on one — see
[Errors, and one limitation of the tool runner](#errors-and-one-limitation-of-the-tool-runner).

## Installation

One coordinate. Both dependencies below are declared `api` rather than `implementation`, because
`BetaMemoryToolHandler` is in this module's supertype list and `Rabosh` is a public constructor
parameter — you cannot compile against the handler without them, so they arrive with it and you do
not name either one yourself.

```kotlin
// build.gradle.kts
dependencies {
    implementation("app.oreshkov:rabosh-memory:0.1.1")
}
```

With a version catalogue:

```toml
# gradle/libs.versions.toml
[libraries]
rabosh-memory = { module = "app.oreshkov:rabosh-memory", version = "0.1.1" }
```

Maven:

```xml
<dependency>
    <groupId>app.oreshkov</groupId>
    <artifactId>rabosh-memory</artifactId>
    <version>0.1.1</version>
</dependency>
```

**[JDK 25](#requirements)** is the floor, inherited from the engine.

### What the pins mean for your build

This module pins `com.anthropic:anthropic-java` and `app.oreshkov:rabosh-api` at exact versions —
never a range, never a snapshot — because there is no shared CI matrix across the repository
boundary, and the pin is the whole of the mitigation for skew. The versions are in
[Requirements](#requirements).

That is a pin, not a lock. If your application already declares `anthropic-java` — and it probably
does, since you are declaring the tool on a message — Gradle and Maven resolve the conflict their own
way and your version is the one that wins. That is usually what you want, and the SDK moves weekly
enough that holding you back would be worse. It does mean the combination you ship is one this
repository has not run. `LiveSmokeTest` is the canary for exactly that — one real conversation driven
through the handler — and [Building and testing](#building-and-testing) says how to run it. If you
have moved the SDK a long way and want certainty rather than a resolved graph, that is the suite to
run against your own version.

## What it is

- The six commands — `view`, `create`, `str_replace`, `insert`, `delete`, `rename` — over one rabosh
  store, returning the **documented response strings verbatim**, because those strings are what the
  model has learned to read. Inconsistent prefixes included: `view`'s missing-path string has no
  `Error: ` prefix, `insert`'s and `delete`'s have one and stop there, and `str_replace`'s has the
  prefix *and* a trailing sentence. Tidying them would be a silent divergence from every other
  implementation of this tool.
- **Every command is one commit.** A crash during a recursive `rename` leaves the whole subtree at
  the old paths or the whole subtree at the new ones, never a mixture. A filesystem cannot promise
  that across a device boundary and does not promise it for a recursive move at all.
- Path normalisation that is a **pure string function**, so the key layout means the same thing on
  Windows and Linux. `java.nio.file.Path` is not used, and the build fails if it appears outside the
  one parameter that is genuinely a filesystem location.
- A `scope` key prefix for multi-tenancy, a per-memory size cap, a `usage()` report and an
  `expireBefore()` retention helper.
- An optional listing index, off by default. It does what it says — a directory listing reads **zero
  documents** — and across every size and count measured that buys a tie at best. See
  [The listing index](#the-listing-index).

## What it is not

- **Not a search tool.** The memory tool contract has no search command. "Recall the relevant
  memory" is a second tool your application declares; putting it here would imply a ranking
  capability the engine does not have.
- **Not embeddings.** Same reason, more so.
- **Not a server.** A library in your process. It opens no sockets.
- **Not a new on-disk format.** It writes ordinary documents through rabosh's public API, so
  rabosh's format-permanence rules govern nothing here and no version bump is ever involved.

## Requirements

JDK 25, the same floor as the engine. Two pinned dependencies and no others:

| | |
|---|---|
| `com.anthropic:anthropic-java` | `2.54.0` |
| `app.oreshkov:rabosh-api` | `0.3.0` |

Both are **exact pins, never ranges and never snapshots**. There is no shared CI matrix across the
repository boundary, so the pin is the whole of the mitigation for cross-repo skew, and the live
smoke test is the canary for the SDK moving underneath.

One writing thread — see rabosh's
[INTEGRATION.md](https://github.com/aoreshkov/rabosh/blob/main/INTEGRATION.md) for the runtime
contract, and [COMPATIBILITY.md](https://github.com/aoreshkov/rabosh/blob/main/COMPATIBILITY.md) for
what is promised about the bytes on disk. [CONTRACT.md](CONTRACT.md) is this module's own contract
and links those rather than restating them.

## Errors, and one limitation of the tool runner

`BetaToolRunner` builds a memory tool result one of two ways: a handler that returns normally gives
`content = <the string>` with `is_error` unset, and a handler that throws gives
`content = "Error: <message>"` with `is_error = true`. **It cannot do both a documented string and
`is_error: true`.**

So this handler returns the documented strings and does not throw for expected outcomes. The
specification is explicit that this is fine — *"Claude reads whatever text your tool result contains"*
— and throwing would replace a precise, model-legible message with a generic one. Exceptions are
reserved for genuine faults: the store closed, IO failed, the lock was lost.

**If you need `is_error` fidelity on expected outcomes, drive the tool-use loop yourself** rather
than using the runner. The six methods are the whole surface; nothing here depends on the runner —
and this is also the path that keeps you off the beta helper namespace entirely, since the tool
declaration on a plain `client.messages()` call is `MemoryTool20250818`.

The loop is the ordinary one: call, check `stop_reason` for `tool_use`, answer every `tool_use` block
in a single user turn, repeat. The only part specific to this handler is the dispatch, which is a
`when` over `command` and one `ToolResultBlockParam` per call:

```kotlin
fun memoryResult(block: ToolUseBlock, handler: RaboshMemoryToolHandler): ToolResultBlockParam {
    val input = block._input().asObject().orElseThrow()
    fun text(field: String): String = input[field]?.asString()?.orElse("").orEmpty()
    fun number(field: String): Long = input[field]?.asNumber()?.orElseThrow()?.toLong() ?: 0L

    val viewRange: Optional<List<Long>> = Optional.ofNullable(
        input["view_range"]?.asArray()?.orElse(null)?.map { it.asNumber().orElseThrow().toLong() },
    )

    val answer = when (val command = text("command")) {
        "view" -> handler.view(text("path"), viewRange)
        "create" -> handler.create(text("path"), text("file_text"))
        "str_replace" -> handler.strReplace(text("path"), text("old_str"), text("new_str"))
        "insert" -> handler.insert(text("path"), number("insert_line"), text("insert_text"))
        "delete" -> handler.delete(text("path"))
        "rename" -> handler.rename(text("old_path"), text("new_path"))
        else -> "Error: unknown command $command"
    }

    return ToolResultBlockParam.builder()
        .toolUseId(block.id())
        .content(answer)
        .isError(answer.startsWith("Error: "))   // the half the runner cannot give you
        .build()
}
```

`isError` is keyed off the prefix here because that is the cheapest rule that matches this handler's
strings, and it is worth knowing what it does *not* catch: `view`'s missing-path string has no
`Error: ` prefix — see [Deliberate divergences](#deliberate-divergences) — so that one outcome
arrives as a successful result whose text says otherwise. Key off the return value of the specific
command instead if that distinction matters to you.

## Context editing, and what it does to this store

A store that outlives the context window is only interesting once the context window is actually
being cleared, so the pairing Anthropic recommends — the memory tool together with
`clear_tool_uses_20250919` — is the case this module is built for. Enabling it is a change to the
message parameters, not to the handler:

```kotlin
val createParams = MessageCreateParams.builder()      // com.anthropic.models.beta.messages, as above
    .model(Model.CLAUDE_OPUS_5)
    .maxTokens(4096L)
    .addTool(BetaMemoryTool20250818.builder().build())
    // AnthropicBeta is one package up, in com.anthropic.models.beta; the three builders below sit
    // beside MessageCreateParams.
    .addBeta(AnthropicBeta.CONTEXT_MANAGEMENT_2025_06_27)
    .contextManagement(
        BetaContextManagementConfig.builder()
            .addEdit(
                BetaClearToolUses20250919Edit.builder()
                    .trigger(BetaInputTokensTrigger.builder().value(30_000L).build())
                    .keep(BetaToolUsesKeep.builder().value(3L).build())
                    .build(),
            )
            .build(),
    )
    .addUserMessage("…")
    .build()
```

`ToolRunnerCreateParams` takes those as its `initialMessageParams`, so the beta and the
configuration reach the API through the runner with no further plumbing and the handler is not
involved in the decision at all. `BetaCompact20260112Edit` is available the same way if you want
server-side compaction as well — the two solve different halves of the same problem, and the
specification suggests running both.

Three things follow that are worth knowing before you turn it on.

**The flush is bursty, and it will produce `create` errors.** As the clearing threshold approaches,
Claude is warned and writes what it wants to keep before its tool results disappear. That burst is
where this store's one deliberate divergence becomes visible: a flush that re-`create`s a path it
already wrote earlier in the same session gets `Error: File … already exists` rather than an
overwrite. This is the documented behaviour working — the model reads the file back and edits it —
and it is the shape to expect in a transcript rather than a bug to report. `MemoryOptions(createOverwrites = true)`
is the other choice, and the trade is unchanged by context editing: overwriting costs you the memory
whose existence the model had forgotten.

**Do not put `memory` in `exclude_tools`.** That option exists for results that are expensive to
obtain again — a web search, a paid API call. Memory results are the opposite of that: they are the
cheapest thing in the transcript to throw away, because every one of them can be read back off disk
with a `view` whenever it is next needed. Excluding them keeps bytes in the context window that the
store already holds.

**The burst is still one writing thread.** Several memories arriving at once is the same single-writer
path as any other sequence of commands — see [Requirements](#requirements) — and each is still capped
by `maxMemoryBytes`. Neither is a new constraint; the flush is simply where they are most likely to
be met for the first time.

## Deliberate divergences

Everything below is a choice, not a gap. The specification is at
[platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool),
and where it is silent about *behaviour* the tie-breaker is the SDK's own
`BetaLocalFilesystemMemoryTool`, which is the only concrete reference implementation.

**`create` returns the already-exists error rather than overwriting.** Claude's tool description says
`create` "creates or overwrites", and the specification calls overwriting a valid choice — but an
overwrite of a memory the model has forgotten it wrote is silent data loss, and not losing things is
this store's entire pitch. Set `MemoryOptions(createOverwrites = true)` for the other behaviour.

**A rejected path returns the command's own error string, never a distinct one.** A traversal attempt
gets `view`'s "does not exist", `create`'s "already exists", `rename`'s "destination already exists",
and so on. A distinct message would tell a prompt-injected model exactly which paths the namespace
refuses, which is a probe oracle.

**Accepted paths are echoed back normalised; rejected ones are echoed as given.** `view
/memories/./a.md` answers about `/memories/a.md`, so every path the model reads back is one it can
reuse verbatim. A rejected path has no normalised form, so it is quoted as sent.

**`/memories` is a directory and never a file.** Elsewhere a document and a subtree may coexist under
one path, which a filesystem forbids and this store does not; there the exact key wins. At the root
it must not, because `view /memories` is the first thing the model does in every session and a
document that landed on the root would hide the whole store. So `create`, `str_replace` and `insert`
on `/memories` return their documented refusals.

**Directory sizes are the sum of what is under them.** There are no directory records to `stat` — a
directory exists exactly when some key has it as a prefix — so the summed size is the only honest
number. The reference implementation reports an inode size instead, which is why its example shows
`4.0K` for a directory holding `3.5K` of files.

**An empty `old_str` is answered with "did not appear verbatim".** It matches at every position, and
the reference implementation would report one line number per character of the file.

**A `.png` path is a text memory like any other.** Claude's tool description tells it that `view`
displays image files — `.jpg`, `.jpeg` and `.png` — so it may well `view` one. There are no image
files here: `create` takes `file_text`, so a memory whose path happens to end in `.png` is rendered
with line numbers like every other. Nothing is lost, since the model can only read back what it
wrote, but the promise its tool description makes is one this store has no way to keep.

**Four strings are additions**, because the specification asks integrators to enforce something and
supplies no wording:

| Situation | String |
|---|---|
| A memory over `maxMemoryBytes` | `Error: File {path} exceeds the maximum memory file size of {n} bytes` |
| A `view` cut at `viewMaxChars` | `[Truncated: showing lines {a} to {b} of {n}. Use view_range [{c}, -1] to read the rest.]` |
| `delete` or `rename` of the root | `Error: Cannot delete the /memories directory itself` / `Error: Cannot rename …` |
| `rename` of a subtree into itself | `Error: Cannot rename {old} to {new}, which is inside it` |

**`rename` checks the source before the destination.** When both are wrong, naming the source is the
more useful answer. The SDK's reference implementation checks the other way round.

**The directory header keeps `and node_modules`.** The specification's header sentence is
`…excluding hidden items and node_modules:`; the SDK's implementation omits the second half. The
specification wins for strings, and both exclusions are really applied.

## The listing index

`MemoryOptions(listingIndex = true)` defines an inverted index over `$.anc[*]` and a shredded column
over `$.bytes`, and a directory `view` is then a query that opens **no documents at all** —
`documentsRead == 0`, asserted in `ListingIndexTest`.

**It is off by default and measurement says leave it that way for listings.** From
`ListingIndexBenchmark`, on one developer machine, as the span of every JVM the cell was run in:

| memories | memory size | listing with the index |
|---|---|---|
| 5,000 | 64 B | 0.51x–0.53x the unindexed speed |
| 5,000 | 4 KiB | 0.92x–1.10x — parity, and the sign is not resolved |
| 50,000 | 64 B | 0.48x–0.57x |
| 50,000 | 4 KiB | 0.42x–0.75x |

**A span rather than a figure, because a figure would be a claim the measurement cannot support.**
Separate JVMs disagree by more than the spread within any one of them, and the disagreement is not
uniform: the 5,000 × 64 B cell repeats to within a hundredth, while 50,000 × 4 KiB — the largest
working set here, and the one where page cache rather than CPU decides — ranges from 0.42x to 0.75x.
Quoting a median from either would imply a precision that is not there. The 4 KiB row at 5,000 is the
one that matters for the decision, and it is written as parity because one process put the index
ahead at 1.10x and three put it behind between 0.92x and 0.97x.

The shape is two axes pulling against each other. Memory *size* helps the index: going from 64 B to
4 KiB roughly doubles the unindexed listing (1746 → 3757 µs at 5,000) while moving the indexed one
much less (3419 → 4102 µs), because the scan opens a document per entry and the index opens none.
Memory *count* hurts it: at 4 KiB the ratio falls from parity at 5,000 to somewhere below 0.75x at
50,000. Over the range measured the two never combine into a win — the best result anywhere in the
table is a tie — so there is no configuration here where turning it on pays for itself on listing
latency alone.

What it is still for is the case where *opening* is the cost rather than the comparison — page-cache
footprint on a store whose memories are large and whose listings are frequent. The trend across the
4 KiB rows is the reason to measure rather than assume: memories larger than 4 KiB are the direction
in which this stops being settled. Measure your own shape:

```
./gradlew --stop && ./gradlew test -Prabosh.memory.bench
```

`-Drabosh.memory.bench.memories` and `-Drabosh.memory.bench.payloads` change the shape;
`-Drabosh.memory.bench.warmup`, `-Drabosh.memory.bench.iterations` and `-Drabosh.memory.bench.runs`
are there if you want to argue with the method. The `--stop` is not a ritual: idle Gradle daemons
from earlier invocations stay resident, the listing reads through mapped segments, and a run taken
beside them measured four times slow with every case straddling parity. **Wide ranges in the output,
or a case reported as straddling parity, mean the machine was busy rather than that the answer is
interesting.**

**Turning it on later is not a migration**, whichever way that measurement goes. Indexes are built
retroactively over segments already on disk — no re-ingest, no rewrite, no version bump. That is why
`$.anc` is written on every document from the first commit whether or not anything reads it: adding
that field later is the one thing that *would* have meant rewriting every memory.

## Rollback: a window, not an archive

There are no version records in v0. `MemoryOptions(history = true)` exists and **rejects**, so that a
host reading the option list once can see that the axis is there; v1 carries it, as an append-only
record per mutation giving audit, point-in-time read and redaction.

What v0 answers with is honest and limited. MVCC keeps the versions compaction would otherwise drop
for as long as a `Snapshot` is held, so a snapshot taken before an edit **is** a rollback point while
you hold it — and `Rabosh.checkpoint` turns any moment into a durable copy, hard-linked where the
filesystem allows, safe to take while writing. The limit is that an open snapshot holds back disk
indefinitely. It is a rollback *window*. It is not audit.

## Security

- **Agent memory holds user data by construction, and rabosh writes plain bytes with no encryption at
  rest.** Use filesystem- or volume-level encryption, and validate content before writing if you need
  a guarantee stronger than the model's own reluctance. The specification makes stripping sensitive
  content the integrator's job.
- **`scope` is a key prefix, not a security boundary.** It separates namespaces inside one store, and
  one store is one process with one set of file permissions. A host serving several end users whose
  threat model needs isolation should use one store *directory* per user — and accept that this means
  one `Rabosh` per user and therefore one writing thread per user.
- **A second `Rabosh.open` on the same directory throws `StoreLockedException`, and that is correct.**
  Catch it specifically and read its `holder`; rabosh's `INTEGRATION.md` explains why deleting the
  lock file is the wrong reflex.

## Building and testing

```
./gradlew build                                     # compile, ABI check, unit and property suites
./gradlew test -Drabosh.memory.seed=<seed>          # replay one generated script exactly
./gradlew test -Drabosh.memory.iterations=600       # more generated scripts
./gradlew test -Prabosh.memory.bench                # the listing benchmark, excluded from build
ANTHROPIC_API_KEY=… ./gradlew test -Prabosh.memory.smoke   # one real conversation, excluded from build
ANTHROPIC_API_KEY=… ./gradlew test -Prabosh.memory.smoke -Drabosh.memory.smoke.model=claude-opus-5
```

The suites, and what each is for:

| Suite | What it settles |
|---|---|
| `DifferentialMemoryTest` | Generated scripts run against the handler and against a `TreeMap` reference written longhand; the assertion is that the **returned strings are identical**, plus a model comparison of the store after every command and after a reopen |
| `MemoryPathTest` | Normalisation, including the Windows spellings that are the reason it is a string function |
| `RaboshMemoryToolHandlerTest` | The response strings against the specification rather than against the oracle, plus limits, retention and lifecycle |
| `CrashSafetyTest` | A child JVM killed with `TerminateProcess`/`SIGKILL` mid-`create` and mid-`rename`; the acknowledged prefix, and all-old-or-all-new |
| `ListingIndexTest` | Same answer with and without the index, `documentsRead == 0`, and a retroactive build over memories written without it |
| `ListingIndexBenchmark` | Listing latency and work at two scales. A benchmark that produced no results fails |
| `LiveSmokeTest` | One real conversation, to catch the SDK moving. It **fails** rather than skips without a key |

`checkNoNioPath` fails the build if a main source outside the store-directory allowlist uses
`java.nio.file.Path`; `checkDeleteDoesNotCompact` fails it if `compact()` is called anywhere but
`expireBefore`, because an interactive `delete` that reclaimed tombstones inline would be slow rather
than wrong and no test would see it; and `checkKotlinAbi` fails it if the published surface changed
without the committed dump in `api/` changing with it.

CI runs `build` on Linux **and** Windows, and both are load-bearing rather than a formality:
`MemoryPathTest` covers the Windows path spellings that are the reason normalisation is a string
function, and `CrashSafetyTest` kills its child with `TerminateProcess` there and `SIGKILL` here —
two instruments wearing one name. The live smoke test is dispatched by hand rather than run on a
push, because a suite that needs a network and a card should not be able to fail a commit; a release
runs it.

## Releases and reporting

Changes are recorded in [CHANGELOG.md](CHANGELOG.md). Releases are tagged, built from the tag, signed,
and published to Maven Central through a pipeline whose last step is the announcement rather than the
upload — `.github/workflows/release.yml` says why, and what to run before spending a tag. Every jar
carries a build provenance attestation:

```sh
gh attestation verify rabosh-memory-<version>.jar --repo aoreshkov/rabosh-memory
```

Suspected vulnerabilities go through [private reporting](SECURITY.md), never a public issue.
[SECURITY.md](SECURITY.md) is also where the threat model is written down — the input is
model-generated and therefore hostile, `scope` is a key prefix and not a boundary, and there is no
encryption at rest.

## Contributing

Issues and questions are welcome; [CONTRIBUTING.md](CONTRIBUTING.md) is worth reading before a pull
request, because most of the surprising behaviour here is deliberate and it lists what fails
**silently** if changed — the verbatim response strings, the ban on `java.nio.file.Path`, the
multiple-occurrence check in `str_replace`, and the measured default for the listing index.
Questions and "should this support X" belong in
[discussions](https://github.com/aoreshkov/rabosh-memory/discussions) rather than issues.
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) applies.

## Licence

Apache 2.0. See [LICENSE](LICENSE).
