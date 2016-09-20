package ru.linachan.monitor;

import com.google.common.base.Joiner;
import com.mongodb.client.MongoCollection;
import com.offbytwo.jenkins.model.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import ru.linachan.monitor.common.build.BuildData;
import ru.linachan.monitor.common.noop.NoOpTest;
import ru.linachan.monitor.common.noop.NoOpTestResult;
import ru.linachan.monitor.common.noop.NoOpTestState;
import ru.linachan.yggdrasil.YggdrasilCore;
import ru.linachan.yggdrasil.common.Queue;
import ru.linachan.yggdrasil.common.console.tables.Table;
import ru.linachan.yggdrasil.plugin.YggdrasilPluginManager;
import ru.linachan.yggdrasil.shell.YggdrasilShellCommand;
import ru.linachan.yggdrasil.shell.helpers.CommandAction;
import ru.linachan.yggdrasil.shell.helpers.ShellCommand;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@ShellCommand(command = "watch", description = "Show and manage BuildWatch reports.")
public class BuildWatchShell extends YggdrasilShellCommand {

    private MongoCollection<Document> builds;

    @Override
    protected void init() throws IOException {
        builds = YggdrasilCore.INSTANCE
            .getManager(YggdrasilPluginManager.class)
            .get(BuildWatchPlugin.class)
            .getDbClient()
            .getCollection("builds");
    }

    private String stripLine(String line, Integer length) {
        if (line.length() > length) {
            return line.substring(0, length - 3) + "...";
        }

        return line;
    }

    @CommandAction("List reports")
    public void list() throws IOException {
        Table reports = new Table("ID", "Job", "Build ID", "Triggered By", "Deploy", "NoOp", "Bug", "Comment");
        Integer resultLimit = Integer.parseInt(kwargs.getOrDefault("limit", "15"));

        for (Document report: builds.find().limit(resultLimit)) {
            BuildData buildData = BuildData.fromBSON(report);

            reports.addRow(
                buildData.getId(),
                buildData.getJobName(),
                String.valueOf(buildData.getBuildId()),
                buildData.getTriggeredBy(),
                String.valueOf(buildData.getDeploymentErrors().size()),
                String.valueOf(buildData.getNoOpTests().size()),
                Joiner.on(", ").join(buildData.getBugs()), buildData.getComment()
            );
        }

        console.writeTable(reports);
    }

    @SuppressWarnings("unchecked")
    @CommandAction("Show detailed report")
    public void show() throws IOException {
        if (args.size() > 0) {
            String reportId = args.get(0);

            Document report = builds.find(new Document("_id", new ObjectId(reportId))).first();
            if (report != null) {
                BuildData buildData = BuildData.fromBSON(report);

                List<String> errors = buildData.getDeploymentErrors();
                List<NoOpTest> noOpTestResults = buildData.getNoOpTests();

                if (errors.size() > 0) {
                    Table deploymentErrors = new Table("Deployment Errors");
                    for (String error : errors) {
                        for (String errorLine : error.split("\\n")) {
                            deploymentErrors.addRow(errorLine);
                        }
                    }
                    console.writeTable(deploymentErrors);
                }

                if (noOpTestResults.size() > 0) {
                    Table noOpTests = new Table("Task", "YAML", "OS", "Tests Failed");
                    noOpTestResults.stream()
                        .filter(noOpTest -> noOpTest.getState().equals(NoOpTestState.FAILED))
                        .forEach(noOpTest -> noOpTests.addRow(
                            noOpTest.getTaskName(),
                            noOpTest.getConfigurationName(),
                            noOpTest.getOs(),
                            String.valueOf(noOpTest.getFailedTests().size())
                        ));
                    console.writeTable(noOpTests);

                    if (kwargs.containsKey("noop-details")) {
                        Table noOpTestDetails = new Table("Task", "YAML", "OS", "Test", "Result", "Reason");
                        noOpTestResults.stream()
                            .filter(noOpTest -> noOpTest.getState().equals(NoOpTestState.FAILED))
                            .forEach(noOpTest -> {
                                for (NoOpTestResult testResult : noOpTest.getTests()) {
                                    String testName = testResult.getTestName();
                                    String failureReason = testResult.getFailureReason();

                                    if (!kwargs.containsKey("no-strip")) {
                                        testName = stripLine(testName, 50);
                                        failureReason = stripLine(failureReason, 50);
                                    }

                                    noOpTestDetails.addRow(
                                        noOpTest.getTaskName(),
                                        noOpTest.getConfigurationName(),
                                        noOpTest.getOs(), testName,
                                        testResult.getState().name(),
                                        failureReason
                                    );
                                }
                            });
                        console.writeTable(noOpTestDetails);
                    }
                }
            } else {
                console.writeLine("Report %s doesn't exists", reportId);
            }
        } else {
            console.writeLine("watch show [--no-strip] [--noop-details] <report>");
        }
    }

    @CommandAction("Set comment on report (overwrites existing).")
    public void set_comment() throws IOException {
        if (args.size() >= 2) {
            ObjectId reportId = new ObjectId(args.get(0));

            builds.updateOne(new Document("_id", reportId), new Document(
                "$set", new Document("comment", Joiner.on(" ").join(args.subList(1, args.size())))
            ));
        } else {
            console.writeLine("watch set_comment <report> <comment>");
        }
    }

    @CommandAction("Attach bug to report (overwrites existing).")
    public void attach_bug() throws IOException {
        if (args.size() >= 2) {
            ObjectId reportId = new ObjectId(args.get(0));

            builds.updateOne(new Document("_id", reportId), new Document(
                "$addToSet", new Document("bug", Joiner.on(" ").join(args.subList(1, args.size())))
            ));
        } else {
            console.writeLine("watch attach_bug <report> <bug_id>");
        }
    }

    @SuppressWarnings("unchecked")
    @CommandAction("Analyze build")
    public void analyze() throws IOException {
        if (args.size() > 0) {
            String jobName = kwargs.getOrDefault("job", null);
            List<Integer> buildNumbers = args.stream().map(Integer::parseInt).collect(Collectors.toList());

            if ((jobName != null)&&(buildNumbers.size() > 0)) {
                JobWithDetails job = core.getManager(YggdrasilPluginManager.class)
                    .get(BuildWatchPlugin.class).getJenkinsServer().getJob(jobName).details();

                if (job != null) {
                    for (Integer buildNumber: buildNumbers) {
                        BuildWithDetails build = job.getBuildByNumber(buildNumber).details();
                        if (build.getResult().equals(BuildResult.FAILURE)) {
                            console.writeLine("Build %s failed. Scheduling analysis.", buildNumber);
                            ((Queue<BuildWithDetails>) core.getQueue("failedBuilds")).push(build);
                        }
                    }
                } else {
                    console.writeLine("Job %s doesn't exists", jobName);
                }
            } else {
                console.writeLine("Job name or build IDs not specified");
            }
        } else {
            console.writeLine("watch analyze --job <job_name> <build> [<build> ...]");
        }
    }

    @Override
    protected void onInterrupt() {

    }
}
