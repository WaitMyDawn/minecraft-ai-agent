// 整合包版本（快照）的状态层检查。
//
// 为什么单独有这一道门：真实的线上故障是"每个『查看图谱』按钮都指向最新一版"——
// 界面能显示出 #hash，但点击时读不到状态，于是悄悄退化成"打开当前图谱"。
// 结构检查查不出这种错（import 都对、模板引用都在），因为它错在**数据**上。
//
// 这里只测纯函数层（utils/pack-state.js 不依赖 Vue 与浏览器），把三条不变量钉住：
//   1) 落版本时必须"版本号 + 状态"成对挂上（内存里的消息同样如此）；
//   2) 两版之间必须判得出不同（否则按钮之间没有区别）；
//   3) 判定要与后端哈希同口径：清单顺序无关、去重。
import {
    applyPackState, attachVersion, packStateOf, parseCsv, readPackState, stateEquals,
} from '../src/main/resources/static/js/utils/pack-state.js';

let failed = 0;
const check = (cond, msg) => {
    if (cond) {
        console.log('ok  : ' + msg);
    } else {
        failed++;
        console.log('FAIL: ' + msg);
    }
};

const mkPack = (over = {}) => ({
    name: 'MAA-Pack', mcVersion: '1.21.1', loader: 'neoforge',
    modSlugs: [], excludedSlugs: [], ...over,
});

// ---- 1. 落版本：版本号与状态必须成对挂上 ----
const v1 = packStateOf(mkPack({name: '空图谱'}));
const msg = {role: 'ai', content: '空图谱', hasData: true};
attachVersion(msg, 'a1b2c3d4e5', v1);
check(msg.snapshotId === 'a1b2c3d4e5', '内存消息要带上版本号');
check(!!msg.packState, '内存消息必须同时带上状态（只带版本号 = 按钮退化成"打开最新图"）');
check(readPackState(msg.packState)?.name === '空图谱', '状态能原样读回');

// ---- 2. 两版之间要判得出不同，并且能切回去 ----
const v2 = packStateOf(mkPack({name: '空图谱', modSlugs: ['sodium']}));
check(!stateEquals(v1, v2), '加了模组 = 新的一版');
check(!stateEquals(readPackState(attachVersion({}, 'x', v1).packState), v2),
    '从消息读出的旧版状态与新版必须不相等（否则点旧按钮会走"打开当前图谱"）');

const work = mkPack({modSlugs: ['sodium']});          // 当前工作区 = 新版
applyPackState(work, readPackState(msg.packState));   // 点旧按钮：切回空图谱那一版
check(work.modSlugs.length === 0, '切回旧版后工作区不能残留新版模组');
check(work.name === '空图谱' && work.loader === 'neoforge', '名称与环境一并恢复');

// ---- 3. 口径要与后端哈希一致：顺序无关、去重 ----
check(stateEquals(packStateOf(mkPack({modSlugs: ['b', 'a', 'b']})),
        packStateOf(mkPack({modSlugs: ['a', 'b']}))),
    '清单顺序与重复项不算改动（与后端 PackSnapshotHash 的规范化口径一致）');
check(!stateEquals(packStateOf(mkPack({modSlugs: ['a']})), packStateOf(mkPack({modSlugs: ['a'], excludedSlugs: ['b']}))),
    '排除名单变化也算新版本');
check(stateEquals(packStateOf(mkPack()), packStateOf(mkPack())), '空状态自身相等');

// ---- 4. 老消息（没有状态）必须能被识别出来，让调用方走兜底 ----
check(readPackState('') === null, '没有状态字段的老消息返回 null（调用方据此打开当前图谱）');
check(readPackState('{坏 JSON') === null, '状态解析失败也不能抛异常，只能返回 null');
check(parseCsv(' a ,, b ,').join('|') === 'a|b', 'CSV 解析去空去空格');

if (failed > 0) {
    console.log('\nSOME CHECKS FAILED (' + failed + ')');
    process.exit(1);
}
console.log('\nALL CHECKS PASSED');
