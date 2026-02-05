package net.tfminecraft;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import me.Plugins.TLibs.TLibs;

public class BirdMessenger extends JavaPlugin implements Listener {

    private final HashMap<UUID, Inventory> openInventories = new HashMap<>();
    private final HashMap<UUID, ItemStack> pendingBooks = new HashMap<>();
    private final HashMap<UUID, Boolean> awaitingRecipient = new HashMap<>();
    private final HashMap<UUID, String> pendingRecipientName = new HashMap<>();
    private final HashMap<UUID, Boolean> awaitingConfirmation = new HashMap<>();

    private final Map<UUID, List<ItemStack>> offlineMail = new HashMap<>();

    private static final long DELIVERY_DELAY_TICKS = 12000L; 

    private File offlineMailFile;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("BirdMessenger enabled!");

        offlineMailFile = new File(getDataFolder(), "offline_mail.yml");
        loadOfflineMail();
    }

    @Override
    public void onDisable() {
        getLogger().info("BirdMessenger disabled!");

   
        saveOfflineMail();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
public void onRightClickWetSpong(PlayerInteractEvent e) {
    if (e.getHand() != EquipmentSlot.HAND) return;

    if (e.getAction() != Action.RIGHT_CLICK_BLOCK &&
        e.getAction() != Action.RIGHT_CLICK_AIR) return;

    Block block = e.getClickedBlock();
    if (block == null) return;

    if (!TLibs.getBlockAPI().getChecker().checkBlock(block, "ia.tfmc:bird_coop")) return;

    Player p = e.getPlayer();
    if (p.isSneaking()) return;

    Inventory inv = Bukkit.createInventory(null, 9, "Bird Messenger");

    ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    ItemMeta meta = pane.getItemMeta();
    meta.setDisplayName("§7");
    pane.setItemMeta(meta);

    for (int i = 0; i < 9; i++) {
        if (i != 4) inv.setItem(i, pane);
    }

    p.openInventory(inv);
    openInventories.put(p.getUniqueId(), inv);
    e.setCancelled(true);
}


    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        Player p = (Player) e.getWhoClicked();
        UUID id = p.getUniqueId();

        if (!openInventories.containsKey(id)) return;
        if (!e.getView().getTitle().equals("Bird Messenger")) return;

        Inventory top = e.getView().getTopInventory();

      
        if (e.getClickedInventory() == top) {
            if (e.getSlot() != 4) {
                e.setCancelled(true);
                return;
            }

            ItemStack cursor = e.getCursor();

         
            if (cursor != null &&
                (cursor.getType() == Material.WRITABLE_BOOK || cursor.getType() == Material.WRITTEN_BOOK)) {

              
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    ItemStack placed = top.getItem(4);
                    if (placed != null &&
                        (placed.getType() == Material.WRITABLE_BOOK || placed.getType() == Material.WRITTEN_BOOK)) {
                        p.closeInventory();
                        Bukkit.getScheduler().runTask(this, () ->
                                p.playSound(p.getLocation(), Sound.ENTITY_PARROT_FLY, 1f, 1f)
                        );
                    }
                }, 3L);
            } else if (cursor != null && cursor.getType() != Material.AIR) {
                p.sendMessage("§cOnly books can be sent by bird.");
                e.setCancelled(true);
            }
        }
     
        else if (e.getClickedInventory() == p.getInventory() && e.isShiftClick()) {
            ItemStack current = e.getCurrentItem();
            if (current != null && (current.getType() == Material.WRITABLE_BOOK || current.getType() == Material.WRITTEN_BOOK)) {
                e.setCancelled(true);

                Bukkit.getScheduler().runTaskLater(this, () -> {
                    ItemStack slotItem = top.getItem(4);
                    if (slotItem == null || slotItem.getType() == Material.AIR) {
                        top.setItem(4, current.clone());
                        current.setAmount(current.getAmount() - 1);

                        p.closeInventory();
                        Bukkit.getScheduler().runTask(this, () ->
                                p.playSound(p.getLocation(), Sound.ENTITY_PARROT_FLY, 1f, 1f)
                        );
                    } else {
                        p.sendMessage("§cYou can only place one book at a time.");
                    }
                }, 1L);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        Player p = (Player) e.getPlayer();
        UUID id = p.getUniqueId();

        if (!openInventories.containsKey(id)) return;

        Inventory inv = openInventories.remove(id);
        ItemStack book = inv.getItem(4);

        if (book == null || (book.getType() != Material.WRITABLE_BOOK && book.getType() != Material.WRITTEN_BOOK)) return;

        pendingBooks.put(id, book);
        awaitingRecipient.put(id, true);
        p.sendMessage("§eWho would you like to send this letter to?");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player sender = e.getPlayer();
        UUID id = sender.getUniqueId();
        String msg = e.getMessage().trim();

       
        if (awaitingConfirmation.getOrDefault(id, false)) {
            e.setCancelled(true);

            if (msg.equalsIgnoreCase("yes")) {
                awaitingConfirmation.put(id, false);

                String name = pendingRecipientName.remove(id);
                ItemStack book = pendingBooks.remove(id);
                if (book == null) {
                    sender.sendMessage("§cNo book found to send.");
                    return;
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    Player target = Bukkit.getPlayerExact(name);

                    sender.sendMessage("§aYour bird takes flight to deliver the letter...");
                    sender.playSound(sender.getLocation(), Sound.ENTITY_PARROT_AMBIENT, 1f, 1.2f);

                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (target == null || !target.isOnline()) {
                            UUID targetId = Bukkit.getOfflinePlayer(name).getUniqueId();
                            offlineMail.computeIfAbsent(targetId, k -> new ArrayList<>()).add(book);
                            sender.sendMessage("§bThey are offline — your letter will be delivered when they log in!");
                            sender.playSound(sender.getLocation(), Sound.ENTITY_PARROT_AMBIENT, 1f, 1f);

                            saveOfflineMail(); 
                        } else {
                            deliverBook(sender, target, book);
                        }
                    }, DELIVERY_DELAY_TICKS);
                });

            } else if (msg.equalsIgnoreCase("cancel")) {
                e.setCancelled(true);
                awaitingConfirmation.put(id, false);

                ItemStack book = pendingBooks.remove(id);
                if (book != null) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.getInventory().addItem(book);
                        sender.sendMessage("§eYour letter has been returned to you.");
                        sender.playSound(sender.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                    });
                }

                pendingRecipientName.remove(id);
                sender.sendMessage("§cLetter cancelled.");
            } else {
                e.setCancelled(true);
                sender.sendMessage("§7Please type §ayes§7 to confirm or §ccancel§7 to stop.");
            }
            return;
        }

  
        if (!awaitingRecipient.getOrDefault(id, false)) return;

        e.setCancelled(true);

        if (msg.equalsIgnoreCase("cancel")) {
            awaitingRecipient.put(id, false);

            ItemStack book = pendingBooks.remove(id);
            if (book != null) {
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.getInventory().addItem(book);
                    sender.sendMessage("§eYour letter has been returned to you.");
                    sender.playSound(sender.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                });
            }

            sender.sendMessage("§cLetter cancelled.");
            return;
        }

        awaitingRecipient.put(id, false);
        pendingRecipientName.put(id, msg);
        awaitingConfirmation.put(id, true);

        sender.sendMessage("§eThe person you want to send to is §a" + msg + "§e.");
        sender.sendMessage("§eType §ayes§e to confirm, or §ccancel§e to stop.");
        Bukkit.getScheduler().runTask(this, () ->
                sender.playSound(sender.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f)
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        if (offlineMail.containsKey(id)) {
            List<ItemStack> letters = new ArrayList<>(offlineMail.remove(id));

            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (ItemStack book : letters) {
                    p.getInventory().addItem(book);
                }
                p.sendMessage("§bA bird swoops in and delivers " + letters.size() + " letter"
                        + (letters.size() > 1 ? "s" : "") + " you missed!");
                p.playSound(p.getLocation(), Sound.ENTITY_PARROT_FLY, 1, 1);
            }, 200L);

            saveOfflineMail(); 
        }
    }

    private void deliverBook(Player sender, Player target, ItemStack book) {
        HashMap<Integer, ItemStack> leftovers = target.getInventory().addItem(book);
        if (!leftovers.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), book);
        }

        target.sendMessage("§bA bird lands at your feet, dropping a letter before taking flight once more.");
        sender.sendMessage("§bYour letter was delivered!");
        Bukkit.getScheduler().runTask(this, () -> {
            target.playSound(target.getLocation(), Sound.ENTITY_PARROT_FLY, 1, 1);
            sender.playSound(sender.getLocation(), Sound.ENTITY_PARROT_FLY, 1, 1);
        });
    }



    private void saveOfflineMail() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        FileConfiguration config = new YamlConfiguration();

        for (Map.Entry<UUID, List<ItemStack>> entry : offlineMail.entrySet()) {
            UUID uuid = entry.getKey();
            List<ItemStack> books = entry.getValue();

            List<Map<String, Object>> serialized = new ArrayList<>();
            for (ItemStack item : books) {
                serialized.add(item.serialize());
            }

            config.set("mail." + uuid.toString(), serialized);
        }

        try {
            config.save(offlineMailFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadOfflineMail() {
        if (!offlineMailFile.exists()) return;

        FileConfiguration config = YamlConfiguration.loadConfiguration(offlineMailFile);

        if (!config.contains("mail")) return;

        for (String key : config.getConfigurationSection("mail").getKeys(false)) {
            UUID uuid = UUID.fromString(key);

            List<Map<String, Object>> serialized =
                    (List<Map<String, Object>>) config.get("mail." + key);

            List<ItemStack> books = new ArrayList<>();
            for (Map<String, Object> map : serialized) {
                books.add(ItemStack.deserialize(map));
            }

            offlineMail.put(uuid, books);
        }
    }
}
