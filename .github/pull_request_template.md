<!--
History is squash-merged, so the title of this pull request becomes the commit message. Write it as
one: what changed, in the imperative, and short enough to read in a log.
-->

## What changed, and why

<!-- Prose, not a bullet list of files. The why is the part a reviewer cannot reconstruct. -->

## Checklist

- [ ] `./gradlew build` passes locally.
- [ ] A behaviour change has a test that **fails without it**.
- [ ] No new dependency — or it was agreed in an issue first, with the trade against writing it by hand stated.
- [ ] The pinned `anthropic-java` / `rabosh-api` versions are untouched (bumping one is a release-time decision).
- [ ] `java.nio.file.Path` is not used in a main source outside the store-directory allowlist.

## If this touches the six commands

<!-- Delete this section if it does not. -->

- [ ] `DifferentialMemoryTest`'s longhand oracle was updated too, so the two are still independent.
- [ ] Response strings are unchanged — or the change is deliberate, and the reasoning is below.

## If `api/` changed

<!-- Delete this section if it did not. An unexplained ABI dump diff is the thing most likely to stall a review. -->

<!-- Say what moved in the published surface and whether it is source- or binary-compatible. -->
