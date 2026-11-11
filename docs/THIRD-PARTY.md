# Зависимости

В JAR включены SQLite JDBC 3.49.1.0 (Xerial, Apache-2.0/BSD notices), MariaDB Connector/J 3.5.2 (LGPL-2.1-or-later), их runtime-зависимости и метаданные лицензий META-INF. Исходники и условия:

- https://github.com/xerial/sqlite-jdbc/tree/3.49.1.0
- https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.2
- https://www.slf4j.org/license.html

MariaDB test server 11.4.5 скачан отдельно из официального архива с проверкой SHA-256 и не входит в JAR/ZIP исходников. Установленные Paper/плагины используются как предоставляемые сервером API; они не включены в JAR TerraWoWmain. Maven Wrapper предоставлен Apache Maven Wrapper 3.3.2, Maven 3.9.9 (Apache-2.0). Форматирование исходников выполнено google-java-format 1.37.0; форматировщик не нужен для запуска сервера.

TerraWoWmain — самостоятельный код этого проекта. Закрытые исходники, оформление и платные ресурсы UltraEconomy не использованы.
