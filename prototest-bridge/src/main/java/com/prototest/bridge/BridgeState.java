package com.prototest.bridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;

/**
 * Read-only projections of the live server state.
 *
 * <p>Every method here must be called on the server main thread; {@link BridgeServer} takes care of that.
 */
final class BridgeState {

    private final PrototestBridge plugin;

    private volatile String blockStatesCache;
    private volatile long blockStatesCacheAt;
    private volatile String itemsCache;
    private volatile long itemsCacheAt;
    private volatile String entityTypesCache;

    BridgeState(PrototestBridge plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ status

    Map<String, Object> status() {
        List<Object> worlds = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            worlds.add(world(w));
        }
        List<Object> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            players.add(playerBrief(p));
        }
        double[] tps = Bukkit.getTPS();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("plugin", "prototest-bridge");
        m.put("pluginVersion", plugin.getPluginMeta().getVersion());
        m.put("serverVersion", Bukkit.getVersion());
        m.put("bukkitVersion", Bukkit.getBukkitVersion());
        m.put("minecraftVersion", Bukkit.getMinecraftVersion());
        m.put("port", Bukkit.getPort());
        m.put("ip", Bukkit.getIp());
        m.put("tick", Bukkit.getCurrentTick());
        m.put("tps", new double[] {tps.length > 0 ? tps[0] : 0, tps.length > 1 ? tps[1] : 0, tps.length > 2 ? tps[2] : 0});
        m.put("avgTickTimeMs", Bukkit.getAverageTickTime());
        m.put("maxPlayers", Bukkit.getMaxPlayers());
        m.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
        m.put("players", players);
        m.put("worlds", worlds);
        m.put("uptimeMs", System.currentTimeMillis() - plugin.startedAt());
        m.put("blockStateCount", blockStateCount());
        return m;
    }

    private volatile int blockStateCountCache = -1;

    int blockStateCount() {
        int n = blockStateCountCache;
        if (n >= 0) {
            return n;
        }
        n = 0;
        try {
            n = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size();
        } catch (Throwable ignored) {
            // fall through to the Bukkit-counting path below
        }
        if (n == 0) {
            for (BlockType t : Registry.BLOCK) {
                n += t.createBlockDataStates().size();
            }
        }
        blockStateCountCache = n;
        return n;
    }

    Map<String, Object> world(World w) {
        Map<String, Object> m = new LinkedHashMap<>();
        Location spawn = w.getSpawnLocation();
        m.put("name", w.getName());
        m.put("uuid", w.getUID().toString());
        m.put("environment", w.getEnvironment().name());
        m.put("seed", w.getSeed());
        m.put("spawn", Json.list(spawn.getX(), spawn.getY(), spawn.getZ()));
        m.put("time", w.getTime());
        m.put("fullTime", w.getFullTime());
        m.put("difficulty", w.getDifficulty().name());
        m.put("pvp", w.getPVP());
        m.put("storm", w.hasStorm());
        m.put("thundering", w.isThundering());
        m.put("weatherDuration", w.getWeatherDuration());
        m.put("thunderDuration", w.getThunderDuration());
        m.put("minHeight", w.getMinHeight());
        m.put("maxHeight", w.getMaxHeight());
        m.put("seaLevel", w.getSeaLevel());
        m.put("autoSave", w.isAutoSave());
        m.put("keepSpawnLoaded", w.getKeepSpawnInMemory());
        m.put("entityCount", w.getEntities().size());
        m.put("livingCount", w.getLivingEntities().size());
        m.put("playerCount", w.getPlayers().size());
        m.put("loadedChunks", w.getLoadedChunks().length);
        m.put("gamerules", gameRules(w));
        return m;
    }

    private Map<String, Object> gameRules(World w) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "doDaylightCycle", w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE));
        put(m, "doWeatherCycle", w.getGameRuleValue(GameRule.DO_WEATHER_CYCLE));
        put(m, "doMobSpawning", w.getGameRuleValue(GameRule.DO_MOB_SPAWNING));
        put(m, "doMobLoot", w.getGameRuleValue(GameRule.DO_MOB_LOOT));
        put(m, "doTileDrops", w.getGameRuleValue(GameRule.DO_TILE_DROPS));
        put(m, "doEntityDrops", w.getGameRuleValue(GameRule.DO_ENTITY_DROPS));
        put(m, "keepInventory", w.getGameRuleValue(GameRule.KEEP_INVENTORY));
        put(m, "mobGriefing", w.getGameRuleValue(GameRule.MOB_GRIEFING));
        put(m, "naturalRegeneration", w.getGameRuleValue(GameRule.NATURAL_REGENERATION));
        put(m, "doFireTick", w.getGameRuleValue(GameRule.DO_FIRE_TICK));
        put(m, "randomTickSpeed", w.getGameRuleValue(GameRule.RANDOM_TICK_SPEED));
        put(m, "spawnRadius", w.getGameRuleValue(GameRule.SPAWN_RADIUS));
        put(m, "fallDamage", w.getGameRuleValue(GameRule.FALL_DAMAGE));
        put(m, "fireDamage", w.getGameRuleValue(GameRule.FIRE_DAMAGE));
        put(m, "drowningDamage", w.getGameRuleValue(GameRule.DROWNING_DAMAGE));
        put(m, "freezeDamage", w.getGameRuleValue(GameRule.FREEZE_DAMAGE));
        put(m, "commandBlockOutput", w.getGameRuleValue(GameRule.COMMAND_BLOCK_OUTPUT));
        put(m, "sendCommandFeedback", w.getGameRuleValue(GameRule.SEND_COMMAND_FEEDBACK));
        put(m, "showDeathMessages", w.getGameRuleValue(GameRule.SHOW_DEATH_MESSAGES));
        put(m, "announceAdvancements", w.getGameRuleValue(GameRule.ANNOUNCE_ADVANCEMENTS));
        put(m, "doImmediateRespawn", w.getGameRuleValue(GameRule.DO_IMMEDIATE_RESPAWN));
        put(m, "playersSleepingPercentage", w.getGameRuleValue(GameRule.PLAYERS_SLEEPING_PERCENTAGE));
        put(m, "reducedDebugInfo", w.getGameRuleValue(GameRule.REDUCED_DEBUG_INFO));
        return m;
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        m.put(key, value instanceof Enum<?> e ? e.name() : value);
    }

    // ----------------------------------------------------------------- players

    Map<String, Object> playerBrief(Player p) {
        Map<String, Object> m = new LinkedHashMap<>();
        Location loc = p.getLocation();
        m.put("name", p.getName());
        m.put("uuid", p.getUniqueId().toString());
        m.put("world", p.getWorld().getName());
        m.put("x", loc.getX());
        m.put("y", loc.getY());
        m.put("z", loc.getZ());
        m.put("yaw", loc.getYaw());
        m.put("pitch", loc.getPitch());
        m.put("onGround", p.isOnGround());
        m.put("gameMode", p.getGameMode().name());
        m.put("health", p.getHealth());
        m.put("ping", p.getPing());
        return m;
    }

    Map<String, Object> player(Player p) {
        Map<String, Object> m = playerBrief(p);
        Location loc = p.getLocation();
        Location eye = p.getEyeLocation();
        m.put("eye", Json.list(eye.getX(), eye.getY(), eye.getZ()));
        m.put("blockPos", Json.list(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        m.put("pose", p.getPose().name());
        m.put("velocity", Json.list(p.getVelocity().getX(), p.getVelocity().getY(), p.getVelocity().getZ()));
        m.put("maxHealth", p.getMaxHealth());
        m.put("absorption", p.getAbsorptionAmount());
        m.put("food", p.getFoodLevel());
        m.put("saturation", p.getSaturation());
        m.put("exhaustion", p.getExhaustion());
        m.put("exp", p.getExp());
        m.put("level", p.getLevel());
        m.put("totalExperience", p.getTotalExperience());
        m.put("expCooldown", p.getExpCooldown());
        m.put("fireTicks", p.getFireTicks());
        m.put("freezeTicks", p.getFreezeTicks());
        m.put("remainingAir", p.getRemainingAir());
        m.put("maximumAir", p.getMaximumAir());
        m.put("fallDistance", (double) p.getFallDistance());
        m.put("walkSpeed", p.getWalkSpeed());
        m.put("flySpeed", p.getFlySpeed());
        m.put("allowFlight", p.getAllowFlight());
        m.put("flying", p.isFlying());
        m.put("sneaking", p.isSneaking());
        m.put("sprinting", p.isSprinting());
        m.put("swimming", p.isSwimming());
        m.put("gliding", p.isGliding());
        m.put("invisible", p.isInvisible());
        m.put("invulnerable", p.isInvulnerable());
        m.put("dead", p.isDead());
        m.put("sleeping", p.isSleeping());
        m.put("sleepTicks", p.getSleepTicks());
        m.put("ticksLived", p.getTicksLived());
        m.put("portalCooldown", p.getPortalCooldown());
        m.put("noDamageTicks", p.getNoDamageTicks());
        m.put("maximumNoDamageTicks", p.getMaximumNoDamageTicks());
        m.put("lastDamage", p.getLastDamage());
        m.put("attackCooldown", p.getAttackCooldown());
        m.put("handRaised", p.isHandRaised());
        m.put("blocking", p.isBlocking());
        m.put("activeHand", p.getActiveItemHand() == null ? null : p.getActiveItemHand().name());
        m.put("activeItemUsedTime", p.getActiveItemUsedTime());
        m.put("activeItem", item(p.getActiveItem()));
        m.put("vehicle", p.getVehicle() == null ? null : p.getVehicle().getType().getKey().toString());
        m.put("passengers", p.getPassengers().size());
        m.put("locale", p.getLocale());
        m.put("clientBrand", p.getClientBrandName());
        m.put("viewDistance", p.getClientViewDistance());
        m.put("heldSlot", p.getInventory().getHeldItemSlot());
        m.put("mainHand", item(p.getInventory().getItemInMainHand()));
        m.put("offHand", item(p.getInventory().getItemInOffHand()));
        List<Object> armor = new ArrayList<>();
        for (ItemStack is : p.getInventory().getArmorContents()) {
            armor.add(item(is));
        }
        m.put("armor", armor);
        List<Object> effects = new ArrayList<>();
        for (PotionEffect e : p.getActivePotionEffects()) {
            effects.add(Json.map(
                    "type", e.getType().getKey().toString(),
                    "duration", e.getDuration(),
                    "amplifier", e.getAmplifier(),
                    "ambient", e.isAmbient(),
                    "particles", e.hasParticles()));
        }
        m.put("effects", effects);
        return m;
    }

    Map<String, Object> inventory(Player p) {
        PlayerInventory inv = p.getInventory();
        Map<String, Object> slots = new LinkedHashMap<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack is = inv.getItem(i);
            Map<String, Object> in = item(is);
            if (in != null) {
                slots.put(String.valueOf(i), in);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("player", p.getName());
        m.put("size", inv.getSize());
        m.put("heldSlot", inv.getHeldItemSlot());
        m.put("selected", item(inv.getItemInMainHand()));
        m.put("offHand", item(inv.getItemInOffHand()));
        List<Object> armor = new ArrayList<>();
        for (ItemStack is : inv.getArmorContents()) {
            armor.add(item(is));
        }
        m.put("armor", armor);
        m.put("slots", slots);
        Map<String, Object> ender = new LinkedHashMap<>();
        for (int i = 0; i < p.getEnderChest().getSize(); i++) {
            Map<String, Object> in = item(p.getEnderChest().getItem(i));
            if (in != null) {
                ender.put(String.valueOf(i), in);
            }
        }
        m.put("enderChest", ender);
        return m;
    }

    Map<String, Object> item(ItemStack is) {
        if (is == null || is.getType().isAir() || is.getAmount() <= 0) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", is.getType().getKey().toString());
        m.put("amount", is.getAmount());
        m.put("maxStack", is.getMaxStackSize());
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            if (meta instanceof org.bukkit.inventory.meta.Damageable dmg) {
                if (dmg.hasDamage()) {
                    m.put("damage", dmg.getDamage());
                }
                if (dmg.hasMaxDamage()) {
                    m.put("maxDamage", dmg.getMaxDamage());
                }
            }
            if (meta.isUnbreakable()) {
                m.put("unbreakable", true);
            }
            if (!meta.getEnchants().isEmpty()) {
                Map<String, Object> ench = new LinkedHashMap<>();
                meta.getEnchants().forEach((k, v) -> ench.put(k.getKey().toString(), v));
                m.put("enchantments", ench);
            }
            if (meta.hasCustomModelData()) {
                m.put("customModelData", meta.getCustomModelData());
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ blocks

    Map<String, Object> block(Block b) {
        BlockData data = b.getBlockData();
        Material material = b.getType();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", b.getWorld().getName());
        m.put("x", b.getX());
        m.put("y", b.getY());
        m.put("z", b.getZ());
        m.put("type", material.getKey().toString());
        m.put("data", data.getAsString());
        m.put("stateId", stateId(data));
        m.put("collision", collision(data, b));
        m.put("boundingBox", box(b.getBoundingBox()));
        m.put("light", b.getLightLevel());
        m.put("skyLight", b.getLightFromSky());
        m.put("blockLight", b.getLightFromBlocks());
        m.put("biome", b.getBiome().getKey().toString());
        m.put("temperature", b.getTemperature() / 1.0D);
        m.put("humidity", (double) b.getHumidity());
        m.put("power", b.getBlockPower());
        m.put("solid", data.getMaterial().isSolid());
        m.put("passable", b.isPassable());
        m.put("air", material.isAir());
        m.put("liquid", material == Material.WATER || material == Material.LAVA);
        m.put("occluding", data.isOccluding());
        m.put("replaceable", data.isReplaceable());
        m.put("hardness", data.getMaterial().getHardness());
        m.put("blastResistance", data.getMaterial().getBlastResistance());
        return m;
    }

    /** The vanilla global block-state id for a state: this is the number chunk sections and set-block packets use. */
    int stateId(BlockData data) {
        try {
            net.minecraft.world.level.block.state.BlockState st = ((org.bukkit.craftbukkit.block.data.CraftBlockData) data).getState();
            return net.minecraft.world.level.block.Block.getId(st);
        } catch (Throwable t) {
            return -1;
        }
    }

    static List<Object> box(org.bukkit.util.BoundingBox bb) {
        return Json.list(bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
    }

    private static List<Object> collision(BlockData data, Block b) {
        List<Object> out = new ArrayList<>();
        try {
            for (org.bukkit.util.BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                out.add(box(bb));
            }
        } catch (Throwable ignored) {
            // leave the list empty when the shape API is unavailable
        }
        return out;
    }

    /** 4096 vanilla state ids of one 16x16x16 section, indexed {@code (y*16 + z)*16 + x}. */
    List<Object> sectionIds(World w, int sx, int sy, int sz) {
        List<Object> out = new ArrayList<>(4096);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    out.add(stateId(w.getBlockAt(sx + x, sy + y, sz + z).getBlockData()));
                }
            }
        }
        return out;
    }

    List<Object> region(World w, int x, int y, int z, int dx, int dy, int dz) {
        List<Object> out = new ArrayList<>();
        for (int ix = 0; ix < dx; ix++) {
            List<Object> rowX = new ArrayList<>();
            for (int iy = 0; iy < dy; iy++) {
                List<Object> rowY = new ArrayList<>();
                for (int iz = 0; iz < dz; iz++) {
                    rowY.add(w.getBlockAt(x + ix, y + iy, z + iz).getBlockData().getAsString());
                }
                rowX.add(rowY);
            }
            out.add(rowX);
        }
        return out;
    }

    List<Object> entities(World w, double x, double y, double z, double radius) {
        Location center = new Location(w, x, y, z);
        List<Object> out = new ArrayList<>();
        for (Entity e : w.getNearbyEntities(center, radius, radius, radius)) {
            Map<String, Object> m = new LinkedHashMap<>();
            Location l = e.getLocation();
            m.put("uuid", e.getUniqueId().toString());
            m.put("type", e.getType().getKey().toString());
            m.put("x", l.getX());
            m.put("y", l.getY());
            m.put("z", l.getZ());
            m.put("yaw", l.getYaw());
            m.put("pitch", l.getPitch());
            m.put("onGround", e.isOnGround());
            m.put("customName", e.getCustomName());
            m.put("velocity", Json.list(e.getVelocity().getX(), e.getVelocity().getY(), e.getVelocity().getZ()));
            m.put("ticksLived", e.getTicksLived());
            if (e instanceof LivingEntity le) {
                m.put("health", le.getHealth());
                m.put("maxHealth", le.getMaxHealth());
                m.put("pose", le.getPose().name());
            }
            if (e instanceof Player) {
                m.put("player", true);
            }
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- registries

    /**
     * The authoritative block-state table: {@code globalStateId -> "minecraft:x[p=v,...]"} in vanilla registry
     * order, which is exactly the order the client-side palette/global-id space uses.
     */
    String blockStates(boolean fresh) {
        String cached = blockStatesCache;
        if (!fresh && cached != null) {
            return cached;
        }
        StringBuilder sb = new StringBuilder(4 << 20);
        int id = 0;
        try {
            // The vanilla block-state registry *is* the network global palette: id order on the wire is exactly
            // byId(0..size-1). The Bukkit BlockType iteration order is hash-based and useless as a wire id space,
            // which is why this goes through NMS instead of enumerating BlockType#createBlockDataStates().
            int n = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size();
            for (int i = 0; i < n; i++) {
                net.minecraft.world.level.block.state.BlockState st = net.minecraft.world.level.block.Block.stateById(i);
                BlockData bd = org.bukkit.craftbukkit.block.data.CraftBlockData.createData(st);
                sb.append(id++).append('\t').append(bd.getAsString()).append('\n');
            }
            sb.append("#source\tblock_state_registry\n");
        } catch (Throwable t) {
            // Fallback: enumerate through Bukkit. Order is not the wire order, but the state strings are still
            // authoritative and can be re-keyed by a consumer that owns the palette.
            id = 0;
            sb.append("#fallback\t").append(t.getClass().getSimpleName()).append(':').append(t.getMessage()).append('\n');
            int errors = 0;
            for (BlockType bt : Registry.BLOCK) {
                Collection<? extends BlockData> states;
                try {
                    states = bt.createBlockDataStates();
                } catch (Throwable ex) {
                    errors++;
                    sb.append("#ERR ").append(bt.getKey()).append(' ').append(ex.getClass().getSimpleName()).append('\n');
                    continue;
                }
                for (BlockData bd : states) {
                    sb.append(id++).append('\t').append(bd.getAsString()).append('\n');
                }
            }
            if (errors > 0) {
                sb.append("#errors\t").append(errors).append('\n');
            }
        }
        sb.append("#total\t").append(id).append('\n');
        String s = sb.toString();
        blockStatesCache = s;
        blockStatesCacheAt = System.currentTimeMillis();
        plugin.getLogger().info("block state table computed: " + id + " states");
        return s;
    }

    long blockStatesAt() {
        return blockStatesCacheAt;
    }

    /** Item registry order -> numeric item id (0 = minecraft:air, as in the network item-id field). */
    String items(boolean fresh) {
        String cached = itemsCache;
        if (!fresh && cached != null) {
            return cached;
        }
        StringBuilder sb = new StringBuilder(1 << 20);
        int id = 0;
        try {
            // Vanilla registry order is the wire order; Bukkit's Registry.ITEM iteration order is not.
            net.minecraft.core.DefaultedRegistry<net.minecraft.world.item.Item> reg =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM;
            int n = reg.size();
            for (int i = 0; i < n; i++) {
                net.minecraft.world.item.Item item = net.minecraft.world.item.Item.byId(i);
                if (item == null) {
                    continue;
                }
                String key = reg.getKey(item).toString();
                int max = item.getDefaultMaxStackSize();
                String block = item instanceof net.minecraft.world.item.BlockItem bi
                        ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString()
                        : "";
                sb.append(id++).append('\t').append(key).append('\t').append(max).append('\t').append(block).append('\n');
            }
            sb.append("#source\tbuilt_in_item_registry\n");
        } catch (Throwable t) {
            id = 0;
            sb.append("#fallback\t").append(t.getClass().getSimpleName()).append(':').append(t.getMessage()).append('\n');
            for (ItemType it : Registry.ITEM) {
                String block = it.hasBlockType() ? it.getBlockType().getKey().toString() : "";
                sb.append(id++).append('\t').append(it.getKey()).append('\t').append(it.getMaxStackSize()).append('\t').append(block).append('\n');
            }
        }
        sb.append("#total\t").append(id).append('\n');
        String s = sb.toString();
        itemsCache = s;
        itemsCacheAt = System.currentTimeMillis();
        return s;
    }

    String entityTypes() {
        String cached = entityTypesCache;
        if (cached != null) {
            return cached;
        }
        StringBuilder sb = new StringBuilder(4096);
        int id = 0;
        try {
            net.minecraft.core.DefaultedRegistry<net.minecraft.world.entity.EntityType<?>> reg =
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE;
            for (net.minecraft.world.entity.EntityType<?> t : reg) {
                sb.append(id++).append('\t').append(reg.getKey(t)).append('\n');
            }
            sb.append("#source\tbuilt_in_entity_type_registry\n");
        } catch (Throwable t) {
            id = 0;
            sb.append("#fallback\t").append(t.getClass().getSimpleName()).append(':').append(t.getMessage()).append('\n');
            for (EntityType bt : Registry.ENTITY_TYPE) {
                sb.append(id++).append('\t').append(bt.getKey()).append('\n');
            }
        }
        sb.append("#total\t").append(id).append('\n');
        String s = sb.toString();
        entityTypesCache = s;
        return s;
    }

    Map<String, Object> itemTypeDetail(String key) {
        var namespaced = org.bukkit.NamespacedKey.fromString(key);
        ItemType t = namespaced == null ? null : Registry.ITEM.get(namespaced);
        if (t == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", t.getKey().toString());
        m.put("maxStackSize", t.getMaxStackSize());
        m.put("maxDurability", (int) t.getMaxDurability());
        m.put("hasBlockType", t.hasBlockType());
        m.put("blockType", t.hasBlockType() ? t.getBlockType().getKey().toString() : null);
        m.put("edible", t.isEdible());
        m.put("fuel", t.isFuel());
        m.put("burnDuration", t.getBurnDuration());
        m.put("translationKey", t.getTranslationKey());
        return m;
    }

    Map<String, Object> blockTypeDetail(String key) {
        var namespaced = org.bukkit.NamespacedKey.fromString(key);
        BlockType t = namespaced == null ? null : Registry.BLOCK.get(namespaced);
        if (t == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", t.getKey().toString());
        m.put("solid", t.isSolid());
        m.put("occluding", t.isOccluding());
        m.put("gravity", t.hasGravity());
        m.put("interactable", t.isInteractable());
        m.put("hardness", t.getHardness());
        m.put("blastResistance", t.getBlastResistance());
        m.put("slipperiness", t.getSlipperiness());
        m.put("air", t.isAir());
        m.put("flammable", t.isFlammable());
        m.put("translationKey", t.getTranslationKey());
        m.put("hasItemType", t.hasItemType());
        m.put("itemType", t.hasItemType() ? t.getItemType().getKey().toString() : null);
        m.put("dataClass", t.getBlockDataClass().getSimpleName());
        m.put("stateCount", t.createBlockDataStates().size());
        m.put("collisionShape", null);
        return m;
    }

    Map<String, Object> equipmentSlots(Player p) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            m.put(slot.name(), item(p.getInventory().getItem(slot)));
        }
        return m;
    }
}
