package ru.linachan.monitor.mail;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMultipart;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.linachan.email.EMailHandler;
import ru.linachan.monitor.BuildWatchPlugin;
import ru.linachan.yggdrasil.plugin.YggdrasilPluginManager;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MailInterface implements EMailHandler {

    private MongoCollection<Document> builds;

    private static Logger logger = LoggerFactory.getLogger(MailInterface.class);
    private static final Pattern CMD_PATTERN = Pattern.compile(
        "^(?<no>no\\s+)?(?<command>[a-zA-Z0-9_]+)\\s*(?<args>.*?)$"
    );

    private MongoCollection<Document> getCollection() {
        if (builds == null) {
            builds = core.getManager(YggdrasilPluginManager.class)
                .get(BuildWatchPlugin.class)
                .getDbClient()
                .getCollection("builds");
        }

        return builds;
    }

    @Override
    public void handle(String title, Message message) {
        Document build = getCollection().find(new Document("buildName", title.trim())).first();
        if (build != null) {
            try {
                logger.info("Got control message for build: {}", title);
                MimeMultipart content = (MimeMultipart) message.getContent();
                switch (content.getBodyPart(0).getContentType().split(";")[0]) {
                    case "text/plain":
                        String[] data = content.getBodyPart(0).getContent().toString().replace("\r", "").split("\\n");
                        for (String command: data) {
                            Matcher commandMatcher = CMD_PATTERN.matcher(command);

                            if (commandMatcher.matches()) {
                                boolean inverse = commandMatcher.group("no") != null;
                                String cmd = commandMatcher.group("command");
                                String args = commandMatcher.group("args");

                                switch (cmd) {
                                    case "comment":
                                        break;
                                    case "bug":
                                        break;
                                    default:
                                        break;
                                }
                            }
                        }
                        break;
                    default:
                        break;
                }
            } catch (MessagingException | IOException e) {
                logger.error("Unable to handle message: {}", e.getMessage());
            }
        }
    }
}
