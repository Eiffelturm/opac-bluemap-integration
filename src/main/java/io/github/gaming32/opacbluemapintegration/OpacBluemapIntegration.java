package io.github.gaming32.opacbluemapintegration;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import xaero.pac.common.claims.player.api.IPlayerClaimPosListAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;

import java.util.*;
import java.util.stream.Collectors;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod(OpacBluemapIntegration.MOD_ID)
public final class OpacBluemapIntegration {
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String MARKER_SET_KEY = "opac-bluemap-integration";

    private static MinecraftServer minecraftServer;

    private static int updateIn;

    public static final String MOD_ID = "opac_bluemap_integration";

    public OpacBluemapIntegration() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, OpacBluemapConfig.serverSpec);
        BlueMapAPI.onEnable(OpacBluemapIntegration::updateClaims);
        MinecraftForge.EVENT_BUS.register(OpacBluemapModEvents.class);
    }

    public static class OpacBluemapModEvents {
        @SubscribeEvent
        public static void serverStarted ( final ServerStartedEvent ev){
            minecraftServer = ev.getServer();
        }

        @SubscribeEvent
        public static void serverStopping ( final ServerStoppingEvent ev){
            minecraftServer = null;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void serverTick ( final TickEvent.ServerTickEvent ev){
            if (updateIn <= 0) return;
            if (--updateIn <= 0) {
                BlueMapAPI.getInstance().ifPresent(OpacBluemapIntegration::updateClaims);
            }
        }

        @SubscribeEvent
        public static void commandEvent ( final RegisterCommandsEvent ev){
            ev.getDispatcher().register(Commands.literal("openpac-bluemap")
                    .requires(s -> s.hasPermission(2))
                    .then(literal("refresh-now")
                            .requires(s -> BlueMapAPI.getInstance().isPresent())
                            .executes(ctx -> {
                                final BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
                                if (api == null) {
                                    ctx.getSource().sendFailure(Component.literal("BlueMap not loaded").withStyle(ChatFormatting.RED));
                                    return 0;
                                }
                                updateClaims(api);
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("BlueMap OpenPaC claims refreshed").withStyle(ChatFormatting.GREEN),
                                        true
                                );
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    .then(literal("refresh-in")
                            .executes(ctx -> {
                                ctx.getSource().sendSuccess(() -> Component.literal("OpenPaC BlueMap will refresh in ").append(
                                                Component.literal((updateIn / 20) + "s").withStyle(ChatFormatting.GREEN)
                                        ),
                                        true
                                );
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(argument("time", TimeArgument.time())
                                    .executes(ctx -> {
                                        updateIn = IntegerArgumentType.getInteger(ctx, "time");
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("OpenPaC BlueMap will refresh in ").append(
                                                        Component.literal((updateIn / 20) + "s").withStyle(ChatFormatting.GREEN)
                                                ),
                                                true
                                        );
                                        return Command.SINGLE_SUCCESS;
                                    })
                            )
                    )
                    .then(literal("refresh-every")
                            .executes(ctx -> {
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("OpenPaC BlueMap auto refreshes every ").append(
                                                Component.literal((OpacBluemapConfig.SERVER.updateInterval.get() / 20) + "s").withStyle(ChatFormatting.GREEN)
                                        ),
                                        true
                                );
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(argument("interval", TimeArgument.time())
                                    .executes(ctx -> {
                                        final int interval = IntegerArgumentType.getInteger(ctx, "interval");
                                        OpacBluemapConfig.SERVER.updateInterval.set(interval);
                                        if (interval < updateIn) {
                                            updateIn = interval;
                                        }
                                        OpacBluemapConfig.SERVER.updateInterval.save();
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("OpenPaC BlueMap will auto refresh every ").append(
                                                        Component.literal((interval / 20) + "s").withStyle(ChatFormatting.GREEN)
                                                ),
                                                true
                                        );
                                        return Command.SINGLE_SUCCESS;
                                    })
                            )
                    )
                    .then(literal("reload")
                            .executes(ctx -> {
                                if (OpacBluemapConfig.SERVER.updateInterval.get() < updateIn) {
                                    updateIn = OpacBluemapConfig.SERVER.updateInterval.get();
                                }
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("Reloaded OpenPaC BlueMap config").withStyle(ChatFormatting.GREEN),
                                        true
                                );
                                return Command.SINGLE_SUCCESS;
                            })
                    )
            );
        }
    }

    public static void updateClaims(BlueMapAPI blueMap) {
        if (minecraftServer == null) {
            LOGGER.warn("updateClaims called with minecraftServer == null!");
            return;
        }
        LOGGER.info("Refreshing OpenPaC BlueMap markers");
        OpenPACServerAPI.get(minecraftServer)
            .getServerClaimsManager()
            .getPlayerInfoStream()
            .forEach(playerClaimInfo -> {
                String name = playerClaimInfo.getClaimsName();
                final String idName;
                if (StringUtils.isBlank(name)) {
                    idName = name = playerClaimInfo.getPlayerUsername();
                    if (name.length() > 2 && name.charAt(0) == '"' && name.charAt(name.length() - 1) == '"') {
                        name = name.substring(1, name.length() - 1) + " claim";
                    } else {
                        name += "'s claim";
                    }
                } else {
                    idName = name;
                }
                final String displayName = name;
                playerClaimInfo.getStream().forEach(entry -> {
                    final BlueMapWorld world = blueMap.getWorld(ResourceKey.create(Registries.DIMENSION, entry.getKey())).orElse(null);                    if (world == null) return;
                    final List<ShapeHolder> shapes = createShapes(
                        entry.getValue()
                            .getStream()
                            .flatMap(IPlayerClaimPosListAPI::getStream)
                            .collect(Collectors.toSet())
                    );
                    world.getMaps().forEach(map -> {
                        final Map<String, Marker> markers = map
                            .getMarkerSets()
                            .computeIfAbsent(MARKER_SET_KEY, k ->
                                MarkerSet.builder()
                                    .toggleable(true)
                                    .label("Open Parties and Claims")
                                    .build()
                            )
                            .getMarkers();
                        final float minY = OpacBluemapConfig.SERVER.markerMinY.get().floatValue();
                        final float maxY = OpacBluemapConfig.SERVER.markerMaxY.get().floatValue();
                        //noinspection SuspiciousNameCombination
                        final boolean flatPlane = Mth.equal(minY, maxY);
                        markers.keySet().removeIf(k -> k.startsWith(idName + "---"));
                        for (int i = 0; i < shapes.size(); i++) {
                            final ShapeHolder shape = shapes.get(i);
                            markers.put(idName + "---" + i,
                                // Yes these builders are the same. No they don't share a superclass (except for label).
                                flatPlane
                                    ? ShapeMarker.builder()
                                        .label(displayName)
                                        .fillColor(new Color(playerClaimInfo.getClaimsColor(), 150))
                                        .lineColor(new Color(playerClaimInfo.getClaimsColor(), 255))
                                        .shape(shape.baseShape(), minY)
                                        .holes(shape.holes())
                                        .depthTestEnabled(OpacBluemapConfig.SERVER.depthTest.get())
                                        .build()
                                    : ExtrudeMarker.builder()
                                        .label(displayName)
                                        .fillColor(new Color(playerClaimInfo.getClaimsColor(), 150))
                                        .lineColor(new Color(playerClaimInfo.getClaimsColor(), 255))
                                        .shape(shape.baseShape(), minY, maxY)
                                        .holes(shape.holes())
                                        .depthTestEnabled(OpacBluemapConfig.SERVER.depthTest.get())
                                        .build()
                            );
                        }
                    });
                });
            });
        LOGGER.info("Refreshed OpenPaC BlueMap markers");
        updateIn = OpacBluemapConfig.SERVER.updateInterval.get();
    }

    public static List<ShapeHolder> createShapes(Set<ChunkPos> chunks) {
        return createChunkGroups(chunks)
            .stream()
            .map(ShapeHolder::create)
            .toList();
    }

    public static List<Set<ChunkPos>> createChunkGroups(Set<ChunkPos> chunks) {
        final List<Set<ChunkPos>> result = new ArrayList<>();
        final Set<ChunkPos> visited = new HashSet<>();
        for (final ChunkPos chunk : chunks) {
            if (visited.contains(chunk)) continue;
            final Set<ChunkPos> neighbors = findNeighbors(chunk, chunks);
            result.add(neighbors);
            visited.addAll(neighbors);
        }
        return result;
    }

    public static Set<ChunkPos> findNeighbors(ChunkPos chunk, Set<ChunkPos> chunks) {
        if (!chunks.contains(chunk)) {
            throw new IllegalArgumentException("chunks must contain chunk to find neighbors!");
        }
        final Set<ChunkPos> visited = new HashSet<>();
        final Queue<ChunkPos> toVisit = new ArrayDeque<>();
        visited.add(chunk);
        toVisit.add(chunk);
        while (!toVisit.isEmpty()) {
            final ChunkPos visiting = toVisit.remove();
            for (final ChunkPosDirection dir : ChunkPosDirection.values()) {
                final ChunkPos offsetPos = dir.add(visiting);
                if (!chunks.contains(offsetPos) || !visited.add(offsetPos)) continue;
                toVisit.add(offsetPos);
            }
        }
        return visited;
    }
}
