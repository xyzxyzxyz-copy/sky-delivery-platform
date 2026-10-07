package com.sky.Task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.service.cache.OrderCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class OrderTask {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderCacheService orderCacheService;
    @Scheduled(cron = "0 * * * * ?")
//@Scheduled(cron = "0/5 * * * * ?")
    public void processTimeoutOrder(){

        log.info("处理超时订单");
        LocalDateTime time=LocalDateTime.now().plusMinutes(-15);
        List<Orders> ordersList=orderMapper.getTimeoutOrder(Orders.PENDING_PAYMENT,time);
        if(ordersList != null && !ordersList.isEmpty()){
            for (Orders orders:ordersList){
                orders.setStatus(Orders.CANCELLED);
                orders.setCancelReason("订单超时,自动取消");
                orders.setCancelTime(LocalDateTime.now());
                orderMapper.update(orders);
                orderCacheService.evict(orders.getId());
            }
        }
    }
    @Scheduled(cron = "0 0 1 * * ?")
//@Scheduled(cron = "0/5 * * * * ?")
    public void processDeliveryOrder(){
        log.info("处理派送中超时的订单");
        LocalDateTime time=LocalDateTime.now().plusMinutes(-60);
        List<Orders> ordersList=orderMapper.getTimeoutOrder(Orders.DELIVERY_IN_PROGRESS,time);
        if(ordersList != null && !ordersList.isEmpty()){
            for (Orders orders:ordersList){
                orders.setStatus(Orders.COMPLETED);
                orderMapper.update(orders);
                orderCacheService.evict(orders.getId());
            }
        }
    }
}
