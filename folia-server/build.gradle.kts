/*
 * AzureBranches — Build
 *
 * Clones Folia ver/26.1.x from GitHub, builds it from source
 * via paperweight, then merges our classes into the output JAR.
 *
 * Credits: Luminol / Lophine by EarthMe — Maven + clone pattern
 *          Folia / Paperclip by PaperMC — server bootstrap
 */
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.time.Instant

plugins { id("java-library") }

val foliaRepo  = "https://github.com/PaperMC/Folia.git"
val foliaRef   = "ver/26.1.x"
val foliaDir   = file("build/folia-src")

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

sourceSets {
    main {
        java { srcDir("../azurebranches-common/src/main/java") }
        resources { srcDir("../azurebranches-common/src/main/resources") }
    }
}

dependencies {
    compileOnly("dev.folia:folia-api:26.1.2.build.8-stable")
    compileOnly("org.jetbrains:annotations:24.1.0")
}

// ── Helpers ──

fun sh(dir: File? = null, vararg cmd: String): Int {
    val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
    if (dir != null) pb.directory(dir)
    val p = pb.start(); p.inputStream.transferTo(System.out); return p.waitFor()
}

fun gw(dir: File): String =
    if (System.getProperty("os.name").lowercase().contains("win"))
        File(dir, "gradlew.bat").absolutePath
    else
        File(dir, "gradlew").absolutePath

fun copyDir(src: File, dst: File) {
    dst.mkdirs()
    for (f in src.listFiles()!!) {
        val t = File(dst, f.name)
        if (f.isDirectory) copyDir(f, t) else Files.copy(f.toPath(), t.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

// ── Clone Folia ──

val foliaJar = layout.buildDirectory.file("cache/folia-paperclip.jar")

tasks.register("cloneFolia") {
    outputs.dir(foliaDir)
    doLast {
        if (File(foliaDir, ".git").exists()) return@doLast
        foliaDir.parentFile.mkdirs()
        println("Cloning Folia $foliaRef ...")
        check(sh(cmd = *arrayOf("git", "clone", "--branch", foliaRef,
            "--depth", "1", foliaRepo, foliaDir.absolutePath)) == 0) { "git clone failed" }

        // Pre-seed PaperMC/Paper git cache so checkoutPaperRepo doesn't need network
        val paperCache = File(foliaDir, ".gradle/caches/paperweight/upstreams/paper")
        if (!File(paperCache, ".git").exists()) {
            println("Pre-seeding Paper git cache (may take a few minutes)...")
            paperCache.mkdirs()
            sh(cmd = *arrayOf("git", "clone", "--bare",
                "https://github.com/PaperMC/Paper.git", paperCache.absolutePath))
            println("Paper cache ready")
        }

        // Copy our patches into Folia's minecraft-patches
        val ourPatches = file("minecraft-patches/features")
        val theirPatches = File(foliaDir, "folia-server/minecraft-patches/features")
        if (ourPatches.exists() && theirPatches.exists()) {
            for (f in ourPatches.listFiles()!!.filter { it.name.startsWith("00") && it.name.contains("AzureBranches") }) {
                Files.copy(f.toPath(), File(theirPatches, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("  Copied patch: ${f.name}")
            }
        }

        // Copy our source files so Folia's build can compile the hooks
        val ourSrc = file("../azurebranches-common/src/main/java/com/azurebranches")
        val theirSrc = File(foliaDir, "folia-server/src/minecraft/java/com/azurebranches")
        if (ourSrc.exists()) {
            copyDir(ourSrc, theirSrc)
            println("  Copied source: com.azurebranches/**")
        }
    }
}

// ── Build Folia ──

tasks.register("buildFolia") {
    dependsOn("cloneFolia")
    outputs.file(foliaJar)
    doLast {
        val dest = foliaJar.get().asFile
        if (dest.exists()) { println("Folia cached (${dest.length()/1024/1024} MB)"); return@doLast }
        dest.parentFile.mkdirs()

        val g = gw(foliaDir)
        val args = arrayOf("--no-daemon", "--no-configuration-cache")

        // Step 1: applyAllPatches
        println("=== Folia step 1/2: applyAllPatches ===")
        check(sh(dir = foliaDir, cmd = *arrayOf(g, "applyAllPatches", *args)) == 0)

        // Fix paperweight remotes so offline-friendly (only needed first run)
        try {
            for (sub in listOf(".gradle/caches/paperweight/upstreams/paper",
                               ".gradle/caches/paperweight/taskCache/filterPaperApiFromPaper")) {
                val d = File(foliaDir, sub)
                if (File(d, ".git").exists()) sh(dir = d, cmd = *arrayOf("git", "remote", "set-url", "origin", "."))
            }
        } catch (_: Exception) { /* remote fix optional */ }

        // Re-copy AzureBranches sources: applyAllPatches regenerates
        // src/minecraft/java from scratch, wiping the copy made in cloneFolia.
        // They must be present for the 0009/0010 hooks to compile.
        run {
            val ourSrc = file("../azurebranches-common/src/main/java/com/azurebranches")
            val theirSrc = File(foliaDir, "folia-server/src/minecraft/java/com/azurebranches")
            if (ourSrc.exists()) {
                copyDir(ourSrc, theirSrc)
                println("  Re-copied AzureBranches sources after patches: com.azurebranches/**")
            }
        }

        // AzureBranches EXP3: apply post-patch source transformations
        // (These modify already-patched Minecraft sources without requiring
        //  additional paperweight patch files, avoiding 3-way merge issues.)
        run {
            val cmdBlockFile = File(foliaDir,
                "folia-server/src/minecraft/java/net/minecraft/world/level/block/CommandBlock.java")
            val setBlockFile = File(foliaDir,
                "folia-server/src/minecraft/java/net/minecraft/server/commands/SetBlockCommand.java")

            if (cmdBlockFile.exists()) {
                var content = cmdBlockFile.readText()

                // 1) Deterministic seed for Phase retry
                content = content.replace(
                    "final long traversalId = head.startTraversal();",
                    "final long traversalId = head.startTraversal(level.getSeed()); // EXP3 deterministic seed"
                )

                // 2) Savepoint before conditional boundaries
                content = content.replace(
                    "if (commandBlock.isConditional() && !commandBlock.markConditionMet()) {",
                    "// EXP3: savepoint for partial rollback\n" +
                    "            if (commandBlock.isConditional()\n" +
                    "                && com.azurebranches.command.PhaseValidator.isEnabled()) {\n" +
                    "                phaseSnap.createSavepoint(pos.asLong(), direction.get3DDataValue());\n" +
                    "            }\n" +
                    "            if (commandBlock.isConditional() && !commandBlock.markConditionMet()) {"
                )

                // 3) Capture readSetPositions
                content = content.replace(
                    "final long[] pendingWrites = currentPhaseSnap != null\n" +
                    "            ? currentPhaseSnap.getPendingWritePositions() : null;",
                    "final long[] pendingWrites = currentPhaseSnap != null\n" +
                    "            ? currentPhaseSnap.getPendingWritePositions() : null;\n" +
                    "        final long[] readSetPos = currentPhaseSnap != null\n" +
                    "            ? currentPhaseSnap.getReadSetPositions() : null;"
                )

                // 4) Attach readSetPositions to Continuation
                content = content.replace(
                    "cont = head.createContinuation(lastRunPos.asLong(), currentDirection.get3DDataValue(),\n" +
                    "                remaining, batchStepCount);\n" +
                    "        }\n" +
                    "        // AzureBranches end - Phase-Based snapshot",
                    "cont = head.createContinuation(lastRunPos.asLong(), currentDirection.get3DDataValue(),\n" +
                    "                remaining, batchStepCount);\n" +
                    "        }\n" +
                    "        cont.readSetPositions = readSetPos; // EXP3\n" +
                    "        // AzureBranches end - Phase-Based snapshot"
                )

                // 5) OCC validation in aggregateAndResume
                content = content.replace(
                    "final com.azurebranches.command.PhaseSnapshot nextPhaseSnap =\n" +
                    "            com.azurebranches.command.PhaseSnapshot.fromContinuation(cont, level.getGameTime());\n" +
                    "        walkExpChain(head, level, headBlock, resumePos, resumeDir, cont.remaining, nextPhaseSnap);",
                    "final com.azurebranches.command.PhaseSnapshot nextPhaseSnap =\n" +
                    "            com.azurebranches.command.PhaseSnapshot.fromContinuation(cont, level.getGameTime());\n" +
                    "        // EXP3: OCC validation\n" +
                    "        if (com.azurebranches.command.PhaseValidator.isEnabled()) {\n" +
                    "            final com.azurebranches.command.PhaseValidator.ValidationResult result =\n" +
                    "                com.azurebranches.command.PhaseValidator.validate(\n" +
                    "                    nextPhaseSnap, cont.retryCount, java.util.Map.of());\n" +
                    "            switch (result) {\n" +
                    "                case COMMIT -> com.azurebranches.command.ExpChainSupport.onValidationPassed();\n" +
                    "                case RETRY -> { com.azurebranches.command.ExpChainSupport.onValidationRetry(); cont.retryCount++; }\n" +
                    "                case RETRY_EXHAUSTED -> com.azurebranches.command.ExpChainSupport.onValidationExhausted();\n" +
                    "                case READ_SET_OVERFLOW -> {}\n" +
                    "            }\n" +
                    "        }\n" +
                    "        walkExpChain(head, level, headBlock, resumePos, resumeDir, cont.remaining, nextPhaseSnap);"
                )

                cmdBlockFile.writeText(content)
                println("  Applied EXP3 transformations to CommandBlock.java")
            }

            if (setBlockFile.exists()) {
                var content = setBlockFile.readText()
                // 6) Capture old block state for rollback
                content = content.replace(
                    "phaseSnap.putBlock(pos.asLong(), expectedState);",
                    "final BlockState oldState = level.getBlockState(pos); // EXP3 rollback\n" +
                    "                phaseSnap.putBlock(pos.asLong(), expectedState, oldState);"
                )
                setBlockFile.writeText(content)
                println("  Applied EXP3 transformation to SetBlockCommand.java")
            }
        }

        // Step 2: create runnable paperclip jar (compiles server + our hooks)
        println("=== Folia step 2/2: createPaperclipJar ===")
        // AzureBranches: patch brand & version identity before building paperclip
        val serverBuildFile = File(foliaDir, "folia-server/build.gradle.kts")
        val originalBuildContent = serverBuildFile.readText()
        val patchedBuildContent = originalBuildContent
            .replace("\"Brand-Id\" to \"papermc:folia\"", "\"Brand-Id\" to \"azurebranches\"")
            .replace("\"Brand-Name\" to \"Folia\"", "\"Brand-Name\" to \"AzureBranches\"")
            .replace("\"Specification-Title\" to \"Folia\"", "\"Specification-Title\" to \"AzureBranches\"")
        serverBuildFile.writeText(patchedBuildContent)
        println("  Patched server identity: AzureBranches")

        // Build metadata env vars for proper version & timestamp
        val now = Instant.now()
        val buildEnv = mapOf(
            "BUILD_NUMBER" to "0004",
            "BUILD_STARTED_AT" to now.toString()
        )

        val pb = ProcessBuilder(g, ":folia-server:createPaperclipJar", *args)
            .directory(foliaDir)
            .redirectErrorStream(true)
        pb.environment().putAll(buildEnv)
        val p = pb.start()
        p.inputStream.transferTo(System.out)
        val exitCode = p.waitFor()

        // Restore original build file
        serverBuildFile.writeText(originalBuildContent)
        check(exitCode == 0) { "createPaperclipJar failed with exit code $exitCode" }

        // Find paperclip JAR
        val libDirs = listOf(File(foliaDir, "build/libs"), File(foliaDir, "folia-server/build/libs"))
        val jar = libDirs.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .sortedBy { if (it.name.contains("paperclip")) 0 else 1 } // prefer paperclip over bundler
            .find { (it.name.contains("bundler") || it.name.contains("paperclip"))
                    && it.name.endsWith(".jar") && it.length() > 10_000_000 }
            ?: throw GradleException("Folia paperclip not found. Searched: $libDirs")

        Files.copy(jar.toPath(), dest.toPath())
        println("Folia built: ${dest.length()/1024/1024} MB")
    }
}

// ── Merge ──

tasks.register("mergeJar") {
    dependsOn(tasks.compileJava, "buildFolia")
    doLast {
        val src = foliaJar.get().asFile
        val classes = sourceSets.main.get().output.classesDirs.singleFile
        val dest = layout.buildDirectory.file("libs/azurebranches-server-${project.version}-EXP3.jar").get().asFile
        dest.parentFile.mkdirs()
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val pb = ProcessBuilder("jar", "uf", dest.absolutePath, "-C", classes.absolutePath, ".").inheritIO()
        pb.start().waitFor()
        println("Done: ${dest.name} (${dest.length()/1024/1024} MB)")
        println("Run: java -jar ${dest.name}")
    }
}

tasks.build { dependsOn("mergeJar") }
