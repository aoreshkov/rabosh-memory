package app.oreshkov.rabosh.memory

/** Default per-memory ceiling: 1 MiB. */
public const val DEFAULT_MAX_MEMORY_BYTES: Long = 1L * 1024 * 1024

/**
 * Default ceiling on a rendered `view`: 16,000 characters.
 *
 * Not chosen here — it is what Claude's own tool description tells the model to expect, so a view
 * that stopped somewhere else would surprise it in a way no error message can fix.
 */
public const val DEFAULT_VIEW_MAX_CHARS: Int = 16_000

/** Default scope, for the single-tenant host that has no use for the axis. */
public const val DEFAULT_SCOPE: String = "default"

/**
 * Tuning for [RaboshMemoryToolHandler].
 *
 * A plain class with default arguments rather than a `data class`, for the reason the engine's own
 * options objects give: `copy` and `componentN` would join the published ABI, and adding an option
 * later would then be a binary-incompatible change to a type whose whole purpose is to grow.
 */
public class MemoryOptions(
    /**
     * The tenant, agent or conversation these memories belong to. A key prefix.
     *
     * **Not a security boundary.** It separates namespaces inside one store, and one store is one
     * process with one set of file permissions. A host serving several end users whose threat model
     * needs isolation should use one store *directory* per user — and accept that this means one
     * `Rabosh` per user and therefore one writing thread per user.
     *
     * Constrained to `[A-Za-z0-9_.-]{1,64}`. The excluded character that matters is `:`, which is
     * what keeps `m:{scope}:{path}` unambiguous.
     */
    public val scope: String = DEFAULT_SCOPE,
    /**
     * Largest a single memory may become, in UTF-8 bytes. Enforced on `create`, `str_replace` and
     * `insert`.
     *
     * The specification asks integrators to cap this and names no number. A megabyte is roughly a
     * quarter of a million tokens — far more than a memory the model will read back in one `view`,
     * and small enough that a runaway loop is stopped rather than merely slowed.
     */
    public val maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES,
    /**
     * Largest rendered `view`, in characters, after any `view_range` has been applied.
     *
     * Applied after range selection on purpose, so a ranged view of a long file is not truncated
     * twice. What is cut is whole lines, and the note that replaces them names the line to resume
     * at — see [MemoryResponses.viewTruncated].
     */
    public val viewMaxChars: Int = DEFAULT_VIEW_MAX_CHARS,
    /**
     * Whether `create` on an existing path overwrites it instead of returning the documented error.
     *
     * `false`, against what the model is told. Claude's tool description says `create` *"creates or
     * overwrites"*, and the specification calls overwriting a valid choice — but an overwrite of a
     * memory the model has forgotten it wrote is silent data loss, and not losing things is this
     * store's entire pitch. The error names the path, and `str_replace`, or `delete` then `create`,
     * is one more turn.
     */
    public val createOverwrites: Boolean = false,
    /**
     * Whether a `view` records `$.accessedAt` on the document it read.
     *
     * `false`, and the cost is the reason: recording a read timestamp turns every `view` into a
     * commit, and the API's injected system prompt makes `view /memories` the first thing that
     * happens in every session. With [maxMemoryBytes]-sized documents that is a full rewrite per
     * read, and under the default durability a genuine extra `fsync`.
     *
     * Expiry by `$.updatedAt` is the cheap alternative and is what [RaboshMemoryToolHandler.expireBefore]
     * uses when this is off.
     */
    public val trackAccess: Boolean = false,
    /**
     * Whether the handler defines an index that answers a directory `view` without opening a
     * document.
     *
     * **`false`, and measurement says leave it that way for a directory listing.** It delivers
     * exactly what it promises — `documentsRead` is `0` with it and one per memory without, which
     * `ListingIndexTest` asserts — and that turns out not to be a latency win here. Both paths are
     * linear in the number of entries the listing returns, and the query's constant is the larger
     * one, so the gap widens rather than closes as the store grows. `ListingIndexBenchmark`,
     * measured on one developer machine:
     *
     * | memories | memory size | listing with the index |
     * |---|---|---|
     * | 5,000 | 64 B | 0.58x the unindexed speed |
     * | 5,000 | 4 KiB | 0.93x |
     * | 50,000 | 64 B | 0.50x |
     *
     * The reason is the engine rather than the index. A scan's "document read" is an open, not a
     * decode: `Variant` is a view over mapped bytes, so reading `$.bytes` out of a 4 KiB memory
     * costs the field and not the memory — which is why growing the memories tenfold barely moved
     * the unindexed number. And a listing is not selective: the key range already bounds the scan to
     * the subtree, so the index has no rows to eliminate, only the same rows to produce more
     * expensively.
     *
     * What it is still for is the case where *opening* is the cost rather than the comparison —
     * page-cache footprint on a store whose memories are large and whose listing is frequent, where
     * touching every memory's blocks to read one integer is the thing being avoided. Measure your
     * own shape before turning it on; the benchmark takes a `-Drabosh.memory.bench.memories` and
     * will tell you.
     *
     * **Turning it on later is not a migration**, whichever way that measurement goes. Indexes are
     * built retroactively over segments that are already on disk — no re-ingest, no rewrite, no
     * version bump — which is why `$.anc` is written on every document from the start whether or not
     * anything reads it. Adding that field later is the one thing that *would* have meant rewriting
     * every memory.
     *
     * Requires the `Rabosh` instance to have been opened with `RaboshOptions(indexes = true)`,
     * which is the default.
     */
    public val listingIndex: Boolean = false,
    /**
     * Whether an append-only version record is written per mutation. **Must be `false`.**
     *
     * The option exists in v0 and rejects `true` rather than arriving later, so that a host reading
     * the option list once can see that the axis is there. v1 carries it: a record at
     * `h:{scope}:{path}:{inverted sequence}` giving audit, point-in-time read and redaction — and
     * doubling the write path, which is why it is not on the critical path of every memory edit
     * before anyone has asked for one.
     *
     * What v0 answers the rollback question with is a *window*, not an archive, and the README says
     * so rather than implying audit: MVCC keeps the versions compaction would otherwise drop for as
     * long as a `Snapshot` is held, and `Rabosh.checkpoint` turns any moment into a durable copy.
     */
    public val history: Boolean = false,
) {
    init {
        require(SCOPE.matches(scope)) {
            "scope must match ${SCOPE.pattern} — ':' in particular is excluded because it separates " +
                "the key's fields — was '$scope'"
        }
        require(maxMemoryBytes > 0) { "maxMemoryBytes must be positive, was $maxMemoryBytes" }
        require(viewMaxChars > 0) { "viewMaxChars must be positive, was $viewMaxChars" }
        require(!history) {
            "history ships in v1, not v0. v0 offers a rollback window instead: hold a Snapshot " +
                "across the edits you might undo, or take a Rabosh.checkpoint. See MemoryOptions.history."
        }
    }

    override fun toString(): String =
        "MemoryOptions(scope=$scope, maxMemoryBytes=$maxMemoryBytes, viewMaxChars=$viewMaxChars, " +
            "createOverwrites=$createOverwrites, trackAccess=$trackAccess, listingIndex=$listingIndex)"

    public companion object {
        // Declared before DEFAULT and not after: companion properties initialise in source order,
        // and DEFAULT's constructor reads this one.
        private val SCOPE = Regex("[A-Za-z0-9_.-]{1,64}")

        /** One scope, a 1 MiB ceiling, no overwriting, no access tracking, no listing index. */
        public val DEFAULT: MemoryOptions = MemoryOptions()
    }
}
