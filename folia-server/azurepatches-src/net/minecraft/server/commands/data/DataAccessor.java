package net.minecraft.server.commands.data;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.RegionCommandExecutor;

public interface DataAccessor {
    void setData(CompoundTag tag) throws CommandSyntaxException;

    CompoundTag getData() throws CommandSyntaxException;

    Component getModifiedSuccess();

    Component getPrintSuccess(Tag data);

    Component getPrintSuccess(NbtPathArgument.NbtPath path, double scale, int value);

    /**
     * AzureBranches EXP5: asynchronous read. The default offloads the synchronous
     * {@link #getData()} onto a non-tick worker so it can block there safely.
     * Region-aware accessors override this to hop to the owning region directly.
     */
    default CompletableFuture<CompoundTag> getDataAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getData();
            } catch (CommandSyntaxException e) {
                throw new CompletionException(e);
            }
        }, RegionCommandExecutor.workerPool());
    }

    /**
     * AzureBranches EXP5: asynchronous write. See {@link #getDataAsync()}.
     */
    default CompletableFuture<Void> setDataAsync(final CompoundTag tag) {
        return CompletableFuture.runAsync(() -> {
            try {
                setData(tag);
            } catch (CommandSyntaxException e) {
                throw new CompletionException(e);
            }
        }, RegionCommandExecutor.workerPool());
    }
}
