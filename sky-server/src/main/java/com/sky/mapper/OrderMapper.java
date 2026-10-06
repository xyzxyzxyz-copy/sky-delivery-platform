package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.GoodsSalesDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import com.sky.vo.OrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {
    /**
     * 插入订单数据
     *
     * @param orders
     */
    void insert(Orders orders);

    /**
     * 根据订单号查询订单
     *
     * @param orderNumber
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    /**
     * 修改订单信息
     *
     * @param orders
     */
    void update(Orders orders);

    Page<OrderVO> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);
    @Select("select * from orders where id=#{id} and user_id=#{userId}")
    OrderVO getOrderDetail(OrderVO orderVO);

    @Select("select * from orders where id=#{id} and user_id=#{userId}")
    Orders getByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Select("select * from orders where id=#{id}")
    Orders getById(Long id);

    @Select("select count(id) from orders where status=#{status}")
    Integer countStatus(Integer status);
    @Select("select * from orders where status=#{pendingPayment} and order_time< #{time} ")
    List<Orders> getTimeoutOrder(Integer pendingPayment, LocalDateTime time);

    /**
     * 统计指定时间区间内、指定状态的订单金额合计
     *
     * @param beginTime 开始时间
     * @param endTime   结束时间
     * @param completed 订单状态
     * @return 金额合计
     */
    Double sumTurn(@Param("beginTime") LocalDateTime beginTime,
                   @Param("endTime") LocalDateTime endTime,
                   @Param("completed") Integer completed);

    Integer getUserStatistics(Map<String, LocalDateTime> map);

    Integer getOrderStatistics(Map map);

    List<GoodsSalesDTO> getSalesTop10(@Param("beginTime") LocalDateTime beginTime, @Param("endTime") LocalDateTime endTime);
}
