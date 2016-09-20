package ru.linachan.monitor.common;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import ru.linachan.monitor.BuildWatchPlugin;
import ru.linachan.monitor.common.noop.NoOpTest;
import ru.linachan.monitor.common.noop.NoOpTestState;
import ru.linachan.yggdrasil.YggdrasilCore;
import ru.linachan.yggdrasil.common.console.tables.Table;
import ru.linachan.yggdrasil.plugin.YggdrasilPluginManager;
import ru.linachan.yggdrasil.shell.YggdrasilShellCommand;
import ru.linachan.yggdrasil.shell.helpers.CommandAction;
import ru.linachan.yggdrasil.shell.helpers.ShellCommand;

import java.io.IOException;
import java.util.List;

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

    @CommandAction("List reports")
    public void list() throws IOException {
        Table reports = new Table("ID", "Job", "Build ID", "Deployment Failures", "NoOp Test Failures");
        Integer resultLimit = Integer.parseInt(kwargs.getOrDefault("limit", "15"));

        for (Document report: builds.find().limit(resultLimit)) {
            reports.addRow(
                report.getObjectId("_id").toHexString(),
                report.getString("buildName").split(" #")[0],
                report.getString("buildName").split(" #")[1],
                String.valueOf(((List) report.get("errors")).size()),
                String.valueOf(((List) report.get("noop")).size())
            );
        }

        console.writeTable(reports);
    }

    @SuppressWarnings("unchecked")
    @CommandAction("Show detailed report")
    public void show() throws IOException {
        String reportId = args.get(0);

        Document report = builds.find(new Document("_id", new ObjectId(reportId))).first();
        if (report != null) {
            List<String> errors = (List<String>) report.get("errors");
            List<Document> noOpTestResults = (List<Document>) report.get("noop");

            if (errors.size() > 0) {
                Table deploymentErrors = new Table("Deployment Error");
                for (String error: errors) {
                    for (String errorLine: error.split("\\n")) {
                        deploymentErrors.addRow(errorLine);
                    }
                }
                console.writeTable(deploymentErrors);
            }

            if (noOpTestResults.size() > 0) {
                Table noOpTests = new Table("Task", "YAML", "OS", "Tests Failed");
                for (Document noOpTestResult: noOpTestResults) {
                    NoOpTest noOpTest = NoOpTest.fromBSON(noOpTestResult);
                    if (noOpTest.getState().equals(NoOpTestState.FAILED)) {
                        noOpTests.addRow(
                            noOpTest.getTaskName(),
                            noOpTest.getConfigurationName(),
                            noOpTest.getOs(),
                            String.valueOf(noOpTest.getFailedTests().size())
                        );
                    }
                }
                console.writeTable(noOpTests);
            }
        } else {
            console.writeLine("Report %s doesn't exists", reportId);
        }
    }

    @Override
    protected void onInterrupt() {

    }
}
