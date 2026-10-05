package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetMealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetMealMapper setmealMapper;
    @Override
    public void add(ShoppingCartDTO shoppingCartDTO){
        ShoppingCart shoppingCart=new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO,shoppingCart);
        Long UserId= BaseContext.getCurrentId();
        shoppingCart.setUserId(UserId);
        List<ShoppingCart> list=shoppingCartMapper.list(shoppingCart);
        if(list!=null&&list.size()>0){
            ShoppingCart cart=list.get(0);
            cart.setNumber(cart.getNumber()+1);
            shoppingCartMapper.updateNumberById(cart);
        }else {
            Long dishId=shoppingCartDTO.getDishId();
            if(dishId!=null){
                Dish dish=dishMapper.getById(dishId);
                shoppingCart.setAmount(dish.getPrice());
                shoppingCart.setName(dish.getName());
                shoppingCart.setImage(dish.getImage());

            }else {
                Setmeal setmeal=setmealMapper.getById(shoppingCartDTO.getSetmealId());
                shoppingCart.setAmount(setmeal.getPrice());
                shoppingCart.setName(setmeal.getName());
                shoppingCart.setImage(setmeal.getImage());
            }
            shoppingCart.setNumber(1);
            shoppingCart.setCreateTime(LocalDateTime.now());
            shoppingCartMapper.insert(shoppingCart);
        }
    }
    @Override
    public List<ShoppingCart> list(){
        ShoppingCart shoppingCart=new ShoppingCart();
        shoppingCart.setUserId(BaseContext.getCurrentId());

        return shoppingCartMapper.list(shoppingCart);

    }
    @Override
    public  void clean(){
        shoppingCartMapper.clean(BaseContext.getCurrentId());
    }
    @Override
    public void sub(ShoppingCartDTO shoppingCartDTO){
        ShoppingCart shoppingCart=new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO,shoppingCart);
        Long UserId= BaseContext.getCurrentId();
        shoppingCart.setUserId(UserId);
        List<ShoppingCart> list=shoppingCartMapper.list(shoppingCart);
        if(list!=null&&list.size()>0){
            ShoppingCart cart=list.get(0);
            if(cart.getNumber()==1){
                shoppingCartMapper.deleteByid(cart.getId());
            }else{
                cart.setNumber(cart.getNumber()-1);
                shoppingCartMapper.updateNumberById(cart);
            }
        }

    }
}
