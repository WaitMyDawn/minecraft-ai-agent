// 登录 / 注册 / 会话（对话列表与历史）相关的一切。
//
// 这个模块是自洽的：块内不调用任何留在 index.html 里的函数，只依赖 store（状态）与 api（请求）。
// 抽取前专门验过这一点——如果它反过来调用图谱/聊天里的函数，就会形成循环依赖，
// 那样只能靠注入回调解决，代价大得多。以后再往这里加东西，先看这条。
import {authError, authForm, authLoading, authMode, authSuccess, authUser, chatHistory,
    conversationList, currentConvId, currentView, hasApiKey, packData, showAuthModal,
    showSidebar, thinkTime} from '../store.js';
import {authHeader, apiFetch} from '../api.js';

// 从 localStorage 恢复登录 — 先恢复再异步校验服务端是否有效
const savedAuth = localStorage.getItem('maa-auth');
if (savedAuth) { try { authUser.value = JSON.parse(savedAuth); } catch(e){} }
// 异步校验 token 有效性，同步前后端状态
if (authUser.value && authUser.value.token) {
    fetch('/api/user/check-token', {
        headers: {'X-Auth-Token': authUser.value.token}
    }).then(r => r.json()).then(d => {
        if (!d.valid) {
            authUser.value = null;
            localStorage.removeItem('maa-auth');
            console.log('令牌已过期，已清除本地登录状态');
        } else {
            // 同步服务端最新数据
            authUser.value = { ...authUser.value, ...d, token: authUser.value.token };
            hasApiKey.value = d.hasApiKey === true;
            localStorage.setItem('maa-auth', JSON.stringify(authUser.value));
            // 刷新页面后必须把对话列表拉回来：侧边栏那个列表是"打开旧对话"的唯一入口，
            // 不拉就永远是空的——数据库里的记录还在，但页面上没有任何路径能点进去。
            // 放在这里的 then 回调里是安全的：它一定在模块体执行完之后才跑，
            // 不会撞上 loadConversations 这个 const 的暂时性死区。
            loadConversations();
        }
    }).catch(() => {});
}


export const doAuth = async () => {
    authLoading.value = true; authError.value = ''; authSuccess.value = '';
    const url = authMode.value === 'login' ? '/api/user/login' : '/api/user/register';
    const body = authMode.value === 'login'
        ? { account: authForm.account, password: authForm.password }
        : { username: authForm.username, password: authForm.password };
    try {
        const r = await fetch(url, {  // url 已是 /api/user/login 格式，浏览器自动拼域名
            method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
        });
        // 非 JSON 响应 = 后端未启动或用 file:// 打开，给明确提示
        const contentType = r.headers.get('content-type') || '';
        if (!contentType.includes('application/json')) {
            authError.value = '请通过 http://localhost:8080 访问，不要用文件方式打开';
            return;
        }
        const d = await r.json();
        if (d.error) { authError.value = d.error; return; }
        authUser.value = d;
        localStorage.setItem('maa-auth', JSON.stringify(d));
        if (authMode.value === 'register') {
            authSuccess.value = '✅ 注册成功！你的账号是: ' + d.accountNumber + ' (已自动登录)';
            setTimeout(() => { showAuthModal.value = false; authSuccess.value = ''; }, 3000);
        } else {
            showAuthModal.value = false;
        }
        await loadConversations();
    } catch(e) {
        console.error('Auth request failed:', e);
        authError.value = '网络错误: ' + (e.message || '请检查后端是否启动');
    } finally { authLoading.value = false; }
};
export const logout = () => {
    authUser.value = null; localStorage.removeItem('maa-auth');
    currentView.value = 'chat'; showSidebar.value = false; conversationList.value = [];
};

// ========== 对话历史 ==========
export const loadConversations = async () => {
    if (!authUser.value) return;
    try {
        const r = await fetch('/api/prefs/conversations', {headers: authHeader()});
        conversationList.value = await r.json();
    } catch(e) {}
};
export const newConversation = async () => {
    if (!authUser.value) return;
    try {
        const r = await fetch('/api/prefs/conversations', {
            method: 'POST', headers: {...authHeader(), 'Content-Type': 'application/json'},
            body: JSON.stringify({title: '新对话 ' + new Date().toLocaleDateString()})
        });
        const d = await r.json();
        currentConvId.value = d.id;
        // 新对话 = 新包：清空清单与删除排除名单
        chatHistory.value = []; packData.modSlugs = []; packData.excludedSlugs = [];
        await loadConversations();
    } catch(e) {}
};
export const loadConversation = async (id) => {
    try {
        const r = await fetch('/api/prefs/conversations/' + id + '/messages', {headers: authHeader()});
        const msgs = await r.json();
        chatHistory.value = msgs.map(m => ({
            role: m.role, content: m.content, hasData: m.hasData,
            thinkTime: m.thinkTime || undefined
        }));
        if (msgs.length > 0 && msgs[msgs.length-1].modSlugs) {
            packData.modSlugs = msgs[msgs.length-1].modSlugs.split(',');
        }
        // 切换历史对话 = 切换包：不继承上一个包的删除排除名单
        packData.excludedSlugs = [];
        currentConvId.value = id;
    } catch(e) {}
};
export const deleteConversation = async (id) => {
    await fetch('/api/prefs/conversations/' + id, {
        method: 'DELETE', headers: authHeader()
    });
    if (currentConvId.value === id) { currentConvId.value = null; chatHistory.value = []; }
    await loadConversations();
};
export const saveMsg = async (role, content, hasData, thinkTime, slugs) => {
    if (!authUser.value || !currentConvId.value) return;
    try {
        const body = {role, content, hasData, thinkTime: thinkTime||'', modSlugs: (slugs||[]).join(',')};
        const title = chatHistory.value.length <= 2 && role === 'user' ? content.substring(0, 30) : null;
        if (title) body.title = title;
        await fetch('/api/prefs/conversations/' + currentConvId.value + '/messages', {
            method: 'POST', headers: {...authHeader(), 'Content-Type': 'application/json'},
            body: JSON.stringify(body)
        });
    } catch(e) {}
};
