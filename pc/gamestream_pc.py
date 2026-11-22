"""
GameStream PC — компаньон для приложения GameStream на телефоне.

• Сообщает телефону в сети (Wi‑Fi или USB), где находится ПК — телефон находит его сам.
• Одной кнопкой добавляет в OBS источник, который принимает картинку и звук игры с телефона.
• Сам открывает OBS, когда телефон начинает стрим.
• Меряет задержку картинки и выставляет такой же сдвиг микрофону — голос совпадает с игрой.
"""

import json
import os
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk

from obs_ws import ObsError, add_phone_source, measure_and_sync, obs_running

DISCOVERY_PORT = 47800   # ПК → телефон: «я здесь»
COMMAND_PORT = 47801     # ПК → телефон: команды (вспышка для замера)
PHONE_PORT = 47802       # телефон → ПК: «я стримлю»
STREAM_PORT = 9000
SRT_URL = f"srt://0.0.0.0:{STREAM_PORT}?mode=listener&latency=200000"
APP_NAME = "GameStreamPC"

BG = "#101016"
CARD = "#1e1e28"
FG = "#ffffff"
DIM = "#a0a0af"
ACCENT = "#e6283c"
OK = "#5fd38d"
BAD = "#ff7b7b"


# ---------- Настройки ----------

def settings_path():
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    folder = os.path.join(base, APP_NAME)
    os.makedirs(folder, exist_ok=True)
    return os.path.join(folder, "settings.json")


def load_settings():
    defaults = {"password": "", "autostart": False, "open_obs": True, "auto_sync": True}
    try:
        with open(settings_path(), encoding="utf-8") as f:
            defaults.update(json.load(f))
    except (OSError, ValueError):
        pass
    return defaults


def save_settings(s):
    try:
        with open(settings_path(), "w", encoding="utf-8") as f:
            json.dump(s, f, ensure_ascii=False)
    except OSError:
        pass


# ---------- Windows: автозапуск и поиск OBS ----------

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def set_autostart(enabled):
    """Запуск вместе с Windows (только для текущего пользователя). Возвращает текст ошибки или None."""
    if os.name != "nt":
        return "Автозапуск работает только в Windows"
    if not getattr(sys, "frozen", False):
        return "Автозапуск доступен только в собранной программе (exe)"
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            if enabled:
                winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, f'"{sys.executable}" --minimized')
            else:
                try:
                    winreg.DeleteValue(key, APP_NAME)
                except FileNotFoundError:
                    pass
        return None
    except OSError as e:
        return f"Не удалось изменить автозапуск: {e}"


def find_obs():
    """Путь к obs64.exe или None."""
    if os.name != "nt":
        return None
    candidates = []
    try:
        import winreg
        for root in (winreg.HKEY_LOCAL_MACHINE, winreg.HKEY_CURRENT_USER):
            for sub in (r"SOFTWARE\OBS Studio", r"SOFTWARE\WOW6432Node\OBS Studio"):
                try:
                    with winreg.OpenKey(root, sub) as key:
                        folder = winreg.QueryValueEx(key, "")[0]
                        candidates.append(os.path.join(folder, "bin", "64bit", "obs64.exe"))
                except OSError:
                    pass
    except ImportError:
        pass
    for env in ("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"):
        base = os.environ.get(env)
        if base:
            candidates.append(os.path.join(base, "obs-studio", "bin", "64bit", "obs64.exe"))
    steam = os.environ.get("ProgramFiles(x86)")
    if steam:
        candidates.append(os.path.join(steam, "Steam", "steamapps", "common", "OBS Studio", "bin", "64bit", "obs64.exe"))
    for path in candidates:
        if os.path.isfile(path):
            return path
    return None


def launch_obs():
    path = find_obs()
    if not path:
        return "OBS не найден — запусти его вручную"
    try:
        # OBS нужно запускать из его папки, иначе он не находит свои файлы
        subprocess.Popen([path, "--disable-shutdown-check"], cwd=os.path.dirname(path))
        return None
    except OSError as e:
        return f"Не удалось запустить OBS: {e}"


# ---------- Сеть ----------

def local_ips():
    """IP-адреса этого ПК во всех сетях (Wi‑Fi, кабель, USB-модем телефона)."""
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127.") and not ip.startswith("169.254."))


def beacon_loop(stop_event):
    """Раз в секунду сообщает в каждую сеть «я здесь» — телефон ловит это и узнаёт IP."""
    name = socket.gethostname()[:40].replace("|", " ")
    msg = f"GAMESTREAM_PC|{name}|{STREAM_PORT}".encode()
    while not stop_event.is_set():
        targets = {"255.255.255.255"}
        for ip in local_ips():
            parts = ip.split(".")
            targets.add(".".join(parts[:3] + ["255"]))
        for t in targets:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                s.sendto(msg, (t, DISCOVERY_PORT))
                s.close()
            except OSError:
                pass
        stop_event.wait(1.0)


class PhoneWatcher:
    """Слушает сигналы телефона: «начинаю стрим», «в эфире», «закончил»."""

    TIMEOUT = 6.0

    def __init__(self, on_event):
        self.on_event = on_event
        self.ip = None
        self.model = ""
        self.state = None
        self.last = 0.0
        self.stop_event = threading.Event()
        threading.Thread(target=self._listen, daemon=True).start()
        threading.Thread(target=self._timeout_loop, daemon=True).start()

    def _listen(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("", PHONE_PORT))
        except OSError:
            self.on_event("error", "Порт 47802 занят — GameStream PC уже запущен?")
            return
        sock.settimeout(1.0)
        while not self.stop_event.is_set():
            try:
                data, addr = sock.recvfrom(512)
            except socket.timeout:
                continue
            except OSError:
                continue
            parts = data.decode("utf-8", "replace").split("|")
            if len(parts) < 3 or parts[0] != "GAMESTREAM_PHONE":
                continue
            state = parts[2]
            changed = state != self.state or addr[0] != self.ip
            self.ip, self.model, self.last = addr[0], parts[1], time.time()
            if changed:
                self.state = state
                self.on_event(state, self.model)

    def _timeout_loop(self):
        while not self.stop_event.wait(1.0):
            if self.state in ("start", "live") and time.time() - self.last > self.TIMEOUT:
                self.state = "stop"
                self.on_event("stop", self.model)

    def send_flash(self):
        if not self.ip:
            raise ObsError("Телефон не на связи")
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.sendto(f"GS_FLASH|{int(time.time() * 1000)}".encode(), (self.ip, COMMAND_PORT))
        finally:
            s.close()

    def stop(self):
        self.stop_event.set()


# ---------- Окно ----------

class App:
    def __init__(self, root, minimized=False):
        self.root = root
        self.settings = load_settings()
        self.syncing = False
        self.synced_this_session = False

        root.title("GameStream PC")
        root.configure(bg=BG)
        root.geometry("540x800")
        root.minsize(480, 640)

        self.stop_event = threading.Event()
        threading.Thread(target=beacon_loop, args=(self.stop_event,), daemon=True).start()
        self.phone = PhoneWatcher(lambda ev, info: root.after(0, lambda: self.on_phone(ev, info)))

        pad = {"padx": 18}
        tk.Label(root, text="● GameStream PC", fg=ACCENT, bg=BG, font=("Segoe UI", 20, "bold")).pack(anchor="w", pady=(16, 0), **pad)
        tk.Label(root, text="Картинка и звук игры — с телефона, всё остальное — в OBS на ПК",
                 fg=DIM, bg=BG, font=("Segoe UI", 10)).pack(anchor="w", **pad)

        # Статус
        card = tk.Frame(root, bg=CARD)
        card.pack(fill="x", pady=(12, 0), **pad)
        ips = local_ips()
        tk.Label(card, text="📡 ПК виден телефонам в сети", fg=OK, bg=CARD,
                 font=("Segoe UI", 11, "bold")).pack(anchor="w", padx=12, pady=(10, 2))
        tk.Label(card, text="IP этого ПК: " + (", ".join(ips) if ips else "не определён"),
                 fg=FG, bg=CARD, font=("Segoe UI", 10), wraplength=480, justify="left").pack(anchor="w", padx=12)
        self.phone_label = tk.Label(card, text="📱 Телефон: ждёт начала стрима", fg=DIM, bg=CARD,
                                    font=("Segoe UI", 10, "bold"))
        self.phone_label.pack(anchor="w", padx=12, pady=(6, 10))

        # OBS
        tk.Label(root, text="OBS", fg=FG, bg=BG, font=("Segoe UI", 13, "bold")).pack(anchor="w", pady=(14, 2), **pad)
        tk.Label(root, justify="left", wraplength=490, fg=DIM, bg=BG, font=("Segoe UI", 9), text=(
            "Один раз: в OBS открой Сервис → Настройки сервера WebSocket → «Включить сервер WebSocket». "
            "Если там стоит пароль — впиши его ниже."
        )).pack(anchor="w", **pad)

        row = tk.Frame(root, bg=BG)
        row.pack(fill="x", pady=(8, 0), **pad)
        tk.Label(row, text="Пароль WebSocket:", fg=FG, bg=BG, font=("Segoe UI", 10)).pack(side="left")
        self.password = tk.Entry(row, show="•", font=("Segoe UI", 10))
        self.password.insert(0, self.settings.get("password", ""))
        self.password.pack(side="left", fill="x", expand=True, padx=(8, 0))
        self.password.bind("<FocusOut>", lambda e: self.save())

        btns = tk.Frame(root, bg=BG)
        btns.pack(fill="x", pady=(10, 0), **pad)
        self.add_btn = self.button(btns, "➕ Добавить телефон в OBS", self.on_add)
        self.add_btn.pack(side="left", fill="x", expand=True, ipady=6)
        self.sync_btn = self.button(btns, "🎙 Синхронизировать голос", self.on_sync)
        self.sync_btn.pack(side="left", fill="x", expand=True, ipady=6, padx=(8, 0))

        self.status = tk.Label(root, text="", fg=DIM, bg=BG, wraplength=490, justify="left", font=("Segoe UI", 10))
        self.status.pack(anchor="w", pady=(6, 0), **pad)

        # Автоматизация
        tk.Label(root, text="Автоматически", fg=FG, bg=BG, font=("Segoe UI", 13, "bold")).pack(anchor="w", pady=(14, 2), **pad)
        self.var_autostart = self.check("Запускать вместе с Windows (свёрнутым)", "autostart", self.on_autostart)
        self.var_open_obs = self.check("Открывать OBS, когда телефон начинает стрим", "open_obs")
        self.var_auto_sync = self.check("Синхронизировать голос при каждом старте стрима", "auto_sync")
        tk.Label(root, justify="left", wraplength=490, fg=DIM, bg=BG, font=("Segoe UI", 9), text=(
            "Синхронизация: телефон на долю секунды делает картинку стрима белой (на экране телефона "
            "этого не видно), программа замечает это в OBS, меряет задержку и ставит такой же сдвиг "
            "микрофону. Лучше делать в лобби игры, а не во время боя."
        )).pack(anchor="w", pady=(4, 0), **pad)

        # Ручной вариант
        tk.Label(root, text="Адрес для OBS вручную", fg=FG, bg=BG, font=("Segoe UI", 11, "bold")).pack(anchor="w", pady=(14, 2), **pad)
        url_row = tk.Frame(root, bg=BG)
        url_row.pack(fill="x", **pad)
        url = ttk.Entry(url_row, font=("Consolas", 9))
        url.insert(0, SRT_URL)
        url.configure(state="readonly")
        url.pack(side="left", fill="x", expand=True)
        tk.Button(url_row, text="Копировать", command=lambda: self.copy(SRT_URL), relief="flat").pack(side="left", padx=(6, 0))

        tk.Label(root, justify="left", wraplength=490, fg=DIM, bg=BG, font=("Segoe UI", 9), text=(
            "Если Windows спросит про доступ к сети для GameStreamPC и OBS — нажми «Разрешить» "
            "(частные сети). По USB: подключи телефон кабелем и включи на нём «USB-модем»."
        )).pack(anchor="w", pady=(12, 0), **pad)

        root.protocol("WM_DELETE_WINDOW", self.on_close)
        if minimized:
            root.after(100, root.iconify)

    # --- элементы ---

    def button(self, parent, text, cmd):
        return tk.Button(parent, text=text, command=cmd, bg=ACCENT, fg="white",
                         activebackground="#b81e30", activeforeground="white",
                         relief="flat", font=("Segoe UI", 10, "bold"), cursor="hand2")

    def check(self, text, key, extra=None):
        var = tk.BooleanVar(value=bool(self.settings.get(key)))

        def changed():
            self.settings[key] = var.get()
            self.save()
            if extra:
                extra(var.get())

        tk.Checkbutton(self.root, text=text, variable=var, command=changed, bg=BG, fg=FG,
                       selectcolor=CARD, activebackground=BG, activeforeground=FG,
                       font=("Segoe UI", 10), anchor="w").pack(fill="x", padx=14)
        return var

    def say(self, text, color=DIM):
        self.status.configure(text=text, fg=color)

    def save(self):
        self.settings["password"] = self.password.get()
        save_settings(self.settings)

    def copy(self, text):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.say("Скопировано", OK)

    def background(self, work, done):
        """Выполнить work() в фоне и показать результат через done(result, error)."""
        def run():
            try:
                result, error = work(), None
            except ObsError as e:
                result, error = None, str(e)
            except Exception as e:  # noqa: BLE001 — показать любую ошибку пользователю
                result, error = None, f"Ошибка: {e}"
            self.root.after(0, lambda: done(result, error))
        threading.Thread(target=run, daemon=True).start()

    # --- действия ---

    def on_autostart(self, enabled):
        err = set_autostart(enabled)
        if err:
            self.say(err, BAD)

    def on_add(self):
        self.save()
        self.add_btn.configure(state="disabled")
        self.say("Подключаюсь к OBS…")
        pw = self.password.get()

        def done(msg, err):
            self.add_btn.configure(state="normal")
            self.say(err or msg, BAD if err else OK)

        self.background(lambda: add_phone_source(SRT_URL, pw), done)

    def on_sync(self, auto=False):
        if self.syncing:
            return
        if self.phone.state != "live":
            if not auto:
                self.say("Сначала начни стрим на телефоне (режим «На ПК»)", BAD)
            return
        self.save()
        self.syncing = True
        self.sync_btn.configure(state="disabled")
        self.say("Синхронизирую голос…")
        pw = self.password.get()

        def log(m):
            self.root.after(0, lambda: self.say(m))

        def done(result, err):
            self.syncing = False
            self.sync_btn.configure(state="normal")
            if err:
                self.say("Синхронизация: " + err, BAD)
                return
            offset, mics = result
            self.synced_this_session = True
            self.say(f"✓ Готово: картинка приходит через {offset / 1000:.1f} с — "
                     f"микрофон ({', '.join(mics)}) задержан на столько же", OK)

        self.background(lambda: measure_and_sync(self.phone.send_flash, pw, log=log), done)

    def on_phone(self, event, info):
        if event == "error":
            self.say(info, BAD)
        elif event == "start":
            self.synced_this_session = False
            self.phone_label.configure(text=f"📱 Телефон {info}: подключается…", fg="#e6a23c")
            if self.settings.get("open_obs") and not obs_running():
                err = launch_obs()
                self.say(err or "Открываю OBS…", BAD if err else DIM)
                if not err:
                    self.prepare_obs_after_launch()
        elif event == "live":
            self.phone_label.configure(text=f"📱 Телефон {info}: в эфире ({self.phone.ip})", fg=OK)
            if self.settings.get("auto_sync") and not self.synced_this_session:
                # Даём картинке устояться, потом меряем
                self.root.after(5000, lambda: self.on_sync(auto=True))
        elif event == "stop":
            self.phone_label.configure(text="📱 Телефон: ждёт начала стрима", fg=DIM)

    def prepare_obs_after_launch(self):
        """Ждём, пока OBS откроется, и проверяем, что источник телефона на месте."""
        pw = self.password.get()

        def work():
            for _ in range(40):
                if obs_running():
                    time.sleep(2)   # WebSocket открывается чуть раньше, чем OBS готов
                    return add_phone_source(SRT_URL, pw)
                time.sleep(1)
            raise ObsError("OBS открылся, но WebSocket не отвечает — включи его в Сервис → Настройки сервера WebSocket")

        self.background(work, lambda msg, err: self.say(err or ("OBS готов. " + msg), BAD if err else OK))

    def on_close(self):
        self.save()
        self.stop_event.set()
        self.phone.stop()
        self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    App(root, minimized="--minimized" in sys.argv)
    root.mainloop()
