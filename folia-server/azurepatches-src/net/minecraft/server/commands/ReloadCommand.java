package net.minecraft.server.commands;

import com.google.common.collect.Lists;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.WorldData;
import org.slf4j.Logger;

/**
 * AzureBranches EXP5 P1: /reload restored.
 *
 * The pack repository is global mutable state; Folia runs commands on region
 * threads, so the synchronous pack discovery (PackRepository#reload) is
 * dispatched to the global tick thread. Feedback is routed back to the source
 * region via RegionCommandExecutor#runOnSource.
 */
public class ReloadCommand {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void reloadPacks(final Collection<String> selectedPacks, final CommandSourceStack source) {
        source.getServer().reloadResources(selectedPacks, io.papermc.paper.event.server.ServerResourcesReloadedEvent.Cause.COMMAND).exceptionally(throwable -> {
            LOGGER.warn("Failed to execute reload", throwable);
            RegionCommandExecutor.runOnSource(source, () ->
                source.sendFailure(Component.translatable("commands.reload.failure")));
            return null;
        });
    }

    private static Collection<String> discoverNewPacks(final PackRepository packRepository, final WorldData worldData, final Collection<String> currentPacks) {
        packRepository.reload(true);
        Collection<String> selected = Lists.newArrayList(currentPacks);
        Collection<String> disabled = worldData.getDataConfiguration().dataPacks().getDisabled();

        for (String pack : packRepository.getAvailableIds()) {
            if (!disabled.contains(pack) && !selected.contains(pack)) {
                selected.add(pack);
            }
        }

        return selected;
    }

    // CraftBukkit start
    public static void reload(MinecraftServer server) {
        PackRepository packRepository = server.getPackRepository();
        WorldData worldData = server.getWorldData();
        Collection<String> selectedIds = packRepository.getSelectedIds();
        Collection<String> collection = discoverNewPacks(packRepository, worldData, selectedIds);
        server.reloadResources(collection, io.papermc.paper.event.server.ServerResourcesReloadedEvent.Cause.PLUGIN);
    }
    // CraftBukkit end

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(s -> {
            CommandSourceStack source = s.getSource();
            MinecraftServer server = source.getServer();
            // AzureBranches: mutate the global pack repository + reload on the
            // global tick thread, never on a region thread.
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
                PackRepository packRepository = server.getPackRepository();
                WorldData worldData = server.getWorldData();
                Collection<String> currentPacks = packRepository.getSelectedIds();
                Collection<String> newSelectedPacks = discoverNewPacks(packRepository, worldData, currentPacks);
                RegionCommandExecutor.runOnSource(source, () ->
                    source.sendSuccess(() -> Component.translatable("commands.reload.success"), true));
                reloadPacks(newSelectedPacks, source);
            });
            return 0;
        }));
    }
}
