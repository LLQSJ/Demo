package org.completableFuture;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * CompletableFuture 实战场景汇总
 *
 * 场景列表：
 *  1. 文件上传 —— 并行上传多个文件，全部完成后返回结果
 *  2. Excel多Sheet导入 —— 并行解析多个Sheet，汇总校验结果
 *  3. 聚合查询 —— 并行调多个接口/查多张表，合并结果（商品详情页常见）
 *  4. 异步任务链 —— 上传完成后异步发通知（thenApply / thenAccept / thenCompose）
 *  5. 任一完成即返回 —— anyOf，取最快的CDN节点
 *  6. 异常处理 —— exceptionally / handle
 */
public class main {

    // 生产环境必须用自定义线程池，不要用默认 ForkJoinPool
    private static final ExecutorService POOL = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            new ThreadFactory() {
                int count = 0;
                @Override public Thread newThread(Runnable r) {
                    return new Thread(r, "biz-async-" + count++);
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时调用方线程兜底执行，不丢任务
    );

    public static void main(String[] args) throws Exception {
        scene1_parallelFileUpload();
        scene2_excelMultiSheetImport();
        scene3_aggregateQuery();
        scene4_asyncChain();
        scene5_anyOf_fastestCDN();
        scene6_exceptionHandling();

        POOL.shutdown();
    }

    // =========================================================
    // 场景1：并行上传多个文件，全部完成后统一返回上传结果
    // 实际场景：用户一次选了5个附件上传，串行太慢，并行打满带宽
    // =========================================================
    static void scene1_parallelFileUpload() throws Exception {
        System.out.println("\n===== 场景1：并行文件上传 =====");

        List<String> files = List.of("合同.pdf", "身份证.jpg", "营业执照.png", "报表.xlsx", "说明书.docx");

        // 每个文件单独一个 CompletableFuture
        List<CompletableFuture<String>> uploadFutures = files.stream()
                .map(file -> CompletableFuture.supplyAsync(() -> uploadFile(file), POOL))
                .collect(Collectors.toList());

        // allOf：等所有任务都完成
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                uploadFutures.toArray(new CompletableFuture[0])
        );

        // allOf本身没有返回值，需要手动从每个future取结果
        List<String> results = allDone.thenApply(v ->
                uploadFutures.stream()
                        .map(CompletableFuture::join) // 此时已全部完成，join不会阻塞
                        .collect(Collectors.toList())
        ).get(); // 主线程在这里等待

        System.out.println("所有文件上传完成：" + results);
    }

    static String uploadFile(String fileName) {
        try {
            // 模拟上传耗时（网络IO）
            Thread.sleep(new Random().nextInt(500) + 200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String url = "https://oss.example.com/" + fileName;
        System.out.println("  上传完成：" + fileName + " -> " + url + "  [" + Thread.currentThread().getName() + "]");
        return url;
    }

    // =========================================================
    // 场景2：Excel多Sheet并行解析 + 汇总校验结果
    // 实际场景：导入模板有 商品/库存/价格/供应商 4个Sheet，串行解析慢
    // =========================================================
    static void scene2_excelMultiSheetImport() throws Exception {
        System.out.println("\n===== 场景2：Excel多Sheet并行导入 =====");

        List<String> sheets = List.of("商品信息", "库存数据", "价格策略", "供应商");

        List<CompletableFuture<SheetResult>> sheetFutures = sheets.stream()
                .map(sheet -> CompletableFuture
                        .supplyAsync(() -> parseSheet(sheet), POOL)
                        // 解析完立即做数据校验（同步，在同一线程继续执行）
                        .thenApply(result -> validateSheet(result))
                )
                .collect(Collectors.toList());

        CompletableFuture.allOf(sheetFutures.toArray(new CompletableFuture[0])).get();

        List<SheetResult> allResults = sheetFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        // 汇总：有没有哪个Sheet校验失败
        long failCount = allResults.stream().filter(r -> !r.valid).count();
        System.out.println("导入完成，共 " + allResults.size() + " 个Sheet，失败 " + failCount + " 个");
        allResults.forEach(r -> System.out.println("  " + r));
    }

    record SheetResult(String sheetName, int rows, boolean valid, String errorMsg) {
        @Override public String toString() {
            return sheetName + ": rows=" + rows + ", valid=" + valid
                    + (valid ? "" : ", error=" + errorMsg);
        }
    }

    static SheetResult parseSheet(String sheetName) {
        try { Thread.sleep(new Random().nextInt(400) + 100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        int rows = new Random().nextInt(500) + 10;
        System.out.println("  解析完成：" + sheetName + " " + rows + "行  [" + Thread.currentThread().getName() + "]");
        return new SheetResult(sheetName, rows, true, null);
    }

    static SheetResult validateSheet(SheetResult result) {
        // 模拟校验：库存数据随机失败
        if (result.sheetName().equals("库存数据") && new Random().nextBoolean()) {
            return new SheetResult(result.sheetName(), result.rows(), false, "第3行库存数量为负数");
        }
        return result;
    }

    // =========================================================
    // 场景3：并行查多个数据源，合并成一个VO返回
    // 实际场景：商品详情页 = 基础信息 + 库存 + 评价数量 + 是否收藏
    //           4个数据来自不同表/服务，串行至少需要400ms，并行只需最慢那个
    // =========================================================
    static void scene3_aggregateQuery() throws Exception {
        System.out.println("\n===== 场景3：聚合查询（商品详情页） =====");

        long start = System.currentTimeMillis();

        CompletableFuture<String> basicInfo   = CompletableFuture.supplyAsync(() -> queryBasicInfo(1L),   POOL);
        CompletableFuture<Integer> stock      = CompletableFuture.supplyAsync(() -> queryStock(1L),       POOL);
        CompletableFuture<Long> reviewCount   = CompletableFuture.supplyAsync(() -> queryReviewCount(1L), POOL);
        CompletableFuture<Boolean> isFavorite = CompletableFuture.supplyAsync(() -> queryFavorite(1L),    POOL);

        // 等全部完成后合并
        String vo = basicInfo.thenCombine(stock, (info, s) -> info + ", 库存=" + s)
                .thenCombine(reviewCount,   (prev, r) -> prev + ", 评价=" + r)
                .thenCombine(isFavorite,    (prev, f) -> prev + ", 已收藏=" + f)
                .get();

        System.out.println("  商品详情: " + vo);
        System.out.println("  耗时: " + (System.currentTimeMillis() - start) + "ms（串行约需400ms+）");
    }

    static String  queryBasicInfo(Long id)   { sleep(120); return "iPhone 16 Pro"; }
    static Integer queryStock(Long id)        { sleep(80);  return 328; }
    static Long    queryReviewCount(Long id)  { sleep(150); return 4821L; }
    static Boolean queryFavorite(Long id)     { sleep(60);  return true; }

    // =========================================================
    // 场景4：异步任务链
    // 实际场景：文件上传成功后 → 异步解析内容 → 异步发站内通知
    //   thenApply   有入参有返回，同步执行（在上一步的线程里继续跑）
    //   thenApplyAsync  有入参有返回，异步执行（交给线程池新线程）
    //   thenAccept  有入参无返回（做收尾动作，如发通知）
    //   thenCompose 入参是另一个CompletableFuture（避免嵌套，类似flatMap）
    // =========================================================
    static void scene4_asyncChain() throws Exception {
        System.out.println("\n===== 场景4：异步任务链 =====");

        CompletableFuture.supplyAsync(() -> uploadFile("年度报告.xlsx"), POOL)
                // 上传完成 -> 异步解析（交给线程池，不占用上传线程）
                .thenApplyAsync(url -> {
                    System.out.println("  开始解析：" + url + "  [" + Thread.currentThread().getName() + "]");
                    sleep(200);
                    return "解析结果：共100行数据";
                }, POOL)
                // 解析完成 -> 异步入库（thenCompose 避免 future 嵌套）
                .thenComposeAsync(parseResult -> CompletableFuture.supplyAsync(() -> {
                    System.out.println("  开始入库：" + parseResult + "  [" + Thread.currentThread().getName() + "]");
                    sleep(150);
                    return "入库完成，batchId=9527";
                }, POOL))
                // 入库完成 -> 发通知（无返回值，纯收尾）
                .thenAccept(batchId -> {
                    System.out.println("  发送通知：" + batchId + " 已处理完毕  [" + Thread.currentThread().getName() + "]");
                })
                .get(); // 等整条链全部跑完
    }

    // =========================================================
    // 场景5：anyOf —— 谁先完成用谁
    // 实际场景：同一文件在多个CDN节点都有，哪个响应最快就用哪个
    // =========================================================
    static void scene5_anyOf_fastestCDN() throws Exception {
        System.out.println("\n===== 场景5：anyOf 取最快CDN节点 =====");

        List<String> cdnNodes = List.of("cdn-bj.example.com", "cdn-sh.example.com", "cdn-gz.example.com");

        List<CompletableFuture<String>> cdnFutures = cdnNodes.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> {
                    int latency = new Random().nextInt(300) + 50;
                    sleep(latency);
                    System.out.println("  节点响应：" + node + " 延迟=" + latency + "ms  [" + Thread.currentThread().getName() + "]");
                    return node + "/file.zip";
                }, POOL))
                .collect(Collectors.toList());

        // anyOf：第一个完成的结果，类型是 Object，需要强转
        String fastestUrl = (String) CompletableFuture.anyOf(
                cdnFutures.toArray(new CompletableFuture[0])
        ).get();

        System.out.println("  使用最快节点：" + fastestUrl);
    }

    // =========================================================
    // 场景6：异常处理
    // exceptionally：出错时提供降级返回值（类似 catch）
    // handle：无论成功失败都会执行（类似 finally，可以同时拿到结果和异常）
    // =========================================================
    static void scene6_exceptionHandling() throws Exception {
        System.out.println("\n===== 场景6：异常处理 =====");

        // --- exceptionally：降级处理 ---
        String result1 = CompletableFuture
                .supplyAsync(() -> {
                    if (true) throw new RuntimeException("OSS服务不可用");
                    return "上传成功URL";
                }, POOL)
                .exceptionally(ex -> {
                    // ex 就是抛出的异常，这里做降级：返回本地存储路径
                    System.out.println("  [exceptionally] 捕获异常：" + ex.getMessage() + "，降级返回本地路径");
                    return "/local/backup/file.zip";
                })
                .get();
        System.out.println("  最终URL：" + result1);

        // --- handle：无论成功失败都走，适合统一收口（如打日志、释放资源）---
        String result2 = CompletableFuture
                .supplyAsync(() -> {
                    if (new Random().nextBoolean()) throw new RuntimeException("随机异常");
                    return "正常结果";
                }, POOL)
                .handle((res, ex) -> {
                    if (ex != null) {
                        System.out.println("  [handle] 发生异常：" + ex.getMessage() + "，返回默认值");
                        return "默认值";
                    }
                    System.out.println("  [handle] 正常完成：" + res);
                    return res;
                })
                .get();
        System.out.println("  handle结果：" + result2);
    }

    // 工具方法
    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
