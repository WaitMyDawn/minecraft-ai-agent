// 统一的请求封装：自动带鉴权头，并把"登录过期"收敛到一处处理。
//
// 为什么要单独一层：几乎每个功能都要发请求，鉴权和过期提示必须只有一份口径
// （401、500 里夹带"未登录"、200 但 body 带 error——三种都要认）。
// 依赖方向是 api → store，不能反过来。
import {authUser, authError, showAuthModal} from './store.js';

export const authHeader = () => authUser.value ? {'X-Auth-Token': authUser.value.token} : {};

// 🔥 全局 fetch 包装: 捕获未登录错误并提示重新登录
export const apiFetch = async (url, opts = {}) => {
    const res = await fetch(url, { ...opts, headers: { ...opts.headers, ...authHeader() } });
    // 401: 服务端明确返回未授权
    if (res.status === 401) {
        authUser.value = null; localStorage.removeItem('maa-auth');
        authError.value = '登录已过期，请重新登录';
        showAuthModal.value = true;
        throw new Error('未登录');
    }
    // 500 with 未登录 text
    if (res.status === 500) {
        try {
            const text = await res.clone().text();
            if (text.includes('未登录') || text.includes('RuntimeException')) {
                authUser.value = null; localStorage.removeItem('maa-auth');
                authError.value = '登录状态异常，请重新登录';
                showAuthModal.value = true;
                throw new Error('未登录');
            }
        } catch(e) { if (e.message === '未登录') throw e; }
    }
    // 200 but JSON body contains error field (defense in depth)
    if (res.status === 200 && res.headers.get('content-type')?.includes('application/json')) {
        try {
            const cloned = res.clone();
            const json = await cloned.json();
            if (json.error && (json.error.includes('未登录') || json.error.includes('不存在'))) {
                authUser.value = null; localStorage.removeItem('maa-auth');
                authError.value = '登录已过期，请重新登录';
                showAuthModal.value = true;
                throw new Error('未登录');
            }
            if (json.error) throw new Error(json.error);
        } catch(e) { if (e.message === '未登录') throw e; }
    }
    return res;
};
