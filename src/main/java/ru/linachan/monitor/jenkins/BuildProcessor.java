package ru.linachan.monitor.jenkins;

import com.mongodb.client.MongoCollection;
import com.offbytwo.jenkins.model.BuildWithDetails;
import org.bson.Document;
import ru.linachan.monitor.BuildWatchPlugin;
import ru.linachan.monitor.common.noop.NoOpTest;
import ru.linachan.yggdrasil.YggdrasilCore;
import ru.linachan.yggdrasil.common.Queue;
import ru.linachan.yggdrasil.plugin.YggdrasilPluginManager;
import ru.linachan.yggdrasil.service.YggdrasilService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BuildProcessor implements YggdrasilService {

    private Queue<BuildWithDetails> failedBuilds;
    private MongoCollection<Document> builds;

    private static final Pattern PATTERN_FAILURE = Pattern.compile(
        "^(?m)(?<state>FAILED|SUCCESS)\\s*(?<task>[^\\s]+)\\s*(?<os>[^\\s]+)\\s*(?<yaml>[^\\s]+)\\s*$"
    );
    private static final Pattern PATTERN_TESTS = Pattern.compile(
        "^(?m)\\s*(?<state>failed|success)\\s*(?<test>should .*?)$"
    );

    private boolean isRunning = true;

    @Override
    @SuppressWarnings("unchecked")
    public void onInit() {
        failedBuilds = (Queue<BuildWithDetails>) YggdrasilCore.INSTANCE.getQueue("failedBuilds");

        builds = YggdrasilCore.INSTANCE
            .getManager(YggdrasilPluginManager.class)
            .get(BuildWatchPlugin.class)
            .getDbClient()
            .getCollection("builds");
    }

    @Override
    public void onShutdown() {
        isRunning = false;
    }

    public List<NoOpTest> findNoOpTests(String[] log) {
        List<NoOpTest> noOpTests = new ArrayList<>();

        for (int lineNo = 0; lineNo < log.length; lineNo++) {
            Matcher failureMatcher = PATTERN_FAILURE.matcher(log[lineNo]);
            if (failureMatcher.matches()) {
                NoOpTest noOpTest = new NoOpTest(
                    failureMatcher.group("task"),
                    failureMatcher.group("yaml"),
                    failureMatcher.group("os"),
                    failureMatcher.group("state")
                );

                while (lineNo < log.length - 1) {
                    Matcher testMatcher = PATTERN_TESTS.matcher(log[lineNo + 1]);

                    if (testMatcher.matches()) {
                        noOpTest.addTest(
                            testMatcher.group("test"),
                            testMatcher.group("state")
                        );

                        lineNo++;
                    } else {
                        break;
                    }
                }

                noOpTests.add(noOpTest);
            }
        }

        return noOpTests;
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
                        String[] lines = build.getConsoleOutputText().split("\\r\\n");

                        Document failedBuildDetails = new Document("buildName", build.getFullDisplayName())
                            .append("description", build.getDescription())
                            .append("noop", new ArrayList<Document>());

                        builds.insertOne(failedBuildDetails);

                        builds.updateOne(new Document("buildName", build.getFullDisplayName()), new Document(
                            "$push", new Document(
                                "noop", new Document(
                                    "$each", findNoOpTests(lines).stream().map(NoOpTest::toBSON).collect(Collectors.toList())
                                )
                            )
                        ));
                    } catch (IOException e) {
                        logger.error("Unable to get Build details: {}", e.getMessage());
                    }
                }
            }
        }
    }
}
