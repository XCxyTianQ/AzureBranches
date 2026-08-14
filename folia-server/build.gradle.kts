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

/**
 * Applies (anchor -> replacement) steps to a generated Minecraft source file.
 *
 * - Normalizes CRLF -> LF before matching and restores the original line
 *   ending style afterwards, so multi-line anchors match on any checkout.
 * - Fails the build when an anchor is missing or occurs more than once,
 *   instead of silently doing nothing.
 */
fun transformSource(file: File, what: String, vararg steps: Pair<String, String>) {
    if (!file.exists()) {
        throw GradleException("AzureBranches source transformation FAILED in $what: file not found: ${file.absolutePath}")
    }
    var content = file.readText()
    val crlf = content.contains("\r\n")
    if (crlf) {
        content = content.replace("\r\n", "\n")
    }
    for ((anchor, replacement) in steps) {
        val occurrences = content.split(anchor).size - 1
        if (occurrences != 1) {
            throw GradleException(
                "AzureBranches source transformation FAILED in $what: " +
                "anchor must occur exactly once (found $occurrences):\n---\n$anchor\n---"
            )
        }
        content = content.replace(anchor, replacement)
    }
    if (crlf) {
        content = content.replace("\n", "\r\n")
    }
    file.writeText(content)
    println("  Applied transformations to $what")
}

// ── Clone Folia ──

val foliaJar = layout.buildDirectory.file("cache/folia-paperclip.jar")

/**
 * Sync our AzureBranches patches and sources into the cloned Folia tree.
 *
 * Must run on every build: paperweight reads patches from
 * folia-src/folia-server/minecraft-patches, and edits to
 * minecraft-patches/features/ (or azurebranches-common/) must be picked up
 * even when the clone itself is already present. Idempotent and cheap
 * (a few small file copies).
 */
fun syncAzureSources() {
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

tasks.register("cloneFolia") {
    // No outputs declared on purpose: this task runs on every build so that
    // patch/source edits are always synced (see syncAzureSources). The git
    // clone itself happens only once, guarded by the .git check below.
    doLast {
        if (File(foliaDir, ".git").exists()) {
            syncAzureSources()
            return@doLast
        }
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

        syncAzureSources()
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

        // AzureBranches EXP3/EXP4: apply post-patch source transformations.
        // These modify already-patched Minecraft sources without requiring
        // additional paperweight patch files, avoiding 3-way merge issues.
        // transformSource() normalizes CRLF/LF before matching and FAILS the
        // build when an anchor is missing or ambiguous, so drifting upstream
        // code can never silently drop a hook again.
        run {
            val minecraftSrc = File(foliaDir, "folia-server/src/minecraft/java")

            // EXP3 hooks in CommandBlock.java:
            //   1) Deterministic seed for Phase retry
            //   2) Savepoint before conditional boundaries
            //   3) Capture readSetPositions
            //   4) Attach readSetPositions to the Continuation
            //   5) OCC validation in aggregateAndResume
            val cmdBlockFile = File(minecraftSrc,
                "net/minecraft/world/level/block/CommandBlock.java")
            transformSource(cmdBlockFile, "CommandBlock.java (EXP3 hooks)",
                "final long traversalId = head.startTraversal();" to
                    "final long traversalId = head.startTraversal(level.getSeed()); // EXP3 deterministic seed",

                "if (commandBlock.isConditional() && !commandBlock.markConditionMet()) {" to
                    "// EXP3: savepoint for partial rollback\n" +
                    "            if (commandBlock.isConditional()\n" +
                    "                && com.azurebranches.command.PhaseValidator.isEnabled()) {\n" +
                    "                phaseSnap.createSavepoint(pos.asLong(), direction.get3DDataValue());\n" +
                    "            }\n" +
                    "            if (commandBlock.isConditional() && !commandBlock.markConditionMet()) {",

                "final long[] pendingWrites = currentPhaseSnap != null\n" +
                    "            ? currentPhaseSnap.getPendingWritePositions() : null;" to
                    "final long[] pendingWrites = currentPhaseSnap != null\n" +
                    "            ? currentPhaseSnap.getPendingWritePositions() : null;\n" +
                    "        final long[] readSetPos = currentPhaseSnap != null\n" +
                    "            ? currentPhaseSnap.getReadSetPositions() : null;",

                "cont = head.createContinuation(lastRunPos.asLong(), currentDirection.get3DDataValue(),\n" +
                    "                remaining, batchStepCount);\n" +
                    "        }\n" +
                    "        // EXP4: attach the captured pre-write old states and the Phase start\n" +
                    "        // so an OCC conflict can restore the world and replay the whole Phase.\n" +
                    "        cont.oldStateCapture = oldStateCapture;\n" +
                    "        cont.phaseStartPos = phaseStartPos.asLong();\n" +
                    "        cont.phaseStartDir = phaseStartDir.get3DDataValue();\n" +
                    "        // AzureBranches end - Phase-Based snapshot" to
                    "cont = head.createContinuation(lastRunPos.asLong(), currentDirection.get3DDataValue(),\n" +
                    "                remaining, batchStepCount);\n" +
                    "        }\n" +
                    "        cont.readSetPositions = readSetPos; // EXP3\n" +
                    "        // EXP4: attach the captured pre-write old states and the Phase start\n" +
                    "        // so an OCC conflict can restore the world and replay the whole Phase.\n" +
                    "        cont.oldStateCapture = oldStateCapture;\n" +
                    "        cont.phaseStartPos = phaseStartPos.asLong();\n" +
                    "        cont.phaseStartDir = phaseStartDir.get3DDataValue();\n" +
                    "        // AzureBranches end - Phase-Based snapshot",

                "final com.azurebranches.command.PhaseSnapshot nextPhaseSnap =\n" +
                    "            com.azurebranches.command.PhaseSnapshot.fromContinuation(cont, level.getGameTime());\n" +
                    "        walkExpChain(head, level, headBlock, resumePos, resumeDir, cont.remaining, nextPhaseSnap);" to
                    "final com.azurebranches.command.PhaseSnapshot nextPhaseSnap =\n" +
                    "            com.azurebranches.command.PhaseSnapshot.fromContinuation(cont, level.getGameTime());\n" +
                    "        verifyReadSetAndResume(head, level, headBlock, cont, resumePos, resumeDir, nextPhaseSnap);",

                // EXP4: OCC rollback & retry — restore pre-write old states on the
                // target regions, then replay the whole Phase from its start.
                "    // AzureBranches end - EXP suspendable chain implementation (v2)" to
                    """    // AzureBranches start - EXP4: OCC rollback & retry (wired by build script)
    private static void rollbackAndRetryExpChain(
        final com.azurebranches.command.ChainHead head,
        final ServerLevel level, final BlockPos headBlock,
        final com.azurebranches.command.Continuation cont,
        final com.azurebranches.command.PhaseSnapshot phaseSnap) {

        // EXP5 P0#2: compensate scoreboard and entity-NBT writes before restoring
        // blocks and replaying, so the replay does not double-apply them. The
        // compensate methods are best-effort and log failures internally.
        try {
            com.azurebranches.command.ScoreLayer.compensate(phaseSnap,
                (objective, holder) -> level.getScoreboard().getOrCreatePlayerScore(
                    net.minecraft.world.scores.ScoreHolder.forNameOnly(holder),
                    level.getScoreboard().getObjective(objective)).get(),
                (objective, holder, value) -> level.getScoreboard().getOrCreatePlayerScore(
                    net.minecraft.world.scores.ScoreHolder.forNameOnly(holder),
                    level.getScoreboard().getObjective(objective)).set(value));
        } catch (final Throwable t) {
            System.err.println("[AzureBranches] score compensation wiring failed: " + t.getMessage());
        }
        try {
            com.azurebranches.command.EntityLayer.compensate(phaseSnap,
                (entityId, path) -> readEntityNbtPath(level, entityId, path),
                (entityId, path, value) -> writeEntityNbtPath(level, entityId, path, value));
        } catch (final Throwable t) {
            System.err.println("[AzureBranches] entity-NBT compensation wiring failed: " + t.getMessage());
        }

        final java.util.Map<Long, Object> oldStates = phaseSnap.getOldBlockStates();
        if (oldStates == null || oldStates.isEmpty()) {
            // Nothing to roll back — retry immediately from the Phase start
            retryExpChainPhase(head, level, headBlock, cont, phaseSnap);
            return;
        }

        // Group old states by target region (restoration must run on each
        // region's own thread).
        final java.util.Map<Long, java.util.Map<BlockPos,
            net.minecraft.world.level.block.state.BlockState>> byRegion = new java.util.HashMap<>();
        for (final java.util.Map.Entry<Long, Object> e : oldStates.entrySet()) {
            if (!(e.getValue() instanceof final net.minecraft.world.level.block.state.BlockState bs)) {
                continue;
            }
            final BlockPos pos = BlockPos.of(e.getKey());
            final int cx = pos.getX() >> 4;
            final int cz = pos.getZ() >> 4;
            byRegion.computeIfAbsent(((long) cx << 32) | (cz & 0xFFFF_FFFFL),
                k -> new java.util.HashMap<>()).put(pos, bs);
        }

        final java.util.List<java.util.concurrent.CompletableFuture<Void>> regionFutures =
            new java.util.ArrayList<>(byRegion.size());
        for (final java.util.Map.Entry<Long,
             java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState>> e
             : byRegion.entrySet()) {
            final int targetCx = (int) (e.getKey() >> 32);
            final int targetCz = (int) (e.getKey() & 0xFFFF_FFFFL);
            final java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> restores =
                e.getValue();
            final java.util.concurrent.CompletableFuture<Void> regionDone =
                new java.util.concurrent.CompletableFuture<>();
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue
                .queueOrExecuteTickTask(level, targetCx, targetCz, () -> {
                    try {
                        for (final java.util.Map.Entry<BlockPos,
                             net.minecraft.world.level.block.state.BlockState> r
                             : restores.entrySet()) {
                            try {
                                level.setBlock(r.getKey(), r.getValue(), 3);
                            } catch (final Exception restoreEx) {
                                // EXP4: a failed restore leaves the world partially
                                // rolled back — log it so it is never silent.
                                System.err.println("[AzureBranches] rollback restore failed at "
                                    + r.getKey() + ": " + restoreEx.getMessage());
                            }
                        }
                    } finally {
                        regionDone.complete(null);
                    }
                });
            regionFutures.add(regionDone);
        }

        java.util.concurrent.CompletableFuture.allOf(
            regionFutures.toArray(new java.util.concurrent.CompletableFuture[0]))
            .whenComplete((v, ex) ->
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue
                    .queueOrExecuteTickTask(level, headBlock.getX() >> 4, headBlock.getZ() >> 4, () -> {
                        if (!head.isCurrent(cont)) {
                            com.azurebranches.command.ExpChainSupport.onSupersede();
                            return;
                        }
                        retryExpChainPhase(head, level, headBlock, cont, phaseSnap);
                    }));
    }

    private static void retryExpChainPhase(
        final com.azurebranches.command.ChainHead head,
        final ServerLevel level, final BlockPos headBlock,
        final com.azurebranches.command.Continuation cont,
        final com.azurebranches.command.PhaseSnapshot phaseSnap) {

        // The world has been restored to its pre-Phase state — replay the
        // whole Phase from its start, re-reading the world.
        phaseSnap.resetForRetry();
        final BlockPos.MutableBlockPos retryPos = BlockPos.of(cont.phaseStartPos).mutable();
        final Direction retryDir = Direction.from3DDataValue(cont.phaseStartDir);
        walkExpChain(head, level, headBlock, retryPos, retryDir,
            cont.remaining + cont.stepCount, phaseSnap);
    }

    // EXP5 P0#2: entity-NBT path read/write helpers for EntityLayer compensation.
    private static Object readEntityNbtPath(final ServerLevel level, final int entityId, final String path) {
        final net.minecraft.world.entity.Entity entity = level.getEntity(entityId);
        if (entity == null) {
            return null;
        }
        final net.minecraft.nbt.CompoundTag tag =
            net.minecraft.advancements.criterion.NbtPredicate.getEntityTagToCompare(entity);
        final int bracket = path.indexOf('[');
        final String name = bracket >= 0 ? path.substring(0, bracket) : path;
        final net.minecraft.nbt.Tag value;
        if (bracket >= 0) {
            final int close = path.indexOf(']', bracket);
            final int idx = Integer.parseInt(path.substring(bracket + 1, close));
            final net.minecraft.nbt.Tag listTag = tag.get(name);
            value = (listTag instanceof net.minecraft.nbt.ListTag list) ? list.get(idx) : null;
        } else {
            value = tag.get(name);
        }
        if (value instanceof net.minecraft.nbt.NumericTag num) {
            return num.box();
        }
        if (value instanceof net.minecraft.nbt.StringTag str) {
            return str.asString().orElse(null);
        }
        return value;
    }

    private static void writeEntityNbtPath(final ServerLevel level, final int entityId, final String path, final Object value) {
        final net.minecraft.world.entity.Entity entity = level.getEntity(entityId);
        if (entity == null) {
            return;
        }
        final net.minecraft.nbt.CompoundTag tag =
            net.minecraft.advancements.criterion.NbtPredicate.getEntityTagToCompare(entity);
        final net.minecraft.nbt.Tag converted = toNbtTag(value);
        final int bracket = path.indexOf('[');
        final String name = bracket >= 0 ? path.substring(0, bracket) : path;
        if (bracket >= 0) {
            final int close = path.indexOf(']', bracket);
            final int idx = Integer.parseInt(path.substring(bracket + 1, close));
            final net.minecraft.nbt.Tag listTag = tag.get(name);
            if (listTag instanceof net.minecraft.nbt.ListTag list) {
                list.set(idx, converted);
            }
        } else {
            tag.put(name, converted);
        }
        try (net.minecraft.util.ProblemReporter.ScopedCollector reporter =
                 new net.minecraft.util.ProblemReporter.ScopedCollector(entity.problemPath(), com.mojang.logging.LogUtils.getLogger())) {
            entity.load(net.minecraft.world.level.storage.TagValueInput.create(reporter, entity.registryAccess(), tag));
        }
    }

    private static net.minecraft.nbt.Tag toNbtTag(final Object value) {
        if (value instanceof Number num) {
            if (value instanceof Double || value instanceof Float) {
                return net.minecraft.nbt.DoubleTag.valueOf(num.doubleValue());
            }
            return net.minecraft.nbt.IntTag.valueOf(num.intValue());
        }
        if (value instanceof String s) {
            return net.minecraft.nbt.StringTag.valueOf(s);
        }
        return (value instanceof net.minecraft.nbt.Tag t) ? t : net.minecraft.nbt.StringTag.valueOf(String.valueOf(value));
    }

    // EXP5 P0#2: execute deferred entity-lifecycle actions at Phase commit.
    private static void commitDeferredActions(final ServerLevel level, final com.azurebranches.command.PhaseSnapshot phaseSnap) {
        if (!phaseSnap.hasDeferredActions()) {
            return;
        }
        for (final com.azurebranches.command.DeferredAction action : phaseSnap.getDeferredActions()) {
            if (action.type == com.azurebranches.command.DeferredAction.ActionType.KILL
                || action.type == com.azurebranches.command.DeferredAction.ActionType.TP) {
                final net.minecraft.world.entity.Entity entity = level.getEntity(action.entityId);
                if (entity != null) {
                    entity.getBukkitEntity().taskScheduler.scheduleOrExecute((net.minecraft.world.entity.Entity scheduled) -> {
                        if (action.type == com.azurebranches.command.DeferredAction.ActionType.KILL) {
                            scheduled.kill(level);
                        } else {
                            scheduled.teleportTo(action.tpX(), action.tpY(), action.tpZ());
                        }
                    });
                }
            }
            // SUMMON requires EntityType + the PhaseSnapshot NBT cache; not yet wired.
        }
        phaseSnap.clearDeferredActions();
    }

    // EXP4Plus: verify the Phase read-set against the live world on each
    // owning region, then commit or rollback + replay. This replaces the
    // previous empty-read-set validate() call, so an external write to a
    // position we read now actually triggers an OCC retry.
    private static void verifyReadSetAndResume(
        final com.azurebranches.command.ChainHead head,
        final ServerLevel level, final BlockPos headBlock,
        final com.azurebranches.command.Continuation cont,
        final BlockPos.MutableBlockPos resumePos, final Direction resumeDir,
        final com.azurebranches.command.PhaseSnapshot phaseSnap) {

        if (!com.azurebranches.command.PhaseValidator.isEnabled()) {
            commitDeferredActions(level, phaseSnap);
            walkExpChain(head, level, headBlock, resumePos, resumeDir, cont.remaining, phaseSnap);
            return;
        }

        final java.util.Map<Long, Object> readValues = phaseSnap.getReadSetValues();
        if (readValues == null || readValues.isEmpty()) {
            com.azurebranches.command.ExpChainSupport.onValidationPassed();
            commitDeferredActions(level, phaseSnap);
            walkExpChain(head, level, headBlock, resumePos, resumeDir, cont.remaining, phaseSnap);
            return;
        }

        final java.util.Map<Long, Boolean> modified = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.List<java.util.concurrent.CompletableFuture<Void>> checks =
            new java.util.ArrayList<>(readValues.size());
        for (final java.util.Map.Entry<Long, Object> entry : readValues.entrySet()) {
            final BlockPos checkPos = BlockPos.of(entry.getKey());
            final Object expected = entry.getValue();
            final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue
                .queueOrExecuteTickTask(level, checkPos.getX() >> 4, checkPos.getZ() >> 4, () -> {
                    try {
                        final BlockState current = level.getBlockState(checkPos);
                        modified.put(entry.getKey(), !java.util.Objects.equals(current, expected));
                    } finally {
                        done.complete(null);
                    }
                });
            checks.add(done);
        }

        java.util.concurrent.CompletableFuture.allOf(
            checks.toArray(new java.util.concurrent.CompletableFuture[0]))
            .whenComplete((v, ex) ->
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue
                    .queueOrExecuteTickTask(level, headBlock.getX() >> 4, headBlock.getZ() >> 4, () -> {
                        if (!head.isCurrent(cont)) {
                            com.azurebranches.command.ExpChainSupport.onSupersede();
                            return;
                        }
                        final com.azurebranches.command.PhaseValidator.ValidationResult result =
                            com.azurebranches.command.PhaseValidator.validate(phaseSnap, cont.retryCount, modified);
                        switch (result) {
                            case COMMIT -> {
                                com.azurebranches.command.ExpChainSupport.onValidationPassed();
                                commitDeferredActions(level, phaseSnap);
                                walkExpChain(head, level, headBlock, resumePos, resumeDir, cont.remaining, phaseSnap);
                            }
                            case RETRY -> {
                                com.azurebranches.command.ExpChainSupport.onValidationRetry();
                                cont.retryCount++;
                                head.setCarryRetryCount(cont.retryCount);
                                rollbackAndRetryExpChain(head, level, headBlock, cont, phaseSnap);
                            }
                            case RETRY_EXHAUSTED -> {
                                com.azurebranches.command.ExpChainSupport.onValidationExhausted();
                                commitDeferredActions(level, phaseSnap);
                                walkExpChain(head, level, headBlock, resumePos, resumeDir, cont.remaining, phaseSnap);
                            }
                            case READ_SET_OVERFLOW -> {
                                commitDeferredActions(level, phaseSnap);
                                walkExpChain(head, level, headBlock, resumePos, resumeDir, cont.remaining, phaseSnap);
                            }
                        }
                    }));
    }
    // AzureBranches end - EXP suspendable chain implementation (v2)"""
            )

            // EXP4: SetBlockCommand write-through is now handled by the data pool
            // interception below (patch 0015 still caches the expected states).
            val setBlockFile = File(minecraftSrc,
                "net/minecraft/server/commands/SetBlockCommand.java")
            if (setBlockFile.exists()) {
                println("  EXP4: SetBlockCommand relies on data pool interception (no per-command patch needed)")
            }

            // EXP4: intercept Level.getBlockState() for a transparent PhaseSnapshot
            // cache plus OCC read-set recording (covers all block read/write commands).
            // The anchor matches the current Paper-inlined else branch of getBlockState.
            val levelFile = File(minecraftSrc, "net/minecraft/world/level/Level.java")
            transformSource(levelFile, "Level.java (EXP4 data pool interception)",
                "        } else {\n" +
                    "            ChunkAccess chunk = this.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, true); // Paper - manually inline to reduce hops and avoid unnecessary null check to reduce total byte code size, this should never return null and if it does we will see it the next line but the real stack trace will matter in the chunk engine\n" +
                    "            return chunk.getBlockState(pos);\n" +
                    "        }" to
                    "        } else {\n" +
                    "            // AzureBranches EXP4: PhaseSnapshot cache interception\n" +
                    "            final com.azurebranches.command.PhaseSnapshot _exp4snap =\n" +
                    "                com.azurebranches.command.ExpChainSupport.getPhaseSnapshot();\n" +
                    "            if (_exp4snap != null) {\n" +
                    "                final Object _cached = _exp4snap.getCached(pos.asLong());\n" +
                    "                if (_cached != null) {\n" +
                    "                    com.azurebranches.command.ExpChainSupport.onDataInterceptBlockRead();\n" +
                    "                    return (BlockState) _cached;\n" +
                    "                }\n" +
                    "            }\n" +
                    "            ChunkAccess chunk = this.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, true); // Paper - manually inline to reduce hops and avoid unnecessary null check to reduce total byte code size, this should never return null and if it does we will see it the next line but the real stack trace will matter in the chunk engine\n" +
                    "            BlockState result = chunk.getBlockState(pos);\n" +
                    "            // AzureBranches EXP4Plus: record read with observed state for OCC validation\n" +
                    "            if (_exp4snap != null) {\n" +
                    "                _exp4snap.recordRead(pos.asLong(), result,\n" +
                    "                    ((net.minecraft.server.level.ServerLevel) this).getGameTime());\n" +
                    "            }\n" +
                    "            return result;\n" +
                    "        }"
            )

            // EXP5 P1: fix Folia's broken reload executor so /reload and /datapack
            // can be restored. Folia removed the server-thread Executor (execute()
            // throws UnsupportedOperationException), but reloadResources still passes
            // `this` as the Executor and mutates global state on a non-main thread.
            val serverFile = File(minecraftSrc, "net/minecraft/server/MinecraftServer.java")
            transformSource(serverFile, "MinecraftServer.java (EXP5 P1 reload executor)",
                "                    .collect(ImmutableList.toImmutableList()),\n" +
                    "                this\n" +
                    "            )" to
                    "                    .collect(ImmutableList.toImmutableList()),\n" +
                    "                this.executor\n" +
                    "            )",

                "            .thenAcceptAsync(newResources -> {" to
                    "            .thenCompose(newResources -> this.runOnGlobalTick(() -> {",

                "            }, this);\n" +
                    "        if (this.isSameThread()) {" to
                    "            }));\n" +
                    "        if (this.isSameThread()) {",

                "    public static WorldDataConfiguration configurePackRepository(" to
                    "    // AzureBranches EXP5 P1: run a task on the global tick thread and\n" +
                    "    // expose it as a CompletableFuture, so reloadResources applies global\n" +
                    "    // state changes on the main thread (Folia removed the server executor).\n" +
                    "    private CompletableFuture<Void> runOnGlobalTick(final Runnable task) {\n" +
                    "        final CompletableFuture<Void> future = new CompletableFuture<>();\n" +
                    "        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {\n" +
                    "            try {\n" +
                    "                task.run();\n" +
                    "                future.complete(null);\n" +
                    "            } catch (final Throwable t) {\n" +
                    "                future.completeExceptionally(t);\n" +
                    "            }\n" +
                    "        });\n" +
                    "        return future;\n" +
                    "    }\n" +
                    "\n" +
                    "    public static WorldDataConfiguration configurePackRepository("
            )
        }

        // AzureBranches: overlay modified/added Minecraft sources after
        // applyAllPatches + transformSource. These files (RegionCommandExecutor,
        // region-aware /data, /tag, /trigger and their accessors) cannot live in
        // azurebranches-common (they import Minecraft internals) and are applied
        // as full-file overlays instead of patches.
        val overrideSrc = file("azurepatches-src")
        if (overrideSrc.exists()) {
            // Fail fast if an overlay file has no counterpart in the freshly
            // generated sources — otherwise an upstream layout change would be
            // silently masked by our old snapshot.
            val generatedMinecraftSrc = File(foliaDir, "folia-server/src/minecraft/java")
            overrideSrc.walkTopDown().filter { it.isFile }.forEach { f ->
                val target = File(generatedMinecraftSrc, f.relativeTo(overrideSrc).path)
                if (!target.exists()) {
                    throw GradleException(
                        "azurepatches-src overlay has no counterpart in generated sources: " +
                            f.relativeTo(overrideSrc).path
                    )
                }
            }
            copyDir(overrideSrc, generatedMinecraftSrc)
            println("  Overlaid azurepatches-src/**")
        }

        // AzureBranches: brand-new Minecraft classes (no counterpart in the
        // generated sources) live under azurepatches-new.
        val newSrc = file("azurepatches-new")
        if (newSrc.exists()) {
            copyDir(newSrc, File(foliaDir, "folia-server/src/minecraft/java"))
            println("  Added azurepatches-new/**")
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
            "BUILD_NUMBER" to "0005",
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
        val dest = layout.buildDirectory.file("libs/azurebranches-server-${project.version}-EXP5Plus.jar").get().asFile
        dest.parentFile.mkdirs()
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val pb = ProcessBuilder("jar", "uf", dest.absolutePath, "-C", classes.absolutePath, ".").inheritIO()
        pb.start().waitFor()
        println("Done: ${dest.name} (${dest.length()/1024/1024} MB)")
        println("Run: java -jar ${dest.name}")
    }
}

tasks.build { dependsOn("mergeJar") }
