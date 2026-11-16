#!/usr/bin/env node
// 样式 token 守卫：颜色只允许出现在 :root 定义里，var(--x) 必须已定义，定义过的必须有人用。
// 目的是锁住 CONSOLE-15 的收敛结果——私有 hex、拼错的 token、以及悄悄退场的死 token 都不该回潮。
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const SRC = new URL('../src/', import.meta.url).pathname;
const HEX = /#[0-9a-fA-F]{3,8}\b/g;
const VAR_USE = /var\((--[\w-]+)/g;
const VAR_DEF = /(--[\w-]+)\s*:/g;

const problems = [];
function walk(dir) {
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

function lineOf(text, index) {
  return text.slice(0, index).split('\n').length;
}

const files = walk(SRC);
const styleSheets = files.filter(f => f.endsWith('.css'));
const components = files.filter(f => f.endsWith('.vue'));
const sources = [...styleSheets, ...components];

// 0) 花括号必须成对。lightningcss 只在 build 阶段报"Invalid empty selector"，vue-tsc 与本文件
//    其余规则都看不见，多一个 } 能一路通过 check 走到构建才炸（曾经真的发生过）。
const stripComments = text => text.replace(/\/\*[\s\S]*?\*\//g, block => block.replace(/[^\n]/g, ' '));
const balanceTargets = [
  ...styleSheets.map(file => [file, readFileSync(file, 'utf8')]),
  ...components.flatMap(file => [...readFileSync(file, 'utf8').matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map(match => [file, match[1]])),
];
for (const [file, raw] of balanceTargets) {
  const text = stripComments(raw);
  const open = [];
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '{') open.push(i);
    else if (text[i] === '}') {
      if (!open.length) { problems.push(`${relative(SRC, file)}:${lineOf(text, i)}：多余的 }`); break; }
      open.pop();
    }
  }
  if (open.length) problems.push(`${relative(SRC, file)}:${lineOf(text, open[0])}：{ 未闭合`);
}

// 1) :root 里的 token 定义；其余位置一律不许出现 hex。
const declared = new Set();
for (const file of styleSheets) {
  const text = readFileSync(file, 'utf8');
  const rootBlock = /:root\s*\{([\s\S]*?)\n\}/.exec(text);
  if (!rootBlock) { problems.push(`${relative(SRC, file)}：找不到 :root，token 层缺失`); continue; }
  const body = rootBlock[1];
  const bodyStart = rootBlock.index + rootBlock[0].indexOf('{');
  for (const match of body.matchAll(VAR_DEF)) declared.add(match[1]);
  for (const match of text.matchAll(HEX)) {
    const insideRoot = match.index > bodyStart && match.index < bodyStart + body.length;
    if (!insideRoot) problems.push(`${relative(SRC, file)}:${lineOf(text, match.index)}：:root 之外禁止硬编码颜色 ${match[0]}`);
  }
}

// 2) 组件的 <style> 里不许出现 hex。
for (const file of components) {
  const text = readFileSync(file, 'utf8');
  for (const block of text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)) {
    for (const match of block[1].matchAll(HEX)) {
      const at = block.index + block[0].indexOf(block[1]) + match.index;
      problems.push(`${relative(SRC, file)}:${lineOf(text, at)}：样式块内禁止硬编码颜色 ${match[0]}，请用 token`);
    }
  }
}

// 3) var(--x) 必须已定义；4) 定义过的 token 必须有人用。
const used = new Set();
for (const file of sources) {
  const text = readFileSync(file, 'utf8');
  for (const match of text.matchAll(VAR_USE)) {
    used.add(match[1]);
    if (!declared.has(match[1])) {
      problems.push(`${relative(SRC, file)}:${lineOf(text, match.index)}：${match[1]} 未在 :root 定义`);
    }
  }
}
for (const name of declared) {
  if (!used.has(name)) problems.push(`src/style.css：${name} 已定义但无人引用，删除或补上用途`);
}

if (problems.length) {
  console.error(`样式 token 检查未通过（${problems.length} 项）：`);
  for (const problem of problems) console.error('  - ' + problem);
  process.exit(1);
}
console.log(`样式 token 检查通过：${declared.size} 个 token 全部已定义且在用，无散落 hex。`);
