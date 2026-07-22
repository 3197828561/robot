"""Authenticated, atomic storage for Robot map JSON uploads."""
from __future__ import annotations
import hashlib, hmac, json, os, re, tempfile, threading
from pathlib import Path
from typing import Annotated, Any
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, StrictInt, field_validator

SAFE_PATH_COMPONENT = re.compile(r"^[A-Za-z0-9_.-]+$")
SHA256_CHECKSUM = re.compile(r"^sha256:[0-9a-fA-F]{64}$")
DEFAULT_STATIC_ROOT = "/opt/robot-platform/static/maps"
router = APIRouter(prefix="/api/maps", tags=["maps"])
upload_bearer = HTTPBearer(auto_error=False)
_write_lock = threading.Lock()

class MapUploadRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    product_type: str = Field(alias="productType", min_length=1)
    device_id: str = Field(alias="deviceId", min_length=1)
    map_id: StrictInt = Field(alias="mapId", ge=0)
    map_version: StrictInt = Field(alias="mapVersion", ge=0)
    map_name: str | None = Field(default=None, alias="mapName")
    checksum: str
    file_size_bytes: StrictInt = Field(alias="fileSizeBytes", ge=0)
    map: dict[str, Any]

    @field_validator("product_type", "device_id")
    @classmethod
    def validate_path_component(cls, value: str) -> str:
        if not SAFE_PATH_COMPONENT.fullmatch(value) or value in {".", ".."}:
            raise ValueError("must contain only letters, digits, '.', '_' or '-'")
        return value

    @field_validator("checksum")
    @classmethod
    def validate_checksum(cls, value: str) -> str:
        if not SHA256_CHECKSUM.fullmatch(value):
            raise ValueError("must use sha256:<64 hexadecimal characters>")
        return value.lower()

class MapUploadResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    map_json_url: str = Field(alias="mapJsonUrl")
    file_size_bytes: int = Field(alias="fileSizeBytes")
    checksum: str

def compact_map_bytes(map_data: dict[str, Any]) -> bytes:
    try:
        return json.dumps(map_data, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail="map must be valid JSON") from exc

def require_upload_token(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(upload_bearer)]) -> None:
    expected = os.getenv("MAP_UPLOAD_TOKEN", "")
    if (not expected or credentials is None or credentials.scheme.lower() != "bearer"
            or not hmac.compare_digest(credentials.credentials, expected)):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid map upload token",
                            headers={"WWW-Authenticate": "Bearer"})

def _validate_map_identity(body: MapUploadRequest) -> None:
    inner_id, inner_version = body.map.get("map_id"), body.map.get("version")
    if type(inner_id) is not int or type(inner_version) is not int:
        raise HTTPException(status_code=400, detail="map.map_id and map.version must be integers")
    if inner_id < 0 or inner_version < 0:
        raise HTTPException(status_code=400, detail="map.map_id and map.version must be non-negative")
    if inner_id != body.map_id or inner_version != body.map_version:
        raise HTTPException(status_code=400, detail="outer and inner map ID/version do not match")

def _response(body: MapUploadRequest, size: int, checksum: str) -> MapUploadResponse:
    base_url = os.environ["MAP_PUBLIC_BASE_URL"].rstrip("/")
    relative_url = f"maps/{body.product_type}/{body.device_id}/map_{body.map_id}_v{body.map_version}.json"
    return MapUploadResponse(mapJsonUrl=f"{base_url}/{relative_url}", fileSizeBytes=size, checksum=checksum)

@router.post("/upload", response_model=MapUploadResponse, response_model_by_alias=True)
def upload_map(body: MapUploadRequest, _: Annotated[None, Depends(require_upload_token)]) -> MapUploadResponse:
    _validate_map_identity(body)
    content = compact_map_bytes(body.map)
    actual_size = len(content)
    actual_checksum = f"sha256:{hashlib.sha256(content).hexdigest()}"
    if body.file_size_bytes != actual_size:
        raise HTTPException(status_code=400, detail="fileSizeBytes does not match serialized map")
    if not hmac.compare_digest(body.checksum, actual_checksum):
        raise HTTPException(status_code=400, detail="checksum does not match serialized map")
    root = Path(os.getenv("MAP_STATIC_ROOT", DEFAULT_STATIC_ROOT))
    destination = root / body.product_type / body.device_id / f"map_{body.map_id}_v{body.map_version}.json"
    temp_path: Path | None = None
    try:
        with _write_lock:
            if destination.exists():
                existing = destination.read_bytes()
                existing_checksum = f"sha256:{hashlib.sha256(existing).hexdigest()}"
                if not hmac.compare_digest(existing_checksum, actual_checksum):
                    raise HTTPException(status_code=409, detail="map ID/version already exists with different content")
                return _response(body, len(existing), existing_checksum)
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(mode="wb", prefix=f".{destination.name}.", suffix=".tmp",
                                             dir=destination.parent, delete=False) as temp_file:
                temp_path = Path(temp_file.name)
                temp_file.write(content); temp_file.flush(); os.fsync(temp_file.fileno())
            os.replace(temp_path, destination); temp_path = None
    except HTTPException:
        raise
    except OSError as exc:
        raise HTTPException(status_code=500, detail="failed to persist map") from exc
    finally:
        if temp_path is not None:
            try: temp_path.unlink(missing_ok=True)
            except OSError: pass
    return _response(body, actual_size, actual_checksum)
