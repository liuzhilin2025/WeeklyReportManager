package com.practice.weeklyreportmanager.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j  // ★ 加上这个注解
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 处理业务异常
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("业务异常：{}", e.getMessage());  // ★ 加一行 warn 日志
        return Result.error(400, e.getMessage());
    }

    // 处理参数校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("参数校验失败：{}", message);  // ★ 加一行 warn 日志
        return Result.error(400, message);
    }

    // 处理静态资源/路径不存在（如浏览器请求 favicon.ico）
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("资源不存在：{}", e.getResourcePath());
        return Result.error(404, "资源不存在");
    }

    // 系统异常的处理
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常，错误类型：{}，错误信息：{}", e.getClass().getName(), e.getMessage(), e);
        return Result.error(500, "系统异常，请稍后重试");
    }
}
