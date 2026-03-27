package com.trading.kalyani.KTManager.repository;

import com.trading.kalyani.KTManager.entity.OptionBuyingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OptionBuyingConfigRepository extends JpaRepository<OptionBuyingConfig, Long> {
}
