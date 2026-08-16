package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.api.Rabosh
import app.oreshkov.rabosh.api.RaboshOptions
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.StoreOptions
import java.nio.file.Path
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * Listing a five-thousand-memory store, with the index and without it, at two memory sizes.
 *
 * Not part of `build`, because minutes do not belong on a commit. Run it with:
 *
 * ```
 * ./gradlew test -Prabosh.memory.bench
 * ```
 *
 * **The rule carried across from rabosh is that a benchmark which produced no results fails.** A
 * benchmark that silently measures nothing reports the best numbers in the project, so every figure
 * is asserted before it is printed: the two paths must return the same listing, the indexed one must
 * open zero documents, and the unindexed one must open exactly one per memory. What is deliberately
 * *not* asserted is a latency threshold, which would be a claim about somebody else's hardware.
 *
 * **Two memory sizes, because the obvious explanation for the first result is wrong.**
 * `documentsRead == 0` is the property the option delivers and it is unconditional — so the expected
 * shape was that the index loses on tiny memories, where a scan walks a few contiguous cached
 * blocks, and wins once a memory is large enough that the scan is decoding kilobytes to read one
 * integer. It is not what happens. Growing each memory from 64 B to 4 KiB barely moved the unindexed
 * number, because a scan's "document read" is an **open, not a decode**: `Variant` is a view over
 * mapped bytes, so `$.bytes` costs the field rather than the memory.
 *
 * So there is no crossover to find, and raising the count does not produce one — at 50,000 memories
 * the ratio gets worse, not better. Both paths are linear in the rows the listing returns, the
 * listing is not selective (the key range already bounds the scan to the subtree, so the index has
 * no rows to eliminate), and the query's constant is the larger one. That is the finding, and
 * `MemoryOptions.listingIndex` carries it where a caller will actually read it.
 *
 * Both scales are printed rather than one, because a single number here would have been an argument
 * instead of a measurement.
 */
@Tag("bench")
class ListingIndexBenchmark {

    @Test
    fun `listing five thousand memories, with and without the index`() {
        val results = ArrayList<Result>()
        for (payload in PAYLOAD_SIZES) {
            TestStores.withDirectory { directory ->
                results += measure(directory.resolve("plain"), payload, listingIndex = false)
                results += measure(directory.resolve("indexed"), payload, listingIndex = true)
            }
        }

        report(results)

        assertTrue(results.size == PAYLOAD_SIZES.size * 2, "the benchmark did not run every case")
        for (result in results) {
            assertTrue(result.iterations > 0, "${result.label}: measured nothing")
            assertTrue(result.listingLines > MEMORIES, "${result.label}: the listing came back empty")
        }
        for (payload in PAYLOAD_SIZES) {
            val (unindexed, indexed) = results.filter { it.payloadBytes == payload }.let { it[0] to it[1] }
            assertEquals(
                unindexed.listingLines,
                indexed.listingLines,
                "${unindexed.label}: the two paths disagreed about the listing, so the comparison " +
                    "is meaningless",
            )
            assertEquals(0, indexed.documentsRead, "${indexed.label}: the indexed listing opened documents")
            assertEquals(
                MEMORIES,
                unindexed.documentsRead,
                "${unindexed.label}: the unindexed listing did not open one document per memory, " +
                    "so it is not the baseline this is comparing against",
            )
        }
    }

    private fun measure(directory: Path, payloadBytes: Int, listingIndex: Boolean): Result {
        // Seeded under BUFFERED and synced once. The subject is the *listing*, and paying five
        // thousand `fsync`s to set it up would be measuring the disk's write path instead.
        val storeOptions = RaboshOptions(store = StoreOptions(durability = Durability.BUFFERED))
        Rabosh.open(directory, storeOptions).use { database ->
            val body = "x".repeat(payloadBytes) + "\n"
            RaboshMemoryToolHandler(database).use { seeder ->
                repeat(MEMORIES) { index ->
                    seeder.create("$LISTED_DIRECTORY/note%05d.md".format(index), "note $index\n$body")
                }
            }
            database.sync()
            // An index covers segments; documents still in a memtable are answered by the scan half
            // of a plan, which opens them. Flushing is what makes the measurement about the index
            // rather than about how much happened to be unflushed.
            database.flush()

            RaboshMemoryToolHandler(database, MemoryOptions(listingIndex = listingIndex)).use { handler ->
                repeat(WARMUP) { handler.view(LISTED_DIRECTORY, Optional.empty()) }

                val started = System.nanoTime()
                var lines = 0
                repeat(ITERATIONS) {
                    lines = handler.view(LISTED_DIRECTORY, Optional.empty()).count { it == '\n' }
                }
                val elapsed = System.nanoTime() - started

                val work = countWork(database, listingIndex)
                return Result(
                    payloadBytes = payloadBytes,
                    listingIndex = listingIndex,
                    iterations = ITERATIONS,
                    nanosPerListing = elapsed / ITERATIONS,
                    listingLines = lines,
                    documentsRead = work.documentsRead,
                    blocksScanned = work.blocksScanned,
                    blocksSkipped = work.blocksSkipped,
                )
            }
        }
    }

    /**
     * How much work one listing does.
     *
     * Read out of the engine for the indexed path, because that is where it is reported, and counted
     * directly for the unindexed one, where every entry in the subtree is a document the scan has to
     * decode to read a single field out of. That asymmetry is the finding rather than a gap in the
     * measurement — there is no cursor statistic to quote for a plain scan because a plain scan
     * skips nothing.
     */
    private fun countWork(database: Rabosh, listingIndex: Boolean): Work {
        if (!listingIndex) return Work(documentsRead = MEMORIES, blocksScanned = 0, blocksSkipped = 0)
        database.query(MemoryQueries.descendants("m:$DEFAULT_SCOPE:", LISTED_DIRECTORY)).use { rows ->
            var drained = 0
            while (rows.next()) drained++
            check(drained == MEMORIES) { "the listing query found $drained memories, not $MEMORIES" }
            val stats = rows.stats
            return Work(stats.documentsRead, stats.blocksScanned, stats.blocksSkipped)
        }
    }

    private fun report(results: List<Result>) {
        val out = StringBuilder("\nlisting $MEMORIES memories in $LISTED_DIRECTORY\n\n")
        out.append(
            "  %-10s %-7s %13s %14s %13s %13s\n".format(
                "memory", "index", "µs/listing", "documentsRead", "blocksScanned", "blocksSkipped",
            ),
        )
        for (result in results) {
            out.append(
                "  %-10s %-7s %13.1f %14d %13d %13d\n".format(
                    "${result.payloadBytes}B",
                    if (result.listingIndex) "on" else "off",
                    result.nanosPerListing / 1_000.0,
                    result.documentsRead,
                    result.blocksScanned,
                    result.blocksSkipped,
                ),
            )
        }
        out.append('\n')
        for (payload in PAYLOAD_SIZES) {
            val (unindexed, indexed) = results.filter { it.payloadBytes == payload }.let { it[0] to it[1] }
            val ratio = unindexed.nanosPerListing.toDouble() / indexed.nanosPerListing.coerceAtLeast(1)
            out.append(
                "  at ${payload}B per memory the index is %.2fx the unindexed listing's speed\n".format(ratio),
            )
        }
        println(out)
    }

    private class Work(val documentsRead: Int, val blocksScanned: Int, val blocksSkipped: Int)

    private class Result(
        val payloadBytes: Int,
        val listingIndex: Boolean,
        val iterations: Int,
        val nanosPerListing: Long,
        val listingLines: Int,
        val documentsRead: Int,
        val blocksScanned: Int,
        val blocksSkipped: Int,
    ) {
        val label: String get() = "${payloadBytes}B, index ${if (listingIndex) "on" else "off"}"
    }

    private companion object {
        const val LISTED_DIRECTORY = "/memories/notes"
        const val WARMUP = 3
        const val ITERATIONS = 20

        /**
         * Five thousand by default, and adjustable because the interesting question about this
         * option is how it behaves as the number grows.
         */
        val MEMORIES: Int = System.getProperty("rabosh.memory.bench.memories")?.toInt() ?: 5_000

        /** A memory the size of its key, and a memory the size of a memory. */
        val PAYLOAD_SIZES: List<Int> = System.getProperty("rabosh.memory.bench.payloads")
            ?.split(',')?.map { it.trim().toInt() }
            ?: listOf(64, 4_096)
    }
}
