"""Минимальный клиент obs-websocket v5 (встроен в OBS 28+). Только стандартная библиотека Python."""

import base64
import hashlib
import json
import os
import socket
import struct


class ObsError(Exception):
    pass


class ObsClient:
    def __init__(self, host="127.0.0.1", port=4455, password="", timeout=5):
        self.host, self.port, self.password, self.timeout = host, port, password, timeout
        self.sock = None
        self._req = 0
        self._buf = b""

    # ---------- WebSocket ----------

    def _recv_exact(self, n):
        # Сначала отдаём то, что пришло вместе с ответом на рукопожатие
        while len(self._buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ObsError("OBS закрыл соединение")
            self._buf += chunk
        data, self._buf = self._buf[:n], self._buf[n:]
        return data

    def _send(self, obj):
        payload = json.dumps(obj).encode("utf-8")
        header = bytearray([0x81])  # FIN + текстовый кадр
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + mask + masked)

    def _recv(self):
        while True:
            b1, b2 = self._recv_exact(2)
            opcode = b1 & 0x0F
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._recv_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._recv_exact(8))[0]
            mask = self._recv_exact(4) if b2 & 0x80 else None
            data = self._recv_exact(n)
            if mask:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            if opcode == 0x8:
                raise ObsError("OBS закрыл соединение (проверь пароль)")
            if opcode == 0x9:  # ping -> pong
                continue
            if opcode in (0x1, 0x0):
                return json.loads(data.decode("utf-8"))

    def connect(self):
        try:
            self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        except OSError:
            raise ObsError(
                "Не удалось подключиться к OBS. Запусти OBS и включи: "
                "Сервис → Настройки сервера WebSocket → Включить сервер WebSocket"
            )
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET / HTTP/1.1\r\nHost: {self.host}:{self.port}\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
            "Sec-WebSocket-Protocol: obswebsocket.json\r\n\r\n"
        )
        self.sock.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(1024)
            if not chunk:
                raise ObsError("OBS не ответил на подключение")
            resp += chunk
        head, _, rest = resp.partition(b"\r\n\r\n")
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise ObsError("OBS отклонил подключение")
        self._buf = rest

        hello = self._recv()
        identify = {"rpcVersion": 1}
        auth = hello.get("d", {}).get("authentication")
        if auth:
            if not self.password:
                raise ObsError("В OBS включён пароль WebSocket — впиши его в программе")
            secret = base64.b64encode(
                hashlib.sha256((self.password + auth["salt"]).encode()).digest()
            ).decode()
            identify["authentication"] = base64.b64encode(
                hashlib.sha256((secret + auth["challenge"]).encode()).digest()
            ).decode()
        self._send({"op": 1, "d": identify})
        msg = self._recv()
        if msg.get("op") != 2:
            raise ObsError("Неверный пароль WebSocket")

    def request(self, rtype, data=None):
        self._req += 1
        rid = str(self._req)
        body = {"requestType": rtype, "requestId": rid}
        if data is not None:
            body["requestData"] = data
        self._send({"op": 6, "d": body})
        while True:
            msg = self._recv()
            if msg.get("op") == 7 and msg["d"].get("requestId") == rid:
                status = msg["d"]["requestStatus"]
                if not status.get("result"):
                    raise ObsError(status.get("comment") or f"Ошибка OBS (код {status.get('code')})")
                return msg["d"].get("responseData", {})

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            finally:
                self.sock = None


SOURCE_NAME = "📱 Телефон (GameStream)"


def add_phone_source(srt_url, password=""):
    """Создаёт (или обновляет) в текущей сцене OBS источник, принимающий поток с телефона."""
    settings = {
        "is_local_file": False,
        "input": srt_url,
        "input_format": "mpegts",
        "hw_decode": True,
        "restart_on_activate": False,
        "close_when_inactive": False,
        "buffering_mb": 1,
        "reconnect_delay_sec": 1,
    }
    obs = ObsClient(password=password)
    try:
        obs.connect()
        inputs = obs.request("GetInputList").get("inputs", [])
        if any(i.get("inputName") == SOURCE_NAME for i in inputs):
            obs.request("SetInputSettings", {"inputName": SOURCE_NAME, "inputSettings": settings})
            return "Источник телефона уже был — настройки обновлены"
        scene = obs.request("GetCurrentProgramScene")["currentProgramSceneName"]
        obs.request("CreateInput", {
            "sceneName": scene,
            "inputName": SOURCE_NAME,
            "inputKind": "ffmpeg_source",
            "inputSettings": settings,
        })
        return f"Готово! Источник добавлен в сцену «{scene}»"
    finally:
        obs.close()
