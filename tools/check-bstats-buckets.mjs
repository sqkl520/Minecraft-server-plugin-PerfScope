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
 * 以及为什么是这个桶。新增 {@code *_bucket} 图表而这里没登记时，脚本会报"未登记"
 * ——白名单而非黑名单，逼着新图表显式声明自己的量级。
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..');
const TARGET = path.join(REPO, 'src', 'main', 'java', 'cn', 'perfscope', 'BstatsCharts.java');

/**
 * 图表 → 必须使用的桶常量。
 *
 * `bounds` 只用于"未登记图表"的提示，不参与判定。
 */
const CHART_RULES = [
    {
        id: 'entity_count_bucket',
        bucket: 'ENTITY_BUCKETS',
        why: '全服实体总数，量级从 0 到数万',
    },
    {
        id: 'loaded_chunks_bucket',
        bucket: 'CHUNK_BUCKETS',
        why: '已加载区块数，量级比实体再大一个数量级',
    },
    {
        id: 'peak_players_bucket',
        bucket: 'PLAYER_BUCKETS',
        why: '在线玩家数——量级只有几百，用实体桶会把 0-100 人合并成一档、'
            + '溢出标签写成「50000+」，bStats 后台读不出大服与小服的差别',
    },
    {
        id: 'heap_max_mb_bucket',
        bucket: null,   // 内联数组，见源码 128-134 行
        why: '堆上限（MB），桶内联在调用点，不引用常量',
    },
    {
        id: 'world_count',
        bucket: null,   // 内联数组 {1,3,5,10}
        why: '世界数，桶内联在调用点',
    },
];

const UNREGISTERED_HINT = [
    '新增的 *_bucket 图表需要在本脚本的 CHART_RULES 里登记它该用哪个桶。',
    '这是白名单：不登记就报错，逼着作者显式声明这个指标的量级——',
    '漏登记正是「玩家数复用实体桶」那类错误的入口。',
];

function main() {
    if (!fs.existsSync(TARGET)) {
        console.error(`source not found: ${TARGET}`);
        process.exit(2);
    }
    const source = fs.readFileSync(TARGET, 'utf8');
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

    // 2. 已登记的图表必须用对桶
    for (const rule of CHART_RULES) {
        if (rule.bucket === null) {
            continue;   // 内联数组，无从校验
        }
        // 抓 "chartId" 之后同一 lambda 里的第一次 bucket( 调用的第二个参数
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
            console.log(`  ok  ${rule.id.padEnd(24)} ${used}`);
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
