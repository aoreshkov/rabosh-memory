package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.api.Rabosh
import java.nio.file.Files
import java.time.Instant
import java.util.Optional
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The response strings, spelled out here exactly as the specification writes them.
 *
 * The differential suite proves the handler and the oracle agree; this proves they agree with the
 * *specification* rather than with each other. Both are needed, and the second is the shorter one to
 * read when somebody wonders whether a prefix is a typo.
 */
class RaboshMemoryToolHandlerTest {

    private fun noRange() = Optional.empty<List<Long>>()

    // --- view ---------------------------------------------------------------------------------

    @Test
    fun `view of an empty root is a listing, not an error`() = TestStores.withHandler { handler, _ ->
        assertEquals(
            "Here're the files and directories up to 2 levels deep in /memories, " +
                "excluding hidden items and node_modules:\n" +
                "0B\t/memories",
            handler.view("/memories", noRange()),
        )
    }

    @Test
    fun `view of a directory lists two levels and rolls the rest up`() =
        TestStores.withHandler { handler, _ ->
            handler.create("/memories/a.md", "a".repeat(1536))
            handler.create("/memories/p/x.md", "x".repeat(512))
            handler.create("/memories/p/q/deep.md", "d".repeat(256))
            handler.create("/memories/p/q/r/deeper.md", "e".repeat(256))
            handler.create("/memories/.hidden.md", "invisible")
            handler.create("/memories/node_modules/pkg.md", "invisible")

            assertEquals(
                "Here're the files and directories up to 2 levels deep in /memories, " +
                    "excluding hidden items and node_modules:\n" +
                    "2.5K\t/memories\n" +
                    "1.5K\t/memories/a.md\n" +
                    "1K\t/memories/p/\n" +
                    "512B\t/memories/p/q/\n" +
                    "512B\t/memories/p/x.md",
                handler.view("/memories", noRange()),
            )
        }

    @Test
    fun `view of a file numbers its lines in six columns`() = TestStores.withHandler { handler, _ ->
        handler.create("/memories/notes.txt", "Hello World\nThis is line two\n")
        assertEquals(
            "Here's the content of /memories/notes.txt with line numbers:\n" +
                "     1\tHello World\n" +
                "     2\tThis is line two\n" +
                "     3\t",
            handler.view("/memories/notes.txt", noRange()),
        )
    }

    @Test
    fun `view_range selects lines and minus one means to the end`() =
        TestStores.withHandler { handler, _ ->
            handler.create("/memories/n.txt", "one\ntwo\nthree\nfour")
            assertEquals(
                "Here's the content of /memories/n.txt with line numbers:\n" +
                    "     2\ttwo\n" +
                    "     3\tthree",
                handler.view("/memories/n.txt", Optional.of(listOf(2L, 3L))),
            )
            assertEquals(
                "Here's the content of /memories/n.txt with line numbers:\n" +
                    "     3\tthree\n" +
                    "     4\tfour",
                handler.view("/memories/n.txt", Optional.of(listOf(3L, -1L))),
            )
            // Out of range clamps rather than failing, as the reference implementations do.
            assertEquals(
                "Here's the content of /memories/n.txt with line numbers:\n" +
                    "     1\tone\n     2\ttwo\n     3\tthree\n     4\tfour",
                handler.view("/memories/n.txt", Optional.of(listOf(-5L, 900L))),
            )
        }

    @Test
    fun `a long view is cut at a line boundary and says how to read the rest`() {
        TestStores.withHandler(MemoryOptions(viewMaxChars = 60)) { handler, _ ->
            handler.create("/memories/long.txt", (1..10).joinToString("\n") { "line $it" })
            val view = handler.view("/memories/long.txt", noRange())
            assertTrue(view.startsWith("Here's the content of /memories/long.txt with line numbers:\n"))
            assertTrue(view.endsWith("[Truncated: showing lines 1 to 4 of 10. Use view_range [5, -1] to read the rest.]"))
            assertTrue("     4\tline 4" in view)
            assertFalse("     5\tline 5" in view)
        }
    }

    @Test
    fun `a missing path has no Error prefix, uniquely`() = TestStores.withHandler { handler, _ ->
        assertEquals(
            "The path /memories/absent.md does not exist. Please provide a valid path.",
            handler.view("/memories/absent.md", noRange()),
        )
    }

    // --- create -------------------------------------------------------------------------------

    @Test
    fun `create refuses to overwrite`() = TestStores.withHandler { handler, _ ->
        assertEquals("File created successfully at: /memories/a.md", handler.create("/memories/a.md", "first"))
        assertEquals("Error: File /memories/a.md already exists", handler.create("/memories/a.md", "second"))
        assertEquals(
            "Here's the content of /memories/a.md with line numbers:\n     1\tfirst",
            handler.view("/memories/a.md", noRange()),
        )
    }

    @Test
    fun `createOverwrites makes it overwrite, keeping the original createdAt`() {
        TestStores.withHandler(MemoryOptions(createOverwrites = true)) { handler, database ->
            handler.create("/memories/a.md", "first")
            handler.create("/memories/a.md", "second")
            assertEquals(mapOf("/memories/a.md" to "second"), TestStores.dump(database))
        }
    }

    @Test
    fun `the size cap is enforced on every write`() {
        TestStores.withHandler(MemoryOptions(maxMemoryBytes = 32)) { handler, _ ->
            assertEquals(
                "Error: File /memories/big.md exceeds the maximum memory file size of 32 bytes",
                handler.create("/memories/big.md", "x".repeat(33)),
            )
            handler.create("/memories/small.md", "unique" + "x".repeat(24))
            assertEquals(
                "Error: File /memories/small.md exceeds the maximum memory file size of 32 bytes",
                handler.strReplace("/memories/small.md", "unique", "y".repeat(10)),
            )
            assertEquals(
                "Error: File /memories/small.md exceeds the maximum memory file size of 32 bytes",
                handler.insert("/memories/small.md", 0L, "yyyy"),
            )
        }
    }

    // --- str_replace --------------------------------------------------------------------------

    @Test
    fun `str_replace returns a snippet of the change`() = TestStores.withHandler { handler, _ ->
        handler.create("/memories/p.txt", "one\ntwo\nthree\nfour\nfive\nsix\n")
        assertEquals(
            "The memory file has been edited. Here is the snippet showing the change (with line numbers):\n" +
                "     2\ttwo\n" +
                "     3\tthree\n" +
                "     4\tFOUR\n" +
                "     5\tfive\n" +
                "     6\tsix",
            handler.strReplace("/memories/p.txt", "four", "FOUR"),
        )
    }

    @Test
    fun `str_replace refuses an ambiguous edit and names the lines`() =
        TestStores.withHandler { handler, _ ->
            handler.create("/memories/p.txt", "dup\nmiddle\ndup\n")
            assertEquals(
                "No replacement was performed. Multiple occurrences of old_str `dup` in lines: 1, 3. " +
                    "Please ensure it is unique",
                handler.strReplace("/memories/p.txt", "dup", "x"),
            )
            // And nothing was written: this is the command where a naive implementation silently
            // edits the wrong occurrence.
            assertEquals(
                "Here's the content of /memories/p.txt with line numbers:\n" +
                    "     1\tdup\n     2\tmiddle\n     3\tdup\n     4\t",
                handler.view("/memories/p.txt", noRange()),
            )
        }

    @Test
    fun `str_replace with text that is not there says so verbatim`() =
        TestStores.withHandler { handler, _ ->
            handler.create("/memories/p.txt", "alpha\n")
            assertEquals(
                "No replacement was performed, old_str `beta` did not appear verbatim in /memories/p.txt.",
                handler.strReplace("/memories/p.txt", "beta", "x"),
            )
        }

    @Test
    fun `str_replace on a missing path keeps its Error prefix and its trailing sentence`() =
        TestStores.withHandler { handler, _ ->
            assertEquals(
                "Error: The path /memories/absent.md does not exist. Please provide a valid path.",
                handler.strReplace("/memories/absent.md", "a", "b"),
            )
        }

    @Test
    fun `an omitted new_str deletes the old one`() = TestStores.withHandler { handler, database ->
        handler.create("/memories/p.txt", "keep this\n")
        handler.strReplace("/memories/p.txt", "this", "")
        assertEquals(mapOf("/memories/p.txt" to "keep \n"), TestStores.dump(database))
    }

    // --- insert -------------------------------------------------------------------------------

    @Test
    fun `insert places text after the given line, zero meaning before the first`() =
        TestStores.withHandler { handler, database ->
            handler.create("/memories/todo.txt", "first\nsecond\n")
            assertEquals("The file /memories/todo.txt has been edited.", handler.insert("/memories/todo.txt", 1L, "middle\n"))
            assertEquals(mapOf("/memories/todo.txt" to "first\nmiddle\nsecond\n"), TestStores.dump(database))

            handler.insert("/memories/todo.txt", 0L, "zeroth")
            assertEquals(
                mapOf("/memories/todo.txt" to "zeroth\nfirst\nmiddle\nsecond\n"),
                TestStores.dump(database),
            )
        }

    @Test
    fun `insert out of range names the range, and a missing path stops at Error`() =
        TestStores.withHandler { handler, _ ->
            handler.create("/memories/todo.txt", "first\nsecond\n")
            assertEquals(
                "Error: Invalid `insert_line` parameter: 9. " +
                    "It should be within the range of lines of the file: [0, 2]",
                handler.insert("/memories/todo.txt", 9L, "x"),
            )
            assertEquals(
                "Error: The path /memories/absent.md does not exist",
                handler.insert("/memories/absent.md", 0L, "x"),
            )
        }

    // --- delete and rename ---------------------------------------------------------------------

    @Test
    fun `delete takes the subtree with it, in one commit`() = TestStores.withHandler { handler, database ->
        handler.create("/memories/p/x.md", "x")
        handler.create("/memories/p/q/deep.md", "d")
        handler.create("/memories/keep.md", "k")

        assertEquals("Successfully deleted /memories/p", handler.delete("/memories/p"))
        assertEquals(mapOf("/memories/keep.md" to "k"), TestStores.dump(database))
        assertEquals("Error: The path /memories/p does not exist", handler.delete("/memories/p"))
    }

    @Test
    fun `the root cannot be deleted or renamed`() = TestStores.withHandler { handler, _ ->
        assertEquals("Error: Cannot delete the /memories directory itself", handler.delete("/memories"))
        assertEquals("Error: Cannot rename the /memories directory itself", handler.rename("/memories", "/memories/x"))
    }

    @Test
    fun `rename moves a whole subtree and refuses an occupied destination`() =
        TestStores.withHandler { handler, database ->
            handler.create("/memories/p/x.md", "x")
            handler.create("/memories/p/q/deep.md", "d")
            handler.create("/memories/taken/z.md", "z")

            assertEquals("Successfully renamed /memories/p to /memories/moved", handler.rename("/memories/p", "/memories/moved"))
            assertEquals(
                mapOf(
                    "/memories/moved/q/deep.md" to "d",
                    "/memories/moved/x.md" to "x",
                    "/memories/taken/z.md" to "z",
                ),
                TestStores.dump(database),
            )

            assertEquals(
                "Error: The destination /memories/taken already exists",
                handler.rename("/memories/moved", "/memories/taken"),
            )
            assertEquals(
                "Error: The path /memories/absent does not exist",
                handler.rename("/memories/absent", "/memories/elsewhere"),
            )
            assertEquals(
                "Error: Cannot rename /memories/moved to /memories/moved/inner, which is inside it",
                handler.rename("/memories/moved", "/memories/moved/inner"),
            )
        }

    // --- rejected paths ------------------------------------------------------------------------

    @Test
    fun `a rejected path answers with the command's own string, never a distinct one`() =
        TestStores.withHandler { handler, database ->
            val traversal = "/memories/../../secrets.env"
            assertEquals(
                "The path $traversal does not exist. Please provide a valid path.",
                handler.view(traversal, noRange()),
            )
            assertEquals("Error: File $traversal already exists", handler.create(traversal, "x"))
            assertEquals(
                "Error: The path $traversal does not exist. Please provide a valid path.",
                handler.strReplace(traversal, "a", "b"),
            )
            assertEquals("Error: The path $traversal does not exist", handler.insert(traversal, 0L, "x"))
            assertEquals("Error: The path $traversal does not exist", handler.delete(traversal))
            assertEquals("Error: The path $traversal does not exist", handler.rename(traversal, "/memories/a.md"))
            assertEquals(
                "Error: The destination $traversal already exists",
                handler.rename("/memories/a.md", traversal),
            )
            assertEquals(emptyMap<String, String>(), TestStores.dump(database))
        }

    @Test
    fun `the root is a directory and never a file`() = TestStores.withHandler { handler, database ->
        assertEquals("Error: File /memories already exists", handler.create("/memories", "x"))
        assertEquals(
            "Error: The path /memories does not exist. Please provide a valid path.",
            handler.strReplace("/memories", "a", "b"),
        )
        assertEquals("Error: The path /memories does not exist", handler.insert("/memories", 0L, "x"))
        assertEquals(emptyMap<String, String>(), TestStores.dump(database))
    }

    // --- housekeeping ---------------------------------------------------------------------------

    @Test
    fun `usage counts memories and content bytes`() = TestStores.withHandler { handler, _ ->
        handler.create("/memories/a.md", "12345")
        handler.create("/memories/p/x.md", "é")
        val usage = handler.usage()
        assertEquals(2L, usage.memories)
        assertEquals(7L, usage.bytes)
    }

    @Test
    fun `expireBefore drops what has not been touched since the cutoff`() =
        TestStores.withHandler { handler, database ->
            handler.create("/memories/old.md", "old")
            // Well clear of the coarsest `currentTimeMillis` granularity this runs on — a tick that
            // did not happen would make this assert nothing rather than fail.
            Thread.sleep(40)
            val cutoff = Instant.ofEpochMilli(System.currentTimeMillis())
            Thread.sleep(40)
            handler.create("/memories/new.md", "new")

            assertEquals(1L, handler.expireBefore(cutoff))
            assertEquals(mapOf("/memories/new.md" to "new"), TestStores.dump(database))
            assertEquals(0L, handler.expireBefore(cutoff))
        }

    @Test
    fun `history is rejected rather than silently ignored`() {
        val failure = assertFailsWith<IllegalArgumentException> { MemoryOptions(history = true) }
        assertTrue("v1" in failure.message.orEmpty())
    }

    @Test
    fun `a scope with a colon in it is rejected, because the key would be ambiguous`() {
        assertFailsWith<IllegalArgumentException> { MemoryOptions(scope = "a:b") }
        assertFailsWith<IllegalArgumentException> { MemoryOptions(scope = "") }
        assertFailsWith<IllegalArgumentException> { MemoryOptions(scope = "x".repeat(65)) }
    }

    // --- lifecycle ------------------------------------------------------------------------------

    /**
     * Asserts by **deleting the directory**, which is how rabosh proves nothing is still mapped.
     *
     * Worth carrying across rather than measuring something: on Windows a mapped file cannot be
     * deleted at all, so a handler that leaked its store fails here and passes everywhere a
     * POSIX-only CI leg would look.
     */
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `a handler that owns its store releases the directory on close`() {
        val directory = Files.createTempDirectory("rabosh-memory-lifecycle")
        try {
            RaboshMemoryToolHandler.open(directory).use { handler ->
                handler.create("/memories/a.md", "content")
            }
            directory.deleteRecursively()
            assertFalse(directory.exists(), "the directory could not be deleted, so something is still mapped")
        } finally {
            if (directory.exists()) directory.deleteRecursively()
        }
    }

    @Test
    fun `a handler that does not own its store closes nothing`() = TestStores.withDirectory { directory ->
        Rabosh.open(directory).use { database ->
            val handler = RaboshMemoryToolHandler(database)
            handler.create("/memories/a.md", "content")
            handler.close()
            // The store is still usable; only the handler refuses.
            assertEquals(mapOf("/memories/a.md" to "content"), TestStores.dump(database))
            assertFailsWith<IllegalStateException> { handler.view("/memories", noRange()) }
        }
    }
}
