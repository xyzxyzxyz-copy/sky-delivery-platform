package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishPageQueryDTO;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.dto.DishDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class DishServiceImpl implements DishService {
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Override
    @Transactional
    public  void saveWithFlavor(DishDTO dishDTO){
        Dish dish=new Dish();
        BeanUtils.copyProperties(dishDTO,dish);
        dishMapper.insert(dish);
        Long dishId=dish.getId();
        List<DishFlavor> dishFlavorList=dishDTO.getFlavors();
        if(dishFlavorList!=null && dishFlavorList.size()>0){
            dishFlavorList.forEach(item->{
                item.setDishId(dishId);
            });
            dishFlavorMapper.insertBatch(dishFlavorList);
        }
    }
    @Override
    public PageResult page(DishPageQueryDTO dishPageQueryDTO){
        PageHelper.startPage(dishPageQueryDTO.getPage(),dishPageQueryDTO.getPageSize());
        Page<DishVO> page= dishMapper.page(dishPageQueryDTO);
        return new PageResult(page.getTotal(),page.getResult());
    }
    @Override
    @Transactional
    public  void deleteBatch(List<Long> ids){
        if(ids==null || ids.isEmpty()){
            return;
        }
        //1、判断是否存在起售中的菜品
        for (Long id : ids) {
            Dish dish=dishMapper.getById(id);
            if(dish!=null && StatusConstant.ENABLE.equals(dish.getStatus())){
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }
        //2、判断菜品是否被套餐关联
        List<Long> setmealIds=setmealDishMapper.getSetmealIdsByDishIds(ids);
        if(setmealIds!=null && !setmealIds.isEmpty()){
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }
        //3、批量删除菜品及其关联的口味数据
        dishMapper.deleteByIds(ids);
        dishFlavorMapper.deleteByDishIds(ids);

    }
    @Override
    public DishVO getById(Long id){
        Dish dish=dishMapper.getById(id);
        List<DishFlavor> dishFlavor=dishFlavorMapper.getByDishId(id);
        DishVO dishVO=new DishVO();
        BeanUtils.copyProperties(dish,dishVO);
        dishVO.setFlavors(dishFlavor);
        return dishVO;
    }
    @Transactional
    @Override
    public void updateDishWithFlavor(DishDTO dishDTO){
        Dish dish=new Dish();
        BeanUtils.copyProperties(dishDTO,dish);
        dishMapper.update(dish);
        dishFlavorMapper.deleteByDishId(dishDTO.getId());
        List<DishFlavor> dishFlavorList=dishDTO.getFlavors();
        if(dishFlavorList!=null && dishFlavorList.size()>0){
            dishFlavorList.forEach(item->{
                item.setDishId(dishDTO.getId());
            });
            dishFlavorMapper.insertBatch(dishFlavorList);
        }
    }
    @Override
    public  void updateStatus(Integer status, Long id){
        Dish dish=new Dish();
        dish.setStatus(status);
        dish.setId(id);
        dishMapper.update(dish);
    }
    @Override
    public List<Dish> list(Long categoryId){
        Dish dish=new Dish();
        dish.setStatus(StatusConstant.ENABLE);
        dish.setCategoryId(categoryId);
        return dishMapper.list(dish);
    }
    @Override
    public List<DishVO> listWithFlavor(Dish dish){
        List<Dish> dishList=dishMapper.list(dish);
        List<DishVO> dishVOList=new ArrayList<>();
        for (Dish d : dishList) {
            DishVO dishVO=new DishVO();
            BeanUtils.copyProperties(d,dishVO);
            //根据菜品id查询对应的口味
            List<DishFlavor> flavors=dishFlavorMapper.getByDishId(d.getId());
            dishVO.setFlavors(flavors);
            dishVOList.add(dishVO);
        }
        return dishVOList;
    }
}
