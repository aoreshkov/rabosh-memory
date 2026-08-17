@file:JvmName("CrashDemo")

package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.api.Rabosh
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * `CrashSafetyTest`'s rename instrument with its assertions replaced by a printout.
 *
 * This exists to be *watched*. The claim the module is built on — a recursive `rename` is one commit,
 * so a crash leaves all-old or all-new and never a mixture — is already asserted in `CrashSafetyTest`,
 * and an assertion is the right form for CI and the wrong form for someone deciding whether to
 * believe it. Here the same child JVM is killed the same way, and every round prints what was on disk
 * afterwards.
 *
 * **It is a demonstration and not a test**: nothing here fails the build, and it deliberately reports
 * the two things that would make a round meaningless rather than hiding them. If the child had
 * already finished when the kill landed, the round's `still running` column says `no` and it proved
 * nothing; if a round is ever `TORN`, that is the headline and the summary says so.
 *
 * Run it with `./gradlew crashDemo`. See `CrashChild` for why the kill is a real process.
 */

/** Enough memories that the move is a commit worth interrupting, few enough that a round is quick. */
private const val MEMORIES = 250

/**
 * The window the kill is swept across, in milliseconds after the child says it has finished seeding.
 *
 * Wide enough to straddle the commit: moving [MEMORIES] memories takes something in the low tens of
 * milliseconds on the machine this was written on, so a sweep from one end of this window to the
 * other crosses the instant the commit lands and the table shows `all-old` above it and `all-new`
 * below. If every round comes out the same way the window is on the wrong side of the move — raise
 * or lower it, since neither is a wrong result, only a duller one.
 */
private const val KILL_WINDOW_MILLIS = 28L

/** Seven steps of four milliseconds across [KILL_WINDOW_MILLIS], which is enough to see the flip. */
private const val DEFAULT_ROUNDS = 7

fun main() {
    val rounds = System.getProperty("rabosh.memory.demo.rounds")?.toIntOrNull() ?: DEFAULT_ROUNDS

    // ASCII only, and not as a style preference: this is written to be filmed, and the Windows
    // console renders an em dash as a replacement character under the default code page. A demo whose
    // first line is mojibake undoes what the rest of it is trying to establish.
    println()
    println("rabosh-memory - a recursive rename, killed in the middle")
    println()
    println("  $MEMORIES memories under /memories/src, moved to /memories/dst by one rename command.")
    println("  Each round kills the JVM partway through the move - no flush, no unmap, no shutdown")
    println("  hook - then reopens the store from disk and reads every memory back.")
    println()
    println("  round  killed after  still running  at /src  at /dst  memories  outcome")

    var killedAlive = 0
    var torn = 0
    var miscounted = 0

    repeat(rounds) { round ->
        TestStores.withDirectory { directory ->
            val process = launch(directory)
            // Swept, not sampled. `CrashSafetyTest` randomises because an unarranged moment is what
            // makes an assertion worth anything; this is trying to show something instead, and what
            // it shows is that the flip from all-old to all-new happens at an instant with nothing
            // in between. A sweep walks up to that instant and past it; random delays would land on
            // both sides in an order that reads like noise.
            val delay = (KILL_WINDOW_MILLIS * (round + 1)) / rounds
            val alive: Boolean
            try {
                // Wait for the subtree to exist, then kill at a moment nothing has arranged.
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                while (line != null && line.trim() != CrashChildProtocol.READY) line = reader.readLine()
                Thread.sleep(delay)
            } finally {
                alive = process.isAlive
                process.destroyForcibly()
                process.waitFor(30, TimeUnit.SECONDS)
            }
            if (alive) killedAlive++

            // Reopening is the whole point: recovery is what turns a log with a torn tail back into
            // the acknowledged prefix, and nothing here touches a file to help it along.
            val present = Rabosh.open(directory).use { database -> TestStores.dump(database) }
            val atSource = present.keys.count { it.startsWith("/memories/src/") }
            val atDestination = present.keys.count { it.startsWith("/memories/dst/") }
            val outcome = when {
                atSource == MEMORIES && atDestination == 0 -> "all-old"
                atDestination == MEMORIES && atSource == 0 -> "all-new"
                else -> "TORN".also { torn++ }
            }
            if (present.size != MEMORIES) miscounted++

            println(
                "  %5d  %9d ms  %13s  %7d  %7d  %8d  %s".format(
                    round + 1,
                    delay,
                    if (alive) "yes" else "no",
                    atSource,
                    atDestination,
                    present.size,
                    outcome,
                ),
            )
        }
    }

    println()
    println(
        "  $rounds rounds, $killedAlive killed mid-command, $torn torn, " +
            "$miscounted with a memory lost or duplicated.",
    )
    if (killedAlive < rounds) {
        println("  Rounds marked 'no' finished before the kill landed and demonstrate nothing.")
    }
    println()
}

/**
 * The child JVM, launched exactly as `CrashSafetyTest` launches it.
 *
 * It cannot inherit `java.class.path`: on Windows Gradle hands a long classpath to the worker through
 * a pathing jar, so the property would be one jar of manifest entries. The `crashDemo` task passes
 * the resolved classpath and the toolchain's home instead.
 */
private fun launch(directory: Path): Process {
    val javaHome = requireNotNull(System.getProperty("rabosh.memory.javaHome")) {
        "rabosh.memory.javaHome is not set; run this through `./gradlew crashDemo`"
    }
    val classpath = requireNotNull(System.getProperty("rabosh.memory.testClasspath")) {
        "rabosh.memory.testClasspath is not set; run this through `./gradlew crashDemo`"
    }
    val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    val java = Path.of(javaHome, "bin", if (isWindows) "java.exe" else "java").toString()
    return ProcessBuilder(
        java,
        // The engine maps segments off-heap through the FFM API, in the child as in the parent.
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        classpath,
        "app.oreshkov.rabosh.memory.CrashChild",
        directory.toString(),
        "rename",
        MEMORIES.toString(),
    ).redirectErrorStream(false).start()
}
