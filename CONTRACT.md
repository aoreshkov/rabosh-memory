# Contract

What this module promises, what it borrows, and where the borrowed promises are written down.

Two documents govern the storage underneath and are **linked rather than restated**, because a copy
of somebody else's guarantee is a copy that goes stale without anybody noticing:

- [rabosh `INTEGRATION.md`](https://github.com/aoreshkov/rabosh/blob/main/INTEGRATION.md) — the
  runtime contract: threading, native access, lifecycle, what an unclosed store costs.
- [rabosh `COMPATIBILITY.md`](https://github.com/aoreshkov/rabosh/blob/main/COMPATIBILITY.md) — what
  is promised about the bytes on disk.

Read those for the engine. Everything below is this module's own.

## The tool

`{"type": "memory_20250818", "name": "memory"}`. Generally available on the Messages API; no beta
header. The name must be literally `memory` — `BetaToolRunner` dispatches on that string. The helper
surfaces live in the SDK's beta namespace even though the tool itself is not beta.

## What is stable

`RaboshMemoryToolHandler`, `MemoryOptions`, `MemoryUsage` and `MemoryPath`, as dumped in
`api/rabosh-memory.api`. `checkKotlinAbi` runs as part of `build`, so a change to any of them is a
change to that file in the same commit.

`memory_20250818` is a **dated** type id. A `memory_2027xxxx` with extra commands would mean a new
handler interface in the SDK, not a patch — so `RaboshMemoryToolHandler` is `open`, each command is
`open`, and `MemoryPath` is public. Extending the handler for a later dated tool is meant to be
possible without forking it.

## Keys

```
m:{scope}:{path}
```

- `scope` matches `[A-Za-z0-9_.-]{1,64}`. The excluded character that matters is `:`, which is what
  keeps the key unambiguous.
- `path` is the normalised memory path, always beginning `/memories`.

Key order is UTF-8 byte order, which for these strings is path lexicographic order, so a subtree is
contiguous and a prefix scan is a range scan. The trailing slash in a subtree prefix is load-bearing:
without it the prefix for `/memories/a` also matches `/memories/ab`.

**The `h:` prefix is reserved** for v1's history records at `h:{scope}:{path}:{inverted sequence}` —
inverted so the newest version of a memory sorts first and a point-in-time read is a bounded forward
scan. Do not spend it on anything else.

## There are no directory records

A directory exists exactly when some key has it as a prefix. `create` of `/memories/a/b.md`
implicitly creates `/memories/a`; deleting the last child makes it vanish. Nothing is written for a
directory and nothing has to be cleaned up.

Two consequences:

- A document at `/memories/a` and documents under `/memories/a/` can coexist, which a filesystem
  would forbid. `view` resolves file-first — except at `/memories`, which is always a directory.
- `delete` and `rename` act on *the exact key and the subtree beneath it*, which is a superset of
  both filesystem behaviours and matches the specification's "recursive" wording.

## The document

```json
{
  "path": "/memories/projects/rabosh.md",
  "content": "…",
  "bytes": 1234,
  "lines": 42,
  "createdAt": 1755300000000,
  "updatedAt": 1755300500000,
  "anc": ["/memories", "/memories/projects"]
}
```

An ordinary document written through rabosh's public API. No magic number, no type id, no section
kind — so rabosh's format-permanence rules govern nothing here and **no version bump is ever
involved**.

- `bytes` and `lines` are stored rather than derived, so a listing never decodes `content` and the
  999,999-line check costs a field read.
- Timestamps are epoch millis as **integers**, so a shredded numeric column reconstructs them
  exactly; the engine's one-scale-per-column caveat bites on decimals and this avoids having one.
- `anc` is every ancestor directory, written unconditionally. It exists for the optional listing
  index, and adding it later would have meant rewriting every document that already existed.
- `accessedAt` is absent unless `MemoryOptions(trackAccess = true)`. Recording a read timestamp turns
  every `view` into a commit, and `view /memories` happens at the top of every session.

## Atomicity and durability

**Every command is one commit.** Reads take a snapshot; writes go through a `WriteBatch` even when
they carry one entry, so the commit shape is uniform. A recursive `delete` or `rename` is one batch
however large the subtree, which is why a crash leaves all-old or all-new and never a mixture.

Durability is the engine's default, `Durability.SYNC`: every commit is `fsync`ed before the call
returns. That is the right default here rather than a conservative one — the system prompt the API
injects for this tool says *"ASSUME INTERRUPTION: Your context window might be reset at any moment,
so you risk losing any progress that is not recorded in your memory directory."* A store that
answered that with buffered writes would be answering a different question. Pass a `RaboshOptions`
with `Durability.BUFFERED` and call `sync()` yourself for bulk seeding.

`delete` does **not** call `compact()`. Tombstones are the retention job's business; reclaiming them
inline would make an interactive command unpredictably slow. `expireBefore` does compact, in the
order a drain has to use: pin a snapshot, decide the set, delete, then compact.

## Concurrency and lifecycle

One `ReentrantLock`, held for the whole of every command — which is what makes the read-modify-write
in `str_replace` and `insert` atomic against a second agent loop in the same process. Cross-process
is prevented by the engine's directory lock.

`RaboshMemoryToolHandler` does **not** own its `Rabosh` by default; the host opens and closes it,
because the host may want the same store for other data. `RaboshMemoryToolHandler.open(directory)`
opens and owns one, and closing it closes the store. Leaking a store costs the directory lock, every
mapping, and on Windows a directory that can never be deleted — `INTEGRATION.md` has the detail, and
`RaboshMemoryToolHandlerTest` asserts it by deleting the directory rather than by measuring anything.

The handler maps no memory and needs no native access of its own. Whether the engine underneath does
is answered by `INTEGRATION.md`.

## Limits

| | Default | Enforced on |
|---|---|---|
| `maxMemoryBytes` | 1 MiB | `create`, `str_replace`, `insert` |
| `viewMaxChars` | 16,000 | `view`, after any `view_range` |
| Path length | 1024 bytes UTF-8 | every command |
| Segment length | 255 bytes UTF-8 | every command |
| Line limit | 999,999 | `view` |

`viewMaxChars` defaults to 16,000 because that is what Claude's own tool description tells the model
to expect, not because it was chosen here. Total usage is **reported** by `usage()` and not enforced:
a hard cap needs a policy — *which memory loses?* — and that belongs to the host.

## Response strings

Reproduced verbatim from the specification, inconsistent prefixes and all. The full list of
deliberate divergences and additions is in the [README](README.md#deliberate-divergences); the
enforcement is `DifferentialMemoryTest`, which runs generated command scripts against an independent
reference implementation and asserts the returned strings are identical.
