package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersConfirmDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.entity.User;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.service.cache.OrderCacheService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import io.swagger.util.Json;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private WebSocketServer webSocketServer;
    @Autowired
    private OrderCacheService orderCacheService;

    /**
     * 是否启用真实微信支付：false 为模拟支付，便于本地联调
     */
    @Value("${sky.wechat.pay-enabled:false}")
    private boolean weChatPayEnabled;

    @Value("${sky.shop.address}")
    private String shopAddress;

    @Value("${sky.baidu.ak:}")
    private String baiduMapAk;

    /**
     * 店铺坐标缓存（lat,lng），避免每次下单都重复调用地理编码接口
     */
    private volatile String shopCoordinateCache;

    /**
     * 用户下单
     *
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    @Transactional
    public OrderSubmitVO submit(OrdersSubmitDTO ordersSubmitDTO){
        Long userId=BaseContext.getCurrentId();
        AddressBook addressBook=addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if(addressBook==null || !userId.equals(addressBook.getUserId())){
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        checkOutOfRange(buildFullAddress(addressBook));

        ShoppingCart shoppingCart=new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartList=shoppingCartMapper.list(shoppingCart);
        if(shoppingCartList==null||shoppingCartList.size()==0){
          throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setAddress(addressBook.getDetail());
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(userId);
        orderMapper.insert(orders);

        List<OrderDetail> orderDetailList=new ArrayList<>();
        for(ShoppingCart shoppingCart1:shoppingCartList){
            OrderDetail orderDetail=new OrderDetail();
            BeanUtils.copyProperties(shoppingCart1,orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);

        shoppingCartMapper.clean(userId);

        // 防止该订单id在创建前曾被空值缓存；事务提交后再删除，避免读到未提交数据。
        orderCacheService.evictAfterCommit(orders.getId());

        //5. 封装VO返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())
                .orderTime(orders.getOrderTime())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .build();

        return orderSubmitVO;
    }

    /**
     * 校验收货地址到店铺的驾车距离是否在5公里内。
     */
    private void checkOutOfRange(String userAddress) {
        if (baiduMapAk == null || baiduMapAk.trim().isEmpty()) {
            //未配置AK时跳过校验，避免阻塞下单流程；配置 sky.baidu.ak 后该功能自动生效
            log.warn("未配置 sky.baidu.ak，已跳过配送范围校验");
            return;
        }

        String shopCoordinate = getShopCoordinate();
        String userCoordinate = geocode(userAddress, MessageConstant.USER_ADDRESS_PARSE_FAILED);

        Map<String, String> params = new HashMap<>();
        params.put("origin", shopCoordinate);
        params.put("destination", userCoordinate);
        params.put("ak", baiduMapAk);
        params.put("steps_info", "0");

        try {
            String response = HttpClientUtil.doGet(
                    "https://api.map.baidu.com/directionlite/v1/driving", params);
            if (response == null || response.trim().isEmpty()) {
                throw new OrderBusinessException(MessageConstant.DELIVERY_ROUTE_PLAN_FAILED);
            }

            JSONObject responseJson = JSON.parseObject(response);
            if (responseJson == null || responseJson.getIntValue("status") != 0) {
                throw new OrderBusinessException(MessageConstant.DELIVERY_ROUTE_PLAN_FAILED);
            }

            JSONObject result = responseJson.getJSONObject("result");
            JSONArray routes = result == null ? null : result.getJSONArray("routes");
            if (routes == null || routes.isEmpty()) {
                throw new OrderBusinessException(MessageConstant.DELIVERY_ROUTE_PLAN_FAILED);
            }

            int distance = routes.getJSONObject(0).getIntValue("distance");
            if (distance > 5000) {
                throw new OrderBusinessException(MessageConstant.OUT_OF_DELIVERY_RANGE);
            }
        } catch (OrderBusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("百度地图配送路线规划失败", ex);
            throw new OrderBusinessException(MessageConstant.DELIVERY_ROUTE_PLAN_FAILED);
        }
    }

    private String geocode(String address, String errorMessage) {
        Map<String, String> params = new HashMap<>();
        params.put("address", address);
        params.put("output", "json");
        params.put("ak", baiduMapAk);

        try {
            String response = HttpClientUtil.doGet(
                    "https://api.map.baidu.com/geocoding/v3/", params);
            if (response == null || response.trim().isEmpty()) {
                throw new OrderBusinessException(errorMessage);
            }

            JSONObject responseJson = JSON.parseObject(response);
            if (responseJson == null || responseJson.getIntValue("status") != 0) {
                throw new OrderBusinessException(errorMessage);
            }

            JSONObject result = responseJson.getJSONObject("result");
            JSONObject location = result == null ? null : result.getJSONObject("location");
            if (location == null || location.get("lat") == null || location.get("lng") == null) {
                throw new OrderBusinessException(errorMessage);
            }
            return location.getString("lat") + "," + location.getString("lng");
        } catch (OrderBusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("百度地图地址解析失败，地址：{}", address, ex);
            throw new OrderBusinessException(errorMessage);
        }
    }

    private String buildFullAddress(AddressBook addressBook) {
        StringBuilder address = new StringBuilder();
        appendAddressPart(address, addressBook.getProvinceName());
        appendAddressPart(address, addressBook.getCityName());
        appendAddressPart(address, addressBook.getDistrictName());
        appendAddressPart(address, addressBook.getDetail());
        return address.toString();
    }

    private void appendAddressPart(StringBuilder address, String part) {
        if (part != null && !part.trim().isEmpty()) {
            address.append(part.trim());
        }
    }

    /**
     * 获取店铺坐标，解析成功后缓存，避免重复调用地理编码接口
     */
    private String getShopCoordinate() {
        if (shopCoordinateCache == null) {
            synchronized (this) {
                if (shopCoordinateCache == null) {
                    shopCoordinateCache = geocode(shopAddress, MessageConstant.SHOP_ADDRESS_PARSE_FAILED);
                }
            }
        }
        return shopCoordinateCache;
    }

    /**
     * 分页参数兜底：前端不传时 page/pageSize 为 0，会导致 limit 0 查不到数据
     */
    private void applyDefaultPage(OrdersPageQueryDTO ordersPageQueryDTO) {
        if (ordersPageQueryDTO.getPage() <= 0) {
            ordersPageQueryDTO.setPage(1);
        }
        if (ordersPageQueryDTO.getPageSize() <= 0) {
            ordersPageQueryDTO.setPageSize(10);
        }
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        Orders ordersDB = orderMapper.getByNumber(ordersPaymentDTO.getOrderNumber());
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (Orders.PAID.equals(ordersDB.getPayStatus())) {
            throw new OrderBusinessException("该订单已支付");
        }

        if (!weChatPayEnabled) {
            //模拟支付：跳过微信下单和支付回调，直接完成支付，便于本地联调
            log.info("未开启微信支付(pay-enabled=false)，订单{}走模拟支付", ordersDB.getNumber());
            paySuccess(ordersDB.getNumber());
            return new OrderPaymentVO();
        }

        //调用微信支付接口，生成预支付交易单
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), //商户订单号
                ordersDB.getAmount(), //支付金额，单位 元
                "苍穹外卖订单", //商品描述
                user.getOpenid() //微信用户的openid
        );

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    @Override
    public void paySuccess(String outTradeNo) {
        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);
        if (ordersDB == null) {
            log.warn("支付回调未找到对应订单，订单号：{}", outTradeNo);
            return;
        }
        if (Orders.PAID.equals(ordersDB.getPayStatus())) {
            //已支付过，避免重复回调重复更新
            return;
        }

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);
        orderCacheService.evictAfterCommit(ordersDB.getId());
        Map map=new HashMap();
        map.put("type",1);
        map.put("orderId",ordersDB.getId());
        map.put("content","订单号:"+outTradeNo);
        String msg=JSON.toJSONString(map);
        webSocketServer.sendToAllClient(msg);
    }
    @Override
    @Transactional
    public PageResult pageQuery(OrdersPageQueryDTO ordersPageQueryDTO){
        applyDefaultPage(ordersPageQueryDTO);
        //只查询当前登录用户的订单
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        PageHelper.startPage(ordersPageQueryDTO.getPage(),ordersPageQueryDTO.getPageSize());
        Page<OrderVO> page=orderMapper.pageQuery(ordersPageQueryDTO);
        List<OrderVO> orderVOList=page.getResult();
        for(OrderVO orderVO:orderVOList){
            List<OrderDetail> orderDetailList=orderDetailMapper.getByOrderId(orderVO.getId());
            orderVO.setOrderDetailList(orderDetailList);
        }
        return new PageResult(page.getTotal(),orderVOList);
    }

    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        // 商家端查询全部用户订单，不能把当前员工id当成用户id。
        ordersPageQueryDTO.setUserId(null);
        applyDefaultPage(ordersPageQueryDTO);
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        Page<OrderVO> page = orderMapper.pageQuery(ordersPageQueryDTO);
        List<OrderVO> orderVOList = page.getResult();

        for (OrderVO orderVO : orderVOList) {
            List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orderVO.getId());
            orderVO.setOrderDishes(getOrderDishesStr(orderDetailList));
            fillUserName(orderVO);
        }

        return new PageResult(page.getTotal(), orderVOList);
    }

    private String getOrderDishesStr(List<OrderDetail> orderDetailList) {
        StringBuilder orderDishes = new StringBuilder();
        if (orderDetailList != null) {
            for (OrderDetail orderDetail : orderDetailList) {
                orderDishes.append(orderDetail.getName())
                        .append("*")
                        .append(orderDetail.getNumber())
                        .append(";");
            }
        }
        return orderDishes.toString();
    }

    private void fillUserName(OrderVO orderVO) {
        if ((orderVO.getUserName() == null || orderVO.getUserName().isEmpty())
                && orderVO.getUserId() != null) {
            User user = userMapper.getById(orderVO.getUserId());
            if (user != null) {
                orderVO.setUserName(user.getName());
            }
        }
    }

    @Override
    public OrderStatisticsVO statistics() {
        OrderStatisticsVO statisticsVO = new OrderStatisticsVO();
        statisticsVO.setToBeConfirmed(orderMapper.countStatus(Orders.TO_BE_CONFIRMED));
        statisticsVO.setConfirmed(orderMapper.countStatus(Orders.CONFIRMED));
        statisticsVO.setDeliveryInProgress(orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS));
        return statisticsVO;
    }

    @Override
    public OrderVO details(Long id) {
        OrderVO orderVO = orderCacheService.query(id, () -> loadOrderDetail(id));
        if (orderVO == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        return orderVO;
    }

    /**
     * 从数据库组装完整订单详情，作为缓存回源方法。
     */
    private OrderVO loadOrderDetail(Long id) {
        if (id == null) {
            return null;
        }
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            return null;
        }

        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        orderVO.setOrderDishes(getOrderDishesStr(orderDetailList));
        fillUserName(orderVO);
        return orderVO;
    }

    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        if (ordersConfirmDTO == null || ordersConfirmDTO.getId() == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        Orders ordersDB = getOrderOrThrow(ordersConfirmDTO.getId());
        if (!Orders.TO_BE_CONFIRMED.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.CONFIRMED)
                .build();
        orderMapper.update(orders);
        orderCacheService.evictAfterCommit(ordersDB.getId());
    }

    @Override
    @Transactional
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        if (ordersRejectionDTO == null || ordersRejectionDTO.getId() == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (ordersRejectionDTO.getRejectionReason() == null
                || ordersRejectionDTO.getRejectionReason().trim().isEmpty()) {
            throw new OrderBusinessException("拒单原因不能为空");
        }

        Orders ordersDB = getOrderOrThrow(ordersRejectionDTO.getId());
        if (!Orders.TO_BE_CONFIRMED.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.CANCELLED)
                .rejectionReason(ordersRejectionDTO.getRejectionReason().trim())
                .cancelTime(LocalDateTime.now())
                .build();
        refundIfPaid(ordersDB, orders);
        orderMapper.update(orders);
        orderCacheService.evictAfterCommit(ordersDB.getId());
    }

    @Override
    @Transactional
    public void cancel(OrdersCancelDTO ordersCancelDTO) {
        if (ordersCancelDTO == null || ordersCancelDTO.getId() == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (ordersCancelDTO.getCancelReason() == null
                || ordersCancelDTO.getCancelReason().trim().isEmpty()) {
            throw new OrderBusinessException("取消原因不能为空");
        }

        Orders ordersDB = getOrderOrThrow(ordersCancelDTO.getId());
        if (Orders.COMPLETED.equals(ordersDB.getStatus())
                || Orders.CANCELLED.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.CANCELLED)
                .cancelReason(ordersCancelDTO.getCancelReason().trim())
                .cancelTime(LocalDateTime.now())
                .build();
        refundIfPaid(ordersDB, orders);
        orderMapper.update(orders);
        orderCacheService.evictAfterCommit(ordersDB.getId());
    }

    @Override
    public void delivery(Long id) {
        Orders ordersDB = getOrderOrThrow(id);
        if (!Orders.CONFIRMED.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.DELIVERY_IN_PROGRESS)
                .build();
        orderMapper.update(orders);
        orderCacheService.evictAfterCommit(ordersDB.getId());
    }

    @Override
    public void complete(Long id) {
        Orders ordersDB = getOrderOrThrow(id);
        if (!Orders.DELIVERY_IN_PROGRESS.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.COMPLETED)
                .deliveryTime(LocalDateTime.now())
                .build();
        orderMapper.update(orders);
        orderCacheService.evictAfterCommit(ordersDB.getId());
    }

    private Orders getOrderOrThrow(Long id) {
        if (id == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        return orders;
    }

    /**
     * 已支付订单被取消或拒单时先退款，再把支付状态标记为已退款
     */
    private void refundIfPaid(Orders ordersDB, Orders ordersToUpdate) {
        if (!Orders.PAID.equals(ordersDB.getPayStatus())) {
            return;
        }
        if (weChatPayEnabled) {
            try {
                //商户退款单号在同一商户下不能重复，这里用订单号加后缀保证唯一
                weChatPayUtil.refund(ordersDB.getNumber(),
                        ordersDB.getNumber() + "R",
                        ordersDB.getAmount(),
                        ordersDB.getAmount());
            } catch (Exception ex) {
                log.error("微信退款失败，订单号：{}", ordersDB.getNumber(), ex);
                throw new OrderBusinessException(MessageConstant.REFUND_FAILED);
            }
        } else {
            log.info("未开启微信支付，订单{}直接标记为已退款", ordersDB.getNumber());
        }
        ordersToUpdate.setPayStatus(Orders.REFUND);
    }

    @Override
    @Transactional
    public OrderVO getOrderDetail(Long id){
        Long currentUserId = BaseContext.getCurrentId();
        OrderVO orderVO = orderCacheService.query(id, () -> loadOrderDetail(id));
        // 命中缓存后仍校验订单归属，缓存不能绕过用户数据权限。
        if (orderVO == null || !currentUserId.equals(orderVO.getUserId())) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        return orderVO;
    }
    @Override
    @Transactional
    public void cancelOrder(Long id){
        Long userId = BaseContext.getCurrentId();
        Orders ordersDB = orderMapper.getByIdAndUserId(id, userId);

        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        if (!Orders.PENDING_PAYMENT.equals(ordersDB.getStatus())
                && !Orders.TO_BE_CONFIRMED.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = Orders.builder()
                .id(id)
                .status(Orders.CANCELLED)
                .cancelReason("用户取消")
                .cancelTime(LocalDateTime.now())
                .build();

        //已支付订单取消时先退款，再把支付状态标记为已退款
        refundIfPaid(ordersDB, orders);

        orderMapper.update(orders);
        orderCacheService.evictAfterCommit(ordersDB.getId());
    }

    @Override
    @Transactional
    public void repetition(Long id){
        Long userId = BaseContext.getCurrentId();
        Orders ordersDB = orderMapper.getByIdAndUserId(id, userId);

        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);
        if (orderDetailList == null || orderDetailList.isEmpty()) {
            throw new OrderBusinessException("订单明细不存在");
        }

        for (OrderDetail orderDetail : orderDetailList) {
            ShoppingCart shoppingCart = ShoppingCart.builder()
                    .name(orderDetail.getName())
                    .image(orderDetail.getImage())
                    .userId(userId)
                    .dishId(orderDetail.getDishId())
                    .setmealId(orderDetail.getSetmealId())
                    .dishFlavor(orderDetail.getDishFlavor())
                    .number(orderDetail.getNumber())
                    .amount(orderDetail.getAmount())
                    .createTime(LocalDateTime.now())
                    .build();
            shoppingCartMapper.insert(shoppingCart);
        }
    }
    @Override
    public void reminder(Long id){
        Orders orders=new Orders();
        orders=orderMapper.getById(id);
        if(orders==null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if(orders.getStatus()!=Orders.REFUND){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        Map map=new HashMap();
        map.put("type",2);
        map.put("orderId",id);
        map.put("content","订单号:"+orders.getNumber());
        String msg=JSON.toJSONString(map);
        webSocketServer.sendToAllClient(msg);
    }
}
