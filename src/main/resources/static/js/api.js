// 统一的请求封装：自动带鉴权头，并把"登录过期"收敛到一处处理。
//
// 为什么要单独一层：几乎每个功能都要发请求，鉴权和过期提示必须只有一份口径
// 服务端通过 401 表示登录过期；业务错误保留原登录态。
// 依赖方向是 api → store，不能反过来。
import {authUser, authError, showAuthModal, currentThought, buildProgress, requestStatuses} from './store.js';
import {mergeBuildProgress, readBuildProgress} from './utils/build-progress.js';
import {fulfilWanted} from './modrinth-client.js';

export const authHeader = () => authUser.value ? {'X-Auth-Token': authUser.value.token} : {};

// 🔥 全局 fetch 包装: 捕获未登录错误并提示重新登录
const authenticatedFetch = async (url, opts = {}) => {
    const {acceptErrorStatuses = [], ...requestOpts} = opts;
    const ownerToken = authUser.value?.token;
    const res = await fetch(url, { ...requestOpts, headers: { ...opts.headers, ...authHeader() } });
    // 401: 服务端明确返回未授权
    if (res.status === 401) {
        if (authUser.value?.token !== ownerToken) throw new DOMException('账号已切换', 'AbortError');
        authUser.value = null; localStorage.removeItem('maa-auth');
        authError.value = '登录已过期，请重新登录';
        showAuthModal.value = true;
        throw new Error('未登录');
    }
    // 业务错误不撤销登录态；只由明确的 401 触发重新登录。
    if (res.headers.get('content-type')?.includes('application/json')) {
        let json;
        try { json = await res.clone().json(); } catch { /* 非 JSON 错误页交由调用方处理。 */ }
        if (json?.error && !acceptErrorStatuses.includes(res.status)) {
            const error = new Error(json.error);
            error.status = res.status;
            throw error;
        }
    }
    return res;
};

// 轮询只读取已有任务；不重复提交业务请求，以免偏好计数或上传解析执行两次。
export const apiFetch = async (url, opts = {}) => {
    const ownerToken = authUser.value?.token;
    const ownedFetch = async (target, options) => {
        if (authUser.value?.token !== ownerToken) throw new DOMException('账号已切换', 'AbortError');
        const result = await authenticatedFetch(target, options);
        if (authUser.value?.token !== ownerToken) throw new DOMException('账号已切换', 'AbortError');
        return result;
    };
    const controller = new AbortController();
    opts = {...opts, signal: opts.signal ? AbortSignal.any([opts.signal, controller.signal]) : controller.signal};
    let res = await ownedFetch(url, opts);
    let taskId, operation;
    let rounds = 0;
    const deadline = Date.now() + 20 * 60 * 1000;
    try {
        for (;;) {
            opts.signal?.throwIfAborted();
            if (!res.headers.get('content-type')?.includes('json')) {
                if (url === '/api/chat') {
                    for (const row of readBuildProgress(await res.clone().text()))
                        buildProgress.value = mergeBuildProgress(buildProgress.value, row.taskId, [row]);
                }
                return res;
            }
            const envelope = await res.clone().json();
            if (!['queued', 'running', 'need-data'].includes(envelope.stage) || !envelope.taskId) return res;
            taskId = envelope.taskId;
            operation = envelope.operation === true;
            if (!/^[a-zA-Z0-9-]{1,128}$/.test(taskId)) throw new Error('任务标识无效');
            if (Date.now() >= deadline) throw new Error('任务等待超时，请稍后重试');
            const status = envelope.stage === 'queued'
                ? `正在排队，当前等待序号 ${envelope.queuePosition || 1}`
                : envelope.stage === 'need-data' ? '正在通过你的网络获取 Modrinth 数据' : '正在处理';
            if (operation) requestStatuses[taskId] = {message: status,
                cancel: () => controller.abort(new Error('操作已取消'))};
            if (url === '/api/chat') {
                buildProgress.value = mergeBuildProgress(buildProgress.value, taskId, envelope.progress);
                currentThought.value = envelope.stage === 'queued' ? status : '';
            }
            const base = operation ? `/api/operations/${taskId}` : `/api/chat/task/${taskId}`;
            if (envelope.stage === 'need-data') {
                if (++rounds > 2000) throw new Error('取数轮次超过限额');
                const budget = Number.isFinite(envelope.perCallBudgetMs)
                    ? Math.max(1, envelope.perCallBudgetMs - 1000) : 110_000;
                const facts = await fulfilWanted(envelope.wanted || [], opts.signal, budget);
                opts.signal?.throwIfAborted();
                res = await ownedFetch(`${base}/facts`, {method: 'POST', signal: opts.signal,
                    headers: {'Content-Type': 'application/json'}, body: JSON.stringify(facts)});
            } else {
                await new Promise(resolve => setTimeout(resolve, 1000));
                opts.signal?.throwIfAborted();
                res = await ownedFetch(operation ? base : `${base}/facts`, operation
                    ? {signal: opts.signal}
                    : {method: 'POST', signal: opts.signal, headers: {'Content-Type': 'application/json'}, body: '{}'});
            }
        }
    } catch (error) {
        if (taskId && error.status === 404)
            error.message = '任务已中断或过期，请重新提交。已保存的清单仍可从历史打开。';
        if (taskId && error.status !== 404 && authUser.value?.token === ownerToken) {
            const cancelUrl = operation ? `/api/operations/${taskId}/cancel` : '/api/chat/abort';
            ownedFetch(cancelUrl, {method: 'POST', headers: {'Content-Type': 'application/json'},
                body: operation ? '{}' : JSON.stringify({uuid: 'default-user'})}).catch(() => {});
        }
        throw error;
    } finally { if (taskId) delete requestStatuses[taskId]; }
};
