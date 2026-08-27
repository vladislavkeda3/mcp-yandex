package ru.glowbyte.streaming.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Работа с Яндекс.Диском по протоколу WebDAV (webdav.yandex.ru). */
public class DiskService {

    private static final String BASE_URL = "https://webdav.yandex.ru";
    private static final DateTimeFormatter OUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Config config;
    private final HttpClient http;

    public DiskService(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ---------- публичные операции ----------

    public Map<String, Object> list(String path) throws Exception {
        String normalized = normalize(path);
        HttpRequest request = builder(normalized)
                .header("Depth", "1")
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        check(response, "PROPFIND", normalized);

        Document doc = parseXml(response.body());
        List<Map<String, Object>> items = new ArrayList<>();
        NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Element resp = (Element) responses.item(i);
            String href = text(resp, "href");
            String decodedHref = URLDecoder.decode(Objects.requireNonNull(href), StandardCharsets.UTF_8);
            // первый элемент multistatus — сама запрошенная папка, пропускаем
            if (stripSlash(decodedHref).equals(stripSlash(normalized))) {
                continue;
            }
            boolean isDir = resp.getElementsByTagNameNS("DAV:", "collection").getLength() > 0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", lastSegment(decodedHref));
            m.put("path", stripSlash(decodedHref) + (isDir ? "/" : ""));
            m.put("type", isDir ? "dir" : "file");
            if (!isDir) {
                String size = text(resp, "getcontentlength");
                m.put("size_bytes", size != null ? Long.parseLong(size) : null);
            }
            String modified = text(resp, "getlastmodified");
            if (modified != null) {
                try {
                    m.put("modified", OUT_FMT.format(
                            ZonedDateTime.parse(modified, DateTimeFormatter.RFC_1123_DATE_TIME)
                                    .withZoneSameInstant(ZoneId.systemDefault())));
                } catch (Exception ignored) {
                    m.put("modified", modified);
                }
            }
            items.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", normalized);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> download(String remotePath, Path targetDir) throws Exception {
        String normalized = normalize(remotePath);
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(lastSegment(normalized).replaceAll("[/\\\\]", "_"));

        HttpRequest request = builder(normalized).GET().build();
        HttpResponse<java.io.InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("WebDAV GET " + normalized + " вернул " + response.statusCode());
        }
        try (var in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved_to", target.toAbsolutePath().toString());
        result.put("size_bytes", Files.size(target));
        return result;
    }

    public Map<String, Object> upload(String localPath, String remotePath) throws Exception {
        Path source = Paths.get(localPath);
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Локальный файл не найден: " + localPath);
        }
        String normalized = normalize(remotePath);
        if (normalized.endsWith("/")) {
            normalized = normalized + source.getFileName();
        }
        HttpRequest request = builder(normalized)
                .PUT(HttpRequest.BodyPublishers.ofFile(source))
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        check(response, "PUT", normalized);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "uploaded");
        result.put("remote_path", normalized);
        result.put("size_bytes", Files.size(source));
        return result;
    }

    public Map<String, Object> mkdir(String path) throws Exception {
        String normalized = normalize(path);
        HttpRequest request = builder(normalized)
                .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 405) {
            throw new IllegalStateException("Папка уже существует: " + normalized);
        }
        check(response, "MKCOL", normalized);
        return Map.of("status", "created", "path", normalized);
    }

    public Map<String, Object> move(String fromPath, String toPath, boolean overwrite) throws Exception {
        String from = normalize(fromPath);
        String to = normalize(toPath);
        HttpRequest request = builder(from)
                .header("Destination", BASE_URL + encodePath(to))
                .header("Overwrite", overwrite ? "T" : "F")
                .method("MOVE", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 412) {
            throw new IllegalStateException("Целевой путь уже существует: " + to
                    + " (передайте overwrite=true для перезаписи)");
        }
        check(response, "MOVE", from);
        return Map.of("status", "moved", "from", from, "to", to);
    }

    public Map<String, Object> delete(String path) throws Exception {
        String normalized = normalize(path);
        HttpRequest request = builder(normalized)
                .DELETE()
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        check(response, "DELETE", normalized);
        return Map.of("status", "deleted", "path", normalized);
    }

    // ---------- внутреннее ----------

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + encodePath(path)))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", authHeader())
                .header("Accept", "*/*");
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        HttpResponse<T> response = http.send(request, handler);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalStateException("Яндекс.Диск: доступ запрещён (" + response.statusCode()
                    + "). Проверьте пароль приложения типа «Файлы» или OAuth-токен");
        }
        return response;
    }

    private static void check(HttpResponse<String> response, String method, String path) {
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("WebDAV " + method + " " + path + " вернул "
                    + response.statusCode() + (response.body() != null && !response.body().isBlank()
                    ? ": " + response.body() : ""));
        }
    }

    private String authHeader() {
        if (config.useOauth()) {
            return "OAuth " + config.oauthToken();
        }
        String cred = config.email() + ":" + config.diskPassword();
        return "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
    }

    /** Приводит путь к виду /a/b/c (ведущий слэш, без дублей). */
    private static String normalize(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "/";
        }
        String p = path.trim().replaceAll("/{2,}", "/");
        return p.startsWith("/") ? p : "/" + p;
    }

    /** Кодирует каждый сегмент пути (кириллица, пробелы), сохраняя слэши. */
    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            sb.append('/').append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        if (sb.isEmpty()) {
            return "/";
        }
        if (path.endsWith("/")) {
            sb.append('/');
        }
        return sb.toString();
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String lastSegment(String path) {
        String p = stripSlash(path);
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    private static String text(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("DAV:", localName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
}
