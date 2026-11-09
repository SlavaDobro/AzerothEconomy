# Сборка и интеграции

Обследовано 9 ноября 2026 года, исходные файлы и последний журнал сервера. Основной сервер при начале работы был штатно остановлен.

| Компонент | Фактическая версия | Проверка |
|---|---|---|
| Paper | Minecraft 26.3, build 244, commit 35a2d15 | JAR, startup log, isolated runtime |
| Runtime | Temurin 25.0.5+8-LTS | server/runtime и startup log |
| JDK сборки | Oracle JDK 26.0.1, release 25 | javac + Maven Wrapper |
| VaultUnlocked | 2.20.3 (имя плагина Vault) | единственный legacy Economy, общие балансы, неверные суммы |
| CMI / CMILib | 9.8.11.2 / 1.6.1.3 | Economy.Enabled=false; порядок загрузки, отсутствие перехвата коротких команд |
| PlaceholderAPI | 2.12.3 | регистрация расширения и форматированный баланс |
| CommandPanels | 4.2.5 | существующий wow_menu, формат команд [chat]/[delay], добавленная кнопка |
| Nexo | 1.29.1 | публичный API itemFromId/build, реальный awakened_shaman_lifescepter через 4 маршрута |
| MMOItems | 6.10.1-SNAPSHOT, файл 20261012.174833-69 | LONG_SWORD и soulbound через бинарное хранение, проверка передачи привязки |
| MythicLib | 1.7.1-SNAPSHOT, файл 20261014.095819-18 | NBTItem и публичный MMOItems loader |
| MMOCore | 1.13.1-SNAPSHOT, файл 20261012.174459-71 | загрузка после провайдера, account UUID; XP overlay обнаружен |
| MMOInventory | 2.0-SNAPSHOT, файл 20261012.174733-38 | совместная загрузка; расширенные слоты не используются |
| MythicMobs | 5.14.1-SNAPSHOT-79b1fe5c | совместная загрузка; rewards API доступен из расширения/консоли |
| ModelEngine | R4.2.0 | совместная загрузка; модели не изменялись |
| PacketEvents | 2.14.0 | совместная загрузка; собственных пакетов плагин не отправляет |

MMOProfiles и иной менеджер нескольких профилей в составе JAR не обнаружены. `/character` — панель характеристик CommandPanels; MMOCore хранит данные аккаунта, guild-plugin=NONE. AzerothEconomy намеренно хранит баланс на UUID аккаунта. Если позднее появится переключение персонажей, валюта/банк останутся общими для аккаунта до отдельной миграции модели.

Существующие `/menu`, `/character`, классы, способности и задания не перестраивались. Меняется только свободная кнопка 49 в wow_menu. Настройки CMI Vault.Money=true сохранены; Economy.Enabled=false сохранено. CMI DB не изменялась и не импортировалась. При проверке CMI содержала одну запись, сумма Balance=0, ненулевых Balance=0.

Обычные суммы сторонних Vault-клиентов трактуются как медные. Существующие дробные цены потребуют осознанной перенастройки у соответствующего плагина. Нельзя молча считать старые «доллары» золотом или округлять покупки. Нативный Vault2 multi-currency сервис и отдельные shared banks Vault не заявлены.

Поддержка ограничений MMOItems проверяет SOULBOUND через публичный `ItemStat.whenLoaded(ReadMMOItem)` и `SoulboundData`, а также DISABLE_DROP. У VolatileMMOItem `getData()` сам не загружает характеристики — этот нюанс выявлен runtime-тестом и исправлен. При ошибке загрузки ограничения операция запрещена. Привязанные предметы не передаются между владельцами; личное хранение владельцем учитывает `soulbound.can-drop`. Все вложенные контейнеры Bundle/Shulker отвергаются. Произвольные ограничения других плагинов и их внешние хранилища не считаются проверенными.

Награды за мобов по умолчанию выключены; встроенная таблица использует Bukkit EntityType. Настраиваемые награды конкретных MythicMobs можно выдавать через консоль/API внешнего сценария со стабильным UUID награды. Отдельный listener внутренних MythicMobs ID не реализован. Квестовой системы нет.

## Документация, использованная при разработке

- [Paper 26.3 ItemStack API](https://jd.papermc.io/paper/26.3/org/bukkit/inventory/ItemStack.html) — бинарная сериализация; опубликованная страница build 230, сигнатуры дополнительно сверены с локальным build 244.
- [Paper scheduler](https://docs.papermc.io/paper/dev/scheduler/) и [databases](https://docs.papermc.io/paper/dev/using-databases/).
- [VaultUnlocked API](https://github.com/TheNewEconomy/VaultUnlockedAPI) — контракт, дополнительно javap локального 2.20.3.
- [Nexo API](https://docs.nexomc.com/community-guides/api) — публичные itemFromId/build; асинхронная загрузка предметов учтена (получение при действии, не в onEnable).
- [MMOItems Soulbound](https://docs.phoenixdevt.fr/mmoitems/features/soulbound.html) и [Default Configuration](https://docs.phoenixdevt.fr/mmoitems/general/default-config), локальные публичные сигнатуры текущего snapshot.
- [PlaceholderAPI expansion](https://wiki.placeholderapi.com/developers/creating-a-placeholderexpansion/).
- [CommandPanels](https://docs.commandpanels.net/), [CMI first run](https://www.zrips.net/cmi/first-run/).
- [UltraEconomy](https://ultraeconomy.com/) — сайт доступен как JS-приложение, закрытый код и платные ресурсы не извлекались.
