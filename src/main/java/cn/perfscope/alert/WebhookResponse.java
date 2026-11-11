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
package cn.perfscope.alert;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Webhook 响应的判定结果（纯函数，无 Bukkit、无 IO，可脱离服务端单测）。
 *
 * <p><b>这个类存在的唯一理由</b>：企业微信 / 飞书 / 钉钉在<b>业务失败时依然返回 HTTP 200</b>，
 * 把错误码写在响应体里。旧实现用 {@code BodyHandlers.discarding()} 丢掉响应体、只看
 * {@code statusCode >= 300}，于是这三家的每一次失败都被当成成功——配置错了、签名错了、
 * 被限流了、机器人被移出群了，全都<b>一条日志都不打</b>。</p>
 *
 * <p>同样的问题<b>在现有的 OneBot 上已经存在</b>：OneBot v11 的 {@code /send_private_msg}
 * 恒定返回 HTTP 200，成败写在 {@code retcode} 里。所以本类不是"为新平台准备的基础设施"，
 * 它修的是一个已经发布的 bug。</p>
 *
 * <p><b>各平台判据</b>（全部要求"显式成功"，而不是"没有明显失败"）：</p>
 * <table border="1">
 *   <caption>成功判据</caption>
 *   <tr><th>type</th><th>成功条件</th><th>说明</th></tr>
 *   <tr><td>discord</td><td>HTTP 2xx（成功是 204）</td><td>错误走 4xx/5xx，无业务码</td></tr>
 *   <tr><td>onebot</td><td>{@code retcode == 0}，或 {@code status == "ok"}</td><td>HTTP 恒 200</td></tr>
 *   <tr><td>wecom</td><td>{@code errcode == 0}</td><td>HTTP 恒 200</td></tr>
 *   <tr><td>feishu</td><td>{@code code == 0}（回退 {@code StatusCode == 0}）</td><td>HTTP 恒 200</td></tr>
 *   <tr><td>dingtalk</td><td>{@code errcode == 0}</td><td>HTTP 恒 200</td></tr>
 * </table>
 *
 * <p><b>解析失败一律判失败</b>，绝不"解析不出来就当成功"。这条看似保守，实际是防一个真实场景：
 * 反向代理/门户认证返回 HTTP 200 的 HTML 登录页时，"catch 里 return true"会让投递永久显示成功。</p>
 */
public final class WebhookResponse {

    /** 投递结果的分类。 */
    public enum Outcome {
        /** 平台明确接受。 */
        OK,
        /** HTTP 层失败（4xx/5xx）——优先于业务码判定。 */
        HTTP_ERROR,
        /** HTTP 200 但平台业务码表示失败。 */
        BIZ_ERROR,
        /** 响应不是可判定的 JSON / 缺关键字段。 */
        BAD_BODY
    }

    /** 解析结果。{@code bizCode} 仅 BIZ_ERROR 时有意义（其他情况为 {@link Long#MIN_VALUE}）。 */
    public record Result(Outcome outcome, int httpStatus, long bizCode, String detail) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }

        static Result ok(int httpStatus) {
            return new Result(Outcome.OK, httpStatus, Long.MIN_VALUE, "");
        }

        static Result httpError(int httpStatus) {
            return new Result(Outcome.HTTP_ERROR, httpStatus, Long.MIN_VALUE, "HTTP " + httpStatus);
        }

        static Result bizError(int httpStatus, long code, String msg) {
            return new Result(Outcome.BIZ_ERROR, httpStatus, code, sanitize(msg));
        }

        static Result badBody(int httpStatus, String why) {
            return new Result(Outcome.BAD_BODY, httpStatus, Long.MIN_VALUE, why);
        }
    }

    private WebhookResponse() {
    }

    /**
     * 判定一次投递是否成功。
     *
     * @param type       平台类型（discord / onebot / wecom / feishu / dingtalk）
     * @param httpStatus HTTP 状态码
     * @param body       响应体（可以为 null 或空；调用方应已做长度上限保护）
     * @return 判定结果，永不为 null
     */
    public static Result parse(String type, int httpStatus, String body) {
        // HTTP 错误优先：Discord 的失败走 4xx/5xx，此时 body 可能是任意内容
        if (httpStatus < 200 || httpStatus >= 300) {
            return Result.httpError(httpStatus);
        }

        String t = type == null ? "" : type;
        // Discord 成功是 204 且没有 body；它不用业务码，HTTP 2xx 即成功
        if ("discord".equals(t)) {
            return Result.ok(httpStatus);
        }

        JsonObject json = parseObject(body);
        if (json == null) {
            // 200 + 非 JSON = 反向代理/门户页/空体，不能当成功
            return Result.badBody(httpStatus, "expected JSON body, got something else");
        }

        return switch (t) {
            case "onebot" -> parseOneBot(httpStatus, json);
            case "wecom", "dingtalk" -> parseErrcode(httpStatus, json);
            case "feishu" -> parseFeishu(httpStatus, json);
            // 未知类型：能走到这里说明 reload 校验漏了。宁可报失败也不要假装成功。
            default -> Result.badBody(httpStatus, "unknown webhook type");
        };
    }

    /**
     * 企业微信 / 钉钉：{@code {"errcode":0,"errmsg":"ok"}}。
     *
     * <p><b>errcode 可能是数字也可能是字符串</b>：钉钉官方文档的字段表写 {@code Number}，
     * 同页的响应示例却写成 {@code "errcode":"0"}，而实测返回的是数字。所以这里按"两种都接受"处理，
     * 用 {@code asText()} 再解析，绝不用 {@code getAsInt()}（遇到字符串会抛）。</p>
     */
    private static Result parseErrcode(int httpStatus, JsonObject json) {
        Long code = lenientLong(json.get("errcode"));
        if (code == null) {
            return Result.badBody(httpStatus, "no errcode field");
        }
        if (code == 0L) {
            return Result.ok(httpStatus);
        }
        String msg = lenientString(json.get("errmsg"));
        // 钉钉的 310000 是个聚合码：签名不匹配/关键词缺失/IP 不在白名单共用它，靠 errmsg 区分
        return Result.bizError(httpStatus, code, msg);
    }

    /**
     * 飞书：{@code {"code":0,"msg":"success"}}。
     *
     * <p>同时存在 {@code StatusCode}/{@code StatusMessage}（官方标注为"冗余字段，用于兼容存量历史逻辑，
     * 不建议使用"）。因此 <b>优先读 {@code code}</b>，只有它缺失时才回退到 {@code StatusCode}——
     * 两者在成功时都是 0，写错不会在成功路径暴露，只会在失败路径给出错误判断。</p>
     */
    private static Result parseFeishu(int httpStatus, JsonObject json) {
        Long code = lenientLong(json.get("code"));
        if (code == null) {
            code = lenientLong(json.get("StatusCode"));
        }
        if (code == null) {
            return Result.badBody(httpStatus, "no code field");
        }
        if (code == 0L) {
            return Result.ok(httpStatus);
        }
        String msg = lenientString(json.get("msg"));
        if (msg.isEmpty()) {
            msg = lenientString(json.get("StatusMessage"));
        }
        return Result.bizError(httpStatus, code, msg);
    }

    /**
     * OneBot v11：{@code {"status":"ok","retcode":0}} 或 {@code {"status":"failed","retcode":100}}。
     *
     * <p><b>顺序很重要</b>：先看 {@code status}。异步发送的响应是
     * {@code {"status":"async","retcode":1}}——retcode 非 0 但消息已经入队待发，
     * 这在 OneBot 语义里算<b>成功</b>。若先按 retcode 判，会把每一次异步发送都报成失败。</p>
     *
     * <p>{@code status} 缺失时才回退到 {@code retcode}（部分实现只回 retcode）。
     * 两者都没有 = 不可判定 = 失败。</p>
     */
    private static Result parseOneBot(int httpStatus, JsonObject json) {
        String status = lenientString(json.get("status"));
        if ("async".equalsIgnoreCase(status)) {
            return Result.ok(httpStatus);
        }
        if ("ok".equalsIgnoreCase(status)) {
            return Result.ok(httpStatus);
        }
        if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
            Long retcode = lenientLong(json.get("retcode"));
            return Result.bizError(httpStatus, retcode == null ? Long.MIN_VALUE : retcode,
                lenientString(json.get("msg")) + lenientString(json.get("wording")));
        }
        // status 缺失或无法识别：回退到 retcode
        Long retcode = lenientLong(json.get("retcode"));
        if (retcode != null) {
            if (retcode == 0L) {
                return Result.ok(httpStatus);
            }
            // retcode 非 0 且没有 status 可判——保守判失败，但把码带出来便于排查
            return Result.bizError(httpStatus, retcode,
                lenientString(json.get("msg")) + lenientString(json.get("wording")));
        }
        return Result.badBody(httpStatus, "no status and no retcode");
    }

    // ==================== 工具 ====================

    private static JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(body);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            // 非 JSON（HTML 门户页、代理错误页、被截断的响应）
            return null;
        }
    }

    /** 宽松读取整数：兼容 JSON number 与数字字符串；读不出返回 null。 */
    private static Long lenientLong(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            String s = el.getAsString().trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String lenientString(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "";
        }
        try {
            return el.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 清理平台返回的消息文本，供日志使用。
     *
     * <p>两个理由：① 长度上限，平台偶尔会回很长的提示，日志不该被它撑爆；
     * ② <b>去掉 URL 形态的片段</b>——某些平台的错误信息会回显请求地址，而 webhook URL 内嵌令牌。
     * 这与本类所在包既有的"日志绝不出现完整 URL"纪律一致。</p>
     */
    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String cleaned = s.replaceAll("https?://\\S+", "<url>")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim();
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200) + "…";
    }
}
