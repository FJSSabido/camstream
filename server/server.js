// Servidor de señalización WebRTC para MiConstelación CamStream.
//
// Qué hace:
//  - Sirve una página web "visor" (public/watch.html) en /watch/:room
//  - Ofrece un WebSocket en /ws que hace de intermediario ("señalización")
//    entre el móvil que emite (host) y cada navegador que mira (viewer).
//  - El vídeo/audio NUNCA pasa por este servidor: solo viaja por aquí el
//    "apretón de manos" inicial de WebRTC (SDP + ICE candidates). El stream
//    real va directo entre el móvil y quien lo ve (peer-to-peer).
//
// Un móvil (host) = una sala (room). Varias personas pueden ver la misma
// sala a la vez (cada una es un "viewer" con su propia conexión WebRTC).

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const PUBLIC_DIR = path.join(__dirname, 'public');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

function serveFile(res, filePath, statusCode = 200) {
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('No encontrado');
      return;
    }
    const ext = path.extname(filePath);
    res.writeHead(statusCode, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
    res.end(data);
  });
}

const httpServer = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  let pathname = url.pathname;

  if (pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
    return;
  }

  // /watch/ROOMID  ->  siempre sirve watch.html (el room id se lee en el navegador desde la URL)
  if (pathname.startsWith('/watch/')) {
    serveFile(res, path.join(PUBLIC_DIR, 'watch.html'));
    return;
  }

  if (pathname === '/' || pathname === '') {
    serveFile(res, path.join(PUBLIC_DIR, 'index.html'));
    return;
  }

  // Servir archivos estáticos normales (evitando salir de PUBLIC_DIR)
  const safePath = path.normalize(pathname).replace(/^(\.\.[/\\])+/, '');
  const filePath = path.join(PUBLIC_DIR, safePath);
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(400);
    res.end('Bad request');
    return;
  }
  serveFile(res, filePath);
});

const wss = new WebSocketServer({ server: httpServer, path: '/ws' });

// rooms: Map<roomId, { hostWs, hostKey: string|null, viewers: Map<viewerId, ws> }>
const rooms = new Map();

function send(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function getOrCreateRoom(roomId) {
  let room = rooms.get(roomId);
  if (!room) {
    room = { hostWs: null, hostKey: null, viewers: new Map() };
    rooms.set(roomId, room);
  }
  return room;
}

function cleanupEmptyRoom(roomId) {
  const room = rooms.get(roomId);
  if (room && !room.hostWs && room.viewers.size === 0) {
    rooms.delete(roomId);
  }
}

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  const roomId = (url.searchParams.get('room') || '').trim();
  const role = url.searchParams.get('role') === 'host' ? 'host' : 'viewer';
  const key = url.searchParams.get('key') || '';

  if (!roomId) {
    send(ws, { type: 'error', message: 'Falta el identificador de sala.' });
    ws.close();
    return;
  }

  const room = getOrCreateRoom(roomId);

  if (role === 'host') {
    if (room.hostWs && room.hostWs.readyState === room.hostWs.OPEN) {
      send(ws, { type: 'error', message: 'Esta sala ya tiene una emisión activa desde otro dispositivo.' });
      ws.close();
      return;
    }
    room.hostWs = ws;
    room.hostKey = key || null;
    ws.role = 'host';
    ws.roomId = roomId;

    send(ws, { type: 'host-ready', viewerCount: room.viewers.size });

    // Avisar al host de los espectadores que ya estaban esperando
    for (const viewerId of room.viewers.keys()) {
      send(ws, { type: 'viewer-joined', viewerId });
    }
  } else {
    // viewer
    if (room.hostKey && room.hostKey !== key) {
      send(ws, { type: 'error', message: 'Contraseña incorrecta.' });
      ws.close();
      return;
    }
    const viewerId = crypto.randomBytes(6).toString('hex');
    room.viewers.set(viewerId, ws);
    ws.role = 'viewer';
    ws.roomId = roomId;
    ws.viewerId = viewerId;

    send(ws, { type: 'viewer-registered', viewerId, hostOnline: !!room.hostWs });

    if (room.hostWs) {
      send(room.hostWs, { type: 'viewer-joined', viewerId });
    }
  }

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }

    const r = rooms.get(ws.roomId);
    if (!r) return;

    if (ws.role === 'host') {
      if (msg.type === 'state' && !msg.viewerId) {
        // A diferencia de offer/answer/candidate (dirigidos a un viewer concreto
        // con msg.viewerId), un cambio de estado — se ha encendido o apagado el
        // vídeo o el micrófono — se avisa a TODOS los espectadores conectados a
        // la vez, para que cada uno actualice sus iconos de "sin señal".
        for (const viewer of r.viewers.values()) send(viewer, msg);
        return;
      }
      // El host manda el resto de mensajes dirigidos a un viewer concreto:
      // {type, viewerId, ...} — incluido 'state' cuando SÍ lleva viewerId (para
      // ponerse al día a un espectador que se acaba de conectar).
      const target = r.viewers.get(msg.viewerId);
      if (target) send(target, msg);
    } else {
      // El viewer manda mensajes al host; el servidor añade su viewerId
      msg.viewerId = ws.viewerId;
      if (r.hostWs) send(r.hostWs, msg);
    }
  });

  ws.on('close', () => {
    const r = rooms.get(ws.roomId);
    if (!r) return;
    if (ws.role === 'host' && r.hostWs === ws) {
      r.hostWs = null;
      r.hostKey = null;
      // Avisar a todos los espectadores de que la emisión se cortó
      for (const viewer of r.viewers.values()) {
        send(viewer, { type: 'host-offline' });
      }
    } else if (ws.role === 'viewer') {
      r.viewers.delete(ws.viewerId);
      if (r.hostWs) send(r.hostWs, { type: 'viewer-left', viewerId: ws.viewerId });
    }
    cleanupEmptyRoom(ws.roomId);
  });
});

httpServer.listen(PORT, () => {
  console.log(`MiConstelación CamStream escuchando en el puerto ${PORT}`);
});
