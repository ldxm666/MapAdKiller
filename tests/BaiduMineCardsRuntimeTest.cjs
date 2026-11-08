// Run against bundles transformed by the production Kotlin adapter, not a JS reimplementation.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
let checks = 0;
function check(ok, label) { checks++; assert.ok(ok, label); }
const groups = [ ['common'], ['car'], ['voice', 'oldVoice'], ['carLogo'], ['sport'],
  ['contribution', 'ugc'], ['goldCoin', 'dxmFinance', 'userOperation', 'shop'], ['gold', 'banner'] ];
const nativeCards = ['header', 'common', 'order', 'banner', 'goldCoin', 'dxmFinance', 'shop',
  'gold', 'loading', 'car', 'voice', 'oldVoice', 'carLogo', 'sport', 'contribution', 'ugc',
  'userOperation', 'customService', 'tail', 'future-native-card'];
for (const file of process.argv.slice(2)) {
  const source = fs.readFileSync(file, 'utf8');
  const boundary = source.indexOf('\n;\n');
  check(boundary > 0, 'adapter prefix exists');
  const context = vm.createContext({});
  const before = vm.runInContext('[Array.prototype.concat,Array.prototype.push]', context);
  vm.runInContext(source.slice(0, boundary), context, { timeout: 1000 });
  const bind = context.__mapcleanMineBind234;
  check(typeof bind === 'function', 'actual adapter helper loads');
  const listeners = new Map();
  const bus = {
    on(name, fn) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); },
    off(name, fn) { listeners.get(name)?.delete(fn); },
    emit(name, payload) { for (const fn of listeners.get(name) || []) fn(payload); }
  };
  function page() {
    const watchers = new Map();
    const store = { cardList: nativeCards.slice(), phoneInfo: { gk: '1' }, serverData: { car: { id: 3 } } };
    const p = {
      data: { get(k) { return store[k]; }, set(k, v) { store[k] = v; for (const cb of watchers.get(k) || []) cb(v); } },
      $app: { TalosEmitter: bus },
      watch(k, cb) { if (!watchers.has(k)) watchers.set(k, []); watchers.get(k).push(cb); }
    };
    bind(p);
    return { p, store };
  }
  const a = page();
  const original = a.store.cardList;
  const event = 'mapclean.mine.cards.234';
  function configure(hidden) { bus.emit(event, JSON.stringify(hidden)); }
  for (let round = 0; round < 3; round++) {
    for (let mask = 0; mask < 256; mask++) {
      const hidden = groups.flatMap((g, bit) => mask & (1 << bit) ? [] : g);
      configure(hidden);
      check(JSON.stringify(a.store.__mapcleanMineCards234) === JSON.stringify(nativeCards.filter(v => !hidden.includes(v))),
        `independent live switches round=${round} mask=${mask}`);
      check(a.store.cardList === original, 'complete native model identity retained');
      check(a.store.phoneInfo.gk === '1' && a.store.serverData.car.id === 3, 'shared phone/server data retained');
    }
  }
  // User report: every optional card off, just the top icon grid on, then off/on again.
  const onlyGrid = groups.slice(1).flat();
  configure(onlyGrid);
  check(a.store.__mapcleanMineCards234.includes('common'), 'only-grid-on restores grid');
  check(!a.store.__mapcleanMineCards234.includes('carLogo'), 'grid does not enable the separate car-logo card');
  configure(groups.flat());
  check(!a.store.__mapcleanMineCards234.includes('common'), 'grid hides');
  configure(onlyGrid);
  check(a.store.__mapcleanMineCards234.includes('common'), 'grid restores in the same runtime');
  // A native server refresh/UGC update must not discard hidden cards needed on re-enable.
  configure(groups.flat());
  const refreshed = nativeCards.concat('new-server-card');
  a.p.data.set('cardList', refreshed);
  check(a.store.cardList === refreshed, 'server refresh is not rewritten');
  check(a.store.__mapcleanMineCards234.includes('new-server-card'), 'unknown new card passes');
  configure([]);
  check(JSON.stringify(a.store.__mapcleanMineCards234) === JSON.stringify(refreshed), 'master off/all on restores every native card');
  const retained = JSON.stringify(a.store.__mapcleanMineCards234);
  for (const invalid of ['invalid json', '{}', '{"value":[]}', '[1]', null]) {
    bus.emit(event, invalid);
    check(JSON.stringify(a.store.__mapcleanMineCards234) === retained, 'invalid event leaves current rendering intact');
  }
  const b = page();
  bind(a.p);
  check(listeners.get(event).size === 2, 'binding is idempotent');
  a.p.__mapcleanMineDispose234();
  check(listeners.get(event).size === 1, 'detach removes only this page listener');
  configure(onlyGrid);
  check(b.store.__mapcleanMineCards234.includes('common'), 'other page still receives updates');
  const after = vm.runInContext('[Array.prototype.concat,Array.prototype.push]', context);
  check(before[0] === after[0] && before[1] === after[1], 'Array prototypes unchanged');
  check(source.includes('processCardList:function(') && source.includes('processServerData:function('), 'native methods retained');
  console.log(`PASS runtime ${file}`);
}
check(process.argv.length > 2, 'at least one transformed device bundle required');
console.log(`PASS BaiduMineCardsRuntimeTest ${checks} assertions`);
