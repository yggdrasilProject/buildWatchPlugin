package ru.linachan.monitor.jenkins;

import com.offbytwo.jenkins.model.BuildWithDetails;
import org.bson.Document;
import ru.linachan.monitor.common.noop.NoOpTest;
import ru.linachan.yggdrasil.YggdrasilCore;
import ru.linachan.yggdrasil.common.Queue;
import ru.linachan.yggdrasil.service.YggdrasilService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BuildProcessor implements YggdrasilService {

    private Queue<BuildWithDetails> failedBuilds;

    private static final Pattern PATTERN_FAILURE = Pattern.compile(
        "^(?m)(?<state>FAILED|SUCCESS)\\s*(?<task>[^\\s]+)\\s*(?<os>[^\\s]+)\\s*(?<yaml>[^\\s]+)\\s*$"
    );
    private static final Pattern PATTERN_TESTS = Pattern.compile(
        "^(?m)\\s*(?<state>failed|success)\\s*(?<test>.*?)$"
    );

    private boolean isRunning = true;

    @Override
    @SuppressWarnings("unchecked")
    public void onInit() {
        failedBuilds = (Queue<BuildWithDetails>) YggdrasilCore.INSTANCE.getQueue("failedBuilds");
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
                logger.info("Failed build found: {}", build.getId());
                try {
                    String[] lines = build.getConsoleOutputText().split("\\r\\n");

                    Document failedBuildDetails = new Document("buildID", build.getId())
                        .append("jobName", build.getFullDisplayName())
                        .append("description", build.getDescription());

                    List<NoOpTest> noOpTests = findNoOpTests(lines);
                    if (noOpTests.size() > 0) {
                        failedBuildDetails.append("noop", noOpTests.stream().map(NoOpTest::toBSON).collect(Collectors.toList()));
                    }

                    logger.info(failedBuildDetails.toJson());
                } catch (IOException e) {
                    logger.error("Unable to get Build details: {}", e.getMessage());
                }
            }
        }
    }
}
