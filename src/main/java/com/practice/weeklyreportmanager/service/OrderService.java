package com.practice.weeklyreportmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 订单服务。
 *
 * 这里演示的是「分布式锁」的经典用法：在高并发 / 多实例部署场景下，
 * 用 Redis（通过 Redisson 客户端）保证同一用户的"创建订单"操作在同一时刻
 * 只能被一个线程执行，避免重复下单等问题。
 *
 * 核心思路：
 *   1. 以业务唯一标识（这里是 userId）拼出锁的 Key；
 *   2. 抢到锁的线程才允许执行下单逻辑（临界区）；
 *   3. 执行完必须在 finally 中释放锁，防止异常时锁被一直占用。
 */
@Service
@Slf4j
public class OrderService {

    /**
     * Redisson 客户端，由 Spring 注入。
     * RedissonClient 是 Redisson 对外提供的主要入口，getLock() 返回分布式锁对象。
     */
    @Autowired
    private RedissonClient redissonClient;

    /**
     * 创建订单（对同一用户加分布式锁，防止重复下单）。
     *
     * 说明：
     * - 不同用户拥有不同 Key 的锁，互不影响，可并行下单；
     * - 同一用户并发请求时，只有一个请求能拿到锁，其余请求会阻塞等待。
     *
     * @param userId 用户唯一标识，用于区分不同用户的订单
     */
    public void createOrder(String userId) {
        // ① 创建锁对象：锁的 Key = "order:lock:" + userId
        //    锁的 Key 必须带业务唯一标识，这样锁只影响同一用户的并发，
        //    而不是把不同用户的下单操作也串行化。
        //    注意：此时只是"创建/获取锁句柄"，还没有真正加锁。
        RLock lock = redissonClient.getLock("order:lock:" + userId);

        try {
            // ② 尝试加锁（阻塞等待，直到拿到锁为止）
            //    lock.lock() 的默认行为：
            //    - 若锁空闲 → 立即加锁成功；
            //    - 若锁已被其他线程持有 → 阻塞等待；
            //    - 加锁成功后开启"看门狗(watchdog)"自动续期机制，
            //      默认 leaseTime 30 秒，只要业务没执行完就会不断续期，
            //      避免业务超时后锁被 Redis 自动删除而失效。release 前不会过期。

            //    如果不想无限期等待，可改用带参数的版本，例如：
            //    lock.tryLock(10, 30, TimeUnit.SECONDS);
            //    含义：最多等待 10 秒获取锁；拿到锁后 30 秒内不续期自动释放。
            lock.lock(); // 默认 30 秒自动续期，直到手动 unlock

            // ③ 业务逻辑（临界区）：只有拿到锁的线程才会执行到这里
            log.info("用户 {} 正在创建订单", userId);
            Thread.sleep(5000); // 模拟耗时操作（如查库存、写数据库等）

        } catch (InterruptedException e) {
            // Thread.sleep() 被打断时（例如线程被 shutdown）抛出该异常。
            // 标准做法：重新设置中断标志位，让上层逻辑感知线程被中断，
            // 而不是把异常吞掉。这里只是演示，实际业务可根据需要记录日志或抛错。
            Thread.currentThread().interrupt();
        } finally {
            // ④ 释放锁（必须放在 finally 中保证一定执行）
            //    如果不释放，锁会被当前线程一直持有（看门狗持续续期），
            //    导致其他线程（即使是同一用户的下一次请求）永远拿不到锁，造成死锁。
            //    isHeldByCurrentThread() 先判断锁是否由当前线程持有，
            //    防止解锁时抛 IllegalMonitorStateException。
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("用户 {} 的锁已释放", userId);
            }
        }
    }
}
