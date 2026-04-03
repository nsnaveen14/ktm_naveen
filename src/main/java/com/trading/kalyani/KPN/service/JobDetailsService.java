package com.trading.kalyani.KPN.service;

import com.trading.kalyani.KPN.entity.JobDetails;

import java.util.List;

public interface JobDetailsService {

    public boolean isJobRunning(Integer appJobConfigNum);

    public List<JobDetails> getJobRunningStatusByConfigNum(Integer appJobConfigNum);

}
