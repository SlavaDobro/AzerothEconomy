# Маунты и питомцы (модуль коллекции)

Журнал маунтов и питомцев в духе WoW. Модуль заменяет McPets: призыв, владение, облики (скины) и сохранение выбора хранятся в TerraWoWmain. Сами существа — мобы MythicMobs с моделями ModelEngine. Плагин создаёт моба и делает игрока его владельцем, поэтому в навыках моба работает targeter `@owner`.

Включение: `modules.collections: true` в `config.yml` (по умолчанию включено). Настройки — `plugins/TerraWoWmain/collections.yml`, он создаётся при первом запуске. После правки выполните `/twm reload`.

## Как это работает в игре

- `/mounts` (или `/menu` → «Маунты») — журнал маунтов. Сначала идут полученные, затем серые «✘ Не получено».
  - **ЛКМ** — призыв за 3 секунды. Движение прерывает призыв, прогресс видно над панелью быстрого доступа. После призыва игрок сразу в седле.
  - **ПКМ** — облики маунта. Выбор сохраняется и применяется при следующем призыве.
  - Наземный маунт: Пробел — прыжок, Shift — спешиться. Летающий: Пробел — вверх, Shift — вниз, Shift на земле — спешиться.
  - Кнопка «Спешиться»: в воздухе игрок получает медленное падение. Маунт без всадника исчезает. При телепорте дальше 10 блоков, выходе и смерти маунт убирается.
- `/pets` (или `/menu` → «Питомцы») — журнал питомцев. Одновременно активен один питомец.
  - **ЛКМ** — призвать. Повторный клик по активному питомцу отзывает его.
  - **ПКМ** — облики. Смена облика сразу заменяет активного питомца.
  - Питомец идёт за владельцем, а если отстал больше чем на 20 блоков или остался в другом мире, телепортируется к нему. Последний питомец возвращается после перезахода и возрождения.

## collections.yml

```yaml
summon:
  mount-cast-seconds: 3     # призыв маунта
  pet-cast-seconds: 0       # питомец — сразу
  move-tolerance: 1.0       # на сколько блоков можно сдвинуться во время призыва
pet-follow:
  follow-distance: 3
  teleport-distance: 20
  speed: 1.3
  resummon-on-join: true

mounts:
  invincible:                       # ID: только [a-z0-9_]
    name: '<#A335EE>Непобедимый'
    mode: flying                    # flying | walking — только для подписи в журнале
    icon: 'nexo:wf_icon_mount_invincible'   # MATERIAL, nexo:<id> или mmo:TYPE:ID
    free: false                     # true — есть у всех без выдачи
    source: 'Король-лич (25, героический)'
    lore: ['...']
    skins:                          # первый облик — базовый, приходит вместе с маунтом
      default: {name: '...', mob: WF_Mount_Invincible}
      spectral: {name: '...', mob: WF_Mount_Invincible_Spectral, icon: ..., lore: [...]}
pets:
  murky: {..., skins: {default: {mob: WF_Pet_Murky}, golden: {mob: WF_Pet_Murky_Golden}}}
```

Каждый облик — отдельный моб MythicMobs: своя модель, оттенок (`tint`) или эффекты. Мобы для моделей команды создаёт `tools/install_mount.py` из папки библиотеки сервера: `WF_Mount_<Id>[_<Облик>]` и `WF_Pet_<Id>[_<Облик>]`. Если моба нет, в консоли при запуске будет `Коллекция: нет моба MythicMobs …`.

Требования к мобу маунта: `model{…;drive=true;ride=true}` и `mountmodel{…} @owner` с задержкой 1–2 тика при появлении, а также удаление при `modelhasdriver false`. Моб питомца нужен с пустыми `AIGoalSelectors`/`AITargetSelectors` и `Invincible: true` — водит его плагин.

## Команды и права

| Действие | Команда | Право |
|---|---|---|
| Журнал маунтов | `/mounts` (`/mount`) | `terrawow.mounts` (true) |
| Журнал питомцев | `/pets` (`/pet`) | `terrawow.pets` (true) |
| Выдать / забрать | `/twm give\|take <ник> <mount\|pet> <id> [облик]` | `terrawow.admin` (op) |
| Список ID | `/twm list` | `terrawow.admin` |
| Открыть журнал игроку | `/twm open <mounts\|pets> <ник>` | `terrawow.admin` |
| Призвать / отозвать за игрока | `/twm summon <ник> <mount\|pet> <id>`, `/twm dismiss <ник> <mount\|pet>` | `terrawow.admin` |
| Перечитать collections.yml | `/twm reload` | `terrawow.admin` |

`/twm` работает из консоли, поэтому награды можно выдавать из квестов, MythicMobs (`command{c="twm give <target.name> mount invincible"}`), CommandPanels `[console]` и т. п. Повторная выдача ничего не ломает. `give … <id> <облик>` выдаёт и сам маунт, если его ещё не было.

Данные игроков хранятся в `plugins/TerraWoWmain/collections/players/<uuid>.yml`: что получено, выбранные облики и последний питомец. Запись атомарная, через временный файл.
