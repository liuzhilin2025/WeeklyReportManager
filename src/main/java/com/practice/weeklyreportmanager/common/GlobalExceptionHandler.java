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

    // 依赖的外部服务不可用（如 AI 服务连不上、鉴权失败）：用 503 而不是 500，
    // 否则下面那个兜底的 Exception 处理器会把「服务暂时不可用」这条对用户有用的提示吞掉，只回一句"系统异常"
    @ExceptionHandler(IllegalStateException.class)
    public Result<Void> handleIllegalStateException(IllegalStateException e) {
        log.error("依赖服务不可用：{}", e.getMessage(), e);
        return Result.error(503, e.getMessage() != null ? e.getMessage() : "服务暂时不可用，请稍后重试");
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
