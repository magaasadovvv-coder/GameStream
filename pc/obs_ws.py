"""Минимальный клиент obs-websocket v5 (встроен в OBS 28+). Только стандартная библиотека Python."""

import base64
import hashlib
import json
import os
import socket
import struct
import time


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
        # Без буфера: звук и картинка идут сразу, задержка не копится
        "buffering_mb": 0,
        "ffmpeg_options": "fflags=nobuffer flags=low_delay",
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


# ---------- Синхронизация голоса ----------

def png_luma(data):
    """Средняя яркость картинки PNG (0–255). Свой мини-декодер, без сторонних библиотек."""
    import zlib
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ObsError("OBS вернул не PNG")
    pos, idat, width, height, ctype, depth, interlace = 8, b"", 0, 0, 0, 0, 0
    while pos < len(data):
        n = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + n]
        if kind == b"IHDR":
            width, height, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
        pos += 12 + n
    if depth != 8 or interlace != 0 or ctype not in (0, 2, 4, 6):
        raise ObsError("Неподдерживаемый формат скриншота")
    bpp = {0: 1, 2: 3, 4: 2, 6: 4}[ctype]
    raw = zlib.decompress(idat)
    stride = width * bpp
    prev = bytearray(stride)
    total, i = 0, 0
    for _ in range(height):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 0xFF
            elif f == 2:
                line[x] = (line[x] + b) & 0xFF
            elif f == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 0xFF
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[x] = (line[x] + pred) & 0xFF
        for x in range(0, stride, bpp):
            if ctype in (2, 6):
                total += (line[x] * 299 + line[x + 1] * 587 + line[x + 2] * 114) // 1000
            else:
                total += line[x]
        prev = line
    return total / max(1, width * height)


MIC_KINDS = ("wasapi_input_capture", "coreaudio_input_capture", "pulse_input_capture")


def _mic_names(obs):
    names = set()
    for i in obs.request("GetInputList").get("inputs", []):
        if i.get("inputKind") in MIC_KINDS or i.get("unversionedInputKind") in MIC_KINDS:
            names.add(i["inputName"])
    special = obs.request("GetSpecialInputs")
    for key in ("mic1", "mic2", "mic3", "mic4"):
        if special.get(key):
            names.add(special[key])
    return sorted(names)


def _luma(obs):
    shot = obs.request("GetSourceScreenshot", {
        "sourceName": SOURCE_NAME, "imageFormat": "png", "imageWidth": 32, "imageHeight": 18,
    })["imageData"]
    return png_luma(base64.b64decode(shot.split(",", 1)[1]))


def measure_and_sync(send_flash, password="", tries=3, log=lambda m: None):
    """
    Меряет, на сколько картинка с телефона приходит позже, и ставит этот сдвиг микрофонам в OBS.
    send_flash() просит телефон на полсекунды сделать картинку стрима белой.
    Возвращает (задержка в мс, список микрофонов).
    """
    obs = ObsClient(password=password)
    try:
        obs.connect()
        if not any(i.get("inputName") == SOURCE_NAME for i in obs.request("GetInputList").get("inputs", [])):
            raise ObsError("В OBS нет источника телефона — сначала нажми «Добавить телефон в OBS»")
        mics = _mic_names(obs)
        if not mics:
            raise ObsError("В OBS не найден микрофон — добавь его как источник «Захват входного аудиопотока»")

        results = []
        for attempt in range(tries):
            log(f"Замер {attempt + 1} из {tries}…")
            base = sum(_luma(obs) for _ in range(3)) / 3
            if base > 200:
                raise ObsError("Картинка слишком светлая для замера — открой в игре тёмный экран")
            t0 = time.perf_counter()
            send_flash()
            found = None
            while time.perf_counter() - t0 < 8:
                t1 = time.perf_counter()
                lum = _luma(obs)
                t2 = time.perf_counter()
                if lum > 215 and lum > base + 60:
                    # Кадр сделан где-то во время запроса — берём середину
                    found = ((t1 + t2) / 2 - t0) * 1000
                    break
            if found is None:
                raise ObsError("Не увидел сигнал с телефона. Стрим идёт? Открыта игра (а не заставка)?")
            results.append(found)
            time.sleep(1.5)   # дать белому кадру пропасть

        results.sort()
        delay = int(round(results[len(results) // 2]))
        offset = max(0, min(20000, delay))
        for name in mics:
            obs.request("SetInputAudioSyncOffset", {"inputName": name, "inputAudioSyncOffset": offset})
        return offset, mics
    finally:
        obs.close()


def obs_running(port=4455):
    """Запущен ли OBS с включённым WebSocket (порт открыт)."""
    try:
        socket.create_connection(("127.0.0.1", port), timeout=1).close()
        return True
    except OSError:
        return False
