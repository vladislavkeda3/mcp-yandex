package ru.glowbyte.streaming.core;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import biweekly.property.Attendee;
import biweekly.property.Organizer;
import biweekly.property.RawProperty;
import biweekly.util.com.google.ical.compat.javautil.DateIterator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Работа с Яндекс.Календарём по протоколу CalDAV (caldav.yandex.ru). */
public class CaldavService {

    private static final String BASE_URL = "https://caldav.yandex.ru";
    private static final DateTimeFormatter CALDAV_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter OUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private final Config config;
    private final HttpClient http;

    public CaldavService(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ---------- публичные операции ----------

    public List<Map<String, Object>> listCalendars() throws Exception {
        String body = """
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><d:displayname/><d:resourcetype/></d:prop>
                </d:propfind>
                """;
        HttpResponse<String> response = request("PROPFIND", calendarHome(), body, "1");
        Document doc = parseXml(response.body());
        List<Map<String, Object>> calendars = new ArrayList<>();
        for (Element resp : elements(doc, "response")) {
            Element resourceType = firstElement(resp, "resourcetype");
            if (resourceType == null || resourceType.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar").getLength() == 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", text(firstElement(resp, "href")));
            m.put("name", text(firstElement(resp, "displayname")));
            calendars.add(m);
        }
        return calendars;
    }

    public Map<String, Object> listEvents(String date, String calendarPath) throws Exception {
        LocalDate day = LocalDate.parse(date);
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime dayStart = day.atStartOfDay(zone);
        ZonedDateTime dayEnd = dayStart.plusDays(1);
        String path = calendarPath != null ? calendarPath : defaultCalendarPath();

        String body = """
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><d:getetag/><c:calendar-data/></d:prop>
                  <c:filter>
                    <c:comp-filter name="VCALENDAR">
                      <c:comp-filter name="VEVENT">
                        <c:time-range start="%s" end="%s"/>
                      </c:comp-filter>
                    </c:comp-filter>
                  </c:filter>
                </c:calendar-query>
                """.formatted(utc(dayStart), utc(dayEnd));

        HttpResponse<String> response = request("REPORT", path, body, "1");
        Document doc = parseXml(response.body());

        List<Map<String, Object>> events = new ArrayList<>();
        for (Element resp : elements(doc, "response")) {
            String href = text(firstElement(resp, "href"));
            Element data = firstElement(resp, "calendar-data");
            if (data == null) {
                continue;
            }
            for (ICalendar ical : Biweekly.parse(data.getTextContent()).all()) {
                for (VEvent event : ical.getEvents()) {
                    Map<String, Object> occurrence = occurrenceWithinDay(event, dayStart, dayEnd, zone);
                    if (occurrence == null) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("href", href);
                    m.put("uid", event.getUid() != null ? event.getUid().getValue() : null);
                    m.put("summary", event.getSummary() != null ? event.getSummary().getValue() : null);
                    m.putAll(occurrence);
                    m.put("location", event.getLocation() != null ? event.getLocation().getValue() : null);
                    m.put("recurring", event.getRecurrenceRule() != null);
                    events.add(m);
                }
            }
        }
        events.sort(Comparator.comparing(e -> String.valueOf(e.get("start"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date);
        result.put("calendar", path);
        result.put("events", events);
        return result;
    }

    public Map<String, Object> getEvent(String href) throws Exception {
        HttpResponse<String> response = request("GET", href, null, null);
        List<ICalendar> icals = Biweekly.parse(response.body()).all();
        VEvent event = icals.stream()
                .flatMap(i -> i.getEvents().stream())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("По адресу " + href + " не найдено событий"));

        ZoneId zone = ZoneId.systemDefault();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("href", href);
        m.put("uid", event.getUid() != null ? event.getUid().getValue() : null);
        m.put("summary", event.getSummary() != null ? event.getSummary().getValue() : null);
        Date start = event.getDateStart() != null ? event.getDateStart().getValue() : null;
        Date end = event.getDateEnd() != null ? event.getDateEnd().getValue() : null;
        m.put("start", start != null ? OUT_FMT.format(start.toInstant().atZone(zone)) : null);
        m.put("end", end != null ? OUT_FMT.format(end.toInstant().atZone(zone)) : null);
        m.put("description", event.getDescription() != null ? event.getDescription().getValue() : null);
        m.put("location", event.getLocation() != null ? event.getLocation().getValue() : null);

        Organizer organizer = event.getOrganizer();
        if (organizer != null) {
            m.put("organizer", formatPerson(organizer.getCommonName(), organizer.getEmail(), organizer.getUri()));
        }
        List<Map<String, Object>> attendees = new ArrayList<>();
        for (Attendee a : event.getAttendees()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("name", a.getCommonName());
            am.put("email", a.getEmail() != null ? a.getEmail() : cleanMailto(a.getUri()));
            am.put("status", a.getParticipationStatus() != null ? a.getParticipationStatus().getValue() : null);
            attendees.add(am);
        }
        m.put("attendees", attendees);

        m.put("meeting_url", extractMeetingUrl(event));

        List<String> attachments = new ArrayList<>();
        event.getAttachments().forEach(att -> {
            if (att.getUri() != null) {
                attachments.add(att.getUri());
            }
        });
        m.put("attachments", attachments);
        return m;
    }

    public Map<String, Object> createEvent(String summary, String startIso, String endIso, String description,
                                           String location, List<String> attendeeEmails, String calendarPath)
            throws Exception {
        ZonedDateTime start = parseDateTime(startIso);
        ZonedDateTime end = parseDateTime(endIso);
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Время окончания должно быть позже времени начала");
        }

        String uid = UUID.randomUUID().toString();
        ICalendar ical = new ICalendar();
        ical.setProductId("-//yandex-mcp//RU");
        VEvent event = new VEvent();
        event.setUid(uid);
        event.setSummary(summary);
        event.setDateStart(Date.from(start.toInstant()), true);
        event.setDateEnd(Date.from(end.toInstant()), true);
        if (description != null && !description.isBlank()) {
            event.setDescription(description);
        }
        if (location != null && !location.isBlank()) {
            event.setLocation(location);
        }
        if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
            Organizer organizer = new Organizer(null, config.email());
            event.setOrganizer(organizer);
            for (String email : attendeeEmails) {
                Attendee attendee = new Attendee(null, email.trim());
                attendee.setRsvp(true);
                event.addAttendee(attendee);
            }
        }
        ical.addEvent(event);
        String ics = Biweekly.write(ical).go();

        String path = (calendarPath != null ? calendarPath : defaultCalendarPath());
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        String eventHref = path + uid + ".ics";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + eventHref))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authHeader())
                .header("Content-Type", "text/calendar; charset=utf-8")
                .header("If-None-Match", "*")
                .PUT(HttpRequest.BodyPublishers.ofString(ics, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("CalDAV PUT вернул " + response.statusCode() + ": " + response.body());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "created");
        result.put("uid", uid);
        result.put("href", eventHref);
        result.put("summary", summary);
        result.put("start", OUT_FMT.format(start));
        result.put("end", OUT_FMT.format(end));
        result.put("attendees", attendeeEmails != null ? attendeeEmails : List.of());
        return result;
    }

    // ---------- внутреннее ----------

    private String calendarHome() {
        return "/calendars/" + config.email() + "/";
    }

    private String defaultCalendarPath() {
        return calendarHome() + "events-default/";
    }

    private String authHeader() {
        if (config.useOauth()) {
            return "OAuth " + config.oauthToken();
        }
        String cred = config.email() + ":" + config.caldavPassword();
        return "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> request(String method, String path, String body, String depth) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authHeader());
        if (depth != null) {
            builder.header("Depth", depth);
        }
        if (body != null) {
            builder.header("Content-Type", "application/xml; charset=utf-8");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalStateException("CalDAV: доступ запрещён (" + response.statusCode()
                    + "). Проверьте пароль приложения типа «Календарь» или OAuth-токен");
        }
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("CalDAV " + method + " " + path + " вернул "
                    + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    /**
     * Возвращает start/end/all_day для вхождения события в заданный день
     * (с учётом повторяющихся событий) или null, если событие в день не попадает.
     */
    private Map<String, Object> occurrenceWithinDay(VEvent event, ZonedDateTime dayStart, ZonedDateTime dayEnd, ZoneId zone) {
        if (event.getDateStart() == null) {
            return null;
        }
        Date startValue = event.getDateStart().getValue();
        boolean allDay = !event.getDateStart().getValue().hasTime();
        long durationMs;
        if (event.getDateEnd() != null) {
            durationMs = event.getDateEnd().getValue().getTime() - startValue.getTime();
        } else if (event.getDuration() != null) {
            durationMs = event.getDuration().getValue().toMillis();
        } else {
            durationMs = allDay ? Duration.ofDays(1).toMillis() : 0;
        }

        DateIterator it = event.getDateIterator(TimeZone.getTimeZone(zone));
        it.advanceTo(new Date(dayStart.toInstant().toEpochMilli() - durationMs));
        while (it.hasNext()) {
            Date occStart = it.next();
            if (occStart.getTime() >= dayEnd.toInstant().toEpochMilli()) {
                return null;
            }
            long occEnd = occStart.getTime() + durationMs;
            if (occEnd > dayStart.toInstant().toEpochMilli()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("start", OUT_FMT.format(occStart.toInstant().atZone(zone)));
                m.put("end", OUT_FMT.format(new Date(occEnd).toInstant().atZone(zone)));
                m.put("all_day", allDay);
                return m;
            }
        }
        return null;
    }

    private static String extractMeetingUrl(VEvent event) {
        RawProperty telemost = event.getExperimentalProperty("X-TELEMOST-CONFERENCE");
        if (telemost != null && telemost.getValue() != null) {
            return telemost.getValue();
        }
        RawProperty conference = event.getExperimentalProperty("CONFERENCE");
        if (conference != null && conference.getValue() != null) {
            return conference.getValue();
        }
        if (event.getUrl() != null && event.getUrl().getValue() != null && !event.getUrl().getValue().isBlank()) {
            return event.getUrl().getValue();
        }
        String description = event.getDescription() != null ? event.getDescription().getValue() : null;
        if (description != null) {
            Matcher matcher = URL_PATTERN.matcher(description);
            while (matcher.find()) {
                String url = matcher.group();
                if (url.contains("telemost") || url.contains("zoom.") || url.contains("meet.")) {
                    return url;
                }
            }
        }
        return null;
    }

    private static String formatPerson(String name, String email, String uri) {
        String mail = email != null ? email : cleanMailto(uri);
        if (name != null && mail != null) {
            return name + " <" + mail + ">";
        }
        return name != null ? name : mail;
    }

    private static String cleanMailto(String uri) {
        if (uri == null) {
            return null;
        }
        return uri.toLowerCase().startsWith("mailto:") ? uri.substring(7) : uri;
    }

    static ZonedDateTime parseDateTime(String iso) {
        try {
            return OffsetDateTime.parse(iso).toZonedDateTime();
        } catch (DateTimeParseException ignored) {
            // не ISO с оффсетом — пробуем локальное время
        }
        try {
            return LocalDateTime.parse(iso).atZone(ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Не удалось разобрать дату-время «" + iso + "». Ожидается формат 2026-07-08T15:00 или с оффсетом 2026-07-08T15:00+03:00");
        }
    }

    private static String utc(ZonedDateTime dt) {
        return CALDAV_UTC.format(dt.withZoneSameInstant(ZoneOffset.UTC));
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static List<Element> elements(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("DAV:", localName);
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add((Element) nodes.item(i));
        }
        return result;
    }

    private static Element firstElement(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("DAV:", localName);
        if (nodes.getLength() == 0) {
            nodes = parent.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", localName);
        }
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private static String text(Element element) {
        return element != null ? element.getTextContent().trim() : null;
    }
}
