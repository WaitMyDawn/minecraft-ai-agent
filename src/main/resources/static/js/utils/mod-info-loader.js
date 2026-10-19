// Subscribers share requests; only the last departing subscriber cancels transport.
export const createModInfoLoader = ({owner, cached, store, request, concurrency = 2, now = Date.now}) => {
    const pending = new Map(), failed = new Map(), queue = [];
    let active = 0, epoch = 0;
    const pump = () => {
        while (active < concurrency && queue.length) {
            const entry = queue.shift();
            if (!entry.users.size || entry.controller.signal.aborted) continue;
            active++;
            Promise.resolve().then(() => request(entry.slug, entry.controller.signal)).then(data => {
                if (!entry.controller.signal.aborted && entry.epoch === epoch && entry.owner === owner()) {
                    if (!data || typeof data !== 'object' || data.error) throw new Error('Invalid metadata');
                    store(entry.slug, data);
                }
            }).catch(() => {
                if (!entry.controller.signal.aborted && entry.epoch === epoch && entry.owner === owner()) {
                    if (failed.size >= 500) failed.delete(failed.keys().next().value);
                    failed.set(entry.slug, now() + 60_000);
                }
            }).finally(() => {
                if (pending.get(entry.slug) === entry) pending.delete(entry.slug);
                for (const finish of [...entry.users]) finish();
                active--;
                pump();
            });
        }
    };
    const subscribe = (slug, signal) => {
        if (!owner() || signal?.aborted || cached(slug) || (failed.get(slug) || 0) > now()) return Promise.resolve();
        let entry = pending.get(slug);
        if (!entry) {
            if (pending.size >= 500) return Promise.resolve();
            entry = {slug, owner: owner(), epoch, controller: new AbortController(), users: new Set()};
            pending.set(slug, entry);
            queue.push(entry);
        }
        return new Promise(resolve => {
            const finish = () => {
                signal?.removeEventListener('abort', finish);
                entry.users.delete(finish);
                if (!entry.users.size) {
                    entry.controller.abort();
                    if (pending.get(slug) === entry) pending.delete(slug);
                    const index = queue.indexOf(entry);
                    if (index >= 0) queue.splice(index, 1);
                }
                resolve();
            };
            entry.users.add(finish);
            signal?.addEventListener('abort', finish, {once: true});
            pump();
        });
    };
    return {
        async load(slugs, signal) {
            await Promise.all([...new Set(slugs)].slice(0, 500)
                .filter(slug => typeof slug === 'string' && /^[a-zA-Z0-9._-]{1,128}$/.test(slug))
                .map(slug => subscribe(slug, signal)));
        },
        reset() {
            epoch++;
            for (const entry of pending.values()) {
                entry.controller.abort();
                for (const finish of [...entry.users]) finish();
            }
            pending.clear(); queue.length = 0; failed.clear();
        }
    };
};
