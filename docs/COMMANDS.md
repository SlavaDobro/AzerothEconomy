# Команды и права

Основной корень `/aeconomy`; сокращения в `aliases: []` выключены. Уже занятые команды не перехватываются. Команды консоли пишутся без `/`. Суммы gold — медные либо номиналы; пример `1g23s45c`. UUID принимается вместо имени; имя разрешается только по известному игроку из таблицы players.

| Действие | Команда | Право |
|---|---|---|
| Главное меню | `aeconomy` | `azeroth.use` |
| Кошелёк/валюты | `balance`, `wallet`, `currencies` | `azeroth.balance` |
| Перевод | `pay <ник/UUID> <валюта> <сумма>` | `azeroth.pay` |
| Обмен | `exchange <из> <в> <сумма>` | `azeroth.exchange` |
| История | `history [страница с 1]` | `azeroth.history` |
| Банк | `bank`, `bank open <вкладка с 0>` | `azeroth.bank` |
| Покупки банка | Кнопки GUI | `azeroth.bank.buy` |
| Переименование | `bank rename <номер> <MATERIAL> <имя>` | `azeroth.bank.rename` |
| Перенос из получения в банк | `bank store <номер> <ID>` | `azeroth.bank` |
| Денежный банк | `bank deposit/withdraw <валюта> <сумма>`, `bank balance` | `azeroth.bank.money` |
| Сохранение предмета из руки | `deposit` | `azeroth.deposit` |
| Получение | `inbox`, `withdraw <ID>` | `azeroth.withdraw` |
| Почта | `mail`, `mail read <ID>` | `azeroth.mail` |
| Подготовка вложений | `mail attach <ID>`, `mail clear` | `azeroth.mail.send` |
| Отправка | `mail send <ник> <валюта> <деньги> <COD> <тема>\|<текст>` | `azeroth.mail.send` |
| Получение письма | Кнопки письма | `azeroth.mail.claim` |
| Торговля | `trade <ник>`, `trade accept`, `trade cancel` | `azeroth.trade` |
| Деньги в торговле | `trade money <валюта> <сумма>` | `azeroth.trade` |
| Аукцион | `auction`, `auction search <текст>`, `auction mine` | `azeroth.auction` |
| Размещение/отмена | `auction sell <цена>` / GUI | `azeroth.auction.sell` |
| Покупка | GUI | `azeroth.auction.buy` |
| Чеки | `cheque issue <валюта> <сумма>`, `cheque redeem` | `azeroth.cheque` |
| Обычный XP | `xp balance`, `xp deposit/withdraw <целые очки>` | `azeroth.bank.xp` |
| Общий банк | `guild create <id>` | `azeroth.guild.create` (op) |
| Роли | `guild role <id> <игрок> OFFICER/MEMBER/REMOVE` | `azeroth.guild` + роль OWNER; назначение OWNER запрещено |
| Баланс общего банка | `guild balance <id> <валюта>` | `azeroth.guild` + членство |
| Деньги общего банка | `guild deposit/withdraw <id> <валюта> <сумма>` | `azeroth.guild` + роль |
| Предметы общего банка | `guild items <id>`, `guild store/take <id> <ID предмета>` | `azeroth.guild` + роль |
| Изменение денег | `give/take/set <ник/UUID> <валюта> <сумма>` | `azeroth.admin.money` |
| Денежная награда | `reward <UUID операции> <ник/UUID> <валюта> <сумма> <тема> <текст>` | `azeroth.admin.reward` |
| Предметная награда | `reward-item <UUID операции> <ник/UUID> <MATERIAL/nexo:id/mmo:TYPE:ID> <число>` | `azeroth.admin.reward` |
| NPC | `npc bank/mail <ник>` | `azeroth.admin.npc` |
| Диагностика | `diagnose` | `azeroth.admin.diagnose` |
| Резервная копия | `backup` | `azeroth.admin.backup` |
| Предметное восстановление | `recovery [ID database/inventory]` | `azeroth.admin.recovery`, только консоль |
| Восстановление XP | `xp-recovery [ID applied/not-applied]` | `azeroth.admin.recovery`, только консоль |

Обычные права по умолчанию true, административные — op. Для общего банка создание доступно op; владелец задаёт OFFICER/MEMBER. Права `azeroth.bank.remote` и `azeroth.mail.remote` обходят запрет удалённого доступа, по умолчанию op. Полный перечень — `src/main/resources/plugin.yml`. Для каждой валюты можно задать дополнительный permission; запреты transfer/trade/physical действуют независимо от права игрока.

Вложения следующего письма — временный список выбранных ID; сами предметы уже находятся в БД. Если игрок отключился или сервер перезапущен до отправки, предметы остаются в получении. Текст письма и сумма вводятся командами, списки/получение/подтверждения работают в GUI. При вводе суммы COD>0 денежное вложение должно быть 0, а предметное — непустым.

Чеки, общий банк, XP, проценты, выплаты и прочие дополнительные модули должны быть отдельно включены в config.yml. Табуляция подсказывает корневые команды, известные подкоманды, онлайн-игроков и валюты.

## Маунты и питомцы

| Действие | Команда | Право |
|---|---|---|
| Журнал маунтов | `/mounts` (`/mount`) | `terrawow.mounts` (true) |
| Журнал питомцев | `/pets` (`/pet`) | `terrawow.pets` (true) |
| Разблокировать маунта со всеми обликами | `/twm unlock <id\|all> [ник]` (без ника — себе) | `terrawow.admin` (op) |
| Выдать / забрать | `/twm give\|take <ник> <mount\|pet> <id> [облик]` | `terrawow.admin` (op) |
| Список, журнал игроку | `/twm list`, `/twm open <mounts\|pets> <ник>` | `terrawow.admin` |
| Призвать / отозвать | `/twm summon <ник> <mount\|pet> <id>`, `/twm dismiss <ник> <mount\|pet>` | `terrawow.admin` |
| Перечитать collections.yml | `/twm reload` | `terrawow.admin` |

Подробности: [COLLECTIONS.md](COLLECTIONS.md).
