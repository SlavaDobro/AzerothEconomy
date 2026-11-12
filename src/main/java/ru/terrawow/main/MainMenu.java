package ru.terrawow.main;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** "Главное меню" buttons in our GUIs (mount/pet journals, race card). */
public final class MainMenu {
  private MainMenu() {}

  /** Our own main menu (menus module); without it the player "types" /menu in chat. */
  public static void open(JavaPlugin plugin, Player p) {
    p.closeInventory();
    if (plugin instanceof TerraWoWmain t && t.menuModule != null && t.menuModule.has(ru.terrawow.main.menu.MenuModule.MAIN)) {
      t.menuModule.open(p, ru.terrawow.main.menu.MenuModule.MAIN);
      return;
    }
    p.chat("/menu"); // goes through PlayerCommandPreprocessEvent like a typed command
  }
}
