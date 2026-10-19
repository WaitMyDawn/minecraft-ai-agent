import {computed, ref} from 'vue';
import {readBuildReport, readReplacementChoices, readRequirementReport, requirementStatus, requirementTone, requirementNote, modProjectLink, issueReason, optimizationRole, optimizationSide} from '../utils/build-report.js';
import {authUser, categoryGloss, modInfoCache} from '../store.js';
import {bindVisibleModInfo} from '../utils/visible-mod-info.js';
import {loadModInfoCache} from '../features/prefs.js';

export default {
    directives: {
        visibleMods: {
            mounted(element, binding) {
                const state = bindVisibleModInfo(element, loadModInfoCache);
                element.modInfoBinding = state;
                state.update(binding.value.slugs, binding.value.owner);
            },
            updated(element, binding) { element.modInfoBinding.update(binding.value.slugs, binding.value.owner); },
            unmounted(element) { element.modInfoBinding.dispose(); }
        }
    },
    props: {content: String},
    setup(props) {
        const report = computed(() => readBuildReport(props.content));
        const replacements = computed(() => readReplacementChoices(props.content));
        const coverage = computed(() => readRequirementReport(props.content));
        const requirementSlugs = row => [...row.mods.map(m => m.slug), ...row.missing, ...(row.evidenceState?.projects || []),
            ...row.criteria.flatMap(c => [...c.mods.map(m => m.slug), ...c.missing, ...(c.evidenceState?.projects || [])])];
        const replacementSlugs = computed(() => replacements.value.flatMap(row => [row.original, ...row.selected]));
        const issueSlugs = computed(() => (report.value?.issues || []).flatMap(i => [i.mod, i.dependency]).filter(Boolean));
        const optimizationSlugs = computed(() => (report.value?.optimization?.entries || []).map(e => e.slug));
        const chart = ref(true);
        const icon = slug => {
            const value = modInfoCache.value[slug]?.icon || '';
            try {
                const url = new URL(value);
                return url.protocol === 'https:' && url.hostname === 'cdn.modrinth.com' ? value : '';
            } catch { return ''; }
        };
        return {authUser, optimizationRole, optimizationSide, optimizationSlugs, report, replacements, coverage, requirementStatus, requirementTone, requirementNote, requirementSlugs, replacementSlugs, chart, categoryGloss, modProjectLink, issueReason, issueSlugs, icon};
    },
    template: `
      <div v-if="report || replacements.length || coverage" class="mt-4 space-y-3">
        <section v-if="replacements.length" v-visible-mods="{slugs: replacementSlugs, owner: authUser?.token}" class="rounded-xl border border-indigo-100 bg-indigo-50/40 p-4 space-y-3">
          <h4 class="text-sm font-semibold text-slate-800">本轮候选选择</h4>
          <div v-for="row in replacements" :key="row.original" class="flex flex-wrap items-center gap-2 text-sm">
            <a :href="modProjectLink(row.original)" target="_blank" rel="noopener noreferrer" class="flex items-center gap-2 text-indigo-700">
              <img v-if="icon(row.original)" :src="icon(row.original)" alt="" loading="lazy" class="w-8 h-8 rounded">
              <span v-else class="w-8 h-8 rounded bg-white inline-flex items-center justify-center">📦</span>
              <span class="break-all">{{ row.original }}</span>
            </a>
            <span class="text-slate-400">→</span>
            <a v-for="slug in row.selected" :key="slug" :href="modProjectLink(slug)" target="_blank" rel="noopener noreferrer" class="flex items-center gap-2 rounded-lg border border-indigo-100 bg-white px-2 py-1 text-indigo-700">
              <img v-if="icon(slug)" :src="icon(slug)" alt="" loading="lazy" class="w-8 h-8 rounded">
              <span v-else class="w-8 h-8 rounded bg-slate-100 inline-flex items-center justify-center">📦</span>
              <span class="break-all">{{ slug }}</span>
            </a>
            <span v-if="!row.selected.length" class="text-slate-500">已去除该要求</span>
          </div>
        </section>
        <section v-if="coverage && coverage.requirements.length" class="rounded-xl border border-slate-200 bg-white overflow-hidden">
          <h4 class="px-4 py-3 bg-slate-50 text-sm font-semibold text-slate-800">逐项构建结果</h4>
          <details v-for="row in coverage.requirements" :key="row.id" v-visible-mods="{slugs: requirementSlugs(row), owner: authUser?.token}" class="border-t border-slate-100">
            <summary class="px-4 py-3 cursor-pointer text-sm flex items-start justify-between gap-3">
              <span class="text-slate-700">{{ row.source }}</span>
              <span class="shrink-0 rounded-full px-2 py-0.5 text-xs" :class="requirementTone(row)">{{ requirementStatus(row) }}</span>
            </summary>
            <div class="px-4 pb-4 space-y-2">
              <div v-for="criterion in row.criteria" :key="criterion.id" class="rounded-lg border border-slate-100 bg-slate-50/50 p-3 space-y-2">
                <div class="flex items-start justify-between gap-2 text-xs"><span class="font-semibold text-slate-700">{{ criterion.source }}</span><span class="shrink-0 text-slate-500">{{ requirementStatus(criterion) }}</span></div>
                <div v-for="mod in criterion.mods" :key="mod.slug" class="text-xs">
                  <a :href="modProjectLink(mod.slug)" target="_blank" rel="noopener noreferrer" class="inline-flex items-center gap-2 text-indigo-700">
                    <img v-if="icon(mod.slug)" :src="icon(mod.slug)" alt="" loading="lazy" class="w-6 h-6 rounded">
                    <span v-else class="w-6 h-6 rounded bg-slate-100 inline-flex items-center justify-center">📦</span>
                    <span class="break-all">{{ mod.slug }}</span>
                  </a><p class="mt-1 text-slate-600">{{ mod.purpose }}</p>
                  <details v-if="mod.quote" class="mt-1 text-xs text-slate-400"><summary class="cursor-pointer">{{ mod.evidenceType === 'PROJECT_GENERATION' ? '查看项目原文依据' : '查看用途依据' }}</summary><p class="mt-1">{{ mod.quote }}</p></details>
                </div>
                <p v-if="criterion.missing.length" class="text-xs text-amber-700">未保留：<a v-for="slug in criterion.missing" :key="slug" :href="modProjectLink(slug)" target="_blank" rel="noopener noreferrer" class="mr-2 underline">{{ slug }}</a></p>
                <p v-if="criterion.evidenceState?.countsComplete !== false && (criterion.modCount > criterion.mods.length || criterion.missingCount > criterion.missing.length)" class="text-xs text-slate-400">仅列前24项；关联项目共 {{ criterion.modCount }} 项，未保留项目共 {{ criterion.missingCount }} 项。</p>
                <p v-if="criterion.evidenceState?.code === 'VERSION_PENDING'" class="text-xs text-slate-600">已在清单：<a v-for="slug in criterion.evidenceState.projects" :key="slug" :href="modProjectLink(slug)" target="_blank" rel="noopener noreferrer" class="mr-2 underline">{{ slug }}</a></p>
                <p v-if="criterion.evidenceState?.countsComplete === false" class="text-xs text-slate-400">仅展示仍可核对的关联项目，不作为完整数量统计。</p>
                <p class="text-xs text-slate-500">{{ requirementNote(criterion) }}</p>
              </div>
              <template v-if="!row.criteria.length">
              <div v-for="mod in row.mods" :key="mod.slug" class="rounded-lg border border-slate-100 p-2">
                <a :href="modProjectLink(mod.slug)" target="_blank" rel="noopener noreferrer" class="flex items-center gap-2 text-sm text-indigo-700">
                  <img v-if="icon(mod.slug)" :src="icon(mod.slug)" alt="" loading="lazy" class="w-7 h-7 rounded">
                  <span v-else class="w-7 h-7 rounded bg-slate-100 inline-flex items-center justify-center">📦</span>
                  <span class="break-all">{{ mod.slug }}</span>
                </a>
                <p class="mt-1 text-xs text-slate-600">{{ mod.purpose }}</p>
                <details v-if="mod.quote" class="mt-1 text-xs text-slate-400"><summary class="cursor-pointer">查看用途依据</summary><p class="mt-1">{{ mod.quote }}</p></details>
              </div>
              <p v-if="row.missing.length" class="text-xs text-amber-800">未保留：<a v-for="slug in row.missing" :key="slug" :href="modProjectLink(slug)" target="_blank" rel="noopener noreferrer" class="mr-2 underline">{{ slug }}</a></p>
              <p v-if="row.evidenceState?.countsComplete !== false && (row.modCount > row.mods.length || row.missingCount > row.missing.length)" class="text-xs text-slate-400">仅列前24项依据或缺口；关联项目共 {{ row.modCount }} 项，未保留项目共 {{ row.missingCount }} 项。</p>
              </template>
              <p v-if="!row.criteria.length && row.evidenceState?.code === 'VERSION_PENDING'" class="text-xs text-slate-600">已在清单：<a v-for="slug in row.evidenceState.projects" :key="slug" :href="modProjectLink(slug)" target="_blank" rel="noopener noreferrer" class="mr-2 underline">{{ slug }}</a></p>
              <p v-if="row.evidenceState?.countsComplete === false" class="text-xs text-slate-400">仅展示仍可核对的关联项目，不作为完整数量统计。</p>
              <p v-if="!row.criteria.length || row.missing.length" class="text-xs text-slate-500">{{ requirementNote(row) }}</p>
            </div>
          </details>
          <p class="px-4 py-3 text-xs text-slate-500">用途依据来自项目说明及已选版本关系，不等于丰富程度或运行效果已验证。</p>
        </section>
        <template v-if="report">
        <details v-if="report.optimization" v-visible-mods="{slugs: optimizationSlugs, owner: authUser?.token}" class="rounded-xl border border-indigo-100 bg-indigo-50/30">
          <summary class="px-4 py-3 cursor-pointer text-sm font-semibold text-slate-800">优化用途组合 · {{ report.optimization.modCount }} 项 · {{ report.optimization.covered.length }} 种用途</summary>
          <div class="px-4 pb-4 space-y-3">
            <p v-if="report.optimization.referenceActive" class="text-xs text-slate-500">占最终文件 {{ report.optimization.percentage.toFixed(1) }}%；5%～10%仅作宽泛优化需求的参考，不是达标门槛。</p>
            <div v-for="(target, index) in report.optimization.targets" :key="target.id + ':' + index" class="rounded-lg bg-white border border-indigo-100 p-3 text-xs space-y-1">
              <p class="font-semibold text-slate-700">{{ target.source }} · {{ optimizationSide(target.side) }}</p>
              <p class="text-slate-600">有用途依据：{{ target.covered.map(optimizationRole).join('、') || '暂未取得' }}</p>
              <p v-if="target.missing.length" class="text-slate-500">可考虑补充：{{ target.missing.map(optimizationRole).join('、') }}。按实际需求取舍，无需凑齐。</p>
            </div>
            <div v-for="entry in report.optimization.entries" :key="entry.slug" class="rounded-lg bg-white border border-slate-100 p-3 space-y-2">
              <a :href="modProjectLink(entry.slug)" target="_blank" rel="noopener noreferrer" class="flex items-center gap-2 text-sm text-indigo-700">
                <img v-if="icon(entry.slug)" :src="icon(entry.slug)" alt="" loading="lazy" class="w-7 h-7 rounded">
                <span v-else class="w-7 h-7 rounded bg-slate-100 inline-flex items-center justify-center">📦</span>
                <span>{{ entry.slug }}</span><span class="text-xs text-slate-400">{{ optimizationSide(entry.side) }}</span>
              </a>
              <p v-for="(quote, role) in entry.roles" :key="role" class="text-xs text-slate-600"><span class="font-medium">{{ optimizationRole(role) }}</span> · {{ quote }}</p>
            </div>
            <p v-if="!report.optimization.rootsKnown" class="text-xs text-slate-500">根项目来源尚未核实，数量仅作参考。</p>
            <p class="text-xs text-slate-500">按最终保留项目及已选版本统计，前置库不计入优化根数；介绍说明的用途不等于实测收益。安装后运行检查由 MAA-Checker 完成。</p>
          </div>
        </details>
        <section v-if="report.categories.length" class="rounded-xl border border-slate-200 bg-white overflow-hidden">
          <div class="px-4 py-3 bg-slate-50 flex items-center justify-between">
            <h4 class="font-semibold text-sm text-slate-800">包内类别构成</h4>
            <button @click="chart = !chart" class="text-xs text-indigo-600">{{ chart ? '隐藏柱状图' : '显示柱状图' }}</button>
          </div>
          <table class="w-full text-sm text-left">
            <thead class="text-xs text-slate-500"><tr><th class="px-4 py-2">类别</th><th class="px-4 py-2 text-right">数量</th><th class="px-4 py-2 text-right">百分比</th></tr></thead>
            <tbody><tr v-for="row in report.categories" :key="row.name" class="border-t border-slate-100">
              <td class="px-4 py-2"><span>{{ categoryGloss[row.name] || row.name }}</span><span v-if="categoryGloss[row.name]" class="ml-2 text-xs text-slate-400">{{ row.name }}</span></td>
              <td class="px-4 py-2 text-right tabular-nums">{{ row.count }}</td>
              <td class="px-4 py-2 text-right tabular-nums"><span>{{ row.percentage.toFixed(1) }}%</span>
                <div v-if="chart" class="mt-1 h-1.5 w-full min-w-[80px] bg-indigo-50 rounded-full overflow-hidden"><div class="h-full bg-indigo-500 rounded-full" :style="{width: row.percentage + '%'}"></div></div>
              </td>
            </tr></tbody>
          </table>
          <p class="px-4 py-3 text-xs text-slate-500">按 {{ report.total }} 个最终文件计算。模组可有多个类别，占比之和可能超过 100%；未分类表示类别信息尚未取得。</p>
        </section>
        <details v-if="report.issues.length" v-visible-mods="{slugs: issueSlugs, owner: authUser?.token}" class="rounded-xl border border-amber-200 bg-amber-50/50">
          <summary class="px-4 py-3 cursor-pointer text-sm font-semibold text-amber-900">模组调整与前置问题 · {{ report.issues.length }} 项</summary>
          <div class="px-4 pb-4 space-y-2">
            <div v-for="(issue, index) in report.issues" :key="index" class="rounded-lg border border-slate-200 bg-white p-3 text-sm">
              <div class="flex flex-wrap items-center gap-2">
                <a v-if="issue.mod" :href="modProjectLink(issue.mod)" target="_blank" rel="noopener noreferrer" class="flex items-center gap-2 text-indigo-700 min-w-0">
                  <img v-if="icon(issue.mod)" :src="icon(issue.mod)" alt="" loading="lazy" class="w-8 h-8 rounded">
                  <span v-else class="w-8 h-8 rounded bg-slate-100 inline-flex items-center justify-center shrink-0">📦</span>
                  <span class="break-all">{{ issue.mod }}</span>
                </a>
                <span v-if="issue.dependency" class="text-slate-400">需要</span>
                <a v-if="issue.dependency" :href="modProjectLink(issue.dependency)" target="_blank" rel="noopener noreferrer" class="flex items-center gap-2 text-indigo-700">
                  <img v-if="icon(issue.dependency)" :src="icon(issue.dependency)" alt="" loading="lazy" class="w-8 h-8 rounded">
                  <span v-else class="w-8 h-8 rounded bg-slate-100 inline-flex items-center justify-center">📦</span>
                  <span class="break-all">{{ issue.dependency }}</span>
                </a>
              </div>
              <p class="mt-2 text-xs text-slate-600">{{ issue.dropped ? '未加入清单' : '前置待处理' }} · {{ issueReason(issue.reason) }}<span v-if="issue.versionId && !issue.dependency"> · 版本引用 {{ issue.versionId }}（项目名暂未取得）</span></p>
              <p v-if="issue.codes.includes('MULTILOADER_DEPENDENCY_UNVERIFIED')" class="mt-2 text-xs text-amber-800">该版本声明多个加载器，尚未核实这条依赖在当前加载器下的适用范围；必需依赖声明仍保留，未按可选项忽略。</p>
              <details v-if="issue.detail || issue.requiredVersions.length" class="mt-2 text-xs text-slate-500">
                <summary class="cursor-pointer">查看依赖判定依据</summary>
                <p v-if="issue.detail" class="mt-1 break-words">{{ issue.detail }}</p>
                <p v-if="issue.requiredVersions.length" class="mt-1 break-all">要求的版本：{{ issue.requiredVersions.join('、') }}</p>
              </details>
            </div>
          </div>
        </details>
        </template>
      </div>`
};
