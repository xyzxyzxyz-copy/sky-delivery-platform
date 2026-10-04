package com.sky.mapper;

import com.sky.entity.DishFlavor;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DishFlavorMapper {

     void insertBatch(List<DishFlavor> dishFlavorList);
    @Delete("delete from dish_flavor where dish_id=#{id}")
    void deleteByDishId(Long id);

    /**
     * 根据菜品id集合批量删除口味数据
     *
     * @param ids 菜品id集合
     */
    void deleteByDishIds(@Param("ids") List<Long> ids);
    @Select("select * from dish_flavor where dish_id=#{id}")
     List<DishFlavor> getByDishId(Long id);
}
