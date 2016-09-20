package ru.linachan.monitor.jenkins;

import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.model.*;
import ru.linachan.yggdrasil.YggdrasilCore;
import ru.linachan.yggdrasil.common.Queue;
import ru.linachan.yggdrasil.scheduler.YggdrasilRunnable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class BuildWatch implements YggdrasilRunnable {

    private JenkinsServer jenkinsServer;
    private ru.linachan.yggdrasil.common.Queue<BuildWithDetails> failedBuilds;

    @SuppressWarnings("unchecked")
    public BuildWatch(JenkinsServer jenkinsServer) {
        this.jenkinsServer = jenkinsServer;
        this.failedBuilds = (Queue<BuildWithDetails>) YggdrasilCore.INSTANCE.getQueue("failedBuilds");
    }

    @Override
    public void run() {
        List<String> jobList = YggdrasilCore.INSTANCE.getConfig().getList("monitor.jenkins.jobs", String.class);
        Integer buildHistorySize = YggdrasilCore.INSTANCE.getConfig().getInt("monitor.jenkins.history.size", 10);

        try {
            Map<String, Job> jobMap = jenkinsServer.getJobs();

            for (String jobName: jobList) {
                if (jobMap.containsKey(jobName)) {
                    JobWithDetails job = jobMap.get(jobName).details();

                    int lastBuildID = job.getLastCompletedBuild().getNumber();
                    int rangeStartID = Math.max(lastBuildID - buildHistorySize, 1);

                    logger.info("Job: {} LastBuildID: {}", job.getDisplayName(), lastBuildID);

                    for (int buildId = rangeStartID; buildId <= lastBuildID; buildId++) {
                        BuildWithDetails buildInfo = job.getBuildByNumber(buildId).details();

                        if (buildInfo.getResult().equals(BuildResult.FAILURE)) {
                            failedBuilds.push(buildInfo);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Unable to get Jenkins jobs: {}", e.getMessage());
        }
    }

    @Override
    public void onCancel() {

    }
}
