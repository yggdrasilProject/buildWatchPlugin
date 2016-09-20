package ru.linachan.monitor;

import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.model.BuildWithDetails;
import ru.linachan.monitor.db.DataBaseWrapper;
import ru.linachan.monitor.jenkins.BuildWatch;
import ru.linachan.yggdrasil.YggdrasilCore;
import ru.linachan.yggdrasil.plugin.YggdrasilPlugin;
import ru.linachan.yggdrasil.plugin.helpers.AutoStart;
import ru.linachan.yggdrasil.plugin.helpers.Plugin;
import ru.linachan.yggdrasil.scheduler.YggdrasilTask;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

@AutoStart
@Plugin(name = "build-monitor", description = "Provides ability to monitor Jenkins jobs.")
public class BuildWatchPlugin implements YggdrasilPlugin {

    private DataBaseWrapper dbClient;

    @Override
    public void onInit() {
        URI jenkinsURI;

        try {
            jenkinsURI = new URI(
                YggdrasilCore.INSTANCE.getConfig().getString("monitor.jenkins.url", "http://localhost/jenkinsServer")
            );
        } catch (URISyntaxException e) {
            logger.error("Unable to parse Jenkins URL. Deactivating plugin...");
            YggdrasilCore.INSTANCE.disablePackage("monitor");
            return;
        }

        String jenkinsUserName = YggdrasilCore.INSTANCE.getConfig().getString("monitor.jenkins.username", null);
        String jenkinsPassWord = YggdrasilCore.INSTANCE.getConfig().getString("monitor.jenkins.password", null);

        JenkinsServer jenkinsServer;

        if ((jenkinsUserName != null)&&(jenkinsPassWord != null)) {
            jenkinsServer = new JenkinsServer(jenkinsURI, jenkinsUserName, jenkinsPassWord);
        } else {
            jenkinsServer = new JenkinsServer(jenkinsURI);
        }

        dbClient = new DataBaseWrapper(
            YggdrasilCore.INSTANCE.getConfig().getString("monitor.db.uri", "mongodb://127.0.0.1:27017/"),
            YggdrasilCore.INSTANCE.getConfig().getString("monitor.db.name", "monitor")
        );

        YggdrasilCore.INSTANCE.createQueue(BuildWithDetails.class, "failedBuilds");

        YggdrasilCore.INSTANCE.getScheduler().scheduleTask(new YggdrasilTask(
            "buildWatch", new BuildWatch(jenkinsServer), 0, 120, TimeUnit.SECONDS
        ));
    }

    public DataBaseWrapper getDbClient() {
        return dbClient;
    }

    @Override
    public void onShutdown() {
        YggdrasilCore.INSTANCE.getScheduler().getTask("buildWatch").cancelTask();
        dbClient.close();
    }
}
