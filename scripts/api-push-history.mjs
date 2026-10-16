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
// ★分支名必须只取「非选项」参数★（P125）
//
// 原来写的是 `process.argv[2] || 'main'`，于是
//     node scripts/api-push-history.mjs --force-history
// 会把 **`--force-history` 当成分支名** —— 结果：23 个提交全建好了，
// 却推到了一个叫 `--force-history` 的新分支上，`main` 纹丝不动
// （实测踩到：远端出现 `--force-history → 9c7db1d`，而 main 仍是旧 head）。
//
// 现在过滤掉所有以 `-` 开头的参数，剩下的第一个才是分支名。
const positional = process.argv.slice(2).filter((a) => !a.startsWith('-'));
const BRANCH = positional[0] || 'main';
if (!TOKEN || !REPO) { console.error('需要 GH_TOKEN 与 GH_REPO=owner/name'); process.exit(1); }
console.log(`目标分支: refs/heads/${BRANCH}`);
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

// 1b. ★--head=<sha> 快速修分支★（P125）—— 必须放在分叉检查**之前**
//
// 上一轮推送把 23 个提交/树都建好了，却因分支名 bug 没更新 main；
// 提交对象已在远端，重跑没必要再传一遍 blob。这里先确认该提交确实存在，
// 再把分支指过去。放在分叉检查前面，是因为修复分支本来就不需要重放历史。
const headArg = process.argv.find((a) => a.startsWith('--head='));
if (headArg) {
  const sha = headArg.slice('--head='.length);
  await gh('GET', `${API}/git/commits/${sha}`);       // 不存在会在此抛错
  console.log(`直接更新 refs/heads/${BRANCH} → ${sha.slice(0,7)}（复用已存在的提交）`);
  await gh('PATCH', `${API}/git/refs/heads/${BRANCH}`, { sha, force: true });
  console.log(`\n✅ 推送完成: https://github.com/${REPO}/tree/${BRANCH}`);
  console.log(`   HEAD=${sha.slice(0,7)}`);
  process.exit(0);
}

// 2. 算出要推送的提交序列（按时间正序）
//
// ★分叉怎么处理★（P125）
//
// 不能无脑用 `${remoteHead}..HEAD`：远端历史可能是**老 api-push.mjs 压平重写**
// 出来的，那些 SHA 在本地根本不存在 → `fatal: Invalid revision range`。
// 实测本仓库就是这种情况（远端 9 个提交 vs 本地 22 个，**零个共同 SHA**，
// 但两边最新的树逐字节一致）。
//
// 判定远端 head 与本地历史的关系（★三种情况，不能只看 SHA 是否存在★）
//
//   ① 远端 head 就在本地历史里        → 正常差集推送
//   ② 远端 head 不在本地，但**树 == 本地某个提交的树**
//      → 这是**本脚本自己上一轮推送的结果**（重放会造出新 SHA，
//        所以远端 SHA 永远不在本地历史里！），内容完全等价，
//        应安全地增量推送，**不需要 --force-history**
//   ③ 其他                            → 真·历史分叉，默认拒绝
//
// ⚠️ 只判断 ① 是错的：本脚本每次重放都会产生新 SHA，
//    第二次推送时必然落入 ②，会被误报成"历史分叉"而拒绝推送。
//    （实测踩到：远端 9c7db1d 是本地 803b570 的重放，tree 完全相同。）
let diverged = false;
let sameTree = false;
if (remoteHead) {
  try {
    git('cat-file', '-e', `${remoteHead}^{commit}`);
  } catch {
    // 远端 head 不在本地 → 取它的 tree，看是否等于本地某个提交的 tree
    try {
      const rc = await gh('GET', `${API}/git/commits/${remoteHead}`);
      const remoteTree = rc?.tree?.sha;
      if (remoteTree) {
        const locals = git('rev-list', 'HEAD').split('\n').filter(Boolean);
        sameTree = locals.some((c) => git('rev-parse', `${c}^{tree}`) === remoteTree);
      }
    } catch (e) {
      // 取不到就当成分叉，走保守路径
    }
    diverged = !sameTree;
    if (sameTree) {
      console.log(`ℹ️ 远端 head ${remoteHead.slice(0,7)} 不在本地历史，但其 tree 与本地某提交一致`);
      console.log(`   → 判定为本脚本上一轮的重放结果（内容等价），按增量推送。`);
    }
  }
}

const FORCE = process.argv.includes('--force-history');
if (diverged && !FORCE) {
  console.error(`\n❌ 远端 ${BRANCH} 的 head ${remoteHead.slice(0,7)} 不在本地历史中 —— 历史已分叉。`);
  console.error(`   本地提交 ${git('rev-list','--count','HEAD')} 个，远端 ${BRANCH} 是另一套历史。`);
  console.error(`   且其 tree 与本地任何提交都不相同（内容确实不同）。`);
  console.error(`   若确认要用本地历史替换远端（远端内容会丢失），加 --force-history 重跑。`);
  console.error(`   回滚锚点（旧 head）：${remoteHead}`);
  process.exit(2);
}

let commits;
if (diverged) {
  // 真分叉：整段本地历史重放，父提交 = 远端旧 head（保留继承认可，不制造孤儿分支）
  commits = git('rev-list', '--reverse', 'HEAD').split('\n').filter(Boolean);
  console.log(`⚠️ 历史分叉：将重放**全部** ${commits.length} 个本地提交`);
  console.log(`   旧远端 head ${remoteHead.slice(0,7)} 将作为第一个提交的父（可回滚）`);
} else if (sameTree) {
  // ② 内容等价：找出远端 head 对应本地哪个提交，只推它之后的
  const rc = await gh('GET', `${API}/git/commits/${remoteHead}`);
  const remoteTree = rc.tree.sha;
  const locals = git('rev-list', 'HEAD').split('\n').filter(Boolean);
  const base = locals.find((c) => git('rev-parse', `${c}^{tree}`) === remoteTree);
  console.log(`   对应本地提交 ${base.slice(0,7)}，只推它之后的。`);
  commits = git('rev-list', '--reverse', `${base}..HEAD`).split('\n').filter(Boolean);
} else {
  const range = remoteHead ? `${remoteHead}..HEAD` : 'HEAD';
  commits = git('rev-list', '--reverse', range).split('\n').filter(Boolean);
}
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

// ★blob 内容去重 + 分段预取★（P125）
//
// 第一版**没有去重**，于是同一个文件在 23 个提交里被反复上传：
// 实测 986 次 POST，而按内容去重后只有 **124 个独立 blob（1.27 MB）** ——
// 白白多花 8 倍请求，直接把推送卡死（一轮跑了几百次往返还没到一半）。
//
// 现在：先扫出全历史用到的所有 blob sha（去重），一次性并发上传，
// 后续建树时直接查缓存。GitHub 的 blob 是内容寻址，同 sha 必同内容，
// 所以「sha → 远端 blob sha」这个映射可以安全复用。
const needed = new Set();
for (const commit of commits) {
  for (const e of treeOf(commit)) if (e.type === 'blob') needed.add(e.sha);
}
console.log(`\n需要上传 ${needed.size} 个 blob（已按内容去重，原累积条目 `
          + `${commits.reduce((n, c) => n + treeOf(c).length, 0)} 个）`);

const localToRemote = new Map();   // 本地 blob sha → 远端 blob sha
{
  const shas = [...needed];
  const CONC = 8;
  let done = 0;
  for (let i = 0; i < shas.length; i += CONC) {
    const slice = shas.slice(i, i + CONC);
    const results = await Promise.all(slice.map(async (sha) => {
      const buf = gitRaw('cat-file', 'blob', sha);
      const blob = await gh('POST', `${API}/git/blobs`,
                            { content: buf.toString('base64'), encoding: 'base64' });
      return [sha, blob.sha];
    }));
    for (const [a, b] of results) localToRemote.set(a, b);
    done += slice.length;
    process.stdout.write(`\r   已上传 ${done}/${shas.length}`);
  }
  process.stdout.write('\n');
}

let parent = remoteHead;
let pushed = 0;
for (const commit of commits) {
  const entries = treeOf(commit);
  const msg = git('log', '-1', '--format=%B', commit);
  console.log(`[${++pushed}/${commits.length}] ${commit.slice(0,7)}  ${entries.length} 个文件`);

  const tree = [];
  for (const e of entries) {
    if (e.type === 'commit') {        // 子模块：直接引用，不上传内容
      tree.push({ path: e.path, mode: e.mode, type: 'commit', sha: e.sha });
      continue;
    }
    tree.push({ path: e.path, mode: e.mode, type: 'blob', sha: localToRemote.get(e.sha) });
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
