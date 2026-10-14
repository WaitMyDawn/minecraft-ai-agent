// 加载器版本表（加载器 → 可选 MC 版本）的唯一取数入口。
//
// 规则编辑器和"生成空图谱"弹窗都要"先选加载器、再选版本"，选项必须来自同一张表
// （maa_db/loader-versions.json，由服务端 loader-envs 接口暴露）。各写一份的话，
// 迟早出现"一处能选、另一处选不出"的偏差 —— fabric 就是典型：它在版本表里的键叫
// fabric-loader，且只有通配项，这层差异由服务端统一换算。

import {apiFetch, authHeader} from '../api.js';

/** 拉取 { neoforge: ['1.20.1', ...], forge: [...], fabric: [...] }；失败返回空对象 */
export const loadLoaderEnvs = async () => {
    try {
        const r = await apiFetch('/api/knowledge/feedback/loader-envs', {headers: authHeader()});
        if (!r.ok) return {};
        return (await r.json()) || {};
    } catch (e) {
        return {};
    }
};

/**
 * 该加载器下的默认版本：优先 1.21.1（项目主力环境），否则取最新一个。
 * 列表由服务端按 MC 版本号升序返回，所以"最后一个"就是最新的。
 */
export const defaultVersionFor = (envs, loader) => {
    const versions = (envs && envs[loader]) || [];
    if (versions.length === 0) return '';
    return versions.includes('1.21.1') ? '1.21.1' : versions[versions.length - 1];
};
