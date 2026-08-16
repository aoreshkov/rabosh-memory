package app.oreshkov.rabosh.memory

/**
 * Every string this handler returns to the model, in one place.
 *
 * **The prefixes are inconsistent and that is deliberate: they are reproduced, not tidied.** `view`
 * on a missing path has no `Error: ` prefix; `insert` and `delete` have one and stop there;
 * `str_replace` has the prefix *and* the trailing sentence. These are what the model has been
 * trained to read, so normalising them into a house style would be a silent divergence from every
 * other implementation of this tool — a change no build turns red and the model simply absorbs.
 *
 * The `ReferenceMemoryHandler` in the test sources deliberately does **not** use this object. It
 * writes the same strings out longhand, which is what makes the differential suite able to catch a
 * typo here rather than agreeing with it.
 *
 * ### Where these come from
 *
 * The wording is the specification's, at
 * `platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool`. Where the specification is
 * silent about *behaviour* — the snippet `str_replace` appends, how a size is rendered, how
 * `view_range` clamps — the tie-breaker is the SDK's own `BetaLocalFilesystemMemoryTool`, which is
 * the only concrete reference implementation. The two disagree in a few places, and where they do
 * the specification wins for strings: it is the document the model's tool description is written
 * against.
 *
 * Five strings here are **additions**, because the specification asks for a behaviour and supplies
 * no wording for it — a size cap, a truncated view, two root refusals and a rename into its own
 * subtree. Each is marked below and all of them are listed in the README, so that a reader comparing
 * this handler against another one is told which strings are ours rather than left to discover it.
 */
internal object MemoryResponses {

    /** The line count above which `view` refuses to render a file. */
    const val MAX_LINES: Int = 999_999

    /** Width of the line-number column: `len("999999")`, right-aligned with spaces. */
    const val LINE_NUMBER_WIDTH: Int = 6

    // --- view ---------------------------------------------------------------------------------

    fun directoryHeader(path: String): String =
        "Here're the files and directories up to 2 levels deep in $path, " +
            "excluding hidden items and node_modules:"

    fun fileHeader(path: String): String = "Here's the content of $path with line numbers:"

    /** `view`'s missing-path string, which uniquely has no `Error: ` prefix. Do not add one. */
    fun viewMissing(path: String): String = "The path $path does not exist. Please provide a valid path."

    fun exceedsLineLimit(path: String): String =
        "File $path exceeds maximum line limit of 999,999 lines."

    /**
     * **Addition.** The specification asks integrators to cap how much `view` returns and to let
     * the model page through the rest, and supplies no wording. Naming `view_range` in the note is
     * the whole point of it: a truncation the model cannot act on is just a shorter answer.
     */
    fun viewTruncated(shown: IntRange, resumeAt: Int, totalLines: Int): String =
        "[Truncated: showing lines ${shown.first} to ${shown.last} of $totalLines. " +
            "Use view_range [$resumeAt, -1] to read the rest.]"

    // --- create -------------------------------------------------------------------------------

    fun created(path: String): String = "File created successfully at: $path"

    fun alreadyExists(path: String): String = "Error: File $path already exists"

    /**
     * **Addition.** The specification asks integrators to cap file size and supplies no wording.
     * Naming the limit in bytes is what lets the model split the memory rather than retry it.
     */
    fun exceedsSizeLimit(path: String, limit: Long): String =
        "Error: File $path exceeds the maximum memory file size of $limit bytes"

    // --- str_replace --------------------------------------------------------------------------

    /**
     * The success header, verbatim from the SDK's reference implementation — including *"Here is
     * the snippet showing the change (with line numbers)"*, which the specification's table
     * abbreviates to "followed by a snippet".
     */
    const val EDITED: String =
        "The memory file has been edited. Here is the snippet showing the change (with line numbers):"

    /** Note the `Error: ` prefix *and* the trailing sentence — `insert`'s equivalent has neither. */
    fun strReplaceMissing(path: String): String =
        "Error: The path $path does not exist. Please provide a valid path."

    fun notVerbatim(oldStr: String, path: String): String =
        "No replacement was performed, old_str `$oldStr` did not appear verbatim in $path."

    fun multipleOccurrences(oldStr: String, lines: List<Int>): String =
        "No replacement was performed. Multiple occurrences of old_str `$oldStr` in lines: " +
            "${lines.joinToString(", ")}. Please ensure it is unique"

    // --- insert -------------------------------------------------------------------------------

    fun inserted(path: String): String = "The file $path has been edited."

    /** `Error: ` and then nothing — no "Please provide a valid path." Reproduced, not tidied. */
    fun insertMissing(path: String): String = "Error: The path $path does not exist"

    fun invalidInsertLine(insertLine: Long, lineCount: Int): String =
        "Error: Invalid `insert_line` parameter: $insertLine. " +
            "It should be within the range of lines of the file: [0, $lineCount]"

    // --- delete -------------------------------------------------------------------------------

    fun deleted(path: String): String = "Successfully deleted $path"

    fun deleteMissing(path: String): String = "Error: The path $path does not exist"

    /**
     * **Addition**, worded after the SDK's reference implementation — the specification says to
     * reject this and gives no string.
     *
     * Not covered by the rule that a rejected path gets the command's own "does not exist" string:
     * that rule exists so a prompt cannot probe the namespace, and this refusal leaks nothing —
     * Claude's own tool description already tells it that `/memories` cannot be removed. Answering
     * with "does not exist" would instead teach it that its memory directory is gone.
     */
    const val CANNOT_DELETE_ROOT: String = "Error: Cannot delete the ${MemoryPath.ROOT} directory itself"

    // --- rename -------------------------------------------------------------------------------

    fun renamed(oldPath: String, newPath: String): String = "Successfully renamed $oldPath to $newPath"

    fun renameMissing(oldPath: String): String = "Error: The path $oldPath does not exist"

    fun destinationExists(newPath: String): String = "Error: The destination $newPath already exists"

    /** **Addition**: the root refusal for `rename`. See [CANNOT_DELETE_ROOT]. */
    const val CANNOT_RENAME_ROOT: String = "Error: Cannot rename the ${MemoryPath.ROOT} directory itself"

    /**
     * **Addition.** Moving a subtree inside itself has no correct outcome — the source keys and the
     * destination keys would overlap — and no documented string covers it. Saying so plainly beats
     * "destination already exists", which would be a lie the model then acts on.
     */
    fun renameIntoOwnSubtree(oldPath: String, newPath: String): String =
        "Error: Cannot rename $oldPath to $newPath, which is inside it"

    // --- rendering ----------------------------------------------------------------------------

    /** One rendered line of a file view: the number right-aligned in six columns, then a tab. */
    fun numberedLine(lineNumber: Int, line: String): String =
        "${lineNumber.toString().padStart(LINE_NUMBER_WIDTH)}\t$line"
}
