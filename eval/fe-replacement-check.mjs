import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {cleanChatReply} from '../src/main/resources/static/js/utils/format.js';

// 使用生产选择逻辑，仅替换 Vue 和 DOM 的状态依赖；接口与模板另由边界测试及浏览器验证。
let source = await readFile(new URL('../src/main/resources/static/js/features/chat.js', import.meta.url), 'utf8');
source = source.replace(/^import .*?;\r?$/gm, '');
source = 'const ref = value => ({value}); const nextTick = callback => Promise.resolve().then(callback);\n' + source;
globalThis.document = {getElementById: () => ({focus() {}})};
globalThis.window = {addEventListener() {}};
const {replacementPrompt, chooseReplacements, confirmReplacement, cancelReplacement, readClarificationTicket, sendMessage, syncReplacementSelection, sameSelectionWorkspace} = await import(
    'data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const options = {ticket: 'fixture', groups: [{original: 'alexs-mobs', verified: true, candidates: [
    {slug: 'alexs-mobs-1.21.1'}, {slug: 'alexs-mobs-continued'}]}]};
const select = () => chooseReplacements(options, new AbortController().signal);
let pending = select();
assert.deepEqual(replacementPrompt.value.groups[0].choice.slugs, ['alexs-mobs-1.21.1']);
replacementPrompt.value.groups[0].choice.slugs = ['alexs-mobs-1.21.1', 'alexs-mobs-continued'];
confirmReplacement();
assert.deepEqual((await pending)['alexs-mobs'].slugs, ['alexs-mobs-1.21.1', 'alexs-mobs-continued']);
assert.equal(replacementPrompt.value, null);
pending = chooseReplacements(options, new AbortController().signal,
    {'alexs-mobs': {slugs: ['alexs-mobs-continued'], drop: false, manualSlug: ''}}, '服务繁忙');
assert.deepEqual(replacementPrompt.value.groups[0].choice.slugs, ['alexs-mobs-continued']);
assert.equal(replacementPrompt.value.error, '服务繁忙');
cancelReplacement();
assert.equal(await pending, null);

pending = select();
replacementPrompt.value.groups[0].choice.slugs = [];
confirmReplacement();
assert.match(replacementPrompt.value.error, /请为每个模组/);
replacementPrompt.value.groups[0].choice.manualSlug = ' alexs-mobs-continued ';
confirmReplacement();
assert.deepEqual((await pending)['alexs-mobs'], {slugs: [], drop: false, manualSlug: 'alexs-mobs-continued'});

pending = select();
replacementPrompt.value.groups[0].choice.drop = true;
confirmReplacement();
assert.deepEqual((await pending)['alexs-mobs'], {slugs: [], drop: true, manualSlug: ''});

pending = select();
cancelReplacement();
assert.equal(await pending, null);
const controller = new AbortController();
pending = chooseReplacements(options, controller.signal);
controller.abort();
await assert.rejects(pending);
assert.equal(replacementPrompt.value, null);
console.log('候选选择检查通过：默认选择、多选、空选择拦截、准确 slug、去除要求、取消及终止清理。');

const overlap = {kind: 'OVERLAP', ticket: 'overlap-ticket', groups: [
    {original: 'worldgen', candidates: [{slug: 'a', recommended: true}, {slug: 'b', recommended: false}, {slug: 'core', protectedSelection: true}]},
    {original: 'ecosystem', candidates: [{slug: 'a', recommended: false}, {slug: 'c', recommended: true}]}
]};
pending = chooseReplacements(overlap, new AbortController().signal);
assert.deepEqual(replacementPrompt.value.groups[0].choice.slugs, ['a', 'core']);
assert.deepEqual(replacementPrompt.value.groups[1].choice.slugs, ['a', 'c']);
syncReplacementSelection('a', false);
assert.deepEqual(replacementPrompt.value.groups[0].choice.slugs, ['core']);
assert.deepEqual(replacementPrompt.value.groups[1].choice.slugs, ['c']);
replacementPrompt.value.groups[0].choice.slugs = [];
confirmReplacement();
assert.match(replacementPrompt.value.error, /核心/);
syncReplacementSelection('core', true);
confirmReplacement();
const overlapResult = await pending;
assert.deepEqual(overlapResult.worldgen, {slugs: ['core'], drop: false, manualSlug: ''});
assert.deepEqual(overlapResult.ecosystem.slugs, ['c']);
pending = chooseReplacements(overlap, new AbortController().signal);
cancelReplacement(); assert.equal(await pending, null);
console.log('组合选择检查通过：默认组合、跨组同步、核心保护、多选及取消。');

const generation = {...overlap, kind: 'GENERATION', ticket: 'generation-ticket'};
pending = chooseReplacements(generation, new AbortController().signal);
assert.deepEqual(replacementPrompt.value.groups[0].choice.slugs, ['a', 'core']);
syncReplacementSelection('a', false);
assert.deepEqual(replacementPrompt.value.groups[1].choice.slugs, ['c']);
confirmReplacement();
assert.deepEqual((await pending).worldgen.slugs, ['core']);
pending = chooseReplacements(generation, new AbortController().signal);
syncReplacementSelection('b', true);
confirmReplacement();
assert.deepEqual((await pending).worldgen.slugs, ['a', 'core', 'b']);
const generationAbort = new AbortController();
pending = chooseReplacements(generation, generationAbort.signal);
generationAbort.abort(new Error('cancel generation'));
await assert.rejects(pending, /cancel generation/);
assert.equal(replacementPrompt.value, null);
console.log('维度生成选择检查通过：默认建议、多个、全部、跨维度同步及取消，无计时自动提交。');

const uncertain = {kind: 'GENERATION', ticket: 'uncertain-ticket', groups: [{original: 'generation:nether',
    explanation: '介绍未明确维度范围，请核对后选择', candidates: [{slug: 'unknown-worldgen', recommended: true,
        scope: '介绍未明确范围', scopeNote: '总群系数量不能证明下界的变化规模'}]}]};
pending = chooseReplacements(uncertain, new AbortController().signal);
await Promise.resolve();
assert.equal(replacementPrompt.value.groups[0].candidates[0].scopeNote, '总群系数量不能证明下界的变化规模');
assert.equal(replacementPrompt.value.ticket, 'uncertain-ticket');
confirmReplacement();
assert.deepEqual((await pending)['generation:nether'].slugs, ['unknown-worldgen']);
console.log('范围待核实选择检查通过：保留原因与范围说明，单一候选仍需主动确认。');

const context = '<clarification_context>{"schemaVersion":1,"ticket":"fixture-ticket"}</clarification_context>';
assert.equal(readClarificationTicket(context), 'fixture-ticket');
assert.equal(readClarificationTicket('<clarification_context>{bad</clarification_context>'), null);
assert.equal(readClarificationTicket('<clarification_context>{"schemaVersion":2,"ticket":"fixture"}</clarification_context>'), null);
assert.equal(readClarificationTicket('<clarification_context>{"schemaVersion":1,"ticket":"../other"}</clarification_context>'), null);
for (const name of ['currentThought', 'buildProgress', 'currentView', 'isLoading', 'isResolving', 'pendingMrpack', 'previewData',
    'selectedMods', 'thinkTime', 'showAuthModal', 'enableUserFeedbackRules']) globalThis[name] = {value: null};
globalThis.authUser = {value: {token: 'fixture-token'}};
globalThis.cleanChatReply = cleanChatReply;
globalThis.currentConvId = {value: 2};
globalThis.sessionUuid = {value: 'fixture-session'};
globalThis.userInput = {value: '好的'};
globalThis.chatHistory = {value: [{role: 'ai', content: '请确认参数' + context}]};
globalThis.packData = {modSlugs: [], mcVersion: '1.21.1', loader: 'neoforge'};
const workspaceRequest = {currentMods: '', mcVersion: '1.21.1', loader: 'neoforge', uuid: 'fixture-session',
    assembly: '', importedPack: '', constraints: '', excludedSlugs: ''};
assert.equal(sameSelectionWorkspace(workspaceRequest), true);
globalThis.packData.assembly = {roots: ['changed']};
assert.equal(sameSelectionWorkspace(workspaceRequest), false);
globalThis.packData.assembly = null;
globalThis.authHeader = () => ({});
globalThis.saveMsg = () => {};
globalThis.recordCurrentVersion = () => {};
globalThis.document.querySelector = () => null;
globalThis.readChatResponse = async response => ({kind: 'reply', data: await response.text()});
const requests = [];
globalThis.apiFetch = async (url, options) => {
    requests.push(JSON.parse(options.body));
    return new Response('已接续原始需求');
};
await sendMessage();
assert.equal(requests.at(-1).clarificationTicket, 'fixture-ticket');
assert.equal(requests.at(-1).prompt, '好的');
assert.equal(globalThis.isLoading.value, false);
globalThis.chatHistory.value = [{role: 'ai', content: '请确认参数' + context}];
globalThis.userInput.value = '好的';
globalThis.apiFetch = async () => { throw Object.assign(new Error('队列繁忙'), {status: 429}); };
await sendMessage();
assert.equal(readClarificationTicket(globalThis.chatHistory.value.at(-1).content), 'fixture-ticket');
assert.equal(globalThis.isLoading.value, false);
console.log('基础确认检查通过：历史票据解析、续建提交、无效协议忽略、队列拒绝保留上下文及思考状态收口。');

const waitFor = async predicate => {
    for (let i = 0; i < 100; i++) {
        if (predicate()) return;
        await new Promise(resolve => setTimeout(resolve, 1));
    }
    assert.fail('未进入预期确认状态');
};
const resetWorkspace = () => {
    globalThis.packData = {modSlugs: ['original'], mcVersion: '1.21.1', loader: 'neoforge'};
    globalThis.chatHistory.value = [];
    globalThis.userInput.value = '增加生态内容';
};
resetWorkspace();
const submissions = [];
globalThis.apiFetch = async (url, opts) => {
    const body = JSON.parse(opts.body); submissions.push(body);
    if (!body.replacementTicket) return new Response('<replacement_options>' + JSON.stringify(overlap) + '</replacement_options>');
    if (submissions.length === 2) throw Object.assign(new Error('队列繁忙'), {status: 429});
    return new Response('已按选择完成<mods>core,c</mods>');
};
let building = sendMessage();
await waitFor(() => replacementPrompt.value);
await new Promise(resolve => setTimeout(resolve, 15));
assert.equal(submissions.length, 1, '等待确认不自动提交默认选择');
assert.deepEqual(globalThis.packData.modSlugs, ['original']);
syncReplacementSelection('a', false);
confirmReplacement();
await waitFor(() => replacementPrompt.value?.error.includes('服务繁忙'));
assert.deepEqual(replacementPrompt.value.groups[0].choice.slugs, ['core']);
assert.deepEqual(replacementPrompt.value.groups[1].choice.slugs, ['c']);
assert.deepEqual(globalThis.packData.modSlugs, ['original'], '排队拒绝不发布草案');
confirmReplacement(); confirmReplacement();
await building;
assert.equal(submissions.length, 3, '重复点击不重复提交确认');
assert.deepEqual(globalThis.packData.modSlugs, ['core', 'c']);

for (const expired of [false, true]) {
    resetWorkspace();
    const calls = [];
    globalThis.apiFetch = async (url, opts) => {
        calls.push(url);
        if (url.endsWith('/cancel')) return new Response('{}');
        const body = JSON.parse(opts.body);
        if (body.replacementTicket) throw Object.assign(new Error('组合确认已过期'), {status: 400});
        return new Response('<replacement_options>' + JSON.stringify(overlap) + '</replacement_options>');
    };
    building = sendMessage();
    await waitFor(() => replacementPrompt.value);
    if (expired) confirmReplacement(); else cancelReplacement();
    await building;
    assert.deepEqual(globalThis.packData.modSlugs, ['original'], '取消或过期不发布草案');
    assert.equal(replacementPrompt.value, null);
    assert.equal(globalThis.isLoading.value, false);
    assert.equal(calls.length, 2);
    assert.equal(calls.at(-1).endsWith('/cancel'), !expired);
}
console.log('确认续建检查通过：等待不提交、429保留选择、重复点击、取消和过期均不误发布草案。');
resetWorkspace();
globalThis.authUser.value = {token:'fixture-old-account'};
let completeOldChat, oldSaves = 0;
globalThis.apiFetch = () => new Promise(resolve => { completeOldChat = resolve; });
globalThis.saveMsg = () => { oldSaves++; };
globalThis.recordCurrentVersion = () => { oldSaves++; };
building = sendMessage();
await waitFor(() => completeOldChat);
globalThis.authUser.value = {token:'fixture-new-account'};
globalThis.chatHistory.value = [];
globalThis.packData = {modSlugs:['new-account-pack'],mcVersion:'1.21.1',loader:'neoforge'};
completeOldChat(new Response('old-private-result<mods>old-private-core</mods>'));
await building;
await new Promise(resolve => setImmediate(resolve));
assert.deepEqual(globalThis.packData.modSlugs, ['new-account-pack']);
assert.equal(globalThis.chatHistory.value.length, 0);
assert.equal(oldSaves, 0, '切换账号后旧对话不得更新工作区或写入新账号历史');
assert.equal(globalThis.isLoading.value, false);
console.log('旧对话恢复隔离检查通过：迟到响应不改新账号清单、不追加消息、不写入历史。');
