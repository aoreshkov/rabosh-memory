# Security policy

## Reporting a vulnerability

**Use [GitHub's private vulnerability reporting](https://github.com/aoreshkov/rabosh-memory/security/advisories/new).**
Do not open a public issue for a suspected vulnerability, and do not put one in a pull request
description.

Private reporting gives us a place to work on a fix, request a CVE and publish an advisory without
the details being public first. It is the only reporting channel for this project; there is no
security mailing address to write to.

What helps, roughly in order:

- The version, the JDK, and the pinned `anthropic-java` and `rabosh-api` versions you were on.
- The sequence of memory-tool commands that reproduces it, with the exact `path` and content strings.
  Small is more useful than realistic.
- What you expected the handler to return and what it returned instead.
- Whether it reproduces on a fresh store, or only on one with particular history.

If the defect is in the storage engine underneath rather than in this handler — a corrupt store file
acted upon, a query returning a document a snapshot should not see — report it to
[rabosh](https://github.com/aoreshkov/rabosh/security/advisories/new) instead. If you are unsure
which, report it here and it will be moved.

You should get an acknowledgement within 72 hours. There is no bounty programme — this is a
single-maintainer project — but you will be credited in the advisory unless you would rather not be.

## Supported versions

Pre-1.0, and there is no release with long-term support. Fixes go to the latest release line and
nowhere else.

| Version | Supported |
| --- | --- |
| Latest `0.x` release | Yes |
| Anything earlier | No — upgrade |

Upgrading does not mean rewriting your memories. This module defines no format of its own — it writes
ordinary documents through rabosh's public API — so what protects the bytes is the engine's promise
that a store written by an earlier release opens on every later one. See
[COMPATIBILITY.md](https://github.com/aoreshkov/rabosh/blob/main/COMPATIBILITY.md).

## What is in scope

This is a library in your process implementing a tool an LLM drives. **Its input is therefore
model-generated and must be assumed hostile**, because a model can be made to emit anything by
content it read — a memory written in an earlier session included. That shapes what a vulnerability
here looks like.

**In scope.**

- **Path handling.** `path` arrives from the model. A path that escapes its scope's key range — by
  traversal, by encoding, by a separator or Unicode spelling normalisation does not fold, by
  overflowing a length limit into truncation — is a vulnerability, and the primary one. There is no
  filesystem to escape *into*, which is the design: a path is normalised into a `Key` by a pure
  string function and a malformed one addresses a key that does not exist. A report that a path
  reaches another **scope**, or reaches outside the memory key space entirely, is in scope regardless
  of how harmless the resulting read looks.
- **The error strings as an oracle.** A rejected path returns the command's own "does not exist"
  string and never a distinct one, deliberately: a distinct message is a probe oracle a malicious
  prompt can use to map the store. A response that distinguishes *rejected* from *absent* — in its
  text, or in anything else observable — is a defect worth reporting.
- **Atomicity.** Every command is one commit. A crash, an IO failure or a lost lock that leaves a
  subtree half-renamed, a memory partially written, or the store readable in a state no sequence of
  commands could produce is in scope.
- **Limits.** The size cap and the retention sweep are what stop a model, or content steering one,
  from filling a disk. A way to write past the cap, or to make a single command consume unbounded
  memory, is in scope.
- Anything in the build or release pipeline: a workflow that can be made to run attacker-supplied
  code, a way to get an artefact signed that should not have been.

**Out of scope.**

- **`scope` is a key prefix, not a security boundary**, and this is documented rather than defended.
  It separates namespaces inside one store, and one store is one process with one set of file
  permissions. A host serving several end users whose threat model needs isolation uses one store
  *directory* per user. "Scope A's data is on the same disk as scope B's" is the design; "a path in
  scope A returned a value from scope B" is the bug above.
- **There is no encryption at rest.** rabosh writes plain bytes and says so, and the memory-tool
  specification makes handling sensitive content the integrator's job. Use filesystem- or
  volume-level encryption. A report that memories are readable by someone who can already read the
  store directory is not a vulnerability.
- **A model persuaded to write, overwrite or delete its own memories.** Prompt injection against the
  application driving this handler is real and serious, and it is not something a storage backend can
  answer. What is in scope is a command doing something *other* than what it says — `create`
  overwriting when `createOverwrites` is false, `delete` removing more than the path names.
- **The tool runner's `is_error` behaviour.** This handler returns the documented strings rather than
  throwing for expected outcomes; the README explains why and names the manual-loop escape hatch. It
  is a deliberate trade, not a gap.
- **Source or binary incompatibility.** The Kotlin API is pre-1.0 and tracks somebody else's SDK. Any
  signature may change in any release.
- Defects in the storage engine itself, which has [its own
  policy](https://github.com/aoreshkov/rabosh/blob/main/SECURITY.md) and its own threat model for
  hostile documents and crafted store files.

## Verifying what you got

Every jar published to Maven Central is signed, and every release carries a build provenance
attestation tying the artefact to the workflow run and commit that produced it:

```sh
gh attestation verify rabosh-memory-<version>.jar --repo aoreshkov/rabosh-memory
```

If that does not verify, the jar did not come from this repository's release pipeline. That is worth
reporting.
