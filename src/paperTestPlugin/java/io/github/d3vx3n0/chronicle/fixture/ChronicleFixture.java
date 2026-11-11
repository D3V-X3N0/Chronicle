package io.github.d3vx3n0.chronicle.fixture;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Tests real Paper dispatch and world sampling with explicitly synthetic player events. */
public final class ChronicleFixture extends JavaPlugin implements Listener {
    private static final UUID ACTOR = UUID.fromString("63a10968-0494-4ff3-874c-012b73f9b00a");
    private final ObservationLog observations = new ObservationLog(256);
    private final ArrayDeque<ObservationLog.Entry> pending = new ArrayDeque<>();
    private boolean cancelPlace;
    private boolean cancelBreak;
    private boolean runningSyntheticCases;
    private int multiPlaceSkipped;
    private PacketFixture packetFixture;

    @Override
    public void onEnable() {
        if (Boolean.getBoolean("chronicle.fixture.packets")) {
            packetFixture = new PacketFixture(this);
            getServer().getPluginManager().registerEvents(packetFixture, this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::sampleLaterStates, 1, 1);
        getLogger().warning("Diagnostic fixture only: cancelled attempts are included; later samples are NOT event post-states.");
    }

    @EventHandler
    public void onServerLoaded(ServerLoadEvent event) {
        if (event.getType() == ServerLoadEvent.LoadType.STARTUP && Boolean.getBoolean("chronicle.fixture.autorun")) {
            getServer().getScheduler().runTask(this, this::runSyntheticCases);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void cancelFixturePlace(BlockPlaceEvent event) {
        if (runningSyntheticCases && cancelPlace) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void cancelFixtureBreak(BlockBreakEvent event) {
        if (runningSyntheticCases && cancelBreak) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void observePlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent) {
            multiPlaceSkipped++;
            return;
        }
        observe("PLACE_ATTEMPT", event.getPlayer(), event.getBlock(), event.isCancelled(), event.canBuild(),
                event.getBlockReplacedState().getBlockData().getAsString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void observeBreak(BlockBreakEvent event) {
        observe("BREAK_ATTEMPT", event.getPlayer(), event.getBlock(), event.isCancelled(), true,
                event.getBlock().getBlockData().getAsString());
    }

    private void observe(String action, Player actor, Block block, boolean cancelled, boolean canBuild, String before) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Fixture capture requires the Paper server thread.");
        }
        var position = new ObservationLog.Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        long id = observations.observe(action, actor.getUniqueId(), position, Bukkit.getCurrentTick(), cancelled,
                canBuild, before, block.getBlockData().getAsString());
        if (id != -1) {
            pending.addLast(observations.entry(id));
        }
    }

    private void sampleLaterStates() {
        int remaining = 64;
        while (remaining-- > 0 && !pending.isEmpty()) {
            if (pending.getFirst().eventTick() == Bukkit.getCurrentTick()) {
                break;
            }
            ObservationLog.Entry entry = pending.removeFirst();
            var position = entry.position();
            var world = Bukkit.getWorld(position.world());
            if (world == null || !world.isChunkLoaded(Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16))) {
                observations.sample(entry.sequence(), null, Bukkit.getCurrentTick(), "WORLD_OR_CHUNK_UNAVAILABLE");
            } else {
                String state = world.getBlockAt(position.x(), position.y(), position.z()).getBlockData().getAsString();
                observations.sample(entry.sequence(), state, Bukkit.getCurrentTick(), "LATER_SAMPLE_ONLY");
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (packetFixture != null && packetFixture.command(sender, args)) return true;
        if (!sender.hasPermission("chronicle.fixture")) {
            sender.sendMessage(Component.text("You need chronicle.fixture to view diagnostic attempts."));
            return true;
        }
        sender.sendMessage(Component.text("Diagnostic attempts only; no committed history. Rejected="
                + observations.rejected() + ", multi-place unsupported=" + multiPlaceSkipped));
        var snapshot = observations.snapshot();
        snapshot.stream().skip(Math.max(0, snapshot.size() - 10)).forEach(entry -> sender.sendMessage(Component.text(entry.toString())));
        return true;
    }

    private void runSyntheticCases() {
        try {
            require(Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Chronicle")).isEnabled(), "Chronicle enabled");
            require(Bukkit.getMinecraftVersion().equals("26.3"), "pinned Minecraft version");
            checkCommands();
            verifyThreadGuard();
            var world = Bukkit.getWorlds().getFirst();
            var origin = world.getSpawnLocation().clone().add(0, 5, 0);
            var player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> ACTOR;
                        case "getName" -> "ChronicleSyntheticFixture";
                        case "toString" -> "Synthetic player; no network client";
                        default -> throw new UnsupportedOperationException("Unexpected synthetic-player call: " + method.getName());
                    });

            runningSyntheticCases = true;
            Block placed = origin.getBlock();
            placed.setType(Material.AIR, false);
            place(placed, player, true);

            Block cancelled = origin.clone().add(1, 0, 0).getBlock();
            cancelled.setType(Material.AIR, false);
            cancelPlace = true;
            place(cancelled, player, true);
            cancelPlace = false;

            Block denied = origin.clone().add(2, 0, 0).getBlock();
            denied.setType(Material.AIR, false);
            place(denied, player, false);

            Block broken = origin.clone().add(3, 0, 0).getBlock();
            broken.setType(Material.STONE, false);
            breakBlock(broken, player);

            Block cancelledBreak = origin.clone().add(4, 0, 0).getBlock();
            cancelledBreak.setType(Material.STONE, false);
            cancelBreak = true;
            breakBlock(cancelledBreak, player);
            cancelBreak = false;

            Block repeated = origin.clone().add(5, 0, 0).getBlock();
            repeated.setType(Material.STONE, false);
            breakBlock(repeated, player);
            place(repeated, player, true);
            runningSyntheticCases = false;

            getServer().getScheduler().runTaskLater(this, () -> verifySamples(placed, cancelled, denied, broken, cancelledBreak), 3);
        } catch (Exception | AssertionError failure) {
            fail(failure);
        } finally {
            runningSyntheticCases = false;
            cancelPlace = false;
            cancelBreak = false;
        }
    }

    private void checkCommands() {
        require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chronicle status"), "real console dispatch");
        var command = Objects.requireNonNull(Bukkit.getPluginCommand("chronicle"));
        var messages = new ArrayList<String>();
        CommandSender allowed = sender(messages, true);
        require(command.execute(allowed, "chronicle", new String[] {"status"}), "status command handled");
        require(messages.stream().anyMatch(message -> message.contains("NOT IMPLEMENTED")), "honest implementation status");
        messages.clear();
        command.execute(sender(messages, false), "chronicle", new String[] {"status"});
        require(messages.stream().noneMatch(message -> message.contains("Runtime:")), "unauthorised runtime information hidden");
    }

    private void verifyThreadGuard() throws Exception {
        var chronicle = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Chronicle"));
        var capture = org.bukkit.event.HandlerList.getRegisteredListeners(chronicle).stream()
                .map(org.bukkit.plugin.RegisteredListener::getListener)
                .filter(listener -> listener.getClass().getSimpleName().equals("PaperBlockCapture")).findFirst().orElseThrow();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread = Thread.ofPlatform().name("chronicle-fixture-thread-guard").start(() -> {
            try { capture.getClass().getMethod("breaking", BlockBreakEvent.class).invoke(capture, new Object[] {null}); }
            catch (ReflectiveOperationException exception) { failure.set(exception.getCause()); }
        });
        thread.join(1000);
        require(!thread.isAlive() && failure.get() instanceof IllegalStateException
                && failure.get().getMessage().contains("server thread"), "off-thread capture refuses before touching event/world");
    }

    private static CommandSender sender(List<String> messages, boolean permission) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission", "isPermissionSet", "isOp" -> permission;
                    case "getName", "toString" -> "ChronicleFixtureSender";
                    case "getServer" -> Bukkit.getServer();
                    case "sendMessage" -> {
                        for (Object arg : args) {
                            if (arg instanceof Component component) {
                                messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                            } else if (arg instanceof String text) {
                                messages.add(text);
                            } else if (arg instanceof String[] texts) {
                                messages.addAll(List.of(texts));
                            }
                        }
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException("Unexpected sender call: " + method.getName());
                });
    }

    private static void place(Block block, Player player, boolean canBuild) {
        BlockState before = block.getState();
        block.setType(Material.STONE, false);
        var event = new BlockPlaceEvent(block, before, block.getRelative(0, -1, 0),
                new ItemStack(Material.STONE), player, canBuild, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            block.setBlockData(before.getBlockData(), false);
        }
    }

    private static void breakBlock(Block block, Player player) {
        var event = new BlockBreakEvent(block, player);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            block.setType(Material.AIR, false);
        }
    }

    private void verifySamples(Block placed, Block cancelled, Block denied, Block broken, Block cancelledBreak) {
        try {
            var entries = observations.snapshot();
            require(entries.size() == 7, "seven ordered diagnostic attempts");
            require(entries.get(1).cancelled() && !entries.get(2).canBuild() && entries.get(4).cancelled(), "cancellation and denial observed");
            require(placed.getType() == Material.STONE && cancelled.getType() == Material.AIR
                    && denied.getType() == Material.AIR && broken.getType() == Material.AIR
                    && cancelledBreak.getType() == Material.STONE, "synthetic fixture world outcomes");
            for (var entry : entries) {
                require(entry.outcome().equals("LATER_SAMPLE_ONLY") && entry.sampledTick() > entry.eventTick(), "sample is later than event");
                require(entry.actor().equals(ACTOR), "actor UUID retained");
                getLogger().info("CAPTURE_OBSERVATION " + entry);
            }
            require(entries.get(5).action().equals("BREAK_ATTEMPT") && entries.get(6).action().equals("PLACE_ATTEMPT"), "same-tick order retained");
            require(entries.get(5).later().equals("minecraft:stone") && entries.get(6).later().equals("minecraft:stone"),
                    "later sample cannot identify the intermediate air state");
            getLogger().info("CHRONICLE_FIXTURE_PASS synthetic-dispatch-and-world-sampling; real-player qualification still required");
            Bukkit.shutdown();
        } catch (Exception | AssertionError failure) {
            fail(failure);
        }
    }

    private static void require(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private void fail(Throwable failure) {
        getLogger().log(java.util.logging.Level.SEVERE, "CHRONICLE_FIXTURE_FAIL", failure);
        Bukkit.shutdown();
    }
}
