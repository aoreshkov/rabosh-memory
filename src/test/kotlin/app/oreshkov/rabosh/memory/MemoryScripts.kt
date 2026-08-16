package app.oreshkov.rabosh.memory

import com.anthropic.helpers.BetaMemoryToolHandler
import java.util.Optional
import kotlin.random.Random

/**
 * Generated command scripts, which are what the differential and model-based suites run.
 *
 * Almost none of the engine's generator surface applies here: those generators produce JSON, and
 * what this module needs is a *sequence of tool calls* with the shape a model actually produces —
 * mostly valid paths, occasionally a traversal attempt, `old_str` values that sometimes appear in
 * the file and sometimes appear twice.
 *
 * The pools are deliberately small and overlapping. A generator that drew fresh random paths would
 * almost never revisit a file, and every interesting case in this contract — `create` on something
 * that exists, `str_replace` with two matches, `rename` onto an occupied destination, a subtree
 * `delete` — needs a collision to happen.
 */
internal sealed interface MemoryCommand {

    fun runOn(handler: BetaMemoryToolHandler): String

    data class View(val path: String, val range: List<Long>?) : MemoryCommand {
        override fun runOn(handler: BetaMemoryToolHandler): String =
            handler.view(path, Optional.ofNullable(range))
    }

    data class Create(val path: String, val text: String) : MemoryCommand {
        override fun runOn(handler: BetaMemoryToolHandler): String = handler.create(path, text)
    }

    data class StrReplace(val path: String, val oldStr: String, val newStr: String) : MemoryCommand {
        override fun runOn(handler: BetaMemoryToolHandler): String =
            handler.strReplace(path, oldStr, newStr)
    }

    data class Insert(val path: String, val line: Long, val text: String) : MemoryCommand {
        override fun runOn(handler: BetaMemoryToolHandler): String = handler.insert(path, line, text)
    }

    data class Delete(val path: String) : MemoryCommand {
        override fun runOn(handler: BetaMemoryToolHandler): String = handler.delete(path)
    }

    data class Rename(val from: String, val to: String) : MemoryCommand {
        override fun runOn(handler: BetaMemoryToolHandler): String = handler.rename(from, to)
    }
}

internal object MemoryScripts {

    /**
     * Paths a model plausibly sends, plus the ones a prompt-injected model sends.
     *
     * The awkward ones are here rather than in a separate suite because what matters is that a
     * rejected path behaves *the same as* an absent one in the middle of an otherwise ordinary
     * script — that is the property, and it cannot be checked in isolation.
     */
    private val PATHS = listOf(
        "/memories",
        "/memories/a.md",
        "/memories/b.md",
        "/memories/p",
        "/memories/p/x.md",
        "/memories/p/y.md",
        "/memories/p/q/deep.md",
        "/memories/p/q/r/deeper.md",
        "/memories/.hidden.md",
        "/memories/node_modules/pkg.md",
        "/memories/./a.md",
        "/memories/p/../a.md",
        "/memories/../../etc/passwd",
        "/memories/%2E%2E/a.md",
        "/memories\\p\\x.md",
        "/etc/passwd",
        "/memoriesish/a.md",
        "",
    )

    private val TEXTS = listOf(
        "",
        "alpha",
        "alpha\n",
        "one\ntwo\nthree\n",
        "dup\nmiddle\ndup\n",
        "aaa",
        "\n",
        "line\twith\ttabs\n",
        "unicode: é中😀\n",
        (1..40).joinToString("\n") { "line $it" } + "\n",
    )

    private val OLD_STRINGS = listOf("alpha", "dup", "aa", "", "zzz", "one", "\n", "line 7", "three")

    private val NEW_STRINGS = listOf("", "beta", "beta\ngamma", "\n", "alpha")

    private val RANGES = listOf<List<Long>?>(
        null,
        listOf(1L, -1L),
        listOf(1L, 2L),
        listOf(2L, 3L),
        listOf(0L, 5L),
        listOf(5L, 2L),
        listOf(3L, -2L),
        listOf(-4L, 9L),
        listOf(1L, 100_000L),
    )

    fun script(random: Random, length: Int): List<MemoryCommand> = List(length) { command(random) }

    private fun command(random: Random): MemoryCommand = when (random.nextInt(100)) {
        // Weighted the way a session is: mostly writing and reading, with the destructive commands
        // rare enough that the store has something in it when they land.
        in 0 until 30 -> MemoryCommand.Create(PATHS.random(random), TEXTS.random(random))
        in 30 until 50 -> MemoryCommand.View(PATHS.random(random), RANGES.random(random))
        in 50 until 68 -> MemoryCommand.StrReplace(
            PATHS.random(random),
            OLD_STRINGS.random(random),
            NEW_STRINGS.random(random),
        )

        in 68 until 84 -> MemoryCommand.Insert(
            PATHS.random(random),
            random.nextInt(-1, 6).toLong(),
            TEXTS.random(random),
        )

        in 84 until 92 -> MemoryCommand.Delete(PATHS.random(random))
        else -> MemoryCommand.Rename(PATHS.random(random), PATHS.random(random))
    }

    private fun <T> List<T>.random(random: Random): T = this[random.nextInt(size)]
}
