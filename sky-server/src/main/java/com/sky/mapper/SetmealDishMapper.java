package com.sky.mapper;

import com.sky.entity.SetmealDish;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SetmealDishMapper {

    /**
     * 根据菜品id集合查询关联的套餐id集合
     *
     * @param ids 菜品id集合
     * @return 关联的套餐id集合
     */
    List<Long> getSetmealIdsByDishIds(@Param("ids") List<Long> ids);

    /**
     * 批量插入套餐与菜品的关联关系
     *
     * @param setmealDishes 套餐菜品关系集合
     */
    void insertBatch(List<SetmealDish> setmealDishes);

    /**
     * 根据套餐id查询套餐和菜品的关联关系
     *
     * @param setmealId 套餐id
     * @return 套餐菜品关联集合
     */
    List<SetmealDish> getBySetmealId(Long setmealId);

    /**
     * 根据套餐id集合批量删除套餐和菜品的关联关系
     *
     * @param ids 套餐id集合
     */
    void deleteBySetmealId(@Param("ids") List<Long> ids);
}
