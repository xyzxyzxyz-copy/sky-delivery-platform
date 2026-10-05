package com.sky.mapper;

import com.sky.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("select * from user where openid=#{openId}")
    User getUserByopenId(String openId);

    /**
     * 根据用户id查询用户
     *
     * @param id
     * @return
     */
    @Select("select * from user where id=#{id}")
    User getById(Long id);

    void insert(User user);
}
