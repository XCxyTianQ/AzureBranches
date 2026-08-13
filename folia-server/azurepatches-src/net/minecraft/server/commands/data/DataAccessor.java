package net.minecraft.server.commands.data;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

public interface DataAccessor {
    void setData(CompoundTag tag) throws CommandSyntaxException;

    CompoundTag getData() throws CommandSyntaxException;

    Component getModifiedSuccess();

    Component getPrintSuccess(Tag data);

    Component getPrintSuccess(NbtPathArgument.NbtPath path, double scale, int value);

    /**
     * AzureBranches EXP5: asynchronous read.
     *
     * <p>The default is synchronous (region-independent accessors such as
     * {@code StorageDataAccessor} complete the future immediately), so the
     * "same-region fast path" in DataCommands sees an already-done future and
     * returns the exact result synchronously. Region-aware accessors override
     * this to hop to the owning region via onBlockAsync/onEntityAsync.</p>
     */
    default CompletableFuture<CompoundTag> getDataAsync() {
        try {
            return CompletableFuture.completedFuture(getData());
        } catch (CommandSyntaxException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * AzureBranches EXP5: asynchronous write. See {@link #getDataAsync()}.
     */
    default CompletableFuture<Void> setDataAsync(final CompoundTag tag) {
        try {
            setData(tag);
            return CompletableFuture.completedFuture(null);
        } catch (CommandSyntaxException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
