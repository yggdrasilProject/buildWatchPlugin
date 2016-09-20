package ru.linachan.monitor.common.noop;

import org.bson.Document;

public class NoOpTestResult {

    private String testName;
    private String failureReason;

    private NoOpTestState testState;

    public NoOpTestResult(String testCase, String state, String cause) {
        testName = testCase;
        failureReason = cause;

        switch (state.toLowerCase()) {
            case "success":
                testState = NoOpTestState.SUCCESS;
                break;
            case "failed":
                testState = NoOpTestState.FAILED;
                break;
            default:
                testState = NoOpTestState.UNKNOWN;
                break;
        }
    }

    public String getTestName() {
        return testName;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public NoOpTestState getState() {
        return testState;
    }

    public Document toBSON() {
        return new Document("test", testName)
            .append("cause", failureReason)
            .append("state", testState.toString());
    }

    public static NoOpTestResult fromBSON(Document testResult) {
        return new NoOpTestResult(
            testResult.getString("test"),
            testResult.getString("state"),
            testResult.getString("cause")
        );
    }
}
