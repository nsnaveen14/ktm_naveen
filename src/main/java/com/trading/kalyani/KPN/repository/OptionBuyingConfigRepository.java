package com.trading.kalyani.KPN.repository;

import com.trading.kalyani.KPN.entity.OptionBuyingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OptionBuyingConfigRepository extends JpaRepository<OptionBuyingConfig, Long> {
}
