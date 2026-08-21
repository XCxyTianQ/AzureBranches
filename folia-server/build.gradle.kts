/*
 * AzureBranches 鈥?Build
 *
 * Clones Folia ver/26.1.x from GitHub, builds it from source
 * via paperweight, then merges our classes into the output JAR.
 *
 * Credits: Luminol / Lophine by EarthMe 鈥?Maven + clone pattern
 *          Folia / Paperclip by PaperMC 鈥?server bootstrap
 */
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.time.Instant

plugins { id("java-library") }

val foliaRepo  = "https://github.com/PaperMC/Folia.git"
// Pin the exact upstream commit the AzureBranches patches and
// transformSource anchors were generated against. Cloning the moving
// ver/26.1.x branch head breaks patch application in CI (upstream context
// drift + no history in a depth-1 clone for the 3-way fallback). Bump this
// deliberately together with a patch/anchors re-base.
val foliaRef   = "62dc0f257a4f5de1ef2eae8cf1627156a769c67f"
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

// 鈹€鈹€ Helpers 鈹€鈹€

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

// 鈹€鈹€ Clone Folia 鈹€鈹€

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
        check(sh(cmd = *arrayOf("git", "init", foliaDir.absolutePath)) == 0) { "git init failed" }
        check(sh(dir = foliaDir, cmd = *arrayOf("git", "remote", "add", "origin", foliaRepo)) == 0) { "git remote add failed" }
        check(sh(dir = foliaDir, cmd = *arrayOf("git", "fetch", "--depth", "1", "origin", foliaRef)) == 0) {
            "git fetch $foliaRef failed"
        }
        check(sh(dir = foliaDir, cmd = *arrayOf("git", "checkout", "--detach", "FETCH_HEAD")) == 0) { "git checkout failed" }

        // NOTE: we intentionally do NOT pre-seed the PaperMC/Paper cache here.
        // A fire-and-forget `git clone --bare` (unchecked exit code) silently
        // produced a broken cache on CI, which poisoned paperweight's
        // setupMacheSources build-cache entry: every subsequent run restored
        // that polluted mache state and the Folia feature patches failed to
        // apply ("sha1 information is lacking or useless", patch 0014). Let
        // paperweight's own checkoutPaperRepo clone Paper with its own
        // verification instead.

        syncAzureSources()
    }
}

// 鈹€鈹€ Build Folia 鈹€鈹€

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

                // EXP6Plus: cross-Phase carries must be captured AFTER the
                // suspend barrier 鈥?the global-tick score write lands in the
                // PhaseSnapshot during the suspend window, so copying at
                // dispatch time would see an empty write state.
                "                        head.removeContinuation(cont);\n" +
                    "                        aggregateAndResume(head, level, headBlock, cont, firstBatchPos, batchRegistry);" to
                    "                        // EXP6Plus: capture cross-Phase carries after the barrier\n" +
                    "                        cont.entityReadCarry = currentPhaseSnap != null\n" +
                    "                            ? new java.util.HashMap<>(currentPhaseSnap.getEntityReadSetValues()) : null;\n" +
                    "                        cont.oldScoreValuesCarry = currentPhaseSnap != null\n" +
                    "                            ? new java.util.HashMap<>(currentPhaseSnap.getOldScoreValues()) : null;\n" +
                    "                        cont.scoreCacheCarry = currentPhaseSnap != null\n" +
                    "                            ? new java.util.HashMap<>(currentPhaseSnap.getScoreCache()) : null;\n" +
                    "                        head.removeContinuation(cont);\n" +
                    "                        aggregateAndResume(head, level, headBlock, cont, firstBatchPos, batchRegistry);",

                // EXP4: OCC rollback & retry 鈥?restore pre-write old states on the
                // target regions, then replay the whole Phase from its start.
                "    // AzureBranches end - EXP suspendable chain implementation (v2)" to
                    """    // AzureBranches start - EXP4: OCC rollback & retry (wired by build script)
    private static void rollbackAndRetryExpChain(
        final com.azurebranches.command.ChainHead head,
        final ServerLevel level, final BlockPos headBlock,
        final com.azurebranches.command.Continuation cont,
        final com.azurebranches.command.PhaseSnapshot phaseSnap) {

        // EXP5Plus P2: scoreboard compensation must run on the global tick
        // thread (scoreboard is server-global data). EXP6: entity-NBT
        // compensation must run per-entity on each entity's OWNING REGION
        // thread 鈥?this fixes the P2 latent bug where it ran on the global
        // tick thread. Run all compensation async first, then chain block
        // restore + replay. Compensation is best-effort and logs failures
        // internally.
        final java.util.List<java.util.concurrent.CompletableFuture<Void>> compensationFutures =
            new java.util.ArrayList<>(4);
        // EXP6Plus: ghost-score guard 鈥?collect the scoreboard identities of
        // entities that already vanished, so score compensation never
        // recreates a score entry for a dead entity (getOrCreatePlayerScore
        // would silently resurrect a ghost holder).
        final java.util.Set<String> deadHolderNames = new java.util.HashSet<>();
        for (final java.util.Map.Entry<Integer, String> e : phaseSnap.getEntityReadSetValues().entrySet()) {
            final net.minecraft.world.entity.Entity ghostCheck = level.getEntity(e.getKey());
            // !isAlive() covers the death-animation window: the entity is
            // dead but not yet discarded, so level.getEntity still returns it.
            if (ghostCheck == null || !ghostCheck.isAlive()) {
                deadHolderNames.add(e.getValue());
            }
        }
        compensationFutures.add(
            net.minecraft.server.commands.RegionCommandExecutor.<Void>onGlobalAsync(() -> {
            try {
                com.azurebranches.command.ScoreLayer.compensate(phaseSnap,
                    (objective, holder) -> level.getScoreboard().getOrCreatePlayerScore(
                        net.minecraft.world.scores.ScoreHolder.forNameOnly(holder),
                        level.getScoreboard().getObjective(objective)).get(),
                    (objective, holder, value) -> {
                        if (deadHolderNames.contains(holder)) {
                            // EXP6Plus ghost-score guard: the holder's entity
                            // died. Folia disabled Scoreboard.entityRemoved
                            // (ServerLevel comments the call out), so dead
                            // entities' score entries survive on their own.
                            // Emulate the vanilla clear instead of writing a
                            // value back 鈥?never resurrect or refresh a ghost.
                            com.azurebranches.command.ExpChainSupport.onScoreCompensationFailed();
                            final net.minecraft.world.scores.Objective ghostObj =
                                level.getScoreboard().getObjective(objective);
                            if (ghostObj != null) {
                                level.getScoreboard().resetSinglePlayerScore(
                                    net.minecraft.world.scores.ScoreHolder.forNameOnly(holder),
                                    ghostObj);
                            }
                            return;
                        }
                        level.getScoreboard().getOrCreatePlayerScore(
                            net.minecraft.world.scores.ScoreHolder.forNameOnly(holder),
                            level.getScoreboard().getObjective(objective)).set(value);
                    });
            } catch (final Throwable t) {
                System.err.println("[AzureBranches] score compensation wiring failed: " + t.getMessage());
            }
            return null;
        }));
        for (final int entityId : com.azurebranches.command.EntityLayer.entityIdsOf(phaseSnap)) {
            final net.minecraft.world.entity.Entity targetEntity = level.getEntity(entityId);
            if (targetEntity == null) {
                continue;
            }
            compensationFutures.add(
                net.minecraft.server.commands.RegionCommandExecutor.<Void>onEntityAsync(targetEntity, scheduled -> {
                    try {
                        com.azurebranches.command.EntityLayer.compensateFor(phaseSnap, entityId,
                            (eid, path) -> readEntityNbtPath(level, eid, path),
                            (eid, path, value) -> writeEntityNbtPath(level, eid, path, value));
                    } catch (final Throwable t) {
                        System.err.println("[AzureBranches] entity-NBT compensation wiring failed: " + t.getMessage());
                    }
                    return null;
                }));
        }
        java.util.concurrent.CompletableFuture.allOf(
            compensationFutures.toArray(new java.util.concurrent.CompletableFuture[0]))
            .handle((v, ex) -> null).thenRun(() -> {

        final java.util.Map<Long, Object> oldStates = phaseSnap.getOldBlockStates();
        if (oldStates == null || oldStates.isEmpty()) {
            // Nothing to roll back 鈥?retry from the Phase start on the head region
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue
                .queueOrExecuteTickTask(level, headBlock.getX() >> 4, headBlock.getZ() >> 4, () -> {
                    if (!head.isCurrent(cont)) {
                        com.azurebranches.command.ExpChainSupport.onSupersede();
                        return;
                    }
                    retryExpChainPhase(head, level, headBlock, cont, phaseSnap);
                });
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
                                // rolled back 鈥?log it so it is never silent.
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
        });
    }

    private static void retryExpChainPhase(
        final com.azurebranches.command.ChainHead head,
        final ServerLevel level, final BlockPos headBlock,
        final com.azurebranches.command.Continuation cont,
        final com.azurebranches.command.PhaseSnapshot phaseSnap) {

        // The world has been restored to its pre-Phase state 鈥?replay the
        // whole Phase from its start, re-reading the world.
        phaseSnap.resetForRetry();
        final BlockPos.MutableBlockPos retryPos = BlockPos.of(cont.phaseStartPos).mutable();
        final Direction retryDir = Direction.from3DDataValue(cont.phaseStartDir);
        walkExpChain(head, level, headBlock, retryPos, retryDir,
            cont.remaining + cont.stepCount, phaseSnap);
    }

    // EXP5 P0#2 / EXP6: entity-NBT path read/write helpers for EntityLayer
    // compensation and read-set verification. These run on the entity's own
    // region thread (EXP6: per-entity onEntityAsync dispatch). Supported path
    // forms (matching EntityLayer keys):
    //   "Health"  "Pos[0]"  "foo/bar"  "Inventory{Slot:0b}"  "Inventory{Slot:0b}/id"
    private static Object leafOf(final net.minecraft.nbt.Tag value) {
        if (value instanceof net.minecraft.nbt.NumericTag num) {
            return num.box();
        }
        if (value instanceof net.minecraft.nbt.StringTag str) {
            return str.value();
        }
        return value; // compound/list 鈥?compared structurally via equals()
    }

    private static Object readEntityNbtPath(final ServerLevel level, final int entityId, final String path) {
        final net.minecraft.world.entity.Entity entity = level.getEntity(entityId);
        if (entity == null) {
            return null;
        }
        final net.minecraft.nbt.CompoundTag tag =
            net.minecraft.advancements.criterion.NbtPredicate.getEntityTagToCompare(entity);
        final int brace = path.indexOf('{');
        final int bracket = path.indexOf('[');
        if (brace >= 0) {
            // Slot path: "Inventory{Slot:0b}[/sub]" 鈥?locate the list entry by
            // its stable slot descriptor instead of a list index.
            final int slash = path.indexOf('/', brace);
            final String containerName = path.substring(0, brace);
            final int eq = path.indexOf(':', brace);
            final int closeBrace = path.indexOf('}', brace);
            if (eq < 0 || closeBrace < 0) {
                return null;
            }
            final String slotFieldName = path.substring(brace + 1, eq).trim();
            final String slotValueRaw = path.substring(eq + 1, closeBrace).trim();
            final net.minecraft.nbt.Tag listTag = tag.get(containerName);
            if (listTag instanceof net.minecraft.nbt.ListTag list) {
                for (final net.minecraft.nbt.Tag item : list) {
                    if (item instanceof net.minecraft.nbt.CompoundTag compound
                        && compound.get(slotFieldName) != null
                        && compound.get(slotFieldName).toString().equals(slotValueRaw)) {
                        if (slash >= 0) {
                            return leafOf(compound.get(path.substring(slash + 1)));
                        }
                        return compound.copy();
                    }
                }
            }
            return null;
        }
        if (bracket >= 0) {
            // Vector path: "Pos[0]"
            final int close = path.indexOf(']', bracket);
            final String name = path.substring(0, bracket);
            final int idx = Integer.parseInt(path.substring(bracket + 1, close));
            final net.minecraft.nbt.Tag listTag = tag.get(name);
            return (listTag instanceof net.minecraft.nbt.ListTag list) ? leafOf(list.get(idx)) : null;
        }
        final int slash = path.indexOf('/');
        if (slash >= 0) {
            // Compound sub-field: "foo/bar"
            final net.minecraft.nbt.Tag sub = tag.get(path.substring(0, slash));
            if (sub instanceof net.minecraft.nbt.CompoundTag compound) {
                return leafOf(compound.get(path.substring(slash + 1)));
            }
            return null;
        }
        return leafOf(tag.get(path));
    }

    private static void writeEntityNbtPath(final ServerLevel level, final int entityId, final String path, final Object value) {
        final net.minecraft.world.entity.Entity entity = level.getEntity(entityId);
        if (entity == null) {
            return;
        }
        final net.minecraft.nbt.CompoundTag tag =
            net.minecraft.advancements.criterion.NbtPredicate.getEntityTagToCompare(entity);
        final net.minecraft.nbt.Tag converted = toNbtTag(value);
        final java.util.UUID uuid = entity.getUUID();
        final int brace = path.indexOf('{');
        final int bracket = path.indexOf('[');
        if (brace >= 0) {
            // Slot path: "Inventory{Slot:0b}[/sub]"
            final int slash = path.indexOf('/', brace);
            final String containerName = path.substring(0, brace);
            final int eq = path.indexOf(':', brace);
            final int closeBrace = path.indexOf('}', brace);
            if (eq < 0 || closeBrace < 0) {
                return;
            }
            final String slotFieldName = path.substring(brace + 1, eq).trim();
            final String slotValueRaw = path.substring(eq + 1, closeBrace).trim();
            final net.minecraft.nbt.Tag listTag = tag.get(containerName);
            if (listTag instanceof net.minecraft.nbt.ListTag list) {
                int found = -1;
                for (int i = 0; i < list.size() && found < 0; i++) {
                    final net.minecraft.nbt.Tag item = list.get(i);
                    if (item instanceof net.minecraft.nbt.CompoundTag compound
                        && compound.get(slotFieldName) != null
                        && compound.get(slotFieldName).toString().equals(slotValueRaw)) {
                        found = i;
                    }
                }
                if (found >= 0) {
                    if (slash >= 0) {
                        final net.minecraft.nbt.CompoundTag copy = list.getCompoundOrEmpty(found).copy();
                        copy.put(path.substring(slash + 1), converted);
                        list.set(found, copy);
                    } else if (converted instanceof net.minecraft.nbt.CompoundTag replacement) {
                        list.set(found, replacement);
                    }
                } else if (slash < 0 && converted instanceof net.minecraft.nbt.CompoundTag replacement) {
                    // The slot entry was removed 鈥?re-append it; the Slot value
                    // inside the compound preserves the semantic slot identity.
                    list.add(replacement.copy());
                }
            }
        } else if (bracket >= 0) {
            final int close = path.indexOf(']', bracket);
            final String name = path.substring(0, bracket);
            final int idx = Integer.parseInt(path.substring(bracket + 1, close));
            final net.minecraft.nbt.Tag listTag = tag.get(name);
            if (listTag instanceof net.minecraft.nbt.ListTag list) {
                list.set(idx, converted);
            }
        } else {
            final int slash = path.indexOf('/');
            if (slash >= 0) {
                final net.minecraft.nbt.Tag sub = tag.get(path.substring(0, slash));
                if (sub instanceof net.minecraft.nbt.CompoundTag compound) {
                    compound.put(path.substring(slash + 1), converted);
                }
            } else {
                tag.put(path, converted);
            }
        }
        try (net.minecraft.util.ProblemReporter.ScopedCollector reporter =
                 new net.minecraft.util.ProblemReporter.ScopedCollector(entity.problemPath(), com.mojang.logging.LogUtils.getLogger())) {
            entity.load(net.minecraft.world.level.storage.TagValueInput.create(reporter, entity.registryAccess(), tag));
            entity.setUUID(uuid);
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
        final java.util.Map<String, Integer> scoreReadValues = phaseSnap.getScoreReadSetValues();
        final java.util.Map<String, Object> nbtReadValues = phaseSnap.getNbtReadSetValues();
        final java.util.Map<Integer, String> entityReadValues = phaseSnap.getEntityReadSetValues();
        final boolean hasBlockReads = readValues != null && !readValues.isEmpty();
        final boolean hasScoreReads = scoreReadValues != null && !scoreReadValues.isEmpty();
        final boolean hasNbtReads = nbtReadValues != null && !nbtReadValues.isEmpty();
        final boolean hasEntityReads = entityReadValues != null && !entityReadValues.isEmpty();
        final java.util.Map<String, Boolean> modifiedNbt = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<Integer, Boolean> modifiedEntities = new java.util.concurrent.ConcurrentHashMap<>();
        if (!hasBlockReads && !hasScoreReads && !hasNbtReads && !hasEntityReads) {
            com.azurebranches.command.ExpChainSupport.onValidationPassed();
            commitDeferredActions(level, phaseSnap);
            walkExpChain(head, level, headBlock, resumePos, resumeDir, cont.remaining, phaseSnap);
            return;
        }

        final java.util.Map<Long, Boolean> modified = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.List<java.util.concurrent.CompletableFuture<Void>> checks =
            new java.util.ArrayList<>(readValues != null ? readValues.size() : 0);
        if (hasBlockReads) {
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
        }

        // EXP5Plus P2: verify the score read-set against the live scoreboard on
        // the global tick thread (scoreboard is server-global data).
        final java.util.Map<String, Boolean> modifiedScores = new java.util.concurrent.ConcurrentHashMap<>();
        if (hasScoreReads) {
            for (final java.util.Map.Entry<String, Integer> entry : scoreReadValues.entrySet()) {
                final String key = entry.getKey();
                final int expected = entry.getValue();
                final int colon = key.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                final String objectiveName = key.substring(0, colon);
                final String holderName = key.substring(colon + 1);
                checks.add(net.minecraft.server.commands.RegionCommandExecutor.<Void>onGlobalAsync(() -> {
                    Integer live = null;
                    final net.minecraft.world.scores.Objective obj = level.getScoreboard().getObjective(objectiveName);
                    if (obj != null) {
                        final net.minecraft.world.scores.ReadOnlyScoreInfo info = level.getScoreboard()
                            .getPlayerScoreInfo(net.minecraft.world.scores.ScoreHolder.forNameOnly(holderName), obj);
                        if (info != null) {
                            live = info.value();
                        }
                    }
                    modifiedScores.put(key, live == null || live != expected);
                    return null;
                }));
            }
        }

        // EXP6: verify the entity-NBT read-set against live entities, grouped
        // by entity and dispatched onto each entity's owning region thread.
        if (hasNbtReads) {
            final java.util.Map<Integer, java.util.List<java.util.Map.Entry<String, Object>>> nbtByEntity =
                new java.util.HashMap<>();
            for (final java.util.Map.Entry<String, Object> entry : nbtReadValues.entrySet()) {
                final int colon = entry.getKey().indexOf(':');
                if (colon < 0) {
                    continue;
                }
                final int entityId;
                try {
                    entityId = Integer.parseInt(entry.getKey().substring(0, colon));
                } catch (final NumberFormatException e) {
                    continue;
                }
                nbtByEntity.computeIfAbsent(entityId, k -> new java.util.ArrayList<>()).add(entry);
            }
            for (final java.util.Map.Entry<Integer, java.util.List<java.util.Map.Entry<String, Object>>> e : nbtByEntity.entrySet()) {
                final int entityId = e.getKey();
                final java.util.List<java.util.Map.Entry<String, Object>> entries = e.getValue();
                final net.minecraft.world.entity.Entity checkEntity = level.getEntity(entityId);
                if (checkEntity == null) {
                    // The entity vanished after our read 鈥?treat as conflict.
                    for (final java.util.Map.Entry<String, Object> entry : entries) {
                        modifiedNbt.put(entry.getKey(), Boolean.TRUE);
                    }
                    continue;
                }
                final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
                net.minecraft.server.commands.RegionCommandExecutor.<Void>onEntityAsync(checkEntity, scheduled -> {
                    try {
                        for (final java.util.Map.Entry<String, Object> entry : entries) {
                            final int colon = entry.getKey().indexOf(':');
                            final String path = entry.getKey().substring(colon + 1);
                            final Object live = readEntityNbtPath(level, entityId, path);
                            final Object expected = entry.getValue();
                            modifiedNbt.put(entry.getKey(), !java.util.Objects.equals(live, expected));
                        }
                    } finally {
                        done.complete(null);
                    }
                    return null;
                });
                checks.add(done);
            }
        }

        // EXP6Plus: verify the scoreboard entity read-set 鈥?each entity
        // resolved by a holder argument must still exist, checked on its own
        // region thread.
        if (hasEntityReads) {
            for (final java.util.Map.Entry<Integer, String> entry : entityReadValues.entrySet()) {
                final int entityId = entry.getKey();
                final net.minecraft.world.entity.Entity checkEntity = level.getEntity(entityId);
                if (checkEntity == null) {
                    // The entity vanished after our resolution 鈥?conflict.
                    modifiedEntities.put(entityId, Boolean.TRUE);
                    continue;
                }
                final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
                net.minecraft.server.commands.RegionCommandExecutor.<Void>onEntityAsync(checkEntity, scheduled -> {
                    try {
                        // Existence is the OCC criterion: a dead/removed entity
                        // invalidates the holder set this Phase read. !isAlive()
                        // covers the death-animation window (dead but not yet
                        // discarded 鈥?still in level.getEntity).
                        modifiedEntities.put(entityId,
                            scheduled.isRemoved() || !scheduled.isAlive() || level.getEntity(entityId) == null);
                    } finally {
                        done.complete(null);
                    }
                    return null;
                });
                checks.add(done);
            }
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
                            com.azurebranches.command.PhaseValidator.validate(phaseSnap, cont.retryCount, modified, modifiedScores, modifiedNbt, modifiedEntities);
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

            // EXP6Plus: the walker must genuinely suspend on EXP-chain futures
            // (registerRemote) 鈥?scoreboard mutations land on the global tick,
            // and the next command block must see them. Previously ctx.futures
            // were only getNow()-aggregated (never awaited), so a chain reading
            // a score right after writing it saw the stale value.
            transformSource(cmdBlockFile, "CommandBlock.java (EXP6Plus chain-future barrier)",
                // (A) collect chain futures across the whole walk
                "        final java.util.LinkedHashMap<Long, java.util.List<com.azurebranches.command.ExpChainSupport.DeferredEntry>>\n" +
                    "            batchRegistry = new java.util.LinkedHashMap<>();" to
                    "        final java.util.LinkedHashMap<Long, java.util.List<com.azurebranches.command.ExpChainSupport.DeferredEntry>>\n" +
                    "            batchRegistry = new java.util.LinkedHashMap<>();\n" +
                    "        final java.util.List<java.util.concurrent.CompletableFuture<Boolean>> chainFutures =\n" +
                    "            new java.util.ArrayList<>(4); // EXP6Plus: scoreboard/global futures",

                // (B) drain each command's receipt bag into the walk list
                "                try {\n" +
                    "                    shouldContinue = baseCommandBlock.performCommand(level);\n" +
                    "                } finally {\n" +
                    "                    ctx = com.azurebranches.command.ExpChainSupport.closeContext();\n" +
                    "                }" to
                    "                try {\n" +
                    "                    shouldContinue = baseCommandBlock.performCommand(level);\n" +
                    "                } finally {\n" +
                    "                    ctx = com.azurebranches.command.ExpChainSupport.closeContext();\n" +
                    "                }\n" +
                    "                chainFutures.addAll(ctx.futures); // EXP6Plus",

                // (C) boundary suspend call site
                "                        dispatchAndSuspend(head, level, headBlock, firstBatchPos, pos.immutable(),\n" +
                    "                            direction, walkRemaining, batchRegistry, batchStepCount,\n" +
                    "                            phaseStartPos, phaseStartDir);" to
                    "                        dispatchAndSuspend(head, level, headBlock, firstBatchPos, pos.immutable(),\n" +
                    "                            direction, walkRemaining, batchRegistry, batchStepCount,\n" +
                    "                            phaseStartPos, phaseStartDir, chainFutures); // EXP6Plus",

                // (D) end-of-walk: suspend also when only chain futures exist
                "        if (!batchRegistry.isEmpty()) {\n" +
                    "            dispatchAndSuspend(head, level, headBlock, firstBatchPos, lastRunPos, direction,\n" +
                    "                walkRemaining, batchRegistry, batchStepCount, phaseStartPos, phaseStartDir);" to
                    "        if (!batchRegistry.isEmpty() || !chainFutures.isEmpty()) { // EXP6Plus\n" +
                    "            dispatchAndSuspend(head, level, headBlock, firstBatchPos, lastRunPos, direction,\n" +
                    "                walkRemaining, batchRegistry, batchStepCount, phaseStartPos, phaseStartDir, chainFutures);",

                // (E) dispatchAndSuspend signature
                "        final int batchStepCount, final BlockPos phaseStartPos, final Direction phaseStartDir) {" to
                    "        final int batchStepCount, final BlockPos phaseStartPos, final Direction phaseStartDir,\n" +
                    "        final java.util.List<java.util.concurrent.CompletableFuture<Boolean>> chainFutures) { // EXP6Plus",

                // (F) fold chain futures into the suspend barrier
                "        final java.util.List<java.util.concurrent.CompletableFuture<Void>> regionFutures =\n" +
                    "            new java.util.ArrayList<>(batchRegistry.size());" to
                    "        final java.util.List<java.util.concurrent.CompletableFuture<Void>> regionFutures =\n" +
                    "            new java.util.ArrayList<>(batchRegistry.size());\n" +
                    "        for (final java.util.concurrent.CompletableFuture<Boolean> chainFuture : chainFutures) {\n" +
                    "            regionFutures.add(chainFuture.handle((v, ex) -> null)); // EXP6Plus barrier\n" +
                    "        }",

                // (G) a command that registered chain futures is a batch boundary
                // by itself 鈥?otherwise the next block would read the scoreboard
                // before the global-tick mutation lands.
                "                if (!ctx.deferredByRegion.isEmpty()) {" to
                    "                if (!ctx.deferredByRegion.isEmpty() || !ctx.futures.isEmpty()) { // EXP6Plus",

                // (H) force the suspend at the boundary when futures exist
                "                    if (conditionalBoundary || sizeBoundary) {" to
                    "                    if (conditionalBoundary || sizeBoundary || !ctx.futures.isEmpty()) { // EXP6Plus"
            )

            // EXP4: SetBlockCommand write-through is now handled by the data pool
            // interception below (patch 0015 still caches the expected states).
            val setBlockFile = File(minecraftSrc,
                "net/minecraft/server/commands/SetBlockCommand.java")
            if (setBlockFile.exists()) {
                println("  EXP4: SetBlockCommand relies on data pool interception (no per-command patch needed)")
            }

            // EXP7: storage engine integration 鈥?retype the Moonrise region-file
            // storage surface from vanilla RegionFile to IRegionFile so the
            // configurable format factory (com.azurebranches.storage.RegionFormat)
            // can produce MCA or the Buffered Linear v4 backend.
            val regionStorageFile = File(minecraftSrc,
                "ca/spottedleaf/moonrise/patches/chunk_system/io/ChunkSystemRegionFileStorage.java")
            transformSource(regionStorageFile, "ChunkSystemRegionFileStorage.java (EXP7 IRegionFile)",
                "    public RegionFile moonrise\$getRegionFileIfLoaded(final int chunkX, final int chunkZ);" to
                    "    public com.azurebranches.storage.IRegionFile moonrise\$getRegionFileIfLoaded(final int chunkX, final int chunkZ); // EXP7",
                "    public RegionFile moonrise\$getRegionFileIfExists(final int chunkX, final int chunkZ) throws IOException;" to
                    "    public com.azurebranches.storage.IRegionFile moonrise\$getRegionFileIfExists(final int chunkX, final int chunkZ) throws IOException; // EXP7"
            )

            val moonriseIoFile = File(minecraftSrc,
                "ca/spottedleaf/moonrise/patches/chunk_system/io/MoonriseRegionFileIO.java")
            transformSource(moonriseIoFile, "MoonriseRegionFileIO.java (EXP7 IRegionFile)",
                "                    final RegionFile regionFile = this.regionDataController.getCache().moonrise\$getRegionFileIfLoaded(this.chunkX, this.chunkZ);" to
                    "                    final com.azurebranches.storage.IRegionFile regionFile = this.regionDataController.getCache().moonrise\$getRegionFileIfLoaded(this.chunkX, this.chunkZ); // EXP7",
                "            public void run(final RegionFile regionFile) throws IOException;" to
                    "            public void run(final com.azurebranches.storage.IRegionFile regionFile) throws IOException; // EXP7"
            )

            val chunkBufferFile = File(minecraftSrc,
                "ca/spottedleaf/moonrise/patches/chunk_system/storage/ChunkSystemChunkBuffer.java")
            transformSource(chunkBufferFile, "ChunkSystemChunkBuffer.java (EXP7 IRegionFile)",
                "    public void moonrise\$write(final RegionFile regionFile) throws IOException;" to
                    "    public void moonrise\$write(final com.azurebranches.storage.IRegionFile regionFile) throws IOException; // EXP7"
            )

            val vanillaRegionFile = File(minecraftSrc,
                "net/minecraft/world/level/chunk/storage/RegionFile.java")
            transformSource(vanillaRegionFile, "RegionFile.java (EXP7 IRegionFile)",
                "public class RegionFile implements AutoCloseable, ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemRegionFile {" to
                    "public class RegionFile implements AutoCloseable, ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemRegionFile {",
                "        public final void moonrise\$write(final RegionFile regionFile) throws IOException {" to
                    "        public final void moonrise\$write(final com.azurebranches.storage.IRegionFile regionFile) throws IOException { // EXP7"
            )

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

            // EXP5Plus P2: intercept the Scoreboard data pool so /scoreboard (and
            // any scoreboard access) inside an EXP command-block chain records
            // reads/writes into the PhaseSnapshot 鈥?score read-set values for OCC
            // validation and pre-write old values for ScoreLayer rollback.
            val scoreboardFile = File(minecraftSrc, "net/minecraft/world/scores/Scoreboard.java")
            transformSource(scoreboardFile, "Scoreboard.java (EXP5Plus score interception)",
                // READ hook: cache read-through + read-set value recording.
                "    public @Nullable ReadOnlyScoreInfo getPlayerScoreInfo(final ScoreHolder name, final Objective objective) {\n" +
                    "        PlayerScores playerScore = this.playerScores.get(name.getScoreboardName());\n" +
                    "        return playerScore != null ? playerScore.get(objective) : null;\n" +
                    "    }" to
                    "    public @Nullable ReadOnlyScoreInfo getPlayerScoreInfo(final ScoreHolder name, final Objective objective) {\n" +
                    "        // AzureBranches EXP5Plus: PhaseSnapshot score read interception\n" +
                    "        final com.azurebranches.command.PhaseSnapshot _expSnap =\n" +
                    "            com.azurebranches.command.ExpChainSupport.getPhaseSnapshot();\n" +
                    "        PlayerScores playerScore = this.playerScores.get(name.getScoreboardName());\n" +
                    "        final ReadOnlyScoreInfo _info = playerScore != null ? playerScore.get(objective) : null;\n" +
                    "        if (_expSnap != null) {\n" +
                    "            final String _key = com.azurebranches.command.PhaseSnapshot.scoreKey(\n" +
                    "                objective.getName(), name.getScoreboardName());\n" +
                    "            final Integer _cached = _expSnap.getCachedScore(_key);\n" +
                    "            if (_cached != null) {\n" +
                    "                com.azurebranches.command.ExpChainSupport.onScoreInterceptCacheHit();\n" +
                    "                final int _value = _cached;\n" +
                    "                return new ReadOnlyScoreInfo() {\n" +
                    "                    @Override\n" +
                    "                    public int value() { return _value; }\n" +
                    "                    @Override\n" +
                    "                    public boolean isLocked() { return false; }\n" +
                    "                    @Override\n" +
                    "                    public net.minecraft.network.chat.numbers.NumberFormat numberFormat() { return null; }\n" +
                    "                };\n" +
                    "            }\n" +
                    "            if (_info != null) {\n" +
                    "                _expSnap.recordScoreRead(_key, _info.value(), _expSnap.getSnapshotTick());\n" +
                    "                com.azurebranches.command.ExpChainSupport.onScoreInterceptRead();\n" +
                    "            }\n" +
                    "        }\n" +
                    "        return _info;\n" +
                    "    }",

                // WRITE hook: the anonymous ScoreAccess.set is the single mutation
                // point for set/add/increment/reset (all others delegate to set).
                "                } else {\n" +
                    "                    boolean hasChanged = requiresSync.isTrue();" to
                    "                } else {\n" +
                    "                    // AzureBranches EXP5Plus: PhaseSnapshot score write interception\n" +
                    "                    final com.azurebranches.command.PhaseSnapshot _expSnap =\n" +
                    "                        com.azurebranches.command.ExpChainSupport.getPhaseSnapshot();\n" +
                    "                    if (_expSnap != null) {\n" +
                    "                        final String _key = com.azurebranches.command.PhaseSnapshot.scoreKey(\n" +
                    "                            objective.getName(), scoreHolder.getScoreboardName());\n" +
                    "                        _expSnap.putScore(_key, value, score.value());\n" +
                    "                        _expSnap.markPendingScore(_key);\n" +
                    "                        com.azurebranches.command.ExpChainSupport.onScoreInterceptWrite();\n" +
                    "                    }\n" +
                    "                    boolean hasChanged = requiresSync.isTrue();",

                // WRITE hook: resetAllPlayerScores removes entries without going
                // through ScoreAccess 鈥?capture old values first.
                "    public void resetAllPlayerScores(final ScoreHolder player) {\n" +
                    "        PlayerScores removed = this.playerScores.remove(player.getScoreboardName());" to
                    "    public void resetAllPlayerScores(final ScoreHolder player) {\n" +
                    "        // AzureBranches EXP5Plus: capture old values before removal for OCC rollback\n" +
                    "        final com.azurebranches.command.PhaseSnapshot _expSnap =\n" +
                    "            com.azurebranches.command.ExpChainSupport.getPhaseSnapshot();\n" +
                    "        if (_expSnap != null) {\n" +
                    "            final PlayerScores _existing = this.playerScores.get(player.getScoreboardName());\n" +
                    "            if (_existing != null) {\n" +
                    "                _existing.listRawScores().forEach((objective, score) -> {\n" +
                    "                    final String _key = com.azurebranches.command.PhaseSnapshot.scoreKey(\n" +
                    "                        objective.getName(), player.getScoreboardName());\n" +
                    "                    _expSnap.putScore(_key, 0, score.value());\n" +
                    "                    _expSnap.markPendingScore(_key);\n" +
                    "                    com.azurebranches.command.ExpChainSupport.onScoreInterceptWrite();\n" +
                    "                });\n" +
                    "            }\n" +
                    "        }\n" +
                    "        PlayerScores removed = this.playerScores.remove(player.getScoreboardName());",

                // WRITE hook: resetSinglePlayerScore removes a single entry.
                "    public void resetSinglePlayerScore(final ScoreHolder player, final Objective objective) {\n" +
                    "        PlayerScores scores = this.playerScores.get(player.getScoreboardName());" to
                    "    public void resetSinglePlayerScore(final ScoreHolder player, final Objective objective) {\n" +
                    "        // AzureBranches EXP5Plus: capture old value before removal for OCC rollback\n" +
                    "        final com.azurebranches.command.PhaseSnapshot _expSnap =\n" +
                    "            com.azurebranches.command.ExpChainSupport.getPhaseSnapshot();\n" +
                    "        if (_expSnap != null) {\n" +
                    "            final PlayerScores _scores = this.playerScores.get(player.getScoreboardName());\n" +
                    "            if (_scores != null) {\n" +
                    "                final Score _existing = _scores.get(objective);\n" +
                    "                if (_existing != null) {\n" +
                    "                    final String _key = com.azurebranches.command.PhaseSnapshot.scoreKey(\n" +
                    "                        objective.getName(), player.getScoreboardName());\n" +
                    "                    _expSnap.putScore(_key, 0, _existing.value());\n" +
                    "                    _expSnap.markPendingScore(_key);\n" +
                    "                    com.azurebranches.command.ExpChainSupport.onScoreInterceptWrite();\n" +
                    "                }\n" +
                    "            }\n" +
                    "        }\n" +
                    "        PlayerScores scores = this.playerScores.get(player.getScoreboardName());"
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
            // generated sources 鈥?otherwise an upstream layout change would be
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
        val patchedBuildContent = (originalBuildContent
            .replace("\"Brand-Id\" to \"papermc:folia\"", "\"Brand-Id\" to \"azurebranches\"")
            .replace("\"Brand-Name\" to \"Folia\"", "\"Brand-Name\" to \"AzureBranches\"")
            .replace("\"Specification-Title\" to \"Folia\"", "\"Specification-Title\" to \"AzureBranches\"")) +
            // AzureBranches EXP7: storage engine deps (Buffered Linear v4 backend)
            // lz4-java is already on the runtime classpath (velocity-natives transitive)
            "\n\n// AzureBranches EXP7: storage engine deps\ndependencies {\n" +
            "    implementation(\"com.github.luben:zstd-jni:1.5.4-1\")\n" +
            "    implementation(\"net.openhft:zero-allocation-hashing:0.16\")\n" +
            "}\n"
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

// 鈹€鈹€ Merge 鈹€鈹€

tasks.register("mergeJar") {
    dependsOn(tasks.compileJava, "buildFolia")
    doLast {
        val src = foliaJar.get().asFile
        val classes = sourceSets.main.get().output.classesDirs.singleFile
        val dest = layout.buildDirectory.file("libs/azurebranches-server-${project.version}-EXP6Plus.jar").get().asFile
        dest.parentFile.mkdirs()
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val pb = ProcessBuilder("jar", "uf", dest.absolutePath, "-C", classes.absolutePath, ".").inheritIO()
        pb.start().waitFor()
        println("Done: ${dest.name} (${dest.length()/1024/1024} MB)")
        println("Run: java -jar ${dest.name}")
    }
}

tasks.build { dependsOn("mergeJar") }
