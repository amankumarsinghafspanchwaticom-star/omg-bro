package com.arachnaid.manager;

import com.arachnaid.ArachnaidPlugin;
import com.arachnaid.item.ArachnaidItemFactory;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WallClimbManager {

    public enum StopReason {
        JUMP_OFF,
        SNEAK_DROP,
        TOGGLED_OFF,
        MANTLED,
        LANDED,
        LEFT_WALL,
        OTHER
    }

    private static final float DEFAULT_FLY_SPEED = 0.1f;
    private static final BlockFace[] CARDINALS = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final ArachnaidPlugin plugin;
    private final ArachnaidItemFactory itemFactory;
    private final Map<UUID, ClimbSession> activeSessions = new ConcurrentHashMap<>();
    private BukkitTask climbTask;

    public WallClimbManager(ArachnaidPlugin plugin, ArachnaidItemFactory itemFactory) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
    }

    public void startTask() {
        climbTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public boolean isClimbing(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    /**
     * Enters scaffolding-smooth wall crawl mode on the clicked wall face.
     * Uses flying state with flySpeed = 0.0f so:
     *  - Zero gravity bobbing / zero elevator launch
     *  - Zero airborne WASD drift fighting server velocity
     *  - Instant scaffolding-style dead stop the millisecond WASD is released
     *  - Zero false "flying is not enabled" kicks on Paper
     */
    public void startClimbing(Player player, BlockFace hitFace) {
        UUID id = player.getUniqueId();
        if (activeSessions.containsKey(id)) {
            return;
        }

        BlockFace intoWall = (hitFace != null) ? hitFace.getOppositeFace() : findNearestWallDirection(player, 1.0);
        if (intoWall == null) {
            return;
        }

        Input initialInput = player.getCurrentInput();
        boolean initJump = initialInput != null && initialInput.isJump();
        boolean initSneak = initialInput != null && initialInput.isSneak();

        boolean prevAllowFlight = player.getAllowFlight();
        boolean prevFlying = player.isFlying();
        float prevFlySpeed = player.getFlySpeed() > 0.005f ? player.getFlySpeed() : DEFAULT_FLY_SPEED;

        activeSessions.put(id, new ClimbSession(
                intoWall,
                System.currentTimeMillis(),
                prevAllowFlight,
                prevFlying,
                prevFlySpeed,
                initJump,
                initSneak
        ));

        player.setAllowFlight(true);
        player.setFlySpeed(0.0f);
        player.setFlying(true);

        // Instant dead stop on attach
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0f);

        player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.7f, 1.35f);
        player.sendActionBar(plugin.rawMsg("climb-start"));
    }

    public void handleInputUpdate(Player player, Input input) {
        ClimbSession session = activeSessions.get(player.getUniqueId());
        if (session == null || input == null) {
            return;
        }

        long graceMs = plugin.getConfig().getLong("wall-climb.cancel-grace-ms", 180L);
        boolean pastGrace = (System.currentTimeMillis() - session.startTimeMs()) >= graceMs;

        boolean jumpNow = input.isJump();
        boolean sneakNow = input.isSneak();

        boolean jumpPressed = jumpNow && !session.prevJump();
        boolean sneakPressed = sneakNow && !session.prevSneak();

        session.setPrevJump(jumpNow);
        session.setPrevSneak(sneakNow);

        if (!pastGrace) {
            return;
        }

        // Space = crisp jump-off away from wall
        if (jumpPressed) {
            stopClimbing(player, StopReason.JUMP_OFF);
            return;
        }

        // Shift = clean drop straight down with zero pushback
        if (sneakPressed) {
            stopClimbing(player, StopReason.SNEAK_DROP);
        }
    }

    public void stopClimbing(Player player, StopReason reason) {
        ClimbSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        restorePlayerFlightState(player, session);
        player.setFallDistance(0f);

        BlockFace intoWall = session.intoWall();

        switch (reason) {
            case JUMP_OFF -> {
                double push = plugin.getConfig().getDouble("wall-climb.jump-off-push", 0.36);
                double up = plugin.getConfig().getDouble("wall-climb.jump-off-upward", 0.38);
                Vector away = new Vector(-intoWall.getModX() * push, up, -intoWall.getModZ() * push);
                player.setVelocity(away);
                player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_HURT, 0.55f, 1.45f);
                player.sendActionBar(plugin.rawMsg("climb-cancelled"));
            }
            case SNEAK_DROP, TOGGLED_OFF -> {
                player.setVelocity(new Vector(0, -0.05, 0));
                player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_STEP, 0.5f, 0.9f);
                player.sendActionBar(plugin.rawMsg("climb-cancelled"));
            }
            case MANTLED -> {
                Vector vault = new Vector(intoWall.getModX() * 0.28, 0.40, intoWall.getModZ() * 0.28);
                player.setVelocity(vault);
                player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_STEP, 0.6f, 1.4f);
                player.sendActionBar(plugin.rawMsg("climb-stop"));
            }
            case LANDED, LEFT_WALL, OTHER -> {
                if (player.isOnline()) {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.sendActionBar(plugin.rawMsg("climb-stop"));
                }
            }
        }
    }

    /**
     * Crash-safe cleanup on player join in case the server hard-crashed while they were crawling.
     */
    public void cleanupAfterCrash(Player player) {
        if (player == null || isClimbing(player)) {
            return;
        }
        if (player.getFlySpeed() < 0.005f) {
            player.setFlySpeed(DEFAULT_FLY_SPEED);
            if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
                player.setFlying(false);
                player.setAllowFlight(false);
            }
        }
    }

    private void restorePlayerFlightState(Player player, ClimbSession session) {
        if (player == null) {
            return;
        }
        player.setFlySpeed(session.previousFlySpeed());
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            player.setAllowFlight(true);
            player.setFlying(session.previousFlying());
        } else {
            player.setFlying(false);
            player.setAllowFlight(session.previousAllowFlight());
        }
    }

    public void stopAll() {
        if (climbTask != null) {
            climbTask.cancel();
            climbTask = null;
        }
        for (UUID id : activeSessions.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                stopClimbing(player, StopReason.OTHER);
            }
        }
        activeSessions.clear();
    }

    private void tick() {
        if (activeSessions.isEmpty()) {
            return;
        }

        double attachDist = plugin.getConfig().getDouble("wall-climb.wall-attach-distance", 0.95);
        double upSpeed = plugin.getConfig().getDouble("wall-climb.climb-up-speed", 0.25);
        double downSpeed = plugin.getConfig().getDouble("wall-climb.climb-down-speed", 0.24);
        double strafeSpeed = plugin.getConfig().getDouble("wall-climb.strafe-speed", 0.22);
        double hugForce = plugin.getConfig().getDouble("wall-climb.wall-hug-force", 0.08);

        Iterator<Map.Entry<UUID, ClimbSession>> it = activeSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ClimbSession> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            ClimbSession session = entry.getValue();

            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }

            if (!itemFactory.isArachnaidAxe(player.getInventory().getItemInMainHand())) {
                stopClimbing(player, StopReason.OTHER);
                continue;
            }

            // Keep scaffolding-lock physics active every tick
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
            if (player.getFlySpeed() != 0.0f) {
                player.setFlySpeed(0.0f);
            }
            if (!player.isFlying()) {
                player.setFlying(true);
            }

            Input input = player.getCurrentInput();
            boolean w = input != null && input.isForward();
            boolean s = input != null && input.isBackward();
            boolean a = input != null && input.isLeft();
            boolean d = input != null && input.isRight();

            BlockFace intoWall = session.intoWall();

            // Verify wall contact
            if (!hasSolidWallInDirection(player, intoWall, attachDist)) {
                // Smooth auto-mantle over the top edge when holding W
                if (w && hasLedgeAtFeet(player, intoWall, attachDist)) {
                    stopClimbing(player, StopReason.MANTLED);
                    continue;
                }

                // Seamless corner wrap onto adjacent solid wall
                BlockFace alternate = findNearestWallDirection(player, attachDist);
                if (alternate != null) {
                    session.setIntoWall(alternate);
                    intoWall = alternate;
                } else {
                    stopClimbing(player, StopReason.LEFT_WALL);
                    continue;
                }
            }

            int vertical = (w ? 1 : 0) - (s ? 1 : 0); // W = Climb Up (+1), S = Climb Down (-1)
            int strafe = (d ? 1 : 0) - (a ? 1 : 0);   // D = Right (+1), A = Left (-1)

            // Pressing S down onto solid ground lands cleanly
            if (vertical < 0 && isTouchingFloor(player)) {
                stopClimbing(player, StopReason.LANDED);
                continue;
            }

            player.setFallDistance(0f);

            // NO WASD PRESSED -> INSTANT FULL STOP, ZERO DRIFT (Scaffolding freeze)
            if (vertical == 0 && strafe == 0) {
                player.setVelocity(new Vector(0, 0, 0));
                continue;
            }

            // Compute wall horizontal tangent (Right when facing intoWall)
            double rightX = -intoWall.getModZ();
            double rightZ = intoWall.getModX();

            // Keep A/D intuitive if player turns head away from wall
            Vector look = player.getLocation().getDirection();
            double facingDot = look.getX() * intoWall.getModX() + look.getZ() * intoWall.getModZ();
            if (facingDot < -0.25) {
                rightX = -rightX;
                rightZ = -rightZ;
            }

            double vy = (vertical > 0) ? upSpeed : (vertical < 0 ? -downSpeed : 0.0);
            double vx = rightX * strafe * strafeSpeed;
            double vz = rightZ * strafe * strafeSpeed;

            // Normalize diagonal speed so W+A / S+D stays smooth
            if (vertical != 0 && strafe != 0) {
                vx *= 0.78;
                vy *= 0.78;
                vz *= 0.78;
            }

            // Hug force keeps player locked flush to the wall on S and A/D
            vx += intoWall.getModX() * hugForce;
            vz += intoWall.getModZ() * hugForce;

            player.setVelocity(new Vector(vx, vy, vz));

            session.incrementMoveTicks();
            if (session.moveTicks() % 7 == 0) {
                Location loc = player.getLocation();
                World world = player.getWorld();
                world.playSound(loc, Sound.ENTITY_SPIDER_STEP, 0.35f, 1.3f);
                world.spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.5, 0), 4,
                        0.18, 0.18, 0.18, Material.COBWEB.createBlockData());
            }
        }
    }

    private boolean hasSolidWallInDirection(Player player, BlockFace dir, double distance) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null || dir == null) {
            return false;
        }
        double[] yOffsets = {0.2, 1.1};
        for (double yOff : yOffsets) {
            Block block = world.getBlockAt(
                    Location.locToBlock(base.getX() + dir.getModX() * distance),
                    Location.locToBlock(base.getY() + yOff),
                    Location.locToBlock(base.getZ() + dir.getModZ() * distance)
            );
            if (isClimbableWallBlock(block)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLedgeAtFeet(Player player, BlockFace dir, double distance) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null || dir == null) {
            return false;
        }
        Block ledge = world.getBlockAt(
                Location.locToBlock(base.getX() + dir.getModX() * distance),
                Location.locToBlock(base.getY() - 0.35),
                Location.locToBlock(base.getZ() + dir.getModZ() * distance)
        );
        Block aboveLedge = ledge.getRelative(BlockFace.UP);
        return isClimbableWallBlock(ledge) && !aboveLedge.getType().isSolid();
    }

    private boolean isTouchingFloor(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        Block below = world.getBlockAt(
                Location.locToBlock(loc.getX()),
                Location.locToBlock(loc.getY() - 0.15),
                Location.locToBlock(loc.getZ())
        );
        return below.getType().isSolid() && below.getType() != Material.COBWEB;
    }

    private BlockFace findNearestWallDirection(Player player, double distance) {
        for (BlockFace face : CARDINALS) {
            if (hasSolidWallInDirection(player, face, distance)) {
                return face;
            }
        }
        return null;
    }

    private boolean isClimbableWallBlock(Block block) {
        return block != null && block.getType().isSolid() && block.getType() != Material.COBWEB;
    }

    private static final class ClimbSession {
        private BlockFace intoWall;
        private final long startTimeMs;
        private final boolean previousAllowFlight;
        private final boolean previousFlying;
        private final float previousFlySpeed;
        private boolean prevJump;
        private boolean prevSneak;
        private int moveTicks = 0;

        private ClimbSession(
                BlockFace intoWall,
                long startTimeMs,
                boolean previousAllowFlight,
                boolean previousFlying,
                float previousFlySpeed,
                boolean prevJump,
                boolean prevSneak
        ) {
            this.intoWall = intoWall;
            this.startTimeMs = startTimeMs;
            this.previousAllowFlight = previousAllowFlight;
            this.previousFlying = previousFlying;
            this.previousFlySpeed = previousFlySpeed;
            this.prevJump = prevJump;
            this.prevSneak = prevSneak;
        }

        BlockFace intoWall() { return intoWall; }
        void setIntoWall(BlockFace intoWall) { this.intoWall = intoWall; }
        long startTimeMs() { return startTimeMs; }
        boolean previousAllowFlight() { return previousAllowFlight; }
        boolean previousFlying() { return previousFlying; }
        float previousFlySpeed() { return previousFlySpeed; }
        boolean prevJump() { return prevJump; }
        void setPrevJump(boolean prevJump) { this.prevJump = prevJump; }
        boolean prevSneak() { return prevSneak; }
        void setPrevSneak(boolean prevSneak) { this.prevSneak = prevSneak; }
        int moveTicks() { return moveTicks; }
        void incrementMoveTicks() { moveTicks++; }
    }
}
