// 图谱可以包含环和共享依赖，遍历按节点去重，不依赖JavaScript调用栈。
export const matchingAncestors = (nodes, children, query) => {
    const keyword = query.trim().toLowerCase();
    const parents = new Map();
    for (const [parent, ids] of Object.entries(children)) {
        if (!nodes[parent]) continue;
        for (const child of ids) {
            if (!nodes[child]) continue;
            const key = String(child);
            if (!parents.has(key)) parents.set(key, new Set());
            parents.get(key).add(parent);
        }
    }
    const matched = new Set(Object.keys(nodes).filter(id => {
        const node = nodes[id];
        return (node.title || '').toLowerCase().includes(keyword)
            || (node.slug || '').toLowerCase().includes(keyword);
    }));
    const queue = [...matched];
    for (let i = 0; i < queue.length; i++) {
        for (const parent of parents.get(queue[i]) || []) {
            if (matched.has(parent)) continue;
            matched.add(parent);
            queue.push(parent);
        }
    }
    return matched;
};

// 无天然根的循环分量也需展示入口；只补充尚未被现有根覆盖的节点。
export const coverTreeRoots = (orderedNodes, roots, children) => {
    const covered = new Set();
    const visit = id => {
        const queue = [id];
        for (let i = 0; i < queue.length; i++) {
            const current = queue[i];
            if (covered.has(current)) continue;
            covered.add(current);
            for (const child of children[current] || []) if (!covered.has(child)) queue.push(child);
        }
    };
    const result = [...roots];
    roots.forEach(node => visit(node.id));
    for (const node of orderedNodes) {
        if (covered.has(node.id)) continue;
        result.push(node);
        visit(node.id);
    }
    return result;
};
