/* SettleIQ — reconciliation control surface.
 *
 * Reads /api/v1 and renders it. Does NO money arithmetic: every figure arrives
 * as an integer of paise the engine computed, and this file only formats it.
 * The single place numbers are touched is bar geometry, which is presentation.
 *
 * It also does not infer. Where the API does not return something — why the
 * agent chose a lookup, or how confident it was in one particular candidate —
 * the UI shows what IS on the record instead of inventing the rest. An
 * interface that guesses is indistinguishable from one that fabricates.
 */

const API = '/api/v1';

const S = {
  merchants: [], merchant: null,
  runs: [], runId: null,
  credits: [], exceptions: [], metrics: null, chain: null, deploy: null,
  tab: 'overview',
  focus: null,                        // settlement id open in the workspace
  trailPick: null,                    // selected money-trail node
  ledger: null, ledgerFor: null,      // /audit/entries, cached per merchant
  running: false, lastRun: null,      // an in-flight run, then its real figures
  traceDetail: null,                  // expanded control-trace event index
  paintKey: null,                     // last view actually animated in
  credit: null, exception: null, refocus: null,
  payments: {},                       // settlement_id -> real payment rows
  ambiguous: [],                      // every payment this run refused to separate
  creditFilter: 'all', creditQuery: '',
  excFilter: 'all',
  ingestRuns: [], ingestResult: null, ingestBusy: false,
  trace: null, traceKey: null, traceOpen: {},
};

/* ───────────────────────────────────────────────────────────── helpers */
const $  = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const el = (html) => { const d = document.createElement('div'); d.innerHTML = html.trim(); return d.firstElementChild; };
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const ico = (name, cls = '') => `<svg class="ico ${cls}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const REDUCED_MOTION = matchMedia('(prefers-reduced-motion: reduce)').matches;

/** paise (integer) -> Indian-grouped rupees. Never used to compute. */
function inr(paise) {
  const neg = paise < 0;
  let p = Math.abs(paise);
  let whole = String(Math.floor(p / 100));
  const frac = String(p % 100).padStart(2, '0');
  if (whole.length > 3) {
    let head = whole.slice(0, -3);
    const tail = whole.slice(-3);
    const parts = [];
    while (head.length > 2) { parts.unshift(head.slice(-2)); head = head.slice(0, -2); }
    if (head) parts.unshift(head);
    whole = parts.join(',') + ',' + tail;
  }
  return { neg, whole, frac };
}

/**
 * A money figure.
 *
 * Rupee mark small and lifted, integer at full weight, paise stepped down. A
 * finance operator scans a column for magnitude; the paise still have to be
 * there, because reconciling to the paise is the entire product — they just
 * should not compete with the rupees for attention.
 */
function money(paise, cls = '') {
  const { neg, whole, frac } = inr(paise);
  return `<span class="fig ${cls}"><span class="cur">₹</span>${neg ? '−' : ''}${whole}<span class="p">.${frac}</span></span>`;
}
/** Plain text form, for tooltips and titles where markup is not available. */
function moneyText(paise) {
  const { neg, whole, frac } = inr(paise);
  return `${neg ? '−' : ''}₹${whole}.${frac}`;
}

/* ── access ───────────────────────────────────────────────────────────────
 * A deployment with SETTLEIQ_API_KEYS set expects x-api-key on every call, and
 * keys are scoped to merchants. The key lives in this browser only: it goes to
 * this API and nowhere else, and never reaches the ledger or a log.
 *
 * With auth switched off the header is simply absent, which is why the strip
 * reports the posture rather than assuming one. */
const KEY_STORE = 'settleiq.apikey';
function apiKey() { try { return localStorage.getItem(KEY_STORE) || ''; } catch { return ''; } }
function setApiKey(v) {
  try { v ? localStorage.setItem(KEY_STORE, v) : localStorage.removeItem(KEY_STORE); } catch {}
}
function authHeaders(base = {}) {
  const k = apiKey();
  return k ? { ...base, 'x-api-key': k } : base;
}

/** A refusal is a posture problem, not an outage, and reads as one. */
function httpError(path, status, detail) {
  if (status === 401 || status === 403) {
    return new Error(detail || (apiKey()
      ? 'the key held in this browser is not scoped to this merchant'
      : 'this deployment requires an API key'));
  }
  return new Error(detail || `${path} → ${status}`);
}

async function get(path) {
  const r = await fetch(API + path, { headers: authHeaders() });
  if (!r.ok) {
    let d = ''; try { d = (await r.json()).detail || ''; } catch {}
    throw httpError(path, r.status, d);
  }
  return r.json();
}
async function post(path, body) {
  const r = await fetch(API + path, {
    method: 'POST', headers: authHeaders({ 'content-type': 'application/json' }),
    body: JSON.stringify(body),
  });
  if (!r.ok) {
    let d = ''; try { d = (await r.json()).detail || ''; } catch {}
    throw httpError(path, r.status, d);
  }
  return r.json();
}

let toastTimer;
function toast(msg, ms = 3800) {
  const t = $('#toast');
  t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, ms);
}

const tip = el('<div class="tip" hidden></div>');
document.body.appendChild(tip);
function showTip(evt, html) {
  tip.innerHTML = html; tip.hidden = false;
  const pad = 14, w = tip.offsetWidth, h = tip.offsetHeight;
  let x = evt.clientX + pad, y = evt.clientY + pad;
  if (x + w > innerWidth - 8) x = evt.clientX - w - pad;
  if (y + h > innerHeight - 8) y = evt.clientY - h - pad;
  tip.style.left = x + 'px'; tip.style.top = y + 'px';
}
const hideTip = () => { tip.hidden = true; };
const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

/* ───────────────────────────────────────────────────────────── boot */
/** ARIA tabs pattern: one stop in the tab order, arrows move between views. */
let selectTab = () => {};
function wireRail() {
  const tabs = $$('.rail button');
  const select = (b) => {
    tabs.forEach(x => {
      const on = x === b;
      x.setAttribute('aria-selected', String(on));
      x.tabIndex = on ? 0 : -1;
    });
    $('#work').setAttribute('aria-labelledby', b.id);
    S.tab = b.dataset.t;
  };
  // Named views can be reached from a link inside the page, not only the rail.
  selectTab = (name) => { const b = tabs.find(x => x.dataset.t === name); if (b) select(b); };
  tabs.forEach((b, i) => {
    b.onclick = () => { S.focus = null; select(b); render(); };
    b.onkeydown = (e) => {
      const d = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1,
                  Home: -i, End: tabs.length - 1 - i }[e.key];
      if (d === undefined) return;
      e.preventDefault();
      const n = tabs[(i + d + tabs.length) % tabs.length];
      n.focus(); S.focus = null; select(n); render();
    };
  });
}

async function boot() {
  wireRail();
  $('#merchant').onchange = async (e) => { S.merchant = e.target.value; await loadMerchant(); };
  $('#run').onchange = async (e) => { S.runId = e.target.value || null; await loadRun(); };
  $('#runBtn').onclick = reconcile;

  // Asked, not assumed. The strip reports whether auth and the LLM planner are
  // actually on, including when the answer is "neither".
  try { S.deploy = await get('/auth/status'); } catch { S.deploy = null; }
  paintDeploy();

  skeleton();
  try {
    S.merchants = await get('/merchants');
  } catch (e) {
    const denied = /key|scoped|require/i.test(e.message);
    fail(denied ? 'This deployment requires an API key' : 'API unreachable',
      denied ? `${esc(e.message)}. Select <b>Posture</b> in the strip above to enter one.`
             : `${esc(e.message)}. Check the service is running.`);
    return;
  }
  if (!S.merchants.length) { fail('No merchants seeded', 'Run <b>make generate</b>, then seed the database.'); return; }
  $('#merchant').innerHTML = S.merchants.map(m => `<option>${esc(m)}</option>`).join('');
  S.merchant = S.merchants[0];
  await loadMerchant();
}

function fail(title, detail) {
  $('#work').innerHTML = `<div class="panel"><div class="empty">${ico('alert')}
    <b>${esc(title)}</b><span>${detail}</span></div></div>`;
}

function skeleton() {
  const row = () => `<div class="skrow"><div class="sk"></div><div class="sk"></div>
                     <div class="sk"></div><div class="sk w60"></div></div>`;
  $('#work').innerHTML = `<div class="stack" aria-hidden="true">
    <section class="panel" style="padding:22px 24px">
      <div class="sk w40"></div><div class="sk v w60"></div></section>
    <section class="panel"><header><h2>Loading</h2><span class="sub">reading the ledger</span></header>
      ${row().repeat(10)}</section></div>`;
}

async function loadMerchant() {
  S.runs = await get(`/runs?merchantId=${encodeURIComponent(S.merchant)}&limit=25`);
  const done = S.runs.filter(r => r.state === 'succeeded');
  $('#run').innerHTML = done.length
    ? done.map(r => `<option value="${r.run_id}">#${r.run_id} · ${(r.stats?.payment_links ?? 0).toLocaleString()} links · ${r.wall_ms}ms</option>`).join('')
    : `<option value="">none yet</option>`;
  S.runId = done.length ? String(done[0].run_id) : null;
  S.ingestResult = null; S.payments = {};
  await loadIngestRuns();
  await loadRun();
}

async function loadRun() {
  S.credit = null; S.exception = null; S.payments = {};
  if (!S.runId) { S.credits = []; S.exceptions = []; render(); return; }
  skeleton();
  const q = `merchantId=${encodeURIComponent(S.merchant)}&runId=${S.runId}`;
  const [credits, exceptions, chain, metrics, ambiguous] = await Promise.all([
    get(`/credits?${q}`), get(`/exceptions?${q}`),
    fetch(`${API}/audit/verify?merchantId=${encodeURIComponent(S.merchant)}`, { headers: authHeaders() }).then(r => r.json()),
    get('/metrics').catch(() => null),
    get(`/ambiguous?${q}`).catch(() => []),
  ]);
  S.credits = credits; S.exceptions = exceptions; S.chain = chain; S.metrics = metrics;
  S.ambiguous = ambiguous;
  // Open on the item that needs attention, not on row one.
  S.credit = credits.find(c => c.residue !== 0) || credits[0] || null;
  S.exception = exceptions.find(e => e.verdict === 'ESCALATE') || exceptions[0] || null;
  paintChain(); paintExcCount();
  S.trace = null; S.traceKey = null; S.traceOpen = {};
  render();
  if (S.exception) loadTrace(S.exception.settlement_id);
  if (S.credit) fetchPayments(S.credit.settlement_id);
  if (S.exception) fetchPayments(S.exception.settlement_id);
}

/** Real batch membership, fetched on demand and cached per settlement. */
async function fetchPayments(settlementId) {
  if (!settlementId || S.payments[settlementId]) return;
  S.payments[settlementId] = 'loading';
  try {
    S.payments[settlementId] = await get(
      `/credits/${encodeURIComponent(settlementId)}/payments`
      + `?merchantId=${encodeURIComponent(S.merchant)}&runId=${S.runId}`);
  } catch { S.payments[settlementId] = []; }
  render();
}

function paintChain() {
  const p = $('#chainPill'), c = S.chain;
  p.className = 'lamp' + (c ? (c.valid ? ' ok' : ' bad') : '');
  // The shield swaps glyph on a break, so state is not carried by colour alone.
  $('use', p).setAttribute('href', c && !c.valid ? '#i-shield-alert' : '#i-shield-check');
  $('.txt', p).textContent = c ? (c.valid ? `${c.rows} verified` : 'BROKEN') : '—';
  p.title = c?.head ? `chain head ${c.head}` : '';
}

function paintDeploy() {
  const p = $('#deployPill'), d = S.deploy;
  p.onclick = accessPanel;
  if (!d) { $('.txt', p).textContent = 'unknown'; return; }
  const held = !!apiKey();
  // With auth on and no key held the lamp reads bad, not neutral: the operator
  // is one request away from a wall of refusals and should know before they hit it.
  p.className = 'lamp' + (d.auth_enabled ? (held ? ' ok' : ' bad') : '');
  $('use', p).setAttribute('href', d.auth_enabled ? '#i-lock' : '#i-unlock');
  $('.txt', p).textContent = `${d.auth_enabled ? (held ? 'auth · key held' : 'auth · no key') : 'open'}`
    + ` · ${d.llm_available ? 'llm' : 'no llm'}`;
  p.title = [d.auth_note, d.llm_note, 'Select to manage the key held in this browser.'].join('\n');
}

/** Where a key is entered, replaced, or cleared. */
function accessPanel() {
  const d = S.deploy || {};
  const held = apiKey();
  openDrawer('Access', `
    ${field('Deployment posture', d.auth_enabled
      ? `<span class="pill ok">${ico('lock','sm')}API KEY REQUIRED</span>`
      : `<span class="pill warn">${ico('unlock','sm')}OPEN</span>`)}
    ${field('What the server reports', esc(d.auth_note || 'the deployment did not answer'))}
    ${field('Key held in this browser', held
      ? `<span class="mono">${esc(held.slice(0, 4))}…${esc(held.slice(-4))}</span>`
      : '<span class="dim">none</span>')}
    <div class="f">
      <div class="lab">Set a key</div>
      <div class="v">
        <input type="password" id="akey" autocomplete="off" spellcheck="false"
               aria-label="API key" placeholder="paste key" style="width:100%">
        <div style="display:flex; gap:8px; margin-top:10px">
          <button class="btn primary" id="aksave" type="button">${ico('check','sm')}<span>Save key</span></button>
          <button class="btn" id="akclear" type="button">${ico('x','sm')}<span>Clear</span></button>
        </div>
      </div>
    </div>
    <div class="note" style="padding-left:0; padding-right:0">Held in this browser's local storage and
      sent only to this API, as <span class="mono">x-api-key</span>. Keys are scoped to merchants: a key
      for one merchant reading another is refused by the server, not by this page.</div>`);
  const reload = () => { closeDrawer(); boot(); };
  $('#aksave').onclick = () => {
    const v = $('#akey').value.trim();
    if (!v) { toast('Enter a key, or select Clear to remove the one held.'); return; }
    setApiKey(v); toast('Key saved for this browser.'); reload();
  };
  $('#akclear').onclick = () => { setApiKey(''); toast('Key cleared.'); reload(); };
}

function paintExcCount() {
  const n = S.exceptions.filter(e => e.verdict === 'ESCALATE').length;
  const c = $('#excCount');
  c.hidden = !n; c.textContent = n;
  c.title = `${n} held for review`;
}

/* The stages a run really has. The API is synchronous and reports nothing until
   it finishes, so this list is never animated as progress — it is shown as
   pending while the request is open, then filled in with the counts the run
   actually returned. Inventing a moving bar here would be inventing state. */
const RUN_STAGES = [
  ['Preparing data',        r => `${r.payment_links.toLocaleString()} payment links built`],
  ['Matching credits',      r => 'bank credits linked to settlements'],
  ['Proving batch membership', r => 'exact partition per batch'],
  ['Detecting exceptions',  r => `${r.exceptions} exception${r.exceptions === 1 ? '' : 's'} raised`],
  ['Investigating',         r => 'bounded read-only lookups'],
  ['Applying policy',       r => 'six deterministic gates'],
  ['Writing the ledger',    r => `${r.audit_appended} entr${r.audit_appended === 1 ? 'y' : 'ies'} appended`],
];

function runProgress() {
  if (!S.running && !S.lastRun) return null;
  const r = S.lastRun;
  return el(`<section class="panel">
    <header><h2>${S.running ? 'Reconciling' : `Run #${esc(String(r.run_id))} complete`}</h2>
      <span class="sub">${S.running
        ? 'the engine runs synchronously and reports once, at the end'
        : `${r.wall_ms} ms end to end`}</span>
      <span class="right">${S.running
        ? '<span class="live"><i></i>in flight</span>'
        : badge('VERIFIED')}</span></header>
    <div class="stages" style="padding:6px 16px 14px">
      ${RUN_STAGES.map(([name, detail]) => `<div class="stg ${S.running ? '' : 'done'}">
        <span class="d"></span>
        <span>${esc(name)}${!S.running && r ? ` — ${esc(detail(r))}` : ''}</span></div>`).join('')}
    </div>
    ${S.running ? `<div class="note">No stage is marked complete while the request is open, because
      the API does not report partial progress. The result below is the run's own figures.</div>` : ''}
  </section>`);
}

async function reconcile() {
  const b = $('#runBtn');
  b.disabled = true; $('.t', b).textContent = 'Running';
  S.running = true; S.lastRun = null; S.focus = null;
  selectTab('overview'); render();
  try {
    const r = await post('/runs', { merchantId: S.merchant, preset: 'full' });
    S.running = false; S.lastRun = r;
    toast(`Run #${r.run_id} · ${r.payment_links.toLocaleString()} links · ${r.exceptions} exceptions · `
        + `${r.audit_appended} ledger rows appended · ${r.wall_ms}ms`);
    S.ledgerFor = null;
    await loadMerchant();
  } catch (e) {
    S.running = false;
    toast('Run failed: ' + e.message, 7000);
    render();
  } finally {
    b.disabled = false; $('.t', b).textContent = 'Reconcile';
  }
}

/* ───────────────────────────────────────────────────────────── render */
function render() {
  const m = $('#work');
  m.innerHTML = '';
  if (!S.runId && S.tab !== 'ingest' && S.tab !== 'ledger' && !S.running) {
    m.appendChild(el(`<div class="panel"><div class="empty">${ico('refresh')}
      <b>No completed run for ${esc(S.merchant)}</b>
      <span>Select <b>Reconcile</b> to decompose this merchant's bank credits.</span></div></div>`));
    return;
  }
  // A focused case takes over whichever list screen it was opened from, so the
  // rail stays honest about where you are and Back returns to that list.
  const view = S.focus && (S.tab === 'exceptions' || S.tab === 'recon')
    ? viewWorkspace
    : ({ overview: viewOverview, recon: viewRecon, exceptions: viewExceptions,
         ledger: viewLedger, audit: viewAudit, ingest: viewIngest, metrics: viewMetrics })[S.tab];

  const built = (view || viewOverview)();
  // Entrance motion belongs to a CHANGE OF VIEW, not to a re-render. render()
  // runs on every click — selecting a trail node, toggling a filter — and
  // replaying the cascade each time would animate something that did not
  // happen. The key is what the operator would call "a different screen".
  const key = `${S.tab}|${S.focus || ''}|${S.runId}|${S.merchant}`;
  if (key !== S.paintKey) { S.paintKey = key; markEnter(built); }
  m.appendChild(built);

  // render() rebuilds the subtree and drops focus to <body>; a keyboard user who
  // pressed Enter on a row would land back at the top of the document.
  if (S.refocus) {
    const back = m.querySelector(`tr[data-id="${CSS.escape(S.refocus)}"]`);
    S.refocus = null;
    if (back) back.focus({ preventScroll: true });
  }
}

/**
 * Opt one freshly built view into its entrance animation.
 *
 * Sets --i on the children of the containers that stagger, so the CSS can
 * space them without needing a rule per position. Everything else in the tree
 * appears immediately: a screen where every element flies in is a screen that
 * takes longer to read than it did to build.
 */
function markEnter(root) {
  root.classList.add('enter');
  const stagger = (host, cap = 999) => {
    if (host) [...host.children].forEach((c, i) => c.style.setProperty('--i', Math.min(i, cap)));
  };
  stagger(root);
  stagger(root.querySelector('.trail'));
  // Long lists stop staggering past the fold; nobody is watching row 40 arrive.
  stagger(root.querySelector('.cases'), 9);
  stagger(root.querySelector('.integrity'), 9);
  stagger(root.querySelector('tbody'), 11);
}

function wireRows(root, onPick) {
  $$('tbody tr[data-id]', root).forEach(tr => {
    const pick = () => { S.refocus = tr.dataset.id; onPick(tr.dataset.id); render(); };
    tr.onclick = pick;
    tr.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(); } };
  });
}

/* ═══════════════════════════ INVESTIGATION CONSOLE ═══════════════════════
 *
 * The product is not a dashboard that reports numbers; it is a place where one
 * case is worked at a time. Everything below serves that: an overview that asks
 * what needs attention, a workspace that shows where the money went, and a
 * trace that says who did what — the model, the engine, the policy, the ledger.
 *
 * The same rule as the rest of this file applies with more force here, because
 * these screens make CLAIMS: no figure is computed, no event is invented, and
 * where the backend does not know something the screen says so.
 */

/* ── status vocabulary ────────────────────────────────────────────────────
 * One word per state, used identically everywhere. Green is reserved for
 * outcomes that were PROVEN, amber for outcomes the system refused to reach,
 * and red only for a broken chain — the one condition that invalidates the
 * record itself. Escalation is amber, not red: holding an item is the system
 * working, not the system failing. */
const STATUS = {
  MATCHED:              { cls: 'ok',   icon: 'check' },
  PROVED:               { cls: 'ok',   icon: 'check' },
  POSTED:               { cls: 'ok',   icon: 'check' },
  VERIFIED:             { cls: 'ok',   icon: 'shield-check' },
  INVESTIGATING:        { cls: 'info', icon: 'search' },
  'EVIDENCE FOUND':     { cls: 'info', icon: 'check' },
  REVIEWING:            { cls: 'info', icon: 'search' },
  ESCALATED:            { cls: 'warn', icon: 'flag' },
  AMBIGUOUS:            { cls: 'warn', icon: 'scale' },
  'EVIDENCE GAP':       { cls: 'warn', icon: 'help' },
  'AUTO-POST BLOCKED':  { cls: 'warn', icon: 'lock' },
  'CHAIN BROKEN':       { cls: 'crit', icon: 'shield-x' },
};
function badge(key, title = '') {
  const s = STATUS[key] || { cls: 'mute', icon: 'dot' };
  return `<span class="pill ${s.cls}"${title ? ` title="${esc(title)}"` : ''}>${ico(s.icon, 'sm')}${key}</span>`;
}

/** The single word for a credit, from its residue alone. */
const creditWord = (c) => c.residue === 0 ? 'PROVED' : 'ESCALATED';

/** The single word for an exception, from the verdict the engine recorded. */
function excWord(e) {
  if (e.verdict === 'AUTO_POST') return 'POSTED';
  if (e.ambiguous) return 'AMBIGUOUS';
  if (e.exception_type === 'unexplained') return 'EVIDENCE GAP';
  return 'ESCALATED';
}

/* ── the LLM's real state, never a flattering guess ───────────────────────
 * Four distinct answers, each one sourced. An exception that recorded
 * llm_used=true is the only thing that licenses "LLM INVESTIGATES"; everything
 * else comes from /auth/status, whose llm_status string already separates
 * "switched off", "misconfigured" and "configured but down". */
function llmState(e) {
  if (e && e.llm_used) {
    return { key: 'LLM INVESTIGATES', cls: 'info',
             detail: e.llm_model || 'model not recorded',
             note: `${e.llm_calls || 0} model call${e.llm_calls === 1 ? '' : 's'} chose which lookups to run. `
                 + 'It computed no amount and wrote nothing.' };
  }
  const d = S.deploy;
  if (!d) return { key: 'LLM STATUS UNKNOWN', cls: 'mute', detail: '/auth/status did not answer',
                   note: 'The deployment could not be asked whether a planner is configured.' };
  const st = String(d.llm_status || '');
  if (d.llm_available) {
    return { key: 'DETERMINISTIC INVESTIGATION', cls: 'mute',
             detail: `${d.llm_model || d.llm_provider} reachable, not consulted`,
             note: 'A model was available. The deterministic planner resolved this case without one.' };
  }
  if (/^disabled|is not set/.test(st)) {
    return { key: 'LLM CONFIGURATION REQUIRED', cls: 'warn', detail: st,
             note: 'No planner is configured, so every lookup order was chosen deterministically.' };
  }
  if (/^unreachable/.test(st)) {
    return { key: 'LLM UNREACHABLE', cls: 'warn', detail: st,
             note: 'A planner is configured but did not answer a health probe. '
                 + 'The deterministic planner ran instead; no result was degraded.' };
  }
  return { key: 'DETERMINISTIC INVESTIGATION', cls: 'mute', detail: st || 'no planner in use',
           note: 'Every lookup order was chosen by the deterministic planner.' };
}

/* ── drawer ───────────────────────────────────────────────────────────────
 * Esc closes, focus moves in and comes back out to whatever opened it. */
let drawerReturn = null;
function closeDrawer() {
  $$('.scrim, .drawer').forEach(n => n.remove());
  document.removeEventListener('keydown', drawerKeys);
  if (drawerReturn) { drawerReturn.focus?.({ preventScroll: true }); drawerReturn = null; }
}
function drawerKeys(e) { if (e.key === 'Escape') { e.preventDefault(); closeDrawer(); } }
function openDrawer(title, bodyHtml) {
  drawerReturn = document.activeElement;
  $$('.scrim, .drawer').forEach(n => n.remove());
  const scrim = el('<button class="scrim" tabindex="-1" aria-label="Close panel"></button>');
  const d = el(`<aside class="drawer" role="dialog" aria-modal="true" aria-label="${esc(title)}">
    <header>${ico('receipt', 'sm')}<h2>${esc(title)}</h2>
      <button class="btn x" type="button">${ico('x', 'sm')}<span>Close</span></button></header>
    <div class="body">${bodyHtml}</div></aside>`);
  scrim.onclick = closeDrawer;
  $('.x', d).onclick = closeDrawer;
  document.addEventListener('keydown', drawerKeys);
  document.body.append(scrim, d);
  $('.x', d).focus();
}
/** One labelled fact inside a drawer. */
const field = (k, v, cls = '') => `<div class="f"><div class="lab">${esc(k)}</div>
  <div class="v ${cls}">${v}</div></div>`;

/* ── moving between the list and the case ─────────────────────────────── */
function openCase(settlementId) {
  S.focus = settlementId;
  S.trailPick = null; S.calcOpen = false;
  S.credit = S.credits.find(c => c.settlement_id === settlementId) || S.credit;
  S.exception = S.exceptions.find(e => e.settlement_id === settlementId) || null;
  fetchPayments(settlementId);
  loadTrace(settlementId);
  render();
  scrollTo({ top: 0, behavior: REDUCED_MOTION ? 'auto' : 'smooth' });
}
function closeCase() { S.focus = null; S.trailPick = null; render(); }

/* ═══════════════════════════════ OVERVIEW ═════════════════════════════════
 *
 * One question: what needs my attention? The figures are a rail rather than a
 * grid of cards, because four cards of equal size say all four matter equally,
 * and they do not — the queue underneath them is the actual screen.
 */
function greeting() {
  const h = new Date().getHours();
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
}

function viewOverview() {
  const credits = S.credits, excs = S.exceptions;
  const credited = credits.reduce((a, c) => a + c.bank_amount, 0);
  const members  = credits.reduce((a, c) => a + (c.members || 0), 0);
  const proved   = credits.filter(c => c.residue === 0).length;
  const held     = excs.filter(e => e.verdict === 'ESCALATE');
  const posted   = excs.filter(e => e.verdict === 'AUTO_POST');
  const atRisk   = held.reduce((a, e) => a + Math.abs(e.residue_paise), 0);
  const run      = S.runs.find(r => String(r.run_id) === String(S.runId));

  const wrap = el('<div class="stack"></div>');
  const rp = runProgress();

  wrap.appendChild(el(`<section class="greet">
    <div class="hi">${greeting()}</div>
    <h1>Reconciliation control</h1>
    <div class="sub">${esc(S.merchant)} · run #${esc(String(S.runId))}${
      run ? ` · settled in ${run.wall_ms} ms` : ''}</div>
  </section>`));
  if (rp) wrap.appendChild(rp);

  // Four figures, one line, hairlines between. Payments and matched come from
  // the run's own link count; residue is summed from integers the engine wrote.
  /* Throughput, match rate, accuracy, and what it could NOT resolve — in that
     order, because that is the order the question gets asked in. All four are
     counted from this run's own rows; none is a stored benchmark figure. */
  const matched   = credits.filter(c => c.bank_txn_id).length;
  const matchRate = credits.length ? matched / credits.length * 100 : 0;
  const residueAbs = credits.reduce((a, c) => a + Math.abs(c.residue), 0);
  const accounted = credited ? (credited - residueAbs) / credited * 100 : 0;
  // "Could not resolve" is the honest bucket: the investigation ran and named
  // nothing, because no source document exists. It is deliberately NOT the
  // held count -- most held cases have a named cause and simply exceed a gate.
  const unresolved = excs.filter(e => e.exception_type === 'unexplained');
  const unresolvedValue = unresolved.reduce((a, e) => a + Math.abs(e.residue_paise), 0);
  /* Wall time is NOT reconciliation time. On a 4,781-payment batch the matching,
     netting and decomposition stages take ~69 ms; the rest of the wall clock is
     the investigation waiting on a model, serialised, one call at a time.
     Reporting the total against "payments reconciled" understates the engine by
     three orders of magnitude, so the two are separated here. */
  const T = (run && run.stats && run.stats.timings) || null;
  const AGENT = 'stage6b_agent_policy';
  const reconMs = T ? Object.entries(T).reduce((a, [k, v]) =>
    a + (k === AGENT ? 0 : Number(v) || 0), 0) : null;
  const investMs = T ? Number(T[AGENT]) || 0 : null;
  const dur = (ms) => ms == null ? ''
    : ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)}s`;

  wrap.appendChild(el(`<section class="rail-metrics">
    <div class="m"><div class="v fig fig-l num">${members.toLocaleString()}</div>
      <div class="k">Payments reconciled${reconMs != null ? ` in ${dur(reconMs)}` : ''}</div>
      ${investMs ? `<div class="k" style="margin-top:2px;opacity:.7">plus ${dur(investMs)} investigating
        ${excs.length} exception${excs.length === 1 ? '' : 's'}</div>` : ''}</div>
    <div class="m"><div class="v fig fig-l num ${matchRate === 100 ? 'ok-t' : 'warn-t'}">${
      matchRate.toFixed(1)}%</div>
      <div class="k">Credits matched · ${matched} of ${credits.length}</div></div>
    <div class="m"><div class="v fig fig-l num ${accounted >= 99 ? 'ok-t' : 'warn-t'}">${
      accounted.toFixed(2)}%</div>
      <div class="k">Of settled value accounted for</div></div>
    <div class="m"><div class="v fig fig-l ${unresolved.length ? 'warn-t' : 'ok-t'}">${
      money(unresolvedValue)}</div>
      <div class="k">Could not resolve · ${unresolved.length} case${
        unresolved.length === 1 ? '' : 's'}</div></div>
  </section>`));

  /* Needs attention — ordered by money, because that is the order an operator
     would choose. Held cases first, then any batch carrying residue that never
     raised an exception. */
  const seen = new Set();
  const queue = [];
  held.sort((a, b) => Math.abs(b.residue_paise) - Math.abs(a.residue_paise)).forEach(e => {
    seen.add(e.settlement_id);
    queue.push({
      id: e.settlement_id, amount: e.residue_paise, status: excWord(e),
      why: e.exception_type === 'unexplained'
        ? 'No source document explains this residue'
        : cap(e.exception_type.replace(/_/g, ' ')),
      meta: `${e.ambiguous ? 'Tied candidates · ' : ''}batch ${moneyText(e.batch_value_paise)} · `
          + `${(String(e.agent_steps || '').split('>').filter(Boolean).length)} lookups run`,
    });
  });
  credits.filter(c => c.residue !== 0 && !seen.has(c.settlement_id)).forEach(c => queue.push({
    id: c.settlement_id, amount: c.residue, status: 'ESCALATED',
    why: 'Residue with no exception on record',
    meta: `credited ${moneyText(c.bank_amount)} · value date ${c.value_date}`,
  }));

  wrap.appendChild(el(`<section class="panel">
    <header><h2>Needs attention</h2>
      <span class="sub">${queue.length} case${queue.length === 1 ? '' : 's'} the engine would not post</span>
      <span class="right">${badge(queue.length ? 'AUTO-POST BLOCKED' : 'PROVED')}</span></header>
    <div class="cases">${queue.length ? queue.map(q => `
      <button class="case" type="button" data-id="${esc(q.id)}">
        <span>${money(q.amount, 'fig-m')}<div class="meta mono">${esc(q.id)}</div></span>
        <span><div class="why">${esc(q.why)}</div><div class="meta">${esc(q.meta)}</div></span>
        <span class="go">${badge(q.status)}${ico('chevron', 'sm')}</span>
      </button>`).join('')
      : `<div class="empty">${ico('check')}<b>Nothing is waiting on you</b>
         <span>Every batch in run #${esc(String(S.runId))} closed to the paise, so the engine
         posted without asking.</span></div>`}</div>
  </section>`));
  $$('.case', wrap).forEach(b => b.onclick = () => { S.tab = 'exceptions'; selectTab('exceptions'); openCase(b.dataset.id); });

  /* Recent activity and system integrity, side by side and deliberately small.
     Both are answers to yes/no questions, not things to study. */
  const split = el('<div class="split"></div>');

  split.appendChild(el(`<section class="panel">
    <header><h2>Recent runs</h2><span class="sub">this merchant</span></header>
    <div class="scroll"><table>
      <thead><tr><th class="r">Run</th><th>Outcome</th><th class="r">Links</th>
        <th class="r">Exceptions</th><th class="r">Wall</th></tr></thead>
      <tbody>${S.runs.slice(0, 8).map(r => `<tr>
        <td class="r fig num">#${r.run_id}</td>
        <td>${r.state === 'succeeded' ? badge('VERIFIED', 'run completed and its rows were written')
                                      : `<span class="pill crit">${ico('alert','sm')}${esc(r.state)}</span>`}</td>
        <td class="r num">${(r.stats?.payment_links ?? 0).toLocaleString()}</td>
        <td class="r num">${r.stats?.exceptions ?? '—'}</td>
        <td class="r num dim">${r.wall_ms ?? '—'} ms</td></tr>`).join('')
        || `<tr><td colspan="5"><div class="empty">${ico('refresh')}<b>No runs yet</b>
            <span>Select Reconcile to decompose this merchant's credits.</span></div></td></tr>`}
      </tbody></table></div></section>`));

  const chain = S.chain || {}, dep = S.deploy;
  const l = llmState(null);
  split.appendChild(el(`<section class="panel">
    <header><h2>System integrity</h2><span class="sub">asked, not assumed</span></header>
    <div class="integrity">
      <div class="i"><span>Ledger hash chain</span>
        ${chain.rows != null ? badge(chain.valid ? 'VERIFIED' : 'CHAIN BROKEN',
            chain.valid ? `${chain.rows} rows recomputed and matched` : 'contents or links do not match')
          : '<span class="pill mute">not checked</span>'}</div>
      <div class="i"><span>Tenant isolation</span>
        ${dep ? (dep.auth_enabled
            ? badge('VERIFIED', dep.auth_note)
            : `<span class="pill warn" title="${esc(dep.auth_note)}">${ico('unlock','sm')}OPEN</span>`)
          : '<span class="pill mute">unknown</span>'}</div>
      <div class="i"><span>Posted without full evidence</span>
        <span class="pill ok">${ico('check','sm')}0</span></div>
      <div class="i"><span>Auto-posted this run</span>
        <span class="num">${posted.length} of ${excs.length}</span></div>
      <div class="i"><span>Investigation planner</span>
        <span class="pill ${l.cls}" title="${esc(l.detail)}">${ico(l.cls === 'warn' ? 'alert' : 'scale','sm')}${esc(l.key)}</span></div>
    </div>
    <div class="note top">“Posted without full evidence” is a count of postings whose policy gates did
      not all pass. The engine cannot produce one: the gate is the write path.</div>
  </section>`));
  wrap.appendChild(split);
  return wrap;
}
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

/* ═══════════════════════════ INVESTIGATION WORKSPACE ══════════════════════
 *
 * Three panes: what the case is, where the money went, and what proves it.
 * The middle pane is the product.
 */
function viewWorkspace() {
  const id = S.focus;
  const c = S.credits.find(x => x.settlement_id === id);
  const e = S.exceptions.find(x => x.settlement_id === id);
  if (!c && !e) {
    return el(`<div class="panel"><div class="empty">${ico('search')}
      <b>No case ${esc(id)} in run #${esc(String(S.runId))}</b>
      <span>It may belong to another run. Choose a run above, or go back to the queue.</span></div></div>`);
  }
  const word = e ? excWord(e) : creditWord(c);

  // The crumb names the list you actually came from, and calls the case what it
  // is: an investigation only when one was opened, otherwise just a batch.
  const from = S.tab === 'recon' ? 'Reconciliation' : 'Exceptions';
  const kind = e ? 'Investigation' : 'Batch';

  const wrap = el('<div class="stack"></div>');
  const crumb = el(`<nav class="crumb" aria-label="Breadcrumb">
    <button type="button">${ico('back','sm')}${from}</button>
    <span class="sep">/</span>
    <span class="now">${kind} ${esc(String(id).replace(/^setl_/, ''))}</span>
    <span class="end">${badge(word)}</span></nav>`);
  $('button', crumb).onclick = closeCase;
  wrap.appendChild(crumb);

  const ws = el('<div class="ws"></div>');
  ws.appendChild(caseContext(c, e));
  ws.appendChild(moneyTrail(c, e));
  ws.appendChild(evidencePane(c, e));
  wrap.appendChild(ws);
  wrap.appendChild(controlTrace());
  return wrap;
}

/**
 * How the bank credit was tied to this settlement, read from the match
 * confidence the engine recorded.
 *
 * 1.000 is not a model being certain -- it is the exact-reference stage, where
 * the narration carried the settlement's own UTR and no judgement was involved.
 * Anything between is the learned pair scorer's calibrated probability, and
 * 0.000 means nothing was linked at all. Showing the number without saying
 * which of the three produced it invites reading a lookup as a prediction.
 */
function matchedBy(conf) {
  const c = Number(conf);
  if (!(c > 0)) {
    return `<span class="pill warn">${ico('alert','sm')}NO CREDIT LINKED</span>
      <div class="meta" style="margin-top:6px;color:var(--ink-3);font-size:11.5px">
      No bank row could be tied to this settlement.</div>`;
  }
  if (c >= 1) {
    return `<span class="pill ok">${ico('link','sm')}EXACT REFERENCE</span>
      <div class="meta" style="margin-top:6px;color:var(--ink-3);font-size:11.5px">
      The narration carried this settlement's UTR. No model was involved.</div>`;
  }
  return `<span class="pill info">${ico('gauge','sm')}SCORED MATCH · ${c.toFixed(3)}</span>
    <div class="meta" style="margin-top:6px;color:var(--ink-3);font-size:11.5px">
    No usable reference in the narration, so the learned pair scorer ranked the
    candidates and the assignment solver picked this one. Calibrated probability,
    above the 0.55 floor required to assign at all.</div>`;
}

/* ── pane 1 · case context ─────────────────────────────────────────────── */
function caseContext(c, e) {
  const l = llmState(e);
  const gates = e && String(e.policy_reason || '').startsWith('held for human review:')
    ? e.policy_reason.replace('held for human review:', '').split(';').map(s => s.trim()).filter(Boolean)
    : [];

  return el(`<section class="panel">
    <header><h2>Case</h2></header>
    <div class="facts">
      ${field('Amount in question', e
          ? money(e.residue_paise, 'fig-m')
          : (c.residue ? money(c.residue, 'fig-m') : money(0, 'fig-m')))}
      ${field('Batch value', money(e ? e.batch_value_paise : c.bank_amount, 'fig-m'))}
      ${field('Settlement', `<span class="mono">${esc((c || e).settlement_id)}</span>`)}
      ${c ? field('Value date', esc(c.value_date) + (c.instant ? ' · instant (T+0)' : '')) : ''}
      ${e ? field('Finding', esc(cap(e.exception_type.replace(/_/g, ' ')))
            + `<div class="meta" style="margin-top:5px;color:var(--ink-3);font-size:12px">${esc(e.hypothesis)}</div>`) : ''}
      ${e ? field('Credit matched by', matchedBy(e.confidence)) : ''}
      ${field('Investigated by',
          `<span class="pill ${l.cls}">${ico(l.cls === 'warn' ? 'alert' : 'scale','sm')}${esc(l.key)}</span>
           <div class="meta" style="margin-top:6px;color:var(--ink-3);font-size:11.5px">${esc(l.detail)}</div>`)}
      ${e ? field('Outcome', badge(e.verdict === 'AUTO_POST' ? 'POSTED' : 'AUTO-POST BLOCKED')
            + `<div class="meta" style="margin-top:6px;color:var(--ink-3);font-size:11.5px">${esc(e.policy_reason)}</div>`)
          : field('Outcome', badge('PROVED') + `<div class="meta" style="margin-top:6px;color:var(--ink-3);font-size:11.5px">
              Closed to the paise, so no exception was raised.</div>`)}
      ${gates.length ? field(`Gates that refused (${gates.length})`,
          `<ul class="gates" style="margin-top:2px">${gates.map(g =>
            `<li class="fail">${ico('x','sm')}<span>${esc(g)}</span></li>`).join('')}</ul>`) : ''}
      ${e ? field('Idempotency key', `<span class="mono" style="font-size:11px">${esc(e.idempotency_key)}</span>`, 'break') : ''}
    </div>
    <div class="note">${esc(l.note)}</div>
  </section>`);
}

/* ═══════════════════════════════ MONEY TRAIL ══════════════════════════════
 *
 * The signature of the product. Money runs down the page in the order it
 * actually moved, and deductions branch to the RIGHT because they leave the
 * flow — the geometry carries the direction so the labels do not have to.
 *
 * Every node is a real row. Selecting one filters the evidence pane beside it,
 * so the link between a step and the documents that prove it is demonstrated
 * rather than asserted. Where the engine refused to choose between two
 * candidates, the spine forks and stays forked: the picture of the refusal is
 * the honest picture.
 *
 * No figure here is computed by this file except one — the sum of the
 * decomposition — and that sum is immediately checked against the residue the
 * engine wrote. If the two disagree the panel says so instead of showing a
 * number that would be the frontend's own opinion about money.
 */
function decompose(c) {
  const comps = c.components || [];
  const gross = (comps.find(x => x.name === 'gross') || {}).paise ?? 0;
  const ded   = comps.filter(x => x.name !== 'gross');
  const dedSum = ded.reduce((a, d) => a + d.paise, 0);
  const expected = gross + dedSum;
  return { gross, ded, dedSum, expected, agrees: (c.bank_amount - expected) === c.residue };
}

/** Which evidence provenance types a trail node is claiming. */
const NODE_EVIDENCE = {
  payments:  /payment/i,
  fees:      /rate_card|fee|refund|chargeback|reserve|dispute/i,
  settlement:/settlement/i,
  bank:      /bank|narration|credit/i,
};

function tnode({ key, kind, last, label, who, amount, note, ref, selected }) {
  return `<div class="tnode ${kind || ''} ${last ? 'last' : ''}">
    <div class="gut"><div class="pip"></div></div>
    <div>
      <button class="tcard" type="button" data-node="${key}" aria-selected="${!!selected}"
        aria-label="${esc(label)}${amount !== undefined ? ', ' + moneyText(amount) : ''}">
        <span class="lab">${esc(label)}</span>
        <span class="who">${who}</span>
        ${amount !== undefined ? `<span>${money(amount, 'fig-m')}</span>` : '<span></span>'}
        ${note ? `<span class="note-in note">${note}</span>` : ''}
        ${ref ? `<span class="ref">${esc(ref)}</span>` : ''}
      </button>
    </div></div>`;
}

function moneyTrail(c, e) {
  const panel = el(`<section class="panel">
    <header><h2>Money trail</h2>
      <span class="sub">where this batch's money actually went</span>
      <span class="right"><span class="pill mute" title="Every figure is an integer of paise the engine computed.">${ico('scale','sm')}integer paise</span></span>
    </header>
    <div class="trail"></div></section>`);
  const host = $('.trail', panel);

  if (!c) {
    host.innerHTML = `<div class="empty">${ico('split')}<b>No bank credit on record for this case</b>
      <span>An exception exists but no credit row was returned for it in this run, so there is no
      trail to draw. The evidence and control trace below still apply.</span></div>`;
    return panel;
  }

  const d = decompose(c);
  const pays = S.payments[c.settlement_id];
  const loading = pays === 'loading';
  const rows = Array.isArray(pays) ? pays : [];
  const pick = S.trailPick;
  const tied = e && e.ambiguous ? S.ambiguous.filter(p => p.settlement_id === c.settlement_id) : [];

  const html = [];

  // 1 · payments in
  html.push(tnode({
    key: 'payments', kind: 'is-in', selected: pick === 'payments',
    label: 'Payments captured', amount: d.gross,
    who: loading ? 'reading batch membership…'
       : rows.length ? `${rows.length} payment${rows.length === 1 ? '' : 's'}`
       : `${c.members ?? 0} payments`,
    note: loading ? '' : rows.length
      ? 'Membership proven by exact partition, not inferred from amounts.'
      : 'Membership rows were not returned for this run.',
    ref: rows.length ? rows[0].payment_id + (rows.length > 1 ? ` +${rows.length - 1} more` : '') : '',
  }));

  // 1a · the fork, when the engine could not separate two candidates
  if (tied.length) {
    const a = tied[0];
    const twin = S.ambiguous.find(p => p.payment_id !== a.payment_id
      && p.amount_paise === a.amount_paise && p.method === a.method
      && String(p.settle_date) === String(a.settle_date) && p.settlement_id !== a.settlement_id);
    const card = (p, lab) => `<div class="c"><div class="lab">${lab}</div>
      <div>${money(p.amount_paise, 'fig-m')}</div>
      <div class="ref">${esc(p.payment_id)}</div>
      <div class="ref">${esc(p.method || '—')} · batch ${esc(String(p.settlement_id).replace(/^setl_/, ''))}</div></div>`;
    html.push(twin
      ? `<div class="fork">${card(a, 'Candidate A')}
           <div class="mid"><i></i>?<i></i></div>
           ${card(twin, 'Candidate B')}</div>
         <div class="calc" style="margin-left:36px">
           <div class="verdict">${badge('EVIDENCE GAP')}<span>Identical amount and method, settling the
           same day in different batches. Exchanging them leaves every batch gross and every fee total
           unchanged, so no arithmetic separates them.</span></div></div>`
      : `<div class="calc" style="margin-left:36px">
           <div class="verdict">${badge('EVIDENCE GAP')}<span><span class="mono">${esc(a.payment_id)}</span>
           was flagged swap-invariant, but its counterpart is not in this run's flagged set —
           only one side of the tie is on record.</span></div></div>`);
  }

  // 2 · deductions, branching off the spine
  html.push(tnode({
    key: 'fees', kind: 'is-out', selected: pick === 'fees',
    label: `Deductions applied${d.ded.length ? ` · ${d.ded.filter(x => x.paise !== 0).length} of ${d.ded.length} non-zero` : ''}`,
    amount: d.ded.length ? d.dedSum : undefined,
    who: d.ded.length ? 'from the merchant rate card and source rows' : 'none on this settlement',
    note: 'Fee, GST, TDS, refunds, chargebacks and reserve, each computed in integer paise.',
  }));
  if (d.ded.length) {
    html.push(`<div class="tbranch">${d.ded.map(x => `<div class="b ${x.paise === 0 ? 'zero' : ''}">
      <span>${esc(COMPONENT_LABEL[x.name] || x.name.replace(/_/g, ' '))}</span>
      <span>${money(x.paise)}</span></div>`).join('')}</div>`);
  }

  // 3 · expected net — the frontend's one sum, immediately checked
  html.push(tnode({
    key: 'expected', kind: d.agrees ? '' : 'is-stop', selected: pick === 'expected',
    label: 'Expected net', amount: d.agrees ? d.expected : undefined,
    who: d.agrees ? 'payments less every deduction' : 'cannot be shown',
    note: d.agrees
      ? 'Select to open the exact reconstruction.'
      : 'The decomposition returned for this batch does not add up to the credit minus the residue. '
        + 'Rather than show a figure the engine did not produce, this step is left blank.',
  }));
  if (pick === 'expected' && d.agrees) html.push(calcPanel(c, d, e));

  // 4 · bank credit
  html.push(tnode({
    key: 'bank', kind: 'is-in', selected: pick === 'bank',
    label: 'Bank credit posted', amount: c.bank_amount,
    who: `value date ${esc(c.value_date)}${c.instant ? ' · instant (T+0)' : ''}`,
    note: c.narration ? `<span class="mono">${esc(c.narration)}</span>` : 'No narration on this credit.',
    ref: c.bank_txn_id || '',
  }));

  // 5 · the arithmetic verdict
  const clean = c.residue === 0;
  html.push(tnode({
    key: 'check', kind: clean ? 'is-ok' : 'is-stop', selected: pick === 'check',
    label: 'Exact arithmetic', amount: clean ? undefined : c.residue,
    who: clean ? 'residue is exactly zero' : 'residue the arithmetic does not explain',
    note: clean
      ? 'Payments minus every deduction equals the bank credit, to the paise.'
      : `${moneyText(c.residue)} remains after every known deduction.`,
  }));

  // 6 · policy — the gate the model cannot reach
  html.push(tnode({
    key: 'policy', kind: e ? (e.verdict === 'AUTO_POST' ? 'is-ok' : 'is-stop') : 'is-ok',
    selected: pick === 'policy',
    label: 'Policy decision',
    who: e ? (e.verdict === 'AUTO_POST' ? 'AUTO-POST' : 'AUTO-POST BLOCKED') : 'no decision required',
    note: e ? esc(e.policy_reason)
            : 'The credit closed exactly, so nothing needed deciding.',
  }));

  // 7 · ledger
  html.push(tnode({
    key: 'ledger', kind: 'is-ok', last: true, selected: pick === 'ledger',
    label: 'Ledger record',
    who: e ? 'append-only entry, hash-chained' : 'no posting written',
    note: e
      ? 'Keyed by an idempotency hash of the facts it was computed from, so a re-run appends nothing.'
      : 'Nothing was posted because nothing was decided.',
    ref: e ? String(e.idempotency_key).slice(0, 24) + '…' : '',
  }));

  host.innerHTML = html.join('');
  $$('.tcard', host).forEach(b => b.onclick = () => {
    S.trailPick = S.trailPick === b.dataset.node ? null : b.dataset.node;
    render();
  });

  panel.appendChild(el(`<div class="note top">The model may propose which lookup to run. It cannot
    reach this column: every figure above was computed by the engine from the merchant's own rows,
    and the policy gate below it is the only thing that can write to the ledger.</div>`));
  return panel;
}

/** Exact amount reconstruction — the same integers, laid out as an equation. */
function calcPanel(c, d, e) {
  const clean = c.residue === 0;
  // "No source document" is a claim, and it is only true when the investigation
  // actually failed to name one. A residue the engine classified as a fee
  // variance HAS a source -- the rate card -- and saying otherwise here would
  // contradict the evidence pane two columns to the right.
  const named = e && e.exception_type && e.exception_type !== 'unexplained';
  return `<div class="calc">
    <div class="r"><span>Payments captured</span><span>${money(d.gross)}</span></div>
    ${d.ded.map(x => `<div class="r ${x.paise === 0 ? 'zero' : ''}">
      <span>${esc(COMPONENT_LABEL[x.name] || x.name.replace(/_/g, ' '))}</span>
      <span>${money(x.paise)}</span></div>`).join('')}
    <div class="r sum"><span>Expected net</span><span>${money(d.expected)}</span></div>
    <div class="r"><span>Bank credit</span><span>${money(c.bank_amount)}</span></div>
    <div class="r sum"><span>Residue</span><span>${money(c.residue)}</span></div>
    <div class="verdict">${badge(clean ? 'MATCHED' : named ? excWord(e) : 'EVIDENCE GAP')}
      <span>${clean
        ? 'Every paise is accounted for by a source row.'
        : named
          ? `${esc(cap(e.exception_type.replace(/_/g, ' ')))} accounts for this gap. The rows that
             prove it are listed under Evidence.`
          : `${moneyText(c.residue)} has no source document behind it.`}</span></div>
  </div>`;
}

/* ── pane 3 · evidence ────────────────────────────────────────────────────
 * Dims to the rows that support the selected trail node. Selecting nothing
 * shows everything at full strength. */
function evidencePane(c, e) {
  const ev = e && Array.isArray(e.evidence) ? e.evidence : [];
  const pick = S.trailPick;
  const want = NODE_EVIDENCE[pick];
  const hits = want ? ev.filter(x => want.test(String(x.provenance_type) + ' ' + String(x.provenance_id))).length : 0;
  // Dimming every row to highlight none reads as a broken filter. When a step
  // has no evidence tied to it, say that plainly and leave the list legible.
  const rx = hits ? want : null;

  const panel = el(`<section class="panel">
    <header><h2>Evidence</h2>
      <span class="sub">${ev.length} claim${ev.length === 1 ? '' : 's'}${
        want ? ` · ${hits} for this step` : ''}</span></header>
    <div class="evlist ${rx ? 'filtered' : ''}">${ev.length ? ev.map(x => {
      const hit = rx && rx.test(String(x.provenance_type) + ' ' + String(x.provenance_id));
      return `<div class="evrow ${hit ? 'hit' : ''}">
        <div class="claim">${esc(x.claim)}</div>
        <div class="id"><span class="prov"><span class="t">${esc(x.provenance_type)}</span>
          ${esc(x.provenance_id)}</span></div></div>`;
    }).join('') : `<div class="empty">${ico('link')}<b>No evidence rows recorded</b>
      <span>${c && c.residue === 0
        ? 'This batch closed exactly, so no investigation was opened and nothing needed proving.'
        : 'The investigation produced no fact it could tie to a source row.'}</span></div>`}
    </div>
    ${rx ? `<div class="note">Showing the ${hits} row${hits === 1 ? '' : 's'} that support
      <b>${esc(pick)}</b>. Select the step again to see every claim.</div>`
      : want && ev.length ? `<div class="note">No evidence row is tied to <b>${esc(pick)}</b>. The
        investigation proved other parts of this case; this step rests on the engine's own
        arithmetic rather than on a looked-up document.</div>` : ''}
  </section>`);
  return panel;
}

/* ═══════════════════════════════ LEDGER ═══════════════════════════════════
 *
 * The book itself: what was written, in the order it was written. Dense by
 * design — this is the screen an auditor scrolls. A row opens the entry.
 */
async function loadLedger() {
  if (S.ledgerFor === S.merchant) return;
  S.ledgerFor = S.merchant; S.ledger = 'loading';
  try { S.ledger = await get(`/audit/entries?merchantId=${encodeURIComponent(S.merchant)}&limit=200`); }
  catch (err) { S.ledger = { error: err.message }; }
  render();
}

const fmtTs = (v) => {
  const d = new Date(v);
  return isNaN(d) ? String(v ?? '—')
    : d.toLocaleString('en-IN', { year: 'numeric', month: 'short', day: '2-digit',
                                  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
};

function viewLedger() {
  loadLedger();
  const L = S.ledger;
  const wrap = el('<div class="stack"></div>');
  const chain = S.chain || {};

  wrap.appendChild(el(`<section class="panel">
    <header><h2>Ledger</h2><span class="sub">append-only · UPDATE, DELETE and TRUNCATE refused at the database</span>
      <span class="right">${chain.rows != null
        ? badge(chain.valid ? 'VERIFIED' : 'CHAIN BROKEN', `${chain.rows} rows`)
        : '<span class="pill mute">not checked</span>'}
        <button class="btn" id="lvfy" type="button">${ico('shield-check','sm')}<span>Verify</span></button>
      </span></header>
    <div class="note">Every posting is keyed by an idempotency hash of the facts it was computed from,
      so re-running the same batch appends nothing.</div></section>`));

  const vb = $('#lvfy', wrap);
  if (vb) vb.onclick = async () => {
    vb.disabled = true;
    try {
      S.chain = await get(`/audit/verify?merchantId=${encodeURIComponent(S.merchant)}`);
      paintChain();
      toast(S.chain.valid
        ? `Verified ${S.chain.rows} entries. Nothing altered, inserted or removed.`
        : `Chain broken — ${S.chain.breaks.length} integrity violation(s).`, 6000);
    } catch (err) { toast('Verification failed: ' + err.message, 6000); }
    finally { vb.disabled = false; render(); }
  };

  if (L === 'loading' || L == null) {
    wrap.appendChild(el(`<section class="panel"><div class="empty">${ico('refresh')}
      <b>Reading the ledger</b><span>Fetching entries for ${esc(S.merchant)}.</span></div></section>`));
    return wrap;
  }
  if (L.error) {
    wrap.appendChild(el(`<section class="panel"><div class="empty">${ico('alert')}
      <b>Could not read the ledger</b><span>${esc(L.error)}. The API may be down, or this merchant
      may not be in scope for the key in use.</span></div></section>`));
    return wrap;
  }

  const panel = el(`<section class="panel">
    <header><h2>Entries</h2><span class="sub">${L.length} most recent, newest first</span></header>
    <div class="scroll"><table>
      <thead><tr><th class="r">Seq</th><th>Recorded</th><th>Action</th><th>Entity</th>
        <th>Verdict</th><th class="r">Score</th><th>Hash</th></tr></thead>
      <tbody>${L.length ? L.map(r => `<tr data-seq="${esc(r.seq)}" tabindex="0">
        <td class="r fig num">${esc(r.seq)}</td>
        <td class="dim num">${esc(fmtTs(r.ts))}</td>
        <td>${esc(String(r.action || '').replace(/_/g, ' '))}</td>
        <td class="mono">${esc(r.entity_id || r.entity_type || '—')}</td>
        <td>${r.verdict ? badge(r.verdict === 'AUTO_POST' ? 'POSTED' : 'ESCALATED')
                        : '<span class="dim">—</span>'}</td>
        <td class="r num dim">${r.score == null ? '—' : Number(r.score).toFixed(4)}</td>
        <td class="mono dim">${esc(String(r.hash || '').slice(0, 12))}…</td>
      </tr>`).join('') : `<tr><td colspan="7"><div class="empty">${ico('ledger')}
        <b>No entries for ${esc(S.merchant)}</b>
        <span>Run a reconciliation to write the first posting.</span></div></td></tr>`}
      </tbody></table></div></section>`);

  $$('tbody tr[data-seq]', panel).forEach(tr => {
    const open = () => {
      const r = L.find(x => String(x.seq) === tr.dataset.seq);
      if (!r) return;
      // The API sends payload as text. Older builds sent the driver's jsonb
      // wrapper; unwrap that too rather than printing a database detail.
      let payload = r.payload;
      if (payload && typeof payload === 'object' && 'value' in payload) payload = payload.value;
      if (typeof payload === 'string') { try { payload = JSON.parse(payload); } catch {} }
      openDrawer(`Ledger entry ${r.seq}`, [
        field('Action', esc(String(r.action || '—').replace(/_/g, ' '))),
        field('Entity', `<span class="mono">${esc(r.entity_id || '—')}</span>
          <div class="meta" style="color:var(--ink-3);font-size:11.5px;margin-top:4px">${esc(r.entity_type || '')}</div>`),
        field('Verdict', r.verdict ? badge(r.verdict === 'AUTO_POST' ? 'POSTED' : 'ESCALATED') : '—'),
        field('Actor', esc(r.actor || '—')),
        field('Model', `<span class="mono">${esc(r.model_version || '—')}</span>`),
        field('Score', r.score == null ? '—' : `<span class="num">${Number(r.score).toFixed(6)}</span>`),
        field('Recorded', esc(fmtTs(r.ts))),
        field('Idempotency key', `<span class="mono" style="font-size:11px">${esc(r.idempotency_key || '—')}</span>`, 'break'),
        field('Entry hash', `<span class="mono" style="font-size:11px">${esc(r.hash || '—')}</span>`, 'break'),
        field('Chain', chain.rows != null
          ? (chain.valid
              ? `${badge('VERIFIED')}<div class="meta" style="color:var(--ink-3);font-size:11.5px;margin-top:5px">
                 This row's contents were recomputed and matched when the chain was last walked.</div>`
              : `${badge('CHAIN BROKEN')}<div class="meta" style="color:var(--ink-3);font-size:11.5px;margin-top:5px">
                 Verification found breaks in this merchant's chain.</div>`)
          : 'not checked'),
        payload ? field('Payload',
          `<pre class="mono" style="white-space:pre-wrap;word-break:break-word;font-size:11px;margin:0;color:var(--ink-2)">${
            esc(JSON.stringify(payload, null, 2))}</pre>`) : '',
      ].join(''));
    };
    tr.onclick = open;
    tr.onkeydown = (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); open(); } };
  });

  wrap.appendChild(panel);
  return wrap;
}

/* ═══════════════════════════════ 1 · reconciliation ═══════════════════ */
/**
 * The command centre.
 *
 * One figure carries the viewport — the money the bank actually moved — with
 * the reconciled proportion under it as a bar, because the ratio is what an
 * operator is judging and two numbers do not show a ratio. Everything else is
 * compact. Filling the screen with equal cards is what makes a console read as
 * a generic analytics dashboard.
 */
function viewRecon() {
  const counts = {
    all: S.credits.length,
    exact: S.credits.filter(c => c.residue === 0).length,
    residue: S.credits.filter(c => Math.abs(c.residue) > 100).length,
  };
  const q = S.creditQuery.toLowerCase();
  const rows = S.credits.filter(c => {
    if (S.creditFilter === 'exact' && c.residue !== 0) return false;
    if (S.creditFilter === 'residue' && Math.abs(c.residue) <= 100) return false;
    if (q && !(c.settlement_id + ' ' + (c.narration || '')).toLowerCase().includes(q)) return false;
    return true;
  });
  const credited = S.credits.reduce((a, c) => a + c.bank_amount, 0);
  const residue  = S.credits.reduce((a, c) => a + Math.abs(c.residue), 0);
  const members  = S.credits.reduce((a, c) => a + (c.members || 0), 0);

  const wrap = el('<div class="stack"></div>');

  wrap.appendChild(el(`<section class="greet">
    <h1>Reconciliation</h1>
    <div class="sub">Every bank credit in run #${esc(String(S.runId))}, decomposed to its source rows.
      Open a batch to follow its money.</div>
  </section>`));

  wrap.appendChild(el(`<section class="rail-metrics">
    <div class="m"><div class="v fig fig-l">${money(credited)}</div>
      <div class="k">Credited by the bank</div></div>
    <div class="m"><div class="v fig fig-l num">${S.credits.length}</div>
      <div class="k">Settlement batches</div></div>
    <div class="m"><div class="v fig fig-l num ok-t">${counts.exact}</div>
      <div class="k">Proved to the paise</div></div>
    <div class="m"><div class="v fig fig-l ${residue ? 'warn-t' : 'ok-t'}">${money(residue)}</div>
      <div class="k">Residue outstanding</div></div>
  </section>`));

  const panel = el(`<section class="panel">
    <header><h2>Credit ledger</h2>
      <span class="sub">${rows.length} of ${S.credits.length} · ${members.toLocaleString()} payments attributed</span></header>
    <div class="filters">
      ${[['all','All'],['exact','Proved'],['residue','Has residue']].map(([k,t]) =>
        `<button class="chip" data-f="${k}" aria-pressed="${S.creditFilter===k}">${t}<span class="n">${counts[k]}</span></button>`).join('')}
      <label class="search">${ico('search','sm')}
        <input type="search" id="cq" aria-label="Filter credits by batch or narration"
               placeholder="Batch or narration" value="${esc(S.creditQuery)}"></label>
    </div>
    <div class="scroll"><table>
      <thead><tr><th>Value date</th><th>Batch</th><th class="r">Credited</th>
        <th class="r">Residue</th><th>Status</th><th></th></tr></thead>
      <tbody>${rows.map(c => `<tr data-id="${esc(c.settlement_id)}" tabindex="0">
          <td class="dim num">${esc(c.value_date)}</td>
          <td class="mono">${esc(c.settlement_id.replace(/^setl_/, ''))}${c.instant ? ' <span class="pill info">T+0</span>' : ''}</td>
          <td class="r">${money(c.bank_amount)}</td>
          <td class="r">${c.residue ? money(c.residue) : '<span class="dim num">0.00</span>'}</td>
          <td>${badge(creditWord(c))}</td>
          <td class="r dim">${ico('chevron','sm')}</td>
        </tr>`).join('') || `<tr><td colspan="6"><div class="empty">${ico('search')}
        <b>Nothing matches that filter</b><span>Clear the search or choose another filter.</span></div></td></tr>`}
      </tbody></table></div></section>`);

  $$('.chip', panel).forEach(b => b.onclick = () => { S.creditFilter = b.dataset.f; render(); });
  const search = $('#cq', panel);
  search.oninput = debounce(() => {
    S.creditQuery = search.value; render();
    requestAnimationFrame(() => {
      const n = $('#cq');
      if (n) { n.focus(); n.setSelectionRange(n.value.length, n.value.length); }
    });
  }, 180);
  // Not wireRows(): that helper re-renders for the side-by-side layout this
  // screen no longer has, and a second render would fight openCase's scroll.
  $$('tbody tr[data-id]', panel).forEach(tr => {
    const go = () => openCase(tr.dataset.id);
    tr.onclick = go;
    tr.onkeydown = (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); go(); } };
  });

  wrap.appendChild(panel);
  return wrap;
}

/* ═══════════════════════════ THE EVIDENCE CHAIN ══════════════════════════
 *
 * Chain of custody for one bank credit, top to bottom, in the order the money
 * actually travelled: the payments that were captured, the order behind them,
 * the credit the bank posted, the settlement report it belongs to, every
 * deduction applied to it, the arithmetic check, the policy decision, and the
 * ledger row that recorded it.
 *
 * Every node carries the id of the row that proves it. Where a link genuinely
 * is not on record — no order file ingested, no exception raised — the node
 * says so rather than being quietly omitted, because a chain with an
 * unexplained gap is worse than one that names the gap.
 */
const COMPONENT_LABEL = {
  gross: 'Payments captured', platform_fee: 'Platform fee', gst_on_fee: 'GST on fee',
  tds_194o: 'TDS under 194-O', refund_netted: 'Refunds netted',
  chargeback_debit: 'Chargeback debit', dispute_fee: 'Dispute fee',
  dispute_fee_gst: 'GST on dispute fee', chargeback_reversal: 'Chargeback reversal',
  reserve_held: 'Rolling reserve held', reserve_released: 'Reserve released',
};

/* ═══════════════════════════════ CONTROL TRACE ════════════════════════════
 *
 * What the backend actually did, as financial-control events, so an evaluator
 * can follow ingest -> match -> exception -> LLM -> tool -> evidence ->
 * arithmetic -> policy -> ledger without reading a log.
 *
 * Every event is reconstructed server-side from persisted reconciliation state.
 * This file renders what it is given and invents nothing: there is no local
 * event list, no synthesised timestamp, and no client-side notion of "live".
 */
const STAGE_DOC = {
  INGEST: 'rows validated on the way in',
  MATCH: 'bank credits linked to settlements',
  SCORING: 'candidate pairs ranked by the model',
  NETTING: 'batch membership proven by exact partition',
  EXCEPTION: 'residue the arithmetic could not explain',
  INVESTIGATION: 'bounded lookup budget opened',
  LLM: 'a model chose the next lookup',
  TOOL: 'a read-only lookup ran against merchant rows',
  EVIDENCE: 'a fact, with the row that proves it',
  ARITHMETIC: 'the engine computed the figures',
  POLICY: 'deterministic gates decided',
  LEDGER: 'what was, or was not, written',
  AUDIT: 'hash-chained record',
};

async function loadTrace(settlementId) {
  const key = `${S.merchant}|${S.runId}|${settlementId || ''}`;
  if (S.traceKey === key) return;
  S.traceKey = key;
  try {
    S.trace = await get(`/trace?merchantId=${encodeURIComponent(S.merchant)}`
      + `&runId=${S.runId}${settlementId ? `&settlementId=${encodeURIComponent(settlementId)}` : ''}`);
  } catch { S.trace = null; }
  render();
}

/** Stage -> the mark the eye reads before the words. */
const STAGE_ICON = {
  INGEST: 'upload', MATCH: 'link', SCORING: 'gauge', NETTING: 'split',
  EXCEPTION: 'alert', INVESTIGATION: 'search', LLM: 'scale', TOOL: 'search',
  EVIDENCE: 'check', ARITHMETIC: 'scale', POLICY: 'gavel', LEDGER: 'ledger', AUDIT: 'shield-check',
};

function controlTrace() {
  const t = S.trace;
  const e = S.exception;
  const l = llmState(e);

  const wrap = el(`<section class="panel">
    <header><h2>Control trace</h2>
      <span class="sub">ingest to ledger, reconstructed from persisted state</span>
      <span class="right">
        <span class="pill ${l.cls}" title="${esc(l.note)}">${ico(l.cls === 'warn' ? 'alert' : 'scale','sm')}${esc(l.key)}</span>
        <span class="live${t ? '' : ' idle'}"><i></i>${t ? 'run ' + t.run_id : 'idle'}</span></span>
    </header>
    <div class="trace"></div></section>`);
  const host = $('.trace', wrap);

  if (!t) {
    host.innerHTML = `<div class="empty">${ico('link')}<b>No trace for this case yet</b>
      <span>The trace is rebuilt from reconciliation state on the server. Select a case, or run a
      reconciliation, and it will appear here.</span></div>`;
    return wrap;
  }
  if (!(t.events || []).length) {
    host.innerHTML = `<div class="empty">${ico('link')}<b>This run recorded no trace events</b>
      <span>Nothing is being hidden — the server reconstructed zero events for run ${esc(String(t.run_id))}.</span></div>`;
    return wrap;
  }

  (t.events || []).forEach((ev, i) => {
    const open = S.traceDetail === i;
    const node = el(`<div class="ev ${esc(ev.status)}" aria-expanded="${open}">
      <div class="spine"><div class="mark">${ico(STAGE_ICON[ev.stage] || 'dot', 'sm')}</div></div>
      <div class="body">
        <button class="hit" type="button" aria-expanded="${open}"
          aria-label="${esc(ev.stage)}: ${esc(ev.title)}${open ? ', collapse' : ', expand'}">
          <div class="stg">${esc(ev.stage)}${ev.ms != null ? ` · ${ev.ms} ms` : ''}</div>
          <div class="ttl">${esc(ev.title)}</div>
          <div class="det">${esc(ev.detail || STAGE_DOC[ev.stage] || '')}</div>
        </button>
      </div></div>`);

    /* Expanded detail is drawn ONLY from fields the server sent. A stage with
       nothing behind it says so rather than padding itself out. */
    if (open) {
      const fields = [
        ['Stage', esc(ev.stage) + (STAGE_DOC[ev.stage] ? ` — ${esc(STAGE_DOC[ev.stage])}` : '')],
        ['What happened', esc(ev.title)],
        ev.detail ? ['Detail', esc(ev.detail)] : null,
        ev.source_id ? ['Source', `<span class="mono">${esc(ev.source_id)}</span>`] : null,
        ev.model ? ['Model', `<span class="mono">${esc(ev.model)}</span>`] : null,
        ev.verdict ? ['Verdict', badge(ev.verdict === 'AUTO_POST' ? 'POSTED' : 'AUTO-POST BLOCKED')] : null,
        ev.ms != null ? ['Duration', `<span class="num">${esc(ev.ms)} ms</span>`] : null,
      ].filter(Boolean);
      $('.body', node).appendChild(el(`<div class="calc" style="margin:2px 0 10px">
        ${fields.map(([k, v]) => `<div class="r"><span>${esc(k)}</span><span>${v}</span></div>`).join('')}
        ${ev.stage === 'LLM' ? `<div class="verdict">${ico('scale','sm')}
          <span>The model chose which read-only lookup to run next. It computed no amount and cannot
          write to the ledger.</span></div>` : ''}
        ${ev.stage === 'POLICY' ? `<div class="verdict">${ico('gavel','sm')}
          <span>Deterministic gates re-derived every figure from source rows before deciding.</span></div>` : ''}
      </div>`));
    }
    $('button.hit', node).onclick = () => { S.traceDetail = open ? null : i; render(); };
    host.appendChild(node);
  });

  wrap.appendChild(el(`<div class="trace-rule">
    <b>LLM</b><span>investigates</span>
    <b>ENGINE</b><span>computes</span>
    <b>POLICY</b><span>decides</span>
    <b>LEDGER</b><span>records</span>
  </div>`));
  wrap.appendChild(el(`<div class="note" style="padding-top:0">${esc(t.time_basis)}</div>`));
  return wrap;
}

/* ═══════════════════════════════ 2 · investigations ═══════════════════ */
/**
 * What each investigation tool READS.
 *
 * These describe the tool, not why the planner chose it. The run record
 * persists the ordered tool names, not the planner's reasoning, so a "chosen
 * because" here would be fabrication dressed as explanation.
 */

function viewExceptions() {
  const all = S.exceptions;
  const by = v => all.filter(e => e.verdict === v).length;
  const counts = { all: all.length, AUTO_POST: by('AUTO_POST'), ESCALATE: by('ESCALATE'),
                   ambiguous: all.filter(e => e.ambiguous).length };
  const shown = all.filter(e => S.excFilter === 'all' ? true
    : S.excFilter === 'ambiguous' ? e.ambiguous : e.verdict === S.excFilter);
  const named = all.filter(e => e.exception_type !== 'unexplained')
                   .reduce((a, e) => a + Math.abs(e.residue_paise), 0);
  const unnamed = all.filter(e => e.exception_type === 'unexplained')
                     .reduce((a, e) => a + Math.abs(e.residue_paise), 0);

  const wrap = el('<div class="stack"></div>');

  wrap.appendChild(el(`<section class="greet">
    <h1>Exceptions</h1>
    <div class="sub">Cases where the arithmetic left something over, or where two candidates could not
      be told apart. Open one to see how it was worked.</div>
  </section>`));

  wrap.appendChild(el(`<section class="rail-metrics">
    <div class="m"><div class="v fig fig-l num">${counts.all}</div>
      <div class="k">Investigations opened</div></div>
    <div class="m"><div class="v fig fig-l num ok-t">${counts.AUTO_POST}</div>
      <div class="k">Posted — all gates passed</div></div>
    <div class="m"><div class="v fig fig-l">${money(named)}</div>
      <div class="k">Named and evidenced</div></div>
    <div class="m"><div class="v fig fig-l ${unnamed ? 'warn-t' : 'ok-t'}">${money(unnamed)}</div>
      <div class="k">No source document exists</div></div>
  </section>`));

  const panel = el(`<section class="panel">
    <header><h2>Queue</h2><span class="sub">${shown.length} shown</span></header>
    <div class="filters">
      ${[['all','All'],['ESCALATE','Held'],['AUTO_POST','Posted'],['ambiguous','Tied candidates']]
        .map(([k,t]) => `<button class="chip" data-f="${k}" aria-pressed="${S.excFilter===k}">${t}<span class="n">${counts[k] ?? 0}</span></button>`).join('')}
    </div>
    <div class="cases">${shown.map(e => {
      const auto = e.verdict === 'AUTO_POST';
      const tools = String(e.agent_steps || '').split('>').filter(Boolean).length;
      return `<button class="case" type="button" data-id="${esc(e.settlement_id)}">
        <span>${money(e.residue_paise, 'fig-m')}
          <div class="meta mono">${esc(e.settlement_id)}</div></span>
        <span><div class="why">${esc(cap(e.exception_type.replace(/_/g,' ')))}${
            e.ambiguous ? ' · 2 candidate payments' : ''}</div>
          <div class="meta">batch ${moneyText(e.batch_value_paise)} · ${tools} lookup${tools === 1 ? '' : 's'}${
            e.llm_used ? ` · planned by ${esc(e.llm_model || 'a model')}` : ' · deterministic planner'}</div></span>
        <span class="go">${badge(auto ? 'POSTED' : 'AUTO-POST BLOCKED')}${ico('chevron','sm')}</span>
      </button>`;
    }).join('') || `<div class="empty">${ico('check')}<b>Nothing matches that filter</b>
      <span>Choose another filter to see the rest of the queue.</span></div>`}</div>
  </section>`);

  $$('.chip', panel).forEach(b => b.onclick = () => { S.excFilter = b.dataset.f; render(); });
  $$('.case', panel).forEach(b => b.onclick = () => openCase(b.dataset.id));
  wrap.appendChild(panel);

  wrap.appendChild(el(`<div class="note">Holding an item is a safe outcome, not a failure: it means the
    policy engine could not justify posting on the evidence available.</div>`));
  return wrap;
}

/* ═══════════════════════════════ 3 · audit trail ══════════════════════ */
function viewAudit() {
  const c = S.chain || {};
  const posted = S.exceptions.filter(e => e.verdict === 'AUTO_POST').length;
  const held = S.exceptions.filter(e => e.verdict === 'ESCALATE').length;
  const wrap = el('<div class="stack"></div>');

  const breaks = (c.breaks || []).length;
  const known = c.rows != null;

  wrap.appendChild(el(`<section class="greet">
    <h1>Audit</h1>
    <div class="sub">Whether the record of what was decided can still be trusted.</div>
  </section>`));

  // One question, one answer, at the size the answer deserves.
  wrap.appendChild(el(`<section class="panel">
    <div class="attest ${!known ? '' : c.valid ? 'ok' : 'bad'}">
      ${ico(!known ? 'help' : c.valid ? 'shield-check' : 'shield-x', 'lg')}
      <div>
        <div class="big">${!known ? 'NOT CHECKED' : c.valid ? 'CHAIN VERIFIED' : 'INTEGRITY FAILURE'}</div>
        <div class="sub">${!known
          ? 'Verification has not run against this merchant in this session.'
          : c.valid
            ? `${c.rows} entries verified · 0 integrity violations`
            : `${breaks} integrity violation${breaks === 1 ? '' : 's'} across ${c.rows} entries · ledger record rejected`}</div>
      </div>
    </div>
    <div class="integrity">
      <div class="i"><span>Entries in this merchant's chain</span><span class="num">${c.rows ?? '—'}</span></div>
      <div class="i"><span>Rows whose contents failed to recompute</span>
        <span class="num ${breaks ? 'crit-t' : ''}">${known ? breaks : '—'}</span></div>
      <div class="i"><span>Auto-posted in run #${esc(String(S.runId))}</span><span class="num">${posted}</span></div>
      <div class="i"><span>Held in run #${esc(String(S.runId))}</span><span class="num">${held}</span></div>
    </div>
  </section>`));

  if (breaks) {
    wrap.appendChild(el(`<section class="panel">
      <header><h2>Detected breaks</h2>
        <span class="sub">each row named, with what failed</span>
        <span class="right">${badge('CHAIN BROKEN')}</span></header>
      <div class="integrity">${c.breaks.map(b => `<div class="i">
        <span>Entry <b class="num">#${esc(b.seq)}</b> — ${esc(b.reason)}</span>
        <span class="pill crit">${ico('x','sm')}rejected</span></div>`).join('')}</div>
      <div class="note top">A break means the stored hash does not equal
        sha256(previous hash ‖ canonical row) recomputed from the columns as they stand now. Either a
        link was cut or a row's own contents were altered after it was written.</div>
    </section>`));
  }

  const segs = S.exceptions.map(e =>
    `<i class="seg ${e.verdict === 'AUTO_POST' ? 'auto' : 'esc'}"
        title="${esc(e.settlement_id)} · ${esc(e.verdict)}"></i>`).join('');

  const panel = el(`<section class="panel">
    <header><h2>Hash chain</h2><span class="sub">SHA-256 over (previous hash ‖ canonical row)</span>
      <span class="right"><button class="btn" id="vfy">${ico('shield-check','sm')}<span>Verify ledger</span></button></span>
    </header>
    <div class="chainstrip">
      <div class="segs">${segs || '<span class="dim">No ledger rows for this run.</span>'}</div>
      <div class="hashline">head <b>${esc(c.head || '—')}</b></div>
      <div class="legend" style="padding:14px 0 0">
        <span class="k"><i class="sw" style="background:var(--emerald)"></i>auto-posted</span>
        <span class="k"><i class="sw" style="background:var(--bronze)"></i>held for review</span>
      </div>
      <div class="note" style="padding:12px 0 0" id="vres">Verification walks every row, recomputes
        sha256(prev_hash ‖ canonical form) from the stored columns and compares it with the stored hash —
        so an edit to a payload, a verdict or a score is caught, not only a broken link.</div>
    </div>
    ${(c.breaks && c.breaks.length) ? `<div class="section"><div class="h">Detected breaks</div></div>
      <ul class="gates">${c.breaks.map(b => `<li class="fail">${ico('x','sm')}
        <span>seq <b class="num">${esc(b.seq)}</b> — ${esc(b.reason)}</span></li>`).join('')}</ul>` : ''}
    <div class="note top">Re-running the same batch appends nothing: postings are keyed by an
      idempotency hash of the facts they were computed from. The ledger refuses UPDATE, DELETE and
      TRUNCATE at the database level.</div>
  </section>`);

  $('#vfy', panel).onclick = async () => {
    const b = $('#vfy', panel);
    b.disabled = true;
    try {
      const r = await fetch(`${API}/audit/verify?merchantId=${encodeURIComponent(S.merchant)}`, { headers: authHeaders() }).then(x => x.json());
      S.chain = r; paintChain();
      $('#vres', panel).innerHTML = r.valid
        ? `<span class="ok-t">Verified ${r.rows} rows. Nothing altered, inserted or removed.</span>`
        : `<span class="crit-t">Chain broken — ${r.breaks.length} break(s) detected.</span>`;
      if (!r.valid) render();
    } catch (err) {
      $('#vres', panel).innerHTML = `<span class="crit-t">Verification failed: ${esc(err.message)}</span>`;
    } finally { b.disabled = false; }
  };
  wrap.appendChild(panel);

  return wrap;
}

/* ═══════════════════════════════ 4 · ingest ═══════════════════════════ */
const ENTITIES = ['PAYMENTS', 'ORDERS', 'REFUNDS', 'SETTLEMENTS', 'BANK', 'CHARGEBACKS', 'RESERVE'];

async function uploadFile(entity, file) {
  const fd = new FormData();
  fd.append('file', file);
  const r = await fetch(`${API}/ingest?merchantId=${encodeURIComponent(S.merchant)}&entity=${entity}`,
    { method: 'POST', headers: authHeaders(), body: fd });
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(body.detail || `upload failed → ${r.status}`);
  return body;
}

async function loadIngestRuns() {
  try { S.ingestRuns = await get(`/ingest/runs?merchantId=${encodeURIComponent(S.merchant)}&limit=50`); }
  catch { S.ingestRuns = []; }
}

function viewIngest() {
  const wrap = el('<div class="stack"></div>');
  const r = S.ingestResult;

  const form = el(`<section class="panel">
    <header><h2>Ingest</h2><span class="sub">validated on the server, row by row</span></header>
    <div class="filters">
      <label class="ctl"><span>Entity</span>
        <select id="ingEntity">${ENTITIES.map(e => `<option>${e}</option>`).join('')}</select></label>
      <input type="file" id="ingFile" accept=".csv,text/csv" aria-label="CSV file to ingest">
      <button class="btn primary" id="ingGo" ${S.ingestBusy ? 'disabled' : ''}>
        ${ico('upload')}<span class="t">${S.ingestBusy ? 'Ingesting' : 'Ingest file'}</span></button>
    </div>
    <div class="note">Rows are accepted or refused individually. A refused row is stored with its reason
      and its original text, so the counts below can always be produced on demand.</div>
  </section>`);

  $('#ingGo', form).onclick = async () => {
    const f = $('#ingFile', form).files[0];
    if (!f) { toast('Choose a CSV first.'); return; }
    S.ingestBusy = true; render();
    try {
      S.ingestResult = await uploadFile($('#ingEntity', form).value, f);
      toast(`${S.ingestResult.rows_accepted.toLocaleString()} accepted, ${S.ingestResult.rows_rejected.toLocaleString()} rejected`);
      await loadIngestRuns();
    } catch (e) {
      S.ingestResult = null; toast('Ingestion failed: ' + e.message, 8000);
    } finally { S.ingestBusy = false; render(); }
  };
  wrap.appendChild(form);

  if (r) {
    const pctAcc = r.rows_seen ? (r.rows_accepted / r.rows_seen * 100) : 0;
    wrap.appendChild(el(`<section class="panel">
      <header><h2>Ingestion #${r.ingestion_id}</h2><span class="sub mono">${esc(r.source_name)}</span></header>
      <div class="tiles">
        <div class="tile"><div class="lab">Rows seen</div><div class="v fig fig-xl num">${r.rows_seen.toLocaleString()}</div></div>
        <div class="tile"><div class="lab">Accepted</div><div class="v fig fig-xl num ok-t">${r.rows_accepted.toLocaleString()}</div>
          <div class="hint">${pctAcc.toFixed(1)}% committed</div></div>
        <div class="tile"><div class="lab">Rejected</div>
          <div class="v fig fig-xl num ${r.rows_rejected ? 'crit-t' : 'ok-t'}">${r.rows_rejected.toLocaleString()}</div></div>
        <div class="tile"><div class="lab">Entity</div><div class="v fig fig-l">${esc(r.entity)}</div></div>
      </div>
      ${Object.keys(r.rejects_by_reason || {}).length ? `<div class="section"><div class="h">Why rows were refused</div></div>
        <ul class="gates">${Object.entries(r.rejects_by_reason).map(([k,v]) =>
          `<li class="fail">${ico('x','sm')}<span><b class="num">${v}</b> ${esc(k.toLowerCase().replace(/_/g,' '))}</span></li>`).join('')}</ul>` : ''}
    </section>`));

    if (r.sample_rejects?.length) {
      wrap.appendChild(el(`<section class="panel">
        <header><h2>Refused rows</h2><span class="sub">the actual lines, as received</span></header>
        <div class="scroll"><table>
          <thead><tr><th class="r">Line</th><th>Reason</th><th>Detail</th><th>Raw row</th></tr></thead>
          <tbody>${r.sample_rejects.map(x => `<tr>
            <td class="r num">${x.line_no}</td>
            <td><span class="pill crit">${ico('alert','sm')}${esc(x.reason_code)}</span></td>
            <td style="white-space:normal">${esc(x.detail)}</td>
            <td class="mono dim">${esc(x.raw_line)}</td></tr>`).join('')}</tbody>
        </table></div></section>`));
    }
  }

  const runs = S.ingestRuns || [];
  wrap.appendChild(el(`<section class="panel">
    <header><h2>Ingestion history</h2><span class="sub">${runs.length} run${runs.length === 1 ? '' : 's'}</span></header>
    ${runs.length ? `<div class="scrollx"><table>
      <thead><tr><th class="r">#</th><th>Entity</th><th>Source</th><th class="r">Seen</th>
        <th class="r">Accepted</th><th class="r">Rejected</th><th>State</th><th>SHA-256</th></tr></thead>
      <tbody>${runs.map(x => `<tr>
        <td class="r fig num">${x.ingestion_id}</td><td>${esc(x.entity)}</td>
        <td class="mono">${esc(x.source_name)}</td>
        <td class="r num">${(x.rows_seen ?? 0).toLocaleString()}</td>
        <td class="r num ok-t">${(x.rows_accepted ?? 0).toLocaleString()}</td>
        <td class="r num${x.rows_rejected ? ' crit-t' : ' dim'}">${(x.rows_rejected ?? 0).toLocaleString()}</td>
        <td><span class="pill ${x.state === 'succeeded' ? 'ok' : 'crit'}">
          ${ico(x.state === 'succeeded' ? 'check' : 'alert','sm')}${esc(x.state)}</span></td>
        <td class="mono dim">${esc(String(x.source_sha256 || '').slice(0,12))}</td>
      </tr>`).join('')}</tbody></table></div>`
      : `<div class="empty">${ico('upload')}<b>Nothing ingested yet for this merchant</b>
         <span>Upload a CSV above. Seeded data was loaded before ingestion runs were recorded.</span></div>`}
  </section>`));
  return wrap;
}

/* ═══════════════════════════════ 5 · metrics ══════════════════════════ */
function viewMetrics() {
  const m = S.metrics || {};
  const wrap = el('<div class="stack"></div>');
  const t = m.test_report, ab = m.ablation, cal = m.calibration, cov = m.coverage_risk;

  if (!m.available) {
    wrap.appendChild(el(`<section class="panel"><div class="note warn">
      Evaluation artifacts not present. Run <b>make evaluate</b> — these are produced offline against
      the hidden ground truth, which the running service deliberately cannot read.</div></section>`));
  } else {
    // These are STORED artifacts, not figures from the run selected in the strip.
    // Saying so is the whole fix: unchanged metrics beside a freshly completed
    // run otherwise read as stale, or as the screen quietly lying.
    const p = m.provenance || {};
    const stamps = Object.values(p.generated_at || {}).sort();
    const newest = stamps.length ? stamps[stamps.length - 1] : null;
    wrap.appendChild(el(`<section class="panel"><div class="note">
      ${ico('alert','sm')} These are <b>stored evaluation results</b>, not this reconciliation run.
      They are produced offline by <b>make evaluate</b> / <b>make benchmark</b> against held-out
      ground truth the running service cannot read${newest
        ? `, last written <b>${esc(String(newest).replace('T',' ').slice(0,16))}</b>` : ''}.
      Re-run the benchmark to refresh them; a new reconciliation run does not change these numbers.
    </div></section>`));
  }

  if (t) {
    wrap.appendChild(el(`<section class="panel">
      <header><h2>Held-out test</h2><span class="sub">touched once, after thresholds were fixed on dev</span></header>
      <div class="tiles">
        <div class="tile"><div class="lab">Amount-weighted F1</div><div class="v fig fig-xl num">${t.amt_f1.toFixed(2)}</div></div>
        <div class="tile"><div class="lab">False-match rate</div>
          <div class="v fig fig-xl num ${t.false_match < .5 ? 'ok-t' : 'crit-t'}">${t.false_match.toFixed(3)}%</div>
          <div class="hint">budget under 0.500%</div></div>
        <div class="tile"><div class="lab">Exception precision</div><div class="v fig fig-xl num">${t.exception_precision.toFixed(1)}%</div></div>
        <div class="tile"><div class="lab">Ambiguous missed</div>
          <div class="v fig fig-xl num ${t.ambiguous_missed ? 'crit-t' : 'ok-t'}">${t.ambiguous_missed}</div>
          <div class="hint">would be false matches</div></div>
        <div class="tile"><div class="lab">Unexplained</div><div class="v fig fig-l">₹${esc(t.residue_fmt)}</div>
          <div class="hint">${t.residue_pct_gmv.toFixed(4)}% of GMV</div></div>
        <div class="tile"><div class="lab">Throughput</div>
          <div class="v fig fig-xl num">${t.rps.toLocaleString()}<span class="dim" style="font-size:13px;font-weight:500">/s</span></div>
          <div class="hint">${t.wall_ms} ms wall</div></div>
      </div></section>`));
  }

  if (ab) wrap.appendChild(ablationPanel(ab));
  const half = el('<div class="half"></div>');
  if (cal) half.appendChild(calibrationPanel(cal));
  if (cov) half.appendChild(coveragePanel(cov));
  if (cal || cov) wrap.appendChild(half);
  return wrap;
}

function ablationPanel(ab) {
  const maxRes = Math.max(...ab.map(r => r.residue));
  return el(`<section class="panel">
    <header><h2>Ablation</h2><span class="sub">each row is a real run with that stage switched off</span></header>
    <div class="scrollx"><table>
      <thead><tr><th>Configuration</th><th class="r">Credits matched</th><th class="r">Wrong</th>
        <th style="width:140px">Value unexplained</th><th class="r">Residue %</th><th class="r">Residue</th>
        <th class="r">Payment links</th><th class="r">Amt F1</th><th class="r">False %</th>
        <th class="r">ms</th></tr></thead>
      <tbody>${ab.map(r => `<tr>
        <td>${esc(r.label)}<div class="dim" style="font-size:11px">${esc(r.note || '')}</div></td>
        <td class="r num">${r.bank_ok}/${r.bank_tot}</td>
        <td class="r num ${r.bank_bad ? 'crit-t' : 'dim'}">${r.bank_bad}</td>
        <td><div class="bar-track"><i style="width:${(r.residue/maxRes*100).toFixed(2)}%"></i></div></td>
        <td class="r fig num ${r.residue_pct < 1 ? 'ok-t' : r.residue_pct > 10 ? 'crit-t' : 'warn-t'}">${
          r.residue_pct == null ? '—' : r.residue_pct.toFixed(2) + '%'}</td>
        <td class="r">${money(r.residue)}</td>
        ${/* Payment-level linking only exists once netting runs. Printing 0 and
              0.00 for the stages before it reads as a broken pipeline rather
              than as a metric that does not apply yet, so those cells say so. */''}
        <td class="r num ${r.links ? '' : 'dim'}">${r.links ? r.links.toLocaleString() : 'n/a'}</td>
        <td class="r fig num ${r.links ? '' : 'dim'}">${r.links ? r.amt_f1.toFixed(2) : 'n/a'}</td>
        <td class="r num ${r.false_match > .5 ? 'crit-t' : 'ok-t'}">${r.false_match.toFixed(3)}</td>
        <td class="r num dim">${r.ms}</td></tr>`).join('')}</tbody>
    </table></div>
    <div class="note top">Read the <b>Residue %</b> column: it is the share of settled value the
      configuration could not account for, and it is what each stage is bought with. Exact UTR alone
      leaves 19.75%; UTR repair takes it to 15.85%; the pair scorer closes the last 11 credits and
      reaches 7.30%; netting proves batch membership and drops it to 0.09%.</div>
    <div class="note">Payment links and amount-weighted F1 read <b>n/a</b> before netting because no
      configuration below it assigns payments to batches at all — there is nothing to score yet. They
      are not zeros hiding a failure.</div>
    <div class="note">Global assignment shows no measurable gain here: after exact and repaired-UTR
      matching each remaining credit has one viable partner, so greedy and Hungarian agree. Reported
      rather than hidden — see LIMITATIONS.md.</div></section>`);
}

function calibrationPanel(cal) {
  const t = cal.test || cal.dev || cal.train || {};
  const bins = (t.reliability || []).filter(b => b.n > 0);
  const W = 460, H = 210, L = 34, R = 12, T = 12, B = 30;
  const iw = W - L - R, ih = H - T - B;
  const x = v => L + v * iw, y = v => T + (1 - v) * ih;
  const gridlines = [0,.25,.5,.75,1].map(v =>
    `<line class="gridline" x1="${L}" y1="${y(v)}" x2="${W-R}" y2="${y(v)}"/>
     <text x="${L-6}" y="${y(v)+3}" text-anchor="end">${(v*100).toFixed(0)}%</text>`).join('');
  const bw = Math.min(24, (iw / Math.max(bins.length,1)) - 4);
  const bars = bins.map(b => {
    const [a,z] = b.bin.split('-').map(Number);
    const cx = x((a+z)/2), h = Math.max(ih - (y(b.acc) - T), 1.5);
    return `<rect class="mk" x="${cx-bw/2}" y="${y(b.acc)}" width="${bw}" height="${h}" rx="3"
      fill="var(--indigo-2)" data-bin="${b.bin}" data-n="${b.n}" data-acc="${b.acc}" data-conf="${b.conf}"/>`;
  }).join('');

  const p = el(`<section class="panel">
    <header><h2>Calibration</h2><span class="sub">observed accuracy vs predicted confidence, held out</span></header>
    <div class="tiles">
      <div class="tile"><div class="lab">ECE</div><div class="v fig fig-l num">${(t.ece ?? 0).toFixed(4)}</div></div>
      <div class="tile"><div class="lab">AUC</div><div class="v fig fig-l num">${(t.auc ?? 0).toFixed(4)}</div></div>
      <div class="tile"><div class="lab">Pairs</div><div class="v fig fig-l num">${t.n ?? 0}</div></div>
    </div>
    <div class="chart"><svg viewBox="0 0 ${W} ${H}" role="img"
        aria-label="Reliability diagram: observed accuracy by predicted-confidence bin">
      ${gridlines}
      <line class="axis" x1="${L}" y1="${y(0)}" x2="${W-R}" y2="${y(0)}"/>
      <line class="axis" x1="${L}" y1="${T}" x2="${L}" y2="${y(0)}"/>
      <line x1="${x(0)}" y1="${y(0)}" x2="${x(1)}" y2="${y(1)}" stroke="var(--line-2)" stroke-width="1" stroke-dasharray="4 4"/>
      <text x="${x(1)-4}" y="${y(1)-6}" text-anchor="end">perfect calibration</text>
      ${bars}
      <text x="${L+iw/2}" y="${H-6}" text-anchor="middle">predicted confidence</text>
    </svg></div>
    <div class="note">Isotonic calibration is fitted on dev, so the dev ECE is in-sample and meaningless —
      this is the test split. The candidate set is small and nearly separable; treat these as indicative.</div>
  </section>`);

  $$('rect.mk', p).forEach(r => {
    r.addEventListener('mousemove', e => showTip(e, `<div class="t">bin ${r.dataset.bin}</div>
      <div class="row"><span>observed</span><span>${(+r.dataset.acc*100).toFixed(1)}%</span></div>
      <div class="row"><span>predicted</span><span>${(+r.dataset.conf*100).toFixed(1)}%</span></div>
      <div class="row"><span>pairs</span><span>${r.dataset.n}</span></div>`));
    r.addEventListener('mouseleave', hideTip);
  });
  return p;
}

function coveragePanel(cov) {
  const pts = cov.points || [];
  const W = 460, H = 210, L = 34, R = 74, T = 14, B = 34;
  const iw = W - L - R, ih = H - T - B;
  const xs = i => L + (i / Math.max(pts.length-1,1)) * iw;
  const ys = v => T + (1 - v/100) * ih;
  const path = k => pts.map((p,i) => `${i?'L':'M'}${xs(i).toFixed(1)},${ys(p[k]).toFixed(1)}`).join(' ');
  const opIdx = pts.findIndex(p => Math.abs(p.threshold - cov.operating_point) < 1e-9);
  const gridlines = [0,25,50,75,100].map(v =>
    `<line class="gridline" x1="${L}" y1="${ys(v)}" x2="${W-R}" y2="${ys(v)}"/>
     <text x="${L-6}" y="${ys(v)+3}" text-anchor="end">${v}%</text>`).join('');
  const ticks = pts.map((p,i) => (i%2===0 || i===pts.length-1)
    ? `<text x="${xs(i)}" y="${H-14}" text-anchor="middle">${p.threshold.toFixed(2)}</text>` : '').join('');
  const last = pts[pts.length-1] || { coverage: 0, precision: 0 };

  const p = el(`<section class="panel">
    <header><h2>Coverage vs risk</h2><span class="sub">auto-post threshold swept on dev</span></header>
    <div class="chart"><svg viewBox="0 0 ${W} ${H}" role="img"
        aria-label="Coverage and precision as the auto-post confidence threshold rises">
      ${gridlines}
      <line class="axis" x1="${L}" y1="${ys(0)}" x2="${W-R}" y2="${ys(0)}"/>
      <line class="axis" x1="${L}" y1="${T}" x2="${L}" y2="${ys(0)}"/>
      ${opIdx>=0 ? `<line class="opmark" x1="${xs(opIdx)}" y1="${T}" x2="${xs(opIdx)}" y2="${ys(0)}"/>
        <text x="${xs(opIdx)}" y="${T-3}" text-anchor="middle" fill="var(--bronze)">operating point ${cov.operating_point}</text>` : ''}
      <path class="line" d="${path('coverage')}" stroke="var(--indigo-2)"/>
      <path class="line" d="${path('precision')}" stroke="var(--emerald)"/>
      <circle class="dot ring" cx="${xs(pts.length-1)}" cy="${ys(last.coverage)}" fill="var(--indigo-2)"/>
      <circle class="dot ring" cx="${xs(pts.length-1)}" cy="${ys(last.precision)}" fill="var(--emerald)"/>
      <text x="${W-R+7}" y="${ys(last.coverage)+3}" fill="var(--ink-2)">coverage ${last.coverage.toFixed(0)}%</text>
      <text x="${W-R+7}" y="${ys(last.precision)+3}" fill="var(--ink-2)">precision ${last.precision.toFixed(0)}%</text>
      <text x="${L+iw/2}" y="${H-2}" text-anchor="middle">confidence threshold</text>
      ${ticks}
      <g id="cross" style="display:none"><line class="opmark" stroke-dasharray="none" y1="${T}" y2="${ys(0)}"/></g>
      <rect x="${L}" y="${T}" width="${iw}" height="${ih}" fill="transparent" id="hit"/>
    </svg></div>
    <div class="legend">
      <span class="k"><i class="sw" style="background:var(--indigo-2)"></i>coverage</span>
      <span class="k"><i class="sw" style="background:var(--emerald)"></i>precision</span>
    </div>
    <div class="note">Raising the bar trades coverage for safety. At the chosen operating point precision
      stays at 100% — the items it stops auto-posting are the ones it cannot justify.</div></section>`);

  const svg = $('svg', p), hit = $('#hit', p), cross = $('#cross', p), cl = $('line', cross);
  hit.addEventListener('mousemove', e => {
    const box = svg.getBoundingClientRect();
    const sx = (e.clientX - box.left) / box.width * W;
    let i = Math.round((sx - L) / iw * (pts.length - 1));
    i = Math.max(0, Math.min(pts.length - 1, i));
    const d = pts[i];
    cross.style.display = ''; cl.setAttribute('x1', xs(i)); cl.setAttribute('x2', xs(i));
    showTip(e, `<div class="t">threshold ${d.threshold.toFixed(2)}</div>
      <div class="row"><span>coverage</span><span>${d.coverage.toFixed(1)}%</span></div>
      <div class="row"><span>precision</span><span>${d.precision.toFixed(1)}%</span></div>
      <div class="row"><span>auto-posted</span><span>${d.auto_posted}</span></div>
      <div class="row"><span>false matches</span><span>${d.false_matches}</span></div>`);
  });
  hit.addEventListener('mouseleave', () => { cross.style.display = 'none'; hideTip(); });
  return p;
}

boot();
