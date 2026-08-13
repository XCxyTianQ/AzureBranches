package net.minecraft.server.commands.data;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import org.slf4j.Logger;

public class BlockDataAccessor implements DataAccessor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SimpleCommandExceptionType ERROR_NOT_A_BLOCK_ENTITY = new SimpleCommandExceptionType(
        Component.translatable("commands.data.block.invalid")
    );
    public static final Function<String, DataCommands.DataProvider> PROVIDER = argPrefix -> new DataCommands.DataProvider() {
        @Override
        public DataAccessor access(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, argPrefix + "Pos");
            // AzureBranches: do NOT resolve the BlockEntity here — it may live on
            // another region thread. Resolve it inside getData()/setData() on the
            // owning region.
            return new BlockDataAccessor(context.getSource().getLevel(), pos);
        }

        @Override
        public ArgumentBuilder<CommandSourceStack, ?> wrap(
            final ArgumentBuilder<CommandSourceStack, ?> parent,
            final Function<ArgumentBuilder<CommandSourceStack, ?>, ArgumentBuilder<CommandSourceStack, ?>> function
        ) {
            return parent.then(Commands.literal("block").then(function.apply(Commands.argument(argPrefix + "Pos", BlockPosArgument.blockPos()))));
        }
    };
    private final ServerLevel level;
    private final BlockPos pos;

    public BlockDataAccessor(final ServerLevel level, final BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    private BlockEntity resolveEntity() throws CommandSyntaxException {
        BlockEntity entity = this.level.getBlockEntity(this.pos);
        if (entity == null) {
            throw BlockDataAccessor.ERROR_NOT_A_BLOCK_ENTITY.create();
        }
        return entity;
    }

    @Override
    public void setData(final CompoundTag tag) throws CommandSyntaxException {
        // AzureBranches: hop to the region owning this block position
        net.minecraft.server.commands.RegionCommandExecutor.onBlock(this.level, this.pos, () -> {
            BlockEntity entity = resolveEntity();
            BlockState state = this.level.getBlockState(this.pos);
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), LOGGER)) {
                entity.loadWithComponents(TagValueInput.create(reporter, this.level.registryAccess(), tag));
                entity.setChanged();
                this.level.sendBlockUpdated(this.pos, state, state, Block.UPDATE_ALL);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> setDataAsync(final CompoundTag tag) {
        return net.minecraft.server.commands.RegionCommandExecutor.<Void>onBlockAsync(this.level, this.pos, () -> {
            BlockEntity entity = resolveEntity();
            BlockState state = this.level.getBlockState(this.pos);
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), LOGGER)) {
                entity.loadWithComponents(TagValueInput.create(reporter, this.level.registryAccess(), tag));
                entity.setChanged();
                this.level.sendBlockUpdated(this.pos, state, state, Block.UPDATE_ALL);
            }
            return null;
        });
    }

    @Override
    public CompoundTag getData() throws CommandSyntaxException {
        // AzureBranches: hop to the region owning this block position
        return net.minecraft.server.commands.RegionCommandExecutor.onBlock(this.level, this.pos, () -> {
            BlockEntity entity = resolveEntity();
            return entity.saveWithFullMetadata(this.level.registryAccess());
        });
    }

    @Override
    public CompletableFuture<CompoundTag> getDataAsync() {
        return net.minecraft.server.commands.RegionCommandExecutor.onBlockAsync(this.level, this.pos, () -> {
            BlockEntity entity = resolveEntity();
            return entity.saveWithFullMetadata(this.level.registryAccess());
        });
    }

    @Override
    public Component getModifiedSuccess() {
        return Component.translatable("commands.data.block.modified", this.pos.getX(), this.pos.getY(), this.pos.getZ());
    }

    @Override
    public Component getPrintSuccess(final Tag data) {
        return Component.translatable("commands.data.block.query", this.pos.getX(), this.pos.getY(), this.pos.getZ(), NbtUtils.toPrettyComponent(data));
    }

    @Override
    public Component getPrintSuccess(final NbtPathArgument.NbtPath path, final double scale, final int value) {
        return Component.translatable(
            "commands.data.block.get", path.asString(), this.pos.getX(), this.pos.getY(), this.pos.getZ(), String.format(Locale.ROOT, "%.2f", scale), value
        );
    }
}
