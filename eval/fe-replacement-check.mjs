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
