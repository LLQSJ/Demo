package org.completableFuture;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class BasicMain {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        fututerTest();
        runAsyncAndSupplyAsyncTest();
        anyFutureTest();
        allFuturesTest();
        thenRunTest();
        thenAcceptTest();
        System.out.println(thenApplyTest());
        whenCompleteTest();
        handleTest();
    }

    private static void fututerTest() throws ExecutionException, InterruptedException {
        FutureTask<String> futureTask = new FutureTask<>(new Callable<String>() {
            @Override
            public String call() throws Exception {
                Thread.sleep(2000);
                return "futureTask1 执行失败";
            }
        });
        new Thread(futureTask).start();
        //GET()方法的作用是，以阻塞得方式，获取任务执行结果
        System.out.println(futureTask.get());
        System.out.println("TOOD...");
    }

    private static void runAsyncAndSupplyAsyncTest() throws ExecutionException, InterruptedException {
        //没有返回值
        CompletableFuture future1 =  CompletableFuture.runAsync(()->{
            System.out.println("执行没有返回值得任务");
        });
        //有返回值
        CompletableFuture<String> future2 = CompletableFuture.supplyAsync(()->{
            return "执行有返回值得任务";
        });
        //有泛型得
        // 1，规范、安全、不翻车、好维护
        // 简单来说，写法跑起来功能一模一样，区别只在: 安全不安全、会不会埋坑、能不能提前查错
        System.out.println(future1.get());
        System.out.println(future2.join());
    }
//    返回跑的最快得那个Future，最快的如果异常都玩完 例如三方Api提供多个接口，可是使用这个
    private static void anyFutureTest(){
        CompletableFuture<String> future1 = CompletableFuture.supplyAsync(()-> "朋友小王去你家送药");
        CompletableFuture<String> future2 = CompletableFuture.supplyAsync(()-> "朋友小张去你家送药");

        CompletableFuture<Object> anyFuture = CompletableFuture.anyOf(future1, future2);

        System.out.println(anyFuture.join());
    }
//    全部并行执行，如果需要获得返回值，需要配合thenApply，异常会抛出不影响其他任务
    private static void allFuturesTest() {
        CompletableFuture<Integer> future1 = CompletableFuture.supplyAsync(()-> 15);
        CompletableFuture<Integer> future2 = CompletableFuture.supplyAsync(()-> 10);

        CompletableFuture<Integer> allFuture = CompletableFuture.allOf(future1, future2)
                .thenApply(res -> {
                    return future1.join() + future2.join();
                });

        System.out.println(allFuture.join());
    }
//    不能接受上一个返回结果，也不能传入参数
    private static void thenRunTest() {
        CompletableFuture future1 = CompletableFuture.supplyAsync(()-> {
            System.out.println("执行第一个任务");
            try {
                Thread.sleep(1000); //主线程跑完了，异步线程还没醒来，如果不使用join后面两个任务不会执行
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return 0;
        }).thenRun(() -> {
            System.out.println("执行第二个任务");
        }).thenRun(()->{
            System.out.println("执行第三个任务");
        });
        System.out.println("主线程-前置");
        future1.join();
        System.out.println("主线程-后置");
    }
//    能接受上一个返回结果，自己不能返回结果
    private static void thenAcceptTest() {
        CompletableFuture future1 = CompletableFuture.supplyAsync(() -> 5)
                .thenAccept(result -> {
                    System.out.println("任务执行结果 = " + result * 3);
                }).thenAccept(result -> {
                    System.out.println("任务执行完成");
                });
    }
//    可以接收到上一个返回结果，自己能返回
    private static Integer thenApplyTest() {
        CompletableFuture<Integer> future1 = CompletableFuture.supplyAsync(() -> 5)
                .thenApply(result -> result * 3)
                .thenApply(result -> result + 3);

        return future1.join();
    }

//    和thenAccept类似，可以接收到上一个返回结果，自己不能返回,区别在于thenAccept出现异常直接中断，它可以继续执行且抛出当前异常
    private static void whenCompleteTest() {
        CompletableFuture<Integer> future1 = CompletableFuture.supplyAsync(() -> {
            System.out.println(111);
            return 9/0;
        }).whenComplete((result, throwable) -> {
            System.out.println(222);
        }).whenComplete((result, throwable) -> {
            System.out.println(333);
        });
        System.out.println(future1.join());
    }
//    等同于thenApply(),可以接收上一个任务的结果，自己也可以返回结果，出现错误不影响继续执行。
    private static void handleTest() {
        CompletableFuture<String> future2 = CompletableFuture.supplyAsync(()->{
            return "返回任务结果";
        });
        CompletableFuture<String> future2Callback = future2.handle((result, throwbale) -> {
            return "返回回调结果";
        });
        System.out.println(future2Callback.join());
    }
}
