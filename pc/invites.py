"""Ключи-приглашения для приложения GameStream на телефоне.

Ключ подписывается секретным ключом владельца (файл GameStream-owner-key.pem).
Без этого файла создать рабочий ключ невозможно, поэтому держи его только у себя.
"""

import base64
import os
import re
import struct
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

PREFIX = "GS1-"


class InviteError(Exception):
    pass


def parse_device_code(text):
    """«1A2B-3C4D-5E6F-7A8B» → 8 байт. Принимает с дефисами, пробелами, в любом регистре."""
    hexpart = re.sub(r"[^0-9a-fA-F]", "", text or "")
    if len(hexpart) != 16:
        raise InviteError("Код устройства — это 16 символов вида 1A2B-3C4D-5E6F-7A8B")
    return bytes.fromhex(hexpart)


def load_owner_key(pem_bytes):
    try:
        key = serialization.load_pem_private_key(pem_bytes, password=None)
    except Exception:
        raise InviteError("Это не ключ владельца GameStream")
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise InviteError("Это не ключ владельца GameStream")
    return key


def make_invite(owner_key, device_code, days=0):
    """Создаёт ключ для телефона. days=0 — бессрочно."""
    device = parse_device_code(device_code)
    expires = 0 if not days else int(time.time() // 86400) + int(days)
    payload = bytes([1]) + device + struct.pack(">I", expires)
    der = owner_key.sign(payload, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    raw = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    return PREFIX + base64.urlsafe_b64encode(payload + raw).decode().rstrip("=")


def owner_key_path(folder):
    return os.path.join(folder, "GameStream-owner-key.pem")
