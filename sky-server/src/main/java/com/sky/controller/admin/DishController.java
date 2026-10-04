package com.sky.controller.admin;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.mapper.DishMapper;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Delete;
import org.apache.xmlbeans.impl.xb.xsdschema.Public;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/admin/dish")
public class DishController {
    @Autowired
    private DishService dishService;
    @PostMapping
    @CacheEvict(cacheNames = "dishCache",key = "#dishDTO.categoryId")
    public Result save(@RequestBody DishDTO dishDTO){
        log.info("新增菜品:{}",dishDTO);
        dishService.saveWithFlavor(dishDTO);
        return Result.success();
    }
    @GetMapping("/page")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO){
        log.info("分页查询");
            PageResult pageResult=dishService.page(dishPageQueryDTO);
            return Result.success(pageResult);
    }
    @DeleteMapping
    @CacheEvict(cacheNames = "dishCache",allEntries = true)
    public Result delete(@RequestParam List<Long> ids){
        log.info("删除菜品");
        dishService.deleteBatch(ids);
        return Result.success();
    }
    @GetMapping("/{id}")
    public  Result<DishVO> getById(@PathVariable Long id){
        DishVO dishVO=dishService.getById(id);
        return Result.success(dishVO);
    }
    @PutMapping
    @CacheEvict(cacheNames = "dishCache",allEntries = true)
    public Result update(@RequestBody DishDTO dishDTO){
        dishService.updateDishWithFlavor(dishDTO);
        return Result.success();
    }
    @PostMapping("/status/{status}")
    @CacheEvict(cacheNames = "dishCache",allEntries = true)
    public Result updateStatus(@PathVariable Integer status,Long id){
        dishService.updateStatus(status,id);
        return Result.success();
    }
    @GetMapping("/list")
    public Result<List<Dish>> list(Long categoryId){
        List<Dish> dishes=dishService.list(categoryId);
        return Result.success(dishes);
    }
}
