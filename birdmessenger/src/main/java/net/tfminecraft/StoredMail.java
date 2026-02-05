package net.tfminecraft;

import java.io.Serializable;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;

public class StoredMail implements Serializable {
    public UUID recipient;
    public ItemStack book;

    public StoredMail(UUID recipient, ItemStack book) {
        this.recipient = recipient;
        this.book = book;
    }
}
