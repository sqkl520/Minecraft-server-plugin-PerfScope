// java-strings.mjs -- 极小的 Java 源码字符串/调用点提取器。
//
// 为什么自己写而不是用现成 parser：门禁脚本必须能在任何装了 Node 的机器上裸跑，
// 不允许 npm install。这里只需要回答两个问题：
//   1) 某文件里的字符串字面量集合是什么（去掉注释、处理 \" \\ \n \uXXXX、拼接相邻字面量）；
//   2) 某个函数名（put / get / logText ...）的调用点，其第 N 个实参求值后的字符串是什么。
// 不是编译器：解析不了的形态（变量、三元、方法调用）一律返回 null，调用方按"无法静态判定"处理，
// 绝不猜测。

import fs from 'node:fs';

/** 把 Java 字符串字面量的转义还原成真实字符。 */
export function unescapeJava(raw) {
  let out = '';
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c !== '\\') {
      out += c;
      continue;
    }
    const n = raw[++i];
    switch (n) {
      case 'n': out += '\n'; break;
      case 't': out += '\t'; break;
      case 'r': out += '\r'; break;
      case 'b': out += '\b'; break;
      case 'f': out += '\f'; break;
      case '0': out += '\0'; break;
      case 's': out += ' '; break;          // Java 15+ 的 \s
      case '"': out += '"'; break;
      case "'": out += "'"; break;
      case '\\': out += '\\'; break;
      case 'u': {
        // \uXXXX（Java 允许连续多个 u）
        let j = i + 1;
        while (raw[j] === 'u') j++;
        const hex = raw.slice(j, j + 4);
        out += String.fromCharCode(parseInt(hex, 16) || 0);
        i = j + 3;
        break;
      }
      default:
        // 未知转义：保留反斜杠（parity 与 javac 的"宽松"行为不同，但门禁只做包含判断）
        out += '\\' + (n === undefined ? '' : n);
    }
  }
  return out;
}

/**
 * 把源码里的注释替换成等长空白，保留字符串字面量原样。
 * 保留长度让后续位置索引与原文一致，便于报错时给出准确偏移。
 */
export function stripComments(src) {
  const out = src.split('');
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    if (c === '/' && src[i + 1] === '/') {
      while (i < n && src[i] !== '\n') out[i++] = ' ';
    } else if (c === '/' && src[i + 1] === '*') {
      out[i++] = ' ';
      out[i++] = ' ';
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] !== '\n') out[i] = ' ';
        i++;
      }
      if (i < n) { out[i++] = ' '; out[i++] = ' '; }
    } else if (c === '"') {
      // 跳过整个字符串字面量（内部的 // 与 /* 不是注释）
      i++;
      while (i < n) {
        if (src[i] === '\\') { i += 2; continue; }
        if (src[i] === '"') { i++; break; }
        i++;
      }
    } else if (c === "'") {
      i++;
      while (i < n) {
        if (src[i] === '\\') { i += 2; continue; }
        if (src[i] === "'") { i++; break; }
        i++;
      }
    } else {
      i++;
    }
  }
  return out.join('');
}

/** 抽出所有字符串字面量（已反转义），按出现顺序。 */
export function stringLiterals(stripped) {
  const found = [];
  let i = 0;
  const n = stripped.length;
  while (i < n) {
    if (stripped[i] === '"') {
      const start = i + 1;
      i++;
      let raw = '';
      while (i < n && stripped[i] !== '"') {
        if (stripped[i] === '\\') { raw += stripped[i] + (stripped[i + 1] ?? ''); i += 2; continue; }
        raw += stripped[i++];
      }
      found.push({ value: unescapeJava(raw), at: start });
      i++;
    } else {
      i++;
    }
  }
  return found;
}

/** 从 openParen 位置开始找配对的右括号（已 strip 注释，字符串已跳过）。 */
function matchParen(stripped, openParen) {
  let depth = 0;
  for (let i = openParen; i < stripped.length; i++) {
    const c = stripped[i];
    if (c === '"') {
      i++;
      while (i < stripped.length && stripped[i] !== '"') {
        if (stripped[i] === '\\') i++;
        i++;
      }
      continue;
    }
    if (c === '(' || c === '[' || c === '{') depth++;
    else if (c === ')' || c === ']' || c === '}') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/** 按顶层逗号切分实参表达式的原始文本。 */
function splitArgs(stripped, from, to) {
  const args = [];
  let depth = 0;
  let start = from;
  for (let i = from; i < to; i++) {
    const c = stripped[i];
    if (c === '"') {
      i++;
      while (i < to && stripped[i] !== '"') {
        if (stripped[i] === '\\') i++;
        i++;
      }
      continue;
    }
    if (c === '(' || c === '[' || c === '{') depth++;
    else if (c === ')' || c === ']' || c === '}') depth--;
    else if (c === ',' && depth === 0) {
      args.push({ text: stripped.slice(start, i), from: start, to: i });
      start = i + 1;
    }
  }
  args.push({ text: stripped.slice(start, to), from: start, to });
  return args;
}

/**
 * 表达式是否为单个裸标识符（如 put 的 en / zh 形参）。是则返回该标识符，否则 null。
 */
export function evalIdentifier(stripped, from, to) {
  const t = stripped.slice(from, to).trim();
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(t) ? t : null;
}

/**
 * 求值"只由字符串字面量和 + 组成的表达式"。
 * 命中返回 { value, literals }；含变量/方法调用返回 null（无法静态判定）。
 */
export function evalConcatExpr(stripped, from, to) {
  const slice = stripped.slice(from, to);
  // 去掉换行/缩进，但保留字符串原样
  let i = 0;
  let value = '';
  const literals = [];
  let sawToken = false;
  while (i < slice.length) {
    const c = slice[i];
    if (c === '"') {
      let raw = '';
      i++;
      while (i < slice.length && slice[i] !== '"') {
        if (slice[i] === '\\') { raw += slice[i] + (slice[i + 1] ?? ''); i += 2; continue; }
        raw += slice[i++];
      }
      if (i >= slice.length) return null;
      i++;
      const v = unescapeJava(raw);
      value += v;
      literals.push(v);
      sawToken = true;
      continue;
    }
    if (c === '+' || /\s/.test(c)) { i++; continue; }
    // 其它任何字符（标识符、括号、点号、问号）都意味着不能静态求值
    return null;
  }
  if (!sawToken) return null;
  return { value, literals };
}

/**
 * 找所有 `<fnName>(...)` 调用点。
 * @returns {Array<{index:number, args:Array<{text:string,from:number,to:number}>}>}
 */
export function callSites(stripped, fnName) {
  const sites = [];
  // 允许前缀（obj. 或 this. 或 Messages.），但要求不是更长的标识符的一部分
  const re = new RegExp(`(^|[^A-Za-z0-9_$.])${fnName}\\s*\\(`, 'g');
  let m;
  while ((m = re.exec(stripped)) !== null) {
    const open = stripped.indexOf('(', m.index + m[0].length - 1);
    const close = matchParen(stripped, open);
    if (close < 0) continue;
    sites.push({ index: m.index, args: splitArgs(stripped, open + 1, close) });
    re.lastIndex = close;
  }
  return sites;
}

/** 递归读取目录下所有 .java。 */
export function javaFiles(root) {
  const out = [];
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = `${dir}/${e.name}`;
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.java')) out.push(p);
    }
  };
  walk(root);
  return out.sort();
}

/** 读文件并返回 { stripped, raw }。 */
export function readJava(file) {
  const raw = fs.readFileSync(file, 'utf8');
  return { raw, stripped: stripComments(raw) };
}
