import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {readBuildReport, readReplacementChoices, readRequirementReport, requirementStatus, requirementNote, requirementTone, issueReason, modProjectLink} from '../src/main/resources/static/js/utils/build-report.js';
import {renderMarkdownSafe, cleanChatReply} from '../src/main/resources/static/js/utils/format.js';

assert.equal(cleanChatReply('构建完成<mods>a,b</mods><trace>{}</trace><constraints>{}</constraints><build_report>{}</build_report>'), '构建完成<build_report>{}</build_report>');

const text = '已生成整合包\n<build_report>' + JSON.stringify({schemaVersion: 1, total: 10,
    categories: {magic: 8, adventure: 7}, issues: [{mod: 'addon', dependency: 'core', versionId: 'V1', reason: 'VERSION_CONFLICT', dropped: false}]}) + '</build_report>';
const report = readBuildReport(text);
assert.equal(report.categories[0].percentage, 80);
assert.equal(report.categories[1].percentage, 70);
assert.equal(report.issues[0].dependency, 'core');
assert.deepEqual(report.issues[0].requiredVersions, ['V1']);
const detailed = readBuildReport('<build_report>' + JSON.stringify({schemaVersion: 1, total: 1, categories: {}, issues: [{
    mod: 'addon', dependency: 'api', versionId: '', reason: 'NO_VERSION_FOR_ENV', dropped: false,
    detail: '声明来源=MODRINTH，声明方=addon@V1', codes: ['NO_VERSION_FOR_ENV', 'MULTILOADER_DEPENDENCY_UNVERIFIED', {}, '<script>'],
    requiredVersions: ['V1', 'V2', null, '../escape']}]}) + '</build_report>');
assert.deepEqual(detailed.issues[0].codes, ['NO_VERSION_FOR_ENV', 'MULTILOADER_DEPENDENCY_UNVERIFIED']);
assert.deepEqual(detailed.issues[0].requiredVersions, ['V1', 'V2']);
assert.match(detailed.issues[0].detail, /声明来源/);
assert.match(issueReason('MULTILOADER_DEPENDENCY_UNVERIFIED'), /适用范围待核实/);
assert.equal(readBuildReport('<build_report>{bad}</build_report>'), null);
assert.equal(readBuildReport('<build_report>{"schemaVersion":2,"total":10}</build_report>'), null);
assert.doesNotMatch(renderMarkdownSafe(text), /build_report|VERSION_CONFLICT/);
assert.match(issueReason('VERSION_CONFLICT'), /精确版本/);
assert.equal(modProjectLink('bad/slug'), 'https://modrinth.com/mod/bad%2Fslug');
const choices = '<replacement_choices>' + JSON.stringify({schemaVersion: 1, choices: {
    'alexs-mobs': ['alexs-mobs-continued', 'alexs-mobs-1.21.1'], 'alexs-caves': []
}}) + '</replacement_choices>';
assert.equal(readReplacementChoices(choices)[0].selected.length, 2);
assert.equal(readReplacementChoices(choices)[1].selected.length, 0);
assert.doesNotMatch(renderMarkdownSafe(choices), /replacement_choices|alexs-mobs/);
assert.equal(renderMarkdownSafe('请确认<clarification_context>{"ticket":"fixture-private-ticket"}</clarification_context>'), '请确认');
assert.deepEqual(readReplacementChoices('<replacement_choices>{"schemaVersion":1,"choices":{"javascript:bad":["bad"]}}</replacement_choices>'), []);
const coverageText = '<requirement_report>' + JSON.stringify({schemaVersion: 1, repairStatus: 'NO_QUALIFIED_CANDIDATE', repaired: [],
    requirements: [{id: 'r-0000000000000000', source: '自动化和物流', status: 'PARTIAL',
        mods: [{slug: 'factory', purpose: '提供工厂自动化', quote: 'factory automation'}], missing: [], note: '物流尚未核实'},
        {id: 'r-1111111111111111', source: '包名', status: 'CONTEXT', mods: [], missing: [], note: ''}]}) + '</requirement_report>';
assert.equal(readRequirementReport(coverageText).requirements.length, 1);
assert.equal(readRequirementReport(coverageText).requirements[0].mods[0].purpose, '提供工厂自动化');
assert.equal(requirementStatus('PARTIAL'), '部分目标待核实');
assert.equal(requirementStatus({status:'UNVERIFIED',evidenceState:{code:'VERSION_PENDING'}}), '版本待核实');
assert.equal(requirementStatus({status:'PARTIAL',evidenceState:{code:'PROJECT_MISSING'}}), '点名项目未入包');
assert.equal(requirementTone({status:'UNVERIFIED'}), 'bg-slate-100 text-slate-600');
assert.equal(requirementNote({note:'用途尚未取得可核对的审核证据；检索命中和类别数量不能证明满足'}), '本项目标的用途仍待核实。');
assert.doesNotMatch(renderMarkdownSafe(coverageText), /requirement_report|factory automation/);
assert.equal(readRequirementReport('<requirement_report>{"schemaVersion":2}</requirement_report>'), null);
const childCoverage = JSON.parse(coverageText.match(/<requirement_report>(.*?)<\/requirement_report>/s)[1]);
childCoverage.requirements[0].criteria = [
    {id: 'r-2222222222222222', source: '自动化', status: 'SUPPORTING_EVIDENCE', mods: childCoverage.requirements[0].mods, missing: [], note: '有用途依据'},
    {id: 'r-3333333333333333', source: '物流', status: 'UNVERIFIED', mods: [], missing: [], note: '尚缺物流依据'},
    {id: 'invalid', source: '无效', status: 'PARTIAL', mods: [], missing: [], note: ''}
];
const readChildren = readRequirementReport('<requirement_report>' + JSON.stringify(childCoverage) + '</requirement_report>').requirements[0];
assert.equal(readChildren.criteria.length, 2);
assert.equal(readChildren.criteria[1].status, 'UNVERIFIED');
assert.equal(readRequirementReport(coverageText).requirements[0].criteria.length, 0);
childCoverage.requirements[0].evidenceState = {code: 'VERSION_PENDING', projects: ['core', 'javascript:bad'], countsComplete: false};
const pendingCoverage = readRequirementReport('<requirement_report>' + JSON.stringify(childCoverage) + '</requirement_report>');
assert.deepEqual(pendingCoverage.requirements[0].evidenceState.projects, ['core']);
assert.equal(pendingCoverage.requirements[0].evidenceState.countsComplete, false);
assert.equal(requirementStatus(pendingCoverage.requirements[0]), '版本待核实');
childCoverage.requirements[0].evidenceState.code = 'UNKNOWN';
assert.equal(readRequirementReport('<requirement_report>' + JSON.stringify(childCoverage) + '</requirement_report>').requirements[0].evidenceState, null);
childCoverage.requirements[0].evidenceState = {code:'CORE_GENERATION_PENDING', projects:[], generation:[
    {dimension:'nether', label:'下界', projects:['core', 'javascript:bad']}, {dimension:[], label:'无效', projects:[]}]};
childCoverage.requirements[0].mods = [
    {slug:'core', purpose:'项目原文描述下界大规模生成', quote:'x'.repeat(600), evidenceType:'PROJECT_GENERATION'},
    {slug:'bad-review', purpose:'模型摘录不能扩大', quote:'x'.repeat(600), evidenceType:'REVIEW_QUOTE'},
    {slug:'too-long', purpose:'超限原文', quote:'x'.repeat(1001), evidenceType:'PROJECT_GENERATION'}];
const generationReport = readRequirementReport('<requirement_report>' + JSON.stringify(childCoverage) + '</requirement_report>').requirements[0];
assert.equal(requirementStatus(generationReport), '核心生成待核实');
assert.deepEqual(generationReport.evidenceState.generation, [{dimension:'nether', label:'下界', projects:['core']}]);
assert.deepEqual(generationReport.mods.map(m => m.slug), ['core']);

// 运行生产预览收口，验证关系错误不会删除仍在最终图中的核心。
const snapshot = await readFile(new URL('../src/main/resources/static/js/features/pack-snapshot.js', import.meta.url), 'utf8');
const section = snapshot.slice(snapshot.indexOf('const HARD_FAIL_REASONS'), snapshot.indexOf('/**\n * 点历史消息'));
const fixture = {authUser: {value: true}, labUnresolved: {value: []}, labChangeNote: {value: ''},
    latestPackState: {value: {name: '', modSlugs: ['core', 'gone']}}, latestSnapshotId: {value: ''},
    packData: {name: '', modSlugs: ['core', 'gone']}, chatHistory: {value: []},
    stateEquals: () => false, packStateOf: pack => ({...pack, modSlugs: [...pack.modSlugs]}),
    saveMsg: async () => 'fixture', attachVersion: () => {}};
globalThis.previewNoteFixture = fixture;
const preview = await import('data:text/javascript;base64,' + Buffer.from(
    'const {authUser,labUnresolved,labChangeNote,latestPackState,latestSnapshotId,packData,chatHistory,stateEquals,packStateOf,saveMsg,attachVersion}=globalThis.previewNoteFixture;\n' + section).toString('base64'));
await preview.handlePreviewResult({state: fixture.packStateOf(fixture.packData), nodes: [{slug: 'core'}],
    unresolved: [{slug: 'core', reason: 'VERSION_CONFLICT'}, {slug: 'core', reason: 'NO_VERSION_FOR_ENV'},
        {slug: 'gone', reason: 'PROJECT_NOT_FOUND'}], dropped: [{slug: 'core', reason: 'VERSION_CONFLICT'}]});
assert.deepEqual(fixture.packData.modSlugs, ['core']);
assert.doesNotMatch(fixture.chatHistory.value[0].content, /移除：core|依赖剔除：core/);
assert.match(fixture.chatHistory.value[0].content, /已移除：gone/);
fixture.packData.modSlugs = ['core'];
fixture.latestPackState.value = {name: '', modSlugs: ['core']};
await preview.handlePreviewResult({state: fixture.packStateOf(fixture.packData), nodes: [],
    unresolved: [{slug: 'core', reason: 'VERSION_CONFLICT'}], dropped: []});
assert.deepEqual(fixture.packData.modSlugs, ['core'], '精确版本冲突本身不能授予删除权限');

await import('./fe-mod-info-check.mjs');
console.log('构建展示检查通过：多标签占比、JSON 历史、隐藏协议文本、原因翻译、链接编码及资料按需加载。');

const optimizationFixture = {schemaVersion: 1, total: 100, categories: {}, issues: [], optimization: {
    schemaVersion: 1, totalFiles: 100, modCount: 1, referenceActive: true, rootsKnown: true,
    percentage: 999, covered: ['MEMORY'], suggestedMissing: ['RENDERER'],
    targets: [{id: 'r-0000000000000001', source: '服务器内存优化', side: 'SERVER', desired: ['MEMORY'], covered: ['MEMORY'], missing: [], modCount: 1}],
    entries: [{slug: 'memory-mod', versionId: 'V1', side: 'BOTH', roles: {MEMORY: 'Reduces memory usage'}}]
}};
const optimizerText = data => '<build_report>' + JSON.stringify(data) + '</build_report>';
assert.equal(readBuildReport(optimizerText(optimizationFixture)).optimization.percentage, 1);
assert.equal(readBuildReport(text).optimization, null, '旧历史无需额外字段');
const corruptPortfolio = structuredClone(optimizationFixture);
corruptPortfolio.optimization.entries[0].roles.UNKNOWN = 'bad';
assert.equal(readBuildReport(optimizerText(corruptPortfolio)).optimization, null);
assert.equal(readBuildReport(optimizerText(corruptPortfolio)).total, 100, '无效组合字段不影响其它报告');
const badLinkPortfolio = structuredClone(optimizationFixture);
badLinkPortfolio.optimization.entries[0].slug = 'javascript:bad';
assert.equal(readBuildReport(optimizerText(badLinkPortfolio)).optimization, null);
assert.doesNotMatch(renderMarkdownSafe(optimizerText(optimizationFixture)), /build_report|Reduces memory/);
