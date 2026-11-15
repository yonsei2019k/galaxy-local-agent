"""Optional explicit comparison profile. No model discovery, download or default selection."""
from pathlib import Path

FAST_4B_ALIAS = 'pc-text-4b'
FAST_4B_SHA256 = '217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14'
FAST_4B_PATH_ENV = 'ZIPHAP_PC_TEXT_4B_MODEL_PATH'


def configured_fast4b_models(environ):
    """Host deployment configuration only; a configured path is not proof of a verified load."""
    raw = environ.get(FAST_4B_PATH_ENV)
    if not isinstance(raw, str) or not 1 <= len(raw) <= 4096 or raw != raw.strip() or any(ord(c) < 32 for c in raw):
        return {}
    if raw.startswith(('\\\\', '//')):
        return {}
    path = Path(raw)
    if not path.is_absolute():
        return {}
    return {FAST_4B_ALIAS: (path, FAST_4B_SHA256)}
