package ru.linachan.monitor.mail;

import com.mongodb.client.MongoCollection;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.bson.Document;
import ru.linachan.email.EMailPlugin;
import ru.linachan.monitor.BuildWatchPlugin;
import ru.linachan.monitor.common.build.BuildData;
import ru.linachan.monitor.common.noop.NoOpTestResult;
import ru.linachan.monitor.common.noop.NoOpTestState;
import ru.linachan.yggdrasil.YggdrasilCore;
import ru.linachan.yggdrasil.common.Queue;
import ru.linachan.yggdrasil.plugin.YggdrasilPluginManager;
import ru.linachan.yggdrasil.service.YggdrasilService;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import java.io.StringWriter;
import java.util.*;

public class MailWatch implements YggdrasilService {

    private Queue<BuildData> buildMail;
    private boolean isRunning = true;

    private EMailPlugin eMailPlugin;
    private VelocityEngine engine;

    private MongoCollection<Document> builds;

    @Override
    @SuppressWarnings("unchecked")
    public void onInit() {
        buildMail = (Queue<BuildData>) YggdrasilCore.INSTANCE.getQueue("buildMail");

        builds = core.getManager(YggdrasilPluginManager.class)
            .get(BuildWatchPlugin.class)
            .getDbClient()
            .getCollection("builds");

        eMailPlugin = core.getManager(YggdrasilPluginManager.class)
            .get(EMailPlugin.class);

        engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        engine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
        engine.init();
    }

    @Override
    public void onShutdown() {
        isRunning = false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run() {
        while (isRunning) {
            BuildData build = buildMail.pop();
            if (build != null) {
                List<Map<String, String>> noOpTests = new ArrayList<>();

                build.getNoOpTests().stream()
                    .filter(noOpTest -> noOpTest.getState().equals(NoOpTestState.FAILED))
                    .forEach(noOpTest -> {
                        for (NoOpTestResult noOpTestResult : noOpTest.getTests()) {
                            Map<String, String> noOpTestData = new HashMap<>();
                            noOpTestData.put("task", noOpTest.getTaskName());
                            noOpTestData.put("yaml", noOpTest.getConfigurationName());
                            noOpTestData.put("os", noOpTest.getOs());

                            noOpTestData.put("test", noOpTestResult.getTestName());
                            noOpTestData.put("state", noOpTestResult.getState().name());
                            noOpTestData.put("cause", noOpTestResult.getFailureReason());

                            noOpTests.add(noOpTestData);
                        }
                    });

                try {
                    List<String> recipients = core.getConfig().getList("monitor.email.recipients", String.class);

                    Address[] recipientList = new Address[recipients.size()];

                    for (int i = 0; i < recipients.size(); i++) {
                        recipientList[i] = new InternetAddress(recipients.get(i));
                    }

                    Message report = eMailPlugin.newMessage(
                        String.format("[-WATCH-] %s #%s", build.getJobName(), build.getBuildId()), recipientList
                    );

                    Template template = engine.getTemplate("templates/report.vm");
                    VelocityContext context = new VelocityContext();

                    context.internalPut("jobName", build.getJobName());
                    context.internalPut("buildId", build.getBuildId());

                    context.internalPut("changeTitle", build.getChangeTitle());
                    context.internalPut("changeNumber", build.getChangeId());
                    context.internalPut("patchSetNumber", build.getPatchSet());

                    context.internalPut("slave", build.getSlave());
                    context.internalPut("data", new Date(build.getTimeStamp()).toString());

                    context.internalPut("errors", build.getDeploymentErrors());
                    context.internalPut("noop", noOpTests);

                    StringWriter writer = new StringWriter();
                    template.merge(context, writer);
                    report.setContent(writer.toString(), "text/html; charset=utf-8");

                    eMailPlugin.sendMessage(report);
                } catch (MessagingException e) {
                    logger.error("Unable to send E-Mail report: {}", e.getMessage());
                }
            }
        }
    }
}
