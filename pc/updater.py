"""Автообновление GameStream PC с GitHub: проверка, скачивание и замена файлов после закрытия программы."""

import json
import os
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

try:
    from version import VERSION
except ImportError:
    VERSION = 0

REPO = "magaasadovvv-coder/GameStream"
ASSET = "GameStreamPC.zip"
UA = {"User-Agent": "GameStreamPC"}


class UpdateError(Exception):
    pass


def can_update():
    """Обновляться может только собранная программа (exe), а не запуск из исходников."""
    return getattr(sys, "frozen", False) and os.name == "nt" and VERSION > 0


def check():
    """(номер новой версии, ссылка) или None, если обновлений нет."""
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases/latest",
                                 headers={**UA, "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        data = json.load(r)
    try:
        version = int(str(data["tag_name"]).rsplit(".", 1)[-1])
    except (KeyError, ValueError):
        return None
    if version <= VERSION:
        return None
    for asset in data.get("assets", []):
        if asset.get("name") == ASSET:
            return version, asset["browser_download_url"]
    return None


def _ps_quote(path):
    return "'" + path.replace("'", "''") + "'"


def download_and_prepare(url, progress=lambda p: None):
    """Скачивает и распаковывает новую версию во временную папку. Возвращает путь к скрипту замены."""
    tmp = tempfile.mkdtemp(prefix="GameStreamPC-update-")
    zpath = os.path.join(tmp, ASSET)
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r, open(zpath, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(256 * 1024)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                progress(int(done * 100 / total))
    new_dir = os.path.join(tmp, "new")
    with zipfile.ZipFile(zpath) as z:
        z.extractall(new_dir)
    if not os.path.isfile(os.path.join(new_dir, "GameStreamPC.exe")):
        raise UpdateError("В архиве обновления нет GameStreamPC.exe")

    install = os.path.dirname(sys.executable)
    script = os.path.join(tmp, "update.ps1")
    # Ждём, пока программа закроется, копируем новые файлы поверх, запускаем снова, убираем за собой
    lines = [
        "$ErrorActionPreference = 'SilentlyContinue'",
        f"Wait-Process -Id {os.getpid()} -Timeout 30",
        "Start-Sleep -Milliseconds 700",
        f"& robocopy {_ps_quote(new_dir)} {_ps_quote(install)} /E /R:5 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null",
        f"Start-Process -FilePath (Join-Path {_ps_quote(install)} 'GameStreamPC.exe')",
        "Start-Sleep -Seconds 2",
        f"Remove-Item -LiteralPath {_ps_quote(tmp)} -Recurse -Force",
    ]
    with open(script, "w", encoding="utf-8-sig") as f:
        f.write("\r\n".join(lines) + "\r\n")
    return script


def run_script(script):
    """Запускает замену файлов отдельным процессом. После этого программу нужно закрыть."""
    flags = 0x00000008 | 0x00000200 | 0x08000000  # DETACHED_PROCESS | NEW_PROCESS_GROUP | NO_WINDOW
    subprocess.Popen(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script],
        creationflags=flags, close_fds=True,
    )
