// Minimal hook lifecycle for handler tests; production components and hooks stay real.
export function createReactHost() {
  const slots = [];
  let cursor = 0, pending = [], dirty = false, unmounted = false, lateWrites = 0;
  const changed = (before, after) => !before || !after || before.length !== after.length ||
    after.some((value, index) => !Object.is(value, before[index]));
  const hooks = {
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) slots[index] = typeof initial === 'function' ? initial() : initial;
      return [slots[index], value => {
        if (unmounted) lateWrites++;
        const next = typeof value === 'function' ? value(slots[index]) : value;
        if (!Object.is(next, slots[index])) {slots[index] = next;dirty = true;}
      }];
    },
    useRef(initial) {const index = cursor++;return slots[index] ??= {current: initial};},
    useEffect(effect, dependencies) {
      const index = cursor++;
      if (changed(slots[index]?.dependencies, dependencies)) pending.push(() => {
        slots[index]?.cleanup?.();slots[index] = {dependencies, cleanup: effect()};
      });
    },
    useMemo(create, dependencies) {
      const index = cursor++;
      if (changed(slots[index]?.dependencies, dependencies)) slots[index] = {dependencies, value: create()};
      return slots[index].value;
    },
    useCallback(fn, dependencies) {return hooks.useMemo(() => fn, dependencies);},
  };
  return {hooks,
    render(fn) {cursor = 0;dirty = false;pending = [];return fn();},
    commit() {const effects = pending;pending = [];effects.forEach(effect => effect());},
    unmount() {unmounted = true;slots.forEach(slot => slot?.cleanup?.());},
    get dirty() {return dirty;},
    get lateWrites() {return lateWrites;},
  };
}

export function findNode(node, predicate) {
  if (Array.isArray(node)) {
    for (const child of node) {const result = findNode(child, predicate);if (result) return result;}
  } else if (node && typeof node === 'object') {
    if (predicate(node)) return node;
    return findNode(node.props?.children, predicate);
  }
}

export function nodeText(node) {
  if (Array.isArray(node)) return node.map(nodeText).join('');
  if (node && typeof node === 'object') return nodeText(node.props?.children);
  return typeof node === 'string' || typeof node === 'number' ? String(node) : '';
}
