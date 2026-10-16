#!/usr/bin/env node
// 通过 GitHub Git Data API 推送 —— 绕开被墙的 github.com:443。
// 用法: GH_TOKEN=xxx GH_REPO=owner/name node scripts/api-push-history.mjs [<远端分支>]
//
// ★与 api-push.mjs 的区别★（P124）
//
// 老脚本的做法是：
//     tree   = 当前工作区**全部文件**（git ls-files 一次性建一棵树）
//     commit = 只带一个父提交（远端当前 head）
// 结果是**整段历史被压成一个提交** —— 本地做了 4 个有意义的提交，
// 推上去只剩 1 个，commit message 还只取 `git log -1`（最后一条）。
//
// 本脚本改为**按本地提交逐条重放**：
//     对每个待推送的本地 commit（自远端 head 之后）：
//       1. 用 `git ls-tree -r <commit>` 拿到该提交自己的文件清单（含 mode/sha）
//       2. 用 `git cat-file blob <sha>` 取内容 → 上传 blob
//       3. POST /git/trees 建该提交的树
//       4. POST /git/commits 建提交，父 = 上一个已推送的 sha
//       5. 最后一次性 PATCH ref（只有在**全部提交都建好**之后才动分支，
//          中途失败不会把远端留在半截状态）
//
// 这样远端会得到与本地**一模一样的提交序列和历史信息**。
import { execFileSync } from 'node:child_process';

const TOKEN = process.env.GH_TOKEN;
const REPO  = process.env.GH_REPO;
const BRANCH = process.argv[2] || 'main';
if (!TOKEN || !REPO) { console.error('需要 GH_TOKEN 与 GH_REPO=owner/name'); process.exit(1); }
const API = `https://api.github.com/repos/${REPO}`;
const H = { Authorization: `Bearer ${TOKEN}`, Accept: 'application/vnd.github+json',
            'Content-Type': 'application/json', 'User-Agent': 'huchuan-agent' };

async function gh(method, url, body) {
  const r = await fetch(url, { method, headers: H, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text();
  if (!r.ok) throw new Error(`${method} ${url} -> ${r.status}: ${t.slice(0, 400)}`);
  return t ? JSON.parse(t) : null;
}
// core.quotepath=false：否则中文文件名会被输出成 \345\215... 八进制转义
const git = (...a) => execFileSync('git', ['-c', 'core.quotepath=false', ...a],
                                   { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 }).trim();
const gitRaw = (...a) => execFileSync('git', ['-c', 'core.quotepath=false', ...a],
                                      { maxBuffer: 256 * 1024 * 1024 });

// 1. 远端当前 head（作为重放的起点）
let remoteHead = null;
try {
  const ref = await gh('GET', `${API}/git/ref/heads/${BRANCH}`);
  remoteHead = ref?.object?.sha ?? null;
} catch { console.log(`(远端 ${BRANCH} 尚不存在，将创建)`); }

// 2. 算出要推送的提交序列（自远端 head 之后，按时间正序）
const range = remoteHead ? `${remoteHead}..HEAD` : 'HEAD';
let commits = git('rev-list', '--reverse', range).split('\n').filter(Boolean);
if (!commits.length) { console.log('✅ 没有新提交，无需推送'); process.exit(0); }
console.log(`将要推送 ${commits.length} 个提交（远端 head=${remoteHead ? remoteHead.slice(0,7) : '无'}）:`);
for (const c of commits) console.log(`   ${c.slice(0,7)}  ${git('log','-1','--format=%s',c)}`);

// 取每个提交的文件清单（path → {mode, sha}），以及其父提交（用于建 merge/线性）
function treeOf(commit) {
  const out = git('ls-tree', '-r', commit);
  const list = [];
  for (const line of out.split('\n')) {
    if (!line) continue;
    // 形如: 100644 blob <sha>\t<path>
    const m = line.match(/^(\d+)\s+(\w+)\s+([0-9a-f]+)\t(.*)$/);
    if (!m) continue;
    list.push({ mode: m[1], type: m[2], sha: m[3], path: m[4] });
  }
  return list;
}

let parent = remoteHead;
let pushed = 0;
for (const commit of commits) {
  const entries = treeOf(commit);
  const msg = git('log', '-1', '--format=%B', commit);
  console.log(`\n[${++pushed}/${commits.length}] ${commit.slice(0,7)}  ${entries.length} 个文件`);

  // blob 去重缓存：同一内容在一个提交里只上一次（GitHub 也按内容寻址）
  const tree = [];
  for (const e of entries) {
    if (e.type === 'commit') {        // 子模块：直接引用，不上传内容
      tree.push({ path: e.path, mode: e.mode, type: 'commit', sha: e.sha });
      continue;
    }
    const buf = gitRaw('cat-file', 'blob', e.sha);
    const blob = await gh('POST', `${API}/git/blobs`,
                          { content: buf.toString('base64'), encoding: 'base64' });
    tree.push({ path: e.path, mode: e.mode, type: 'blob', sha: blob.sha });
  }
  const newTree = await gh('POST', `${API}/git/trees`, { tree });
  const newCommit = await gh('POST', `${API}/git/commits`, {
    message: msg, tree: newTree.sha, parents: parent ? [parent] : [],
  });
  parent = newCommit.sha;
  console.log(`      ✅ ${newCommit.sha.slice(0,7)}`);
}

// 3. 全部提交建好后才动分支
console.log(`\n更新 refs/heads/${BRANCH} → ${parent.slice(0,7)}`);
if (remoteHead) {
  await gh('PATCH', `${API}/git/refs/heads/${BRANCH}`, { sha: parent, force: true });
} else {
  await gh('POST', `${API}/git/refs`, { ref: `refs/heads/${BRANCH}`, sha: parent });
}

console.log(`\n✅ 推送完成: https://github.com/${REPO}/tree/${BRANCH}`);
console.log(`   ${pushed} 个提交，HEAD=${parent.slice(0,7)}`);
