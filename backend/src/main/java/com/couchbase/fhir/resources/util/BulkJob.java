package com.couchbase.fhir.resources.util;

import java.util.List;

public class BulkJob {

    private String jobId;
    private List<BulkTask> taskList;
    private String status;
    private List<BulkOutput> output;


    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public List<BulkTask> getTaskList() {
        return taskList;
    }

    public void setTaskList(List<BulkTask> taskList) {
        this.taskList = taskList;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }


    public List<BulkOutput> getOutput() {
        return output;
    }

    public void setOutput(List<BulkOutput> output) {
        this.output = output;
    }
}
