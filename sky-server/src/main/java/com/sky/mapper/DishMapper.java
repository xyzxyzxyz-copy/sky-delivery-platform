package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AuToFill;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.enumeration.OperationType;
import com.sky.vo.DishVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface DishMapper {

    /**
     * 根据分类id查询菜品数量
     * @param categoryId
     * @return
     */
    @Select("select count(id) from dish where category_id = #{categoryId}")
    Integer countByCategoryId(Long categoryId);
    @AuToFill(OperationType.INSERT)
    void insert(Dish dish);

    Page<DishVO> page(DishPageQueryDTO dishPageQueryDTO);
    @Select("select * from dish where id=#{id}")
    Dish getById(Long id);
    @Delete("delete from dish where id=#{id}")
    void delete(Long id);

    /**
     * 根据菜品id集合批量删除菜品
     *
     * @param ids 菜品id集合
     */
    void deleteByIds(@Param("ids") List<Long> ids);
    @AuToFill(value = OperationType.UPDATE)
    void update(Dish dish);

    List<Dish> list(Dish dish);
    Integer countByMap(Map map);
}
