plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
}

allprojects {
    version = "1.0.4"
}

/**
 * Guards the sources against encoding corruption (UTF-8 -> CP1251 -> UTF-8
 * re-transcoding), which turned every Russian string into "е"/"�"-style
 * garbage. Any source file containing such artifacts fails the build with a
 * clear message instead of shipping a UI with unreadable text.
 */
val checkEncoding by tasks.registering {
    group = "verification"
    description = "Fails the build if source files were corrupted by encoding re-transcoding."
    doLast {
        // CP1251-looking artifacts produced by reading UTF-8 bytes as CP1251:
        // е, т, �, �, и (built from code points � no literal Cyrillic here).
        val artifacts = listOf(
            "\u0420\u00B5",   // е
            "\u0421\u201A",   // т
            "\u0412\u0402",   // �
            "\u0412\u2020",   // �
            "\u0420\u0456",   // и
            "\u0420\u045C",   // М
        )
        val bad = mutableListOf<String>()
        listOf("apps", "core", "features", "docs").forEach { rootName ->
            val root = file(rootName)
            if (!root.isDirectory) return@forEach
            root.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "sq", "kts", "md", "xml", "properties", "toml") }
                .forEach { file ->
                    val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                    if (text == null) {
                        bad += file.path + " (invalid UTF-8)"
                    } else if (artifacts.any { text.contains(it) }) {
                        bad += file.path
                    }
                }
        }
        if (bad.isNotEmpty()) {
            println("ENCODING CHECK BAD FILES:")
            bad.forEach { println(it) }
            throw GradleException(
                "���������� ����������� ��������� ���������� (������������� UTF-8\u2192CP1251\u2192UTF-8) " +
                    "� ��������� ������:\n" + bad.joinToString("\n") +
                    "\n������������ �� (encode CP1251 \u2192 decode UTF-8) � ������������.",
            )
        }
    }
}

gradle.projectsEvaluated {
    rootProject.subprojects.forEach { sub ->
        val buildTask = sub.tasks.findByName("build")
        if (buildTask != null) {
            buildTask.dependsOn(checkEncoding)
        }
    }
}
