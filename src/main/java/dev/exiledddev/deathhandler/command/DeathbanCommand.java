package dev.exiledddev.deathhandler.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.exiledddev.deathhandler.DeathbanService;
import dev.exiledddev.deathhandler.DeathHandlerPlugin;
import dev.exiledddev.deathhandler.Msg;
import dev.exiledddev.deathhandler.Permissions;
import dev.exiledddev.deathhandler.store.Database;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/**
 * {@code /deathban list | info | pardon | revive | on | off | status | reload}.
 */
public final class DeathbanCommand {

    private static final String ALL = "-all";

    /** Usage line, text a click puts in the chat box, and what it does. */
    private record HelpEntry(String usage, String suggestion, String description) {
    }

    private static final List<HelpEntry> HELP = List.of(
        new HelpEntry("/deathban list", "/deathban list", "who is deathbanned"),
        new HelpEntry("/deathban info <player>", "/deathban info ", "a player's recent deaths"),
        new HelpEntry("/deathban pardon <player>|-all", "/deathban pardon ", "let deathbanned players back in"),
        new HelpEntry("/deathban revive <player>", "/deathban revive ", "pardon and bring them back where they died"),
        new HelpEntry("/deathban on|off", "/deathban ", "turn deathbans on or off"),
        new HelpEntry("/deathban status", "/deathban status", "on/off, bans and immortal players"),
        new HelpEntry("/immortal [targets] [on|off]", "/immortal ", "can't die (except with a totem in hand)"),
        new HelpEntry("/deathban reload", "/deathban reload", "reload config.yml")
    );

    private final DeathHandlerPlugin plugin;
    private final DeathbanService service;

    public DeathbanCommand(final DeathHandlerPlugin plugin, final DeathbanService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("deathban")
            .requires(source -> source.getSender().hasPermission(Permissions.ADMIN))
            .executes(this::help)
            .then(Commands.literal("help").executes(this::help))
            .then(Commands.literal("list").executes(this::list))
            .then(Commands.literal("info")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((ctx, builder) -> Suggest.matching(builder, this.service.knownNames()))
                    .executes(this::info)))
            .then(Commands.literal("pardon")
                .then(Commands.literal(ALL).executes(this::pardonAll))
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((ctx, builder) -> Suggest.matching(builder, this.service.bannedNames()))
                    .executes(this::pardon)))
            .then(Commands.literal("revive")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((ctx, builder) -> Suggest.matching(builder, this.service.bannedNames()))
                    .executes(this::revive)))
            .then(Commands.literal("on").executes(ctx -> this.toggle(ctx, true)))
            .then(Commands.literal("off").executes(ctx -> this.toggle(ctx, false)))
            .then(Commands.literal("status").executes(this::status))
            .then(Commands.literal("reload").executes(ctx -> {
                this.plugin.reloadSettings();
                Msg.success(ctx.getSource().getSender(), "Reloaded config.yml.");
                return Command.SINGLE_SUCCESS;
            }))
            .build();
    }

    private int help(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        Msg.info(sender, "Deathbans are <state>. Commands <dark_gray>(click one to type it)</dark_gray>:",
            Msg.component("state", this.state()));
        for (final HelpEntry entry : HELP) {
            sender.sendMessage(Component.text()
                .append(Component.text(" " + entry.usage(), NamedTextColor.GOLD)
                    .clickEvent(ClickEvent.suggestCommand(entry.suggestion()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to type " + entry.suggestion().strip()))))
                .append(Component.text(" - " + entry.description(), NamedTextColor.GRAY)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int list(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final Collection<Database.Ban> bans = this.service.bans();
        if (bans.isEmpty()) {
            Msg.info(sender, "Nobody is deathbanned.");
            return 0;
        }
        Msg.info(sender, "<count> deathbanned player(s), newest first:", Msg.text("count", bans.size()));
        for (final Database.Ban ban : bans) {
            final String pardon = "/deathban pardon " + ban.realName();
            Msg.line(sender, " <white><real></white><nick> <dark_gray>- <gray><cause></gray> (<when>)</dark_gray> <button>",
                Msg.text("real", ban.realName()),
                Msg.text("nick", ban.nickname().equals(ban.realName()) ? "" : " (as " + ban.nickname() + ")"),
                Msg.text("cause", ban.cause()),
                Msg.text("when", ago(ban.bannedAt())),
                Msg.component("button", Component.text("[pardon]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand(pardon))
                    .hoverEvent(HoverEvent.showText(Component.text(pardon)))));
        }
        return bans.size();
    }

    private int info(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final UUID uuid = this.resolve(sender, StringArgumentType.getString(ctx, "player"));
        if (uuid == null) {
            return 0;
        }
        final List<Database.Death> deaths = this.service.database().deaths(uuid, 10);
        final String name = this.service.nameOf(uuid);
        Msg.info(sender, "<white><name></white> is <state><immortal>.",
            Msg.text("name", name),
            Msg.component("state", this.service.isBanned(uuid) ? Component.text("deathbanned", NamedTextColor.RED) : Component.text("not deathbanned", NamedTextColor.GREEN)),
            Msg.text("immortal", this.service.isImmortal(uuid) ? " and immortal" : ""));
        if (deaths.isEmpty()) {
            Msg.line(sender, " <gray>No deaths logged.");
            return 0;
        }
        for (final Database.Death death : deaths) {
            final Database.Spot spot = death.spot();
            Msg.line(sender, " <gray><when>:</gray> <white><cause></white> <dark_gray>(<details>)",
                Msg.text("when", ago(death.diedAt())),
                Msg.text("cause", death.cause()),
                Msg.text("details", (death.nickname().equals(death.realName()) ? "" : "as " + death.nickname() + ", ")
                    + (death.killer() == null ? "" : "killer: " + death.killer() + ", ")
                    + spot.world() + " " + Math.round(spot.x()) + " " + Math.round(spot.y()) + " " + Math.round(spot.z())
                    + (death.banned() ? ", deathbanned" : ", not banned")));
        }
        return deaths.size();
    }

    private int pardon(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final String name = StringArgumentType.getString(ctx, "player");
        final UUID uuid = this.resolve(sender, name);
        if (uuid == null) {
            return 0;
        }
        final Database.Ban ban = this.service.pardon(uuid);
        if (ban == null) {
            Msg.error(sender, "<name> isn't deathbanned.", Msg.text("name", this.service.nameOf(uuid)));
            return 0;
        }
        Msg.success(sender, "Pardoned <name>. They can join again.", Msg.text("name", ban.realName()));
        return Command.SINGLE_SUCCESS;
    }

    private int pardonAll(final CommandContext<CommandSourceStack> ctx) {
        final int count = this.service.pardonAll();
        Msg.success(ctx.getSource().getSender(), count == 0 ? "Nobody was deathbanned." : "Pardoned all <count> deathbanned player(s).",
            Msg.text("count", count));
        return count;
    }

    private int revive(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final UUID uuid = this.resolve(sender, StringArgumentType.getString(ctx, "player"));
        if (uuid == null) {
            return 0;
        }
        if (!this.service.revive(uuid)) {
            Msg.error(sender, "No death is logged for <name>, so there's nowhere to bring them back to. Use /deathban pardon instead.",
                Msg.text("name", this.service.nameOf(uuid)));
            return 0;
        }
        Msg.success(sender, "Revived <name>: they're pardoned and will reappear where they died when they join.",
            Msg.text("name", this.service.nameOf(uuid)));
        return Command.SINGLE_SUCCESS;
    }

    private int toggle(final CommandContext<CommandSourceStack> ctx, final boolean on) {
        this.service.setEnabled(on);
        Msg.success(ctx.getSource().getSender(), on
            ? "Deathbans are on: anyone who dies (and isn't immortal or exempt) is removed until pardoned."
            : "Deathbans are off: deaths are normal. Existing deathbans stay until you pardon them.");
        return Command.SINGLE_SUCCESS;
    }

    private int status(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        Msg.info(sender, "Deathbans are <state>. <bans> deathbanned, <immortal> immortal<names>.",
            Msg.component("state", this.state()),
            Msg.text("bans", this.service.bans().size()),
            Msg.text("immortal", this.service.immortalNames().size()),
            Msg.text("names", this.service.immortalNames().isEmpty() ? "" : " (" + Msg.join(this.service.immortalNames()) + ")"));
        return Command.SINGLE_SUCCESS;
    }

    // ---- Helpers --------------------------------------------------------------------------

    private Component state() {
        return this.service.enabled() ? Component.text("ON", NamedTextColor.RED) : Component.text("OFF", NamedTextColor.GREEN);
    }

    /** One player matching a real name or nickname, or null after telling the sender why not. */
    private @Nullable UUID resolve(final CommandSender sender, final String name) {
        final Set<UUID> found = this.service.find(name);
        if (found.isEmpty()) {
            Msg.error(sender, "No player called <name> is known (by real name or nickname).", Msg.text("name", name));
            return null;
        }
        if (found.size() > 1) {
            Msg.error(sender, "<name> matches several players: <names>. Use their real name.", Msg.text("name", name),
                Msg.text("names", Msg.join(found.stream().map(this.service::nameOf).toList())));
            return null;
        }
        return found.iterator().next();
    }

    static String ago(final long timestamp) {
        final Duration elapsed = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - timestamp));
        if (elapsed.toMinutes() < 1) {
            return "just now";
        }
        if (elapsed.toHours() < 1) {
            return elapsed.toMinutes() + "m ago";
        }
        if (elapsed.toDays() < 1) {
            return elapsed.toHours() + "h ago";
        }
        return elapsed.toDays() + "d ago";
    }
}
