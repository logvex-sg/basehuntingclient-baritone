package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.systems.StashManager;
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
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flags chunks that were generated after the server started, which is how you find player activity on
 * anarchy servers: a freshly generated chunk is one nobody has ever walked through.
 *
 * <p>Three independent signals are combined into a confidence score because no single one is reliable on
 * every server version or with every anti-cheat:
 * <ul>
 *     <li><b>inhabited time</b> - the server only raises this while a player is nearby, so a chunk that
 *         still reads zero has almost certainly never been visited.</li>
 *     <li><b>light data</b> - chunks generated after world creation are frequently sent without a
 *         populated light layer.</li>
 *     <li><b>flowing liquids</b> - liquids generate as source blocks; anything flowing means a block
 *         update happened, which only occurs in chunks that already existed.</li>
 * </ul>
 */
public class NewChunks extends Module {
    /** Block entities that world generation creates on its own; everything else implies a player. */
    private static final Set<String> NATURALLY_GENERATED_BLOCK_ENTITIES = Set.of(
        "minecraft:mob_spawner", "minecraft:chest", "minecraft:barrel", "minecraft:dispenser",
        "minecraft:dropper", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
        "minecraft:hopper", "minecraft:brewing_stand", "minecraft:enchanting_table",
        "minecraft:end_portal", "minecraft:nether_portal", "minecraft:beacon",
        "minecraft:skull", "minecraft:banner", "minecraft:sign", "minecraft:hanging_sign",
        "minecraft:decorated_pot", "minecraft:lectern", "minecraft:sculk_sensor",
        "minecraft:sculk_shrieker", "minecraft:calibrated_sculk_sensor", "minecraft:creaking_heart",
        "minecraft:trial_spawner", "minecraft:vault", "minecraft:beehive", "minecraft:campfire",
        "minecraft:soul_campfire", "minecraft:bell", "minecraft:jigsaw", "minecraft:structure_block",
        "minecraft:command_block", "minecraft:barrier"
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print newly found chunks in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> saveToStore = sgGeneral.add(new BoolSetting.Builder()
        .name("save-to-store")
        .description("Remember detected chunks so they persist between sessions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> mergeRadius = sgGeneral.add(new IntSetting.Builder()
        .name("store-merge-radius")
        .description("Detected chunks within this distance are merged into a single stored cluster.")
        .defaultValue(64)
        .min(0)
        .sliderRange(0, 512)
        .visible(saveToStore::get)
        .build()
    );

    private final Setting<Integer> clearAfterMinutes = sgGeneral.add(new IntSetting.Builder()
        .name("clear-after-minutes")
        .description("Forget detected chunks this many minutes after they were found. 0 keeps them forever.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 240)
        .build()
    );

    // Detection

    private final Setting<Boolean> useInhabitedTime = sgDetection.add(new BoolSetting.Builder()
        .name("use-inhabited-time")
        .description("Treat chunks with zero inhabited time as newly generated.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxInhabitedTime = sgDetection.add(new IntSetting.Builder()
        .name("max-inhabited-time")
        .description("Inhabited time (in ticks) below which a chunk still counts as new.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 200)
        .visible(useInhabitedTime::get)
        .build()
    );

    private final Setting<Boolean> useLightData = sgDetection.add(new BoolSetting.Builder()
        .name("use-light-data")
        .description("Treat chunks sent without a populated light layer as newly generated.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useFlowingLiquids = sgDetection.add(new BoolSetting.Builder()
        .name("use-flowing-liquids")
        .description("Scan chunks for flowing liquids, which prove a chunk is old. Slower but very reliable.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useBlockEntities = sgDetection.add(new BoolSetting.Builder()
        .name("use-block-entities")
        .description("Chunks containing player placed blocks or containers can never be new.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minConfidence = sgDetection.add(new IntSetting.Builder()
        .name("min-confidence")
        .description("How many signals must agree before a chunk is reported.")
        .defaultValue(2)
        .min(1)
        .max(4)
        .sliderRange(1, 4)
        .build()
    );

    private final Setting<Integer> minDistanceFromSpawn = sgDetection.add(new IntSetting.Builder()
        .name("min-distance-from-spawn")
        .description("Ignore chunks closer to world spawn than this. 0 disables the check.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 20000)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlight detected chunks in the world.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How chunk markers are drawn.")
        .defaultValue(ShapeMode.Lines)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> newChunkColor = sgRender.add(new ColorSetting.Builder()
        .name("new-chunk-color")
        .description("Colour used for newly generated chunks.")
        .defaultValue(new SettingColor(0, 200, 255, 60))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> oldChunkColor = sgRender.add(new ColorSetting.Builder()
        .name("old-chunk-color")
        .description("Colour used for chunks proven to be old.")
        .defaultValue(new SettingColor(255, 80, 80, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> renderOldChunks = sgRender.add(new BoolSetting.Builder()
        .name("render-old-chunks")
        .description("Also draw chunks that were proven to be old.")
        .defaultValue(false)
        .visible(render::get)
        .build()
    );

    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Only render chunk markers within this many blocks.")
        .defaultValue(256)
        .min(16)
        .sliderRange(16, 1024)
        .visible(render::get)
        .build()
    );

    private final Setting<Integer> renderY = sgRender.add(new IntSetting.Builder()
        .name("render-y")
        .description("Y level at which chunk markers are drawn.")
        .defaultValue(64)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .visible(render::get)
        .build()
    );

    // Webhook

    private final WebhookSettings webhook = new WebhookSettings(sgWebhook);

    private final Setting<Boolean> notifyPerChunk = sgWebhook.add(new BoolSetting.Builder()
        .name("notify-per-chunk")
        .description("Send a webhook for every detected chunk. Noisy, but complete.")
        .defaultValue(false)
        .visible(webhook.enabled::get)
        .build()
    );

    private final Setting<Integer> notifyIntervalSeconds = sgWebhook.add(new IntSetting.Builder()
        .name("notify-interval")
        .description("How often to summarise newly detected chunks, in seconds.")
        .defaultValue(60)
        .min(5)
        .sliderRange(5, 600)
        .visible(() -> webhook.enabled.get() && !notifyPerChunk.get())
        .build()
    );

    private final Setting<Integer> notifyMinCluster = sgWebhook.add(new IntSetting.Builder()
        .name("notify-min-cluster")
        .description("Only summarise when at least this many chunks were found in the interval.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 64)
        .visible(() -> webhook.enabled.get() && !notifyPerChunk.get())
        .build()
    );

    private final Set<Long> newChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> oldChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingScan = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> foundAt = new ConcurrentHashMap<>();

    private final List<Long> notifyQueue = new ArrayList<>();
    private long lastNotifyTime;
    private long lastCleanup;

    public NewChunks() {
        super(BaseHuntingAddon.CATEGORY, "new-chunks", "Detects chunks generated after the world was created, revealing player activity.");
    }

    @Override
    public void onActivate() {
        newChunks.clear();
        oldChunks.clear();
        pendingScan.clear();
        notifyQueue.clear();
        lastNotifyTime = java.lang.System.currentTimeMillis();
        lastCleanup = lastNotifyTime;
    }

    @Override
    public String getInfoString() {
        return "%d/%d".formatted(newChunks.size(), oldChunks.size());
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.player == null || mc.world == null) return;

        WorldChunk chunk = event.chunk();
        ChunkPos pos = chunk.getPos();
        long key = pos.toLong();

        if (newChunks.contains(key) || oldChunks.contains(key)) return;
        if (!pendingScan.add(key)) return;

        // The chunk is still being populated while this event fires, so the scan is deferred by a tick
        // to let the client finish filling in light and block entity data.
        MeteorExecutor.execute(() -> {
            try {
                evaluate(chunk, pos, key);
            } catch (Exception e) {
                BaseHuntingAddon.LOG.debug("Chunk scan failed for {}", pos, e);
            } finally {
                pendingScan.remove(key);
            }
        });
    }

    private void evaluate(WorldChunk chunk, ChunkPos pos, long key) {
        int signals = 0;
        int required = minConfidence.get();
        StringBuilder reasons = new StringBuilder();

        if (minDistanceFromSpawn.get() > 0) {
            int distance = ServerUtils.spawnDistance(pos.getCenterX(), pos.getCenterZ());
            if (distance < minDistanceFromSpawn.get()) return;
        }

        boolean inhabitedTimeSignal = false;
        if (useInhabitedTime.get()) {
            long inhabited = chunk.getInhabitedTime();
            inhabitedTimeSignal = inhabited <= maxInhabitedTime.get();
            if (inhabitedTimeSignal) signals++;
            reasons.append(inhabitedTimeSignal ? "inhabited=0 " : "inhabited=").append(inhabited).append(' ');
        }

        boolean lightSignal = false;
        if (useLightData.get()) {
            lightSignal = !chunk.isLightOn();
            if (lightSignal) signals++;
            reasons.append(lightSignal ? "no-light " : "lit ");
        }

        // Any flowing liquid or block entity proves the chunk has been touched since generation, so these
        // act as hard vetoes rather than just extra confidence.
        boolean provenOld = false;

        if (useFlowingLiquids.get()) {
            if (hasFlowingLiquid(chunk)) {
                provenOld = true;
                reasons.append("flowing-liquid ");
            }
        }

        if (!provenOld && useBlockEntities.get() && hasPlayerBlockEntities(chunk)) {
            provenOld = true;
            reasons.append("block-entities ");
        }

        if (provenOld) {
            oldChunks.add(key);
            if (chatFeedback.get()) info("Old chunk at (highlight)%d, %d (default)- %s", pos.getCenterX(), pos.getCenterZ(), reasons.toString().trim());
            return;
        }

        if (signals < required) return;

        newChunks.add(key);
        foundAt.put(key, java.lang.System.currentTimeMillis());
        onNewChunkFound(pos, reasons.toString().trim());
    }

    private boolean hasFlowingLiquid(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();

        for (int index = 0; index < sections.length; index++) {
            ChunkSection section = sections[index];
            if (section == null || section.isEmpty()) continue;

            // Palettes are the fast path: an empty fluid palette means there is nothing to inspect here.
            if (!section.getBlockStateContainer().hasAny(state -> !state.getFluidState().isEmpty())) continue;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        FluidState fluid = section.getFluidState(x, y, z);
                        if (fluid.isEmpty()) continue;
                        if (!fluid.isStill()) return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean hasPlayerBlockEntities(WorldChunk chunk) {
        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
        if (blockEntities.isEmpty()) return false;

        for (BlockEntity blockEntity : blockEntities.values()) {
            if (isPlayerPlaced(blockEntity)) return true;
        }

        return false;
    }

    /**
     * Natural generation only produces a handful of block entities. Anything else had to be placed by a
     * player, which means the chunk is not new.
     */
    private boolean isPlayerPlaced(BlockEntity blockEntity) {
        Identifier id = Registries.BLOCK_ENTITY_TYPE.getId(blockEntity.getType());
        if (id == null) return true;

        return !NATURALLY_GENERATED_BLOCK_ENTITIES.contains(id.toString());
    }

    private void onNewChunkFound(ChunkPos pos, String reasons) {
        if (chatFeedback.get()) {
            info("New chunk at (highlight)%d, %d (default)- %s", pos.getCenterX(), pos.getCenterZ(), reasons);
        }

        if (saveToStore.get()) {
            StashManager manager = StashManager.get();
            if (manager != null) {
                StashManager.Stash stash = new StashManager.Stash(
                    ServerUtils.serverAddress(),
                    new BlockPos(pos.getCenterX(), renderY.get(), pos.getCenterZ()),
                    StashManager.Kind.NewChunks
                );
                stash.dimension = ServerUtils.dimension();
                manager.addOrMerge(stash, mergeRadius.get());
            }
        }

        notifyQueue.add(pos.toLong());

        if (notifyPerChunk.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(buildChunkEmbed(pos, reasons));
        }
    }

    private WebhookManager.Embed buildChunkEmbed(ChunkPos pos, String reasons) {
        return new WebhookManager.Embed()
            .title("New chunk detected")
            .color(0x00C8FF)
            .field("Coordinates", "%d, %d".formatted(pos.getCenterX(), pos.getCenterZ()))
            .field("Server", ServerUtils.serverAddress())
            .field("Dimension", ServerUtils.dimension())
            .field("Signals", reasons.isBlank() ? "n/a" : reasons)
            .field("Player", ServerUtils.coordinates())
            .footer("Base Hunting Client");
    }

    public int getTrackedCount() {
        return newChunks.size();
    }

    public int getOldChunkCount() {
        return oldChunks.size();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = java.lang.System.currentTimeMillis();

        if (clearAfterMinutes.get() > 0 && now - lastCleanup > 30_000L) {
            lastCleanup = now;
            long cutoff = now - clearAfterMinutes.get() * 60_000L;
            newChunks.removeIf(key -> {
                Long found = foundAt.get(key);
                if (found == null || found > cutoff) return false;
                foundAt.remove(key);
                return true;
            });
        }

        if (!webhook.isEnabled() || notifyPerChunk.get() || notifyQueue.isEmpty()) return;
        if (now - lastNotifyTime < notifyIntervalSeconds.get() * 1000L) return;

        List<Long> batch = new ArrayList<>(notifyQueue);
        notifyQueue.clear();
        lastNotifyTime = now;

        if (batch.size() < notifyMinCluster.get()) return;
        if (WebhookSettings.globallyMuted()) return;

        long sumX = 0;
        long sumZ = 0;
        for (long key : batch) {
            sumX += ChunkPos.getPackedX(key) * 16 + 8;
            sumZ += ChunkPos.getPackedZ(key) * 16 + 8;
        }

        int centerX = (int) (sumX / batch.size());
        int centerZ = (int) (sumZ / batch.size());

        webhook.send(new WebhookManager.Embed()
            .title("New chunk cluster")
            .description("%d new chunks detected in the last %d seconds.".formatted(batch.size(), notifyIntervalSeconds.get()))
            .color(0x00C8FF)
            .field("Approximate center", "%d, %d".formatted(centerX, centerZ))
            .field("Server", ServerUtils.serverAddress())
            .field("Dimension", ServerUtils.dimension())
            .field("Spawn distance", String.valueOf(ServerUtils.spawnDistance(centerX, centerZ)))
            .footer("Base Hunting Client"));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null) return;

        int y = renderY.get();
        int maxDistanceSq = renderDistance.get() * renderDistance.get();

        for (long key : newChunks) {
            if (!shouldRender(key, maxDistanceSq)) continue;
            renderChunk(event, key, y, newChunkColor.get());
        }

        if (renderOldChunks.get()) {
            for (long key : oldChunks) {
                if (!shouldRender(key, maxDistanceSq)) continue;
                renderChunk(event, key, y, oldChunkColor.get());
            }
        }
    }

    private boolean shouldRender(long key, int maxDistanceSq) {
        int chunkX = ChunkPos.getPackedX(key) * 16 + 8;
        int chunkZ = ChunkPos.getPackedZ(key) * 16 + 8;

        double dx = chunkX - mc.player.getX();
        double dz = chunkZ - mc.player.getZ();

        return dx * dx + dz * dz <= maxDistanceSq;
    }

    private void renderChunk(Render3DEvent event, long key, int y, SettingColor color) {
        int x = ChunkPos.getPackedX(key) * 16;
        int z = ChunkPos.getPackedZ(key) * 16;

        event.renderer.box(x, y, z, x + 16, y + 0.05, z + 16, color, color, shapeMode.get(), 0);
    }
}
