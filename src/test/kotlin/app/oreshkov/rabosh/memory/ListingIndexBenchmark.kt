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
 * **Two memory sizes, because size and count pull in opposite directions.** Size helps the index: a
 * scan opens a document per entry and the index opens none, so growing each memory from 64 B to
 * 4 KiB roughly doubles the unindexed listing while moving the indexed one much less. Count hurts
 * it: at 4 KiB the ratio falls from parity at 5,000 memories to about 0.72x at 50,000. Over the
 * range measured the two never combine into a win — the best cell in the table is a tie — which is
 * why the default is off, and `MemoryOptions.listingIndex` carries the table where a caller will
 * read it.
 *
 * **The earlier version of this benchmark got that wrong, and the way it got it wrong is the reason
 * for the warm-up constants below.** It timed three warm-up listings and one run of twenty, which
 * measures code the JIT has not finished compiling. The 64 B unindexed baseline came out about twice
 * as slow as it really is, which flattered the index at small sizes, flattened the size axis to
 * nothing, and supported a tidy "there is no crossover to find" conclusion that longer runs do not
 * reproduce. A benchmark that is not warm is not measuring the program.
 *
 * **Stop the Gradle daemons before believing a run: `./gradlew --stop`.** Successive invocations
 * leave idle JVMs resident — six of them, one at 948 MB, after an afternoon of this — and the
 * listing reads through mapped segments, so what they cost is page cache rather than CPU. A run
 * taken beside them measured the 64 B baseline at 6995 µs against 1746 µs from a clean daemon, four
 * times slow, with both cases straddling parity and a spread twice as wide. The failure is loud
 * rather than silent, which is the only reason it is a note and not a guard: a run whose ranges are
 * wide or whose cases straddle parity is a run taken on a busy machine, and the fix is to stop the
 * daemons and take it again.
 *
 * **A range within one JVM understates the uncertainty**, so the report says so. Two processes
 * disagree by more than the spread inside either one — the 5,000 × 4 KiB case came out at 1.10x and
 * then 0.97x — so a cell near parity has to be repeated in a fresh JVM before it means anything, and
 * a figure quoted anywhere else in this repository is per JVM rather than averaged across them.
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

                // Timed in separate runs rather than as one long loop, so the spread is visible. A
                // single number cannot say whether 0.99x means "just under parity" or "somewhere
                // around parity, ask again tomorrow", and for this option that is the whole question.
                var lines = 0
                val perRun = LongArray(RUNS)
                for (run in 0 until RUNS) {
                    val started = System.nanoTime()
                    repeat(ITERATIONS) {
                        lines = handler.view(LISTED_DIRECTORY, Optional.empty()).count { it == '\n' }
                    }
                    perRun[run] = (System.nanoTime() - started) / ITERATIONS
                }
                perRun.sort()

                val work = countWork(database, listingIndex)
                return Result(
                    payloadBytes = payloadBytes,
                    listingIndex = listingIndex,
                    iterations = ITERATIONS,
                    // The median, not the mean: one run that collided with a GC pause or the page
                    // cache should move the reported figure by nothing at all.
                    nanosPerListing = perRun[RUNS / 2],
                    fastestNanos = perRun.first(),
                    slowestNanos = perRun.last(),
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
        val out = StringBuilder("\nlisting $MEMORIES memories in $LISTED_DIRECTORY\n")
        out.append("$WARMUP warm-up listings, then $RUNS timed runs of $ITERATIONS\n")
        // Said out loud because the ranges below look tighter than the truth. Two runs of this
        // benchmark in two JVMs disagree by more than the spread within either one, so a range here
        // is a lower bound on the uncertainty and a case sitting near parity needs repeating in a
        // fresh process before it is believed.
        out.append("ranges are within one process; a fresh JVM moves them further\n\n")
        out.append(
            "  %-10s %-7s %13s %17s %14s %13s %13s\n".format(
                "memory", "index", "µs/listing", "range", "documentsRead", "blocksScanned",
                "blocksSkipped",
            ),
        )
        for (result in results) {
            out.append(
                "  %-10s %-7s %13.1f %17s %14d %13d %13d\n".format(
                    "${result.payloadBytes}B",
                    if (result.listingIndex) "on" else "off",
                    result.nanosPerListing / 1_000.0,
                    "%.1f-%.1f".format(result.fastestNanos / 1_000.0, result.slowestNanos / 1_000.0),
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
            // The widest ratio the timings admit: the unindexed path at its best against the indexed
            // path at its worst, and the reverse. Quoting only the median would hide a case that
            // straddles parity, which is the one case where the default is arguable.
            val lowest = unindexed.fastestNanos.toDouble() / indexed.slowestNanos.coerceAtLeast(1)
            val highest = unindexed.slowestNanos.toDouble() / indexed.fastestNanos.coerceAtLeast(1)
            out.append(
                "  at ${payload}B per memory the index is %.2fx the unindexed listing's speed (%.2fx-%.2fx)\n"
                    .format(ratio, lowest, highest),
            )
            if (lowest < 1.0 && highest > 1.0) {
                out.append("    ^ straddles parity: this case is not evidence either way\n")
            }
        }
        println(out)
    }

    private class Work(val documentsRead: Int, val blocksScanned: Int, val blocksSkipped: Int)

    private class Result(
        val payloadBytes: Int,
        val listingIndex: Boolean,
        val iterations: Int,
        val nanosPerListing: Long,
        val fastestNanos: Long,
        val slowestNanos: Long,
        val listingLines: Int,
        val documentsRead: Int,
        val blocksScanned: Int,
        val blocksSkipped: Int,
    ) {
        val label: String get() = "${payloadBytes}B, index ${if (listingIndex) "on" else "off"}"
    }

    private companion object {
        const val LISTED_DIRECTORY = "/memories/notes"
        /**
         * Enough warm-up to be measuring compiled code, and enough timed runs to see the spread.
         *
         * The first version of this used three warm-up listings and one timed run of twenty. That is
         * not steady state — the query path is the larger body of code and is still being compiled
         * while it is being timed — and the symptom was a ratio that moved by a tenth between
         * otherwise identical runs, which is the difference between "slower" and "about the same" for
         * the case this benchmark exists to decide.
         */
        val WARMUP: Int = System.getProperty("rabosh.memory.bench.warmup")?.toInt() ?: 50
        val ITERATIONS: Int = System.getProperty("rabosh.memory.bench.iterations")?.toInt() ?: 200

        /** Timed runs per case, reported as a median and a range. Odd, so the median is an element. */
        val RUNS: Int = System.getProperty("rabosh.memory.bench.runs")?.toInt() ?: 9

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
