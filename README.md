# mcp-yandex

MCP-сервер для Яндекс.Почты (IMAP/SMTP), Яндекс.Календаря (CalDAV) и Яндекс.Диска (WebDAV).
Работает с личными аккаунтами и Яндекс 360 для бизнеса.

## Инструменты

| Инструмент                | Что делает                                                             |
|---------------------------|------------------------------------------------------------------------|
| `mail_list_folders`       | Список папок с количеством писем и непрочитанных                       |
| `mail_list_messages`      | Список писем в папке (UID, отправитель, тема, дата), от новых к старым |
| `mail_get_message`        | Текст письма по UID + список вложений                                  |
| `mail_save_attachment`    | Сохраняет вложение на диск, возвращает путь                            |
| `mail_send`               | Отправляет письмо через SMTP                                           |
| `calendar_list_calendars` | Список календарей пользователя                                         |
| `calendar_list_events`    | События на день (включая повторяющиеся)                                |
| `calendar_get_event`      | Детали события: участники, описание, ссылка на встречу, вложения       |
| `calendar_create_event`   | Создаёт встречу, участникам уходят приглашения                         |
| `disk_list`               | Содержимое папки Диска: имена, типы, размеры, даты                     |
| `disk_download`           | Скачивает файл с Диска на локальный диск                               |
| `disk_upload`             | Загружает локальный файл на Диск                                       |
| `disk_mkdir`              | Создаёт папку на Диске                                                 |
| `disk_move`               | Перемещает/переименовывает файл или папку                              |
| `disk_delete`             | Удаляет файл или папку (необратимо через API)                          |

## Сборка

### Docker (рекомендуется)

```bash
docker build -t mcp-yandex:latest .
```

Локальные JDK и Maven не нужны — сборка целиком проходит внутри multi-stage образа.
В образе задан часовой пояс `Europe/Moscow` (переопределяется переменной `TZ`),
вложения сохраняются в `/attachments` — примонтируйте туда каталог хоста.

### Транспорты

- **Streamable HTTP (для постоянного сервера):** задаётся `YANDEX_MCP_TRANSPORT=http`.
  Один контейнер обслуживает много клиентов, копии не плодятся:

  ```bash
  docker run -d --restart unless-stopped --name mcp-yandex \
    -p 127.0.0.1:8191:8081 \
    -e YANDEX_MCP_TRANSPORT=http \
    -e YANDEX_EMAIL=... -e YANDEX_MAIL_PASSWORD=... \
    -e YANDEX_CALDAV_PASSWORD=... -e YANDEX_DISK_PASSWORD=... \
    -v "$HOME/.mcp/yandex-mcp/attachments:/attachments" \
    mcp-yandex:latest
  # Endpoint для MCP-клиентов: http://127.0.0.1:8191/mcp
  ```

  Порт — `YANDEX_MCP_HTTP_PORT` (по умолчанию `8081`), путь — `YANDEX_MCP_HTTP_ENDPOINT` (по умолчанию `/mcp`).

- **stdio (по умолчанию):** обмен через stdin/stdout (`docker run -i --rm ...`).

### Альтернатива: локальный jar

```bash
mvn package
# → target/mcp-yandex-1.0.jar (fat jar)
```

Требуется JDK 21+.

## Настройка доступа

Аккаунт с двухфакторной аутентификацией: создайте **пароли приложений** на
[id.yandex.ru/security/app-passwords](https://id.yandex.ru/security/app-passwords):

1. Пароль типа **«Почта»** → `YANDEX_MAIL_PASSWORD`
2. Пароль типа **«Календарь»** → `YANDEX_CALDAV_PASSWORD`
3. Пароль типа **«Файлы»** → `YANDEX_DISK_PASSWORD`

Либо используйте OAuth-токен (`YANDEX_OAUTH_TOKEN`) — он применяется к почте (XOAUTH2), календарю и диску.
Для Яндекс 360 может потребоваться, чтобы администратор организации разрешил IMAP и пароли приложений.

### Переменные окружения

| Переменная                   | Обязательна | Описание                                                        |
|------------------------------|-------------|-----------------------------------------------------------------|
| `YANDEX_EMAIL`               | да          | Адрес почты (логин), например `user@company.ru`                 |
| `YANDEX_MAIL_PASSWORD`       | *           | Пароль приложения «Почта»                                       |
| `YANDEX_CALDAV_PASSWORD`     | *           | Пароль приложения «Календарь»                                   |
| `YANDEX_DISK_PASSWORD`       | *           | Пароль приложения «Файлы» (WebDAV Яндекс.Диска)                 |
| `YANDEX_OAUTH_TOKEN`         | *           | OAuth-токен (вместо паролей приложений)                         |
| `YANDEX_MCP_ATTACHMENTS_DIR` | нет         | Куда сохранять вложения (по умолчанию `~/Downloads/yandex-mcp`) |
| `YANDEX_MCP_TRANSPORT`       | нет         | `stdio` (по умолчанию) или `http` — постоянный streamable HTTP-сервер |
| `YANDEX_MCP_HTTP_PORT`       | нет         | Порт HTTP-режима (по умолчанию `8081`) |
| `YANDEX_MCP_HTTP_ENDPOINT`   | нет         | Путь MCP endpoint в HTTP-режиме (по умолчанию `/mcp`) |

\* нужен OAuth-токен **или** хотя бы один пароль приложения; инструменты почты/календаря/диска
регистрируются только при наличии соответствующих учётных данных.

## Подключение к Claude Code

### Через Docker (рекомендуется)

```bash
claude mcp add yandex \
  --env YANDEX_EMAIL=user@company.ru \
  --env YANDEX_MAIL_PASSWORD=xxxx \
  --env YANDEX_CALDAV_PASSWORD=yyyy \
  --env YANDEX_DISK_PASSWORD=zzzz \
  -- docker run -i --rm \
     -e YANDEX_EMAIL -e YANDEX_MAIL_PASSWORD -e YANDEX_CALDAV_PASSWORD -e YANDEX_DISK_PASSWORD \
     -v "$HOME/.mcp/yandex-mcp/attachments:/attachments" \
     mcp-yandex:1.0
```

Или вручную в `.mcp.json`:

```json
{
  "mcpServers": {
    "yandex": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "-e", "YANDEX_EMAIL=user@company.ru",
        "-e", "YANDEX_MAIL_PASSWORD=xxxx",
        "-e", "YANDEX_CALDAV_PASSWORD=yyyy",
        "-e", "YANDEX_DISK_PASSWORD=zzzz",
        "-v", "/Users/вы/Downloads/yandex-mcp:/attachments",
        "mcp-yandex:1.0"
      ]
    }
  }
}
```

Флаг `-i` обязателен (обмен идёт через stdin/stdout), `-t` не нужен. Том `-v` нужен только
для `mail_save_attachment` и `disk_download`/`disk_upload` — без него файлы останутся внутри контейнера.

### Через локальный jar

```bash
claude mcp add yandex \
  --env YANDEX_EMAIL=user@company.ru \
  --env YANDEX_MAIL_PASSWORD=xxxx \
  --env YANDEX_CALDAV_PASSWORD=yyyy \
  --env YANDEX_DISK_PASSWORD=zzzz \
  -- java -jar /абсолютный/путь/к/target/mcp-yandex-1.0.jar
```

Или вручную в `.mcp.json`:

```json
{
  "mcpServers": {
    "yandex": {
      "command": "java",
      "args": [
        "-jar",
        "/абсолютный/путь/к/target/mcp-yandex-1.0.jar"
      ],
      "env": {
        "YANDEX_EMAIL": "user@company.ru",
        "YANDEX_MAIL_PASSWORD": "xxxx",
        "YANDEX_CALDAV_PASSWORD": "yyyy",
        "YANDEX_DISK_PASSWORD": "zzzz"
      }
    }
  }
}
```

## Примечания

- Хосты: `imap.yandex.ru:993`, `smtp.yandex.ru:465`, `caldav.yandex.ru`, `webdav.yandex.ru`.
- Основной календарь — `/calendars/{email}/events-default/`; другие календари смотрите через `calendar_list_calendars`.
- Ссылка на встречу ищется в свойствах `X-TELEMOST-CONFERENCE`, `CONFERENCE`, `URL` и в тексте описания.
- Времена событий выводятся в системном часовом поясе; при создании встречи время без оффсета трактуется как локальное.
- Пути на Диске указываются от корня, например `/Документы/отчёт.xlsx`; кириллица и пробелы поддерживаются.
