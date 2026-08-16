@file:JvmName("CrashChild")

package app.oreshkov.rabosh.memory

import java.nio.file.Path

/**
 * The child JVM the crash instruments kill.
 *
 * It is a separate process for the only reason that matters: a crash test that used an in-process
 * fault injector would be asserting something about the injector. `TerminateProcess` — which is what
 * `Process.destroyForcibly` becomes on Windows, as `SIGKILL` is on POSIX — gives the process no
 * chance to flush, close, unmap or run a shutdown hook, which is the failure the engine's
 * acknowledged-prefix guarantee is actually about.
 *
 * It talks to the parent over stdout, one line at a time and flushed, because that is the only
 * channel that survives being killed halfway through a sentence.
 */
internal object CrashChildProtocol {
    const val READY: String = "READY"
    const val DONE: String = "DONE"

    /** Big enough that a write is not instantaneous, small enough that a few hundred are quick. */
    fun payload(index: Int): String = "memory $index\n" + "x".repeat(400) + "\n"

    fun memoryPath(directory: String, index: Int): String = "$directory/m%05d.md".format(index)
}

fun main(arguments: Array<String>) {
    val directory = Path.of(arguments[0])
    val mode = arguments[1]
    val count = arguments[2].toInt()

    RaboshMemoryToolHandler.open(directory).use { handler ->
        when (mode) {
            // Create memories one at a time, announcing each *after* the command returned. Every
            // announced index is therefore acknowledged, and the parent asserts that reopening finds
            // all of them.
            "create" -> repeat(count) { index ->
                handler.create(CrashChildProtocol.memoryPath("/memories", index), CrashChildProtocol.payload(index))
                println(index)
                System.out.flush()
            }

            // Seed a subtree, announce, and then move the whole thing in one command. The parent
            // kills somewhere around the move, and asserts all-old or all-new.
            "rename" -> {
                repeat(count) { index ->
                    handler.create(
                        CrashChildProtocol.memoryPath("/memories/src", index),
                        CrashChildProtocol.payload(index),
                    )
                }
                println(CrashChildProtocol.READY)
                System.out.flush()
                handler.rename("/memories/src", "/memories/dst")
                println(CrashChildProtocol.DONE)
                System.out.flush()
            }

            else -> error("unknown crash mode '$mode'")
        }
    }
}
