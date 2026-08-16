package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.api.Rabosh
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The listing index: same answer, no documents opened, and built over data already on disk.
 *
 * Three claims, and they are separable. That the answer does not change is what makes the option
 * safe to turn on. That `documentsRead` is `0` is what makes it worth turning on. That it can be
 * turned on *afterwards* is the engine's headline claim doing real work rather than appearing in a
 * sample.
 */
class ListingIndexTest {

    private fun noRange() = Optional.empty<List<Long>>()

    @Test
    fun `an index changes how fast a listing runs, never what it returns`() {
        val memories = (1..40).associate { index ->
            val path = when (index % 4) {
                0 -> "/memories/top$index.md"
                1 -> "/memories/projects/p$index.md"
                2 -> "/memories/projects/kotlin/k$index.md"
                else -> "/memories/projects/kotlin/deep/d$index.md"
            }
            path to "content for $index\n".repeat(index)
        }

        val withoutIndex = TestStores.withHandler { handler, _ ->
            memories.forEach { (path, text) -> handler.create(path, text) }
            listOf("/memories", "/memories/projects", "/memories/projects/kotlin")
                .associateWith { handler.view(it, noRange()) }
        }

        val withIndex = TestStores.withHandler(MemoryOptions(listingIndex = true)) { handler, _ ->
            memories.forEach { (path, text) -> handler.create(path, text) }
            listOf("/memories", "/memories/projects", "/memories/projects/kotlin")
                .associateWith { handler.view(it, noRange()) }
        }

        assertEquals(withoutIndex, withIndex)
        assertTrue(withoutIndex.getValue("/memories").lineSequence().count() > 3)
    }

    /**
     * The number the option exists for, measured against the query the handler runs.
     *
     * The store is flushed first, deliberately: an index covers segments, and documents still in a
     * memtable are answered by the scan half of a plan, which does open them. That is not a caveat
     * being dodged — it is what "an index changes the speed and not the answer" means in an engine
     * whose index is a set of per-segment sidecars.
     */
    @Test
    fun `a directory listing reads zero documents`() = TestStores.withDirectory { directory ->
        Rabosh.open(directory).use { database ->
            RaboshMemoryToolHandler(database, MemoryOptions(listingIndex = true)).use { handler ->
                repeat(200) { index -> handler.create("/memories/projects/m$index.md", "body $index\n") }
            }
            database.flush()

            val query = MemoryQueries.descendants("m:$DEFAULT_SCOPE:", "/memories/projects")
            database.query(query).use { rows ->
                var counted = 0
                while (rows.next()) counted++
                assertEquals(200, counted, "the listing query did not find the memories")
                assertEquals(
                    0,
                    rows.stats.documentsRead,
                    "the listing opened documents; the shredded \$.bytes column did not answer it",
                )
            }
        }
    }

    /**
     * Turning the option on for a store that already has data is a sidecar build, not a migration.
     *
     * No document is rewritten and no version is bumped — which is why `$.anc` is written on every
     * document from the very first commit whether or not anything reads it. Adding it later is the
     * one thing that *would* have meant rewriting every memory.
     */
    @Test
    fun `the index can be built retroactively over memories written without it`() =
        TestStores.withDirectory { directory ->
            Rabosh.open(directory).use { database ->
                RaboshMemoryToolHandler(database).use { plain ->
                    repeat(50) { index -> plain.create("/memories/notes/n$index.md", "note $index\n") }
                }
            }

            // A second process, a second day, a changed option — and nothing to migrate.
            Rabosh.open(directory).use { database ->
                RaboshMemoryToolHandler(database, MemoryOptions(listingIndex = true)).use { indexed ->
                    val listing = indexed.view("/memories/notes", noRange())
                    assertEquals(51, listing.lineSequence().count() - 1)
                    assertTrue("/memories/notes/n7.md" in listing)
                }
            }
        }
}
