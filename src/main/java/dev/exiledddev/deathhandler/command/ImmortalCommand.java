package dev.exiledddev.deathhandler.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.exiledddev.deathhandler.DeathbanService;
import dev.exiledddev.deathhandler.Msg;
import dev.exiledddev.deathhandler.Permissions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /immortal [targets] [on|off]} and {@code /immortal list}. Targets take any vanilla
 * selector, e.g. {@code /immortal @a[team=red] on}.
 */
public final class ImmortalCommand {

    private static final String TARGETS = "targets";

    private final DeathbanService service;

    public ImmortalCommand(final DeathbanService service) {
        this.service = service;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("immortal")
            .requires(source -> source.getSender().hasPermission(Permissions.ADMIN))
            .executes(this::self)
            .then(Commands.literal("list").executes(this::list))
            .then(Commands.argument(TARGETS, ArgumentTypes.players())
                .executes(ctx -> this.set(ctx, null))
                .then(Commands.literal("on").executes(ctx -> this.set(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> this.set(ctx, false))))
            .build();
    }

    private int self(final CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final Player player = source.getExecutor() instanceof Player executor ? executor
            : source.getSender() instanceof Player sender ? sender : null;
        if (player == null) {
            Msg.error(source.getSender(), "Name who to make immortal: /immortal <targets> [on|off]");
            return 0;
        }
        this.apply(source.getSender(), List.of(player), null);
        return Command.SINGLE_SUCCESS;
    }

    /** @param on true/false to set, or null to toggle each player */
    private int set(final CommandContext<CommandSourceStack> ctx, final @Nullable Boolean on) throws CommandSyntaxException {
        final List<Player> targets = ctx.getArgument(TARGETS, PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        this.apply(ctx.getSource().getSender(), targets, on);
        return targets.size();
    }

    private void apply(final CommandSender sender, final List<Player> targets, final @Nullable Boolean on) {
        final List<String> nowImmortal = new ArrayList<>();
        final List<String> nowMortal = new ArrayList<>();
        for (final Player player : targets) {
            final boolean immortal = on != null ? on : !this.service.isImmortal(player.getUniqueId());
            this.service.setImmortal(player, immortal);
            (immortal ? nowImmortal : nowMortal).add(player.getName());
            if (!player.equals(sender)) {
                Msg.info(player, immortal
                    ? "You're <gold>immortal</gold>: you can't die (unless you hold a totem) and can't be deathbanned."
                    : "You're mortal again.");
            }
        }
        if (!nowImmortal.isEmpty()) {
            Msg.success(sender, "Immortal: <names>.", Msg.text("names", Msg.join(nowImmortal)));
        }
        if (!nowMortal.isEmpty()) {
            Msg.success(sender, "Mortal again: <names>.", Msg.text("names", Msg.join(nowMortal)));
        }
    }

    private int list(final CommandContext<CommandSourceStack> ctx) {
        final var names = this.service.immortalNames();
        if (names.isEmpty()) {
            Msg.info(ctx.getSource().getSender(), "Nobody is immortal.");
        } else {
            Msg.info(ctx.getSource().getSender(), "Immortal (<count>): <white><names>", Msg.text("count", names.size()), Msg.text("names", Msg.join(names)));
        }
        return names.size();
    }
}
