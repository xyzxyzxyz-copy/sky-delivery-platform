package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetMealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetMealService;
import com.sky.vo.DishItemVO;
import com.sky.vo.SetmealVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
public class SetMealServiceImpl implements SetMealService {
    @Autowired
    private SetMealMapper setmealMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Override
    @Transactional
    public void save(SetmealDTO setmealDTO){
        Setmeal setmeal=new Setmeal();
        BeanUtils.copyProperties(setmealDTO,setmeal);
        setmeal.setStatus(StatusConstant.DISABLE);
        //1、保存套餐基本信息，主键回填到 setmeal.id
        setmealMapper.insert(setmeal);
        //2、保存套餐与菜品的关联关系
        List<SetmealDish> setmealDishes=setmealDTO.getSetmealDishes();
        if(setmealDishes!=null && !setmealDishes.isEmpty()){
            setmealDishes.forEach(item->item.setSetmealId(setmeal.getId()));
            setmealDishMapper.insertBatch(setmealDishes);
        }
    }
    @Override
    public PageResult page(SetmealPageQueryDTO setmealPageQueryDTO){
        PageHelper.startPage(setmealPageQueryDTO.getPage(),setmealPageQueryDTO.getPageSize());
        Page<Setmeal> page=setmealMapper.PageQuery(setmealPageQueryDTO);

        return new PageResult(page.getTotal(),page.getResult());
    }
    @Override
    @Transactional
    public void deleteBatch(List<Long> ids){
        if(ids==null || ids.isEmpty()){
            return;
        }
        //1、判断是否存在起售中的套餐
        for (Long id : ids) {
            Setmeal setmeal=setmealMapper.getById(id);
            if(setmeal!=null && StatusConstant.ENABLE.equals(setmeal.getStatus())){
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        }
        //2、批量删除套餐及其关联的套餐菜品关系数据
        setmealMapper.deleteById(ids);
        setmealDishMapper.deleteBySetmealId(ids);
    }
    @Override
    public SetmealVO getById(Long id){
        //1、查询套餐基本信息
        Setmeal setmeal=setmealMapper.getById(id);
        //2、查询套餐关联的菜品，用于修改页面回显
        List<SetmealDish> setmealDishes=setmealDishMapper.getBySetmealId(id);
        SetmealVO setmealVO=new SetmealVO();
        BeanUtils.copyProperties(setmeal,setmealVO);
        setmealVO.setSetmealDishes(setmealDishes);
        return setmealVO;
    }
    @Override
    @Transactional
    public void update(SetmealDTO setmealDTO){
        Setmeal setmeal=new Setmeal();
        BeanUtils.copyProperties(setmealDTO,setmeal);
        //1、修改套餐基本信息
        setmealMapper.update(setmeal);
        //2、删除原有的套餐菜品关联关系
        Long setmealId=setmealDTO.getId();
        setmealDishMapper.deleteBySetmealId(Collections.singletonList(setmealId));
        //3、重新插入新的套餐菜品关联关系
        List<SetmealDish> setmealDishes=setmealDTO.getSetmealDishes();
        if(setmealDishes!=null && !setmealDishes.isEmpty()){
            setmealDishes.forEach(item->item.setSetmealId(setmealId));
            setmealDishMapper.insertBatch(setmealDishes);
        }
    }
    @Override
    public void startOrStop(Integer status, Long id){
        Setmeal setmeal=new Setmeal();
        setmeal.setId(id);
        setmeal.setStatus(status);
        setmealMapper.update(setmeal);
    }
    @Override
    public List<Setmeal> list(Setmeal setmeal){
        return setmealMapper.list(setmeal);
    }
    @Override
    public List<DishItemVO> getDishItemById(Long id){
        return setmealMapper.getDishItemBySetmealId(id);
    }
}
