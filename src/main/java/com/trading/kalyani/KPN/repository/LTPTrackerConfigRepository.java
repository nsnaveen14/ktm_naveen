package com.trading.kalyani.KPN.repository;

import com.trading.kalyani.KPN.entity.LTPTrackerConfig;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LTPTrackerConfigRepository extends CrudRepository<LTPTrackerConfig, Integer> {
}
