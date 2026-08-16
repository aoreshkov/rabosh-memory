package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.api.Rabosh
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The primary suite: generated command scripts run against the handler and against
 * [ReferenceMemoryHandler], asserting that **the returned strings are identical**.
 *
 * Not that the stores agree — that is [modelBasedStoreAgreement]'s job, and it is the weaker
 * property. What the model sees is the string, so a divergence in a string is the failure that would
 * silently confuse it rather than fail a build, and it is the only failure this suite is really for.
 *
 * A failing case prints its seed. Replay it exactly with:
 *
 * ```
 * ./gradlew test -Drabosh.memory.seed=<seed>
 * ```
 */
class DifferentialMemoryTest {

    @Test
    fun `returned strings match the reference over generated scripts`() {
        forEachScript { seed, script ->
            TestStores.withHandler { handler, _ ->
                val reference = ReferenceMemoryHandler()
                script.forEachIndexed { step, command ->
                    val actual = command.runOn(handler)
                    val expected = command.runOn(reference)
                    if (actual != expected) {
                        fail(
                            "seed $seed, step $step, $command\n" +
                                "expected:\n$expected\n\nactual:\n$actual",
                        )
                    }
                }
            }
        }
    }

    /**
     * The same scripts, asserting the *store* against the model after every command — and again
     * after a close and a reopen.
     *
     * The reopen is not ceremony. It is the only assertion in the suite that the bytes on disk say
     * what the memtable said, which is the difference between a store and a cache.
     */
    @Test
    fun modelBasedStoreAgreement() {
        forEachScript { seed, script ->
            TestStores.withDirectory { directory ->
                val reference = ReferenceMemoryHandler()
                Rabosh.open(directory).use { database ->
                    RaboshMemoryToolHandler(database).use { handler ->
                        script.forEachIndexed { step, command ->
                            command.runOn(handler)
                            command.runOn(reference)
                            assertEquals(
                                reference.store,
                                TestStores.dump(database),
                                "seed $seed, step $step, after $command",
                            )
                        }
                    }
                }
                Rabosh.open(directory).use { reopened ->
                    assertEquals(
                        reference.store,
                        TestStores.dump(reopened),
                        "seed $seed: the store disagreed with the model after a reopen",
                    )
                }
            }
        }
    }

    /** The same, with a scope that is not the default, to prove the prefix is doing its job. */
    @Test
    fun `two scopes in one store do not see each other`() {
        TestStores.withDirectory { directory ->
            Rabosh.open(directory).use { database ->
                val alice = RaboshMemoryToolHandler(database, MemoryOptions(scope = "alice"))
                val bob = RaboshMemoryToolHandler(database, MemoryOptions(scope = "bob"))

                alice.create("/memories/notes.md", "alice's notes\n")
                assertEquals(
                    "The path /memories/notes.md does not exist. Please provide a valid path.",
                    bob.view("/memories/notes.md", java.util.Optional.empty()),
                )
                assertEquals(
                    "Here're the files and directories up to 2 levels deep in /memories, " +
                        "excluding hidden items and node_modules:\n0B\t/memories",
                    bob.view("/memories", java.util.Optional.empty()),
                )
                assertEquals(mapOf("/memories/notes.md" to "alice's notes\n"), TestStores.dump(database, "alice"))
                assertEquals(emptyMap<String, String>(), TestStores.dump(database, "bob"))
            }
        }
    }

    /**
     * One script per iteration, each with a fresh seed — unless a seed was given, in which case
     * exactly that one script runs. Replaying a failure must not be a lottery with better odds.
     */
    private fun forEachScript(action: (Long, List<MemoryCommand>) -> Unit) {
        val configured = System.getProperty(SEED_PROPERTY)?.toLong()
        if (configured != null) {
            action(configured, MemoryScripts.script(Random(configured), SCRIPT_LENGTH))
            return
        }
        val scripts = System.getProperty(ITERATIONS_PROPERTY)?.toInt() ?: DEFAULT_SCRIPTS
        repeat(scripts) {
            val seed = Random.nextLong()
            action(seed, MemoryScripts.script(Random(seed), SCRIPT_LENGTH))
        }
    }

    private companion object {
        const val SEED_PROPERTY = "rabosh.memory.seed"
        const val ITERATIONS_PROPERTY = "rabosh.memory.iterations"

        /**
         * Enough scripts to hit the collisions the pools are sized for, and few enough that the
         * suite stays inside a few seconds on a laptop. Raise it with `-Drabosh.memory.iterations`.
         */
        const val DEFAULT_SCRIPTS = 60
        const val SCRIPT_LENGTH = 60
    }
}
