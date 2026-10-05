package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MessageConstant;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.exception.LoginFailedException;
import com.sky.mapper.UserMapper;
import com.sky.properties.WeChatProperties;
import com.sky.service.UserService;
import com.sky.utils.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class UserServiceImpl implements UserService {
    public static final String WX_LOGIN="https://api.weixin.qq.com/sns/jscode2session";
    //微信接口返回的openid字段名(全小写)，注意不要写成openId
    public static final String WX_OPENID="openid";
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatProperties weChatProperties;
    @Override
    public User weChatLogin(UserLoginDTO userLoginDTO){
        String openId=getopenId(userLoginDTO.getCode());
        if(openId==null){
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }
        //根据openid查询用户，查不到说明是新用户，需要自动注册
        User user=userMapper.getUserByopenId(openId);
        if(user==null){
            //注意：此处必须new一个User对象，否则下面set会空指针
            user=new User();
            user.setOpenid(openId);
            user.setCreateTime(LocalDateTime.now());
            userMapper.insert(user);
        }
        return user;
    }
    public String getopenId(String code){
        Map<String, String> map=new HashMap<>();
        map.put("appid",weChatProperties.getAppid());
        map.put("secret",weChatProperties.getSecret());
        map.put("js_code",code);
        map.put("grant_type","authorization_code");
        String json=HttpClientUtil.doGet(WX_LOGIN,map);
        //打印微信接口原始返回，便于排查(失败时微信会返回errcode/errmsg)
        log.info("微信登录接口返回结果：{}",json);
        if(json==null || json.isEmpty()){
            return null;
        }
        JSONObject jsonObject=JSON.parseObject(json);
        //微信返回的字段名是全小写的openid
        String openId=jsonObject.getString(WX_OPENID);
        log.info("获取到的openid：{}",openId);
        return openId;
    }
}
