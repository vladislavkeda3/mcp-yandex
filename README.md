# mcp-yandex

MCP-сервер для Яндекс.Почты (IMAP/SMTP) и Яндекс.Календаря (CalDAV). Работает с личными аккаунтами и Яндекс 360 для
бизнеса.

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

## Сборка

### Docker (рекомендуется)

```bash
docker build -t yandex-mcp:1.0 .
```

Локальные JDK и Maven не нужны — сборка целиком проходит внутри multi-stage образа.
В образе задан часовой пояс `Europe/Moscow` (переопределяется переменной `TZ`),
вложения сохраняются в `/attachments` — примонтируйте туда каталог хоста.

### Альтернатива: локальный jar

```bash
mvn package
# → target/yandex-mcp-1.0.jar (fat jar)
```

Требуется JDK 21+.

## Настройка доступа

Аккаунт с двухфакторной аутентификацией: создайте **пароли приложений** на
[id.yandex.ru/security/app-passwords](https://id.yandex.ru/security/app-passwords):

1. Пароль типа **«Почта»** → `YANDEX_MAIL_PASSWORD`
2. Пароль типа **«Календарь»** → `YANDEX_CALDAV_PASSWORD`

Либо используйте OAuth-токен (`YANDEX_OAUTH_TOKEN`) — он применяется и к почте (XOAUTH2), и к календарю.
Для Яндекс 360 может потребоваться, чтобы администратор организации разрешил IMAP и пароли приложений.

### Переменные окружения

| Переменная                   | Обязательна | Описание                                                        |
|------------------------------|-------------|-----------------------------------------------------------------|
| `YANDEX_EMAIL`               | да          | Адрес почты (логин), например `user@company.ru`                 |
| `YANDEX_MAIL_PASSWORD`       | *           | Пароль приложения «Почта»                                       |
| `YANDEX_CALDAV_PASSWORD`     | *           | Пароль приложения «Календарь»                                   |
| `YANDEX_OAUTH_TOKEN`         | *           | OAuth-токен (вместо паролей приложений)                         |
| `YANDEX_MCP_ATTACHMENTS_DIR` | нет         | Куда сохранять вложения (по умолчанию `~/Downloads/yandex-mcp`) |

\* нужен OAuth-токен **или** хотя бы один пароль приложения; инструменты почты/календаря
регистрируются только при наличии соответствующих учётных данных.

## Подключение к Claude Code

### Через Docker (рекомендуется)

```bash
claude mcp add yandex \
  --env YANDEX_EMAIL=user@company.ru \
  --env YANDEX_MAIL_PASSWORD=xxxx \
  --env YANDEX_CALDAV_PASSWORD=yyyy \
  -- docker run -i --rm \
     -e YANDEX_EMAIL -e YANDEX_MAIL_PASSWORD -e YANDEX_CALDAV_PASSWORD \
     -v "$HOME/Downloads/yandex-mcp:/attachments" \
     yandex-mcp:1.0
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
        "-v", "/Users/вы/Downloads/yandex-mcp:/attachments",
        "yandex-mcp:1.0"
      ]
    }
  }
}
```

Флаг `-i` обязателен (обмен идёт через stdin/stdout), `-t` не нужен. Том `-v` нужен только
для `mail_save_attachment` — без него вложения останутся внутри контейнера.

### Через локальный jar

```bash
claude mcp add yandex \
  --env YANDEX_EMAIL=user@company.ru \
  --env YANDEX_MAIL_PASSWORD=xxxx \
  --env YANDEX_CALDAV_PASSWORD=yyyy \
  -- java -jar /абсолютный/путь/к/target/yandex-mcp-1.0.jar
```

Или вручную в `.mcp.json`:

```json
{
  "mcpServers": {
    "yandex": {
      "command": "java",
      "args": [
        "-jar",
        "/абсолютный/путь/к/target/yandex-mcp-1.0.jar"
      ],
      "env": {
        "YANDEX_EMAIL": "user@company.ru",
        "YANDEX_MAIL_PASSWORD": "xxxx",
        "YANDEX_CALDAV_PASSWORD": "yyyy"
      }
    }
  }
}
```

## Примечания

- Хосты: `imap.yandex.ru:993`, `smtp.yandex.ru:465`, `caldav.yandex.ru`.
- Основной календарь — `/calendars/{email}/events-default/`; другие календари смотрите через `calendar_list_calendars`.
- Ссылка на встречу ищется в свойствах `X-TELEMOST-CONFERENCE`, `CONFERENCE`, `URL` и в тексте описания.
- Времена событий выводятся в системном часовом поясе; при создании встречи время без оффсета трактуется как локальное.
