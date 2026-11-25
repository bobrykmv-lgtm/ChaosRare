package com.chaossmp.chaosrare;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class ChaosRare extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey rareVariantKey;

    private double rareSpawnChance = 0.005;
    private boolean allowSpawners = false;
    private boolean notifyNearbyPlayers = true;
    private double notifyRadius = 48.0;
    private boolean glowingEnabled = true;
    private boolean particlesEnabled = true;
    private Set<EntityType> allowedEntityTypes = EnumSet.noneOf(EntityType.class);
    private final Map<String, RareVariantConfig> variants = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        rareVariantKey = new NamespacedKey(this, "rare_variant");
        saveDefaultConfig();
        loadPluginConfig();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("chaosrare") != null) {
            getCommand("chaosrare").setExecutor(this);
            getCommand("chaosrare").setTabCompleter(this);
        }

        getLogger().info("ChaosRare (UnstableSMP) запущен! Вариантов: " + variants.size()
                + ", шанс: " + (rareSpawnChance * 100.0) + "%");
    }

    @Override
    public void onDisable() {
        variants.clear();
        allowedEntityTypes.clear();
        getLogger().info("ChaosRare выключен.");
    }

    private void loadPluginConfig() {
        reloadConfig();
        variants.clear();
        allowedEntityTypes = EnumSet.noneOf(EntityType.class);

        this.rareSpawnChance = Math.max(0.0, Math.min(1.0, getConfig().getDouble("rare-spawn-chance", 0.005)));
        this.allowSpawners = getConfig().getBoolean("allow-spawners", false);
        this.notifyNearbyPlayers = getConfig().getBoolean("notify-nearby.enabled", true);
        this.notifyRadius = Math.max(4.0, getConfig().getDouble("notify-nearby.radius", 48.0));
        this.glowingEnabled = getConfig().getBoolean("visuals.glowing", true);
        this.particlesEnabled = getConfig().getBoolean("visuals.particles", true);

        for (String typeName : getConfig().getStringList("allowed-mobs")) {
            try {
                EntityType type = EntityType.valueOf(typeName.trim().toUpperCase(Locale.ROOT));
                if (type.isAlive() && type != EntityType.PLAYER) {
                    allowedEntityTypes.add(type);
                }
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Неизвестный тип моба в allowed-mobs: " + typeName);
            }
        }

        ConfigurationSection variantsSection = getConfig().getConfigurationSection("variants");
        if (variantsSection != null) {
            for (String key : variantsSection.getKeys(false)) {
                ConfigurationSection sec = variantsSection.getConfigurationSection(key);
                if (sec == null) continue;

                String id = key.toLowerCase(Locale.ROOT);
                String name = colorize(sec.getString("name", "&6Редкий моб"));
                double weight = Math.max(0.01, sec.getDouble("weight", 1.0));
                double maxHealth = Math.max(1.0, sec.getDouble("max-health", 60.0));
                double expMultiplier = Math.max(1.0, sec.getDouble("exp-multiplier", 3.0));
                Color armorColor = parseColor(sec.getString("armor-color", "YELLOW"));
                Material helmetMat = parseMaterial(sec.getString("equipment.helmet", "NONE"));
                Material chestMat = parseMaterial(sec.getString("equipment.chestplate", "NONE"));
                Material mainHandMat = parseMaterial(sec.getString("equipment.main-hand", "NONE"));

                List<DropEntry> drops = new ArrayList<>();
                for (Map<?, ?> rawDrop : sec.getMapList("drops")) {
                    Object matObj = rawDrop.get("material");
                    if (matObj == null) continue;
                    Material mat = parseMaterial(String.valueOf(matObj));
                    if (mat == null) continue;

                    int min = toInt(rawDrop.get("min"), 1);
                    int max = Math.max(min, toInt(rawDrop.get("max"), min));
                    double chance = Math.max(0.0, Math.min(1.0, toDouble(rawDrop.get("chance"), 1.0)));
                    drops.add(new DropEntry(mat, min, max, chance));
                }

                variants.put(id, new RareVariantConfig(
                        id, name, weight, maxHealth, expMultiplier,
                        armorColor, helmetMat, chestMat, mainHandMat, drops
                ));
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (variants.isEmpty() || allowedEntityTypes.isEmpty()) {
            return;
        }

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        boolean validReason = reason == CreatureSpawnEvent.SpawnReason.NATURAL
                || (allowSpawners && reason == CreatureSpawnEvent.SpawnReason.SPAWNER);
        if (!validReason) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }

        if (!allowedEntityTypes.contains(living.getType())) {
            return;
        }

        if (living.getPersistentDataContainer().has(rareVariantKey, PersistentDataType.STRING)) {
            return;
        }

        if (ThreadLocalRandom.current().nextDouble() >= rareSpawnChance) {
            return;
        }

        RareVariantConfig chosen = rollVariantByWeight();
        if (chosen != null) {
            applyRareVariant(living, chosen);
        }
    }

    private RareVariantConfig rollVariantByWeight() {
        double totalWeight = 0.0;
        for (RareVariantConfig v : variants.values()) {
            totalWeight += v.weight();
        }
        if (totalWeight <= 0.0) return null;

        double r = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (RareVariantConfig v : variants.values()) {
            cumulative += v.weight();
            if (r <= cumulative) {
                return v;
            }
        }
        return variants.values().iterator().next();
    }

    public void applyRareVariant(LivingEntity entity, RareVariantConfig variant) {
        entity.getPersistentDataContainer().set(rareVariantKey, PersistentDataType.STRING, variant.id());
        entity.setCustomName(variant.displayName());
        entity.setCustomNameVisible(true);
        entity.setRemoveWhenFarAway(false);

        if (glowingEnabled) {
            entity.setGlowing(true);
        }

        AttributeInstance maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealthAttr.setBaseValue(variant.maxHealth());
            entity.setHealth(variant.maxHealth());
        }

        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            if (variant.helmet() != null) {
                equipment.setHelmet(colorLeatherIfNeeded(new ItemStack(variant.helmet()), variant.armorColor()));
                equipment.setHelmetDropChance(0.0f);
            }
            if (variant.chestplate() != null) {
                equipment.setChestplate(colorLeatherIfNeeded(new ItemStack(variant.chestplate()), variant.armorColor()));
                equipment.setChestplateDropChance(0.0f);
            }
            if (variant.mainHand() != null) {
                equipment.setItemInMainHand(new ItemStack(variant.mainHand()));
                equipment.setItemInMainHandDropChance(0.0f);
            }
        }

        if (particlesEnabled) {
            entity.getWorld().spawnParticle(
                    Particle.END_ROD,
                    entity.getLocation().add(0, 1.0, 0),
                    25,
                    0.4, 0.6, 0.4,
                    0.02
            );
        }

        if (notifyNearbyPlayers) {
            String msg = ChatColor.GOLD + "[ChaosRare] " + ChatColor.YELLOW
                    + "Поблизости появился редкий моб: " + variant.displayName();
            for (Player player : entity.getWorld().getPlayers()) {
                if (player.getLocation().distanceSquared(entity.getLocation()) <= notifyRadius * notifyRadius) {
                    player.sendMessage(msg);
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.3f);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        String variantId = entity.getPersistentDataContainer().get(rareVariantKey, PersistentDataType.STRING);
        if (variantId == null) {
            return;
        }

        RareVariantConfig variant = variants.get(variantId.toLowerCase(Locale.ROOT));
        if (variant == null) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (DropEntry entry : variant.drops()) {
            if (random.nextDouble() <= entry.chance()) {
                int amount = (entry.max() == entry.min())
                        ? entry.min()
                        : random.nextInt(entry.min(), entry.max() + 1);
                if (amount > 0) {
                    event.getDrops().add(new ItemStack(entry.material(), amount));
                }
            }
        }

        int bonusExp = (int) Math.round(event.getDroppedExp() * variant.expMultiplier());
        event.setDroppedExp(Math.max(event.getDroppedExp(), bonusExp));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chaosrare.admin") && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "[ChaosRare] Нет прав.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "[ChaosRare] " + ChatColor.YELLOW
                    + "/chaosrare reload | spawn <variant> | info");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            loadPluginConfig();
            sender.sendMessage(ChatColor.GREEN + "[ChaosRare] Конфиг перезагружен. Шанс: "
                    + (rareSpawnChance * 100.0) + "%");
            return true;
        }

        if (args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(ChatColor.GOLD + "[ChaosRare] " + ChatColor.WHITE
                    + "Шанс: " + (rareSpawnChance * 100.0) + "% | Вариантов: " + variants.size()
                    + " | Типов мобов: " + allowedEntityTypes.size());
            return true;
        }

        if (args[0].equalsIgnoreCase("spawn")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Команду spawn может выполнить только игрок.");
                return true;
            }
            String targetId = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : null;
            RareVariantConfig variant = targetId != null ? variants.get(targetId) : rollVariantByWeight();
            if (variant == null) {
                player.sendMessage(ChatColor.RED + "[ChaosRare] Вариант не найден. Доступные: "
                        + String.join(", ", variants.keySet()));
                return true;
            }

            EntityType mobType = allowedEntityTypes.contains(EntityType.ZOMBIE)
                    ? EntityType.ZOMBIE
                    : (allowedEntityTypes.isEmpty() ? EntityType.ZOMBIE : allowedEntityTypes.iterator().next());

            LivingEntity spawned = (LivingEntity) player.getWorld().spawnEntity(player.getLocation(), mobType);
            applyRareVariant(spawned, variant);
            player.sendMessage(ChatColor.GREEN + "[ChaosRare] Призван: " + variant.displayName());
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "spawn", "info");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return new ArrayList<>(variants.keySet());
        }
        return List.of();
    }

    private ItemStack colorLeatherIfNeeded(ItemStack item, Color color) {
        if (color != null && item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String colorize(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private Material parseMaterial(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("NONE")) {
            return null;
        }
        Material mat = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (mat == null) {
            getLogger().warning("Неизвестный материал в конфиге: " + name);
        }
        return mat;
    }

    private Color parseColor(String name) {
        if (name == null) return Color.YELLOW;
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "GOLD", "YELLOW" -> Color.YELLOW;
            case "RED" -> Color.RED;
            case "BLUE" -> Color.BLUE;
            case "GREEN" -> Color.GREEN;
            case "AQUA" -> Color.AQUA;
            case "PURPLE" -> Color.PURPLE;
            case "ORANGE" -> Color.ORANGE;
            case "WHITE" -> Color.WHITE;
            case "BLACK" -> Color.BLACK;
            default -> Color.YELLOW;
        };
    }

    private int toInt(Object val, int fallback) {
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (Exception e) {
            return fallback;
        }
    }

    private double toDouble(Object val, double fallback) {
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (Exception e) {
            return fallback;
        }
    }

    private record DropEntry(Material material, int min, int max, double chance) {}

    private record RareVariantConfig(
            String id,
            String displayName,
            double weight,
            double maxHealth,
            double expMultiplier,
            Color armorColor,
            Material helmet,
            Material chestplate,
            Material mainHand,
            List<DropEntry> drops
    ) {}
}
