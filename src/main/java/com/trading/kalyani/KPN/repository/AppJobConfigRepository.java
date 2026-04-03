package com.trading.kalyani.KPN.repository;

import com.trading.kalyani.KPN.entity.AppJobConfig;
import org.springframework.data.repository.CrudRepository;

public interface AppJobConfigRepository extends CrudRepository<AppJobConfig,Integer> {

    Iterable<AppJobConfig> findAllByOrderByAppJobConfigNumAsc();
}
