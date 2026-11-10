"""
GameStream PC — компаньон для приложения GameStream на телефоне.

• Сообщает телефону в Wi‑Fi сети, где находится ПК (телефон находит его сам).
• Одной кнопкой добавляет в OBS источник, который принимает картинку и звук игры с телефона.
Нагрузка (микрофон, вебка, оверлеи, отправка на YouTube) остаётся на ПК, в OBS.
"""

import socket
import threading
import time
import tkinter as tk
from tkinter import ttk

from obs_ws import ObsError, add_phone_source

DISCOVERY_PORT = 47800
STREAM_PORT = 9000
SRT_URL = f"srt://0.0.0.0:{STREAM_PORT}?mode=listener&latency=200000"

BG = "#101016"
CARD = "#1e1e28"
FG = "#ffffff"
DIM = "#a0a0af"
ACCENT = "#e6283c"


def local_ips():
    """IP-адреса этого ПК в локальной сети."""
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
    return sorted(ip for ip in ips if not ip.startswith("127."))


def beacon_loop(stop_event):
    """Раз в секунду кричит в сеть «я здесь» — телефон ловит это и узнаёт IP."""
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


class App:
    def __init__(self, root):
        self.root = root
        root.title("GameStream PC")
        root.configure(bg=BG)
        root.geometry("520x640")
        root.minsize(460, 560)

        self.stop_event = threading.Event()
        threading.Thread(target=beacon_loop, args=(self.stop_event,), daemon=True).start()

        pad = {"padx": 18}
        tk.Label(root, text="● GameStream PC", fg=ACCENT, bg=BG, font=("Segoe UI", 20, "bold")).pack(anchor="w", pady=(18, 0), **pad)
        tk.Label(root, text="Картинка и звук игры — с телефона, всё остальное — в OBS на ПК",
                 fg=DIM, bg=BG, font=("Segoe UI", 10)).pack(anchor="w", **pad)

        # Статус
        card = tk.Frame(root, bg=CARD)
        card.pack(fill="x", pady=(16, 0), **pad)
        ips = local_ips()
        tk.Label(card, text="📡 ПК виден телефонам в сети", fg="#5fd38d", bg=CARD,
                 font=("Segoe UI", 11, "bold")).pack(anchor="w", padx=12, pady=(10, 2))
        tk.Label(card, text="IP этого ПК: " + (", ".join(ips) if ips else "не определён"),
                 fg=FG, bg=CARD, font=("Segoe UI", 11)).pack(anchor="w", padx=12)
        tk.Label(card, text="В приложении на телефоне: «На ПК» → «Найти ПК автоматически»",
                 fg=DIM, bg=CARD, font=("Segoe UI", 9)).pack(anchor="w", padx=12, pady=(2, 10))

        # OBS
        tk.Label(root, text="Настройка OBS (один раз)", fg=FG, bg=BG,
                 font=("Segoe UI", 13, "bold")).pack(anchor="w", pady=(18, 4), **pad)
        tk.Label(root, justify="left", wraplength=470, fg=DIM, bg=BG, font=("Segoe UI", 10), text=(
            "1. Открой OBS → Сервис → Настройки сервера WebSocket → «Включить сервер WebSocket».\n"
            "2. Если там стоит пароль — впиши его ниже.\n"
            "3. Нажми кнопку — источник с телефона появится в текущей сцене."
        )).pack(anchor="w", **pad)

        row = tk.Frame(root, bg=BG)
        row.pack(fill="x", pady=(10, 0), **pad)
        tk.Label(row, text="Пароль WebSocket:", fg=FG, bg=BG, font=("Segoe UI", 10)).pack(side="left")
        self.password = tk.Entry(row, show="•", font=("Segoe UI", 10))
        self.password.pack(side="left", fill="x", expand=True, padx=(8, 0))

        self.add_btn = tk.Button(root, text="➕  Добавить телефон в OBS", command=self.on_add,
                                 bg=ACCENT, fg="white", activebackground="#b81e30", activeforeground="white",
                                 relief="flat", font=("Segoe UI", 12, "bold"), cursor="hand2")
        self.add_btn.pack(fill="x", pady=(12, 0), ipady=8, **pad)
        self.status = tk.Label(root, text="", fg=DIM, bg=BG, wraplength=470, justify="left", font=("Segoe UI", 10))
        self.status.pack(anchor="w", pady=(6, 0), **pad)

        # Ручной вариант
        tk.Label(root, text="Или вручную", fg=FG, bg=BG, font=("Segoe UI", 13, "bold")).pack(anchor="w", pady=(16, 4), **pad)
        tk.Label(root, justify="left", wraplength=470, fg=DIM, bg=BG, font=("Segoe UI", 10), text=(
            "Источники → «+» → «Источник медиа» → снять «Локальный файл» → в поле «Ввод» вставить:"
        )).pack(anchor="w", **pad)
        url_row = tk.Frame(root, bg=BG)
        url_row.pack(fill="x", pady=(6, 0), **pad)
        url = ttk.Entry(url_row, font=("Consolas", 10))
        url.insert(0, SRT_URL)
        url.configure(state="readonly")
        url.pack(side="left", fill="x", expand=True)
        tk.Button(url_row, text="Копировать", command=lambda: self.copy(SRT_URL), relief="flat").pack(side="left", padx=(6, 0))

        tk.Label(root, justify="left", wraplength=470, fg=DIM, bg=BG, font=("Segoe UI", 9), text=(
            "Если Windows спросит про доступ OBS к сети — нажми «Разрешить». "
            "Микрофон и вебку добавляй в OBS как обычно, стрим на YouTube запускай кнопкой «Начать трансляцию»."
        )).pack(anchor="w", pady=(14, 0), **pad)

        root.protocol("WM_DELETE_WINDOW", self.on_close)

    def copy(self, text):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.status.configure(text="Скопировано", fg="#5fd38d")

    def on_add(self):
        self.add_btn.configure(state="disabled")
        self.status.configure(text="Подключаюсь к OBS…", fg=DIM)
        password = self.password.get()

        def work():
            try:
                msg, color = add_phone_source(SRT_URL, password), "#5fd38d"
            except ObsError as e:
                msg, color = str(e), "#ff7b7b"
            except Exception as e:  # noqa: BLE001 — показать любую ошибку пользователю
                msg, color = f"Ошибка: {e}", "#ff7b7b"
            self.root.after(0, lambda: self.done(msg, color))

        threading.Thread(target=work, daemon=True).start()

    def done(self, msg, color):
        self.add_btn.configure(state="normal")
        self.status.configure(text=msg, fg=color)

    def on_close(self):
        self.stop_event.set()
        self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    App(root)
    root.mainloop()
