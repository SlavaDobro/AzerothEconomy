package ru.terrawow.main.ui;

import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;

/**
 * Drawn WoW background in every server window (TerraWoWmain, menus, MMOCore/MythicLib): when a chest
 * window opens, its title becomes "background picture + the old title text in gold". The pictures are
 * the font "wowforever:gui" made by tools/wow_gui_backgrounds.py (panel_1..6 by window rows).
 * A title that already carries a picture (the main menu) is left as it is.
 */
public final class UiBackgrounds implements Listener {
  static final Key FONT = Key.key("wowforever", "gui");
  static final char SHIFT_TO_EDGE = '', SHIFT_BACK = '';
  static final TextColor TITLE = TextColor.color(0xFFD100);

  private static volatile boolean active;

  /** True while backgrounds are on: windows must not fill empty slots with glass panes. */
  public static boolean active() {
    return active;
  }

  /** Background character of a window with this many rows, or 0 if there is none. */
  public static char picture(int rows) {
    return rows >= 1 && rows <= 6 ? (char) (0xE000 + rows) : 0;
  }

  /** Does the title already start with a drawn background (private-use characters)? */
  public static boolean hasPicture(String plain) {
    for (int i = 0; i < plain.length(); i++) {
      char c = plain.charAt(i);
      if (c >= '' && c <= '') return true;
    }
    return false;
  }

  private final List<String> windows;

  public UiBackgrounds(List<String> windows) {
    this.windows = windows;
    active = true;
  }

  public static void disable() {
    active = false;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void open(InventoryOpenEvent e) {
    var inv = e.getInventory();
    if (inv.getType() != InventoryType.CHEST || !UiSounds.isWindow(inv.getHolder(false), windows)) return;
    char pic = picture(inv.getSize() / 9);
    if (pic == 0) return;
    Component old = e.titleOverride() != null ? e.titleOverride() : e.getView().title();
    String text = PlainTextComponentSerializer.plainText().serialize(old);
    if (hasPicture(text)) return;
    e.titleOverride(Component.empty()
        .append(Component.text("" + SHIFT_TO_EDGE + pic + SHIFT_BACK, NamedTextColor.WHITE).font(FONT))
        .append(Component.text(text.strip(), TITLE)));
  }
}
