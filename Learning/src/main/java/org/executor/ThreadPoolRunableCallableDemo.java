package org.executor;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池 Runnable + Callable 生产级完整示例
 *
 * 核心改动点（相比纯 demo 版）：
 *  1. 自定义 ThreadFactory —— 线程统一命名，方便 jstack / 监控平台识别
 *  2. Future.get() 必须带超时 —— 防止下游卡死时主线程永久阻塞
 *  3. InterruptedException 处理规范 —— catch 后必须重置中断标志
 *  4. ExecutionException 的 getCause() 做 null 防御
 */
public class ThreadPoolRunableCallableDemo {

    // ----------------------------------------------------------------
    // 1. 自定义线程工厂：给线程起有意义的名字
    //    生产中名字通常带业务模块，例如 "order-pool-1"、"report-pool-2"
    // ----------------------------------------------------------------
    static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        // 原子计数器，保证多线程并发创建时编号唯一
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            // 设为守护线程，JVM 退出时不等待该线程跑完
            // 如果任务必须跑完再退出，改为 false（默认也是 false，这里显式写出供参考）
            t.setDaemon(false);
            return t;
        }
    }

    // ----------------------------------------------------------------
    // 2. Runnable 任务：无返回值
    // ----------------------------------------------------------------
    static class MyRunnableTask implements Runnable {
        @Override
        public void run() {
            System.out.println("Runnable 任务执行，线程：" + Thread.currentThread().getName());
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                // 规范写法：捕获后必须重置中断标志，否则调用栈上层感知不到中断
                Thread.currentThread().interrupt();
            }
        }
    }

    // ----------------------------------------------------------------
    // 3. Callable 任务：有返回值，可抛出受检异常
    // ----------------------------------------------------------------
    static class MyCallableTask implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            System.out.println("Callable 任务执行，线程：" + Thread.currentThread().getName());
            TimeUnit.MILLISECONDS.sleep(800);
            return 300;
        }
    }

    public static void main(String[] args) {

        // ----------------------------------------------------------------
        // 4. 手动构建线程池（生产标准写法，禁止用 Executors.newFixedThreadPool 等）
        //    原因：Executors 工厂方法使用无界队列，队列堆积会打爆内存
        // ----------------------------------------------------------------
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
                2,                                      // 核心线程数：常驻线程，即使空闲也不销毁
                5,                                      // 最大线程数：队列满后才会扩到这里
                10L,                                    // 超过核心数的线程空闲多久后销毁
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),           // 有界队列，防止无限堆积
                new NamedThreadFactory("demo-pool"),    // ← 改动点1：命名线程工厂
                new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：队列满且达到最大线程时，
                                                          // 由提交任务的线程自己执行，起到降速效果
        );

        // ================================================================
        // 场景一：execute(Runnable) —— 提交无返回值任务，无法感知异常
        // ================================================================
        System.out.println("===== execute(Runnable) =====");
        threadPool.execute(new MyRunnableTask());
        threadPool.execute(() ->
            System.out.println("Lambda Runnable 执行，线程：" + Thread.currentThread().getName())
        );

        // ================================================================
        // 场景二：submit(Runnable) —— 返回 Future，可感知执行完成
        // ================================================================
        System.out.println("\n===== submit(Runnable) =====");
        Future<?> runFuture = threadPool.submit(new MyRunnableTask());
        try {
            // ← 改动点2：必须设置超时，防止下游永久阻塞
            //   超时时间根据业务 SLA 设置，这里示例 3 秒
            Object result = runFuture.get(3, TimeUnit.SECONDS);
            System.out.println("submit(Runnable) 完成，返回值（固定 null）：" + result);
        } catch (TimeoutException e) {
            // 超时：取消任务，true 表示允许中断正在执行的线程
            runFuture.cancel(true);
            System.err.println("submit(Runnable) 超时，已取消");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // ← 改动点3：重置中断标志
            System.err.println("等待结果时线程被中断");
        } catch (ExecutionException e) {
            // 任务内部抛出的异常被包装在这里，getCause() 拿到原始异常
            Throwable cause = e.getCause();
            System.err.println("任务执行异常：" + (cause != null ? cause.getMessage() : e.getMessage()));
        }

        // ================================================================
        // 场景三：submit(Callable) —— 有返回值，是最常用的生产写法
        // ================================================================
        System.out.println("\n===== submit(Callable) =====");
        Future<Integer> callFuture = threadPool.submit(new MyCallableTask());
        try {
            // ← 改动点2：同样必须带超时
            Integer res = callFuture.get(3, TimeUnit.SECONDS);
            System.out.println("Callable 返回结果：" + res);
        } catch (TimeoutException e) {
            callFuture.cancel(true);
            System.err.println("Callable 超时，已取消");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("等待 Callable 结果时线程被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            System.err.println("Callable 任务异常：" + (cause != null ? cause.getMessage() : e.getMessage()));
        }

        // ================================================================
        // 关闭线程池（两段式关闭，生产标准写法）
        // ================================================================
        threadPool.shutdown(); // 不再接受新任务，等待队列中的任务跑完
        try {
            // 等待最多 5 秒
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                // 超时仍未结束，强制中断所有正在执行的线程
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt(); // ← 改动点3：重置中断标志
        }

        System.out.println("线程池已关闭");
    }
}
