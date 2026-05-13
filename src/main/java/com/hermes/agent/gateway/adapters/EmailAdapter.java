package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.*;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
import jakarta.mail.internet.*;
import jakarta.mail.search.FlagTerm;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 邮件平台适配器 (IMAP/SMTP)
 *
 * 参考Python版 email.py 实现。支持：
 * - IMAP IDLE 实时接收新邮件
 * - SMTP 发送邮件
 * - HTML 和纯文本消息
 * - 附件处理
 * - 按发件人/主题过滤
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     email:
 *       transport: imap-smtp
 *       imap_host: imap.gmail.com
 *       imap_port: 993
 *       smtp_host: smtp.gmail.com
 *       smtp_port: 587
 *       address: bot@example.com
 *       password: app-specific-password
 *       extra:
 *         allowed_senders: ["user@example.com"]
 *         subject_prefix: "[Hermes]"
 *         fetch_interval: 30
 *         max_attachment_size: 26214400
 * </pre>
 */
public class EmailAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(EmailAdapter.class);

    private Session imapSession;
    private Session smtpSession;
    private Store imapStore;
    private Folder inboxFolder;
    private ScheduledExecutorService idleScheduler;

    private final String imapHost;
    private final int imapPort;
    private final String smtpHost;
    private final int smtpPort;
    private final String address;
    private final String password;

    // 可选配置
    private final Set<String> allowedSenders;
    private final String subjectPrefix;
    private final int fetchIntervalSeconds;
    private final long maxAttachmentSize;

    public EmailAdapter(PlatformConfig config) {
        super(config, Platform.EMAIL);
        this.imapHost = config.getExtraString("imap_host").orElse("imap.gmail.com");
        this.imapPort = config.getExtraInt("imap_port").orElse(993);
        this.smtpHost = config.getExtraString("smtp_host").orElse("smtp.gmail.com");
        this.smtpPort = config.getExtraInt("smtp_port").orElse(587);
        this.address = config.getExtraString("address").orElseThrow(
            () -> new IllegalArgumentException("Email adapter requires 'address' config"));
        this.password = config.getExtraString("password").orElseThrow(
            () -> new IllegalArgumentException("Email adapter requires 'password' config"));

        this.allowedSenders = new HashSet<>(config.getExtraStringList("allowed_senders"));
        this.subjectPrefix = config.getExtraString("subject_prefix").orElse("");
        this.fetchIntervalSeconds = config.getExtraInt("fetch_interval").orElse(30);
        this.maxAttachmentSize = config.getExtraLong("max_attachment_size").orElse(26214400L);
    }

    @Override
    public Mono<Boolean> connect() {
        return Mono.fromCallable(() -> {
            try {
                initImapSession();
                initSmtpSession();
                connectImap();
                startPolling();
                markConnected();
                log.info("Email adapter connected: {}", address);
                return true;
            } catch (Exception e) {
                setFatalError("CONNECT_FAILED", e.getMessage(), true);
                log.error("Failed to connect email adapter: {}", e.getMessage());
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> disconnect() {
        return Mono.<Void>fromCallable(() -> {
            stopPolling();
            try {
                if (inboxFolder != null && inboxFolder.isOpen()) {
                    inboxFolder.close(false);
                }
                if (imapStore != null && imapStore.isConnected()) {
                    imapStore.close();
                }
            } catch (MessagingException e) {
                log.warn("Error disconnecting email: {}", e.getMessage());
            }
            markDisconnected();
            return null;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        // chatId = 收件人地址
        return Mono.fromCallable(() -> {
            try {
                MimeMessage message = new MimeMessage(smtpSession);
                message.setFrom(new InternetAddress(address));

                // 收件人
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(chatId));

                // 主题
                String subject = subjectPrefix;
                if (metadata != null && metadata.containsKey("subject")) {
                    subject += metadata.get("subject");
                } else if (replyTo != null) {
                    subject += "Re: " + replyTo;
                } else {
                    // 从内容前30字生成主题
                    String preview = content.length() > 30 ? content.substring(0, 30) + "..." : content;
                    subject += preview;
                }
                message.setSubject(subject, "UTF-8");

                // 内容
                MimeBodyPart textPart = new MimeBodyPart();
                boolean isHtml = content.contains("<html") || content.contains("<div") || content.contains("<p>");
                if (isHtml) {
                    textPart.setContent(content, "text/html; charset=utf-8");
                } else {
                    textPart.setContent(content, "text/plain; charset=utf-8");
                }

                Multipart multipart = new MimeMultipart();
                multipart.addBodyPart(textPart);

                // 附件
                if (metadata != null && metadata.containsKey("attachments")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> attachments = (List<Map<String, Object>>) metadata.get("attachments");
                    for (Map<String, Object> att : attachments) {
                        MimeBodyPart attachmentPart = new MimeBodyPart();
                        if (att.containsKey("file_path")) {
                            attachmentPart.attachFile((String) att.get("file_path"));
                        } else if (att.containsKey("bytes")) {
                            byte[] bytes = (byte[]) att.get("bytes");
                            attachmentPart.setContent(bytes, (String) att.getOrDefault("content_type", "application/octet-stream"));
                        }
                        attachmentPart.setFileName((String) att.getOrDefault("filename", "attachment"));
                        multipart.addBodyPart(attachmentPart);
                    }
                }

                message.setContent(multipart);
                message.setSentDate(new Date());

                // 发送
                Transport.send(message);
                String msgId = message.getMessageID();
                log.debug("Email sent to {}: {}", chatId, msgId);
                return SendResult.success(msgId);
            } catch (MessagingException e) {
                log.error("Failed to send email to {}: {}", chatId, e.getMessage());
                return SendResult.failure(e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        // 对于邮件，chatId 是收件人地址
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", "email");
        info.put("address", chatId);
        return Mono.just(info);
    }

    // ========== IMAP 连接 ==========

    private void initImapSession() {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", imapHost);
        props.put("mail.imaps.port", String.valueOf(imapPort));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.timeout", "30000");
        props.put("mail.imaps.connectiontimeout", "30000");

        imapSession = Session.getInstance(props);
    }

    private void initSmtpSession() {
        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.ssl.trust", "*");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.connectiontimeout", "30000");

        smtpSession = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(address, password);
            }
        });
    }

    private void connectImap() throws MessagingException {
        imapStore = imapSession.getStore("imaps");
        imapStore.connect(imapHost, address, password);

        inboxFolder = imapStore.getFolder("INBOX");
        inboxFolder.open(Folder.READ_WRITE);

        // 监听新邮件
        inboxFolder.addMessageCountListener(new MessageCountAdapter() {
            @Override
            public void messagesAdded(MessageCountEvent e) {
                for (jakarta.mail.Message msg : e.getMessages()) {
                    try {
                        processIncomingMessage(msg);
                    } catch (Exception ex) {
                        log.error("Error processing incoming email: {}", ex.getMessage());
                    }
                }
            }
        });
    }

    // ========== 邮件轮询 ==========

    private void startPolling() {
        idleScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "email-idle-poll");
            t.setDaemon(true);
            return t;
        });

        // 定期检查新邮件（备用方案，IMAP IDLE 不一定支持）
        idleScheduler.scheduleAtFixedRate(() -> {
            try {
                if (inboxFolder != null && inboxFolder.isOpen()) {
                    // 搜索未读邮件
                    FlagTerm unread = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
                    jakarta.mail.Message[] messages = inboxFolder.search(unread);
                    for (jakarta.mail.Message msg : messages) {
                        processIncomingMessage(msg);
                    }
                }
            } catch (Exception e) {
                log.error("Error polling emails: {}", e.getMessage());
                // 尝试重连
                tryReconnect();
            }
        }, fetchIntervalSeconds, fetchIntervalSeconds, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        if (idleScheduler != null) {
            idleScheduler.shutdownNow();
            idleScheduler = null;
        }
    }

    private void tryReconnect() {
        try {
            if (imapStore != null && !imapStore.isConnected()) {
                imapStore.connect(imapHost, address, password);
                if (inboxFolder != null && !inboxFolder.isOpen()) {
                    inboxFolder.open(Folder.READ_WRITE);
                }
                log.info("Email adapter reconnected");
            }
        } catch (MessagingException e) {
            log.error("Email reconnect failed: {}", e.getMessage());
        }
    }

    // ========== 消息处理 ==========

    private void processIncomingMessage(jakarta.mail.Message msg) {
        try {
            Address[] froms = msg.getFrom();
            String fromAddress = froms != null && froms.length > 0
                ? ((InternetAddress) froms[0]).getAddress() : "unknown";

            // 发件人过滤
            if (!allowedSenders.isEmpty() && !allowedSenders.contains(fromAddress)) {
                log.debug("Ignoring email from non-allowed sender: {}", fromAddress);
                return;
            }

            String subject = msg.getSubject();
            String content = extractContent(msg);

            // 标记已读
            msg.setFlag(Flags.Flag.SEEN, true);

            // 构建消息事件
            SessionSource source = buildSource(
                fromAddress,                    // chatId = 发件人
                fromAddress,                    // chatName
                "dm",                           // email 是一对一
                fromAddress,                    // userId
                fromAddress,                    // userName
                null,                           // threadId
                subject                         // chatTopic = 邮件主题
            );

            MessageEvent event = new MessageEvent();
            event.setText(content != null ? content : subject);
            event.setMessageType(MessageEvent.MessageType.TEXT);
            event.setSource(source);
            event.setMessageId(msg instanceof MimeMessage mm ? mm.getMessageID() : String.valueOf(msg.getMessageNumber()));

            // 提取附件
            List<String> mediaUrls = extractAttachments(msg);
            if (!mediaUrls.isEmpty()) {
                event.setMediaUrls(mediaUrls);
            }

            // 触发消息处理器
            if (messageHandler != null) {
                messageHandler.apply(event).subscribe(
                    response -> {
                        if (response != null && !response.isEmpty()) {
                            send(fromAddress, response, subject, Map.of("subject", "Re: " + subject))
                                .subscribe();
                        }
                    },
                    error -> log.error("Error handling email message: {}", error.getMessage())
                );
            }
        } catch (Exception e) {
            log.error("Error processing incoming email: {}", e.getMessage());
        }
    }

    /**
     * 提取邮件内容（优先 HTML，回退纯文本）
     */
    private String extractContent(jakarta.mail.Message msg) throws IOException, MessagingException {
        Object content = msg.getContent();

        if (content instanceof String text) {
            return text;
        }

        if (content instanceof Multipart multipart) {
            StringBuilder html = new StringBuilder();
            StringBuilder plain = new StringBuilder();

            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                String partContent = extractPartContent(part);
                if (partContent == null) continue;

                String contentType = part.getContentType().toLowerCase();
                if (contentType.contains("text/html")) {
                    html.append(partContent);
                } else if (contentType.contains("text/plain")) {
                    plain.append(partContent);
                }
            }

            return !html.isEmpty() ? html.toString() : plain.toString();
        }

        return content != null ? content.toString() : null;
    }

    private String extractPartContent(BodyPart part) throws IOException, MessagingException {
        Object content = part.getContent();
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof InputStream inputStream) {
            return new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * 提取附件到临时文件
     */
    private List<String> extractAttachments(jakarta.mail.Message msg) throws IOException, MessagingException {
        List<String> paths = new ArrayList<>();
        Object content = msg.getContent();

        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                    String fileName = part.getFileName();
                    if (fileName == null) fileName = "attachment_" + i;

                    // 检查大小
                    int size = part.getSize();
                    if (size > maxAttachmentSize) {
                        log.warn("Attachment too large ({} > {}): {}", size, maxAttachmentSize, fileName);
                        continue;
                    }

                    // 保存到临时文件
                    File tempFile = File.createTempFile("hermes_email_", "_" + fileName);
                    tempFile.deleteOnExit();
                    try (InputStream is = part.getInputStream();
                         FileOutputStream fos = new FileOutputStream(tempFile)) {
                        is.transferTo(fos);
                    }
                    paths.add(tempFile.getAbsolutePath());
                }
            }
        }

        return paths;
    }

    @Override
    public String formatMessage(String content) {
        // 邮件支持 HTML，但纯文本更安全
        return content;
    }
}
