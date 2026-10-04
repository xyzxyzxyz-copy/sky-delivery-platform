package com.sky.service;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.vo.DishVO;

import java.util.List;

public interface DishService {
    void saveWithFlavor(DishDTO dishDTO);

    PageResult page(DishPageQueryDTO dishPageQueryDTO);

    void deleteBatch(List<Long> ids);

    DishVO getById(Long id);

    void updateDishWithFlavor(DishDTO dishDTO);

    void updateStatus(Integer status, Long id);

    /**
     * 根据分类id查询菜品（管理端新增套餐时使用）
     * @param categoryId
     * @return
     */
    List<Dish> list(Long categoryId);

    /**
     * 条件查询菜品和口味（C端商品浏览）
     * @param dish
     * @return
     */
    List<DishVO> listWithFlavor(Dish dish);
}
