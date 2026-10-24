// ClassFocus 서버: 교사 명령(수업 시작/종료/허용앱)을 학생 기기들에 실시간 전달
const express = require('express');
const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const app = express();
app.use(express.json());
app.use(express.static(__dirname + '/public'));

const classes = new Map(); // code -> class

app.post('/api/classes', (req, res) => {
  let code;
  do { code = String(crypto.randomInt(100000, 999999)); } while (classes.has(code));
  const c = {
    code, teacherToken: crypto.randomUUID(), teacher: null,
    active: false, allowed: [], iosAllowPreset: false,
    students: new Map(), created: Date.now(),
  };
  classes.set(code, c);
  res.json({ code, teacherToken: c.teacherToken });
});

const send = (ws, obj) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); };
const stateMsg = c => ({ type: 'state', active: c.active, allowed: c.allowed, iosAllowPreset: c.iosAllowPreset });

function catalog(c) {
  const m = new Map();
  for (const s of c.students.values()) for (const a of s.apps || []) if (!m.has(a.pkg)) m.set(a.pkg, a.label);
  return [...m].map(([pkg, label]) => ({ pkg, label })).sort((a, b) => a.label.localeCompare(b.label));
}
function pushRoster(c) {
  send(c.teacher, {
    type: 'roster', ...stateMsg(c),
    apps: catalog(c),
    students: [...c.students.values()].map(s => ({
      id: s.id, name: s.name, platform: s.platform,
      online: !!s.ws && s.ws.readyState === 1, tamper: s.tamper || null, lastSeen: s.lastSeen,
    })),
  });
}
function broadcast(c) {
  const m = stateMsg(c);
  for (const s of c.students.values()) send(s.ws, m);
  pushRoster(c);
}

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

wss.on('connection', ws => {
  ws.ctx = null;
  ws.on('message', raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (!ws.ctx) { // 첫 메시지는 반드시 hello
      if (m.type !== 'hello') return ws.close();
      const c = classes.get(String(m.code));
      if (!c) { send(ws, { type: 'error', message: '수업 코드가 올바르지 않습니다' }); return ws.close(); }
      if (m.role === 'teacher') {
        if (m.token !== c.teacherToken) return ws.close();
        c.teacher = ws; ws.ctx = { role: 'teacher', c };
        return pushRoster(c);
      }
      if (m.role === 'student' && m.id) {
        const id = String(m.id).slice(0, 64);
        const s = c.students.get(id) || { id, apps: [] };
        Object.assign(s, { name: String(m.name || '학생').slice(0, 30), platform: m.platform === 'ios' ? 'ios' : 'android', ws, lastSeen: Date.now() });
        c.students.set(id, s); ws.ctx = { role: 'student', c, s };
        send(ws, stateMsg(c)); return pushRoster(c);
      }
      return ws.close();
    }
    const { role, c, s } = ws.ctx;
    if (role === 'teacher') {
      if (m.type === 'start') c.active = true;
      else if (m.type === 'end') c.active = false;
      else if (m.type === 'setAllowed') {
        c.allowed = Array.isArray(m.allowed) ? m.allowed.map(String).slice(0, 200) : [];
        c.iosAllowPreset = !!m.iosAllowPreset;
      } else return;
      broadcast(c);
    } else {
      s.lastSeen = Date.now();
      if (m.type === 'apps' && Array.isArray(m.apps)) {
        s.apps = m.apps.slice(0, 500).map(a => ({ pkg: String(a.pkg), label: String(a.label).slice(0, 60) }));
        pushRoster(c);
      } else if (m.type === 'status') {
        const t = m.tamper ? String(m.tamper).slice(0, 40) : null;
        const changed = t !== s.tamper; s.tamper = t;
        if (changed) pushRoster(c);
      }
    }
  });
  ws.on('close', () => {
    if (ws.ctx?.role === 'student' && ws.ctx.s.ws === ws) pushRoster(ws.ctx.c); // 수업 중 오프라인 = 교사 화면에 표시
    if (ws.ctx?.role === 'teacher' && ws.ctx.c.teacher === ws) ws.ctx.c.teacher = null;
  });
});

setInterval(() => { // 24시간 지난 수업 정리
  for (const [k, c] of classes) if (Date.now() - c.created > 864e5) classes.delete(k);
}, 36e5);

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log('ClassFocus server on :' + PORT));
