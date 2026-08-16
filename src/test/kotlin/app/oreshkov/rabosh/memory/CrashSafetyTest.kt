package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.api.Rabosh
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The claim this module is built on, killed rather than asserted.
 *
 * A child JVM is destroyed with `TerminateProcess` (Windows) or `SIGKILL` (POSIX) in the middle of a
 * command, the directory is reopened, and what is there is compared against what the child had been
 * told was committed. No shutdown hook runs, nothing is flushed, nothing is unmapped — which is the
 * whole point, and the reason this is a process rather than a mock.
 *
 * `rabosh-testkit` is not published, so the harness is restated here. It is a child JVM, a line of
 * stdout and a `destroyForcibly`, which is not much more than the engine's own version.
 */
class CrashSafetyTest {

    /**
     * Killed mid-`create`: everything the child was told was written is there, and nothing beyond
     * a prefix of what it attempted.
     */
    @Test
    fun `a kill during create leaves the acknowledged prefix`() {
        repeat(ROUNDS) { round ->
            TestStores.withDirectory { directory ->
                val process = launch(directory, mode = "create", count = CREATE_COUNT)
                var acknowledged = -1
                var killedAlive = false
                try {
                    val reader = process.inputStream.bufferedReader()
                    // Let a handful land, then kill at a moment nothing has arranged.
                    val target = MIN_ACKS + Random.nextInt(MIN_ACKS)
                    while (acknowledged < target) {
                        val line = reader.readLine() ?: break
                        acknowledged = line.trim().toInt()
                    }
                } finally {
                    killedAlive = process.isAlive
                    process.destroyForcibly()
                    process.waitFor(30, TimeUnit.SECONDS)
                }

                // The instrument has to be shown to be instrumenting. An assertion about what
                // survives a kill that never happened is the most comfortable green there is.
                assertTrue(
                    acknowledged >= MIN_ACKS,
                    "round $round: the child acknowledged only ${acknowledged + 1} creates, " +
                        "so it failed rather than being interrupted",
                )
                assertTrue(killedAlive, "round $round: the child had already exited; nothing was interrupted")

                val present = reopen(directory)
                assertTrue(
                    present.size < CREATE_COUNT,
                    "round $round: the child completed all $CREATE_COUNT creates, so the kill " +
                        "landed after the work rather than inside it",
                )
                for (index in 0..acknowledged) {
                    assertEquals(
                        CrashChildProtocol.payload(index),
                        present[CrashChildProtocol.memoryPath("/memories", index)],
                        "round $round: memory $index was acknowledged and is not there",
                    )
                }
                // A prefix, not a scattering: the store may hold more than was announced — the child
                // was killed after a commit and before its `println` — but it must not hold a gap.
                val indices = present.keys
                    .map { it.removePrefix("/memories/m").removeSuffix(".md").toInt() }
                    .sorted()
                assertEquals(
                    (0 until indices.size).toList(),
                    indices,
                    "round $round: the surviving memories are not a prefix of what was attempted",
                )
            }
        }
    }

    /**
     * Killed mid-`rename` of a subtree: **all-old or all-new, never a mixture.**
     *
     * This is the test that has to exist. A filesystem cannot promise this across a device boundary
     * and does not promise it for a recursive move at all; here it holds because the whole move is
     * one commit and the acknowledged prefix is a commit boundary.
     *
     * Note that the assertion is sound whichever side of the move the kill lands on. Timing decides
     * how *interesting* a round is, not whether it is valid — which is what makes a randomised delay
     * an acceptable way to aim at a window this small.
     */
    @Test
    fun `a kill during a recursive rename leaves all-old or all-new`() {
        var roundsKilledAlive = 0
        repeat(ROUNDS) { round ->
            TestStores.withDirectory { directory ->
                val process = launch(directory, mode = "rename", count = RENAME_COUNT)
                try {
                    val reader = process.inputStream.bufferedReader()
                    var line = reader.readLine()
                    while (line != null && line.trim() != CrashChildProtocol.READY) line = reader.readLine()
                    assertTrue(line != null, "round $round: the child never finished seeding")
                    Thread.sleep(Random.nextLong(RENAME_KILL_DELAY_MILLIS).coerceAtLeast(1))
                } finally {
                    if (process.isAlive) roundsKilledAlive++
                    process.destroyForcibly()
                    process.waitFor(30, TimeUnit.SECONDS)
                }

                val present = reopen(directory)
                assertEquals(RENAME_COUNT, present.size, "round $round: memories were lost or duplicated")

                val underSource = present.keys.count { it.startsWith("/memories/src/") }
                val underDestination = present.keys.count { it.startsWith("/memories/dst/") }
                if (underSource != RENAME_COUNT && underDestination != RENAME_COUNT) {
                    fail(
                        "round $round: the rename was torn — $underSource memories at the old paths " +
                            "and $underDestination at the new ones",
                    )
                }

                val prefix = if (underDestination == RENAME_COUNT) "/memories/dst" else "/memories/src"
                for (index in 0 until RENAME_COUNT) {
                    assertEquals(
                        CrashChildProtocol.payload(index),
                        present[CrashChildProtocol.memoryPath(prefix, index)],
                        "round $round: memory $index is missing or wrong under $prefix",
                    )
                }
            }
        }
        assertTrue(
            roundsKilledAlive > 0,
            "every round's child had already exited before the kill, so nothing was interrupted " +
                "and the all-old-or-all-new assertion proved nothing",
        )
    }

    private fun launch(directory: Path, mode: String, count: Int): Process {
        val javaHome = requireNotNull(System.getProperty("rabosh.memory.javaHome")) {
            "rabosh.memory.javaHome is not set; see the Test task wiring in build.gradle.kts"
        }
        val classpath = requireNotNull(System.getProperty("rabosh.memory.testClasspath")) {
            "rabosh.memory.testClasspath is not set; see the Test task wiring in build.gradle.kts"
        }
        val java = Path.of(javaHome, "bin", if (isWindows) "java.exe" else "java").toString()
        return ProcessBuilder(
            java,
            // The engine maps segments off-heap through the FFM API, in the child as in the parent.
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            classpath,
            "app.oreshkov.rabosh.memory.CrashChild",
            directory.toString(),
            mode,
            count.toString(),
        ).redirectErrorStream(false).start()
    }

    /**
     * Reopens the directory the child was killed on and reads every memory back.
     *
     * The reopen is the assertion's whole basis: recovery is what turns a log with a torn tail into
     * the acknowledged prefix, and nothing here inspects a file to help it along.
     */
    private fun reopen(directory: Path): Map<String, String> =
        Rabosh.open(directory).use { database -> TestStores.dump(database) }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private companion object {
        const val ROUNDS = 3

        /** Enough creates that the kill has somewhere to land; each one is an `fsync`. */
        const val CREATE_COUNT = 400
        const val MIN_ACKS = 8

        /** A subtree large enough that moving it is one commit worth interrupting. */
        const val RENAME_COUNT = 250
        const val RENAME_KILL_DELAY_MILLIS = 40L
    }
}
