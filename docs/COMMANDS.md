# Команды и права

Все команды плагина — подкоманды **`/twm`** (сокращение от TerraWoWmain): `/twm economy`, `/twm mount`, `/twm pet`, `/twm race`, `/twm racial`, `/twm reload`. Подсказка: `/twm help` и `/twm <раздел> help`. Короткие варианты для игроков: `/mounts`, `/pets`, `/race`, `/racial`. Все права начинаются с `twm.`.

## Экономика — `/twm economy …` (также `/twm eco`)

В таблице ниже подкоманды пишутся после `/twm economy`: например `/twm economy pay <ник> gold 1g`. сокращения в `aliases: []` выключены. Уже занятые команды не перехватываются. Команды консоли пишутся без `/`. Суммы gold — медные либо номиналы; пример `1g23s45c`. UUID принимается вместо имени; имя разрешается только по известному игроку из таблицы players.

| Действие | Команда | Право |
|---|---|---|
| Главное меню | `twm economy` | `twm.economy.use` |
| Кошелёк/валюты | `balance`, `wallet`, `currencies` | `twm.economy.balance` |
| Перевод | `pay <ник/UUID> <валюта> <сумма>` | `twm.economy.pay` |
| Обмен | `exchange <из> <в> <сумма>` | `twm.economy.exchange` |
| История | `history [страница с 1]` | `twm.economy.history` |
| Банк | `bank`, `bank open <вкладка с 0>` | `twm.economy.bank` |
| Покупки банка | Кнопки GUI | `twm.economy.bank.buy` |
| Переименование | `bank rename <номер> <MATERIAL> <имя>` | `twm.economy.bank.rename` |
| Перенос из получения в банк | `bank store <номер> <ID>` | `twm.economy.bank` |
| Денежный банк | `bank deposit/withdraw <валюта> <сумма>`, `bank balance` | `twm.economy.bank.money` |
| Сохранение предмета из руки | `deposit` | `twm.economy.deposit` |
| Получение | `inbox`, `withdraw <ID>` | `twm.economy.withdraw` |
| Почта | `mail`, `mail read <ID>` | `twm.economy.mail` |
| Подготовка вложений | `mail attach <ID>`, `mail clear` | `twm.economy.mail.send` |
| Отправка | `mail send <ник> <валюта> <деньги> <COD> <тема>\|<текст>` | `twm.economy.mail.send` |
| Получение письма | Кнопки письма | `twm.economy.mail.claim` |
| Торговля | `trade <ник>`, `trade accept`, `trade cancel` | `twm.economy.trade` |
| Деньги в торговле | `trade money <валюта> <сумма>` | `twm.economy.trade` |
| Аукцион | `auction`, `auction search <текст>`, `auction mine` | `twm.economy.auction` |
| Размещение/отмена | `auction sell <цена>` / GUI | `twm.economy.auction.sell` |
| Покупка | GUI | `twm.economy.auction.buy` |
| Чеки | `cheque issue <валюта> <сумма>`, `cheque redeem` | `twm.economy.cheque` |
| Обычный XP | `xp balance`, `xp deposit/withdraw <целые очки>` | `twm.economy.bank.xp` |
| Общий банк | `guild create <id>` | `twm.economy.guild.create` (op) |
| Роли | `guild role <id> <игрок> OFFICER/MEMBER/REMOVE` | `twm.economy.guild` + роль OWNER; назначение OWNER запрещено |
| Баланс общего банка | `guild balance <id> <валюта>` | `twm.economy.guild` + членство |
| Деньги общего банка | `guild deposit/withdraw <id> <валюта> <сумма>` | `twm.economy.guild` + роль |
| Предметы общего банка | `guild items <id>`, `guild store/take <id> <ID предмета>` | `twm.economy.guild` + роль |
| Изменение денег | `give/take/set <ник/UUID> <валюта> <сумма>` | `twm.economy.admin.money` |
| Денежная награда | `reward <UUID операции> <ник/UUID> <валюта> <сумма> <тема> <текст>` | `twm.economy.admin.reward` |
| Предметная награда | `reward-item <UUID операции> <ник/UUID> <MATERIAL/nexo:id/mmo:TYPE:ID> <число>` | `twm.economy.admin.reward` |
| NPC | `npc bank/mail <ник>` | `twm.economy.admin.npc` |
| Диагностика | `diagnose` | `twm.economy.admin.diagnose` |
| Резервная копия | `backup` | `twm.economy.admin.backup` |
| Предметное восстановление | `recovery [ID database/inventory]` | `twm.economy.admin.recovery`, только консоль |
| Восстановление XP | `xp-recovery [ID applied/not-applied]` | `twm.economy.admin.recovery`, только консоль |

Обычные права по умолчанию true, административные — op. Для общего банка создание доступно op; владелец задаёт OFFICER/MEMBER. Права `twm.economy.bank.remote` и `twm.economy.mail.remote` обходят запрет удалённого доступа, по умолчанию op. Полный перечень — `src/main/resources/plugin.yml`. Для каждой валюты можно задать дополнительный permission; запреты transfer/trade/physical действуют независимо от права игрока.

Вложения следующего письма — временный список выбранных ID; сами предметы уже находятся в БД. Если игрок отключился или сервер перезапущен до отправки, предметы остаются в получении. Текст письма и сумма вводятся командами, списки/получение/подтверждения работают в GUI. При вводе суммы COD>0 денежное вложение должно быть 0, а предметное — непустым.

Чеки, общий банк, XP, проценты, выплаты и прочие дополнительные модули должны быть отдельно включены в config.yml. Табуляция подсказывает корневые команды, известные подкоманды, онлайн-игроков и валюты.

## Маунты и питомцы

| Действие | Команда | Право |
|---|---|---|
| Журнал маунтов | `/mounts` (`/mount`) | `twm.mount` (true) |
| Журнал питомцев | `/pets` (`/pet`) | `twm.pet` (true) |
| Разблокировать маунта со всеми обликами | `/twm mount\|pet unlock <id\|all> [ник]` (без ника — себе) | `twm.admin` (op) |
| Выдать / забрать | `/twm mount\|pet give\|take <ник> <id> [облик]` | `twm.admin` (op) |
| Список, журнал игроку | `/twm mount\|pet list [ник]`, `/twm mount\|pet open <ник>` | `twm.admin` |
| Призвать / отозвать | `/twm mount\|pet summon <ник> <id>`, `/twm mount\|pet dismiss <ник>` | `twm.admin` |
| Перечитать collections.yml | `/twm mount reload` или `/twm reload` | `twm.admin` |

Подробности: [COLLECTIONS.md](COLLECTIONS.md).

## Расы

| Действие | Команда | Право |
|---|---|---|
| Карточка расы / создание персонажа | `/race` | `twm.race` (true) |
| Расовая способность | `/racial` | `twm.race` |
| Поставить / сбросить расу | `/twm race set <ник> <раса> [male\|female]`, `/twm race reset <ник>` | `twm.admin` (op) |
| Узнать, открыть выбор, перечитать | `/twm race info [ник]`, `/twm race open <ник>`, `/twm race reload` | `twm.admin` |

Подробности: [RACES.md](RACES.md).

## Меню

| Действие | Команда | Право |
|---|---|---|
| Главное меню | `/menu`, `/wow`, `/twm menu` | все |
| Открыть меню | `/twm menu <id>` | все (себе) |
| Открыть меню игроку | `twm menu <id> <ник>` | консоль или `twm.admin` |
| Список / перечитать | `/twm menu list`, `/twm menu reload` | `twm.admin` |

Подробности и формат файлов: [MENUS.md](MENUS.md).
