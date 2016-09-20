package ru.linachan.monitor.jenkins;

import com.mongodb.client.MongoCollection;
import com.offbytwo.jenkins.model.BuildWithDetails;
import org.bson.Document;
import ru.linachan.monitor.BuildWatchPlugin;
import ru.linachan.monitor.common.build.BuildData;
import ru.linachan.yggdrasil.YggdrasilCore;
import ru.linachan.yggdrasil.common.Queue;
import ru.linachan.yggdrasil.plugin.YggdrasilPluginManager;
import ru.linachan.yggdrasil.service.YggdrasilService;

import java.io.IOException;


public class BuildProcessor implements YggdrasilService {

    private Queue<BuildWithDetails> failedBuilds;
    private Queue<BuildData> buildMail;

    private MongoCollection<Document> builds;

    private boolean isRunning = true;

    @Override
    @SuppressWarnings("unchecked")
    public void onInit() {
        failedBuilds = (Queue<BuildWithDetails>) YggdrasilCore.INSTANCE.getQueue("failedBuilds");
        buildMail = (Queue<BuildData>) YggdrasilCore.INSTANCE.getQueue("buildMail");

        builds = core.getManager(YggdrasilPluginManager.class)
            .get(BuildWatchPlugin.class)
            .getDbClient()
            .getCollection("builds");
    }

    @Override
    public void onShutdown() {
        isRunning = false;
    }

    @Override
    public void run() {
        while (isRunning) {
            BuildWithDetails build = failedBuilds.pop();
            if (build != null) {
                boolean buildFound = builds.count(
                    new Document("buildName", build.getFullDisplayName())
                ) > 0;

                if (!buildFound) {
                    logger.info("New failed build found: {}", build.getId());

                    try {
                        BuildData buildData = new BuildData(build);
                        builds.insertOne(buildData.toBSON());
                        buildMail.push(buildData);
                    } catch (IOException e) {
                        logger.error("Unable to get Build details: {}", e.getMessage());
                    }
                }
            }
        }
    }
}
