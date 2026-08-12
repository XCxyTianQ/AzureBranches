package net.minecraft.server.commands.data;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.advancements.criterion.NbtPredicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueInput;
import org.slf4j.Logger;

public class EntityDataAccessor implements DataAccessor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SimpleCommandExceptionType ERROR_NO_PLAYERS = new SimpleCommandExceptionType(Component.translatable("commands.data.entity.invalid"));
    public static final Function<String, DataCommands.DataProvider> PROVIDER = arg -> new DataCommands.DataProvider() {
        @Override
        public DataAccessor access(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            return new EntityDataAccessor(EntityArgument.getEntity(context, arg));
        }

        @Override
        public ArgumentBuilder<CommandSourceStack, ?> wrap(
            final ArgumentBuilder<CommandSourceStack, ?> parent,
            final Function<ArgumentBuilder<CommandSourceStack, ?>, ArgumentBuilder<CommandSourceStack, ?>> function
        ) {
            return parent.then(Commands.literal("entity").then(function.apply(Commands.argument(arg, EntityArgument.entity()))));
        }
    };
    private final Entity entity;
    private volatile Component cachedDisplayName;

    public EntityDataAccessor(final Entity entity) {
        this.entity = entity;
    }

    private Component displayName() {
        // AzureBranches: capture the display name on the entity's region thread
        // (getModifiedSuccess/getPrintSuccess run on the source thread).
        Component cached = this.cachedDisplayName;
        if (cached != null) {
            return cached;
        }
        try {
            Component name = net.minecraft.server.commands.RegionCommandExecutor.onEntity(
                this.entity, (Entity scheduled) -> scheduled.getDisplayName());
            this.cachedDisplayName = name;
            return name;
        } catch (CommandSyntaxException e) {
            return this.entity.getDisplayName();
        }
    }

    @Override
    public void setData(final CompoundTag tag) throws CommandSyntaxException {
        // AzureBranches: hop to the region owning this entity
        net.minecraft.server.commands.RegionCommandExecutor.onEntity(this.entity, (Entity scheduled) -> {
            if (scheduled instanceof Player) {
                throw ERROR_NO_PLAYERS.create();
            }
            UUID uuid = scheduled.getUUID();
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(scheduled.problemPath(), LOGGER)) {
                scheduled.load(TagValueInput.create(reporter, scheduled.registryAccess(), tag));
                scheduled.setUUID(uuid);
            }
            return null;
        });
    }

    @Override
    public CompoundTag getData() throws CommandSyntaxException {
        // AzureBranches: hop to the region owning this entity
        return net.minecraft.server.commands.RegionCommandExecutor.onEntity(this.entity, (Entity scheduled) ->
            NbtPredicate.getEntityTagToCompare(scheduled));
    }

    @Override
    public Component getModifiedSuccess() {
        return Component.translatable("commands.data.entity.modified", this.displayName());
    }

    @Override
    public Component getPrintSuccess(final Tag data) {
        return Component.translatable("commands.data.entity.query", this.displayName(), NbtUtils.toPrettyComponent(data));
    }

    @Override
    public Component getPrintSuccess(final NbtPathArgument.NbtPath path, final double scale, final int value) {
        return Component.translatable(
            "commands.data.entity.get", path.asString(), this.displayName(), String.format(Locale.ROOT, "%.2f", scale), value
        );
    }
}
