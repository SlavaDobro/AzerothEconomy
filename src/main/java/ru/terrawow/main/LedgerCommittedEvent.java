package ru.terrawow.main;

import java.util.List;
import org.bukkit.event.*;

/** Delivered on Paper's main thread AFTER commit; immutable, not cancellable. */
public final class LedgerCommittedEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final List<Ledger.Change> changes;

  public LedgerCommittedEvent(List<Ledger.Change> changes) {
    this.changes = List.copyOf(changes);
  }

  public List<Ledger.Change> changes() {
    return changes;
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
