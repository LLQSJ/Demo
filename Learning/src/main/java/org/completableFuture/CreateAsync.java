package org.completableFuture;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

public class CreateAsync {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        // 注意这块只是我自己演的CompletableFuture的用法，实际开发当中必须要自己传入自定义线程池
        // 如果使用默认的线程池：
        // 1.它的默认核心线程数是cpu-1
        // 2.它是一个共用线程池，不仅仅是对CompletableFuture使用，其他线程也会使用
        // 3.虽然它有自动扩容机制但是仅限于调用ManagedBlocker接口的时候，才会为了避免饥饿而扩容，但实际并没有调用这个接口
        // 无返回值，使用公共ForkJoinPool
        CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
            System.out.println("Task1 run in: " + Thread.currentThread().getName());
        });

        // 有返回值，自定义线程池
        var executor = Executors.newFixedThreadPool(2);
        CompletableFuture<String> future2 = CompletableFuture.supplyAsync(() -> {
            return "Result from " + Thread.currentThread().getName();
        }, executor);

        // 获取结果并输出
        future1.join();  // 等待无返回值任务结束
        // 虽然CompletableFuture比Future的优点就在于不会阻塞线程，但是如果使用get/join还是会阻塞线程
        System.out.println(future2.join()); // 输出返回值

        executor.shutdown();
    }
}