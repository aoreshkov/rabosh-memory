package app.oreshkov.rabosh.memory

import app.oreshkov.rabosh.api.Rabosh
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.helpers.BetaMemoryToolHandler
import com.anthropic.models.beta.messages.BetaMemoryTool20250818
import com.anthropic.models.beta.messages.MessageCreateParams
import com.anthropic.models.beta.messages.ToolRunnerCreateParams
import com.anthropic.models.messages.Model
import java.nio.file.Path
import java.util.Optional
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Tag

/**
 * One real conversation, against the real API, through the real tool runner.
 *
 * **This exists to catch the SDK moving under us, and nothing else.** Every claim about what the six
 * commands return is settled offline by the differential suite; what no offline test can settle is
 * whether `ToolRunnerCreateParams` still takes a `betaMemoryToolHandler`, whether the runner still
 * dispatches on the tool name `memory`, and whether the command union still visits the six methods
 * this handler implements. `anthropic-java` is pinned to an exact version precisely so that those
 * answers only change when somebody changes them — and this is how they find out.
 *
 * Not part of `build`: it costs money and needs a network. Run it with:
 *
 * ```
 * ANTHROPIC_API_KEY=… ./gradlew test -Prabosh.memory.smoke
 * ```
 *
 * It **fails** rather than skips when the key is absent. A smoke test that quietly passes because it
 * did not run is worse than no smoke test, because it is reported as a green canary.
 */
@Tag("smoke")
class LiveSmokeTest {

    @Test
    fun `the model writes a memory in one conversation and reads it back in the next`() {
        if (System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()) {
            fail(
                "ANTHROPIC_API_KEY is not set. This suite is excluded from `build` and only runs " +
                    "when asked for with -Prabosh.memory.smoke, so an unset key is a broken " +
                    "invocation rather than an environment to skip on.",
            )
        }

        val token = "ACME-%06X".format(Random.nextInt(0, 0xFFFFFF))
        val client = AnthropicOkHttpClient.fromEnv()

        TestStores.withDirectory { directory ->
            val firstCalls = converse(
                client = client,
                directory = directory,
                prompt = "Remember this for later: the account reference is $token. " +
                    "Store it in your memory directory, then confirm in one sentence.",
            )
            assertTrue(firstCalls.commands > 0, "the model never called the memory tool")

            val stored = Rabosh.open(directory).use { TestStores.dump(it) }
            assertTrue(stored.isNotEmpty(), "the conversation ended with an empty memory directory")
            assertTrue(
                stored.values.any { token in it },
                "no memory holds $token; the store has ${stored.keys}",
            )

            // A second conversation with no history at all: everything it can know comes off disk.
            val secondCalls = converse(
                client = client,
                directory = directory,
                prompt = "What is the account reference you recorded earlier? " +
                    "Answer with the reference itself.",
            )
            assertTrue(secondCalls.commands > 0, "the second conversation never read the memory directory")
            assertTrue(
                token in secondCalls.text,
                "the model did not read $token back; it said:\n${secondCalls.text}",
            )
        }
    }

    private fun converse(
        client: com.anthropic.client.AnthropicClient,
        directory: Path,
        prompt: String,
    ): Transcript {
        Rabosh.open(directory).use { database ->
            RaboshMemoryToolHandler(database).use { handler ->
                val counting = CountingHandler(handler)

                val createParams = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_4_5)
                    .maxTokens(1024L)
                    .addTool(BetaMemoryTool20250818.builder().build())
                    .addUserMessage(prompt)
                    .build()

                val runnerParams = ToolRunnerCreateParams.builder()
                    .betaMemoryToolHandler(counting)
                    .initialMessageParams(createParams)
                    .maxIterations(10L)
                    .build()

                val text = StringBuilder()
                for (message in client.beta().messages().toolRunner(runnerParams)) {
                    for (block in message.content()) {
                        block.text().ifPresent { text.append(it.text()).append('\n') }
                    }
                }
                return Transcript(counting.commands, text.toString())
            }
        }
    }

    private class Transcript(val commands: Int, val text: String)

    /**
     * Counts the commands the runner dispatched.
     *
     * The assertion that matters most in this suite is not about a string — it is that the runner
     * reached the handler at all. A future SDK that renamed the tool, changed the dispatch key or
     * stopped calling the memory path would produce a perfectly pleasant conversation and zero
     * commands, and only this counter would notice.
     */
    private class CountingHandler(private val delegate: BetaMemoryToolHandler) : BetaMemoryToolHandler {
        var commands: Int = 0
            private set

        override fun view(path: String, viewRange: Optional<List<Long>>): String {
            commands++
            return delegate.view(path, viewRange)
        }

        override fun create(path: String, fileText: String): String {
            commands++
            return delegate.create(path, fileText)
        }

        override fun strReplace(path: String, oldStr: String, newStr: String): String {
            commands++
            return delegate.strReplace(path, oldStr, newStr)
        }

        override fun insert(path: String, insertLine: Long, insertText: String): String {
            commands++
            return delegate.insert(path, insertLine, insertText)
        }

        override fun delete(path: String): String {
            commands++
            return delegate.delete(path)
        }

        override fun rename(oldPath: String, newPath: String): String {
            commands++
            return delegate.rename(oldPath, newPath)
        }
    }
}
