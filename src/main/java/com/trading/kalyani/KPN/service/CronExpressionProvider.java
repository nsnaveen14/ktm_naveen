package com.trading.kalyani.KPN.service;

public interface CronExpressionProvider {

    String getCronExpressionForSnapshot(Integer indexId);

    String getCronExpressionForStartJob(Integer indexId);

    String getCronExpressionForEndJob(Integer indexId);

}
