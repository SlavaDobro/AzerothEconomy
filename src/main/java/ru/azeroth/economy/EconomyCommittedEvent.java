package ru.azeroth.economy;

import java.util.UUID;
import org.bukkit.event.*;

/** Notification only, after durable API commit. Cancellation cannot undo a transaction. */
public final class EconomyCommittedEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final UUID operation;
  private final String kind;

  public EconomyCommittedEvent(UUID operation, String kind) {
    this.operation = operation;
    this.kind = kind;
  }

  public UUID operation() {
    return operation;
  }

  public String kind() {
    return kind;
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
