package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.query.Projection
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.path

/**
 * The query a directory listing is when [MemoryOptions.listingIndex] is on.
 *
 * Extracted so that `ListingIndexTest` can assert `documentsRead == 0` against **the query the
 * handler actually runs** rather than a restatement of it. A test that rebuilt the query would be
 * measuring its own copy, and the day the two drifted the claim would still be green.
 */
internal object MemoryQueries {

    /**
     * Every memory under [directory], in the scope [keyPrefix] names, projecting its size.
     *
     * Three pieces, each load-bearing:
     *
     * - `$.anc[*] eq directory` is the subtree. `anc` holds every ancestor of a path, so one term
     *   answers "everything below here" without a scan and without knowing the depth. Reporting
     *   presence above 100% for this path is expected and correct — a memory three directories deep
     *   really does occur three times.
     * - The **key range** is not decoration. `anc` holds paths that carry no scope, so without it a
     *   store with two tenants would answer one tenant's listing with the other's memories.
     * - `Projection.of("$.bytes")` is what makes `documentsRead == 0` reachable: with a shredded
     *   column over that path the size is read from the sidecar, and the key comes back free, so a
     *   listing opens no document at all.
     */
    fun descendants(keyPrefix: String, directory: String): Query {
        val from = keyPrefix.toByteArray(Charsets.UTF_8)
        val to = from.copyOf().also { it[it.size - 1]++ }
        return Query.where(path(MemoryDocument.ANCESTORS_PATH) eq directory)
            .range(Key.of(from), Key.of(to))
            .project(Projection.of(MemoryDocument.BYTES_PATH))
    }
}
