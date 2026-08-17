@file:OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

description = "The Anthropic memory tool, backed by rabosh instead of a directory of files."

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())

    explicitApi()

    compilerOptions {
        allWarningsAsErrors = true
    }

    // Kotlin's built-in ABI validation (2.4+), as in the engine. Registers `checkKotlinAbi`, which
    // `check` depends on, and `updateKotlinAbi`, which rewrites the committed dump in `api/`.
    //
    // It matters more here than it does there. This module's whole job is to keep implementing an
    // interface that belongs to somebody else, on somebody else's release cadence, and the dump is
    // what makes an SDK bump that quietly changed a signature fail at the commit that took it
    // rather than at a consumer's.
    abiValidation()
}

java {
    withSourcesJar()
}

dependencies {
    // The reason this repository exists, and the one third-party runtime dependency it may have.
    // `api`, not `implementation`: `BetaMemoryToolHandler` is in this module's public supertype list
    // and `Optional<List<Long>>` is in a public signature, so a consumer cannot compile against the
    // handler without the SDK on their compile classpath. Hiding it would only mean they add it back
    // by hand at a version we did not choose.
    api(libs.anthropic.java)

    // Likewise `api`: `Rabosh` is a public constructor parameter. A consumer holds the store, opens
    // it and closes it — see `RaboshMemoryToolHandler`'s ownership note — so it is theirs to name.
    api(libs.rabosh.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/*
 * The two suites that are deliberately not part of `build`, each behind its own tag.
 *
 * `bench` — the listing-index benchmark. Minutes, not seconds, and it writes five thousand
 * memories to a temporary directory. Run it with `-Prabosh.memory.bench`.
 *
 * `smoke` — one real conversation against the API. It costs money, needs
 * `ANTHROPIC_API_KEY`, and exists to catch the SDK moving under us rather than to assert anything
 * about this module's logic. Run it with `-Prabosh.memory.smoke`.
 *
 * Neither is optional in the sense of "nice to have": a release runs both. They are excluded from
 * `build` because a suite that needs a network and a card should not be able to fail a commit.
 */
val runBenchmarks: Boolean = providers.gradleProperty("rabosh.memory.bench").isPresent
val runLiveSmoke: Boolean = providers.gradleProperty("rabosh.memory.smoke").isPresent

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        if (!runBenchmarks) excludeTags("bench")
        if (!runLiveSmoke) excludeTags("smoke")
    }

    // A second benchmark run with different dials is UP-TO-DATE, prints nothing, and looks exactly
    // like a run that measured something. The dials are set in `doFirst` — they have to be, for the
    // configuration cache — so they are not inputs and cannot make the task stale. Rather than
    // declare them as inputs they are not, take the task out of up-to-date checking whenever a dial
    // is present: the only reason to pass one is to want a fresh measurement.
    // Decided here rather than inside the lambda: a predicate that reads `runBenchmarks` closes over
    // the build script itself, which the configuration cache cannot serialize. `{ false }` captures
    // nothing.
    if (runBenchmarks || runLiveSmoke) outputs.upToDateWhen { false }

    // Property tests print the seed of a failing case; make sure it reaches the console.
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL

        // Off for the ordinary suite, whose chatter would bury the one failure worth reading, and on
        // behind the two dials that exist to be read: the benchmark's whole output is a table, and
        // the live smoke test's is a transcript. `TestLoggingContainer` applies this at every log
        // level, so `-i` is not a substitute — without this the benchmark prints into a void.
        showStandardStreams = runBenchmarks || runLiveSmoke
    }

    // The engine underneath maps segments off-heap via the FFM API.
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    // Forward the test dials into the test JVM. Without this a `-D` on the command line reaches only
    // the daemon, and `./gradlew test -Drabosh.memory.seed=…` — the documented way to replay a CI
    // failure exactly — silently does nothing.
    for (key in listOf(
        "rabosh.memory.seed",
        "rabosh.memory.iterations",
        "rabosh.memory.bench.memories",
        "rabosh.memory.bench.payloads",
        "rabosh.memory.bench.warmup",
        "rabosh.memory.bench.iterations",
        "rabosh.memory.bench.runs",
    )) {
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }

    // The crash instruments fork a child JVM and kill it. The child needs this module and the engine
    // on its classpath, and it cannot get them from `java.class.path`: on Windows Gradle hands a long
    // test classpath to the worker through a pathing jar, so the property would be one jar of
    // manifest entries. Resolved at execution time so configuration stays cache-friendly.
    val testRuntimeClasspath = sourceSets["test"].runtimeClasspath
    doFirst {
        systemProperty("rabosh.memory.testClasspath", testRuntimeClasspath.asPath)
        systemProperty("rabosh.memory.javaHome", javaLauncher.get().metadata.installationPath.asFile.absolutePath)
    }
}

/*
 * The crash demonstration: `CrashSafetyTest`'s rename instrument with its assertions replaced by a
 * printout, for someone deciding whether to believe the claim rather than for CI.
 *
 * A `JavaExec` rather than a tagged test, deliberately. A test that cannot fail is a misuse of the
 * word, `check` should not grow a task whose output only means something to a human reading it, and
 * this way the console gets the printout without `showStandardStreams` being involved at all.
 *
 * It forks and kills a child JVM, so it needs what the `Test` tasks above need: the resolved test
 * classpath, because on Windows `java.class.path` would be one pathing jar, and the toolchain's home
 * so the child is the same JDK. Rounds default to five; `-Drabosh.memory.demo.rounds=N` changes it.
 */
val crashDemo by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Kills a JVM mid-rename, reopens the store, and prints what survived."

    val testRuntimeClasspath = sourceSets["test"].runtimeClasspath
    classpath = testRuntimeClasspath
    mainClass = "app.oreshkov.rabosh.memory.CrashDemo"

    // The engine maps segments off-heap via the FFM API, here as in the child.
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    providers.systemProperty("rabosh.memory.demo.rounds").orNull?.let {
        systemProperty("rabosh.memory.demo.rounds", it)
    }

    // Resolved at execution time so configuration stays cache-friendly, as above.
    doFirst {
        systemProperty("rabosh.memory.testClasspath", testRuntimeClasspath.asPath)
        systemProperty("rabosh.memory.javaHome", javaLauncher.get().metadata.installationPath.asFile.absolutePath)
    }
}

/*
 * A rule enforced as a build step rather than as a review habit.
 *
 * Path normalisation is a pure string function, and the reason is in CLAUDE.md: on Windows
 * `Path.of("/memories/a")` normalises with backslashes and `resolve` applies drive-letter semantics,
 * so a key layout derived from `java.nio.file.Path` would mean different things on different
 * machines. That is a divergence no test on one platform can see, which is exactly why it is checked
 * mechanically instead of being trusted.
 *
 * The one legitimate use is the store *directory* — a real filesystem location, which is what
 * `java.nio.file.Path` is for — so the factory that opens a store is allowed it and nothing else is.
 */
val nioPathAllowlist = setOf("RaboshMemoryToolHandler.kt")

val checkNoNioPath = tasks.register("checkNoNioPath") {
    group = "verification"
    description = "Fails if a main source file outside the allowlist mentions java.nio.file.Path."

    val sources = sourceSets["main"].allSource.matching { include("**/*.kt") }
    val allowlist = nioPathAllowlist
    val report = layout.buildDirectory.file("reports/no-nio-path.txt")

    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(report)

    doLast {
        // Comment lines are stripped first. The rule is about what the code does, and the file that
        // most needs to say "java.nio.file.Path is deliberately not used here" is the very file the
        // rule is about — matching its KDoc would make explaining the rule break the rule.
        fun isComment(line: String): Boolean = line.trimStart().let {
            it.startsWith("*") || it.startsWith("//") || it.startsWith("/*")
        }

        val offenders = sources.files
            .filter { it.name !in allowlist }
            .filter { file -> file.readLines().any { !isComment(it) && "java.nio.file.Path" in it } }

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                if (offenders.isEmpty()) {
                    "no main source outside $allowlist mentions java.nio.file.Path\n"
                } else {
                    offenders.joinToString("\n") { it.path } + "\n"
                },
            )
        }

        check(offenders.isEmpty()) {
            "java.nio.file.Path is used outside the store-directory parameter, in:\n" +
                offenders.joinToString("\n") { "  ${it.path}" } +
                "\nPath normalisation is a pure string function; see MemoryPath and CONTRACT.md. " +
                "If a new file genuinely needs a filesystem location, add it to `nioPathAllowlist` " +
                "in build.gradle.kts and say why."
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoNioPath)
}

/*
 * The module name this jar answers to on the module path.
 *
 * The same reasoning as the engine's: without the attribute an automatic module is named after the
 * file, which is derived from an artefact id and is therefore unstable — a jar renamed, shaded or
 * republished under another coordinate silently becomes a different module. An attribute is
 * reversible and a `module-info.java` is not, and that asymmetry is the whole decision.
 */
tasks.named<Jar>("jar") {
    manifest {
        attributes("Automatic-Module-Name" to "app.oreshkov.rabosh.memory")
    }
}

/**
 * Dokka's HTML, packaged under the `javadoc` classifier.
 *
 * HTML rather than the Javadoc format, because the API is Kotlin: nullability, default arguments and
 * extension receivers all survive Dokka's rendering and none of them survives a Javadoc one. What a
 * repository requires is that a `javadoc` artefact exists, not which tool made it.
 */
val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
    group = "documentation"
    description = "Packages the Dokka HTML documentation for publication."
    archiveClassifier = "javadoc"
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

publishing {
    repositories {
        // Not a remote. A Central Portal deployment is one upload of one bundle, so the artefacts are
        // staged into a directory that a release zips whole — the deployment then validates whole or
        // fails whole, rather than half-publishing.
        maven {
            name = "centralStaging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }

    publications.register<MavenPublication>("maven") {
        from(components["java"])
        artifact(dokkaJavadocJar)

        pom {
            name = providers.provider { project.name }
            description = providers.provider { project.description }
            url = "https://github.com/aoreshkov/rabosh-memory"
            inceptionYear = "2026"

            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                }
            }

            developers {
                // No email address, deliberately: a POM is published forever, and an address in one
                // is a permanent decision made on someone's behalf.
                developer {
                    id = "aoreshkov"
                    name = "Atanas Oreshkov"
                    url = "https://github.com/aoreshkov"
                }
            }

            scm {
                url = "https://github.com/aoreshkov/rabosh-memory"
                connection = "scm:git:https://github.com/aoreshkov/rabosh-memory.git"
                developerConnection = "scm:git:ssh://git@github.com/aoreshkov/rabosh-memory.git"
            }

            issueManagement {
                system = "GitHub Issues"
                url = "https://github.com/aoreshkov/rabosh-memory/issues"
            }
        }
    }
}

/*
 * PGP signing, from the environment and only from the environment.
 *
 * Optional here rather than required, for the reason the engine gives: a build that demanded a key
 * would make `./gradlew build` need one, and a build that carried a keyring would put a private key
 * in a repository that is published forever. A release supplies `SIGNING_KEY`; nothing else does.
 */
signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY")
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")

    isRequired = signingKey.isPresent
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.getOrElse(""))
        sign(publishing.publications["maven"])
    }
}

/*
 * The Central deployment bundle, and the check that it is a whole release.
 *
 * The Portal is the one irreversible step in this repository's release: once published, a version
 * cannot be removed, replaced or amended. There is no second chance at 0.1.0, only a 0.1.1 — so
 * everything knowable about the bundle before it is uploaded is worth knowing before it is uploaded.
 * The Portal's own validation is the wrong place to find out, because it rejects a bundle that is
 * *malformed* and happily accepts one that is well-formed and **short**: a `javadoc` jar Dokka
 * silently failed to produce, artefacts staged at a version the tag did not name, a bundle left
 * unsigned because a release ran with the secrets unset. Each of those publishes successfully and is
 * permanent.
 *
 * So the assertion is made against the **artefact** — the zip's own entry list, read back after it is
 * written — rather than against the staging directory Gradle was asked to write or the tasks it was
 * asked to run. What is checked is what is uploaded.
 *
 * Two tasks rather than one. An archive task with no source files is skipped as NO-SOURCE and a
 * `doLast` on a skipped task does not run, so a check hung off the archive would silently stop
 * checking in exactly the case worth catching — a release where nothing was staged. `bundleForCentral`
 * is a lifecycle task that always runs and reads the archive back, so "there is no archive" is one of
 * the answers it can give rather than a way for it to be skipped.
 *
 * The engine keeps the equivalent logic in `build-logic` with unit tests, because there it decides the
 * fate of six modules and a hand-maintained list of them could disagree with `settings.gradle.kts`.
 * Here there is one module and no included build — `settings.gradle.kts` says why — so the expectation
 * below is not a list that can drift from anything: it is the five files Maven Central requires of a
 * single artefact, written out. That is the trade, and it is the reason this is short enough to read
 * rather than something needing a test of its own.
 */
val centralBundleZip = tasks.register<Zip>("centralBundleZip") {
    group = "publishing"
    description = "Zips the staged artefacts into a Central Portal deployment bundle."

    dependsOn(tasks.named("publishAllPublicationsToCentralStagingRepository"))

    from(layout.buildDirectory.dir("staging-deploy"))

    // Repository metadata is the repository's to write, not a deployment's to carry. Excluded rather
    // than tolerated — and the check below reports one anyway, so a future Gradle writing it under a
    // name this pattern misses is a failure here rather than a surprise on the Portal.
    exclude("**/maven-metadata*")

    destinationDirectory = layout.buildDirectory
    archiveFileName = "central-bundle.zip"
}

tasks.register("bundleForCentral") {
    group = "publishing"
    description = "Builds the Central deployment bundle and verifies it is a complete release."

    dependsOn(centralBundleZip)

    // Copied into locals of *this* block, which is what makes the action below serialisable: a task
    // action that reads `project` at execution time holds a reference the configuration cache cannot
    // store. `println` rather than `logger` for the same reason.
    val bundle = centralBundleZip.flatMap { it.archiveFile }
    val groupPath = project.group.toString().replace('.', '/')
    val artifactId = project.name
    val releaseVersion = project.version.toString()

    doLast {
        val file = bundle.get().asFile
        val problems = mutableListOf<String>()

        // What Central requires of a module whose packaging is not `pom`: the POM, the main jar, and
        // jars carrying sources and Javadoc. `.module` is Gradle Module Metadata — not something
        // Central asks for, but something Gradle deploys, so it is signed and checksummed like any
        // other file. The `javadoc` one is Dokka's HTML under that classifier, and it is the single
        // most likely thing to go quietly missing, because Dokka failing leaves a build that still
        // assembles.
        val prefix = "$groupPath/$artifactId/$releaseVersion/"
        val required = listOf(
            "$artifactId-$releaseVersion.pom",
            "$artifactId-$releaseVersion.module",
            "$artifactId-$releaseVersion.jar",
            "$artifactId-$releaseVersion-sources.jar",
            "$artifactId-$releaseVersion-javadoc.jar",
        )

        // `.sha256` and `.sha512` are accepted by Central and written by Gradle, and are deliberately
        // not required: demanding what is optional turns a future Gradle that stops writing them into
        // a release failure with a misleading message. The `.asc` files need no companions of their
        // own — Central states that signatures need no checksums and checksums need no signatures.
        val companions = listOf("asc", "md5", "sha1")

        if (!file.isFile) {
            problems += "${file.path}: no bundle was written, so there is nothing to upload and " +
                "nothing to check."
        } else {
            if (releaseVersion.endsWith("-SNAPSHOT")) {
                problems += "the version is $releaseVersion. Central does not accept a snapshot " +
                    "through the release path, and a tag that produced one means the version was " +
                    "not overridden for this build — `gradle.properties` is the development version " +
                    "by construction and the tag is what decides a release."
            }

            val entries = ZipFile(file).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            }

            if (entries.isEmpty()) {
                problems += "the bundle is empty. Nothing was staged, so the publish task either " +
                    "did not run or wrote somewhere else."
            } else {
                // The staging directory accumulates, so a second release built over a first without a
                // `clean` is reported here rather than silently swept away: the bundle would hold both
                // versions and publish both, permanently. A CI runner starts empty, so this is a
                // local-run message.
                val misplaced = entries.filterNot { it.startsWith(prefix) }
                if (misplaced.isNotEmpty()) {
                    problems += "holds ${misplaced.sorted().joinToString()}, outside $prefix. Every " +
                        "file in a deployment belongs to the version being released, and a bundle " +
                        "spanning two versions publishes both."
                }

                val present = entries.toSet()
                for (name in required) {
                    if (prefix + name !in present) {
                        problems += "$name is missing. Central requires sources and javadoc jars for " +
                            "any packaging other than pom, and the javadoc jar is Dokka's output."
                        continue
                    }
                    val missing = companions.filterNot { "$prefix$name.$it" in present }
                    if (missing.isNotEmpty()) {
                        problems += "$name has no ${missing.joinToString(", ") { ".$it" }}. Signing " +
                            "is skipped when no key is present, so an unsigned bundle is what a " +
                            "release run with SIGNING_KEY unset produces."
                    }
                }

                // A signature is a deployed file like any other, so Gradle writes checksums beside it
                // too -- `…​.pom.asc.sha1` and friends. Central does not ask for those and does not
                // refuse them, so they are tolerated here rather than required: the `companions` list
                // above stays the *requirement*, and this is the *permission*.
                //
                // Getting this wrong is what the first signed run found. The expectation used to be
                // built from `required` and `companions` alone, with a loose escape for anything
                // ending `.sha256` or `.sha512` -- which silently covered `.asc.sha256` while leaving
                // `.asc.md5` and `.asc.sha1` reported as files a release does not produce. Every local
                // check until then had run *unsigned*, where no `.asc` exists and neither do its
                // companions, so the one case that could fail was the one case never exercised. Hence
                // the enumeration below is total: each deployed file, its signature, and a checksum of
                // either, with no wildcard left to hide behind.
                val checksums = listOf("md5", "sha1", "sha256", "sha512")
                val expected = buildSet {
                    for (name in required) {
                        for (base in listOf(name, "$name.asc")) {
                            add(base)
                            checksums.forEach { add("$base.$it") }
                        }
                    }
                }
                val unexpected = entries
                    .filter { it.startsWith(prefix) }
                    .map { it.removePrefix(prefix) }
                    .filterNot { it in expected }
                if (unexpected.isNotEmpty()) {
                    problems += "staged ${unexpected.sorted().joinToString()}, which a release of " +
                        "this module does not produce. Publishing a file by accident is as permanent " +
                        "as publishing one on purpose."
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The Central deployment bundle is not what a release of $releaseVersion should be:" +
                    problems.joinToString("") { "\n  - $it" } +
                    "\n\nNothing has been uploaded. Central deployments are permanent once published, " +
                    "so this fails here rather than after.",
            )
        }
        println("Central bundle: ${file.path}, $releaseVersion, ${required.size} artefacts, signed.")
    }
}
