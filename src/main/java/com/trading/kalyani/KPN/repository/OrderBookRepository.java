package com.trading.kalyani.KPN.repository;

import com.trading.kalyani.KPN.entity.OrderBook;
import org.springframework.data.repository.CrudRepository;

public interface OrderBookRepository extends CrudRepository<OrderBook,String> {
}
