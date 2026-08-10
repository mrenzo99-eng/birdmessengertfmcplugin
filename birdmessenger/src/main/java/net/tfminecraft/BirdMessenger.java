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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    /*
     * Mail that has completed its flight but the recipient was offline.
     */
    private final Map<UUID, List<ItemStack>> offlineMail = new HashMap<>();

    /*
     * Mail that is currently in flight.
     *
     * This is persisted to disk so a server restart/crash does not
     * cancel the delivery.
     */
    private final Map<UUID, StoredMail> inFlightMail = new HashMap<>();

    /*
     * 12000 ticks = 10 minutes.
     */
    private static final long DELIVERY_DELAY_TICKS = 22000L;

    private File offlineMailFile;
    private File inFlightMailFile;

    @Override
    public void onEnable() {

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("BirdMessenger enabled!");

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        offlineMailFile = new File(
                getDataFolder(),
                "offline_mail.yml"
        );

        inFlightMailFile = new File(
                getDataFolder(),
                "in_flight_mail.yml"
        );

        /*
         * Load both types of saved mail.
         */
        loadOfflineMail();
        loadInFlightMail();

        /*
         * Resume any letters that were flying when the server stopped.
         */
        resumeInFlightMail();
    }

    @Override
    public void onDisable() {

        getLogger().info("BirdMessenger disabled!");

        /*
         * Save BOTH types of mail.
         *
         * This is especially important for /reload or a normal shutdown.
         */
        saveOfflineMail();
        saveInFlightMail();
    }

    /*
     * ============================================================
     * BIRD COOP
     * ============================================================
     */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRightClickBirdCoop(PlayerInteractEvent e) {

        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = e.getClickedBlock();

        if (block == null) {
            return;
        }

        /*
         * Check for the ItemsAdder bird coop.
         */
        if (!TLibs.getBlockAPI().getChecker()
                .checkBlock(block, "ia.tfmc:bird_coop")) {
            return;
        }

        Player p = e.getPlayer();

        /*
         * Sneaking can still be used for other interactions.
         */
        if (p.isSneaking()) {
            return;
        }

        Inventory inv = Bukkit.createInventory(
                null,
                9,
                "Bird Messenger"
        );

        ItemStack pane =
                new ItemStack(Material.GRAY_STAINED_GLASS_PANE);

        ItemMeta meta = pane.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§7");
            pane.setItemMeta(meta);
        }

        /*
         * Fill every slot except the center slot.
         */
        for (int i = 0; i < 9; i++) {
            if (i != 4) {
                inv.setItem(i, pane);
            }
        }

        p.openInventory(inv);

        openInventories.put(
                p.getUniqueId(),
                inv
        );

        e.setCancelled(true);
    }

    /*
     * ============================================================
     * INVENTORY CLICK
     * ============================================================
     */

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {

        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }

        UUID id = p.getUniqueId();

        if (!openInventories.containsKey(id)) {
            return;
        }

        if (!e.getView().getTitle().equals("Bird Messenger")) {
            return;
        }

        Inventory top = e.getView().getTopInventory();

        /*
         * Clicking inside the Bird Messenger inventory.
         */
        if (e.getClickedInventory() == top) {

            /*
             * Only slot 4 is usable.
             */
            if (e.getSlot() != 4) {
                e.setCancelled(true);
                return;
            }

            ItemStack cursor = e.getCursor();

            /*
             * Allow books.
             */
            if (cursor != null &&
                    (cursor.getType() == Material.WRITABLE_BOOK ||
                     cursor.getType() == Material.WRITTEN_BOOK)) {

                /*
                 * Wait a few ticks so Bukkit finishes placing
                 * the item before we inspect slot 4.
                 */
                Bukkit.getScheduler().runTaskLater(this, () -> {

                    ItemStack placed = top.getItem(4);

                    if (placed != null &&
                            (placed.getType() == Material.WRITABLE_BOOK ||
                             placed.getType() == Material.WRITTEN_BOOK)) {

                        p.closeInventory();

                        p.playSound(
                                p.getLocation(),
                                Sound.ENTITY_PARROT_FLY,
                                1f,
                                1f
                        );
                    }

                }, 3L);

            } else if (cursor != null &&
                    cursor.getType() != Material.AIR) {

                p.sendMessage(
                        "§cOnly books can be sent by bird."
                );

                e.setCancelled(true);
            }
        }

        /*
         * Shift-clicking a book from the player's inventory.
         */
        else if (e.getClickedInventory() == p.getInventory()
                && e.isShiftClick()) {

            ItemStack current = e.getCurrentItem();

            if (current != null &&
                    (current.getType() == Material.WRITABLE_BOOK ||
                     current.getType() == Material.WRITTEN_BOOK)) {

                e.setCancelled(true);

                Bukkit.getScheduler().runTaskLater(this, () -> {

                    ItemStack slotItem = top.getItem(4);

                    if (slotItem == null ||
                            slotItem.getType() == Material.AIR) {

                        top.setItem(4, current.clone());

                        current.setAmount(
                                current.getAmount() - 1
                        );

                        p.closeInventory();

                        p.playSound(
                                p.getLocation(),
                                Sound.ENTITY_PARROT_FLY,
                                1f,
                                1f
                        );

                    } else {

                        p.sendMessage(
                                "§cYou can only place one book at a time."
                        );
                    }

                }, 1L);
            }
        }
    }

    /*
     * ============================================================
     * INVENTORY DRAG
     * ============================================================
     *
     * Prevents players from bypassing the book-only restriction
     * by dragging items into slot 4.
     */

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {

        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }

        UUID id = p.getUniqueId();

        if (!openInventories.containsKey(id)) {
            return;
        }

        if (!e.getView().getTitle().equals("Bird Messenger")) {
            return;
        }

        /*
         * If slot 4 is affected by the drag, inspect the item.
         */
        if (e.getRawSlots().contains(4)) {

            ItemStack oldCursor = e.getOldCursor();

            if (oldCursor == null ||
                    (oldCursor.getType() != Material.WRITABLE_BOOK &&
                     oldCursor.getType() != Material.WRITTEN_BOOK)) {

                e.setCancelled(true);

                if (oldCursor != null &&
                        oldCursor.getType() != Material.AIR) {

                    p.sendMessage(
                            "§cOnly books can be sent by bird."
                    );
                }
            }
        }

        /*
         * Prevent dragging into any of the decorative slots.
         */
        for (int rawSlot : e.getRawSlots()) {

            if (rawSlot < e.getView().getTopInventory().getSize()
                    && rawSlot != 4) {

                e.setCancelled(true);
                return;
            }
        }
    }

    /*
     * ============================================================
     * INVENTORY CLOSE
     * ============================================================
     */

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {

        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }

        UUID id = p.getUniqueId();

        if (!openInventories.containsKey(id)) {
            return;
        }

        Inventory inv = openInventories.remove(id);

        ItemStack book = inv.getItem(4);

        if (book == null ||
                (book.getType() != Material.WRITABLE_BOOK &&
                 book.getType() != Material.WRITTEN_BOOK)) {

            return;
        }

        pendingBooks.put(id, book);

        awaitingRecipient.put(id, true);

        p.sendMessage(
                "§eWho would you like to send this letter to?"
        );

        p.sendMessage(
                "§7Type §ccancel §7to return the letter."
        );
    }

    /*
     * ============================================================
     * CHAT
     * ============================================================
     */

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {

        Player sender = e.getPlayer();

        UUID id = sender.getUniqueId();

        String msg = e.getMessage().trim();

        /*
         * ========================================================
         * CONFIRMATION
         * ========================================================
         */

        if (awaitingConfirmation.getOrDefault(id, false)) {

            e.setCancelled(true);

            /*
             * YES
             */
            if (msg.equalsIgnoreCase("yes")) {

                awaitingConfirmation.put(id, false);

                String name =
                        pendingRecipientName.remove(id);

                ItemStack book =
                        pendingBooks.remove(id);

                if (book == null) {

                    sender.sendMessage(
                            "§cNo book found to send."
                    );

                    return;
                }

                /*
                 * Switch back to the main server thread.
                 */
                Bukkit.getScheduler().runTask(this, () -> {

                    Player target =
                            Bukkit.getPlayerExact(name);

                    UUID recipientId;

                    if (target != null) {

                        recipientId =
                                target.getUniqueId();

                    } else {

                        /*
                         * Use the offline player's UUID.
                         */
                        recipientId =
                                Bukkit.getOfflinePlayer(name)
                                        .getUniqueId();
                    }

                    /*
                     * Generate a unique ID for this letter.
                     */
                    UUID mailId =
                            UUID.randomUUID();

                    /*
                     * Current time + 10 minutes.
                     *
                     * 50 milliseconds = 1 Minecraft tick.
                     */
                    long deliveryTime =
                            System.currentTimeMillis()
                                    + (DELIVERY_DELAY_TICKS * 50L);

                    /*
                     * Create the persistent mail record.
                     */
                    StoredMail mail = new StoredMail(
                            mailId,
                            sender.getUniqueId(),
                            recipientId,
                            book.clone(),
                            deliveryTime
                    );

                    /*
                     * IMPORTANT:
                     *
                     * Save the letter BEFORE starting the timer.
                     *
                     * This means that if the server crashes one
                     * second later, the letter still exists on disk.
                     */
                    inFlightMail.put(
                            mailId,
                            mail
                    );

                    saveInFlightMail();

                    sender.sendMessage(
                            "§aYour bird takes flight to deliver the letter..."
                    );

                    sender.playSound(
                            sender.getLocation(),
                            Sound.ENTITY_PARROT_AMBIENT,
                            1f,
                            1.2f
                    );

                    /*
                     * Begin/resume the delivery timer.
                     */
                    scheduleMail(mail);
                });

            }

            /*
             * CANCEL
             */
            else if (msg.equalsIgnoreCase("cancel")) {

                e.setCancelled(true);

                awaitingConfirmation.put(id, false);

                ItemStack book =
                        pendingBooks.remove(id);

                if (book != null) {

                    Bukkit.getScheduler().runTask(this, () -> {

                        HashMap<Integer, ItemStack> leftovers =
                                sender.getInventory()
                                        .addItem(book);

                        /*
                         * If their inventory is full, drop the
                         * book at their feet instead of losing it.
                         */
                        if (!leftovers.isEmpty()) {

                            for (ItemStack leftover :
                                    leftovers.values()) {

                                sender.getWorld()
                                        .dropItemNaturally(
                                                sender.getLocation(),
                                                leftover
                                        );
                            }
                        }

                        sender.sendMessage(
                                "§eYour letter has been returned to you."
                        );

                        sender.playSound(
                                sender.getLocation(),
                                Sound.BLOCK_NOTE_BLOCK_BASS,
                                1f,
                                0.5f
                        );
                    });
                }

                pendingRecipientName.remove(id);

                sender.sendMessage(
                        "§cLetter cancelled."
                );
            }

            /*
             * Invalid response.
             */
            else {

                e.setCancelled(true);

                sender.sendMessage(
                        "§7Please type §ayes§7 to confirm or §ccancel§7 to stop."
                );
            }

            return;
        }

        /*
         * ========================================================
         * RECIPIENT
         * ========================================================
         */

        if (!awaitingRecipient.getOrDefault(id, false)) {
            return;
        }

        e.setCancelled(true);

        /*
         * CANCEL RECIPIENT SELECTION
         */
        if (msg.equalsIgnoreCase("cancel")) {

            awaitingRecipient.put(id, false);

            ItemStack book =
                    pendingBooks.remove(id);

            if (book != null) {

                Bukkit.getScheduler().runTask(this, () -> {

                    HashMap<Integer, ItemStack> leftovers =
                            sender.getInventory()
                                    .addItem(book);

                    if (!leftovers.isEmpty()) {

                        for (ItemStack leftover :
                                leftovers.values()) {

                            sender.getWorld()
                                    .dropItemNaturally(
                                            sender.getLocation(),
                                            leftover
                                    );
                        }
                    }

                    sender.sendMessage(
                            "§eYour letter has been returned to you."
                    );

                    sender.playSound(
                            sender.getLocation(),
                            Sound.BLOCK_NOTE_BLOCK_BASS,
                            1f,
                            0.5f
                    );
                });
            }

            sender.sendMessage(
                    "§cLetter cancelled."
            );

            return;
        }

        /*
         * Store recipient name.
         */
        awaitingRecipient.put(id, false);

        pendingRecipientName.put(id, msg);

        awaitingConfirmation.put(id, true);

        sender.sendMessage(
                "§eThe person you want to send to is §a"
                        + msg
                        + "§e."
        );

        sender.sendMessage(
                "§eType §ayes§e to confirm, or §ccancel§e to stop."
        );

        Bukkit.getScheduler().runTask(this, () ->
                sender.playSound(
                        sender.getLocation(),
                        Sound.BLOCK_NOTE_BLOCK_PLING,
                        1f,
                        1.5f
                )
        );
    }

    /*
     * ============================================================
     * PLAYER JOIN
     * ============================================================
     */

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {

        Player p = e.getPlayer();

        UUID id = p.getUniqueId();

        if (!offlineMail.containsKey(id)) {
            return;
        }

        List<ItemStack> letters =
                new ArrayList<>(
                        offlineMail.remove(id)
                );

        Bukkit.getScheduler().runTaskLater(this, () -> {

            for (ItemStack book : letters) {

                HashMap<Integer, ItemStack> leftovers =
                        p.getInventory().addItem(book);

                /*
                 * If the inventory is full, drop the book.
                 */
                if (!leftovers.isEmpty()) {

                    for (ItemStack leftover :
                            leftovers.values()) {

                        p.getWorld().dropItemNaturally(
                                p.getLocation(),
                                leftover
                        );
                    }
                }
            }

            p.sendMessage(
                    "§bA bird swoops in and delivers "
                            + letters.size()
                            + " letter"
                            + (letters.size() > 1 ? "s" : "")
                            + " you missed!"
            );

            p.playSound(
                    p.getLocation(),
                    Sound.ENTITY_PARROT_FLY,
                    1,
                    1
            );

        }, 200L);

        saveOfflineMail();
    }

    /*
     * ============================================================
     * PLAYER QUIT
     * ============================================================
     *
     * Currently we don't need to cancel anything when a player
     * leaves. In-flight mail is deliberately independent of the
     * sender being online.
     */

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {

        UUID id = e.getPlayer().getUniqueId();

        /*
         * Clean up GUI/input state.
         *
         * If the player disconnects before confirming the letter,
         * return the pending book to memory cleanup rather than
         * accidentally leaving stale chat state behind.
         */
        openInventories.remove(id);
        awaitingRecipient.remove(id);
        awaitingConfirmation.remove(id);
        pendingRecipientName.remove(id);

        /*
         * We intentionally do NOT remove pendingBooks here yet.
         *
         * The book was taken out of the GUI, so this is an edge case
         * we can handle separately if desired.
         */
    }

    /*
     * ============================================================
     * MAIL DELIVERY
     * ============================================================
     */

    private void scheduleMail(StoredMail mail) {

        long remaining =
                mail.getDeliveryTime()
                        - System.currentTimeMillis();

        /*
         * If the delivery time has already passed while the
         * server was offline, deliver immediately.
         */
        if (remaining <= 0) {

            deliverStoredMail(mail);

            return;
        }

        /*
         * Convert milliseconds back to Minecraft ticks.
         */
        long ticks =
                Math.max(
                        1L,
                        remaining / 50L
                );

        Bukkit.getScheduler().runTaskLater(
                this,
                () -> deliverStoredMail(mail),
                ticks
        );
    }

    /*
     * Actually deliver a persistent letter.
     */
    private void deliverStoredMail(StoredMail mail) {

        /*
         * Make sure this mail still exists.
         *
         * This protects against accidentally scheduling the same
         * letter twice.
         */
        if (!inFlightMail.containsKey(mail.getId())) {
            return;
        }

        Player target =
                Bukkit.getPlayer(
                        mail.getRecipient()
                );

        /*
         * Recipient is offline.
         */
        if (target == null || !target.isOnline()) {

            offlineMail
                    .computeIfAbsent(
                            mail.getRecipient(),
                            k -> new ArrayList<>()
                    )
                    .add(
                            mail.getBook().clone()
                    );

            /*
             * Save offline mail FIRST.
             */
            saveOfflineMail();

            /*
             * Only remove the in-flight copy after the offline
             * copy has safely been written.
             */
            inFlightMail.remove(
                    mail.getId()
            );

            saveInFlightMail();

            Player sender =
                    Bukkit.getPlayer(
                            mail.getSender()
                    );

            if (sender != null &&
                    sender.isOnline()) {

                sender.sendMessage(
                        "§bThey are offline — your letter will be delivered when they log in!"
                );

                sender.playSound(
                        sender.getLocation(),
                        Sound.ENTITY_PARROT_AMBIENT,
                        1f,
                        1f
                );
            }

            return;
        }

        /*
         * Recipient is online.
         *
         * We deliver the item first, then remove the persistent
         * record. This prioritizes never losing a letter.
         */
        Player sender =
                Bukkit.getPlayer(
                        mail.getSender()
                );

        deliverBook(
                sender,
                target,
                mail.getBook().clone()
        );

        /*
         * Now that delivery has occurred, remove the persistent
         * in-flight record.
         */
        inFlightMail.remove(
                mail.getId()
        );

        saveInFlightMail();
    }

    /*
     * ============================================================
     * NORMAL DELIVERY
     * ============================================================
     */

    private void deliverBook(
            Player sender,
            Player target,
            ItemStack book
    ) {

        HashMap<Integer, ItemStack> leftovers =
                target.getInventory().addItem(book);

        /*
         * Inventory is full.
         *
         * Drop the actual leftover item instead of losing it.
         */
        if (!leftovers.isEmpty()) {

            for (ItemStack leftover :
                    leftovers.values()) {

                target.getWorld().dropItemNaturally(
                        target.getLocation(),
                        leftover
                );
            }
        }

        target.sendMessage(
                "§bA bird lands at your feet, dropping a letter before taking flight once more."
        );

        if (sender != null &&
                sender.isOnline()) {

            sender.sendMessage(
                    "§bYour letter was delivered!"
            );
        }

        target.playSound(
                target.getLocation(),
                Sound.ENTITY_PARROT_FLY,
                1,
                1
        );

        if (sender != null &&
                sender.isOnline()) {

            sender.playSound(
                    sender.getLocation(),
                    Sound.ENTITY_PARROT_FLY,
                    1,
                    1
            );
        }
    }

    /*
     * ============================================================
     * OFFLINE MAIL SAVE
     * ============================================================
     */

    private void saveOfflineMail() {

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        FileConfiguration config =
                new YamlConfiguration();

        for (Map.Entry<UUID, List<ItemStack>> entry :
                offlineMail.entrySet()) {

            UUID uuid =
                    entry.getKey();

            List<ItemStack> books =
                    entry.getValue();

            List<Map<String, Object>> serialized =
                    new ArrayList<>();

            for (ItemStack item : books) {

                serialized.add(
                        item.serialize()
                );
            }

            config.set(
                    "mail." + uuid.toString(),
                    serialized
            );
        }

        try {

            config.save(
                    offlineMailFile
            );

        } catch (IOException e) {

            getLogger().severe(
                    "Could not save offline mail!"
            );

            e.printStackTrace();
        }
    }

    /*
     * ============================================================
     * OFFLINE MAIL LOAD
     * ============================================================
     */

    @SuppressWarnings("unchecked")
    private void loadOfflineMail() {

        if (!offlineMailFile.exists()) {
            return;
        }

        FileConfiguration config =
                YamlConfiguration.loadConfiguration(
                        offlineMailFile
                );

        if (!config.contains("mail")) {
            return;
        }

        if (config.getConfigurationSection("mail") == null) {
            return;
        }

        for (String key :
                config.getConfigurationSection("mail")
                        .getKeys(false)) {

            try {

                UUID uuid =
                        UUID.fromString(key);

                List<Map<String, Object>> serialized =
                        (List<Map<String, Object>>)
                                config.get("mail." + key);

                if (serialized == null) {
                    continue;
                }

                List<ItemStack> books =
                        new ArrayList<>();

                for (Map<String, Object> map :
                        serialized) {

                    books.add(
                            ItemStack.deserialize(map)
                    );
                }

                offlineMail.put(
                        uuid,
                        books
                );

            } catch (Exception ex) {

                getLogger().warning(
                        "Could not load offline mail for "
                                + key
                );

                ex.printStackTrace();
            }
        }
    }

    /*
     * ============================================================
     * IN-FLIGHT MAIL SAVE
     * ============================================================
     */

    private void saveInFlightMail() {

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        FileConfiguration config =
                new YamlConfiguration();

        for (StoredMail mail :
                inFlightMail.values()) {

            String path =
                    "mail." + mail.getId();

            config.set(
                    path + ".sender",
                    mail.getSender().toString()
            );

            config.set(
                    path + ".recipient",
                    mail.getRecipient().toString()
            );

            config.set(
                    path + ".delivery-time",
                    mail.getDeliveryTime()
            );

            config.set(
                    path + ".book",
                    mail.getBook().serialize()
            );
        }

        try {

            config.save(
                    inFlightMailFile
            );

        } catch (IOException e) {

            getLogger().severe(
                    "Could not save in-flight mail!"
            );

            e.printStackTrace();
        }
    }

    /*
     * ============================================================
     * IN-FLIGHT MAIL LOAD
     * ============================================================
     */

    @SuppressWarnings("unchecked")
    private void loadInFlightMail() {

        if (!inFlightMailFile.exists()) {
            return;
        }

        FileConfiguration config =
                YamlConfiguration.loadConfiguration(
                        inFlightMailFile
                );

        if (!config.contains("mail")) {
            return;
        }

        if (config.getConfigurationSection("mail") == null) {
            return;
        }

        for (String key :
                config.getConfigurationSection("mail")
                        .getKeys(false)) {

            try {

                UUID id =
                        UUID.fromString(key);

                String senderString =
                        config.getString(
                                "mail." + key + ".sender"
                        );

                String recipientString =
                        config.getString(
                                "mail." + key + ".recipient"
                        );

                if (senderString == null ||
                        recipientString == null) {

                    continue;
                }

                UUID sender =
                        UUID.fromString(
                                senderString
                        );

                UUID recipient =
                        UUID.fromString(
                                recipientString
                        );

                long deliveryTime =
                        config.getLong(
                                "mail." + key + ".delivery-time"
                        );

                Map<String, Object> serializedBook =
                        (Map<String, Object>)
                                config.get(
                                        "mail." + key + ".book"
                                );

                if (serializedBook == null) {
                    continue;
                }

                ItemStack book =
                        ItemStack.deserialize(
                                serializedBook
                        );

                StoredMail mail =
                        new StoredMail(
                                id,
                                sender,
                                recipient,
                                book,
                                deliveryTime
                        );

                inFlightMail.put(
                        id,
                        mail
                );

            } catch (Exception ex) {

                getLogger().warning(
                        "Could not load in-flight mail "
                                + key
                );

                ex.printStackTrace();
            }
        }
    }

    /*
     * ============================================================
     * RESUME IN-FLIGHT MAIL
     * ============================================================
     */

    private void resumeInFlightMail() {

        /*
         * Copy the values so the map can safely be modified by
         * delivery operations.
         */
        for (StoredMail mail :
                new ArrayList<>(inFlightMail.values())) {

            scheduleMail(mail);
        }

        if (!inFlightMail.isEmpty()) {

            getLogger().info(
                    "Resumed "
                            + inFlightMail.size()
                            + " in-flight letter(s)."
            );
        }
    }
}
