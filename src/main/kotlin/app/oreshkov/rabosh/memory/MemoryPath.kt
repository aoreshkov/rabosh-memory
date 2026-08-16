package app.oreshkov.rabosh.memory

/**
 * Normalisation of the paths the memory tool speaks, as a pure string function.
 *
 * **`java.nio.file.Path` is deliberately not used here, and the build fails if it appears.** On
 * Windows `Path.of("/memories/a")` normalises with backslashes and `resolve` applies drive-letter
 * semantics, so a key layout derived from it would be a store that means different things on
 * different machines — a divergence no test on one platform can see. The `checkNoNioPath` task in
 * `build.gradle.kts` is what keeps that from being a habit rather than a rule.
 *
 * **This is namespace hygiene, not a security control.** There is no filesystem underneath to escape
 * into: the engine sees a key and nothing else. What normalisation buys is that one memory is one
 * key — a path that normalises two ways would be two memories the model believes are one, which is
 * the failure this function exists to prevent.
 *
 * Public because a host implementing a future dated tool version — `memory_2027xxxx` will be a new
 * interface, not a patch — needs the same rules, and re-deriving them is how two implementations of
 * the same namespace start to disagree.
 */
public object MemoryPath {

    /** The root every memory path lives under. The tool contract fixes this string. */
    public const val ROOT: String = "/memories"

    /** Longest normalised path, in UTF-8 bytes. */
    public const val MAX_PATH_BYTES: Int = 1024

    /** Longest single path segment, in UTF-8 bytes. */
    public const val MAX_SEGMENT_BYTES: Int = 255

    /**
     * Percent-encoded traversal, which is refused rather than decoded.
     *
     * The tool specification calls this sequence out by name. Since nothing here ever
     * percent-decodes, the only two honest answers are *refuse* and *accept a literal segment named
     * `%2e%2e`*, and the second one would put a memory at a path that any handler which does decode
     * would read as something else.
     */
    private val PERCENT_TRAVERSAL = Regex("%2e%2e", RegexOption.IGNORE_CASE)

    /**
     * [raw] in canonical form, or `null` if it is not a path this store will accept.
     *
     * In order: reject percent-encoded traversal; reject NUL, any other C0 control, DEL, a backslash
     * and an unpaired surrogate; require a `/memories` prefix; drop empty segments and `.`; pop on
     * `..` and reject a pop past the root; re-join; require the result to be `/memories` or to start
     * `/memories/`; and bound the whole path and each segment in UTF-8 bytes.
     *
     * The prefix is checked twice — before normalisation and after — because the first check alone
     * accepts `/memories/../../etc` and the second alone accepts nothing useful to check *why*.
     *
     * **A caller must not turn `null` into a message of its own.** Every command answers a rejected
     * path with its own documented "does not exist" string, so that a malicious prompt cannot use
     * the difference between *rejected* and *absent* as an oracle for probing the namespace.
     */
    public fun normalise(raw: String): String? {
        if (PERCENT_TRAVERSAL.containsMatchIn(raw)) return null
        for (character in raw) {
            // A backslash is refused rather than treated as a separator: accepting it would make
            // `\memories\a` and `/memories/a` the same memory on Windows and two on Linux.
            if (character == '\\' || character.code < 0x20 || character.code == 0x7F) return null
        }
        if (!raw.startsWith(ROOT)) return null

        val segments = ArrayList<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.size - 1)
                else -> segments.add(segment)
            }
        }

        val normalised = segments.joinToString(separator = "/", prefix = "/")
        if (normalised != ROOT && !normalised.startsWith("$ROOT/")) return null

        val totalBytes = normalised.utf8SizeOrNull() ?: return null
        if (totalBytes > MAX_PATH_BYTES) return null
        for (segment in segments) {
            val segmentBytes = segment.utf8SizeOrNull() ?: return null
            if (segmentBytes > MAX_SEGMENT_BYTES) return null
        }
        return normalised
    }

    /** Whether [path] is the memory root, which is a directory and never a file. */
    public fun isRoot(path: String): Boolean = path == ROOT

    /**
     * Every ancestor directory of [path], outermost first: `/memories/a/b.md` gives
     * `["/memories", "/memories/a"]`, and the root itself gives none.
     *
     * Stored on each document as `$.anc` so that a directory listing can be an index lookup rather
     * than a subtree scan — see `MemoryOptions.listingIndex`. Written unconditionally, because
     * adding it later would mean rewriting every document that already exists, and it costs a few
     * tens of bytes against a document whose whole point is prose.
     */
    public fun ancestors(path: String): List<String> {
        val ancestors = ArrayList<String>(4)
        var separator = path.indexOf('/', startIndex = 1)
        while (separator > 0) {
            ancestors.add(path.substring(0, separator))
            separator = path.indexOf('/', startIndex = separator + 1)
        }
        return ancestors
    }

    /**
     * The segments of [path] below [parent], or `null` if [path] is not below it.
     *
     * `segmentsBelow("/memories", "/memories/a/b.md")` is `["a", "b.md"]`, whose size is the depth
     * the directory view filters on.
     */
    public fun segmentsBelow(parent: String, path: String): List<String>? {
        val prefix = "$parent/"
        if (!path.startsWith(prefix)) return null
        return path.substring(prefix.length).split('/')
    }

    /**
     * Whether any of [segments] is one the directory view promises to leave out.
     *
     * The header sentence says *"excluding hidden items and node_modules"*, so it has to be true.
     */
    public fun isExcludedFromListing(segments: List<String>): Boolean =
        segments.any { it.startsWith(".") || it == "node_modules" }

    /**
     * The UTF-8 length of this string, or `null` if it holds an unpaired surrogate.
     *
     * Counted rather than encoded because this runs on every command and the answer is usually
     * "well under the limit". The surrogate check is not incidental: `Key.of(String)` refuses an
     * unpaired surrogate outright, so without it a path the model could plausibly produce would
     * leave this function as a valid path and then throw on the way to the store — an
     * `is_error: true` for what is really "we do not accept that path".
     */
    private fun String.utf8SizeOrNull(): Int? {
        var size = 0
        var index = 0
        while (index < length) {
            val code = this[index].code
            when {
                code < 0x80 -> size += 1
                code < 0x800 -> size += 2
                code in 0xD800..0xDBFF -> {
                    val next = if (index + 1 < length) this[index + 1].code else -1
                    if (next !in 0xDC00..0xDFFF) return null
                    size += 4
                    index++
                }

                code in 0xDC00..0xDFFF -> return null
                else -> size += 3
            }
            index++
        }
        return size
    }
}
