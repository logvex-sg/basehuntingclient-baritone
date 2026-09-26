package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.utils.BaritoneHelper;
import dev.basehunt.bhclient.utils.ServerUtils;
import dev.basehunt.bhclient.utils.WebhookManager;
import dev.basehunt.bhclient.utils.WebhookSettings;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts storage containers in every chunk the client loads and reports the chunks that look like a stash.
 *
 * <p>Detection runs on the block entities the server sends, so it works without ever touching the blocks
 * themselves and costs nothing extra on top of what the client already received.
 */
public class StashFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print stash locations in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> saveToStore = sgGeneral.add(new BoolSetting.Builder()
        .name("save-to-store")
        .description("Persist found stashes so they survive restarts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> mergeRadius = sgGeneral.add(new IntSetting.Builder()
        .name("store-merge-radius")
        .description("Stashes within this distance are treated as one location.")
        .defaultValue(96)
        .min(0)
        .sliderRange(0, 512)
        .visible(saveToStore::get)
        .build()
    );

    private final Setting<Integer> minDistanceFromSpawn = sgGeneral.add(new IntSetting.Builder()
        .name("min-distance-from-spawn")
        .description("Ignore stashes closer to spawn than this, they are usually already looted.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 100000)
        .build()
    );

    private final Setting<Integer> maxChunksPerSecond = sgGeneral.add(new IntSetting.Builder()
        .name("max-scans-per-second")
        .description("Throttle chunk scanning to keep the client responsive while flying fast.")
        .defaultValue(40)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    // Detection

    private final Setting<Integer> minStorageCount = sgDetection.add(new IntSetting.Builder()
        .name("min-storage-count")
        .description("How many storage containers a chunk needs before it is reported.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Integer> minScore = sgDetection.add(new IntSetting.Builder()
        .name("min-score")
        .description("Weighted score threshold. Shulkers and chests are worth more than furnaces.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Boolean> detectSpawners = sgDetection.add(new BoolSetting.Builder()
        .name("detect-spawners")
        .description("Report chunks containing mob spawners, which often mark a farm.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> detectShulkers = sgDetection.add(new BoolSetting.Builder()
        .name("detect-shulker-boxes")
        .description("Report chunks containing shulker boxes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> countBlockEntitiesBelow = sgDetection.add(new BoolSetting.Builder()
        .name("count-all-heights")
        .description("Count containers at any Y level instead of only those above the given height.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minimumY = sgDetection.add(new IntSetting.Builder()
        .name("minimum-y")
        .description("Ignore containers below this Y level when 'count-all-heights' is off.")
        .defaultValue(-64)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .visible(() -> !countBlockEntitiesBelow.get())
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw markers on found stashes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How stash markers are drawn.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Marker fill colour.")
        .defaultValue(new SettingColor(255, 215, 0, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Marker outline colour.")
        .defaultValue(new SettingColor(255, 215, 0))
        .visible(render::get)
        .build()
    );

    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Only draw markers within this distance.")
        .defaultValue(512)
        .min(16)
        .sliderRange(16, 4096)
        .visible(render::get)
        .build()
    );

    // Webhook

    private final WebhookSettings webhook = new WebhookSettings(sgWebhook);

    private final Setting<Boolean> notifyOnlyImprovements = sgWebhook.add(new BoolSetting.Builder()
        .name("only-improvements")
        .description("Only send a webhook when a stash grows or is found for the first time.")
        .defaultValue(true)
        .visible(webhook.enabled::get)
        .build()
    );

    private final List<StashManager.Stash> renderStashes = new ArrayList<>();
    private final Map<Long, Long> lastScanned = new HashMap<>();

    private int scansThisSecond;
    private long secondStart;

    public StashFinder() {
        super(BaseHuntingAddon.CATEGORY, "stash-finder", "Finds stashes and bases by counting storage containers in loaded chunks.");
    }

    @Override
    public void onActivate() {
        renderStashes.clear();
        lastScanned.clear();
        scansThisSecond = 0;
        secondStart = java.lang.System.currentTimeMillis();
        refreshRenderList();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.player == null || mc.world == null) return;

        WorldChunk chunk = event.chunk();
        ChunkPos pos = chunk.getPos();

        // Chunks get re-sent constantly while flying; re-scanning them is pure waste.
        long key = pos.toLong();
        long now = java.lang.System.currentTimeMillis();
        Long previous = lastScanned.get(key);
        if (previous != null && now - previous < 60_000L) return;
        lastScanned.put(key, now);

        scan(chunk, pos);
    }

    private void scan(WorldChunk chunk, ChunkPos pos) {
        if (!throttle()) return;

        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
        if (blockEntities.isEmpty()) return;

        int chests = 0;
        int barrels = 0;
        int shulkers = 0;
        int enderChests = 0;
        int furnaces = 0;
        int hoppers = 0;
        int dispensers = 0;
        int spawners = 0;

        int lowestY = Integer.MAX_VALUE;

        for (BlockEntity blockEntity : blockEntities.values()) {
            if (!countBlockEntitiesBelow.get() && blockEntity.getPos().getY() < minimumY.get()) continue;

            BlockEntityType<?> type = blockEntity.getType();
            Identifier id = Registries.BLOCK_ENTITY_TYPE.getId(type);
            if (id == null) continue;

            String name = id.getPath();

            switch (name) {
                case "chest", "trapped_chest" -> chests++;
                case "barrel" -> barrels++;
                case "shulker_box" -> {
                    if (detectShulkers.get()) shulkers++;
                }
                case "ender_chest" -> enderChests++;
                case "furnace", "blast_furnace", "smoker" -> furnaces++;
                case "hopper" -> hoppers++;
                case "dispenser", "dropper" -> dispensers++;
                case "mob_spawner", "trial_spawner" -> {
                    if (detectSpawners.get()) spawners++;
                }
                default -> {
                    // Not a container we care about.
                }
            }

            lowestY = Math.min(lowestY, blockEntity.getPos().getY());
        }

        int total = chests + barrels + shulkers + enderChests + furnaces + hoppers + dispensers;

        boolean spawnerHit = detectSpawners.get() && spawners > 0;
        if (total < minStorageCount.get() && !spawnerHit) return;

        int score = chests * 2 + barrels * 2 + shulkers * 8 + enderChests + furnaces + hoppers + dispensers + spawners * 4;
        if (score < minScore.get() && !spawnerHit) return;

        int centerX = pos.getCenterX();
        int centerZ = pos.getCenterZ();

        if (minDistanceFromSpawn.get() > 0 && ServerUtils.spawnDistance(centerX, centerZ) < minDistanceFromSpawn.get()) return;

        int y = lowestY == Integer.MAX_VALUE ? (int) mc.player.getY() : lowestY;

        StashManager.Stash stash = new StashManager.Stash(
            ServerUtils.serverAddress(),
            new BlockPos(centerX, y, centerZ),
            StashManager.Kind.Stash
        );

        stash.chests = chests;
        stash.barrels = barrels;
        stash.shulkers = shulkers;
        stash.enderChests = enderChests;
        stash.furnaces = furnaces;
        stash.hoppers = hoppers;
        stash.dispensers = dispensers;
        stash.spawners = spawners;

        boolean isNew = true;
        int previousScore = 0;

        StashManager manager = StashManager.get();
        if (saveToStore.get() && manager != null) {
            StashManager.Stash existing = manager.findNearby(stash.server, stash.pos, mergeRadius.get());
            if (existing != null) {
                isNew = false;
                previousScore = existing.score();
                manager.addOrMerge(stash, mergeRadius.get());
            } else {
                manager.addOrMerge(stash, mergeRadius.get());
            }
        }

        if (!isNew && notifyOnlyImprovements.get() && stash.score() <= previousScore) return;

        onStashFound(stash, isNew);
    }

    private boolean throttle() {
        long now = java.lang.System.currentTimeMillis();
        if (now - secondStart >= 1000L) {
            secondStart = now;
            scansThisSecond = 0;
        }

        if (scansThisSecond >= maxChunksPerSecond.get()) return false;
        scansThisSecond++;
        return true;
    }

    private void onStashFound(StashManager.Stash stash, boolean isNew) {
        if (chatFeedback.get()) {
            info("%s stash at (highlight)%d, %d, %d (default)[%s]",
                isNew ? "Found" : "Updated",
                stash.pos.getX(), stash.pos.getY(), stash.pos.getZ(),
                stash.describeCounts());
        }

        refreshRenderList();

        if (!webhook.isEnabled() || WebhookSettings.globallyMuted()) return;

        webhook.send(new WebhookManager.Embed()
            .title(isNew ? "Stash found" : "Stash updated")
            .color(0xFFD700)
            .field("Coordinates", "%d, %d, %d".formatted(stash.pos.getX(), stash.pos.getY(), stash.pos.getZ()))
            .field("Server", stash.server)
            .field("Dimension", stash.dimension)
            .field("Spawn distance", String.valueOf(ServerUtils.spawnDistance(stash.pos.getX(), stash.pos.getZ())))
            .field("Contents", stash.describeCounts(), false)
            .field("Score", String.valueOf(stash.score()))
            .footer("Base Hunting Client"));
    }

    private void refreshRenderList() {
        renderStashes.clear();

        StashManager manager = StashManager.get();
        if (manager == null) return;

        for (StashManager.Stash stash : manager.forCurrentServer()) {
            if (stash.kind == StashManager.Kind.Stash || stash.kind == StashManager.Kind.Base || stash.kind == StashManager.Kind.Manual) {
                renderStashes.add(stash);
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        if (renderStashes.isEmpty()) refreshRenderList();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null) return;

        double maxDistanceSq = (double) renderDistance.get() * renderDistance.get();

        for (StashManager.Stash stash : renderStashes) {
            if (!stash.dimension.equals(ServerUtils.dimension())) continue;
            if (stash.pos.toCenterPos().squaredDistanceTo(mc.player.getEntityPos()) > maxDistanceSq) continue;

            event.renderer.box(
                stash.pos.getX() - 8, stash.pos.getY() - 4, stash.pos.getZ() - 8,
                stash.pos.getX() + 8, stash.pos.getY() + 4, stash.pos.getZ() + 8,
                sideColor.get(), lineColor.get(), shapeMode.get(), 0
            );
        }
    }
}
