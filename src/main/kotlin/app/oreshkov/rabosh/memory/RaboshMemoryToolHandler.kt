package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.api.Rabosh
import app.oreshkov.rabosh.api.RaboshOptions
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.core.WriteBatch
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBuilder
import com.anthropic.helpers.BetaMemoryToolHandler
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.floor

/**
 * The Anthropic memory tool's six commands, over one rabosh store.
 *
 * ```kotlin
 * Rabosh.open(Path.of("memories")).use { db ->
 *     val createParams = MessageCreateParams.builder()          // com.anthropic.models.beta.messages
 *         .model(Model.CLAUDE_OPUS_5)
 *         .maxTokens(1024L)
 *         .addTool(BetaMemoryTool20250818.builder().build())
 *         .addUserMessage("Remember that Acme Corp prefers email follow-ups.")
 *         .build()
 *
 *     val runnerParams = ToolRunnerCreateParams.builder()
 *         .betaMemoryToolHandler(RaboshMemoryToolHandler(db))
 *         .initialMessageParams(createParams)
 *         .maxIterations(10)
 *         .build()
 *
 *     for (message in client.beta().messages().toolRunner(runnerParams)) { … }
 * }
 * ```
 *
 * ### What it promises that a directory of files does not
 *
 * **Every command is one commit.** A crash mid-`rename` of a subtree leaves either the whole subtree
 * at the old paths or the whole subtree at the new ones, never a mixture — a filesystem cannot
 * promise that across a device boundary and does not promise it for a recursive move at all. That
 * claim is asserted rather than stated: `CrashSafetyTest` kills a child JVM in the middle of one.
 *
 * **A directory listing can read zero documents.** With [MemoryOptions.listingIndex] on, a `view` of
 * a directory is answered from an index over `$.anc[*]` and a shredded `$.bytes` column, so no
 * memory is opened to find out how big it is. Because indexes are built retroactively, turning that
 * on later is a sidecar build over data already on disk — no re-ingest and no rewrite. It is off by
 * default and, on the evidence of `ListingIndexBenchmark`, should usually stay off: `documentsRead`
 * really does go to zero, and that is not the same as being faster. [MemoryOptions.listingIndex] has
 * the numbers and the reason.
 *
 * ### Errors, and the one thing the tool runner cannot do
 *
 * `BetaToolRunner` builds a memory tool result one of two ways: a handler that returns normally
 * gives `content = <the string>` with `is_error` unset, and a handler that throws gives
 * `content = "Error: <message>"` with `is_error = true`. **It cannot do both a documented string and
 * `is_error: true`.**
 *
 * So this handler **returns the documented strings and does not throw for expected outcomes** — a
 * missing path, a duplicate `old_str`, a `create` on something that already exists. The
 * specification is explicit that this is fine (*"Claude reads whatever text your tool result
 * contains"*), and throwing would replace a precise, model-legible message with a generic one.
 * Exceptions are reserved for genuine faults — the store closed, IO failed, the lock was lost —
 * where `is_error: true` is right and the message is for the operator rather than the model.
 *
 * A host that needs `is_error` fidelity on expected outcomes has to drive the tool-use loop itself
 * rather than use the runner. Nothing here stops that: the six methods are the whole surface.
 *
 * ### Concurrency and ownership
 *
 * One [ReentrantLock], taken for the whole of every command. Contention is nil — one agent loop
 * issues one command at a time — and holding it across the read-modify-write in `str_replace` and
 * `insert` is what makes those atomic against a second loop in the same process. Cross-process is
 * already prevented by the engine's directory lock; a second `Rabosh.open` on the same directory
 * throws `StoreLockedException`, which is correct, and rabosh's `INTEGRATION.md` says what to do
 * with it.
 *
 * **This handler does not own the store.** The host opens it and closes it, because the host may
 * want the same store for other data. [open] is the convenience that opens and owns one; closing a
 * handler that does not own its store closes nothing.
 *
 * ### Extending it
 *
 * `memory_20250818` is a *dated* type id. A `memory_2027xxxx` with extra commands would mean a new
 * handler interface, not a patch — so this class is `open`, each command is `open`, and the store
 * and options a subclass would need are `protected`. [MemoryPath] is public for the same reason.
 */
public open class RaboshMemoryToolHandler private constructor(
    /** The store this handler reads and writes. Not closed by [close] unless [open] created it. */
    protected val database: Rabosh,
    /** How this handler behaves. See [MemoryOptions]. */
    protected val options: MemoryOptions,
    private val ownsDatabase: Boolean,
) : BetaMemoryToolHandler, AutoCloseable {

    /**
     * A handler over a store the caller owns, opens and closes.
     *
     * @throws IllegalStateException if [options] asks for a listing index and [database] was opened
     *   with `RaboshOptions(indexes = false)`.
     */
    @JvmOverloads
    public constructor(
        database: Rabosh,
        options: MemoryOptions = MemoryOptions.DEFAULT,
    ) : this(database, options, ownsDatabase = false)

    private val lock = ReentrantLock()

    /**
     * Reused across the commands of one handler, and safe because `WriteBatch.put` copies the
     * document bytes at staging time rather than at commit time. The dictionary is interned once for
     * the seven field names rather than per document.
     */
    private val builder = VariantBuilder()

    private val keyPrefix = "m:${options.scope}:"

    private var closed = false

    init {
        if (options.listingIndex) {
            // Retroactive by construction: this builds sidecars over segments that are already on
            // disk, so switching the option on for an existing store is a build rather than a
            // migration. Documents still in a memtable are covered by the scan half of a plan.
            database.createIndex(IndexDefinition.inverted(MemoryDocument.ANCESTORS_PATH))
            database.createIndex(IndexDefinition.column(MemoryDocument.BYTES_PATH))
        }
    }

    // --- the six commands -----------------------------------------------------------------------

    override fun view(path: String, viewRange: Optional<List<Long>>): String =
        withStore { doView(path, viewRange.orElse(null)) }

    override fun create(path: String, fileText: String): String =
        withStore { doCreate(path, fileText) }

    override fun strReplace(path: String, oldStr: String, newStr: String): String =
        withStore { doStrReplace(path, oldStr, newStr) }

    override fun insert(path: String, insertLine: Long, insertText: String): String =
        withStore { doInsert(path, insertLine, insertText) }

    override fun delete(path: String): String =
        withStore { doDelete(path) }

    override fun rename(oldPath: String, newPath: String): String =
        withStore { doRename(oldPath, newPath) }

    // --- housekeeping the tool contract has no command for --------------------------------------

    /**
     * How much this scope holds: how many memories, and how many bytes of content between them.
     *
     * Cheap with [MemoryOptions.listingIndex] on, because the sizes come from a shredded column and
     * no document is opened; a scope scan without it. Reported rather than enforced: a hard total
     * cap needs a policy — *which memory loses?* — and that policy belongs to the host, not to the
     * store.
     */
    public fun usage(): MemoryUsage = withStore {
        var memories = 0L
        var bytes = 0L
        forEachInScope { _, size ->
            memories += 1
            bytes += size
        }
        MemoryUsage(memories, bytes)
    }

    /**
     * Deletes every memory in this scope last written — or last read, when
     * [MemoryOptions.trackAccess] is on — before [instant], and returns how many.
     *
     * The order is the one a drain has to use and the one rabosh's own `runDrain` sample
     * demonstrates: pin a snapshot, decide the set against it, delete, **then** compact. A drain
     * that never compacts grows for ever while reporting that it deleted everything, because the
     * tombstones it wrote are what make the keys disappear and compaction is what makes the
     * tombstones disappear.
     *
     * Unlike the six commands this one does compact, and that is the division of labour: reclaiming
     * space is the retention job's business, not an interactive `delete`'s.
     */
    public fun expireBefore(instant: Instant): Long = withStore {
        val cutoff = instant.toEpochMilli()
        val batch = WriteBatch()
        database.snapshot().use { snapshot ->
            scanScope(snapshot) { key, document ->
                val stamp = if (options.trackAccess) {
                    MemoryDocument.accessedAt(document) ?: MemoryDocument.updatedAt(document)
                } else {
                    MemoryDocument.updatedAt(document)
                }
                if (stamp < cutoff) batch.delete(key)
            }
        }
        val expired = batch.size.toLong()
        if (expired > 0) {
            database.write(batch)
            database.compact()
        }
        expired
    }

    /**
     * Closes the store, but only if [open] created it.
     *
     * A handler over a store the caller opened closes nothing — the caller may still be using it.
     * A handler from [open] closes it here, and leaking one costs the directory lock, every mapping,
     * and on Windows a directory that can never be deleted. Idempotent.
     */
    override fun close() {
        val owned = lock.withLock {
            if (closed) return
            closed = true
            ownsDatabase
        }
        if (owned) database.close()
    }

    override fun toString(): String =
        "RaboshMemoryToolHandler(${database.directory}, scope=${options.scope}" +
            (if (lock.withLock { closed }) ", closed)" else ")")

    // --- view -----------------------------------------------------------------------------------

    private fun doView(requested: String, viewRange: List<Long>?): String {
        val path = MemoryPath.normalise(requested) ?: return MemoryResponses.viewMissing(requested)

        // The root is a directory and never a file. Everywhere else a document and a subtree may
        // coexist — which a filesystem forbids and this store does not — and there the exact key
        // wins. Here it must not: `view /memories` is the first thing the model does in every
        // session, and a document that happened to land on the root would hide the whole store.
        if (!MemoryPath.isRoot(path)) {
            val document = database.get(key(path))
            if (document != null) return renderFile(path, document, viewRange)
        }

        database.snapshot().use { snapshot ->
            val entries = listingEntries(path, snapshot)
            // An empty store is not an error, and `view /memories` on one is not either: the API
            // injects "ALWAYS VIEW YOUR MEMORY DIRECTORY BEFORE DOING ANYTHING ELSE", so answering
            // the very first call of every session with "does not exist" would be a bad first
            // impression by construction.
            if (entries.isEmpty() && !MemoryPath.isRoot(path)) return MemoryResponses.viewMissing(path)
            return renderDirectory(path, entries)
        }
    }

    private fun renderFile(path: String, document: Variant, viewRange: List<Long>?): String {
        // Every field is read out before anything is written back, because a `Variant` is a view
        // over stored bytes rather than a copy of them.
        val content = MemoryDocument.content(document)
        val storedLines = MemoryDocument.lines(document)
        val createdAt = MemoryDocument.createdAt(document)
        val updatedAt = MemoryDocument.updatedAt(document)

        if (storedLines > MemoryResponses.MAX_LINES) return MemoryResponses.exceedsLineLimit(path)
        if (options.trackAccess) recordAccess(path, content, createdAt, updatedAt)

        // Split without stripping a trailing newline, which is what the SDK's reference
        // implementation does: a file ending in "\n" therefore shows a final empty line.
        val lines = content.split("\n")
        if (lines.size > MemoryResponses.MAX_LINES) return MemoryResponses.exceedsLineLimit(path)

        var first = 0
        var last = lines.size
        if (viewRange != null && viewRange.size == 2) {
            first = clampIndex(maxOf(1L, viewRange[0]) - 1, lines.size)
            val end = viewRange[1]
            last = when {
                end == -1L -> lines.size
                // Python's slice semantics, reproduced: a negative end counts back from the last
                // line. `[start, -1]` means "to the end" and is special-cased above.
                end < 0 -> clampIndex(lines.size + end, lines.size)
                else -> clampIndex(end, lines.size)
            }
        }

        val selected = if (last > first) lines.subList(first, last) else emptyList()
        return render(MemoryResponses.fileHeader(path), first + 1, selected, lines.size)
    }

    /**
     * The numbered body, cut to [MemoryOptions.viewMaxChars] at a line boundary.
     *
     * At least one line is always rendered: a single line longer than the whole budget should come
     * back truncated-with-a-note rather than as an empty view the model cannot act on.
     */
    private fun render(header: String, firstLineNumber: Int, lines: List<String>, totalLines: Int): String {
        val body = StringBuilder(header)
        var bodyLength = 0
        var rendered = 0
        for ((offset, line) in lines.withIndex()) {
            val entry = MemoryResponses.numberedLine(firstLineNumber + offset, line)
            if (rendered > 0 && bodyLength + 1 + entry.length > options.viewMaxChars) break
            body.append('\n').append(entry)
            bodyLength += (if (rendered > 0) 1 else 0) + entry.length
            rendered++
        }
        if (rendered < lines.size) {
            body.append('\n').append(
                MemoryResponses.viewTruncated(
                    shown = firstLineNumber..(firstLineNumber + rendered - 1),
                    resumeAt = firstLineNumber + rendered,
                    totalLines = totalLines,
                ),
            )
        }
        return body.toString()
    }

    private fun renderDirectory(path: String, entries: List<ListingEntry>): String {
        val root = ListingNode()
        var total = 0L
        for (entry in entries) {
            root.add(entry.segments, entry.bytes)
            total += entry.bytes
        }

        val out = StringBuilder(MemoryResponses.directoryHeader(path))
        out.append('\n').append(humanSize(total)).append('\t').append(path)
        root.emit(path, depth = 1, out = out)
        return out.toString()
    }

    // --- create ---------------------------------------------------------------------------------

    private fun doCreate(requested: String, fileText: String): String {
        // A rejected path answers with `create`'s own error rather than a distinct one. A distinct
        // message would tell a malicious prompt exactly which paths the namespace refuses, which is
        // a probe oracle; this tells it only that the write did not happen.
        val path = MemoryPath.normalise(requested) ?: return MemoryResponses.alreadyExists(requested)
        if (MemoryPath.isRoot(path)) return MemoryResponses.alreadyExists(path)

        if (MemoryDocument.utf8Size(fileText) > options.maxMemoryBytes) {
            return MemoryResponses.exceedsSizeLimit(path, options.maxMemoryBytes)
        }

        val existing = database.get(key(path))
        if (existing != null && !options.createOverwrites) return MemoryResponses.alreadyExists(path)

        val now = System.currentTimeMillis()
        val createdAt = existing?.let { MemoryDocument.createdAt(it) } ?: now
        commitOne(path, fileText, createdAt, now)
        return MemoryResponses.created(path)
    }

    // --- str_replace ----------------------------------------------------------------------------

    private fun doStrReplace(requested: String, oldStr: String, newStr: String): String {
        val path = MemoryPath.normalise(requested) ?: return MemoryResponses.strReplaceMissing(requested)
        if (MemoryPath.isRoot(path)) return MemoryResponses.strReplaceMissing(path)

        val document = database.get(key(path)) ?: return MemoryResponses.strReplaceMissing(path)
        val content = MemoryDocument.content(document)
        val createdAt = MemoryDocument.createdAt(document)

        // An empty `old_str` matches at every position. The reference implementation would report
        // one line number per character of the file; refusing it as "did not appear verbatim" is a
        // documented string, is bounded, and leads the model to the same next move.
        if (oldStr.isEmpty()) return MemoryResponses.notVerbatim(oldStr, path)

        val at = content.indexOf(oldStr)
        if (at < 0) return MemoryResponses.notVerbatim(oldStr, path)
        // Counted without overlaps, as the reference does, while the *reported* line numbers come
        // from an overlapping walk. The two disagree only for a self-overlapping `old_str` ("aa" in
        // "aaa"), where the non-overlapping count is what decides whether the edit is unambiguous.
        //
        // This is not `replaceFirst`, and that is the point of the command: a naive implementation
        // silently edits the wrong occurrence, which is the one failure the model cannot see.
        if (nonOverlappingCount(content, oldStr) > 1) {
            return MemoryResponses.multipleOccurrences(oldStr, occurrenceLines(content, oldStr))
        }

        val updated = content.replaceRange(at, at + oldStr.length, newStr)
        if (MemoryDocument.utf8Size(updated) > options.maxMemoryBytes) {
            return MemoryResponses.exceedsSizeLimit(path, options.maxMemoryBytes)
        }
        commitOne(path, updated, createdAt, System.currentTimeMillis())

        // The snippet is the reference implementation's: two lines of context either side of the
        // changed line, numbered against the *new* content.
        val changedIndex = content.take(at).count { it == '\n' }
        val newLines = updated.split("\n")
        val from = maxOf(0, changedIndex - 2)
        val to = minOf(newLines.size, changedIndex + 3)
        val snippet = (from until to).joinToString("\n") { MemoryResponses.numberedLine(it + 1, newLines[it]) }
        return "${MemoryResponses.EDITED}\n$snippet"
    }

    // --- insert ---------------------------------------------------------------------------------

    private fun doInsert(requested: String, insertLine: Long, insertText: String): String {
        val path = MemoryPath.normalise(requested) ?: return MemoryResponses.insertMissing(requested)
        if (MemoryPath.isRoot(path)) return MemoryResponses.insertMissing(path)

        val document = database.get(key(path)) ?: return MemoryResponses.insertMissing(path)
        val content = MemoryDocument.content(document)
        val createdAt = MemoryDocument.createdAt(document)

        val lines = linesForInsert(content)
        if (insertLine < 0 || insertLine > lines.size) {
            return MemoryResponses.invalidInsertLine(insertLine, lines.size)
        }

        // One trailing newline, not all of them: the text the model sends is a line, and a line that
        // was meant to be followed by a blank one should still be.
        lines.add(insertLine.toInt(), insertText.removeSuffix("\n"))
        val joined = lines.joinToString("\n")
        val updated = if (joined.endsWith("\n")) joined else "$joined\n"

        if (MemoryDocument.utf8Size(updated) > options.maxMemoryBytes) {
            return MemoryResponses.exceedsSizeLimit(path, options.maxMemoryBytes)
        }
        commitOne(path, updated, createdAt, System.currentTimeMillis())
        return MemoryResponses.inserted(path)
    }

    // --- delete ---------------------------------------------------------------------------------

    private fun doDelete(requested: String): String {
        val path = MemoryPath.normalise(requested) ?: return MemoryResponses.deleteMissing(requested)
        if (MemoryPath.isRoot(path)) return MemoryResponses.CANNOT_DELETE_ROOT

        val batch = WriteBatch()
        database.snapshot().use { snapshot ->
            if (database.get(key(path), snapshot) != null) batch.delete(key(path))
            scanSubtree(path, snapshot) { subtreeKey, _ -> batch.delete(subtreeKey) }
        }
        if (batch.isEmpty()) return MemoryResponses.deleteMissing(path)

        // One commit for the exact key and every key beneath it, so a crash leaves the whole subtree
        // or none of it. `compact()` is deliberately not called: tombstones are the retention job's
        // business, and reclaiming them inline would make an interactive delete unpredictably slow.
        database.write(batch)
        return MemoryResponses.deleted(path)
    }

    // --- rename ---------------------------------------------------------------------------------

    private fun doRename(requestedOld: String, requestedNew: String): String {
        val from = MemoryPath.normalise(requestedOld) ?: return MemoryResponses.renameMissing(requestedOld)
        // A rejected destination answers with `rename`'s own occupied-destination string, for the
        // reason `create` answers with its own: a distinct message is a probe oracle.
        val to = MemoryPath.normalise(requestedNew) ?: return MemoryResponses.destinationExists(requestedNew)

        if (MemoryPath.isRoot(from)) return MemoryResponses.CANNOT_RENAME_ROOT
        if (MemoryPath.isRoot(to)) return MemoryResponses.destinationExists(to)
        if (to == from) return MemoryResponses.destinationExists(to)
        // Moving a subtree inside itself has no correct outcome — the source keys and the
        // destination keys would overlap — so it is refused rather than half-performed.
        if (to.startsWith("$from/")) return MemoryResponses.renameIntoOwnSubtree(from, to)

        val batch = WriteBatch()
        val now = System.currentTimeMillis()

        database.snapshot().use { snapshot ->
            // Source first, then destination. The order is visible when both are wrong, and naming
            // the *source* is the more useful of the two answers: a model that mistyped a path
            // learns which half to correct, where "destination already exists" would send it looking
            // for a file it never wrote. The SDK's reference implementation checks the other way
            // round; the specification's own error list is in this order.
            val source = database.get(key(from), snapshot)
            if (source == null) {
                // The source may still be a directory, which here means "some key has it as a
                // prefix" and nothing else — there are no directory records to look up.
                var populated = false
                scanSubtree(from, snapshot) { _, _ -> populated = true }
                if (!populated) return MemoryResponses.renameMissing(from)
            }

            if (database.get(key(to), snapshot) != null) return MemoryResponses.destinationExists(to)
            var destinationOccupied = false
            scanSubtree(to, snapshot) { _, _ -> destinationOccupied = true }
            if (destinationOccupied) return MemoryResponses.destinationExists(to)

            if (source != null) {
                stage(
                    batch = batch,
                    path = to,
                    content = MemoryDocument.content(source),
                    createdAt = MemoryDocument.createdAt(source),
                    updatedAt = now,
                    accessedAt = if (options.trackAccess) MemoryDocument.accessedAt(source) else null,
                )
                batch.delete(key(from))
            }

            scanSubtree(from, snapshot) { oldKey, document ->
                stage(
                    batch = batch,
                    path = to + pathOf(oldKey).substring(from.length),
                    content = MemoryDocument.content(document),
                    createdAt = MemoryDocument.createdAt(document),
                    updatedAt = now,
                    accessedAt = if (options.trackAccess) MemoryDocument.accessedAt(document) else null,
                )
                batch.delete(oldKey)
            }
        }

        // **The operation worth pointing at.** One commit carries every put and every delete, so a
        // crash mid-rename leaves the whole subtree at the old paths or the whole subtree at the new
        // ones — because the acknowledged prefix is a commit boundary. A filesystem cannot promise
        // this across a device boundary and does not promise it for a recursive move at all.
        database.write(batch)
        return MemoryResponses.renamed(from, to)
    }

    // --- store access ---------------------------------------------------------------------------

    private fun key(path: String): Key = Key.of(keyPrefix + path)

    private fun pathOf(key: Key): String = key.toByteArray().decodeToString().substring(keyPrefix.length)

    private fun stage(
        batch: WriteBatch,
        path: String,
        content: String,
        createdAt: Long,
        updatedAt: Long,
        accessedAt: Long?,
    ) {
        batch.put(key(path), MemoryDocument.encode(builder, path, content, createdAt, updatedAt, accessedAt))
    }

    private fun commitOne(path: String, content: String, createdAt: Long, updatedAt: Long) {
        // A batch even for one entry, so every command commits the same shape.
        val batch = WriteBatch()
        stage(batch, path, content, createdAt, updatedAt, if (options.trackAccess) updatedAt else null)
        database.write(batch)
    }

    private fun recordAccess(path: String, content: String, createdAt: Long, updatedAt: Long) {
        val batch = WriteBatch()
        stage(batch, path, content, createdAt, updatedAt, System.currentTimeMillis())
        database.write(batch)
    }

    /**
     * Walks every key under `path/`, in key order.
     *
     * The upper bound is the prefix with its last byte — always `/`, so never `0xFF` — incremented,
     * which is what turns an inclusive-at-both-ends range into a prefix scan. The bound alone is not
     * enough: `to` is inclusive, so a key spelled `/memories/a0` would slip in, and the prefix check
     * is what keeps it out. Without the trailing slash the prefix for `/memories/a` would also match
     * `/memories/ab`, which is the bug this shape exists to avoid.
     */
    private fun scanSubtree(path: String, snapshot: Snapshot?, action: (Key, Variant) -> Unit) {
        scanPrefix("$keyPrefix$path/", snapshot, action)
    }

    /** The same walk over the whole scope, rooted at `m:{scope}:`. */
    private fun scanScope(snapshot: Snapshot?, action: (Key, Variant) -> Unit) {
        scanPrefix(keyPrefix, snapshot, action)
    }

    private fun scanPrefix(prefix: String, snapshot: Snapshot?, action: (Key, Variant) -> Unit) {
        val fromBytes = prefix.toByteArray(Charsets.UTF_8)
        val toBytes = fromBytes.copyOf().also { it[it.size - 1]++ }
        database.scan(from = Key.of(fromBytes), to = Key.of(toBytes), snapshot = snapshot).use { cursor ->
            while (cursor.next()) {
                if (!cursor.key.toByteArray().decodeToString().startsWith(prefix)) continue
                action(cursor.key, cursor.document)
            }
        }
    }

    /** Path and size for every memory in the scope, from the index when there is one. */
    private fun forEachInScope(action: (String, Long) -> Unit) {
        if (options.listingIndex) {
            queryDescendants(MemoryPath.ROOT, snapshot = null, action = action)
        } else {
            scanScope(snapshot = null) { key, document -> action(pathOf(key), MemoryDocument.bytes(document)) }
        }
    }

    /**
     * The entries a directory `view` of [directory] renders, already filtered.
     *
     * Two ways in, one answer out. With [MemoryOptions.listingIndex] on this is a query over
     * `$.anc[*]` projecting `$.bytes`, and the key comes back free — so `documentsRead` is `0` and
     * the depth filter is arithmetic on a string. Without it, a subtree scan that opens every
     * document to read one field. An index changes how fast this runs, never what it returns.
     */
    private fun listingEntries(directory: String, snapshot: Snapshot): List<ListingEntry> {
        val entries = ArrayList<ListingEntry>()
        val collect: (String, Long) -> Unit = { descendant, bytes ->
            val segments = MemoryPath.segmentsBelow(directory, descendant)
            if (segments != null && !MemoryPath.isExcludedFromListing(segments)) {
                entries.add(ListingEntry(segments, bytes))
            }
        }
        if (options.listingIndex) {
            queryDescendants(directory, snapshot, collect)
        } else {
            scanSubtree(directory, snapshot) { key, document ->
                collect(pathOf(key), MemoryDocument.bytes(document))
            }
        }
        return entries
    }

    /** Every memory with [directory] among its ancestors, by way of the listing index. */
    private fun queryDescendants(directory: String, snapshot: Snapshot?, action: (String, Long) -> Unit) {
        database.query(MemoryQueries.descendants(keyPrefix, directory), snapshot).use { rows ->
            while (rows.next()) {
                action(pathOf(rows.key), rows.row[0]?.longValue() ?: 0L)
            }
        }
    }

    // --- plumbing -------------------------------------------------------------------------------

    private fun <T> withStore(action: () -> T): T = lock.withLock {
        check(!closed) { "this RaboshMemoryToolHandler is closed" }
        action()
    }

    /**
     * The lines `insert` counts, which are not the lines `view` renders.
     *
     * `view` splits on `\n` and keeps a trailing empty line; `insert` drops it, so a file ending in
     * a newline offers one fewer insertion point. That is the reference implementation's behaviour
     * in both commands, inconsistency included, and reconciling them would move every `insert_line`
     * the model has learned by one.
     */
    private fun linesForInsert(content: String): MutableList<String> =
        if (content.isEmpty()) ArrayList() else content.removeSuffix("\n").split("\n").toMutableList()

    private fun clampIndex(value: Long, size: Int): Int = value.coerceIn(0L, size.toLong()).toInt()

    /** Occurrences of [needle] in [haystack] counted **without** overlaps, as the reference does. */
    private fun nonOverlappingCount(haystack: String, needle: String): Int {
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** The 1-based line each occurrence of [needle] *starts* on, counting overlaps. */
    private fun occurrenceLines(haystack: String, needle: String): List<Int> {
        val lines = ArrayList<Int>()
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            lines.add(haystack.take(at).count { it == '\n' } + 1)
            at = haystack.indexOf(needle, at + 1)
        }
        return lines
    }

    public companion object {
        /**
         * Opens a store at [directory] and returns a handler that owns it.
         *
         * The convenience for a host whose only use for rabosh is this handler. `close()` then
         * closes the store, which matters more than it sounds: an unclosed store keeps the directory
         * lock and every mapping, and on Windows a mapped file cannot be deleted at all.
         *
         * @throws app.oreshkov.rabosh.core.StoreLockedException if another process holds
         *   [directory]. Catch it specifically and read its `holder` — rabosh's `INTEGRATION.md`
         *   says why deleting the lock file is the wrong reflex.
         */
        @JvmStatic
        @JvmOverloads
        public fun open(
            directory: Path,
            options: MemoryOptions = MemoryOptions.DEFAULT,
            storeOptions: RaboshOptions = RaboshOptions.DEFAULT,
        ): RaboshMemoryToolHandler {
            val database = Rabosh.open(directory, storeOptions)
            return try {
                RaboshMemoryToolHandler(database, options, ownsDatabase = true)
            } catch (failure: Throwable) {
                try {
                    database.close()
                } catch (secondary: Throwable) {
                    failure.addSuppressed(secondary)
                }
                throw failure
            }
        }

        /**
         * A human-readable size, reproducing the SDK reference implementation's `_format_file_size`
         * exactly — `0B`, then `B`/`K`/`M`/`G`, with one decimal place unless the value is whole.
         *
         * Rounded half-to-even rather than half-up, because Python's `f"{x:.1f}"` is, and `1.25K`
         * appearing as `1.2K` in one implementation and `1.3K` in another is the kind of difference
         * that is invisible until somebody diffs two listings.
         */
        internal fun humanSize(bytes: Long): String {
            if (bytes <= 0L) return "0B"
            val units = arrayOf("B", "K", "M", "G")
            val index = minOf((63 - bytes.countLeadingZeroBits()) / 10, units.size - 1)
            val scaled = bytes.toDouble() / (1L shl (index * 10))
            return if (scaled == floor(scaled)) {
                "${scaled.toLong()}${units[index]}"
            } else {
                BigDecimal(scaled).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + units[index]
            }
        }
    }
}

/** What [RaboshMemoryToolHandler.usage] reports: how many memories, and how many bytes of content. */
public class MemoryUsage internal constructor(
    /** How many memories the scope holds. */
    public val memories: Long,
    /** How many bytes of content between them, excluding keys and document overhead. */
    public val bytes: Long,
) {
    override fun toString(): String = "MemoryUsage(memories=$memories, bytes=$bytes)"
}

/** One memory as a directory listing sees it: its segments below the viewed path, and its size. */
private class ListingEntry(val segments: List<String>, val bytes: Long)

/**
 * The listing, as a tree, so that the emitted order is the reference implementation's.
 *
 * That order is name-sorted within each level and depth-first — a directory is emitted and then
 * immediately descended into — which is not the same as sorting the full paths: a directory `p` and
 * a file `p.md` come out `p/`, `p/…`, `p.md` here and `p.md`, `p/`, `p/…` from a path sort, because
 * `.` sorts before `/`. Cheap to get right, and invisible until two listings are compared.
 */
private class ListingNode {
    val children = LinkedHashMap<String, ListingNode>()
    private var ownBytes: Long = 0
    private var descendantBytes: Long = 0
    private var isFile = false

    fun add(segments: List<String>, bytes: Long) {
        if (segments.isEmpty()) {
            isFile = true
            ownBytes = bytes
            return
        }
        // Accumulated on proper ancestors only. A document at `/memories/a` and documents under
        // `/memories/a/` are separate entries in the listing, so counting the document's bytes into
        // the directory's total as well would report them twice.
        descendantBytes += bytes
        children.getOrPut(segments.first()) { ListingNode() }.add(segments.subList(1, segments.size), bytes)
    }

    /**
     * Appends this node's children to [out] — relative depth 1 and 2 only.
     *
     * Everything deeper is rolled up into its depth-2 ancestor, which is emitted once with the sum
     * of its descendants. That is the only honest size a store with no directory records can report:
     * there is no inode to `stat`, and a directory exists here exactly when some key has it as a
     * prefix.
     */
    fun emit(prefix: String, depth: Int, out: StringBuilder) {
        for (name in children.keys.sorted()) {
            val child = children.getValue(name)
            val childPath = "$prefix/$name"
            if (child.isFile) {
                out.append('\n')
                    .append(RaboshMemoryToolHandler.humanSize(child.ownBytes))
                    .append('\t').append(childPath)
            }
            if (child.children.isNotEmpty()) {
                out.append('\n')
                    .append(RaboshMemoryToolHandler.humanSize(child.descendantBytes))
                    .append('\t').append(childPath).append('/')
                if (depth < 2) child.emit(childPath, depth + 1, out)
            }
        }
    }
}
