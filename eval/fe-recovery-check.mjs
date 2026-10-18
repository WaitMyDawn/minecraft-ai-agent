import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const auth = await readFile(new URL('../src/main/resources/static/js/features/auth.js', import.meta.url), 'utf8');
const snapshots = await readFile(new URL('../src/main/resources/static/js/features/pack-snapshot.js', import.meta.url), 'utf8');
const functionSource = (source, declaration) => {
    const start = source.indexOf(declaration); assert.ok(start >= 0);
    return source.slice(start, source.indexOf('\n};', start) + 3).replace(/^export /, '');
};
const create = overrides => {
    const context = {authUser:{value:{token:'fixture-a'}}, currentConvId:{value:7}, chatHistory:{value:[]},
        localStorage:{removeItem() {}}, authHeader:() => ({}), csv:a => (a || []).join(','), ...overrides};
    return {context, save:vm.runInNewContext(functionSource(auth, 'export const saveMsg =') + '\nsaveMsg;', context)};
};
let calls = [], created = 0;
let fixture = create({apiFetch:async url => {
    calls.push(url); if (calls.length === 1) throw Object.assign(new Error('gone'), {status:404});
    return {ok:true,json:async () => ({snapshotId:'saved'})};
}});
fixture.context.createConversation = async () => { created++; fixture.context.currentConvId.value = 9; return 9; };
assert.equal(await fixture.save('ai', 'fixture', true, '', [], {}), 'saved');
assert.equal(created, 1); assert.deepEqual(calls, ['/api/prefs/conversations/7/messages', '/api/prefs/conversations/9/messages']);

calls = [];
fixture = create({apiFetch:async url => { calls.push(url); throw new TypeError('unknown delivery result'); }});
assert.equal(await fixture.save('ai','fixture',true,'',[],{}), null);
assert.equal(calls.length, 1, '结果未知的写请求不得自动重放');

fixture = create({apiFetch:async () => { fixture.context.currentConvId.value = 99; throw Object.assign(new Error('gone'), {status:404}); }});
assert.equal(await fixture.save('ai','fixture',true,'',[],{}), null);
assert.equal(fixture.context.currentConvId.value, 99, '旧保存失败不得清除新会话');

let resume;
fixture = create({currentConvId:{value:null}, apiFetch:async () => assert.fail('切换账号后不能继续写')});
fixture.context.createConversation = () => new Promise(resolve => { resume = resolve; });
const pending = fixture.save('ai','fixture',true,'',[],{});
fixture.context.authUser.value = {token:'fixture-b'}; fixture.context.currentConvId.value = 99; resume(9);
assert.equal(await pending, null);

fixture = create({currentConvId:{value:null}, createConversation:async () => { throw new Error('create unavailable'); }});
assert.equal(await fixture.save('ai','fixture',true,'',[],{}), null, '建会话故障应进入保存未确认路径');
let completeCreate;
const createContext = {authUser:{value:{token:'fixture-a'}}, currentConvId:{value:null}, authHeader:() => ({}),
    apiFetch:async () => ({json:() => new Promise(resolve => { completeCreate = resolve; })}),
    localStorage:{setItem:() => assert.fail('不能写入已切换账号的指针')}, loadConversations:async () => {}};
const createConversation = vm.runInNewContext(functionSource(auth, 'const createConversation =') + '\ncreateConversation;', createContext);
const creating = createConversation('fixture');
await new Promise(resolve => setImmediate(resolve));
createContext.authUser.value = {token:'fixture-b'}; createContext.currentConvId.value = 99;
completeCreate({id:7}); assert.equal(await creating, null); assert.equal(createContext.currentConvId.value, 99);

const previous = {name:'previous'};
const state = {name:'current', modSlugs:['fixture-core']};
const context = {authUser:{value:{token:'fixture-a'}}, saveMsg:async () => null,
    latestSnapshotId:{value:'previous-id'}, latestPackState:{value:previous}, attachVersion:() => assert.fail('不能标记未确认的持久版本')};
const record = vm.runInNewContext(functionSource(snapshots, 'const recordState =') + '\nrecordState;', context);
const msg = {role:'ai',content:'fixture',hasData:true};
await record(msg,state); await record(msg,state);
assert.equal(msg.content.match(/尚未确认保存/g).length, 1);
assert.equal(context.latestSnapshotId.value, 'previous-id'); assert.equal(context.latestPackState.value, previous);
assert.deepEqual(state.modSlugs, ['fixture-core']);
console.log('故障恢复前端检查通过：404有界重建、未知写结果不重放、账号/会话切换隔离及保存未确认提示。');
