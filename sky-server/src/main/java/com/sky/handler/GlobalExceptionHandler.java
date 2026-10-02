package com.sky.handler;

import com.sky.constant.MessageConstant;
import com.sky.exception.BaseException;
import com.sky.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;

/**
 * 全局异常处理器，处理项目中抛出的业务异常
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 捕获业务异常
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result exceptionHandler(BaseException ex){
        log.error("异常信息：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }
    @ExceptionHandler
    public Result exceptionHandler(SQLIntegrityConstraintViolationException sqlIntegrityConstraintViolationException){
        String n=sqlIntegrityConstraintViolationException.getMessage();
        //打印真实的数据库异常信息，便于排查（例如：Column 'xxx' cannot be null）
        log.error("SQL完整性约束异常信息：{}", n);
        String msg="";
        if(n!=null && n.contains("Duplicate entry")){
            String[] str=n.split(" ");
            msg="用户"+str[2]+ MessageConstant.ALREADY_EXISTS;

        }else{
            //非重复键冲突时返回原始错误信息，避免前端只看到 code=0、msg 为空
            msg=n==null?"数据库操作异常":n;
        }
        return Result.error(msg);
    }
}
