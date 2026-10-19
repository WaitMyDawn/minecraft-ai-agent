// Observe the actual section so offscreen history does not populate icon metadata.
export const bindVisibleModInfo = (element, load, observe = callback => new IntersectionObserver(callback)) => {
    let visible = false, slugs = [], signature = '', controller, owner = true, disposed = false;
    const refresh = () => {
        if (disposed) return;
        const needed = !!owner && visible && (element.tagName !== 'DETAILS' || element.open);
        const next = needed ? JSON.stringify([owner, slugs]) : '';
        if (signature === next) return;
        controller?.abort();
        signature = next;
        if (needed && slugs.length) {
            controller = new AbortController();
            load(slugs, controller.signal);
        }
    };
    const observer = observe(entries => {
        for (const entry of entries) if (entry.target === element) visible = entry.isIntersecting;
        refresh();
    });
    const toggle = event => { if (event.target === element) refresh(); };
    element.addEventListener('toggle', toggle);
    observer.observe(element);
    return {
        update(values, nextOwner = true) { owner = nextOwner; slugs = [...new Set(values || [])].slice(0, 100); refresh(); },
        dispose() { disposed = true; observer.disconnect(); element.removeEventListener('toggle', toggle); controller?.abort(); }
    };
};
