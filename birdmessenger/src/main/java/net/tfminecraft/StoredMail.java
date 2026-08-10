package net.tfminecraft;

import java.util.UUID;

import org.bukkit.inventory.ItemStack;

public class StoredMail {

    private final UUID id;
    private final UUID sender;
    private final UUID recipient;
    private final ItemStack book;
    private final long deliveryTime;

    public StoredMail(
            UUID id,
            UUID sender,
            UUID recipient,
            ItemStack book,
            long deliveryTime
    ) {
        this.id = id;
        this.sender = sender;
        this.recipient = recipient;
        this.book = book;
        this.deliveryTime = deliveryTime;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSender() {
        return sender;
    }

    public UUID getRecipient() {
        return recipient;
    }

    public ItemStack getBook() {
        return book;
    }

    public long getDeliveryTime() {
        return deliveryTime;
    }
}
