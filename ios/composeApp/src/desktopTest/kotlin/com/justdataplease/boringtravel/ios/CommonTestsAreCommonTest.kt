package com.justdataplease.boringtravel.ios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The tests in src/commonTest also run on the iPhone Simulator (`:composeApp:iosSimulatorArm64Test`, on the Mac), which
 * Windows can't compile: this checks they name nothing that only the desktop's JVM has (Java, JUnit, a blocking
 * coroutine runner), so a mistake shows here before it costs a Mac run.
 */
class CommonTestsAreCommonTest {
    private val forbidden = listOf(
        Regex("""^\s*import\s+java\."""), Regex("""^\s*import\s+javax\."""), Regex("""^\s*import\s+org\.junit"""),
        Regex("""\bjava\.(io|util|time|net|nio|lang|text|security)\."""), Regex("""\bString\.format\("""), Regex("""\bSystem\.(currentTimeMillis|nanoTime|getProperty|out|err)\b"""),
        Regex("""\bThread\."""), Regex("""\brunBlocking\b"""), Regex("""@JvmStatic|@JvmField|@Throws\("""),
    )

    @Test
    fun theSharedTestsNameNothingOfTheJvm() {
        val root = File("src/commonTest/kotlin")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 5, "src/commonTest has its tests: ${files.size}")
        val found = files.flatMap { f ->
            f.readLines().withIndex().filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && forbidden.any { it.containsMatchIn(line) } }
                .map { (i, line) -> "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" }
        }
        assertTrue(found.isEmpty(), "JVM-only names in the shared tests:\n" + found.joinToString("\n"))
    }
}
