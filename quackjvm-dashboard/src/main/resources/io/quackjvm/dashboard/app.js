// The quackjvm dashboard: polls /api/state once a second and redraws. No libraries.
// Everything taken from the state is inserted with textContent, never as markup.
'use strict';

const POLL_MS = 1000;
const $ = (id) => document.getElementById(id);

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

// ---------- Formatting ----------

const isNum = (v) => typeof v === 'number' && isFinite(v);

function fmtRate(v) {
  if (!isNum(v)) return '–';
  if (v >= 10000) return (v / 1000).toFixed(0) + 'k';
  if (v >= 1000) return (v / 1000).toFixed(1) + 'k';
  if (v >= 100) return v.toFixed(0);
  if (v >= 10) return v.toFixed(1);
  return v.toFixed(v === 0 ? 0 : 2);
}

function fmtMs(v) {
  if (!isNum(v)) return '–';
  if (v >= 1000) return (v / 1000).toFixed(2) + ' s';
  if (v >= 100) return v.toFixed(0) + ' ms';
  if (v >= 10) return v.toFixed(1) + ' ms';
  if (v >= 1) return v.toFixed(2) + ' ms';
  return (v * 1000).toFixed(0) + ' µs';
}

function fmtBytes(v) {
  if (!isNum(v)) return '–';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (i === 0 ? v.toFixed(0) : v.toFixed(v >= 100 ? 0 : 1)) + ' ' + units[i];
}

const fmtPct = (v) => (isNum(v) ? (v * 100).toFixed(0) + '%' : '–');

function fmtDuration(ms) {
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm ' + (s % 60) + 's';
  return Math.floor(m / 60) + 'h ' + (m % 60) + 'm';
}

// ---------- Sparklines ----------

const SVG = 'http://www.w3.org/2000/svg';

function sparkline(values, floorMax) {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 100 40');
  svg.setAttribute('preserveAspectRatio', 'none');
  svg.setAttribute('aria-hidden', 'true');
  const points = (values || []).map((v, i) => [i, isNum(v) ? v : null]);
  const present = points.filter((p) => p[1] !== null);
  if (present.length < 2) return svg;
  const max = Math.max(floorMax || 0, ...present.map((p) => p[1])) || 1;
  const n = Math.max(1, points.length - 1);
  const x = (i) => (i / n) * 100;
  const y = (v) => 38 - (v / max) * 34;
  let line = '';
  let area = '';
  let run = [];
  const flush = () => {
    if (run.length > 1) {
      const d = run.map((p, k) => (k ? 'L' : 'M') + x(p[0]).toFixed(2) + ' ' + y(p[1]).toFixed(2)).join(' ');
      line += d + ' ';
      area += d + ` L${x(run[run.length - 1][0]).toFixed(2)} 40 L${x(run[0][0]).toFixed(2)} 40 Z `;
    }
    run = [];
  };
  points.forEach((p) => (p[1] === null ? flush() : run.push(p)));
  flush();
  const fill = document.createElementNS(SVG, 'path');
  fill.setAttribute('class', 'area');
  fill.setAttribute('d', area);
  const stroke = document.createElementNS(SVG, 'path');
  stroke.setAttribute('class', 'stroke');
  stroke.setAttribute('d', line);
  svg.append(fill, stroke);
  return svg;
}

// ---------- Sections ----------

function renderStatus(state) {
  const status = $('status');
  const findings = state.findings || [];
  if (findings.length === 0) {
    status.dataset.state = 'ok';
    $('status-text').textContent = 'Nothing is choking';
  } else {
    const top = findings[0];
    status.dataset.state = top.severity >= 0.6 ? 'bad' : 'warn';
    $('status-text').textContent = top.headline.charAt(0).toUpperCase() + top.headline.slice(1);
  }
  const updated = new Date(state.timestamp).toLocaleTimeString();
  $('meta').textContent = `updated ${updated} · watching for ${fmtDuration(state.timestamp - state.startedAt)}`;
  $('window').textContent = `last ${Math.round(state.windowSeconds)} s`;
  $('statements-window').textContent = `last ${Math.round(state.longWindowSeconds)} s`;
}

function renderFindings(state) {
  const box = $('findings');
  box.replaceChildren();
  const findings = state.findings || [];
  if (findings.length === 0) {
    const card = el('div', 'finding healthy');
    const head = el('div', 'head');
    head.append(el('span', 'cause', 'OK'), el('span', 'headline', 'Nothing is choking'));
    card.append(head, el('p', 'seen',
      'No write-lock queueing, CPU contention, conflicts, memory pressure, prepare churn or connection churn in the last few seconds.'));
    card.lastChild.style.margin = '8px 0 0';
    card.lastChild.style.color = 'var(--muted)';
    box.append(card);
    return;
  }
  for (const f of findings) {
    const card = el('div', 'finding' + (f.severity >= 0.6 ? ' severe' : ''));
    const head = el('div', 'head');
    head.append(el('span', 'cause', f.cause.replace('_', ' ')), el('span', 'headline',
      f.headline.charAt(0).toUpperCase() + f.headline.slice(1)));
    const dl = el('dl');
    dl.append(el('dt', null, 'Seen'), el('dd', 'seen', f.evidence), el('dt', null, 'Do'), el('dd', null,
      f.advice.charAt(0).toUpperCase() + f.advice.slice(1)));
    card.append(head, dl);
    box.append(card);
  }
}

function renderOthers(state) {
  const box = $('others');
  const seen = state.otherProcesses || {};
  const list = seen.processes || [];
  if (list.length === 0) {
    box.hidden = true;
    return;
  }
  const title = el('div', 'title', 'Other programs using the cores ');
  title.append(el('span', 'hint',
    `they held ${fmtPct(seen.othersShare)} of the machine, seen ${Math.round((state.timestamp - seen.at) / 1000)} s ago`));
  const ul = el('ul');
  for (const p of list) {
    const li = el('li');
    const who = el('span', 'who', p.name);
    who.title = p.name;
    li.append(who, el('span', 'pid', 'pid ' + p.pid), bar(p.share, 'wait'));
    ul.append(li);
  }
  box.replaceChildren(title, ul);
  box.hidden = false;
}

function tile(label, value, unit, sub, series, alert, floorMax) {
  const t = el('div', 'tile' + (alert ? ' alert' : ''));
  const v = el('div', 'value', value);
  if (unit) v.append(el('span', 'unit', unit));
  t.append(el('div', 'label', label), v, el('div', 'sub', sub || ''));
  if (series) t.append(sparkline(series, floorMax));
  return t;
}

function renderTiles(state) {
  const n = state.now || {};
  const s = state.series || {};
  const busiest = Math.max(isNum(n.cpu) ? n.cpu : 0, isNum(n.machineCpu) ? n.machineCpu : 0);
  const cpuBusy = busiest >= 0.7;
  const memTight = (isNum(n.tempBytes) && n.tempBytes > 0)
    || (isNum(n.memoryBytes) && isNum(n.memoryLimitBytes) && n.memoryBytes >= 0.9 * n.memoryLimitBytes);
  const tiles = [
    tile('Reads', fmtRate(n.readsPerSecond), '/s', `p50 ${fmtMs(n.readP50)} · p99 ${fmtMs(n.readP99)}`,
      s.readsPerSecond),
    tile('Read p99', fmtMs(n.readP99), '', 'slowest 1% of read requests', s.readP99),
    tile('Writes', fmtRate(n.writesPerSecond), '/s', `p50 ${fmtMs(n.writeP50)} · p99 ${fmtMs(n.writeP99)}`,
      s.writesPerSecond),
    tile('Write p99', fmtMs(n.writeP99), '', 'slowest 1% of write requests, lock already held', s.writeP99),
    tile('Write-lock wait', fmtPct(n.lockWaitShare), '', 'share of write time spent queueing',
      s.lockWaitShare, n.lockWaitShare >= 0.25, 1),
    tile('CPU, whole machine', fmtPct(isNum(n.machineCpu) ? n.machineCpu : n.cpu), '',
      `this process ${fmtPct(n.cpu)} · ${n.cores || '?'} cores · DuckDB threads ${isNum(n.duckdbThreads) ? n.duckdbThreads : '?'}`,
      s.machineCpu || s.cpu, cpuBusy, 1),
    tile('Heavy statements at once', isNum(n.heavyStatementsAtOnce) ? n.heavyStatementsAtOnce.toFixed(1) : '–', '',
      `≥ 1 ms each · ${isNum(n.statementsAtOnce) ? n.statementsAtOnce.toFixed(1) : '–'} of any size`,
      s.heavyStatements, cpuBusy && n.heavyStatementsAtOnce >= 1.5, 1),
    tile('DuckDB memory', fmtBytes(n.memoryBytes), '',
      `of ${fmtBytes(n.memoryLimitBytes)} · spilled ${fmtBytes(n.tempBytes)}`, s.memoryBytes, memTight),
    tile('Conflicts', fmtRate(n.conflictsPerSecond), '/s', `failed statements, all kinds: ${fmtRate(n.errorsPerSecond)}/s`,
      s.conflictsPerSecond, n.conflictsPerSecond > 0, 1),
    tile('Connections in use', isNum(n.connectionsInUse) ? String(n.connectionsInUse) : '–', '',
      `opened ${fmtRate(n.connectionsOpenedPerSecond)}/s · statement cache hits ${fmtPct(n.prepareHitRate)}`,
      null, n.connectionsOpenedPerSecond > 1),
  ];
  $('tiles').replaceChildren(...tiles);
}

function bar(fraction, kind) {
  const b = el('div', 'bar' + (kind ? ' ' + kind : ''));
  const track = el('div', 'track');
  const fill = el('div', 'fill');
  fill.style.width = Math.max(0, Math.min(1, fraction || 0)) * 100 + '%';
  track.append(fill);
  b.append(track, el('span', 'pct', fmtPct(fraction || 0)));
  return b;
}

function cell(text, className) {
  return el('td', className, text);
}

function renderCollections(state) {
  const body = $('collections').tBodies[0];
  const rows = (state.collections || []).map((c) => {
    const tr = el('tr');
    const share = el('td');
    share.append(bar(c.waitShare, 'wait'));
    // Requests made through database.sql(), query() and join() are recorded under "sql".
    const name = cell(c.name === 'sql' ? 'SQL' : c.name, 'name');
    if (c.name === 'sql') name.title = 'database.sql(), query() and join()';
    tr.append(
      name,
      cell(fmtRate(c.readsPerSecond), 'num'),
      cell(`${fmtMs(c.readP50)} · ${fmtMs(c.readP99)}`, 'num'),
      cell(fmtRate(c.writesPerSecond), 'num'),
      cell(`${fmtMs(c.writeP50)} · ${fmtMs(c.writeP99)}`, 'num'),
      cell(fmtMs(c.lockWaitP99), 'num'),
      share);
    return tr;
  });
  if (rows.length === 0) {
    const tr = el('tr');
    const td = cell('No requests in the last few seconds.', 'empty');
    td.colSpan = 7;
    tr.append(td);
    rows.push(tr);
  }
  body.replaceChildren(...rows);
}

function renderStatements(state) {
  const body = $('statements').tBodies[0];
  const rows = (state.statements || []).map((st) => {
    const tr = el('tr');
    const share = el('td');
    share.append(bar(st.share));
    const sql = el('td', 'sql');
    const span = el('span', null, st.shape);
    span.title = st.shape;
    sql.append(span);
    tr.append(share, cell(fmtRate(st.perSecond), 'num'), cell(fmtMs(st.mean), 'num'), cell(fmtMs(st.p99), 'num'), sql);
    return tr;
  });
  if (rows.length === 0) {
    const tr = el('tr');
    const td = cell('No statements in the last minute.', 'empty');
    td.colSpan = 5;
    tr.append(td);
    rows.push(tr);
  }
  body.replaceChildren(...rows);
}

function renderRecording(recording) {
  const line = $('recording');
  line.classList.toggle('failed', Boolean(recording.error));
  if (recording.error) {
    line.textContent = recording.error;
  } else if (recording.directory) {
    line.replaceChildren('Recording every second to ', el('code', null, recording.directory),
      ' - JSON Lines, queryable with DuckDB: ', el('code', null, `SELECT * FROM '${recording.directory}/metrics-*.jsonl'`));
  } else {
    line.textContent = 'Not recording to disk.';
  }
}

// ---------- Polling ----------

let lastOk = 0;

async function poll() {
  try {
    const response = await fetch('api/state', { cache: 'no-store' });
    if (!response.ok) throw new Error(String(response.status));
    const state = await response.json();
    lastOk = Date.now();
    $('title').textContent = state.title && state.title !== 'quackjvm' ? state.title : '';
    document.title = (state.title && state.title !== 'quackjvm' ? state.title + ' · ' : '') + 'quackjvm dashboard';
    renderRecording(state.recording || {});
    if (state.warmingUp) {
      $('status-text').textContent = 'Collecting the first samples…';
    } else {
      renderStatus(state);
      renderFindings(state);
      renderOthers(state);
      renderTiles(state);
      renderCollections(state);
      renderStatements(state);
    }
  } catch (e) {
    if (Date.now() - lastOk > 3000) {
      $('status').dataset.state = 'stale';
      $('status-text').textContent = 'Not reachable - is the application still running?';
    }
  } finally {
    setTimeout(poll, POLL_MS);
  }
}

poll();
