package ru.glowbyte.streaming.core;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Работа с Яндекс.Почтой по IMAP (чтение) и SMTP (отправка). */
public class MailService {

    private static final String IMAP_HOST = "imap.yandex.ru";
    private static final String SMTP_HOST = "smtp.yandex.ru";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Config config;

    public MailService(Config config) {
        this.config = config;
    }

    // ---------- публичные операции ----------

    public List<Map<String, Object>> listFolders() throws MessagingException {
        try (AutoCloseableStore acs = connect()) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Folder f : acs.store.getDefaultFolder().list("*")) {
                if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.getFullName());
                m.put("messages", f.getMessageCount());
                m.put("unread", f.getUnreadMessageCount());
                result.add(m);
            }
            return result;
        }
    }

    public Map<String, Object> listMessages(String folderName, int limit, int offset, boolean unreadOnly)
            throws MessagingException {
        try (AutoCloseableStore acs = connect()) {
            Folder folder = acs.store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            try {
                UIDFolder uidFolder = (UIDFolder) folder;
                int total = folder.getMessageCount();
                List<Map<String, Object>> items = new ArrayList<>();

                Message[] messages;
                if (unreadOnly) {
                    messages = folder.search(new jakarta.mail.search.FlagTerm(new Flags(Flags.Flag.SEEN), false));
                } else {
                    messages = folder.getMessages();
                }
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(FetchProfile.Item.FLAGS);
                fp.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, fp);

                // от новых к старым
                for (int i = messages.length - 1 - offset, taken = 0; i >= 0 && taken < limit; i--, taken++) {
                    Message msg = messages[i];
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("uid", uidFolder.getUID(msg));
                    m.put("from", addresses(msg.getFrom()));
                    m.put("to", addresses(msg.getRecipients(Message.RecipientType.TO)));
                    m.put("subject", msg.getSubject());
                    m.put("date", formatDate(msg.getSentDate()));
                    m.put("seen", msg.isSet(Flags.Flag.SEEN));
                    items.add(m);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("folder", folderName);
                result.put("total", total);
                result.put("returned", items.size());
                result.put("messages", items);
                return result;
            } finally {
                folder.close(false);
            }
        }
    }

    public Map<String, Object> getMessage(String folderName, long uid) throws MessagingException, IOException {
        try (AutoCloseableStore acs = connect()) {
            Folder folder = acs.store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            try {
                Message msg = requireMessage(folder, uid);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("uid", uid);
                m.put("folder", folderName);
                m.put("from", addresses(msg.getFrom()));
                m.put("to", addresses(msg.getRecipients(Message.RecipientType.TO)));
                m.put("cc", addresses(msg.getRecipients(Message.RecipientType.CC)));
                m.put("subject", msg.getSubject());
                m.put("date", formatDate(msg.getSentDate()));
                m.put("seen", msg.isSet(Flags.Flag.SEEN));

                BodyContent body = new BodyContent();
                List<Map<String, Object>> attachments = new ArrayList<>();
                walk(msg, body, attachments, new int[]{0});
                m.put("body", body.best());
                m.put("attachments", attachments);
                return m;
            } finally {
                folder.close(false);
            }
        }
    }

    public Map<String, Object> saveAttachment(String folderName, long uid, int attachmentIndex, Path targetDir)
            throws MessagingException, IOException {
        try (AutoCloseableStore acs = connect()) {
            Folder folder = acs.store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            try {
                Message msg = requireMessage(folder, uid);
                Part part = findAttachment(msg, attachmentIndex, new int[]{0});
                if (part == null) {
                    throw new IllegalArgumentException("Вложение с индексом " + attachmentIndex + " не найдено");
                }
                Files.createDirectories(targetDir);
                String fileName = part.getFileName() != null
                        ? MimeUtility.decodeText(part.getFileName())
                        : "attachment-" + uid + "-" + attachmentIndex;
                Path target = targetDir.resolve(fileName.replaceAll("[/\\\\]", "_"));
                try (InputStream in = part.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("saved_to", target.toAbsolutePath().toString());
                result.put("file_name", fileName);
                result.put("size_bytes", Files.size(target));
                result.put("content_type", baseContentType(part));
                return result;
            } finally {
                folder.close(false);
            }
        }
    }

    public Map<String, Object> send(List<String> to, List<String> cc, List<String> bcc, String subject, String body)
            throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", "465");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        if (config.useOauth()) {
            props.put("mail.smtp.auth.mechanisms", "XOAUTH2");
        }
        Session session = Session.getInstance(props);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(config.email()));
        msg.setRecipients(Message.RecipientType.TO, parse(to));
        if (cc != null && !cc.isEmpty()) {
            msg.setRecipients(Message.RecipientType.CC, parse(cc));
        }
        if (bcc != null && !bcc.isEmpty()) {
            msg.setRecipients(Message.RecipientType.BCC, parse(bcc));
        }
        msg.setSubject(subject, "UTF-8");
        msg.setText(body, "UTF-8");
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(SMTP_HOST, config.email(), mailSecret());
            transport.sendMessage(msg, msg.getAllRecipients());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "sent");
        result.put("to", to);
        result.put("subject", subject);
        return result;
    }

    // ---------- внутреннее ----------

    private String mailSecret() {
        return config.useOauth() ? config.oauthToken() : config.mailPassword();
    }

    private AutoCloseableStore connect() throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.host", IMAP_HOST);
        props.put("mail.imap.port", "993");
        props.put("mail.imap.ssl.enable", "true");
        props.put("mail.imap.connectiontimeout", "15000");
        props.put("mail.imap.timeout", "60000");
        if (config.useOauth()) {
            props.put("mail.imap.auth.mechanisms", "XOAUTH2");
        }
        Session session = Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect(IMAP_HOST, config.email(), mailSecret());
        return new AutoCloseableStore(store);
    }

    private record AutoCloseableStore(Store store) implements AutoCloseable {
        @Override
        public void close() throws MessagingException {
            store.close();
        }
    }

    private static Message requireMessage(Folder folder, long uid) throws MessagingException {
        Message msg = ((UIDFolder) folder).getMessageByUID(uid);
        if (msg == null) {
            throw new IllegalArgumentException("Письмо с UID " + uid + " не найдено в папке " + folder.getFullName());
        }
        return msg;
    }

    /** Собирает текст письма и метаданные вложений, обходя MIME-дерево. */
    private static void walk(Part part, BodyContent body, List<Map<String, Object>> attachments, int[] attachmentCounter)
            throws MessagingException, IOException {
        String disposition = part.getDisposition();
        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
                || (part.getFileName() != null && !part.isMimeType("multipart/*"));
        if (isAttachment) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("index", attachmentCounter[0]++);
            a.put("file_name", part.getFileName() != null ? MimeUtility.decodeText(part.getFileName()) : null);
            a.put("content_type", baseContentType(part));
            a.put("size_bytes", part.getSize());
            attachments.add(a);
            return;
        }
        if (part.isMimeType("text/plain")) {
            body.plain = appendText(body.plain, (String) part.getContent());
        } else if (part.isMimeType("text/html")) {
            body.html = appendText(body.html, (String) part.getContent());
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                walk(mp.getBodyPart(i), body, attachments, attachmentCounter);
            }
        } else if (part.isMimeType("message/rfc822")) {
            walk((Part) part.getContent(), body, attachments, attachmentCounter);
        }
    }

    private static Part findAttachment(Part part, int wantedIndex, int[] counter)
            throws MessagingException, IOException {
        String disposition = part.getDisposition();
        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
                || (part.getFileName() != null && !part.isMimeType("multipart/*"));
        if (isAttachment) {
            return counter[0]++ == wantedIndex ? part : null;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart child = mp.getBodyPart(i);
                Part found = findAttachment(child, wantedIndex, counter);
                if (found != null) {
                    return found;
                }
            }
        } else if (part.isMimeType("message/rfc822")) {
            return findAttachment((Part) part.getContent(), wantedIndex, counter);
        }
        return null;
    }

    private static class BodyContent {
        String plain;
        String html;

        String best() {
            if (plain != null && !plain.isBlank()) {
                return plain;
            }
            return html != null ? stripHtml(html) : "";
        }
    }

    private static String appendText(String current, String addition) {
        if (addition == null) {
            return current;
        }
        return current == null ? addition : current + "\n" + addition;
    }

    static String stripHtml(String html) {
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n")
                .replaceAll("<[^>]+>", "");
        return text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private static String baseContentType(Part part) throws MessagingException {
        String ct = part.getContentType();
        if (ct == null) {
            return null;
        }
        int semi = ct.indexOf(';');
        return (semi > 0 ? ct.substring(0, semi) : ct).trim().toLowerCase();
    }

    private static List<String> addresses(Address[] addrs) {
        if (addrs == null) {
            return List.of();
        }
        return Arrays.stream(addrs).map(a -> {
            if (a instanceof InternetAddress ia) {
                return ia.getPersonal() != null ? ia.getPersonal() + " <" + ia.getAddress() + ">" : ia.getAddress();
            }
            return a.toString();
        }).toList();
    }

    private static Address[] parse(List<String> emails) throws MessagingException {
        List<Address> list = new ArrayList<>();
        for (String e : emails) {
            list.addAll(Arrays.asList(InternetAddress.parse(e)));
        }
        return list.toArray(Address[]::new);
    }

    private static String formatDate(Date date) {
        if (date == null) {
            return null;
        }
        return DATE_FMT.format(date.toInstant().atZone(ZoneId.systemDefault()));
    }
}
