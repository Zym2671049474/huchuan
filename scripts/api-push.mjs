#!/usr/bin/env node
// 通过 GitHub Git Data API 推送 —— 绕开被墙的 github.com:443。
// 用法: GH_TOKEN=xxx GH_REPO=owner/name node scripts/api-push.mjs
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const TOKEN = process.env.GH_TOKEN;
const REPO  = process.env.GH_REPO;
if (!TOKEN || !REPO) { console.error('需要 GH_TOKEN 与 GH_REPO=owner/name'); process.exit(1); }
const API = `https://api.github.com/repos/${REPO}`;
const H = { Authorization: `Bearer ${TOKEN}`, Accept: 'application/vnd.github+json',
            'Content-Type': 'application/json', 'User-Agent': 'huchuan-agent' };

async function gh(method, url, body) {
  const r = await fetch(url, { method, headers: H, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text();
  if (!r.ok) throw new Error(`${method} ${url} -> ${r.status}: ${t.slice(0, 300)}`);
  return t ? JSON.parse(t) : null;
}

// core.quotepath=false：否则 git 会把中文文件名输出成 \345\215... 八进制转义
const git = (...a) => execFileSync('git', ['-c', 'core.quotepath=false', ...a], { encoding: 'utf8' }).trim();

// 1. 收集所有被跟踪文件
const files = git('ls-files').split('\n').filter(Boolean);
console.log(`[1/4] ${files.length} 个文件，上传 blob`);

// 2. 创建 blob（base64）
const tree = [];
for (const f of files) {
  const content = readFileSync(f);
  const blob = await gh('POST', `${API}/git/blobs`, {
    content: content.toString('base64'), encoding: 'base64' });
  tree.push({ path: f, mode: '100644', type: 'blob', sha: blob.sha });
}
console.log(`      ✅ ${tree.length} 个 blob`);

// 3. 建 tree
console.log('[2/4] 创建 tree');
const newTree = await gh('POST', `${API}/git/trees`, { tree });

// 4. 建 commit（无父提交 = 首次推送）
console.log('[3/4] 创建 commit');
const msg = git('log', '-1', '--pretty=%B');
// 取当前 main 的 head 作为父提交（仓库已由 .gitkeep 初始化）
let parents = [];
try {
  const ref = await gh('GET', `${API}/git/ref/heads/main`);
  if (ref && ref.object) parents = [ref.object.sha];
} catch (e) { console.log('      (无父提交)'); }
const commit = await gh('POST', `${API}/git/commits`, {
  message: msg, tree: newTree.sha, parents });

// 5. 建/更新 ref
console.log('[4/4] 更新 refs/heads/main');
// main 已存在则 PATCH 更新，否则 POST 创建
try {
  await gh('PATCH', `${API}/git/refs/heads/main`, { sha: commit.sha, force: true });
} catch (e) {
  await gh('POST', `${API}/git/refs`, { ref: 'refs/heads/main', sha: commit.sha });
}

console.log(`✅ 推送完成: https://github.com/${REPO}`);
console.log(`   commit ${commit.sha.slice(0, 7)}  ${files.length} 文件`);
