// 应用级共享状态（单例）。
//
// 全部从 index.html 的 setup() 里搬出来：那边是一个巨大的闭包，220 个符号挤在一个作用域里，
// 谁也说不清哪些状态被谁用。搬到这里之后，import 名就是契约。
//
// 注意：这里只按原样搬运，不重新分类。功能私有的状态（keEnvs、profileApiKey …）
// 会在后续步骤里从本文件挪进各自的功能模块——那属于局部小改，好验证。

import {ref, reactive} from 'vue';

export const currentView = ref('chat');    // 控制当前显示的视图: chat, lab, db, sync
export const chatHistory = ref([]);        // 存储所有消息 [{role, content, thinkTime}]
export const userInput = ref('');          // 输入框当前文本
export const sessionUuid = ref('session-' + Date.now() + '-' + Math.random().toString(36).slice(2,8));
export const searchQuery = ref('');
export const thinkTime = ref('0.0');
export const currentThought = ref('');     // 当前显示的思考步骤文案
export const isLoading = ref(false);
export const isResolving = ref(false);
export const isBuilding = ref(false);
export const packData = reactive({
    name: "MAA-Pack",
    mcVersion: "1.21.1",
    loader: "neoforge",
    modSlugs: [],
    // 🗑️ 用户在图谱里显式删除的模组：随 preview/chat 一起下发，
    // 防止被 Architect 复述或依赖引擎当作前置重新补回来（P2）
    excludedSlugs: []
});
export const previewData = ref(null);
export const selectedMods = ref(new Set());
export const rootTreeNodes = ref([]);              // 根节点（不被任何模组依赖的模组）
export const nodesMap = ref({});                   // ID -> 模组对象的映射表
export const childrenMap = ref({});                // ID -> 依赖它的模组ID数组
export const globalHighlightId = ref(null);        // 当前高亮的模组ID
export const dbRules = ref([]);                    // 存储从后端获取的所有规则
export const deleteMode = ref(false);            // 左侧树是否处于"批量删除"标记模式
export const deleteMarked = ref(new Set());      // 标记模式下待删除的节点 id
export const deleteBusy = ref(false);            // 删除执行中
export const isRefreshing = ref(false);          // 后台异步重跑 preview 中
export const deleteConflicts = ref([]);          // 断链诊断 [{requiredBy, missing}]
export const conflictIds = ref(new Set());       // 断链受影响的节点 id（用于树/图描边）
export const ctxMenu = reactive({visible: false, x: 0, y: 0, nodeId: null});
export const deleteDialog = reactive({
    visible: false,
    stage: '',        // 'cascade' = 被删项是别人的前置；'prereq' = 被删项自己有前置
    seed: [],
    dependents: [],
    prerequisites: []
});
export const pendingMrpack = ref(null);
export const manualAddSlug = ref('');
export const showSuggestBoard = ref(false);
export const isSuggesting = ref(false);
export const suggestedMods = ref([]);
export const selectedSuggests = ref(new Set());
export const availableCategories = ref(['technology', 'magic', 'adventure', 'worldgen', 'food',
    'storage', 'optimization', 'equipment', 'utility', 'decoration',
    'mobs', 'cursed', 'economy', 'game-mechanics', 'library', 'management',
    'minigame', 'social', 'transportation']);
export const categoryGloss = ref({});   // category -> 中文释义（用于按钮 tooltip）
export const suggestForm = reactive({ categories: [], minDownloads: 0, maxDownloads: 2100000000, sortType: 'downloads', page: 0 });
export const pageJumpInput = ref('');
export const suggestMode = ref('pack'); // 'pack' = 添加到整合包, 'prefs' = 添加到偏好
export const showAuthModal = ref(false);
export const authMode = ref('login');  // 'login' | 'register'
export const authForm = reactive({account: '', username: '', password: ''});
export const authError = ref('');
export const authSuccess = ref('');
export const authLoading = ref(false);
export const authUser = ref(null);  // {token, accountNumber, username}
export const showSidebar = ref(false);
export const currentConvId = ref(null);
export const conversationList = ref([]);
export const profileApiKey = ref('');
export const profileApiKeyMsg = ref('');
export const profileUsername = ref('');
export const profileWeight = ref(authUser.value ? authUser.value.preferenceWeight : 0.3);
export const hasApiKey = ref(false);  // 是否已设置 API Key
export const pwOld = ref('');
export const pwNew = ref('');
export const pwMsg = ref('');
export const categoryPrefs = ref([]);
export const modPrefs = ref([]);
export const modPrefSearch = ref('');
export const newCatPref = reactive({category: 'adventure', rank: 5});
export const modPrefPage = ref(0);
export const modPrefJump = ref('');
export const blacklistPage = ref(0);
export const blacklistJump = ref('');
export const modInfoCache = ref({});
export const manualSlugInput = ref('');
export const addingModBySlug = ref(false);
export const modSlugAddMsg = ref('');
export const editingBuildCountId = ref(null);
export const editingBuildCountVal = ref(0);
export const blacklistItems = ref([]);
export const blacklistSlugInput = ref('');
export const blacklistMsg = ref('');
export const enableBlacklist = ref(false);
export const enableUserFeedbackRules = ref(false);
export const keEnvs = ref(['neoforge-1.21.1']);
export const keEnv = ref('neoforge-1.21.1');
export const keRelation = ref('DEPENDS_ON');
export const keModA = ref('');
export const keModB = ref('');
export const keModAInfo = ref(null);
export const keModBInfo = ref(null);
export const keModAError = ref('');
export const keModBError = ref('');
export const keMsg = ref('');
