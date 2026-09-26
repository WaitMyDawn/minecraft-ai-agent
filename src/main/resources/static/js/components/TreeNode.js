// 依赖树节点（递归组件）。
//
// 8 个 provide/inject key 与 ChatController 侧的 provide 一一对应，
// 改这里就等于改契约：inject 拿不到会静默变成 undefined，报错要等到用户点勾选框才出现。
import {computed, inject} from 'vue';

const TreeNodeComponent = {
    name: 'TreeNode',
    template: '#tree-node-template',
    props: ['node', 'path', 'visited'],
    setup(props) {
        const selectedMods = inject('selectedMods');
        const toggleMod = inject('toggleMod');
        const childrenMap = inject('childrenMap');
        const nodesMap = inject('nodesMap');
        const globalHighlightId = inject('globalHighlightId');
        // 🗑️ 批量删除模式：复用同一勾选框，但语义切换为"标记删除"
        const deleteMode = inject('deleteMode');
        const deleteMarked = inject('deleteMarked');
        const toggleDeleteMark = inject('toggleDeleteMark');
        const conflictIds = inject('conflictIds');

        const isSelected = computed(() => selectedMods.value.has(props.node.id));
        const toggle = () => toggleMod(props.node.id);
        const isDeleteMarked = computed(() => deleteMarked.value.has(props.node.id));
        const isConflict = computed(() => conflictIds.value.has(props.node.id));
        // 勾选框显示状态：删除模式下显示"待删除标记"，否则显示"是否导出"
        const isChecked = computed(() => deleteMode.value ? isDeleteMarked.value : isSelected.value);
        const onToggle = () => {
            if (deleteMode.value) toggleDeleteMark(props.node.id);
            else toggle();
        };

        const childrenIds = computed(() => childrenMap.value[props.node.id] || []);
        const children = computed(() => {
            let arr = childrenIds.value.map(id => nodesMap.value[id]).filter(Boolean);
            return arr.sort((a, b) => (a.slug || '').localeCompare(b.slug || ''));
        });

        const isCycle = computed(() => props.visited.includes(props.node.id));
        const currentVisited = computed(() => [...props.visited, props.node.id]);

        return {
            isSelected, toggle, children, isCycle, currentVisited, globalHighlightId,
            deleteMode, isDeleteMarked, isChecked, onToggle, isConflict
        };
    }
};

export default TreeNodeComponent;
