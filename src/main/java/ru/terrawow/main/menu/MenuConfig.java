package ru.terrawow.main.menu;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Menus from plugins/TerraWoWmain/menus/*.yml (pure parsing, conditions and colours; unit-tested).
 *
 * <pre>
 * main:                              # menu id (/twm menu main)
 *   title: '&lt;#3A2410&gt;Главное меню'
 *   rows: 6
 *   fill: frame                      # item placed on every empty slot (optional)
 *   items:
 *     frame: {icon: BLACK_STAINED_GLASS_PANE, name: ' '}
 *     hero:
 *       slot: 4                      # or slots: [0, 8, '45-53']
 *       icon: 'head:{player}'        # MATERIAL | nexo:id | mmo:TYPE:ID | head:&lt;ник&gt;
 *       model-data: 2058             # custom model data (optional)
 *       glint: true
 *       name: '&lt;gold&gt;{player}'
 *       lore: ['&amp;7Класс: %mmocore_class%']
 *       show-if: '%mmocore_class_id% == HUMAN'   # several items in one slot: the first shown wins
 *       click: [close, 'delay 1', 'console mmocore:player {player}']   # left and right
 *       right-click: [...]           # optional, overrides click for the right button
 *       require: 'perm:twm.admin'    # click condition; deny: actions when it fails
 *       deny: ['message &amp;cНельзя']
 * </pre>
 *
 * Actions: close · open &lt;menu&gt; · delay &lt;ticks&gt; · console &lt;cmd&gt; · player &lt;cmd&gt; ·
 * chat &lt;text or /cmd&gt; · message &lt;text&gt; · sound &lt;key&gt; [volume] [pitch]. {player} is the
 * player's name; PlaceholderAPI placeholders work in texts, commands and conditions.
 *
 * Conditions: {@code a == b}, {@code a != b}, {@code a > b} (also &gt;=, &lt;, &lt;= on numbers),
 * {@code perm:node}, {@code !perm:node}, joined with {@code &&} / {@code ||}.
 */
public final class MenuConfig {
  public record Item(
      String id,
      List<Integer> slots,
      String icon,
      int modelData,
      boolean glint,
      String name,
      List<String> lore,
      String showIf,
      List<String> left,
      List<String> right,
      String require,
      List<String> deny) {}

  public record Menu(String id, String title, int rows, String fill, List<Item> items, String file) {
    public Item item(String id) {
      for (Item i : items) if (i.id().equals(id)) return i;
      return null;
    }
  }

  static final Set<String> ACTIONS = Set.of("close", "open", "delay", "console", "player", "chat", "message", "msg", "sound");

  public final Map<String, Menu> menus = new LinkedHashMap<>();

  /** {@code files}: file name → loaded yml. Every top-level key is one menu. */
  public MenuConfig(Map<String, YamlConfiguration> files) {
    for (var f : files.entrySet())
      for (String id : f.getValue().getKeys(false)) {
        if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException(f.getKey() + ": ID меню только [a-z0-9_]: " + id);
        if (menus.containsKey(id)) throw new IllegalArgumentException("Меню " + id + " есть в двух файлах (" + f.getKey() + ")");
        menus.put(id, menu(id, f.getValue().getConfigurationSection(id), f.getKey()));
      }
    for (Menu m : menus.values())
      for (Item i : m.items())
        for (List<String> list : List.of(i.left(), i.right(), i.deny()))
          for (String a : list)
            if (a.toLowerCase(Locale.ROOT).startsWith("open ")) {
              String target = a.substring(5).trim();
              if (!menus.containsKey(target))
                throw new IllegalArgumentException(m.file() + " / " + m.id() + " / " + i.id() + ": нет меню " + target);
            }
  }

  private static Menu menu(String id, ConfigurationSection s, String file) {
    String where = file + " / " + id;
    if (s == null) throw new IllegalArgumentException(where + ": пусто");
    int rows = s.getInt("rows", 6);
    if (rows < 1 || rows > 6) throw new IllegalArgumentException(where + ": rows 1–6");
    List<Item> items = new ArrayList<>();
    ConfigurationSection is = s.getConfigurationSection("items");
    if (is != null)
      for (String iid : is.getKeys(false)) items.add(item(where + " / " + iid, iid, is.getConfigurationSection(iid), rows * 9));
    String fill = s.getString("fill");
    if (fill != null && items.stream().noneMatch(i -> i.id().equals(fill)))
      throw new IllegalArgumentException(where + ": fill — нет предмета " + fill);
    return new Menu(id, s.getString("title", id), rows, fill, List.copyOf(items), file);
  }

  private static Item item(String where, String id, ConfigurationSection s, int size) {
    List<Integer> slots = new ArrayList<>();
    if (s.isInt("slot")) slots.add(s.getInt("slot"));
    for (Object o : s.getList("slots", List.of())) slots.addAll(range(where, String.valueOf(o)));
    for (int slot : slots)
      if (slot < 0 || slot >= size) throw new IllegalArgumentException(where + ": слот " + slot + " вне окна (0–" + (size - 1) + ")");
    List<String> click = actions(where, s.getStringList("click"));
    List<String> left = s.isList("left-click") ? actions(where, s.getStringList("left-click")) : click;
    List<String> right = s.isList("right-click") ? actions(where, s.getStringList("right-click")) : click;
    return new Item(id, List.copyOf(slots), s.getString("icon", "STONE"), s.getInt("model-data", 0), s.getBoolean("glint"),
        s.getString("name", ""), s.getStringList("lore"), s.getString("show-if", ""), left, right, s.getString("require", ""),
        actions(where, s.getStringList("deny")));
  }

  static List<Integer> range(String where, String text) {
    String t = text.trim();
    try {
      if (t.contains("-")) {
        String[] p = t.split("-", 2);
        int a = Integer.parseInt(p[0].trim()), b = Integer.parseInt(p[1].trim());
        List<Integer> out = new ArrayList<>();
        for (int i = Math.min(a, b); i <= Math.max(a, b); i++) out.add(i);
        return out;
      }
      return List.of(Integer.parseInt(t));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(where + ": слот '" + text + "'");
    }
  }

  private static List<String> actions(String where, List<String> list) {
    for (String a : list) {
      String head = a.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
      if (!ACTIONS.contains(head)) throw new IllegalArgumentException(where + ": действие '" + a + "' — одно из " + ACTIONS);
      if (head.equals("delay")) {
        String[] p = a.trim().split("\\s+");
        if (p.length < 2 || !p[1].matches("\\d{1,5}")) throw new IllegalArgumentException(where + ": delay <тики>");
      }
    }
    return List.copyOf(list);
  }

  // ---------------------------------------------------------------- conditions (after placeholders)
  private static final Pattern OP = Pattern.compile("^(.*?)\\s*(==|!=|>=|<=|>|<)\\s*(.*)$");

  /** Evaluates a condition whose placeholders and perm: checks are already replaced. Empty = true. */
  public static boolean test(String condition) {
    if (condition == null || condition.isBlank()) return true;
    for (String any : condition.split("\\|\\|")) {
      boolean all = true;
      for (String part : any.split("&&")) all &= atom(part.trim());
      if (all) return true;
    }
    return false;
  }

  private static boolean atom(String a) {
    if (a.equalsIgnoreCase("true")) return true;
    if (a.equalsIgnoreCase("false")) return false;
    Matcher m = OP.matcher(a);
    if (!m.matches()) return !a.isEmpty();
    String l = m.group(1).trim(), op = m.group(2), r = m.group(3).trim();
    switch (op) {
      case "==" -> {
        return l.equalsIgnoreCase(r);
      }
      case "!=" -> {
        return !l.equalsIgnoreCase(r);
      }
      default -> {
        try {
          double x = Double.parseDouble(l.replace(',', '.')), y = Double.parseDouble(r.replace(',', '.'));
          return switch (op) {
            case ">" -> x > y;
            case ">=" -> x >= y;
            case "<" -> x < y;
            default -> x <= y;
          };
        } catch (NumberFormatException e) {
          return false;
        }
      }
    }
  }

  // ---------------------------------------------------------------- colours
  private static final Map<Character, String> LEGACY = Map.ofEntries(
      Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"), Map.entry('2', "<dark_green>"), Map.entry('3', "<dark_aqua>"),
      Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"), Map.entry('6', "<gold>"), Map.entry('7', "<gray>"),
      Map.entry('8', "<dark_gray>"), Map.entry('9', "<blue>"), Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
      Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"), Map.entry('e', "<yellow>"), Map.entry('f', "<white>"),
      Map.entry('k', "<obfuscated>"), Map.entry('l', "<bold>"), Map.entry('m', "<strikethrough>"), Map.entry('n', "<underlined>"),
      Map.entry('o', "<italic>"), Map.entry('r', "<reset>"));
  private static final Pattern HEX = Pattern.compile("[&§]#([0-9A-Fa-f]{6})");

  /** "&7", "§a", "&#RRGGBB" → MiniMessage tags, so one text may mix both styles. */
  public static String toMini(String s) {
    if (s == null) return "";
    String out = HEX.matcher(s).replaceAll("<#$1>");
    StringBuilder b = new StringBuilder(out.length() + 16);
    for (int i = 0; i < out.length(); i++) {
      char c = out.charAt(i);
      if ((c == '&' || c == '§') && i + 1 < out.length()) {
        String tag = LEGACY.get(Character.toLowerCase(out.charAt(i + 1)));
        if (tag != null) {
          b.append(tag);
          i++;
          continue;
        }
      }
      b.append(c);
    }
    return b.toString();
  }
}
