/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package cn.perfscope.report;

import cn.perfscope.PerfScope;
import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.metrics.ThresholdOperator;
import cn.perfscope.util.DurationFormat;
import cn.perfscope.util.Messages;
import cn.perfscope.util.ThresholdColors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 性能报告生成器 - 支持 HTML / TXT / JSON 三种格式
 * 自动管理报告清理
 *
 * <p>报告文案全部经 {@link Messages} 取值（见 {@code ReportMessages}），随
 * {@code general.language} 整份切换；HTML 的 {@code <html lang>} 也随之决定。
 * JSON 报告例外：字段名与字面值是与下游脚本约定的外部契约，<b>不参与翻译</b>。</p>
 */
public class ReportGenerator {

    private final PerfScope plugin;
    private final MetricsCollector collector;
    private final ConfigManager configManager;
    private final File reportDir;
    // v1.2 A8：报告层阈值统一改由 ThresholdColors 读 config 判定
    private final ThresholdColors thresholdColors;

    /**
     * 最近一次生成失败的原因（null = 上次成功或尚未尝试）。
     *
     * <p><b>为什么需要它</b>：{@code /perfscope report} 失败时原来只说一句"报告生成失败"，
     * 而真实原因有四种——没有快照、目录不可用、渲染抛异常、文件名连撞 {@code MAX}-次。
     * 四者的处置完全不同（先等等采集 / 修权限 / 报 bug / 清目录），用户却拿到同一句话，
     * 只能去翻服务端日志。这个插件的主打能力是"一屏全量诊断"，它自己的导出失败时
     * 说不出原因，是自相矛盾的。</p>
     *
     * <p>只在生成线程写、命令线程读，值是一次性字符串引用，故用 {@code volatile} 足够；
     * 不要求严格时序——最坏情况是用户看到上一条失败原因，而那次失败的真正原因在
     * 日志里仍然完整。</p>
     */
    private volatile String lastFailureReason;

    // P0-11：线程安全的日期格式化器（SimpleDateFormat 非线程安全，已删除）
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
    /** f-s05-sec-03：本插件报告文件的完整名字形态（与 {@link #FILE_TS} + {@link #writeReportAtomically} 对齐）。 */
    private static final Pattern REPORT_FILE_NAME = Pattern.compile(
        "^perfscope-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}(-\\d+)?\\.(html|txt|json)$");
    /**
     * f-s05-sec-03 的补充（验收 N-A6）：上面那条只校验位数，{@code perfscope-2026-13-03_...}
     * （13 月）、{@code ..._25-05-07-...}（25 时）仍会被判成本插件产物并删除。此处按
     * {@link #FILE_TS} 的字段值域收紧：月 01-12、日 01-31、时 00-23、分 00-59、秒 00-59。
     *
     * <p>日只校 01-31 不校月内天数（2 月 30 日仍会放行）：该文件仍然**只可能**由本插件命名，
     * 因为插件自己不会产出非法日期；放宽这一位是为了避免闰年/月末逻辑在这里重复实现一遍。
     * 真正的所有权判据是「形如本插件的时间戳」而非「时间戳是合法日期」。</p>
     */
    private static final Pattern REPORT_FILE_NAME_STRICT = Pattern.compile(
        "^perfscope-\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])"
            + "_([01]\\d|2[0-3])-[0-5]\\d-[0-5]\\d-\\d{3}(-\\d+)?\\.(html|txt|json)$");
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 「最后采集 HH:mm:ss」——与 {@code MetricsCollector#formatClock} / 命令层同口径（只要时刻，不要日期）。 */
    private static final DateTimeFormatter CLOCK_TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    // A5/T2b：单次清理最多删除的报告文件数硬上限——防止时钟异常/误配导致"一次删空"式批量误删
    private static final int MAX_CLEAN_PER_RUN = 50;
    // A5/T2c：lastModified "未来时间"容差（1 小时）——超过 now + 容差即视为时钟前跳/文件系统异常，跳过不删
    private static final long FUTURE_MTIME_TOLERANCE_MS = 3600_000L;
    // P0-11：JSON 改用 Gson（服务端 provided）；serializeNulls 让 unavailable 语义明确
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    // ==================== 9A-10 #4：报告写入串行化 ====================
    // 背景：自动报告（异步定时器）与 /ps report（命令线程）会同时调用 generateAndSave()，
    // 旧实现的 resolveReportFile().exists() 与 Files.write(TRUNCATE_EXISTING) 之间没有原子性——
    // 两个线程可能解析出同一文件名，后写者把先写者的内容整段截断，留下半份报告。
    // 修复分三层：
    //   ① 单线程执行器串行化本类所有"生成+写盘"操作（同一时刻只有一个报告在写）；
    //   ② 文件创建改用 CREATE_NEW（O_EXCL 语义，原子），真正碰撞时由内核拒绝而不是静默覆盖；
    //   ③ 捕获 FileAlreadyExistsException 后换名重试（最多 MAX_FILE_NAME_RETRIES 次）。
    // 守护线程 + 有界语义：不阻止 JVM 退出，onDisable 由 shutdown() 主动收尾。
    private static final int MAX_FILE_NAME_RETRIES = 10;
    // f-s06-conc-02：shutdownNow() 会 drain 队列却不完成已排队的 Future，无超时的 get() 会永久阻塞。
    private static final long REPORT_AWAIT_TIMEOUT_SECONDS = 30L;
    private final ExecutorService reportExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "perfscope-report");
        t.setDaemon(true);
        return t;
    });

    public ReportGenerator(PerfScope plugin) {
        this.plugin = plugin;
        // 9D：报告层文案表由 ReportMessages 在 static 块注册；本类实例化晚于 Messages.load，
        // 因此这里确认键已并入当前语言快照（未并入则补一次 load，只在首次出现）。
        ReportMessages.ensureRegistered(plugin);
        this.collector = plugin.getMetricsCollector();
        this.configManager = plugin.getConfigManager();
        this.thresholdColors = new ThresholdColors(configManager);
        this.reportDir = new File(plugin.getDataFolder(), "reports");
        if (!reportDir.exists()) {
            reportDir.mkdirs();
        }
    }

    /**
     * 生成并保存报告 (默认格式)。
     *
     * <p><b>9A-10 #4</b>：提交到单线程报告执行器串行执行，返回值是执行完成后的文件绝对路径
     * （{@code null} = 失败/生成器已关闭）。同一路径既有自动报告（异步定时器）也有
     * {@code /ps report}（命令线程）调用，串行化 + {@code CREATE_NEW} 才能保证不互相截断。</p>
     */
    public String generateAndSave() {
        return submitAndAwait(() -> generateSpecificOnExecutor(configManager.get().report_format));
    }

    /**
     * 按指定格式生成并保存报告（同样经报告执行器串行化）。
     */
    public String generateSpecific(String format) {
        return submitAndAwait(() -> generateSpecificOnExecutor(format));
    }

    /**
     * 9A-10 #4：把一次"生成+写盘"提交到 {@link #reportExecutor} 并等待结果。
     *
     * <ul>
     *   <li>已在执行器线程内调用（例如未来某处在任务内部再生成报告）→ 直接内联执行，避免自等死锁；</li>
     *   <li>已 {@link #shutdown()} → 记 warning 并返回 null（关服阶段不再产生新报告）；</li>
     *   <li>等待被中断 → 恢复中断标志并返回 null，绝不吞掉中断信号。</li>
     * </ul>
     */
    private String submitAndAwait(Supplier<String> task) {
        if (Thread.currentThread().getName().startsWith("perfscope-report")) {
            // 同一线程内联：RejectedExecutionException/自等死锁的防呆
            return task.get();
        }
        Future<String> future;
        try {
            future = reportExecutor.submit(task::get);
        } catch (RejectedExecutionException e) {
            plugin.getLogger().warning(Messages.get("report.log.executor-closed"));
            return null;
        }
        try {
            return future.get(REPORT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 恢复中断，交由上层停止
            return null;
        } catch (TimeoutException e) {
            // f-s06-conc-02：不 cancel(true)，否则可能中断写盘留下半写文件。
            // N-1：文案明确"后台可能仍会生成"，避免用户看到"失败"后又冒出文件。
            plugin.getLogger().warning(Messages.get("report.log.await-timeout", REPORT_AWAIT_TIMEOUT_SECONDS));
            return null;
        } catch (ExecutionException e) {
            // generateSpecificOnExecutor 自身已兜底全部异常，走到这里属意外；仍不向调用方抛出
            plugin.getLogger().warning(Messages.get("report.log.task-failed", e.getCause()));
            return null;
        }
    }

    /**
     * 9A-10 #4：真正执行"生成+写盘"的方法——<b>只允许在 {@link #reportExecutor} 线程内调用</b>
     * （由 {@link #submitAndAwait} 保证串行）。
     */
    private String generateSpecificOnExecutor(String format) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) {
            lastFailureReason = Messages.get("report.fail.no-snapshot");
            return null;
        }

        List<MetricsSnapshot> history = collector.getHistory();

        // B5（RT-02 自愈）：写入前确保 reports/ 目录存在且可写（构造器只建过一次，运行中被删即永久失败）
        if (!ensureReportDir()) {
            lastFailureReason = Messages.get("report.fail.dir-unusable", reportDir.getAbsolutePath());
            return null;
        }

        String ext = normalizeExtension(format);
        String content;
        try {
            content = switch (ext) {
                case "html" -> generateHtmlReport(snap, history);
                case "json" -> generateJsonReport(snap, history);
                default -> generateTextReport(snap, history);
            };
        } catch (Throwable t) {
            // P0-11 兜底：极端精简端缺 Gson 等运行时异常同样返回 null
            lastFailureReason = Messages.get("report.fail.render", ext.toUpperCase(Locale.ROOT),
                describe(t));
            plugin.getLogger().warning(Messages.get("report.log.generate-failed",
                ext.toUpperCase(Locale.ROOT), t.getMessage()));
            return null;
        }

        File file = writeReportAtomically(ext, content);
        if (file == null) {
            return null;
        }
        // 成功即清空：下次失败若没写原因（理论上不会），也不会把陈年旧因当成新的
        lastFailureReason = null;

        // 清理旧报告
        cleanOldReports();

        return file.getAbsolutePath();
    }

    /**
     * 9A-10 #4：把报告内容写到一个<b>此前不存在</b>的文件里。
     *
     * <p>{@link StandardOpenOption#CREATE_NEW} 是原子创建（底层 O_CREAT|O_EXCL）：文件已存在时
     * 内核直接抛 {@link FileAlreadyExistsException}，不会像 {@code TRUNCATE_EXISTING} 那样
     * 静默截断别人的报告。碰撞时计数器 +1 换名重试，上限 {@link #MAX_FILE_NAME_RETRIES}。</p>
     *
     * @return 实际写入的文件；重试耗尽或 IO 失败时返回 {@code null}（已记 warning）
     */
    private File writeReportAtomically(String extension, String content) {
        String base = "perfscope-" + LocalDateTime.now().format(FILE_TS);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        for (int counter = 0; counter <= MAX_FILE_NAME_RETRIES; counter++) {
            File file = new File(reportDir, counter == 0
                ? base + "." + extension
                : base + "-" + counter + "." + extension);
            try {
                Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return file;
            } catch (FileAlreadyExistsException e) {
                // 正常碰撞（同毫秒/同秒内并发生成，或旧文件残留）：换名重试
                continue;
            } catch (IOException e) {
                lastFailureReason = Messages.get("report.fail.write", describe(e));
                plugin.getLogger().warning(Messages.get("report.log.generate-failed",
                    extension.toUpperCase(Locale.ROOT), e.getMessage()));
                return null;
            } catch (Throwable t) {
                lastFailureReason = Messages.get("report.fail.write", describe(t));
                plugin.getLogger().warning(Messages.get("report.log.generate-failed",
                    extension.toUpperCase(Locale.ROOT), t.getMessage()));
                return null;
            }
        }
        lastFailureReason = Messages.get("report.fail.name-collision", MAX_FILE_NAME_RETRIES);
        plugin.getLogger().warning(Messages.get("report.log.name-collision",
            MAX_FILE_NAME_RETRIES, base + "." + extension));
        return null;
    }

    /**
     * 最近一次生成失败的原因（null = 上次成功或尚未尝试）。
     *
     * <p>供命令层在"报告生成失败"后面补一句真实原因。四种失败各有不同处置方式，
     * 只说"失败"会把排查成本推给用户。</p>
     */
    public String failureReason() {
        return lastFailureReason;
    }

    /**
     * 异常 → 一行可读描述（类名 + 首段消息，去掉换行）。
     *
     * <p>只取形状不取堆栈：这条文本会进游戏内聊天栏，多行会把后续输出吞掉。
     * 完整堆栈仍然只在日志里。</p>
     */
    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        int nl = msg.indexOf('\n');
        if (nl >= 0) {
            msg = msg.substring(0, nl);
        }
        if (msg.length() > 120) {
            msg = msg.substring(0, 120) + "…";
        }
        return t.getClass().getSimpleName() + ": " + msg;
    }

    /** 9A-10 #4：格式归一化（与旧 switch 的 case 集合保持一致：html / json / txt|text / 其它 → txt）。 */
    private static String normalizeExtension(String format) {
        if (format == null) {
            return "txt";
        }
        switch (format.toLowerCase(Locale.ROOT)) {
            case "html":
                return "html";
            case "json":
                return "json";
            default:
                return "txt";   // 含 "txt" / "text" / 未知值
        }
    }

    /**
     * 9A-10 #4：关闭报告执行器（由 {@code PerfScope.onDisable()} 在停止生产者之后调用）。
     * 使用 {@code shutdownNow()} 丢弃排队请求并中断在写任务——关服阶段不应再阻塞主线程等待 IO。
     */
    public void shutdown() {
        reportExecutor.shutdownNow();
    }

    /**
     * 清理超过保留天数的旧报告。
     *
     * <p>T2 三连加固（RC-3 + RT-07）：</p>
     * <ul>
     *   <li>a. 文件名过滤：只删本插件生成的 {@code perfscope-*.html|txt|json} 普通文件，
     *         用户在 reports/ 里自建的任何文件/子目录一律不碰（reports/ 不是插件独占目录）；</li>
     *   <li>b. 删除上限：单次调用最多删除 {@link #MAX_CLEAN_PER_RUN} 个，防时钟异常/误配导致一次删空；</li>
     *   <li>c. 时钟异常防御：lastModified 为"未来时间"（&gt; now + {@link #FUTURE_MTIME_TOLERANCE_MS}）
     *         的文件跳过不删——系统时钟前跳会让正常文件被误判超期，时钟回跳后的"未来文件"不可信。</li>
     * </ul>
     */
    public void cleanOldReports() {
        int retentionDays = configManager.get().report_retentionDays;
        if (retentionDays <= 0) return;

        long now = System.currentTimeMillis();
        long cutoff = now - (long) retentionDays * 24 * 3600 * 1000;
        File[] files = reportDir.listFiles();
        if (files == null) return;

        int deleted = 0;
        int deleteFailed = 0;   // f-s05-err-07：删除失败计数（首个立即记，其余汇总一条）
        for (File file : files) {
            // a. 仅处理普通文件，且文件名必须匹配本插件生成模式
            if (!file.isFile() || !isReportFileName(file.getName())) continue;
            long mtime = file.lastModified();
            // c. 未来时间（时钟前跳/文件系统异常）→ 跳过不删
            if (mtime > now + FUTURE_MTIME_TOLERANCE_MS) continue;
            if (mtime < cutoff) {
                // b. 确实还有超期文件待删时才发现超限 → 记日志并停止本次清理
                if (deleted >= MAX_CLEAN_PER_RUN) {
                    plugin.getLogger().warning(Messages.get("report.log.clean-limit", MAX_CLEAN_PER_RUN));
                    break;
                }
                if (file.delete()) {
                    deleted++;
                } else {
                    // f-s05-err-07：delete() 返回 false 此前被静默丢弃。Windows 上文件被浏览器/
                    // 杀软占用或目录只读时删除恒失败，这些文件永不清理且无任何诊断线索。
                    // 逐文件记 WARNING 会在批量占用时刷屏，故与 clean-limit 同范式：只记首个并计数。
                    if (deleteFailed == 0) {
                        plugin.getLogger().warning(Messages.get("report.log.delete-failed",
                            file.getName(), file.getAbsolutePath()));
                    }
                    deleteFailed++;
                }
            }
        }
        if (deleteFailed > 1) {
            plugin.getLogger().warning(Messages.get("report.log.delete-failed-summary", deleteFailed));
        }
    }

    /**
     * T2a：报告文件名判定——仅 {@code perfscope-<时间戳>[-N].html|txt|json} 视为本插件产物。
     * 加前缀+后缀双条件，避免误删用户在 reports/ 中自建的文件。
     *
     * <p>f-s05-sec-03：前缀+后缀不足以宣称所有权——管理员自建的 {@code perfscope-notes.txt}、
     * {@code perfscope-2026.html} 同样满足，超期后会被清理逻辑当成自己的产物删掉。故改为按
     * {@link #FILE_TS} 的完整格式（{@code yyyy-MM-dd_HH-mm-ss-SSS}）做正则校验；{@code -N}
     * 是 {@link #writeReportAtomically} 换名重试产生的后缀，必须一并放行，否则碰撞文件永不清理。</p>
     */
    private static boolean isReportFileName(String name) {
        return name != null && REPORT_FILE_NAME.matcher(name).matches()
            && REPORT_FILE_NAME_STRICT.matcher(name).matches();
    }

    /**
     * B5（RT-02）：确保 reports/ 目录存在且可写。
     * 构造器只在启动时建过一次目录，运行中目录被外部删除后须在此自愈；
     * 建目录失败或目录不可写时记明确 warning（含路径与原因）并返回 false，由调用方中止写入。
     */
    private boolean ensureReportDir() {
        if (!reportDir.isDirectory()) {
            if (!reportDir.mkdirs() && !reportDir.isDirectory()) {
                plugin.getLogger().warning(Messages.get("report.log.dir-unavailable",
                    reportDir.getAbsolutePath()));
                return false;
            }
        }
        if (!reportDir.canWrite()) {
            plugin.getLogger().warning(Messages.get("report.log.dir-not-writable",
                reportDir.getAbsolutePath()));
            return false;
        }
        return true;
    }

    // ==================== HTML 报告 ====================

    private String generateHtmlReport(MetricsSnapshot snap, List<MetricsSnapshot> history) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"").append(htmlLang()).append("\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>").append(htmlMsg("report.html.title")).append("</title>\n");
        html.append("<style>\n");
        html.append(loadHtmlCss());
        html.append("</style>\n</head>\n<body>\n");

        // 头部
        // 报告头必须同时给出「报告生成于」与「数据采集于」两个时刻。
        // 旧实现只印 LocalDateTime.now()，于是一份由卡死 10 分钟后自动导出
        // （report.auto_export_interval: 30）的 HTML，标题写着"生成于 当前时刻"、卡片全绿、
        // 数字全冻结——正是本项目反复修过的"把冻住的数据当当期值"。
        // 生成时刻回答不了"这些数字是哪一刻的"，必须与采样时刻并列给出。
        Staleness stale = stalenessOf(snap);
        html.append("<div class=\"header\">\n");
        html.append("  <h1>").append(htmlMsg("report.html.heading")).append("</h1>\n");
        html.append("  <p>").append(htmlMsg("report.common.generated-at"))
            .append(LocalDateTime.now().format(DISPLAY_TS)).append("</p>\n");
        html.append("  <p>").append(htmlMsg("report.common.sampled-at"))
            .append(stale.sampleAtMs() <= 0L
                ? htmlMsg("report.common.never-sampled")
                : escapeHtml(formatDisplay(stale.sampleAtMs())))
            .append("</p>\n");
        html.append("  <p>").append(htmlMsg("report.html.uptime"))
            .append(escapeHtml(formatUptime(snap.uptimeSeconds))).append("</p>\n");
        if (stale.isStale()) {
            // 采集真的停了：红底横幅，而不是让读者拿两个时刻自己算差
            html.append("  <p class=\"stale-banner\">").append(htmlMsg("report.html.stale-banner",
                DurationFormat.duration(stale.staleForMs()), lastCollectClock(stale))).append("</p>\n");
        }
        html.append("</div>\n");

        html.append("<div class=\"container\">\n");

        // 概览卡片
        html.append("<div class=\"section\">\n");
        html.append("<h2>").append(htmlMsg("report.html.section.overview")).append("</h2>\n");
        html.append("<div class=\"card-grid\">\n");

        // P0-5：unavailable 模块显示"不可用"；P0-13：MSPT/CPU -1 哨兵显示"未知"
        // f-s05-logic-03：这两个变量会被传进 htmlCard，而 htmlCard 内部会 escapeHtml 一次。
        // 这里若再用 htmlMsg（= escapeHtml + Messages.get）就会双重转义——默认文案里没有 &<>，
        // 但用户在 messages.yml 里覆盖成 "N/A <unknown>" 时会被渲染成 "N/A &amp;lt;unknown&amp;gt;"。
        // 故此处取裸文案，转义交给 htmlCard 单点负责。
        String unavailable = Messages.get("report.common.unavailable");
        String unknown = Messages.get("report.common.unknown");
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            html.append(htmlCard(Messages.get("report.html.card.tps"), unavailable, "unknown", ""));
        } else {
            // 9A-2：Folia 上 Bukkit.getTPS() 只反映 global region，不能宣称是全服口径
            html.append(htmlCard(Messages.get("report.html.card.tps"),
                String.format(java.util.Locale.ROOT, "%.2f", snap.tps1m), getTpsClass(snap.tps1m),
                Messages.get("report.html.card.tps.subtitle",
                    String.format(java.util.Locale.ROOT, "%.2f", snap.tps5m), String.format(java.util.Locale.ROOT, "%.2f", snap.tps15m))
                    + ServerCompat.tpsScopeLabel()));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
            // f-s05-logic-02(C)：按真实成因选原因（配置禁用 > 平台不支持 > 采集异常），
            // 不再拿静态能力位代表 unavailable 的成员身份。裸文案交 htmlCard 单次转义。
            html.append(htmlCard(Messages.get("report.html.card.mspt"), unavailable, "unknown", msptUnavailableCause(snap)));
        // P2-S2：判据从 `< 0` 收紧到 `<= 0`。MSPT 的 0 不可信（每 tick 至少花掉可测量时间），
        // 旧写法会让一个 0 走 else 分支，把卡片渲染成 "0.00 ms"——绿色的"极快"，
        // 而真相是这一维度没有有效读数。口径见 check-sentinels.mjs 的 SENTINEL_AND_ZERO_BOTH_BAD。
        } else if (snap.msptAvg <= 0) {
            html.append(htmlCard(Messages.get("report.html.card.mspt"), unknown, "unknown",
                Messages.get("report.html.mspt.estimate-note")));
        } else {
            // 9A-0：min/max 为 -1 表示该采集路径不提供极值（avg-only / TPS 反推），
            // 渲染"未采集"而不是把平均值复制成假极值（旧实现输出 "最小 12.3 | 最大 12.3"）
            // f-s05-logic-03：range 会作为 subtitle 传进 htmlCard，转义由 htmlCard 单点负责，
            // 故这里取裸文案（原用 htmlMsg 会双重转义，用户覆盖含 &<> 的文案时出现 &amp;amp;）。
            String range = (snap.msptMin <= 0 || snap.msptMax <= 0)
                ? Messages.get("report.html.mspt.range-uncollected")
                : Messages.get("report.html.mspt.range",
                    String.format(java.util.Locale.ROOT, "%.2f", snap.msptMin), String.format(java.util.Locale.ROOT, "%.2f", snap.msptMax));
            html.append(htmlCard(Messages.get("report.html.card.mspt"),
                String.format(java.util.Locale.ROOT, "%.2f ms", snap.msptAvg) + msptEstimateTag(snap),
                getMsptClass(snap.msptAvg), range));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            html.append(htmlCard(Messages.get("report.html.card.players"), unavailable, "unknown", ""));
        } else {
            // 级别判定输入是百分比（snap.playerPercent()），不是原始玩家数——
            // players 的阈值键（players.warning/critical_threshold）口径就是占容量百分比。
            html.append(htmlCard(Messages.get("report.html.card.players"),
                snap.playerCount + " / " + snap.maxPlayers, getPlayersClass(snap.playerPercent()), ""));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            html.append(htmlCard(Messages.get("report.html.card.entities"), unavailable, "unknown", ""));
        } else {
            html.append(htmlCard(Messages.get("report.html.card.entities"),
                String.valueOf(snap.totalEntities), getEntitiesClass(snap.totalEntities), ""));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            html.append(htmlCard(Messages.get("report.html.card.chunks"), unavailable, "unknown", ""));
        } else {
            // 刻意<b>不</b>接阈值：本卡渲染的是 snap.totalLoadedChunks —— 全服合计，
            // 而 chunks.loaded_warning/critical_threshold 的口径是「每个世界」
            // （config.yml 原文标注，Metric.CHUNKS 的判定也只服务逐世界）。
            // 反例：3 个世界各 4000 区块的正常服，合计 12000 会越过全服黄线 10000，
            // 卡片随即变黄，而此刻每个世界都远低于阈值、一条告警都不会发。
            // 级别改挂到下方「实体分布」表的逐世界 chunks 单元格（列口径一致）。
            html.append(htmlCard(Messages.get("report.html.card.chunks"),
                String.valueOf(snap.totalLoadedChunks), "normal",
                Messages.get("report.html.card.chunks.subtitle", snap.totalForceLoadedChunks)));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            html.append(htmlCard(Messages.get("report.html.card.heap"), unavailable, "unknown", ""));
        } else {
            html.append(htmlCard(Messages.get("report.html.card.heap"),
                formatBytes(snap.heapUsed) + " / " + formatBytes(snap.heapMax),
                getMemClass(snap.heapUsagePercent),
                Messages.get("report.html.card.heap.subtitle", snap.heapUsagePercent)));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CPU)) {
            html.append(htmlCard(Messages.get("report.html.card.cpu"), unavailable, "unknown", ""));
        } else {
            html.append(htmlCard(Messages.get("report.html.card.cpu"),
                formatCpuPct(snap.processCpuLoad), getCpuClass(snap.processCpuLoad),
                Messages.get("report.html.card.cpu.subtitle", formatCpuPct(snap.systemCpuLoad))));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_SERVER)) {
            html.append(htmlCard(Messages.get("report.html.card.worlds"), unavailable, "unknown", ""));
        } else {
            // worldCount 刻意保持中性 "normal"：世界数量没有对应的阈值键，
            // ThresholdColors.Metric 里也没有这个指标（配置里不存在「世界太多」的告警）。
            // 给它编一个颜色等于凭空发明一条健康判据。注意 "normal" 不是 CSS class
            // （见 .card 基础规则），它落到基础样式即青色中性边条——这正是想要的。
            html.append(htmlCard(Messages.get("report.html.card.worlds"),
                String.valueOf(snap.worldCount), "normal",
                Messages.get("report.html.card.worlds.subtitle", snap.loadedPluginCount)));
        }

        html.append("</div></div>\n");

        // TPS趋势图 (简单ASCII风格)
        if (history.size() > 1) {
            html.append("<div class=\"section\">\n");
            html.append("<h2>")
                .append(htmlMsg("report.html.section.tps-trend",
                    Math.min(60, history.size())))
                .append("</h2>\n");
            html.append("<div class=\"chart\">\n");
            // 刻度含义必须写出来：柱高是 tps/上限*100，两条判定线在哪一格由 config 决定，
            // 只画柱子和一串数字时，读者只能靠颜色猜"红的是从多少开始"。
            html.append("<div class=\"chart-legend\">")
                .append(tpsThresholdLegend(configManager.get()))
                .append("</div>\n");
            html.append("<div class=\"tps-graph\">\n");
            // 内层绘图区：柱子与参考线必须共用**同一个**盒子，否则百分比对不上尺。
            // 外层有 5px 内边距（背景色描边感），柱高 % 是按内容盒算的，而绝对定位的参考线
            // 是按内边距盒算的——直接混用会让 15 TPS 的柱顶比红线低 2.5px，"柱顶越过线"
            // 这个唯一的读图依据就变成近似值。内层无内边距，两个 % 才真正同基准。
            html.append("<div class=\"tps-plot\">\n");
            // 两条阈值参考线：位置 = 阈值/上限*100，与柱高同一把尺（见 tpsThresholdMarkers）。
            html.append(tpsThresholdMarkers(configManager.get()));
            int start = Math.max(0, history.size() - 60);
            for (int i = start; i < history.size(); i++) {
                MetricsSnapshot h = history.get(i);
                // A5（data-contract F2/R7）：该采样点 TPS 模块不可用时不画"红色 0 高柱"（会被读作 TPS=0 灾难），
                // 改为中性灰色矮柱 + title"不可用"，语义清晰且不误导。
                if (h.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
                    html.append("<div class=\"bar\" style=\"height:2%;background:#607d8b\" title=\"")
                        .append(escapeHtml(unavailable)).append("\"></div>\n");
                    continue;
                }
                double pct = h.tps1m / TPS_AXIS_CEILING * 100;
                // v1.2 A8：改为读 config 阈值；桶 3/2 绿（区分深浅沿用原色值）、1 黄、0 红
                String color;
                switch (thresholdColors.tpsTrendBucket(h.tps1m)) {
                    case 3:
                        color = "#69F0AE";
                        break;
                    case 2:
                        color = "#4CAF50";
                        break;
                    case 1:
                        color = "#FFB300";
                        break;
                    default:
                        color = "#FF5252";
                        break;
                }
                html.append("<div class=\"bar\" style=\"height:").append(String.format(java.util.Locale.ROOT, "%.1f", pct))
                    .append("%;background:").append(color).append("\" title=\"")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", h.tps1m)).append("\"></div>\n");
            }
            html.append("</div>\n");
            html.append("</div>\n");
            html.append("<div class=\"chart-labels\">\n");
            for (String tick : tpsAxisTickLabels(TPS_AXIS_CEILING, TPS_AXIS_TICK_COUNT)) {
                html.append("<span>").append(escapeHtml(tick)).append("</span>");
            }
            html.append("\n");
            html.append("</div></div></div>\n");
        }

        // 实体分布
        // T1a/T1b（P1-2 + data-contract F3）：三列各自按对应模块判 unavailable（entities/chunks/tile_entities），
        // 模块不可用时对应单元格显示灰色"不可用"，而非误导性的 0；
        // 实体模块不可用时 entitiesByWorld 为空、无行可渲染，整表以 colspan 行标注"不可用"。
        html.append("<div class=\"section\">\n");
        html.append("<h2>").append(htmlMsg("report.html.section.entities-by-world")).append("</h2>\n");
        html.append("<table><tr><th>").append(htmlMsg("report.html.th.world"))
            .append("</th><th>").append(htmlMsg("report.html.th.entities"))
            .append("</th><th>").append(htmlMsg("report.html.th.chunks"))
            .append("</th><th>").append(htmlMsg("report.html.th.tile-entities"))
            .append("</th></tr>\n");
        boolean entitiesUnavailable = snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES);
        boolean chunksUnavailable = snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS);
        boolean teUnavailable = snap.unavailable.contains(MetricsSnapshot.MODULE_TILE_ENTITIES);
        if (entitiesUnavailable && snap.entitiesByWorld.isEmpty()) {
            html.append("<tr><td colspan=\"4\" style=\"color:#90a4ae\">")
                .append(escapeHtml(unavailable)).append("</td></tr>\n");
        } else {
            for (Map.Entry<String, Integer> entry : snap.entitiesByWorld.entrySet()) {
                String world = entry.getKey();
                int entities = entry.getValue();
                int chunks = snap.chunksByWorld.getOrDefault(world, 0);
                int tileEntities = snap.tileEntitiesByWorld.getOrDefault(world, 0);
                html.append("<tr><td>").append(escapeHtml(world)).append("</td>")
                    .append(htmlCell(entitiesUnavailable, entities))
                    .append(htmlLevelCell(chunksUnavailable, chunks, getChunksClass(world, chunks)))
                    .append(htmlCell(teUnavailable, tileEntities))
                    .append("</tr>\n");
            }
        }
        html.append("</table></div>\n");

        // 实体类型 Top 15
        html.append("<div class=\"section\">\n");
        html.append("<h2>").append(htmlMsg("report.html.section.entities-by-type")).append("</h2>\n");
        // P0-5：实体模块 unavailable 时显示"不可用"（灰色），而非空表
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            html.append("<p style=\"color:#90a4ae\">").append(escapeHtml(unavailable)).append("</p>\n");
        } else {
            html.append("<table><tr><th>").append(htmlMsg("report.html.th.entity-type"))
                .append("</th><th>").append(htmlMsg("report.html.th.count"))
                .append("</th><th>").append(htmlMsg("report.html.th.share"))
                .append("</th></tr>\n");
            snap.entitiesByType.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .forEach(entry -> {
                    double pct = snap.totalEntities > 0 ?  // @sentinel-ok 判断"有没有实体可以展示"，不是哨兵判断
                        (double) entry.getValue() / snap.totalEntities * 100 : 0;
                    html.append("<tr><td>").append(escapeHtml(entry.getKey())).append("</td><td>")
                        .append(entry.getValue()).append("</td><td>")
                        .append(String.format(java.util.Locale.ROOT, "%.1f%%", pct)).append("</td></tr>\n");
                });
            html.append("</table>\n");
        }
        html.append("</div>\n");

        // 内存详情
        html.append("<div class=\"section\">\n");
        html.append("<h2>").append(htmlMsg("report.html.section.memory")).append("</h2>\n");
        // P0-5：内存模块 unavailable 时整段显示"不可用"（灰色），而非 0 值进度条
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            html.append("<p style=\"color:#90a4ae\">").append(escapeHtml(unavailable)).append("</p>\n");
        } else {
            html.append("<div class=\"mem-bar\">\n");
            html.append("<div class=\"mem-fill\" style=\"width:").append(snap.heapUsagePercent)
                .append("%;background:").append(getMemBarColor(snap.heapUsagePercent)).append("\"></div>\n");
            html.append("</div>\n");
            html.append("<p>").append(htmlMsg("report.html.memory.heap")).append(formatBytes(snap.heapUsed))
                .append(" / ").append(formatBytes(snap.heapMax)).append(" (")
                .append(snap.heapUsagePercent).append("%)</p>\n");
            html.append("<p>").append(htmlMsg("report.html.memory.non-heap"))
                .append(formatBytes(snap.nonHeapUsed));
            if (snap.nonHeapMax > 0) html.append(" / ").append(formatBytes(snap.nonHeapMax));
            html.append("</p>\n");
        }
        // P0-5：GC 模块独立判断（与概览一致），unavailable 时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            html.append("<p style=\"color:#90a4ae\">").append(htmlMsg("report.html.gc.unavailable"))
                .append("</p>\n");
        } else {
            html.append("<p>").append(htmlMsg("report.html.gc", snap.gcCount, snap.gcTime)).append("</p>\n");
        }
        html.append("</div>\n");

        // 告警
        if (!snap.activeCriticals.isEmpty() || !snap.activeWarnings.isEmpty()) {
            html.append("<div class=\"section\">\n");
            html.append("<h2>").append(htmlMsg("report.html.section.alerts")).append("</h2>\n");
            for (String critical : snap.activeCriticals) {
                html.append("<p class=\"critical\">[!!!] ").append(escapeHtml(critical)).append("</p>\n");
            }
            for (String warning : snap.activeWarnings) {
                html.append("<p class=\"warning\">[!] ").append(escapeHtml(warning)).append("</p>\n");
            }
            html.append("</div>\n");
        }

        html.append("</div>\n");

        // 页脚
        html.append("<div class=\"footer\">\n");
        html.append("<p>").append(htmlMsg("report.html.footer",
            plugin.getDescription().getVersion())).append("</p>\n");
        html.append("</div>\n");

        html.append("</body>\n</html>");
        return html.toString();
    }

    // ==================== TXT 报告 ====================

    private String generateTextReport(MetricsSnapshot snap, List<MetricsSnapshot> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append(Messages.get("report.txt.title")).append("\n");
        sb.append("========================================\n");
        Staleness stale = stalenessOf(snap);
        sb.append(Messages.get("report.common.generated-at")).append(LocalDateTime.now().format(DISPLAY_TS)).append("\n");
        sb.append(Messages.get("report.common.sampled-at"))
            .append(stale.sampleAtMs() <= 0L
                ? Messages.get("report.common.never-sampled")
                : formatDisplay(stale.sampleAtMs()))
            .append("\n");
        if (stale.isStale()) {
            // 纯文本没有颜色，只能靠 [WARNING] 前缀把这一行从正文里拔出来
            sb.append(Messages.get("report.txt.stale-banner",
                DurationFormat.duration(stale.staleForMs()), lastCollectClock(stale))).append("\n");
        }
        sb.append(Messages.get("report.txt.uptime")).append(formatUptime(snap.uptimeSeconds)).append("\n\n");

        sb.append(Messages.get("report.txt.section.overview")).append("\n");
        // P0-5：unavailable 模块显示"不可用"；P0-13：MSPT/CPU -1 哨兵显示"未知"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            sb.append(Messages.get("report.txt.tps.unavailable")).append("\n");
        } else {
            // 9A-2：Folia 上 TPS 是 global region 局部值，报告里必须标注口径
            sb.append(Messages.get("report.txt.tps",
                String.format(java.util.Locale.ROOT, "%.2f", snap.tps1m), String.format(java.util.Locale.ROOT, "%.2f", snap.tps5m),
                String.format(java.util.Locale.ROOT, "%.2f", snap.tps15m), ServerCompat.tpsScopeLabel())).append("\n");
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
            // f-s05-logic-02(C)：与 HTML 侧共用同一成因判定，杜绝两路径口径分裂
            sb.append(Messages.get("report.txt.mspt.unavailable-reason", msptUnavailableCause(snap))).append("\n");
        // P2-S2：与 HTML 侧同一判据（`<= 0` 而非 `< 0`），否则 TXT 报告会把 0 印成 "0.00 ms"。
        } else if (snap.msptAvg <= 0) {
            sb.append(Messages.get("report.txt.mspt.unknown")).append("\n");
        } else if (snap.msptMin <= 0 || snap.msptMax <= 0) {
            // 9A-0：极值未采集（avg-only / TPS 反推路径），不输出伪造的 min==max==avg
            sb.append(Messages.get("report.txt.mspt.partial", String.format(java.util.Locale.ROOT, "%.2f", snap.msptAvg) + msptEstimateTag(snap))).append("\n");
        } else {
            sb.append(Messages.get("report.txt.mspt", String.format(java.util.Locale.ROOT, "%.2f", snap.msptAvg) + msptEstimateTag(snap),
                String.format(java.util.Locale.ROOT, "%.2f", snap.msptMin), String.format(java.util.Locale.ROOT, "%.2f", snap.msptMax))).append("\n");
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            sb.append(Messages.get("report.txt.players.unavailable")).append("\n");
        } else {
            sb.append(Messages.get("report.txt.players", snap.playerCount, snap.maxPlayers)).append("\n");
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            sb.append(Messages.get("report.txt.entities.unavailable")).append("\n");
        } else {
            sb.append(Messages.get("report.txt.entities", snap.totalEntities)).append("\n");
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            sb.append(Messages.get("report.txt.chunks.unavailable")).append("\n");
        } else {
            sb.append(Messages.get("report.txt.chunks",
                snap.totalLoadedChunks, snap.totalForceLoadedChunks)).append("\n");
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            sb.append(Messages.get("report.txt.heap.unavailable")).append("\n");
        } else {
            sb.append(Messages.get("report.txt.heap", formatBytes(snap.heapUsed),
                formatBytes(snap.heapMax), snap.heapUsagePercent)).append("\n");
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CPU)) {
            sb.append(Messages.get("report.txt.cpu.unavailable")).append("\n");
        } else {
            sb.append(Messages.get("report.txt.cpu",
                formatCpuPct(snap.processCpuLoad), formatCpuPct(snap.systemCpuLoad))).append("\n");
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            sb.append(Messages.get("report.txt.gc.unavailable")).append("\n");
        } else {
            sb.append(Messages.get("report.txt.gc", snap.gcCount, snap.gcTime)).append("\n");
        }
        sb.append("\n");

        sb.append(Messages.get("report.txt.section.entities-by-world")).append("\n");
        // T1a/T1b（P1-2 + data-contract F3）：三列各自按对应模块判 unavailable，不可用列显示"不可用"而非 0；
        // 实体模块不可用时无行可渲染，整段标注"不可用"。
        boolean entitiesUnavailable = snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES);
        boolean chunksUnavailable = snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS);
        boolean teUnavailable = snap.unavailable.contains(MetricsSnapshot.MODULE_TILE_ENTITIES);
        if (entitiesUnavailable && snap.entitiesByWorld.isEmpty()) {
            sb.append(Messages.get("report.txt.indent.unavailable")).append("\n");
        } else {
            for (Map.Entry<String, Integer> entry : snap.entitiesByWorld.entrySet()) {
                String world = entry.getKey();
                sb.append(Messages.get("report.txt.world-row",
                    world,
                    txtCell(entitiesUnavailable, entry.getValue()),
                    txtCell(chunksUnavailable, snap.chunksByWorld.getOrDefault(world, 0)),
                    txtCell(teUnavailable, snap.tileEntitiesByWorld.getOrDefault(world, 0)))).append("\n");
            }
        }
        sb.append("\n");

        sb.append(Messages.get("report.txt.section.entities-by-type")).append("\n");
        // P0-5：实体模块 unavailable 时显示"不可用"，而非空列表
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            sb.append(Messages.get("report.txt.indent.unavailable")).append("\n");
        } else {
            snap.entitiesByType.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> {
                    double pct = snap.totalEntities > 0 ?  // @sentinel-ok 判断"有没有实体可以展示"，不是哨兵判断
                        (double) entry.getValue() / snap.totalEntities * 100 : 0;
                    sb.append(Messages.get("report.txt.entity-row",
                        entry.getKey(), entry.getValue(), String.format(java.util.Locale.ROOT, "%.1f", pct))).append("\n");
                });
        }
        sb.append("\n");

        if (!snap.activeCriticals.isEmpty() || !snap.activeWarnings.isEmpty()) {
            sb.append(Messages.get("report.txt.section.alerts")).append("\n");
            for (String c : snap.activeCriticals) sb.append("  [!!!] ").append(c).append("\n");
            for (String w : snap.activeWarnings) sb.append("  [!] ").append(w).append("\n");
            sb.append("\n");
        }

        sb.append("========================================\n");
        sb.append(Messages.get("report.txt.footer", plugin.getDescription().getVersion())).append("\n");
        return sb.toString();
    }

    // ==================== JSON 报告 ====================

    /**
     * P0-11：JSON 改用 Gson 生成。键名与旧手拼版本完全一致（老解析脚本兼容）。
     * P0-5：unavailable 模块对应段输出 {"status": "unavailable"}。
     * P0-13：MSPT/CPU 保留数字 -1 哨兵，前端自解释。
     */
    private String generateJsonReport(MetricsSnapshot snap, List<MetricsSnapshot> history) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generated_at", LocalDateTime.now().format(DISPLAY_TS));
        root.put("plugin_version", plugin.getDescription().getVersion());

        // 采集新鲜度段（**增量新键**，既有键名一个都没动）。
        //
        // 为什么必须进 JSON：巡检脚本按 generated_at 判断"这份报告新不新"，而它回答的是
        // "文件是何时写的"，不是"数字是何时采的"。采集任务被取消后 getLatest() 仍返回历史上
        // 最后一张快照，报告照常落盘、卡片全绿、健康度 ok——下游拿到一份数值完美但全冻结的
        // JSON，与"服务器很健康"逐字节不可区分。
        //
        // 判定直接取 collector.isStale()，不在此重算阈值：阈值是 MetricsCollector 的职责
        // （3 × 采集间隔，下界 3000ms），报告层重算会造出第二份 staleness 判定。
        root.put("stale", staleSection(stalenessOf(snap), snap));

        // TPS
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            root.put("tps", unavailableSection(snap, MetricsSnapshot.MODULE_TPS));
        } else {
            Map<String, Object> tps = new LinkedHashMap<>();
            tps.put("1m", safeDouble(snap.tps1m));
            tps.put("5m", safeDouble(snap.tps5m));
            tps.put("15m", safeDouble(snap.tps15m));
            root.put("tps", tps);
        }

        // MSPT
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
            root.put("mspt", unavailableSection(snap, MetricsSnapshot.MODULE_MSPT));
        } else {
            Map<String, Object> mspt = new LinkedHashMap<>();
            mspt.put("avg", safeDouble(snap.msptAvg));
            // 9A-0：-1 = 该采集路径不提供极值（avg-only / TPS 反推）。
            // 下游 JSON 消费方必须据此判空，不能再假设 min/max 恒等于 avg。
            mspt.put("min", safeDouble(snap.msptMin));
            mspt.put("max", safeDouble(snap.msptMax));
            mspt.put("source", msptSourceName(snap.msptSource));
            root.put("mspt", mspt);
        }

        // 玩家
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            root.put("players", unavailableSection(snap, MetricsSnapshot.MODULE_PLAYERS));
        } else {
            Map<String, Object> players = new LinkedHashMap<>();
            players.put("online", snap.playerCount);
            players.put("max", snap.maxPlayers);
            root.put("players", players);
        }

        // 实体
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            root.put("entities", unavailableSection(snap, MetricsSnapshot.MODULE_ENTITIES));
        } else {
            Map<String, Object> entities = new LinkedHashMap<>();
            entities.put("total", snap.totalEntities);
            entities.put("by_world", snap.entitiesByWorld);
            entities.put("by_type", snap.entitiesByType);
            root.put("entities", entities);
        }

        // T1a（RC-2 补段）：Tile Entity 模块状态。TE 禁用/采集失败时输出 {"status":"unavailable"}，
        // 而非任何误导性的 0；可用时输出 total/by_world。
        // 注意：v1.0 手拼 JSON 无此段，本段为**增量新键**（不改动任何既有键名，旧解析脚本天然忽略）。
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TILE_ENTITIES)) {
            root.put("tile_entities", unavailableSection(snap, MetricsSnapshot.MODULE_TILE_ENTITIES));
        } else {
            Map<String, Object> tileEntities = new LinkedHashMap<>();
            tileEntities.put("total", snap.tileEntityCount);
            tileEntities.put("by_world", snap.tileEntitiesByWorld);
            root.put("tile_entities", tileEntities);
        }

        // 区块
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            root.put("chunks", unavailableSection(snap, MetricsSnapshot.MODULE_CHUNKS));
        } else {
            Map<String, Object> chunks = new LinkedHashMap<>();
            chunks.put("total_loaded", snap.totalLoadedChunks);
            chunks.put("total_force_loaded", snap.totalForceLoadedChunks);
            chunks.put("by_world", snap.chunksByWorld);
            root.put("chunks", chunks);
        }

        // 内存
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            root.put("memory", unavailableSection(snap, MetricsSnapshot.MODULE_MEMORY));
        } else {
            Map<String, Object> memory = new LinkedHashMap<>();
            memory.put("heap_used", snap.heapUsed);
            memory.put("heap_max", snap.heapMax);
            memory.put("heap_total", snap.heapTotal);
            memory.put("heap_usage_percent", snap.heapUsagePercent);
            memory.put("non_heap_used", snap.nonHeapUsed);
            memory.put("non_heap_max", snap.nonHeapMax);
            root.put("memory", memory);
        }

        // CPU
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CPU)) {
            root.put("cpu", unavailableSection(snap, MetricsSnapshot.MODULE_CPU));
        } else {
            Map<String, Object> cpu = new LinkedHashMap<>();
            cpu.put("process_load", safeDouble(snap.processCpuLoad));
            cpu.put("system_load", safeDouble(snap.systemCpuLoad));
            cpu.put("available_processors", snap.availableProcessors);
            root.put("cpu", cpu);
        }

        // GC
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            root.put("gc", unavailableSection(snap, MetricsSnapshot.MODULE_GC));
        } else {
            Map<String, Object> gc = new LinkedHashMap<>();
            gc.put("count", snap.gcCount);
            gc.put("time_ms", snap.gcTime);
            root.put("gc", gc);
        }

        // 服务器信息
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_SERVER)) {
            root.put("server", unavailableSection(snap, MetricsSnapshot.MODULE_SERVER));
        } else {
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("uptime_seconds", snap.uptimeSeconds);
            server.put("thread_count", snap.threadCount);
            server.put("plugin_count", snap.loadedPluginCount);
            server.put("world_count", snap.worldCount);
            root.put("server", server);
        }

        // 告警
        // RT-01（红队）：alerts 段新增 "health" 状态字段——巡检脚本（CI 门禁/Grafana 抓取）此前无法区分
        // "真健康（无告警）"与"全模块静默死亡（采集全挂所以无告警）"：snap.unavailable 非空 = degraded。
        // "health" 为增量新键（既有 critical/warnings 键名不动），旧脚本天然忽略。
        Map<String, Object> alerts = new LinkedHashMap<>();
        alerts.put("critical", snap.activeCriticals);
        alerts.put("warnings", snap.activeWarnings);
        alerts.put("health", healthOf(snap));
        root.put("alerts", alerts);

        // 历史趋势 (TPS)
        // A5/T1c（data-contract F2/R7）：不可用采样输出 null（Gson serializeNulls 会写出 null），
        // 老解析脚本按数字解析会得到 null 而非误导性的 0.0。
        // ⚠ 这是 v1.1 唯一有意的值语义变更（相对 v1.0：不可用采样点由 0.0 → null）；
        //   可用但非有限值仍走 safeDouble → -1 哨兵（既有契约不变）。
        List<Double> tpsHistory = new ArrayList<>(history.size());
        for (MetricsSnapshot h : history) {
            if (h.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
                tpsHistory.add(null);
            } else {
                tpsHistory.add(safeDouble(h.tps1m));
            }
        }
        root.put("tps_history", tpsHistory);

        return GSON.toJson(root) + "\n";
    }

    // ==================== 数据新鲜度（stale 标记） ====================

    /**
     * 报告渲染时用到的三个新鲜度事实，<b>全部派生自 {@link MetricsCollector}</b>。
     *
     * <p>为什么不把 {@code isStale()} 直接写在三个 {@code generate*Report} 里各调一次：
     * 三处各调一次就是三次时钟读数，同一份报告的三段可能落在阈值两侧（采集恰好在渲染途中
     * 恢复或停摆）——HTML 说健康、JSON 说 stale。收成一个 record 之后，三个格式共用同一次
     * 判定快照，报告内部自洽。</p>
     *
     * <p>同样重要的是：这里<b>不重算</b> staleness 阈值。"多久算过期"是
     * {@link MetricsCollector#isStale()} 的职责（{@code 3 × update_interval}，下界 3000ms，
     * 从未采集过时以启动时刻为基准并叠加 10 秒宽限）。报告层若自己写一遍 {@code now - ts > x}，
     * 就会出现第二份阈值、两处口径迟早分叉，而 {@code /perfscope status} 与报告会开始
     * 对同一台服务器给出相反答案。</p>
     *
     * @param isStale       {@link MetricsCollector#isStale()} 的原样结果
     * @param staleForMs    {@link MetricsCollector#staleForMs()}：距最后一次成功采集多久
     * @param lastCollectMs {@link MetricsCollector#lastCollectAtMs()}：最后一次成功采集的时刻
     *                      （{@code 0} = 启动后从未成功过）
     * @param sampleAtMs    报告里那份快照自己的 {@code timestamp}（{@code 0} = 从未采集）
     */
    private record Staleness(boolean isStale, long staleForMs, long lastCollectMs, long sampleAtMs) {}

    /**
     * 取一次新鲜度判定快照。
     *
     * <p>{@code collector} 理论上恒非 null（构造器从 {@code plugin.getMetricsCollector()} 取），
     * 但报告层是"落盘前最后一道"，此处仍按 null 安全处理：宁可少一条提示，不可让整份报告
     * 因为新鲜度这一行而渲染失败。</p>
     */
    private Staleness stalenessOf(MetricsSnapshot snap) {
        long sampleAt = snap == null ? 0L : snap.timestamp;
        if (collector == null) {
            return new Staleness(false, 0L, 0L, sampleAt);
        }
        return new Staleness(collector.isStale(), collector.staleForMs(),
            collector.lastCollectAtMs(), sampleAt);
    }

    /**
     * 生成时刻的展示格式（{@code yyyy-MM-dd HH:mm:ss}，本地时区）。
     *
     * <p>{@code epochMs <= 0} 时返回空串——调用点必须<b>先</b>判 0 再决定渲染"从未采集"
     * （{@code report.common.never-sampled}）。这里再做一次 0 兜底是因为 {@code 0}
     * 直接格式化出来是 {@code 1970-01-01 08:00:00}，那个年份会被当成真实读数读。
     * 报告层此前在 {@code /status} 上已经踩过同一类坑（{@code clockOf(0)} 的"从未"分支）。</p>
     */
    private static String formatDisplay(long epochMs) {
        if (epochMs <= 0L) {
            return "";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
            .format(DISPLAY_TS);
    }

    /**
     * 横幅里的"最后采集 HH:mm:ss"。
     *
     * <p>三种状态分开渲染，不能一律格式化：</p>
     * <ul>
     *   <li>正常采集过 → {@code HH:mm:ss}（与 {@code /perfscope status} 同口径，只要时刻）；</li>
     *   <li>启动后从未成功过（{@code lastCollectMs == 0}）→ 走"从未采集"文案。
     *       此处若直接格式化 0，横幅会写"最后采集 08:00:00"，读者会以为今天早上采到过；</li>
     *   <li>快照 timestamp 为 0（采集器造壳/极端形态）→ 同样不伪造时刻。</li>
     * </ul>
     */
    private static String lastCollectClock(Staleness stale) {
        long ref = stale.lastCollectMs() > 0L ? stale.lastCollectMs() : stale.sampleAtMs();
        if (ref <= 0L) {
            return Messages.get("report.common.never-sampled");
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ref), ZoneId.systemDefault())
            .format(CLOCK_TS);
    }

    /**
     * JSON 的 {@code stale} 段（<b>增量新键</b>，不改任何既有键名）。
     *
     * <p>字段契约（下游脚本可依赖，<b>不随语言变化</b>）：</p>
     * <ul>
     *   <li>{@code isStale} — {@code true} 时这份报告里的数字已经不能代表服务器当前状态；</li>
     *   <li>{@code staleForMs} — 距最后一次成功采集的毫秒数（负数已被 collector 夹成 0）；</li>
     *   <li>{@code lastCollectAt} — 最后一次成功采集的 {@code yyyy-MM-dd HH:mm:ss}；
     *       {@code null} = 启动后从未成功采集过（Gson 的 serializeNulls 会写出显式 null）。
     *       <b>刻意不用 {@code ""} 或 {@code 0}</b>：空串会被 {@code new Date(s)} 一类的
     *       宽松解析读成 1970，与"从未"完全是两件事；</li>
     *   <li>{@code sampleAt} — 报告正文那份快照的采样时刻，同上格式与同上 null 语义。</li>
     * </ul>
     *
     * <p>为什么同时给 {@code staleForMs} 与 {@code lastCollectAt}（冗余）：前者适合直接
     * 与阈值比较（"超过 5 分钟就翻红"），后者适合人读与跨系统关联。只给一个，另一个的
     * 使用方就得自己从 {@code generated_at} 做减法，而 {@code generated_at} 是本地时区无
     * 偏移量的字符串——减法结果依赖读者所在时区，那是个陷阱。</p>
     */
    private static Map<String, Object> staleSection(Staleness stale, MetricsSnapshot snap) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("isStale", stale.isStale());
        section.put("staleForMs", stale.staleForMs());
        section.put("lastCollectAt", stale.lastCollectMs() <= 0L ? null : formatDisplay(stale.lastCollectMs()));
        section.put("sampleAt", snap == null || snap.timestamp <= 0L ? null : formatDisplay(snap.timestamp));
        return section;
    }

    // ==================== 工具方法 ====================

    /**
     * P0-11：unavailable 模块的 JSON 段。
     */
    /**
     * JSON 的不可用段。{@code cause} 字段区分三种成因，契约见 {@link #unavailableCause}。
     *
     * <p><b>为什么加 cause</b>：此前所有不可用段都是恒定的 {@code {"status":"unavailable"}}，
     * 于是"服主自己关了 mspt.enabled"与"采集抛异常"在 JSON 里**逐字节不可区分**。
     * 更糟的是 {@code alerts.health} 直接拿 {@code snap.unavailable.isEmpty()} 判定，
     * 把"按配置正常运行"也算成 {@code degraded}——依赖该字段的 CI 门禁 / Grafana 抓取
     * 会对完全健康的服务器永久报异常。HTML/TXT 两侧此前已按成因区分，JSON 是唯一的缺口。</p>
     *
     * <p><b>契约（外部分接口）</b>：{@code status} 与 {@code cause} 都是新增/既有稳定字段。
     * 旧脚本只读 {@code status} 的，行为零变化；需要成因的读 {@code cause}。</p>
     */
    private static Map<String, Object> unavailableSection(MetricsSnapshot snap, String module) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("status", "unavailable");
        section.put("cause", unavailableCause(snap, module));
        return section;
    }

    /** 成因的字面量（JSON 值，非展示文案；稳定契约，不随语言变化）。 */
    private static final String CAUSE_DISABLED = "disabled";
    private static final String CAUSE_PLATFORM = "platform";
    private static final String CAUSE_FAILED = "collect_failed";

    /**
     * 判定模块不可用的成因，返回**稳定字面量**而非本地化文案——JSON 是给脚本消费的，
     * 文案会随 {@code general.language} 变化，字面量不会。
     *
     * <p>优先级依 {@link MetricsSnapshot} 的集合契约
     * （{@code platformSkipped ⊆ disabledModules ⊆ unavailable}）：
     * 平台跳过 → 配置禁用 → 采集失败。顺序不能反：Folia 上被跳过的模块同时也在
     * {@code disabledModules} 里，若先判 disabled 会把平台成因误报成用户配置。</p>
     */
    private static String unavailableCause(MetricsSnapshot snap, String module) {
        if (snap == null || module == null) {
            return CAUSE_FAILED;
        }
        if (snap.platformSkipped.contains(module)) {
            return CAUSE_PLATFORM;
        }
        if (snap.disabledModules.contains(module)) {
            return CAUSE_DISABLED;
        }
        return CAUSE_FAILED;
    }

    /**
     * {@code alerts.health} 的取值。
     *
     * <p>只有<b>真正的采集失败</b>才算 {@code degraded}。模块被服主主动关闭、平台不支持、
     * 或仍在首轮预热窗口内（{@code warmingUp}，默认 10 秒内自愈），都属于"按预期运行"，
     * 不应污染健康度——否则 CI 门禁会对健康的服务器永久报警。</p>
     *
     * <p>取值：{@code ok}（无不可用）/ {@code degraded}（有采集失败）/ {@code partial}
     * （仅配置禁用、平台跳过或预热中，无采集失败）。{@code partial} 是新增取值，旧脚本
     * 若只认 {@code ok} 会把它当异常——这是有意的：它确实不完全健康，只是原因不是故障。</p>
     */
    private static String healthOf(MetricsSnapshot snap) {
        if (snap == null || snap.unavailable.isEmpty()) {
            return "ok";
        }
        for (String module : snap.unavailable) {
            // f-s05-logic-01（同类残留）：预热不是故障。warmingUp ⊆ unavailable，
            // 若不在此跳过，启动/重载后 10 秒内的报告会把"马上自愈"判成 degraded。
            if (snap.warmingUp.contains(module)) {
                continue;
            }
            if (CAUSE_FAILED.equals(unavailableCause(snap, module))) {
                return "degraded";
            }
        }
        return "partial";
    }

    /**
     * P0-11：Gson 默认拒绝 NaN/Infinity，非有限值统一转为 -1 哨兵。
     */
    private static double safeDouble(double v) {
        return Double.isFinite(v) ? v : -1;
    }

    /** 9A-0：MSPT 采集路径名（JSON 可读输出，便于下游区分精确与估算）。 */
    private static String msptSourceName(int source) {
        switch (source) {
            case MetricsSnapshot.MSPT_SOURCE_TICK_TIMES:
                return "tick_times";
            case MetricsSnapshot.MSPT_SOURCE_AVG_TICK:
                return "avg_tick";
            case MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK:
                return "tps_fallback";
            default:
                return "unknown";
        }
    }

    /** f-s05-logic-02(C)：MSPT 不可用的真实成因（HTML/TXT 唯一入口）。"{@code MODULE_MSPT} ∈ unavailable" 与
     *  能力位 {@link ServerCompat#isMsptTrustworthy()} 互不蕴含；优先级依 {@link MetricsSnapshot#disabledModules}
     *  契约：配置禁用 → 平台不支持 → 采集异常。返回裸文案，永不为 null。 */
    private static String msptUnavailableCause(MetricsSnapshot snap) {
        if (snap != null && snap.disabledModules.contains(MetricsSnapshot.MODULE_MSPT)) {
            return Messages.get("report.common.mspt.disabled");
        }
        if (ServerCompat.isMsptTrustworthy()) {
            return Messages.get("report.common.mspt.collect-failed");
        }
        String reason = ServerCompat.msptUnavailableReason();
        return (reason == null || reason.isEmpty()) ? Messages.get("report.common.unavailable") : reason;
    }

    /**
     * f-s02-data-08：TPS 反推路径的估值标记（HTML/TXT 共用，与聊天层同口径；一/二级是真实测量，不标）。
     * 返回裸文案不转义——HTML 侧由 {@link #htmlCard} 统一单次转义，避免二次转义。
     */
    private static String msptEstimateTag(MetricsSnapshot snap) {
        return snap.msptSource == MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK ? Messages.get("report.common.estimated-tag") : "";
    }

    /**
     * P0-11：HTML 最小转义（& < > " '），防世界名/告警文案破版与 XSS。
     */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * P0-13：CPU -1 哨兵渲染为"未知"。
     */
    private static String formatCpuPct(double v) {
        return v < 0 ? Messages.get("report.common.unknown") : String.format(java.util.Locale.ROOT, "%.1f%%", v);
    }

    /**
     * T1a/T1b：TXT 世界分布表单元格渲染——模块不可用时输出"不可用"文本，否则输出数值字符串。
     */
    private static String txtCell(boolean unavailable, int value) {
        return unavailable ? Messages.get("report.common.unavailable") : String.valueOf(value);
    }

    /**
     * T1a/T1b：世界分布表单元格渲染——模块不可用时输出灰色"不可用"（与既有 .unknown 灰色 #90a4ae 一致），
     * 否则输出数值；数值均为非负计数，无 -1 哨兵路径。
     */
    private static String htmlCell(boolean unavailable, int value) {
        return unavailable
            ? "<td style=\"color:#90a4ae\">" + escapeHtml(Messages.get("report.common.unavailable")) + "</td>"
            : "<td>" + value + "</td>";
    }

    /**
     * 按级别着色的世界分布表单元格（当前仅「区块」列使用）。
     *
     * <p><b>为什么只有偏离才上色</b>：这是数据表，不是结论面板。整列刷成绿色会让
     * 「哪一行有问题」淹没在满屏绿字里；把颜色留给 WARN / CRITICAL 才能一眼定位到
     * 那个真正超线的世界。GOOD 与「未判级」共用默认样式，因此健康服的报告外观不变。</p>
     *
     * <p>色值与 {@code .card.good/.warn/.critical} 及 htmlCell 的灰色保持同一套：
     * {@code #69F0AE / #FFB300 / #FF5252 / #90a4ae}（GOOD 不上色，见上）。</p>
     */
    private static String htmlLevelCell(boolean unavailable, int value, String cssClass) {
        if (unavailable) {
            return htmlCell(true, value);
        }
        String color;
        switch (cssClass) {
            case "warn":
                color = "#FFB300";
                break;
            case "critical":
                color = "#FF5252";
                break;
            case "unknown":
                color = "#90a4ae";
                break;
            default:
                return htmlCell(false, value); // good：保持中性，见方法注释
        }
        return "<td style=\"color:" + color + "\">" + value + "</td>";
    }

    /**
     * HTML 文档语言标签：由当前生效语言决定（{@code en} / {@code zh-CN}）。
     *
     * <p>不能写死：{@code <html lang>} 决定浏览器选中哪套断行/朗读规则、也决定屏幕阅读器
     * 用哪种语音合成。一份英文报告标成 {@code zh-CN} 会被当成中文念出来。
     * 值取自 {@link Messages#activeLocale()} 的快照，因此报告落盘时的语言与正文一致；
     * 已生成的旧报告不会被追溯改写（报告是时点产物）。</p>
     */
    private static String htmlLang() {
        return Messages.LOCALE_ZH.equals(Messages.activeLocale()) ? "zh-CN" : "en";
    }

    /**
     * HTML 报告里的文案取值：先按当前语言取值，再做 HTML 转义。
     *
     * <p>文案可能来自 {@code messages.yml}（用户可编辑），也可能含 {@code &}、{@code <}、{@code '}
     * 这类字符（英文的 "Min &amp; max"、中文的引号变体）。直接拼进标记里会破版甚至注入，
     * 因此 HTML 侧一律走这个方法；TXT 报告不需要转义，直接用 {@link Messages#get}。</p>
     */
    private static String htmlMsg(String key, Object... args) {
        return escapeHtml(Messages.get(key, args));
    }

    // ==================== TPS 趋势图的纵轴 ====================

    /**
     * 趋势图纵轴上限：<b>TPS 的物理上限 20</b>，而不是 config 里的 critical 值。
     *
     * <p>为什么这么取（而不是"把 20/15/10/5/0 换成 config 值"）：</p>
     * <ul>
     *   <li><b>柱高必须与刻度同一把尺</b>。柱高的分母就是它，读者把柱子顶端对齐到某条刻度线
     *       才读得出"这一刻是 15 还是 18"。若分母随 critical 跑，critical=15 时一根 15 TPS 的柱子
     *       会画到 100% 满高——<b>健康读数被画成"顶到天花板"</b>，恰好是这条缺陷原本会制造的误读。</li>
     *   <li><b>刻度均分必须可读</b>。原实现是 20/15/10/5/0 五档均分；若直接按 critical 均分，
     *       critical=15 会变成 15/11.25/7.5/3.75/0——四个小数、三条无意义刻度，
     *       读者要多算一次才敢下结论。这里改为<b>固定 5 等分的整刻度 + 两条阈值线单独标注</b>：
     *       刻度只回答"绝对位置"，阈值线回答"越过哪一格算黄/红"，两个问题各用各的表达。</li>
     *   <li><b>阈值是被读出来的，不是被画歪的</b>。若"最大值改成 critical"，
     *       一个把 critical 设成 5 的激进服主会得到一张永远满高的图——图变成了阈值报警器，
     *       不再能看见真实读数。</li>
     * </ul>
     *
     * <p>所以：上限固定 20（TPS 物理上限，改 config 不该改物理），刻度线由
     * {@link #tpsAxisTickLabels} 均分，阈值（warning / critical）由
     * {@link #tpsThresholdLegend} 以文字标出——后者才是跟着 config 走的那部分。</p>
     */
    static final double TPS_AXIS_CEILING = 20.0;

    /** 纵轴刻度档数（含 0 与上限）：与 CSS 里 {@code .chart-labels} 的 {@code space-between} 布局对应。 */
    static final int TPS_AXIS_TICK_COUNT = 5;

    /**
     * 纵轴刻度文案：由 {@code 0.0} 到 {@code ceiling} 均分 {@code ticks} 档，从上到下。
     *
     * <p>TPS 的上限是整数 20，{@code 20/5 = 4} 的等分恰好落在整数上（20/16/12/8/4/0 若取 6 档），
     * 出厂 {@code ticks = 5} 给的是 20/15/10/5/0——与 v1.1 那张手写刻度逐位相同，
     * 所以<b>默认配置下 CSV/HTML 的呈现零变化</b>，变的只是"它现在是算出来的"。</p>
     *
     * <p>刻意<b>不</b>把中间某两档替换成 critical/warning：那会让刻度间距不等（20/18/15/10/5/0），
     * 柱子对齐刻度时就读不出线性关系。阈值由图例文字标注，见 {@link #tpsThresholdLegend}。</p>
     *
     * @param ceiling 上限（&gt;0，调用点传 {@link #TPS_AXIS_CEILING}）
     * @param ticks   档数（&gt;=2；调用点传 {@link #TPS_AXIS_TICK_COUNT}）
     */
    static List<String> tpsAxisTickLabels(double ceiling, int ticks) {
        List<String> out = new ArrayList<>(Math.max(0, ticks));
        if (!(ceiling > 0) || ticks < 2) {
            // 非法入参不抛异常：这是展示层，宁可给一档 0.0 也不要让整份报告生成失败
            out.add(String.format(java.util.Locale.ROOT, "%.1f", 0.0));
            return out;
        }
        for (int i = 0; i < ticks; i++) {
            double value = ceiling - (ceiling / (ticks - 1)) * i;
            out.add(String.format(java.util.Locale.ROOT, "%.1f", value));
        }
        return out;
    }

    /**
     * 图例：把两条阈值线的<b>数值与比较方向</b>写在图上。
     *
     * <p>为什么需要它：柱子颜色（绿/黄/红）回答"这一刻是什么档"，但读者看不出"红是从多少开始"。
     * 以前答案藏在那串硬编码的 20/15/10/5/0 里——它只是刻度，从来不是阈值；
     * 而服主把 {@code tps.critical_threshold} 改成 17 之后，图上没有任何一处提到 17。</p>
     *
     * <p>比较符取自 {@link ThresholdOperator#TPS}（不是在这里再写一个 {@code "<"}）：
     * 改算子表时这句话跟着变，不会出现"图例说小于、判定用小于等于"这种两份副本。</p>
     *
     * <p>返回的是<b>已转义</b>的 HTML：数字与符号不可能含标记，但文案来自
     * {@code messages.yml}（用户可写 {@code <}），所以中文/英文两段都要过 {@link #escapeHtml}。</p>
     */
    static String tpsThresholdLegend(ConfigManager.ConfigData cfg) {
        if (cfg == null) {
            return "";
        }
        ThresholdOperator.Rule rule = ThresholdOperator.TPS;
        // 文案模板：{0}=critical 值 {1}=critical 比较符 {2}=warning 值 {3}=warning 比较符。
        // 比较符作为独立占位符传入，而不是拼进模板里——翻译只换语序，不碰符号。
        String criticalPart = Messages.get("report.html.chart.tps-critical",
            formatTpsThreshold(cfg.tps_criticalThreshold), rule.criticalSymbol());
        String warnPart = Messages.get("report.html.chart.tps-warn",
            formatTpsThreshold(cfg.tps_warningThreshold), rule.warningSymbol());
        return "<span class=\"legend-critical\">" + escapeHtml(criticalPart) + "</span>"
            + "<span class=\"legend-warn\">" + escapeHtml(warnPart) + "</span>";
    }

    /** 阈值文案：整数补一位小数（{@code 15} → {@code 15.0}），与纵轴刻度同一格式。 */
    private static String formatTpsThreshold(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /**
     * 图上两条阈值参考线的位置（CSS {@code bottom} 百分比），由 config 阈值除以纵轴上限得到。
     *
     * <p>与 {@link #TPS_AXIS_CEILING} 同一把尺：柱高 {@code tps/20*100}、参考线
     * {@code threshold/20*100}，所以 {@code critical = 15} 的线正好落在 75%，
     * 与那根 15 TPS 的柱子顶端齐平——读者看到"柱子越过红线"就等于"这条读数红了"。</p>
     *
     * <p>越界的参考线（阈值 &gt; 上限，例如合法上限 20.5 的 {@code 20.2}）与非法值
     * 一律不画：画在 100% 顶部会被读成"阈值就是 20"，而那正是这条缺陷的旧形态。
     * 它们仍会出现在 {@link #tpsThresholdLegend} 的文字里，信息不丢。</p>
     *
     * @return 两段 HTML（critical 在前）；无可用阈值时返回空串
     */
    static String tpsThresholdMarkers(ConfigManager.ConfigData cfg) {
        if (cfg == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        appendThresholdMarker(out, "critical", cfg.tps_criticalThreshold);
        appendThresholdMarker(out, "warn", cfg.tps_warningThreshold);
        return out.toString();
    }

    private static void appendThresholdMarker(StringBuilder out, String cssLevel, double threshold) {
        if (!(threshold > 0) || threshold > TPS_AXIS_CEILING) {
            return;
        }
        out.append("<div class=\"tps-threshold ").append(cssLevel)
            .append("\" style=\"bottom:").append(String.format(java.util.Locale.ROOT, "%.1f", threshold / TPS_AXIS_CEILING * 100))
            .append("%\"><span>").append(formatTpsThreshold(threshold)).append("</span></div>");
    }

    private String htmlCard(String title, String value, String cssClass, String subtitle) {
        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card ").append(cssClass).append("\">\n");
        card.append("<div class=\"card-value\">").append(escapeHtml(value)).append("</div>\n");
        card.append("<div class=\"card-title\">").append(escapeHtml(title)).append("</div>\n");
        if (!subtitle.isEmpty()) {
            card.append("<div class=\"card-subtitle\">").append(escapeHtml(subtitle)).append("</div>\n");
        }
        card.append("</div>\n");
        return card.toString();
    }

    private String getTpsClass(double tps) {
        // v1.2 A8：改为读 config 阈值
        return thresholdColors.cssClass(thresholdColors.levelOf(ThresholdColors.Metric.TPS, tps));
    }

    private String getMsptClass(double mspt) {
        // v1.2 A8：改为读 config 阈值
        return thresholdColors.cssClass(thresholdColors.levelOf(ThresholdColors.Metric.MSPT, mspt));
    }

    private String getMemClass(int pct) {
        // v1.2 A8：改为读 config 阈值
        return thresholdColors.cssClass(thresholdColors.levelOf(ThresholdColors.Metric.MEMORY, pct));
    }

    /**
     * 玩家卡片级别——输入是<b>占容量百分比</b>（{@link MetricsSnapshot#playerPercent()}），
     * 不是原始玩家数：{@code players.warning_threshold: 90} 的含义是「90% 满员」。
     */
    private String getPlayersClass(int pct) {
        return thresholdColors.cssClass(thresholdColors.levelOf(ThresholdColors.Metric.PLAYERS, pct));
    }

    /**
     * 实体卡片级别——卡片显示的是<b>全服合计</b>，故用两参重载（不传世界名）。
     * 三参重载是为「实体分布」表的逐世界行准备的（会去 worlds 段取覆盖阈值）；
     * 卡片是全服合计，拿某个世界的覆盖阈值判它会得出错误结论。
     */
    private String getEntitiesClass(int total) {
        return thresholdColors.cssClass(thresholdColors.levelOf(ThresholdColors.Metric.ENTITIES, total));
    }

    /**
     * 进程 CPU 卡片级别。{@code processCpuLoad < 0}（Windows 上恒为 -1，未采集）判 UNKNOWN →
     * 卡片转灰，比原先恒青色更诚实（灰色 = 未采集，而非"一切正常"）。
     * config 只有 warning 键没有 critical 键，故本卡最高只到 {@code warn}。
     */
    private String getCpuClass(double load) {
        return thresholdColors.cssClass(thresholdColors.levelOf(ThresholdColors.Metric.CPU_PROCESS, load));
    }

    /**
     * 逐世界区块单元格级别——口径必须是「每个世界」（{@code Metric.CHUNKS} 的全局阈值
     * 在采集与命令层都只喂给单世界读数；{@code ThresholdColors.levelOf} 的 CHUNKS 分支
     * 不读 worlds 段覆盖，因为区块阈值本来就没有按世界配置）。
     *
     * <p>这与上方的区块总览卡片形成互补：卡片是<b>全服合计</b>且刻意保持中性，
     * 因为合计口径与阈值口径不一致；本表每行的值恰好就是阈值定义的那个量。</p>
     */
    private String getChunksClass(String world, int chunks) {
        return thresholdColors.cssClass(thresholdColors.levelOf(ThresholdColors.Metric.CHUNKS, chunks, world));
    }

    private String getMemBarColor(int pct) {
        // v1.2 A8：改为读 config 阈值（档位来源改 config，色值沿用文件内既有）
        switch (thresholdColors.levelOf(ThresholdColors.Metric.MEMORY, pct)) {
            case GOOD:
                return "#69F0AE";
            case WARN:
                return "#FFB300";
            default:
                return "#FF5252";
        }
    }

    /**
     * 字节数 → 人类可读文案（f-s05-logic-04）。
     *
     * <p>换档判据必须与输出精度同口径：旧实现按原始字节判档、却按 {@code %.1f} 输出，
     * 于是 {@code 1048575}（比 1 MB 少 1 字节）落进 KB 档并被格式化成 {@code "1024.0 KB"}，
     * {@code 1073741823} 变成 {@code "1024.0 MB"}——读者看到"1024 KB"，会以为该进位却没进位。</p>
     *
     * <p>修法：判档前先按目标精度**四舍五入**，用舍入后的值判档。这样 1048575 先舍到 1024.0，
     * 判档看到 1024.0 ≥ 1024 → 进 MB 档，输出 {@code "1.0 MB"}，与直觉一致。</p>
     */
    private String formatBytes(long bytes) {
        if (bytes < 0) return bytes + " B";
        // KB 档用 1 位小数 -> 舍入步长 0.1 KB = 102.4 字节
        if (bytes < 1024) return bytes + " B";
        double kb = Math.round(bytes / 102.4) / 10.0;   // 与 %.1f KB 同精度
        if (kb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        double mb = Math.round(bytes / (1024.0 * 1024.0) * 10.0) / 10.0;   // 与 %.1f MB 同精度
        if (mb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * 运行时间（秒）→ 时长文案。
     *
     * <p>9D：委托 {@link DurationFormat#uptime(long)}（入参换算成毫秒）。报告是落盘产物，
     * 与命令面板共用同一实现，避免"同一份数据在聊天栏和报告里写法不同"。</p>
     */
    private String formatUptime(long seconds) {
        return DurationFormat.uptime(seconds * 1000L);
    }

    private String loadHtmlCss() {
        return """
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                background: #1a1a2e;
                color: #eceff1;
                line-height: 1.6;
            }
            .header {
                background: linear-gradient(135deg, #00BCD4, #00838F);
                padding: 30px;
                text-align: center;
            }
            .header h1 { font-size: 2em; margin-bottom: 10px; }
            .header p { opacity: 0.9; }
            .container { max-width: 1200px; margin: 0 auto; padding: 20px; }
            .section {
                background: #16213e;
                border-radius: 8px;
                padding: 20px;
                margin-bottom: 20px;
            }
            .section h2 {
                color: #00BCD4;
                margin-bottom: 15px;
                font-size: 1.3em;
                border-bottom: 2px solid #00BCD4;
                padding-bottom: 8px;
            }
            .card-grid {
                display: grid;
                grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
                gap: 15px;
            }
            .card {
                background: #0f3460;
                border-radius: 8px;
                padding: 15px;
                border-left: 4px solid #00BCD4;
            }
            .card.good { border-left-color: #69F0AE; }
            .card.warn { border-left-color: #FFB300; }
            .card.critical { border-left-color: #FF5252; }
            .card.unknown { border-left-color: #607d8b; }
            .card.normal { border-left-color: #00BCD4; }
            .card.unknown .card-value { color: #90a4ae; }
            .card-value { font-size: 1.8em; font-weight: bold; color: #4DD0E1; }
            .card.good .card-value { color: #69F0AE; }
            .card.warn .card-value { color: #FFB300; }
            .card.critical .card-value { color: #FF5252; }
            .card-title { font-size: 0.85em; color: #90a4ae; margin-top: 5px; }
            .card-subtitle { font-size: 0.75em; color: #607d8b; margin-top: 3px; }
            table {
                width: 100%;
                border-collapse: collapse;
            }
            th, td {
                padding: 10px 12px;
                text-align: left;
                border-bottom: 1px solid #1a1a2e;
            }
            th { background: #0f3460; color: #00BCD4; }
            tr:hover { background: #1a2744; }
            .chart { margin: 15px 0; }
            /* 图例：两条阈值线的数值 + 比较符。文字走 messages.yml，所以这里只管排版。
               flex + gap 而不是往模板里塞空格/分隔符：文案是可翻译的，
               分隔符若写进 messages.yml 会被各语言自行翻译成不同东西。 */
            .chart-legend {
                display: flex;
                flex-wrap: wrap;
                gap: 14px;
                font-size: 0.75em;
                color: #90a4ae;
                margin-bottom: 6px;
            }
            .chart-legend .legend-critical { color: #FF5252; }
            .chart-legend .legend-warn { color: #FFB300; }
            /* 外层：只负责背景与内边距（视觉上的描边）。position: relative 仅是兜底，
               真正给参考线定位的是内层 .tps-plot —— 见模板处的注释。 */
            .tps-graph {
                position: relative;
                height: 120px;
                padding: 5px;
                background: #0f3460;
                border-radius: 4px;
            }
            /* 绘图区：柱子与参考线**共用**这一个盒子，百分比才同基准。
               原实现把两者都放在带 5px 内边距的 .tps-graph 里：柱高 % 按内容盒算、
               绝对定位的参考线按内边距盒算，15 TPS 的柱顶会比 75% 的红线低约 2.5px。 */
            .tps-plot {
                position: relative;
                display: flex;
                align-items: flex-end;
                gap: 2px;
                height: 100%;
            }
            .bar {
                flex: 1;
                min-width: 4px;
                border-radius: 2px 2px 0 0;
                transition: height 0.3s;
            }
            /* 参考线本身：绝对定位的横线，不参与 flex 布局（否则会挤掉一根柱子）。
               虚线而不是实线：它表达"界限"，不是一根读数。 */
            .tps-threshold {
                position: absolute;
                left: 0;
                right: 0;
                border-top: 1px dashed;
                pointer-events: none;
                z-index: 2;
            }
            .tps-threshold.critical { border-color: #FF5252; }
            .tps-threshold.warn { border-color: #FFB300; }
            .tps-threshold span {
                position: absolute;
                right: 0;
                top: -1.2em;
                font-size: 0.7em;
                background: rgba(15, 52, 96, 0.85);
                padding: 0 3px;
                border-radius: 2px;
            }
            .tps-threshold.critical span { color: #FF5252; }
            .tps-threshold.warn span { color: #FFB300; }
            .chart-labels {
                display: flex;
                justify-content: space-between;
                font-size: 0.75em;
                color: #607d8b;
                margin-top: 5px;
            }
            .mem-bar {
                width: 100%;
                height: 20px;
                background: #0f3460;
                border-radius: 10px;
                overflow: hidden;
                margin: 10px 0;
            }
            .mem-fill {
                height: 100%;
                border-radius: 10px;
                transition: width 0.5s;
            }
            .critical { color: #FF5252; margin: 5px 0; }
            .warning { color: #FFB300; margin: 5px 0; }
            /* 数据过期横幅：红底白字 + 加粗，压在页头三行之下。刻意做成整条色带而不是一行红字——
               旧报告那种"全绿卡片 + 一行小字"的组合里，小字会被当成脚注跳过。 */
            .stale-banner {
                display: inline-block;
                margin-top: 12px;
                padding: 10px 18px;
                background: #B71C1C;
                color: #FFFFFF;
                font-weight: bold;
                border-radius: 6px;
                border-left: 6px solid #FF5252;
            }
            .footer {
                text-align: center;
                padding: 20px;
                color: #607d8b;
                font-size: 0.85em;
            }
            """;
    }
}
