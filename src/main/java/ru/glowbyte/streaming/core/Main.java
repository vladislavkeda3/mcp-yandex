package ru.glowbyte.streaming.core;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.apache.catalina.Context;
import org.apache.catalina.filters.SetCharacterEncodingFilter;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import tools.jackson.databind.json.JsonMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** MCP-сервер для Яндекс.Почты (IMAP/SMTP), Яндекс.Календаря (CalDAV) и Яндекс.Диска (WebDAV). */
public class Main {

    public static void main(String[] args) throws Exception {
        // Логи только в stderr: stdout занят JSON-RPC
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");

        Config config = Config.fromEnv();
        McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

        List<SyncToolSpecification> tools = new ArrayList<>();
        if (config.mailConfigured()) {
            registerMailTools(tools, mapper, new MailService(config), config);
        }
        if (config.caldavConfigured()) {
            registerCalendarTools(tools, mapper, new CaldavService(config));
        }
        if (config.diskConfigured()) {
            registerDiskTools(tools, mapper, new DiskService(config), config);
        }

        // Транспорт: stdio (по умолчанию, для docker run -i) или http
        // (постоянный сервер для многих клиентов; настраивается через
        // YANDEX_MCP_TRANSPORT=http, YANDEX_MCP_HTTP_PORT, YANDEX_MCP_HTTP_ENDPOINT)
        String transport = System.getenv().getOrDefault("YANDEX_MCP_TRANSPORT", "stdio").trim().toLowerCase();
        if (transport.equals("http")) {
            startHttp(config, mapper, tools);
            return;
        }

        // Следим за EOF на stdin: когда клиент отключается, процесс должен завершиться
        // (важно для docker run -i --rm)
        CountDownLatch stdinClosed = new CountDownLatch(1);
        InputStream stdin = new FilterInputStream(System.in) {
            @Override
            public int read() throws IOException {
                int r = super.read();
                if (r == -1) {
                    stdinClosed.countDown();
                }
                return r;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int r = super.read(b, off, len);
                if (r == -1) {
                    stdinClosed.countDown();
                }
                return r;
            }
        };

        McpServer.sync(new StdioServerTransportProvider(mapper, stdin, System.out))
                .serverInfo("yandex-mcp", "1.1.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(tools)
                .build();

        stdinClosed.await();
        // небольшая пауза, чтобы транспорт дописал ответы в stdout
        Thread.sleep(500);
        System.exit(0);
    }

    /** Streamable HTTP: постоянный сервер на embedded Tomcat, обслуживает много клиентов одновременно. */
    private static void startHttp(Config config, McpJsonMapper mapper, List<SyncToolSpecification> tools) throws Exception {
        String endpoint = System.getenv().getOrDefault("YANDEX_MCP_HTTP_ENDPOINT", "/mcp").trim();
        int port = Integer.parseInt(System.getenv().getOrDefault("YANDEX_MCP_HTTP_PORT", "8081").trim());

        HttpServletStreamableServerTransportProvider transportProvider =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(mapper)
                        .mcpEndpoint(endpoint)
                        .build();

        McpServer.sync(transportProvider)
                .serverInfo("yandex-mcp", "1.1.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(tools)
                .build();

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(java.nio.file.Files.createTempDirectory("yandex-mcp-tomcat").toString());
        tomcat.setPort(port);
        tomcat.getConnector(); // без этого connector не добавляется в service и порт никто не слушает
        Context ctx = tomcat.addContext("", null);

        // Клиенты обычно присылают Content-Type: application/json без charset, и сервлет
        // по умолчанию читает тело как ISO-8859-1. Тогда кириллица в аргументах (пути Диска,
        // имена папок почты) превращается в «ÐÐ°Ð³ÑÑÐ·ÐºÐ¸» и запросы уходят на несуществующие
        // пути (404). JSON по стандарту всегда UTF-8, поэтому charset клиента игнорируем.
        FilterDef utf8Body = new FilterDef();
        utf8Body.setFilterName("utf8-body");
        utf8Body.setFilterClass(SetCharacterEncodingFilter.class.getName());
        utf8Body.addInitParameter("encoding", "UTF-8");
        utf8Body.addInitParameter("ignore", "true");
        ctx.addFilterDef(utf8Body);

        FilterMap utf8Mapping = new FilterMap();
        utf8Mapping.setFilterName("utf8-body");
        utf8Mapping.addURLPattern("/*");
        ctx.addFilterMap(utf8Mapping);

        Tomcat.addServlet(ctx, "mcp", transportProvider).setAsyncSupported(true);
        ctx.addServletMappingDecoded(endpoint, "mcp");
        tomcat.start();
        System.err.println("yandex-mcp: streamable HTTP на " + endpoint + ", порт " + port);
        tomcat.getServer().await();
    }

    // ---------- почта ----------

    private static void registerMailTools(List<SyncToolSpecification> tools, McpJsonMapper mapper,
                                          MailService mail, Config config) {
        tools.add(tool(mapper, "mail_list_folders",
                "Список папок Яндекс.Почты с количеством писем (всего и непрочитанных)",
                """
                {"type":"object","properties":{}}
                """,
                arguments -> mail.listFolders()));

        tools.add(tool(mapper, "mail_list_messages",
                "Список писем в папке Яндекс.Почты, от новых к старым. Возвращает UID, отправителя, тему, дату",
                """
                {"type":"object","properties":{
                  "folder":{"type":"string","description":"Имя папки, по умолчанию INBOX"},
                  "limit":{"type":"integer","description":"Сколько писем вернуть, по умолчанию 20"},
                  "offset":{"type":"integer","description":"Сколько новейших писем пропустить, по умолчанию 0"},
                  "unread_only":{"type":"boolean","description":"Только непрочитанные, по умолчанию false"}
                }}
                """,
                arguments -> mail.listMessages(
                        str(arguments, "folder", "INBOX"),
                        intArg(arguments, "limit", 20),
                        intArg(arguments, "offset", 0),
                        boolArg(arguments, "unread_only"))));

        tools.add(tool(mapper, "mail_get_message",
                "Полный текст письма по UID: заголовки, тело и список вложений (индексы для mail_save_attachment)",
                """
                {"type":"object","properties":{
                  "folder":{"type":"string","description":"Имя папки, по умолчанию INBOX"},
                  "uid":{"type":"integer","description":"UID письма из mail_list_messages"}
                },"required":["uid"]}
                """,
                arguments -> mail.getMessage(str(arguments, "folder", "INBOX"), longArg(arguments, "uid"))));

        tools.add(tool(mapper, "mail_save_attachment",
                "Сохраняет вложение письма на диск и возвращает путь к файлу",
                """
                {"type":"object","properties":{
                  "folder":{"type":"string","description":"Имя папки, по умолчанию INBOX"},
                  "uid":{"type":"integer","description":"UID письма"},
                  "attachment_index":{"type":"integer","description":"Индекс вложения из mail_get_message"},
                  "target_dir":{"type":"string","description":"Каталог для сохранения (по умолчанию из конфигурации)"}
                },"required":["uid","attachment_index"]}
                """,
                arguments -> {
                    String dir = str(arguments, "target_dir", null);
                    Path target = dir != null ? Paths.get(dir) : config.attachmentsDir();
                    return mail.saveAttachment(
                            str(arguments, "folder", "INBOX"),
                            longArg(arguments, "uid"),
                            intArg(arguments, "attachment_index", 0),
                            target);
                }));

        tools.add(tool(mapper, "mail_send",
                "Отправляет письмо с адреса " + config.email() + " через SMTP Яндекса",
                """
                {"type":"object","properties":{
                  "to":{"type":"array","items":{"type":"string"},"description":"Получатели"},
                  "cc":{"type":"array","items":{"type":"string"},"description":"Копия"},
                  "bcc":{"type":"array","items":{"type":"string"},"description":"Скрытая копия"},
                  "subject":{"type":"string","description":"Тема письма"},
                  "body":{"type":"string","description":"Текст письма (plain text)"}
                },"required":["to","subject","body"]}
                """,
                arguments -> mail.send(
                        strList(arguments, "to"),
                        strList(arguments, "cc"),
                        strList(arguments, "bcc"),
                        str(arguments, "subject", ""),
                        str(arguments, "body", ""))));
    }

    // ---------- календарь ----------

    private static void registerCalendarTools(List<SyncToolSpecification> tools, McpJsonMapper mapper,
                                              CaldavService caldav) {
        tools.add(tool(mapper, "calendar_list_calendars",
                "Список календарей пользователя в Яндекс.Календаре (путь и название)",
                """
                {"type":"object","properties":{}}
                """,
                arguments -> caldav.listCalendars()));

        tools.add(tool(mapper, "calendar_list_events",
                "События Яндекс.Календаря на конкретный день (учитывает повторяющиеся события)",
                """
                {"type":"object","properties":{
                  "date":{"type":"string","description":"День в формате YYYY-MM-DD"},
                  "calendar":{"type":"string","description":"Путь календаря из calendar_list_calendars; по умолчанию основной"}
                },"required":["date"]}
                """,
                arguments -> caldav.listEvents(str(arguments, "date", null), str(arguments, "calendar", null))));

        tools.add(tool(mapper, "calendar_get_event",
                "Детали события: время, участники и их статусы, описание, ссылка на встречу (Телемост и т.п.), вложения",
                """
                {"type":"object","properties":{
                  "href":{"type":"string","description":"Путь события (href) из calendar_list_events"}
                },"required":["href"]}
                """,
                arguments -> caldav.getEvent(str(arguments, "href", null))));

        tools.add(tool(mapper, "calendar_create_event",
                "Создаёт встречу в Яндекс.Календаре; участникам отправляются приглашения",
                """
                {"type":"object","properties":{
                  "summary":{"type":"string","description":"Название встречи"},
                  "start":{"type":"string","description":"Начало: 2026-07-08T15:00 (локальное время) или с оффсетом 2026-07-08T15:00+03:00"},
                  "end":{"type":"string","description":"Окончание, тот же формат"},
                  "description":{"type":"string","description":"Описание"},
                  "location":{"type":"string","description":"Место"},
                  "attendees":{"type":"array","items":{"type":"string"},"description":"Email-адреса участников"},
                  "calendar":{"type":"string","description":"Путь календаря; по умолчанию основной"}
                },"required":["summary","start","end"]}
                """,
                arguments -> caldav.createEvent(
                        str(arguments, "summary", null),
                        str(arguments, "start", null),
                        str(arguments, "end", null),
                        str(arguments, "description", null),
                        str(arguments, "location", null),
                        strList(arguments, "attendees"),
                        str(arguments, "calendar", null))));
    }

    // ---------- диск ----------

    private static void registerDiskTools(List<SyncToolSpecification> tools, McpJsonMapper mapper,
                                          DiskService disk, Config config) {
        tools.add(tool(mapper, "disk_list",
                "Содержимое папки Яндекс.Диска: имена, типы (файл/папка), размеры, даты изменения",
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Путь к папке, по умолчанию корень «/»"}
                }}
                """,
                arguments -> disk.list(str(arguments, "path", "/"))));

        tools.add(tool(mapper, "disk_download",
                "Скачивает файл с Яндекс.Диска на локальный диск и возвращает путь",
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Путь к файлу на Диске"},
                  "target_dir":{"type":"string","description":"Локальный каталог (по умолчанию из конфигурации)"}
                },"required":["path"]}
                """,
                arguments -> {
                    String dir = str(arguments, "target_dir", null);
                    Path target = dir != null ? Paths.get(dir) : config.attachmentsDir();
                    return disk.download(str(arguments, "path", null), target);
                }));

        tools.add(tool(mapper, "disk_upload",
                "Загружает локальный файл на Яндекс.Диск",
                """
                {"type":"object","properties":{
                  "local_path":{"type":"string","description":"Путь к локальному файлу"},
                  "remote_path":{"type":"string","description":"Куда положить на Диске: путь к файлу или папке (с завершающим «/»)"}
                },"required":["local_path","remote_path"]}
                """,
                arguments -> disk.upload(str(arguments, "local_path", null), str(arguments, "remote_path", null))));

        tools.add(tool(mapper, "disk_mkdir",
                "Создаёт папку на Яндекс.Диске",
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Путь новой папки"}
                },"required":["path"]}
                """,
                arguments -> disk.mkdir(str(arguments, "path", null))));

        tools.add(tool(mapper, "disk_move",
                "Перемещает или переименовывает файл/папку на Яндекс.Диске",
                """
                {"type":"object","properties":{
                  "from":{"type":"string","description":"Текущий путь"},
                  "to":{"type":"string","description":"Новый путь"},
                  "overwrite":{"type":"boolean","description":"Перезаписать, если целевой путь занят (по умолчанию false)"}
                },"required":["from","to"]}
                """,
                arguments -> disk.move(str(arguments, "from", null), str(arguments, "to", null),
                        boolArg(arguments, "overwrite"))));

        tools.add(tool(mapper, "disk_delete",
                "Удаляет файл или папку на Яндекс.Диске (папку — вместе с содержимым). Действие необратимо через API",
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Путь к файлу или папке"}
                },"required":["path"]}
                """,
                arguments -> disk.delete(str(arguments, "path", null))));
    }

    // ---------- инфраструктура ----------

    @FunctionalInterface
    private interface ToolHandler {
        Object handle(Map<String, Object> arguments) throws Exception;
    }

    private static final TypeRef<HashMap<String, Object>> MAP_TYPE_REF = new TypeRef<>() {
    };

    private static SyncToolSpecification tool(McpJsonMapper mapper, String name, String description,
                                              String schema, ToolHandler handler) {
        final Tool tool;
        try {
            tool = Tool.builder(name, mapper.readValue(schema, MAP_TYPE_REF))
                    .description(description)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Object result = handler.handle(request.arguments() != null ? request.arguments() : Map.of());
                        return CallToolResult.builder()
                                .addTextContent(mapper.writeValueAsString(result))
                                .build();
                    } catch (Exception e) {
                        String message = e.getMessage() != null ? e.getMessage() : e.toString();
                        return CallToolResult.builder()
                                .isError(true)
                                .addTextContent("Ошибка: " + message)
                                .build();
                    }
                })
                .build();
    }

    private static String str(Map<String, Object> arguments, String key, String defaultValue) {
        Object v = arguments.get(key);
        return v != null && !v.toString().isBlank() ? v.toString() : defaultValue;
    }

    private static int intArg(Map<String, Object> arguments, String key, int defaultValue) {
        Object v = arguments.get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }

    @SuppressWarnings("SameParameterValue")
    private static long longArg(Map<String, Object> arguments, String key) {
        Object v = arguments.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            return Long.parseLong(s.trim());
        }
        throw new IllegalArgumentException("Не задан обязательный числовой параметр «" + key + "»");
    }

    private static boolean boolArg(Map<String, Object> arguments, String key) {
        Object v = arguments.get(key);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }

    private static List<String> strList(Map<String, Object> arguments, String key) {
        Object v = arguments.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(v.toString());
    }
}
