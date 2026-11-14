"""Advertise an installed Tailscale IPv4 without exposing any account or peer metadata."""
import ipaddress
import os
from pathlib import Path
import socket
import subprocess
import threading
import time

_NETWORK = ipaddress.ip_network('100.64.0.0/10')
_lock = threading.Lock()
_cached = None
_expires = 0.0


def _select(values):
    for value in values:
        try:
            address = ipaddress.ip_address(value.strip())
        except ValueError:
            continue
        if address.version == 4 and address in _NETWORK:
            return str(address)
    return None


def discover_ipv4():
    try:
        address = _select(socket.gethostbyname_ex(socket.gethostname())[2])
        if address:
            return address
    except OSError:
        pass
    # Windows hostname DNS does not necessarily include the Tailscale adapter.
    # Ask the installed official CLI for this computer's IP only, never status/account data.
    binary = Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'Tailscale' / 'tailscale.exe'
    if not binary.is_file():
        return None
    try:
        result = subprocess.run([str(binary), 'ip', '-4'], capture_output=True, text=True,
                                timeout=2, check=False,
                                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        return _select(result.stdout.splitlines()) if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def overlay_endpoint(port):
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError('INVALID_OVERLAY_PORT')
    global _cached, _expires
    with _lock:
        now = time.monotonic()
        if now >= _expires:
            _cached = discover_ipv4()
            _expires = now + (60 if _cached else 5)
        return {'overlayEndpoint': f'https://{_cached}:{port}'} if _cached else {}
