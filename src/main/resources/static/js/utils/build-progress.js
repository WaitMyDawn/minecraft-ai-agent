/** 阶段以任务及序号去重；取数轮次和倒序轮询快照不改变已发生的业务阶段。 */
export const mergeBuildProgress = (rows, taskId, entries) => {
    if (typeof taskId !== 'string' || !/^[a-zA-Z0-9-]{1,128}$/.test(taskId) || !Array.isArray(entries)) return rows;
    const result = rows.slice();
    for (const entry of entries.slice(0, 64)) {
        if (!Number.isInteger(entry.sequence) || entry.sequence < 1 || entry.sequence > 64
            || typeof entry.title !== 'string' || entry.title.length > 100 || !Number.isFinite(entry.startedAt)) continue;
        const id = `${taskId}:${entry.sequence}`;
        const found = result.findIndex(row => row.id === id);
        if (found >= 0) {
            if (!result[found].endedAt && Number.isFinite(entry.endedAt)) result[found] = {...result[found], endedAt: entry.endedAt};
            continue;
        }
        // 旧快照不能把迟到的低序号插入已显示的任务阶段。
        if (result.some(row => row.taskId === taskId && row.sequence > entry.sequence)) continue;
        if (result.length >= 128) break;
        result.push({...entry, taskId, id});
    }
    return result;
};

export const readBuildProgress = content => {
    const match = String(content ?? '').match(/<build_progress>(.*?)<\/build_progress>/s);
    if (!match) return [];
    try {
        const value = JSON.parse(match[1]);
        return Array.isArray(value.history) ? value.history.reduce((rows, task) => mergeBuildProgress(rows, task.taskId, task.entries), [])
            : mergeBuildProgress([], value.taskId, value.entries);
    } catch { return []; }
};
