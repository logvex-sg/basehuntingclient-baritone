package dev.basehunt.bhclient.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.basehunt.bhclient.modules.BaseHunter;
import dev.basehunt.bhclient.modules.NewChunks;
import dev.basehunt.bhclient.modules.PlayerLogger;
import dev.basehunt.bhclient.modules.StashFinder;
import dev.basehunt.bhclient.modules.WebhookNotifier;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.utils.BaritoneHelper;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.BlockPos;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;

/**
 * Single entry point for everything the addon can do from chat, so no module has to be opened by hand.
 */
public class BaseHuntCommand extends Command {
    public BaseHuntCommand() {
        super("basehunt", "Controls the base hunting addon.", "bh");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            status();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("status").executes(context -> {
            status();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("fly")
            .then(argument("x", integer())
                .then(argument("z", integer())
                    .executes(context -> {
                        flyTo(getInteger(context, "x"), getInteger(context, "z"));
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );

        builder.then(literal("stop").executes(context -> {
            BaseHunter hunter = Modules.get().get(BaseHunter.class);
            if (hunter != null && hunter.isActive()) {
                hunter.disable();
                info("Base Hunter stopped.");
            } else {
                BaritoneHelper.stop();
                info("Baritone pathing cancelled.");
            }

            return SINGLE_SUCCESS;
        }));

        builder.then(literal("waypoint")
            .then(literal("add").executes(context -> {
                BaseHunter hunter = Modules.get().get(BaseHunter.class);
                if (hunter == null || mc.player == null) return SINGLE_SUCCESS;

                hunter.addWaypoint(mc.player.getBlockPos());
                info("Waypoint added at %s. %d total.", ServerUtils.coordinates(), hunter.getWaypointCount());
                return SINGLE_SUCCESS;
            }))
            .then(literal("add-at")
                .then(argument("x", integer())
                    .then(argument("z", integer())
                        .executes(context -> {
                            BaseHunter hunter = Modules.get().get(BaseHunter.class);
                            if (hunter == null) return SINGLE_SUCCESS;

                            hunter.addWaypoint(new BlockPos(getInteger(context, "x"), 64, getInteger(context, "z")));
                            info("Waypoint added. %d total.", hunter.getWaypointCount());
                            return SINGLE_SUCCESS;
                        })
                    )
                )
            )
            .then(literal("clear").executes(context -> {
                BaseHunter hunter = Modules.get().get(BaseHunter.class);
                if (hunter != null) hunter.clearWaypoints();
                info("Waypoints cleared.");
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("stashes").executes(context -> {
            StashManager manager = StashManager.get();
            if (manager == null) {
                error("Stash store is not loaded.");
                return SINGLE_SUCCESS;
            }

            var stashes = manager.forCurrentServer();
            if (stashes.isEmpty()) {
                info("No stashes recorded for this server yet.");
                return SINGLE_SUCCESS;
            }

            info("Stashes on %s:", ServerUtils.serverAddress());
            for (StashManager.Stash stash : stashes) {
                info("  %s %s [%s]", stash.kind, stash.pos.toShortString(), stash.describeCounts());
            }

            return SINGLE_SUCCESS;
        }));

        builder.then(literal("players").executes(context -> {
            PlayerTracker tracker = PlayerTracker.get();
            if (tracker == null) {
                error("Player tracker is not loaded.");
                return SINGLE_SUCCESS;
            }

            var players = tracker.forCurrentServer();
            if (players.isEmpty()) {
                info("No players recorded for this server yet.");
                return SINGLE_SUCCESS;
            }

            info("Players seen on %s:", ServerUtils.serverAddress());
            for (PlayerTracker.Entry entry : players) {
                String logout = entry.hasLogoutSpot() ? "logout " + entry.logoutCoords() : "no logout spot";
                info("  %s - last %s - %s", entry.name, entry.lastCoords(), logout);
            }

            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(context -> {
            PlayerTracker tracker = PlayerTracker.get();
            if (tracker != null) {
                info("Removed %d player records.", tracker.clearCurrentServer());
            }

            PlayerLogger logger = Modules.get().get(PlayerLogger.class);
            if (logger != null) logger.clearSpots();

            return SINGLE_SUCCESS;
        }));

        builder.then(literal("webhook")
            .then(literal("test").executes(context -> {
                WebhookNotifier notifier = Modules.get().get(WebhookNotifier.class);
                if (notifier == null || !notifier.isActive()) {
                    error("Enable the webhook-notifier module first.");
                    return SINGLE_SUCCESS;
                }

                info("Sending a test webhook.");
                notifier.sendTest();
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void flyTo(int x, int z) {
        BaseHunter hunter = Modules.get().get(BaseHunter.class);
        if (hunter == null) {
            error("Base Hunter module is unavailable.");
            return;
        }

        if (!hunter.isActive()) hunter.enable();

        if (!BaritoneHelper.AVAILABLE) {
            error("Baritone is not installed, cannot fly.");
            return;
        }

        hunter.start();
        info("Base Hunter started in %s mode.", hunter.getModeName());
        info("Flying to %d, %d.", x, z);
    }

    private void status() {
        info("Base Hunting Client");
        info("Server: %s (%s)", ServerUtils.serverAddress(), ServerUtils.dimension());
        info("Position: %s", ServerUtils.coordinates());
        info("Baritone: %s", BaritoneHelper.AVAILABLE ? "available" : "missing");

        NewChunks newChunks = Modules.get().get(NewChunks.class);
        if (newChunks != null && newChunks.isActive()) {
            info("New chunks: %d found, %d proven old", newChunks.getTrackedCount(), newChunks.getOldChunkCount());
        }

        StashManager stashManager = StashManager.get();
        if (stashManager != null) {
            info("Stashes: %d on this server", stashManager.forCurrentServer().size());
        }

        PlayerTracker tracker = PlayerTracker.get();
        if (tracker != null) {
            info("Players: %d on this server", tracker.forCurrentServer().size());
        }

        BaseHunter hunter = Modules.get().get(BaseHunter.class);
        if (hunter != null && hunter.isActive()) {
            BlockPos destination = hunter.getCurrentDestination();
            info("Autopilot: %s, destination %s, flown %,d blocks",
                hunter.isPaused() ? "paused" : "flying",
                destination == null ? "none" : destination.toShortString(),
                (long) hunter.getTravelledBlocks());
        }
    }
}
