#!/usr/bin/env node
/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * bStats 分桶选择门禁：每个 {@code *_bucket} 图表必须用它自己量级的桶。
 *
 * <h2>为什么这条不能用单元测试锁</h2>
 * 桶的选择写在 {@code BstatsCharts.register(Metrics, PerfScope, MetricsCollector)} 里，
 * 而那些 lambda 要拿到真的 {@code Metrics} 与 {@code MetricsCollector} 才会执行——
 * 两者都依赖 bStats 类与 Bukkit 服务端，测试环境跑不起来。
 *
 * <p>用反射读常量也不够：常量本身是对的，错的是<b>哪个图表引用哪个常量</b>。
 * 实测过一次——测试里自己声明两个数组然后断言它们对不同值给不同标签，
 * 那是自证；把 {@code register()} 里的 {@code PLAYER_BUCKETS} 改回
 * {@code ENTITY_BUCKETS}，测试照样全绿。所以这条只能看源码。
 *
 * <h2>它挡的是什么</h2>
 * 曾经的真实 bug：{@code peak_players_bucket} 复用了 {@code ENTITY_BUCKETS}
 * （边界 0/100/500/…/50000），于是 0–100 玩家全被合并成同一条柱子、
 * 溢出标签写 {@code "50000+"}。后果是 bStats 后台那张图<b>读不出大服与小服的差别</b>，
 * 而它存在的唯一目的就是回答"大服到底有多大"。
 *
 * <p>这类错误零异常、零日志，只在遥测后台的图形上体现——属于"数据不可信但没人查"的典型。
 * 所以用门禁把它变成"改了就红"。
 *
 * <h2>扩充方式</h2>
 * 在 {@link CHART_RULES} 里加一条。每条声明：图表 id、该图表<b>必须</b>用的桶常量、
 * 该常量的<b>边界数值</b>、溢出标签、以及这个指标量级的合理区间。新增 {@code *_bucket}
 * 图表而这里没登记时，脚本会报"未登记"——白名单而非黑名单，逼着新图表显式声明自己的量级。
 *
 * <h2>为什么除了桶名还要比数值（这条门禁曾经只看得见一半）</h2>
 *
 * 旧实现只比较标识符名：{@code peak_players_bucket} 引用的常量名是 {@code PLAYER_BUCKETS}
 * 就算过。于是把 {@code PLAYER_BUCKETS} 的值改成 {@code {0,2,5,10}}——0 到 10 人合成一档、
 * 11 人以上就是溢出档"10+"，20 人的服和 200 人的服在图上是同一根柱子——门禁<b>照样 PASS</b>。
 * 而这恰恰是本门禁存在的理由："players 复用了 entity 桶"那次事故的<b>表现</b>就是刻度数值错。
 * 名字对了、数值错了，是同一个 bug 换了个更隐蔽的形态。
 *
 * <h2>比什么：字面量 + 量级，两道都要</h2>
 *
 * <p>只钉字面量不够：有人为了"修绿"会把实体桶的值原样抄进玩家桶的期望值里，差异就消失了。</p>
 *
 * <p>只做量级判断也不够：{@code {0,2,5,10}} 完全落在"玩家不超过几千"这个宽松区间内，
 * 语义上"说得通"，但刻度已经退化成几乎不可用。所以两道并行：</p>
 *
 * <ol>
 *   <li><b>字面量</b>——{@code expect} 是该桶精确的期望数组。任何数值漂移都要人显式改本脚本，
 *       于是"改刻度"变成 diff 里看得见的一行，而不是藏在数据文件里；</li>
 *   <li><b>量级区间</b>——{@code maxRange} 给出这个指标最后一桶的合理上下限。即使有人
 *       连期望值一起改（把实体桶抄进玩家桶），区间也会把"玩家桶溢出档 50000"这种
 *       量级错误单独判红，并给出"这个指标到底多大"的具体理由。</li>
 * </ol>
 *
 * <p>三条结构不变量也一并校验：边界必须严格递增（否则 {@code bucket()} 的 {@code <=} 扫描
 * 会给出错标签）、溢出标签必须等于"最后一桶+{@code +}"（否则图形标签与刻度对不上）、
 * 内联数组（heap/world）同样钉住（它们以前完全不受检）。</p>
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..');
const TARGET = path.join(REPO, 'src', 'main', 'java', 'cn', 'perfscope', 'BstatsCharts.java');

/**
 * 把注释替换成等长空白（保留换行与字符串字面量原样）。
 *
 * <p>与 {@code check-reload-rebuild.mjs} 的同名需求一致，但这里刻意<b>保留字符串</b>：
 * 本脚本要读的溢出标签 {@code "200+"} 就在引号里。注释必须剥掉，否则一个
 * {@code // private static final int[] PLAYER_BUCKETS = {0, 5, 10, ...};} 的旧注释行
 * 会被 {@code readConstArray()} 的 {@code match()} 先命中——实测真实值换成实体量级后
 * 门禁照样 PASS。注释里的数组声明不是代码。</p>
 */
function stripComments(src) {
    let out = '';
    let i = 0;
    let state = 'code';
    while (i < src.length) {
        const c = src[i];
        const d = src[i + 1];
        if (state === 'code') {
            if (c === '/' && d === '/') { state = 'line'; out += '  '; i += 2; continue; }
            if (c === '/' && d === '*') { state = 'block'; out += '  '; i += 2; continue; }
            if (c === '"') { state = 'string'; out += c; i++; continue; }
            out += c; i++; continue;
        }
        if (state === 'line') {
            if (c === '\n') { state = 'code'; out += '\n'; i++; continue; }
            out += ' '; i++; continue;
        }
        if (state === 'block') {
            if (c === '*' && d === '/') { state = 'code'; out += '  '; i += 2; continue; }
            out += (c === '\n') ? '\n' : ' '; i++; continue;
        }
        // string：原样保留（溢出标签要读它），只处理转义
        if (c === '\\') { out += src.slice(i, i + 2); i += 2; continue; }
        if (c === '"') { state = 'code'; out += c; i++; continue; }
        out += (c === '\n') ? '\n' : c; i++;
    }
    return out;
}

/**
 * 图表 → 必须使用的桶常量 + 该桶的精确边界数值 + 溢出标签 + 量级区间。
 *
 * <p>{@code bucket} 为 {@code null} 表示数组内联在调用点（用 {@code expect} 钉住数值）。</p>
 *
 * <ul>
 *   <li>{@code expect}：该图表要求的确切边界数组。改动必须同步改本脚本。</li>
 *   <li>{@code overflow}：要求的溢出标签，必须等于 {@code expect} 最后一桶 + "+"}。</li>
 *   <li>{@code maxRange}：{@code [下限, 上限]}，这个指标最后一桶的合理量级。
 *       用来单独抓"拿别的指标的量级当自己的"——名字和数组都可能被一起改，但量级不会骗人。</li>
 * </ul>
 */
const CHART_RULES = [
    {
        id: 'entity_count_bucket',
        bucket: 'ENTITY_BUCKETS',
        expect: [0, 100, 500, 1000, 2500, 5000, 10000, 25000, 50000],
        overflow: '50000+',
        maxRange: [1000, 1000000],
        why: '全服实体总数，量级从 0 到数万',
    },
    {
        id: 'loaded_chunks_bucket',
        bucket: 'CHUNK_BUCKETS',
        expect: [0, 100, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000],
        overflow: '100000+',
        maxRange: [1000, 2000000],
        why: '已加载区块数，量级比实体再大一个数量级',
    },
    {
        id: 'peak_players_bucket',
        bucket: 'PLAYER_BUCKETS',
        expect: [0, 5, 10, 20, 50, 100, 200],
        overflow: '200+',
        maxRange: [100, 5000],
        why: '在线玩家数——量级只有几百，用实体桶会把 0-100 人合并成一档、'
            + '溢出标签写成「50000+」，bStats 后台读不出大服与小服的差别',
    },
    {
        id: 'heap_max_mb_bucket',
        bucket: null,   // 内联数组，见源码
        expect: [1024, 2048, 4096, 8192, 16384, 32768, 65536],
        overflow: '65536+',
        maxRange: [8192, 1048576],
        why: '堆上限（MB），桶内联在调用点，不引用常量',
    },
    {
        id: 'world_count',
        bucket: null,   // 内联数组 {1,3,5,10}
        expect: [1, 3, 5, 10],
        overflow: '10+',
        maxRange: [5, 200],
        why: '世界数，桶内联在调用点',
    },
];

const UNREGISTERED_HINT = [
    '新增的 *_bucket 图表需要在本脚本的 CHART_RULES 里登记它该用哪个桶。',
    '这是白名单：不登记就报错，逼着作者显式声明这个指标的量级——',
    '漏登记正是「玩家数复用实体桶」那类错误的入口。',
];

/** 两个整数数组是否逐项相同。 */
function sameArray(a, b) {
    return a.length === b.length && a.every((v, i) => v === b[i]);
}

/** 从 `int[] { ... }` 的源码片段里解出整数数组；含非数字项则返回 null（不猜）。 */
function parseIntList(text) {
    const nums = text.split(',').map((s) => s.trim()).filter((s) => s.length > 0);
    const out = [];
    for (const s of nums) {
        if (!/^-?\d+$/.test(s)) return null;   // `1 << 10`、`CAP` 之类一律不猜
        out.push(Number(s));
    }
    return out.length > 0 ? out : null;
}

/**
 * 读一个 `static final int[] NAME = { ... };` 常量的边界数值。
 * @returns {{values:number[], line:number}|null}
 */
function readConstArray(source, name) {
    const re = new RegExp(String.raw`int\[\]\s+${name}\s*=\s*\{([^}]*)\}`);
    const m = source.match(re);
    if (!m) return null;
    const values = parseIntList(m[1]);
    if (values === null) return null;
    return { values, line: source.slice(0, source.indexOf(m[0])).split('\n').length };
}

/**
 * 读某个图表 lambda 里 `bucket(x, new int[]{...}, "label")` 的**内联**边界与溢出标签。
 * @returns {{values:number[], overflow:string|null, line:number}|null}
 */
function readInlineArray(source, chartId) {
    const re = new RegExp(
        String.raw`"${chartId}"[\s\S]{0,400}?bucket\(\s*[^,]+,\s*new\s+int\[\]\s*\{([^}]*)\}\s*,\s*"([^"]*)"`,
    );
    const m = source.match(re);
    if (!m) return null;
    const values = parseIntList(m[1]);
    if (values === null) return null;
    return {
        values,
        overflow: m[2],
        line: source.slice(0, source.indexOf(m[0])).split('\n').length,
    };
}

/**
 * 读某个图表 lambda 里 `bucket(x, CONST, "label")` 的**溢出标签**（常量引用的形态）。
 * @returns {string|null}
 */
function readConstOverflow(source, chartId) {
    const re = new RegExp(
        String.raw`"${chartId}"[\s\S]{0,400}?bucket\(\s*[^,]+,\s*\w+\s*,\s*"([^"]*)"`,
    );
    const m = source.match(re);
    return m ? m[1] : null;
}

function main() {
    if (!fs.existsSync(TARGET)) {
        console.error(`source not found: ${TARGET}`);
        process.exit(2);
    }
    // 先剥注释再做一切判断：注释里的同名数组声明不是代码，能骗过 readConstArray()。
    // 等长空白替换，所以行号与原文一致。
    const source = stripComments(fs.readFileSync(TARGET, 'utf8'));
    const lines = source.split('\n');
    const rel = path.relative(REPO, TARGET).replace(/\\/g, '/');

    const errors = [];

    // 1. 收集源码里所有 *_bucket 图表 id（以及 world_count 这类非 bucket 命名的已知图表）
    const chartIds = new Set();
    const idRe = /new (?:SimpleBarChart|SingleLineChart|SimplePie|AdvancedBarChart|AdvancedPie|DrilldownPie)\("([a-z0-9_]+)"/g;
    let m;
    while ((m = idRe.exec(source)) !== null) {
        chartIds.add(m[1]);
    }

    // 2. 已登记的图表必须用对桶，且桶的**数值**必须与登记一致
    for (const rule of CHART_RULES) {
        if (rule.bucket !== null) {
            // 2a. 抓 "chartId" 之后同一 lambda 里的第一次 bucket( 调用的第二个参数
            const re = new RegExp(
                String.raw`"${rule.id}"[\s\S]{0,400}?bucket\(\s*[^,]+,\s*(\w+)`,
            );
            const hit = source.match(re);
            if (!hit) {
                errors.push({
                    chart: rule.id,
                    detail: `找不到 "${rule.id}" 的 bucket(...) 调用——`
                        + '图表可能被改名/删除，或结构改到本脚本的正则覆盖不到。'
                        + '若是有意重构，同步更新 CHART_RULES。',
                });
                continue;
            }
            if (hit[1] !== rule.bucket) {
                const line = source.slice(0, source.indexOf(hit[0])).split('\n').length;
                errors.push({
                    chart: rule.id,
                    line,
                    detail: `"${rule.id}" 用了 ${hit[1]}，但应当用 ${rule.bucket}。`
                        + `理由：${rule.why}`,
                });
                continue;
            }
        }

        // 2b. 取实际边界数值：常量引用则解引用，内联数组则就地解析
        const actual = rule.bucket !== null
            ? readConstArray(source, rule.bucket)
            : readInlineArray(source, rule.id);
        if (actual === null) {
            errors.push({
                chart: rule.id,
                detail: rule.bucket !== null
                    ? `找不到 ${rule.bucket} 的声明（或声明不是 int[] = { ... } 字面量）。`
                        + '桶名对了但数值无从核对——本脚本的判断前提失效，请同步更新。'
                    : `找不到 "${rule.id}" 的内联数组（或下次序调整后结构变了）。`,
            });
            continue;
        }
        if (rule.bucket !== null && actual.overflow === undefined) {
            // 常量引用形态的溢出标签在调用点，不在常量声明处
            actual.overflow = readConstOverflow(source, rule.id);
        }
        const bucketLabel = rule.bucket === null ? '内联桶' : rule.bucket;

        // 2c. 精确数值
        if (!sameArray(actual.values, rule.expect)) {
            errors.push({
                chart: rule.id,
                line: actual.line,
                detail: `${bucketLabel} 的实际边界是 `
                    + `{${actual.values.join(', ')}}，本门禁钉住的是 {${rule.expect.join(', ')}}。`
                    + `理由：${rule.why}。`
                    + '刻度数值漂移与"桶名放错"是同一个 bug 的两种形态——图会变得读不出量级，'
                    + '而零异常、零日志。确属有意调整请在 CHART_RULES 里同步 expect。',
            });
        }

        // 2d. 溢出标签
        if (actual.overflow !== rule.overflow) {
            errors.push({
                chart: rule.id,
                line: actual.line,
                detail: `溢出标签是 "${actual.overflow}"，但边界最后一桶为 `
                    + `${rule.expect[rule.expect.length - 1]}，两者对不上（应为 "${rule.overflow}"）。`
                    + '标签与刻度不一致时，图形上"最大的那一档"到底代表多少就没人说得清。',
            });
        }

        // 2e. 结构不变量：严格递增（bucket() 用 value <= bound 顺序扫描，递减会给出错标签）
        for (let k = 1; k < actual.values.length; k++) {
            if (!(actual.values[k] > actual.values[k - 1])) {
                errors.push({
                    chart: rule.id,
                    line: actual.line,
                    detail: `边界 {${actual.values.join(', ')}} 在第 ${k} 项不是严格递增`
                        + `（${actual.values[k - 1]} → ${actual.values[k]}）。`
                        + 'bucket() 用 value <= bound 顺序扫描，等价边界会让相邻两档永远取到同一根柱子。',
                });
                break;
            }
        }

        // 2f. 量级（这道单独抓"连期望值一起改成了别的指标的量级"）
        const top = actual.values[actual.values.length - 1];
        if (top < rule.maxRange[0] || top > rule.maxRange[1]) {
            errors.push({
                chart: rule.id,
                line: actual.line,
                detail: `溢出档是 ${top}，不在本指标的量级区间 [${rule.maxRange[0]}, ${rule.maxRange[1]}] 内。`
                    + `"${rule.id}" 的语义是：${rule.why}。`
                    + '桶数组取自另一个指标的量级时，图形读不出大服与小服的差别；'
                    + '即使桶名和期望值都被一起改过，这一条也会独立判红。',
            });
        }
    }

    // 3. 源码里有、CHART_RULES 里没有的图表 → 报错（白名单）
    const registered = new Set(CHART_RULES.map((r) => r.id));
    for (const id of chartIds) {
        if (!registered.has(id)) {
            // 只有 *_bucket 与已知的量级图表需要登记；性别/版本之类无关
            if (id.endsWith('_bucket') || id === 'world_count') {
                const line = source.split('\n')
                    .findIndex((l) => l.includes(`"${id}"`)) + 1;
                errors.push({
                    chart: id,
                    line,
                    detail: `"${id}" 未在 CHART_RULES 里登记。${UNREGISTERED_HINT.join(' ')}`,
                });
            }
        }
    }

    // 4. 反向检查：登记的图表是否真的存在
    for (const rule of CHART_RULES) {
        if (!chartIds.has(rule.id)) {
            errors.push({
                chart: rule.id,
                detail: `CHART_RULES 登记了 "${rule.id}"，但源码里找不到这个图表——`
                    + '图表被删了却忘了删登记项。',
            });
        }
    }

    console.log('PerfScope bStats bucket-selection check');
    console.log(`  repo  : ${REPO}`);
    console.log(`  source: ${rel}`);
    console.log(`  charts: ${chartIds.size} found, ${CHART_RULES.length} registered`);
    console.log('');

    if (errors.length === 0) {
        for (const rule of CHART_RULES) {
            const used = rule.bucket === null ? '(inline)' : rule.bucket;
            console.log(`  ok  ${rule.id.padEnd(24)} ${used.padEnd(16)} `
                + `{${rule.expect.join(', ')}} → "${rule.overflow}"`);
        }
        console.log('');
        console.log('BSTATS BUCKET CHECK: PASS');
        process.exit(0);
    }

    for (const e of errors) {
        const where = e.line ? `:${e.line}` : '';
        console.log(`  FAIL  ${e.chart}${where}`);
        console.log(`        ${e.detail}`);
    }
    console.log('');
    console.log(`BSTATS BUCKET CHECK: FAIL (${errors.length} problem(s))`);
    process.exit(1);
}

main();
