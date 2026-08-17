# Contributing

Thank you for looking. This is a single-maintainer project, so the honest summary is: issues and
questions are always welcome, and pull requests are welcome with one caveat — most of this module's
surprising behaviour is deliberate, and the reasoning is written down next to the thing it governs.
Reading that first is the difference between a patch that lands and one that gets a long reply.

## Before you change behaviour, read these

| Where | What it settles |
|---|---|
| [`CONTRACT.md`](CONTRACT.md) | Key layout, document shape, atomicity, durability, lifecycle, limits |
| [`README.md`](README.md#deliberate-divergences) | The deliberate divergences from the tool specification, and the measurements behind the options |
| [`CLAUDE.md`](CLAUDE.md) | The decisions already taken, and why each is settled rather than open |
| KDoc | The reasoning for a rule sits on the declaration that rule governs, not in a document |

The upstream contract being implemented is Anthropic's
[memory tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool). Where it is
silent, the tie-breaker is the SDK's own `BetaLocalFilesystemMemoryTool` — the only concrete
reference implementation, which disagrees with the specification in a few places. **The specification
wins for strings; the SDK wins for algorithms.**

## The rules that fail silently

Every item here looks like a defect on first reading and is not. They are grouped because they share
a failure mode: breaking one produces a divergence the *model* absorbs, not a build that goes red.
Each names what catches a breach — and where nothing does, that is said too.

- **The response strings are reproduced verbatim, inconsistent prefixes and all.** `view`'s
  missing-path string has no `Error: ` prefix, `insert`'s and `delete`'s have one and stop there, and
  `str_replace`'s has the prefix *and* a trailing sentence. Tidying them into one shape is a silent
  divergence from every other implementation the model was trained against. See `MemoryResponses`'
  KDoc. Caught by `DifferentialMemoryTest` and `RaboshMemoryToolHandlerTest`.
- **A rejected path returns the command's own "does not exist" string**, never a distinct error. A
  distinct message is a probe oracle for a malicious prompt. Caught by `RaboshMemoryToolHandlerTest`.
- **`java.nio.file.Path` must not be used.** Path normalisation is a pure string function: on Windows
  `Path` normalises with backslashes and applies drive-letter semantics, and a platform-dependent key
  layout is a store that means different things on different machines. See `MemoryPath`'s KDoc.
  Caught by the `checkNoNioPath` build task, which `check` depends on.
- **`str_replace` is not `replaceFirst`.** The multiple-occurrence check is the point, and this is the
  one command where a naive implementation silently edits the wrong occurrence. Caught by
  `DifferentialMemoryTest`, whose generators are seeded with duplicate-bearing content on purpose.
- **Every command is one commit.** A crash mid-`rename` of a subtree leaves all-old or all-new, never
  a mixture. Caught by `CrashSafetyTest`, which asserts the child was still alive when it was killed
  so the instrument cannot pass vacuously.
- **`delete` does not call `compact()`.** Tombstones are the retention job's business, not the
  interactive command's. **Nothing catches a breach of this** — it shows up as latency, not failure.
- **Accepted paths are echoed back normalised, rejected ones as given.** Changing that changes what
  the model reads back and can reuse.
- **The listing index default is measured, not assumed.** It delivers what it promises — a directory
  `view` reads zero documents — and over every size and count measured that buys a tie at best: about
  0.5x the unindexed listing at 64 B, 0.42x–0.75x at 50,000 × 4 KiB, and parity at 5,000 × 4 KiB with
  the sign unresolved between JVMs. Do not change the default without re-running
  `ListingIndexBenchmark`; `MemoryOptions.listingIndex` carries the table. **Run it with
  `./gradlew --stop` first and quote a span across JVMs, never an average** — the numbers this
  replaced were measured before the JIT had finished, and a later run beside a few idle daemons came
  out four times slow. Wide ranges, or a case the report calls out as straddling parity, mean the
  machine was busy.

Two more, from `CLAUDE.md`, that are scope rather than mechanism: there is **no search and no
embeddings** here, because the tool contract has no search command and putting one here would imply a
ranking capability the engine does not have; and **`scope` is a key prefix, not a security
boundary** — one directory per end user when the threat model needs one.

If you think one of these is wrong, that is a conversation worth having — open an issue and make the
argument. It is just not a conversation to have for the first time in a pull request diff.

## Building and testing

JDK 25. The wrapper handles the rest.

```sh
./gradlew build                                # compile, ABI check, unit and property suites
./gradlew test -Drabosh.memory.seed=<seed>     # replay one generated script exactly
./gradlew test -Prabosh.memory.bench           # the listing benchmark, excluded from build
```

[Building and testing](README.md#building-and-testing) in the README has the full table of suites and
what each one settles. Two things worth knowing before you push:

- **`checkKotlinAbi` fails the build if the published surface changed** without the committed dump in
  `api/` changing with it. If your change is deliberately an API change, regenerate the dump and say
  so in the pull request — an unexplained `api/` diff is the thing most likely to stall a review.
- **CI runs on Linux and Windows, and both are load-bearing.** `MemoryPathTest` covers the Windows
  path spellings that are the reason normalisation is a string function, and `CrashSafetyTest` kills
  its child with `TerminateProcess` there and `SIGKILL` here. A change that passes on one is not
  known to pass.
- **`LiveSmokeTest` costs money and needs a key.** It is dispatched by hand, never on a push. You are
  not expected to run it; the maintainer runs it before a release.

## Pull requests

- **Branch, then open a pull request.** `main` is protected and history is squash-merged, so the pull
  request title becomes the commit message — write it as one.
- **Say what changed and why in prose.** This repository argues for its decisions in full sentences
  everywhere else; a pull request is not the place to stop.
- **A behaviour change needs a test that fails without it.** For anything touching the six commands,
  that usually means `DifferentialMemoryTest` — the oracle is written longhand precisely so a change
  has something independent to disagree with.
- **Adding a dependency requires asking first**, and the trade against writing it by hand stated in
  the issue. JetBrains and Kotlin libraries are pre-approved; `anthropic-java` and `rabosh-api` are
  the only third-party runtime dependencies this module may have. `CLAUDE.md` has the policy.
- **Do not bump the pinned versions in a drive-by.** The exact pins are the whole of the mitigation
  for cross-repo skew, and moving one is a release-time decision with a smoke test attached.

Releases, tags and Maven Central publishing are maintainer-only — `.github/workflows/release.yml`
documents the order and why it is that order.

## Issues, questions and security

- **Bugs** → [issues](https://github.com/aoreshkov/rabosh-memory/issues). A seed from
  `DifferentialMemoryTest`, or the exact sequence of commands with the literal `path` and content
  strings, is worth more than a description. Small beats realistic.
- **Questions, ideas, "should this support X"** → [discussions](https://github.com/aoreshkov/rabosh-memory/discussions).
- **Suspected vulnerabilities** → **never** a public issue. [SECURITY.md](SECURITY.md) has the
  private channel and the threat model.
- **Conduct** → [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Licence

By contributing you agree that your contributions are licensed under [Apache 2.0](LICENSE), the same
licence as the project. There is no separate CLA.
