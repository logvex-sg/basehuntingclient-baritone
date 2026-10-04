package dev.basehunt.bhclient;

import com.mojang.logging.LogUtils;
import dev.basehunt.bhclient.commands.BaseHuntCommand;
import dev.basehunt.bhclient.hud.BaseHuntingHud;
import dev.basehunt.bhclient.modules.AutoLog;
import dev.basehunt.bhclient.modules.BaseHunter;
import dev.basehunt.bhclient.modules.BaseHunterV2;
import dev.basehunt.bhclient.modules.NewChunks;
import dev.basehunt.bhclient.modules.PlayerLogger;
import dev.basehunt.bhclient.modules.StashFinder;
import dev.basehunt.bhclient.modules.WebhookNotifier;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.utils.WebhookManager;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class BaseHuntingAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Base Hunting");
    public static final HudGroup HUD_GROUP = new HudGroup("Base Hunting");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Base Hunting Client");

        // Systems have to be registered before Modules.load() runs, otherwise their saved data is lost.
        Systems.add(new StashManager());
        Systems.add(new PlayerTracker());

        Modules.get().add(new BaseHunter());
        Modules.get().add(new BaseHunterV2());
        Modules.get().add(new NewChunks());
        Modules.get().add(new StashFinder());
        Modules.get().add(new WebhookNotifier());
        Modules.get().add(new PlayerLogger());
        Modules.get().add(new AutoLog());

        Commands.add(new BaseHuntCommand());

        Hud.get().register(BaseHuntingHud.INFO);

        WebhookManager.get().start();
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.basehunt.bhclient";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("logvex-sg", "basehuntingclient-baritone", "main", null);
    }
}
