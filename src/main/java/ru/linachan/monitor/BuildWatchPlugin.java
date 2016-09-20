package ru.linachan.monitor;

import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.model.BuildWithDetails;
import ru.linachan.monitor.db.DataBaseWrapper;
import ru.linachan.monitor.jenkins.BuildWatch;
import ru.linachan.monitor.mail.MailInterface;
import ru.linachan.yggdrasil.YggdrasilCore;
import ru.linachan.yggdrasil.plugin.YggdrasilPlugin;
import ru.linachan.yggdrasil.plugin.YggdrasilPluginManager;
import ru.linachan.yggdrasil.plugin.helpers.AutoStart;
import ru.linachan.yggdrasil.plugin.helpers.DependsOn;
import ru.linachan.yggdrasil.plugin.helpers.Plugin;
import ru.linachan.yggdrasil.scheduler.YggdrasilTask;
import ru.linachan.email.EMailPlugin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

@AutoStart
@DependsOn(EMailPlugin.class)
@Plugin(name = "build-monitor", description = "Provides ability to monitor Jenkins jobs.")
public class BuildWatchPlugin implements YggdrasilPlugin {

    private DataBaseWrapper dbClient;
    private JenkinsServer jenkinsServer;

    @Override
    public void onInit() {
        URI jenkinsURI;

        try {
            jenkinsURI = new URI(
                core.getConfig().getString("monitor.jenkins.url", "http://localhost/jenkinsServer")
            );
        } catch (URISyntaxException e) {
            logger.error("Unable to parse Jenkins URL. Deactivating plugin...");
            core.disablePackage("monitor");
            return;
        }

        String jenkinsUserName = core.getConfig().getString("monitor.jenkins.username", null);
        String jenkinsPassWord = core.getConfig().getString("monitor.jenkins.password", null);

        if ((jenkinsUserName != null)&&(jenkinsPassWord != null)) {
            jenkinsServer = new JenkinsServer(jenkinsURI, jenkinsUserName, jenkinsPassWord);
        } else {
            jenkinsServer = new JenkinsServer(jenkinsURI);
        }

        dbClient = new DataBaseWrapper(
            core.getConfig().getString("monitor.db.uri", "mongodb://127.0.0.1:27017/"),
            core.getConfig().getString("monitor.db.name", "monitor")
        );

        core.createQueue(BuildWithDetails.class, "failedBuilds");
        core.createQueue(BuildWithDetails.class, "buildMail");

        core.getScheduler().scheduleTask(new YggdrasilTask(
            "buildWatch", new BuildWatch(jenkinsServer), 0, 120, TimeUnit.SECONDS
        ));

        core.getManager(YggdrasilPluginManager.class).get(EMailPlugin.class).registerHandler(
            "WATCH", new MailInterface()
        );
    }

    public DataBaseWrapper getDbClient() {
        return dbClient;
    }

    public JenkinsServer getJenkinsServer() {
        return jenkinsServer;
    }

    @Override
    public void onShutdown() {
        YggdrasilCore.INSTANCE.getScheduler().getTask("buildWatch").cancelTask();
        dbClient.close();
    }
}
