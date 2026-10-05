package com.sky.controller.admin;

//import com.sky.config.RedisConfiguration;
import com.sky.result.Result;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

@RestController("adminShopController")
@RequestMapping("/admin/shop")
@Slf4j
public class ShopController {
    private final String Key="SHOP_STATUS";
    @Autowired
    private RedisTemplate redisTemplate;
    @PutMapping("/{status}")
    @ApiOperation("设置店铺状态")
    public Result setStatue(@PathVariable Integer status){
        log.info("店铺状态设置为:{}",status==1?"营业":"打样");
        redisTemplate.opsForValue().set(Key,status);
        return Result.success();
    }
    @GetMapping("/status")
    @ApiOperation("查询店铺状态")
    public Result<Integer> getStatus(){
        Integer status=(Integer) redisTemplate.opsForValue().get(Key);
        return Result.success(status);
    }
}
