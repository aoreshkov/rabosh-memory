package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.api.Rabosh
import app.oreshkov.rabosh.core.Key
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import kotlin.io.path.deleteRecursively

/**
 * The small amount of scaffolding the suites share.
 *
 * `rabosh-testkit` is not published, so none of the engine's instruments arrive here as a
 * dependency. That is the separate-repository decision presenting its bill, and it is paid per
 * instrument: this file is the whole of what a store fixture costs, and the kill harness in
 * `CrashSafetyTest` is the whole of what the crash instrument costs.
 */
internal object TestStores {

    /**
     * Every memory in [scope], read straight out of the store rather than through the handler.
     *
     * The model-based property test compares this against the reference's `TreeMap` after every
     * command. Going around the handler is the point: a comparison made through the thing under test
     * cannot see a write that landed under the wrong key.
     */
    fun dump(database: Rabosh, scope: String = DEFAULT_SCOPE): TreeMap<String, String> {
        val prefix = "m:$scope:"
        val from = prefix.toByteArray(Charsets.UTF_8)
        val to = from.copyOf().also { it[it.size - 1]++ }
        val contents = TreeMap<String, String>()
        database.scan(from = Key.of(from), to = Key.of(to)).use { cursor ->
            while (cursor.next()) {
                val key = cursor.key.toByteArray().decodeToString()
                if (!key.startsWith(prefix)) continue
                contents[key.substring(prefix.length)] = MemoryDocument.content(cursor.document)
            }
        }
        return contents
    }

    /** A temporary directory that removes itself, and its store with it, however the block ends. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun <T> withDirectory(action: (Path) -> T): T {
        val directory = Files.createTempDirectory("rabosh-memory-test")
        return try {
            action(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** A handler over a store this function opens, closes and then deletes the directory of. */
    fun <T> withHandler(
        options: MemoryOptions = MemoryOptions.DEFAULT,
        action: (RaboshMemoryToolHandler, Rabosh) -> T,
    ): T = withDirectory { directory ->
        Rabosh.open(directory).use { database ->
            RaboshMemoryToolHandler(database, options).use { handler ->
                action(handler, database)
            }
        }
    }
}
