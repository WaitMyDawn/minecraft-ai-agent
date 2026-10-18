import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

// 只替换 Vue 状态依赖，执行生产 apiFetch 与真实浏览器取数器。
globalThis.queueTestState = {
    authUser: {value: {token: 'fixture-token'}}, authError: {value: ''},
    showAuthModal: {value: false}, currentThought: {value: ''}, buildProgress: {value: []}, requestStatuses: {}
};
const stateModule = 'data:text/javascript;base64,' + Buffer.from(
    'export const {authUser, authError, showAuthModal, currentThought, buildProgress, requestStatuses} = globalThis.queueTestState;'
).toString('base64');
const clientUrl = new URL('../src/main/resources/static/js/modrinth-client.js', import.meta.url).href;
let source = await readFile(new URL('../src/main/resources/static/js/api.js', import.meta.url), 'utf8');
source = source.replace("'./store.js'", JSON.stringify(stateModule))
    .replace("'./modrinth-client.js'", JSON.stringify(clientUrl))
    .replace("'./utils/build-progress.js'", JSON.stringify(new URL('../src/main/resources/static/js/utils/build-progress.js', import.meta.url).href));
const {apiFetch} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const reply = (body, status = 200) => new Response(JSON.stringify(body), {
    status, headers: {'Content-Type': 'application/json'}
});
let submissions = 0, polls = 0, receipts = 0, cancellations = 0;
globalThis.fetch = async (url, options) => {
    if (url.startsWith('https://api.modrinth.com/')) {
        assert.equal(options.headers, undefined, '网站 token 不得发给 Modrinth');
        return reply([{id: 'P1', slug: 'create'}]);
    }
    assert.equal(options.headers['X-Auth-Token'], 'fixture-token');
    if (url === '/api/modpack/preview') {
        submissions++;
        return reply({taskId: 'fixture-task', operation: true, stage: 'queued', queuePosition: 3}, 202);
    }
    if (url === '/api/operations/fixture-task') {
        polls++;
        assert.equal(globalThis.queueTestState.requestStatuses['fixture-task'].message, '正在排队，当前等待序号 3');
        return reply({taskId: 'fixture-task', operation: true, stage: 'need-data', wanted: [{kind: 'project', key: 'create'}]}, 202);
    }
    if (url.endsWith('/facts')) {
        receipts++;
        assert.equal(JSON.parse(options.body).answered[0].data.slug, 'create');
        return reply({nodes: [{slug: 'create'}]});
    }
    if (url.endsWith('/cancel')) { cancellations++; return reply({ok: true}); }
    throw new Error('unexpected request');
};
const result = await apiFetch('/api/modpack/preview', {method: 'POST', body: '{}'});
assert.equal((await result.json()).nodes[0].slug, 'create');
assert.equal(submissions, 1, '等待期间不得重复提交业务请求');
assert.equal(polls, 1);
assert.equal(receipts, 1);
assert.deepEqual(globalThis.queueTestState.requestStatuses, {});

const cancel = new AbortController();
setTimeout(() => cancel.abort(), 20);
await assert.rejects(() => apiFetch('/api/modpack/preview', {method: 'POST', signal: cancel.signal}));
assert.equal(cancellations, 1, '取消等待必须取消后台任务');
assert.equal(submissions, 2);
assert.deepEqual(globalThis.queueTestState.requestStatuses, {});
globalThis.fetch = async (_url, options) => {
    assert.equal(options.acceptErrorStatuses, undefined, '接口控制选项不能发给 fetch');
    return reply({error: '依赖未闭合', canExportIncomplete: true}, 409);
};
await assert.rejects(() => apiFetch('/api/modpack/build'), /依赖未闭合/);
const conflict = await apiFetch('/api/modpack/build', {acceptErrorStatuses: [409]});
assert.equal(conflict.status, 409);
assert.equal((await conflict.json()).canExportIncomplete, true);
assert.equal(globalThis.queueTestState.showAuthModal.value, false, '业务冲突不撤销登录状态');
globalThis.fetch = async () => reply({error: '构筑队列已满'}, 429);
await assert.rejects(() => apiFetch('/api/chat'), error => {
    assert.equal(error.status, 429, '续建界面需要依据状态保留选择并等待显式重试');
    return true;
});
const stages = [
    {sequence: 1, title: '解析需求', startedAt: 100, endedAt: 200},
    {sequence: 2, title: '召回候选', startedAt: 200, endedAt: null}
];
let phasePoll = 0;
globalThis.fetch = async () => {
    phasePoll++;
    if (phasePoll === 1) return reply({taskId: 'chat-progress', stage: 'running', progress: stages});
    if (phasePoll === 2) return reply({taskId: 'chat-progress', stage: 'need-data', wanted: [], progress: stages});
    if (phasePoll === 3) return reply({taskId: 'chat-progress', stage: 'running', progress: [stages[0]]});
    return new Response('结果<build_progress>' + JSON.stringify({taskId: 'chat-progress', entries: [stages[0], {...stages[1], endedAt: 300}]})
        + '</build_progress>', {headers: {'Content-Type': 'text/plain'}});
};
await apiFetch('/api/chat', {method: 'POST', body: '{}'});
assert.equal(globalThis.queueTestState.buildProgress.value.length, 2, 'running/need-data 和旧快照不得覆盖或重复阶段');
assert.equal(globalThis.queueTestState.buildProgress.value[1].endedAt, 300, '最终响应应补齐阶段收口');
assert.equal(globalThis.queueTestState.currentThought.value, '', '取数过程不替换业务阶段文案');
let recoverySubmissions = 0;
globalThis.fetch = async url => {
    if (url === '/api/chat') { recoverySubmissions++; return reply({taskId:'lost-task',stage:'running'}, 202); }
    return reply({error:'任务不存在或已过期'}, 404);
};
await assert.rejects(() => apiFetch('/api/chat', {method:'POST',body:'{}'}), error => error.status === 404 && error.message.includes('重新提交'));
assert.equal(recoverySubmissions, 1, '任务丢失不得自动重建或重新调用模型');
let completeOldRequest;
globalThis.fetch = () => new Promise(resolve => { completeOldRequest = resolve; });
const oldRequest = apiFetch('/api/prefs/export');
globalThis.queueTestState.authUser.value = {token:'fixture-new-account'};
completeOldRequest(reply({error:'expired'}, 401));
await assert.rejects(() => oldRequest, error => error.name === 'AbortError');
assert.equal(globalThis.queueTestState.authUser.value.token, 'fixture-new-account', '旧401不得清除新账号');
let oldTaskCalls = 0;
globalThis.fetch = async () => { oldTaskCalls++; return reply({taskId:'old-account-task',stage:'running'}, 202); };
const oldTask = apiFetch('/api/chat', {method:'POST',body:'{}'});
await new Promise(resolve => setImmediate(resolve));
globalThis.queueTestState.authUser.value = {token:'fixture-next-account'};
await assert.rejects(() => oldTask, error => error.name === 'AbortError');
assert.equal(oldTaskCalls, 1, '切换账号后旧任务不得继续轮询或取消新账号任务');
delete globalThis.queueTestState;
console.log('前端队列检查通过：排位显示、单次提交、浏览器取数、回执、取消和清理。');
