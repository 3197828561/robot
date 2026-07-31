"""Authenticated, byte-preserving storage for Robot map JSON uploads."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import tempfile
import threading
from pathlib import Path
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, StrictInt, ValidationError, field_validator

SAFE_PATH_COMPONENT = re.compile(r"^[A-Za-z0-9_.-]+$")
SHA256_CHECKSUM = re.compile(r"^sha256:[0-9a-fA-F]{64}$")
DEFAULT_STATIC_ROOT = "/opt/robot-platform/static/maps"
MAX_UPLOAD_BYTES = 32 * 1024 * 1024

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


def _skip_whitespace(raw: bytes, index: int) -> int:
    while index < len(raw) and raw[index] in b" \t\r\n":
        index += 1
    return index


def _skip_json_string(raw: bytes, index: int) -> int:
    if index >= len(raw) or raw[index] != ord('"'):
        raise ValueError("expected JSON string")
    index += 1
    escaped = False
    while index < len(raw):
        byte = raw[index]
        if escaped:
            escaped = False
        elif byte == ord("\\"):
            escaped = True
        elif byte == ord('"'):
            return index + 1
        index += 1
    raise ValueError("unterminated JSON string")


def _skip_json_value(raw: bytes, index: int) -> int:
    index = _skip_whitespace(raw, index)
    if index >= len(raw):
        raise ValueError("missing JSON value")
    if raw[index] == ord('"'):
        return _skip_json_string(raw, index)
    if raw[index] not in (ord("{"), ord("[")):
        while index < len(raw) and raw[index] not in b",}":
            index += 1
        return index

    stack = [raw[index]]
    index += 1
    while index < len(raw) and stack:
        byte = raw[index]
        if byte == ord('"'):
            index = _skip_json_string(raw, index)
            continue
        if byte in (ord("{"), ord("[")):
            stack.append(byte)
        elif byte == ord("}"):
            if stack[-1] != ord("{"):
                raise ValueError("mismatched JSON brackets")
            stack.pop()
        elif byte == ord("]"):
            if stack[-1] != ord("["):
                raise ValueError("mismatched JSON brackets")
            stack.pop()
        index += 1
    if stack:
        raise ValueError("unterminated JSON value")
    return index


def extract_map_content(raw_body: bytes) -> tuple[dict[str, Any], bytes]:
    if not raw_body or len(raw_body) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=400, detail="invalid upload body size")
    try:
        payload = json.loads(raw_body)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail="invalid JSON upload envelope") from exc
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="upload envelope must be an object")

    try:
        index = _skip_whitespace(raw_body, 0)
        if index >= len(raw_body) or raw_body[index] != ord("{"):
            raise ValueError("expected object")
        index = _skip_whitespace(raw_body, index + 1)
        map_content: bytes | None = None
        while index < len(raw_body) and raw_body[index] != ord("}"):
            key_start = index
            key_end = _skip_json_string(raw_body, key_start)
            key = json.loads(raw_body[key_start:key_end])
            index = _skip_whitespace(raw_body, key_end)
            if index >= len(raw_body) or raw_body[index] != ord(":"):
                raise ValueError("expected colon")
            value_start = _skip_whitespace(raw_body, index + 1)
            value_end = _skip_json_value(raw_body, value_start)
            index = _skip_whitespace(raw_body, value_end)
            if key == "map":
                if map_content is not None or index >= len(raw_body) or raw_body[index] != ord("}"):
                    raise ValueError("map must be the final and unique field")
                # Robot embeds the source file bytes directly. Whitespace between the
                # parsed map value and the envelope's final brace therefore belongs
                # to the source file too (for example, its trailing newline).
                map_content = raw_body[value_start:index]
            if index < len(raw_body) and raw_body[index] == ord(","):
                index = _skip_whitespace(raw_body, index + 1)
            elif index < len(raw_body) and raw_body[index] == ord("}"):
                break
            else:
                raise ValueError("invalid object separator")
        if map_content is None:
            raise ValueError("missing map field")
        index = _skip_whitespace(raw_body, index + 1)
        if index != len(raw_body):
            raise ValueError("trailing data")
        map_object = json.loads(map_content)
        if not isinstance(map_object, dict) or map_object != payload.get("map"):
            raise ValueError("map payload mismatch")
        return payload, map_content
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail="invalid upload envelope") from exc


def require_upload_token(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(upload_bearer)],
) -> None:
    expected = os.getenv("MAP_UPLOAD_TOKEN", "")
    if (
        not expected
        or credentials is None
        or credentials.scheme.lower() != "bearer"
        or not hmac.compare_digest(credentials.credentials, expected)
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid map upload token",
            headers={"WWW-Authenticate": "Bearer"},
        )


def _validate_map_identity(body: MapUploadRequest) -> None:
    inner_id = body.map.get("map_id")
    inner_version = body.map.get("version")
    if type(inner_id) is not int or type(inner_version) is not int:
        raise HTTPException(status_code=400, detail="map.map_id and map.version must be integers")
    if inner_id < 0 or inner_version < 0:
        raise HTTPException(status_code=400, detail="map.map_id and map.version must be non-negative")
    if inner_id != body.map_id or inner_version != body.map_version:
        raise HTTPException(status_code=400, detail="outer and inner map ID/version do not match")


def _response(body: MapUploadRequest, size: int, checksum: str) -> MapUploadResponse:
    base_url = os.environ["MAP_PUBLIC_BASE_URL"].rstrip("/")
    relative_url = (
        f"maps/{body.product_type}/{body.device_id}/"
        f"map_{body.map_id}_v{body.map_version}.json"
    )
    return MapUploadResponse(
        mapJsonUrl=f"{base_url}/{relative_url}",
        fileSizeBytes=size,
        checksum=checksum,
    )


@router.post("/upload", response_model=MapUploadResponse, response_model_by_alias=True)
async def upload_map(
    request: Request,
    _: Annotated[None, Depends(require_upload_token)],
) -> MapUploadResponse:
    payload, content = extract_map_content(await request.body())
    try:
        body = MapUploadRequest.model_validate(payload)
    except ValidationError as exc:
        raise HTTPException(status_code=400, detail=exc.errors(include_context=False)) from exc

    _validate_map_identity(body)
    actual_size = len(content)
    actual_checksum = f"sha256:{hashlib.sha256(content).hexdigest()}"
    if body.file_size_bytes != actual_size:
        raise HTTPException(status_code=400, detail="fileSizeBytes does not match original map bytes")
    if not hmac.compare_digest(body.checksum, actual_checksum):
        raise HTTPException(status_code=400, detail="checksum does not match original map bytes")

    root = Path(os.getenv("MAP_STATIC_ROOT", DEFAULT_STATIC_ROOT))
    destination = (
        root
        / body.product_type
        / body.device_id
        / f"map_{body.map_id}_v{body.map_version}.json"
    )
    temp_path: Path | None = None
    try:
        with _write_lock:
            if destination.exists():
                existing = destination.read_bytes()
                existing_checksum = f"sha256:{hashlib.sha256(existing).hexdigest()}"
                if not hmac.compare_digest(existing_checksum, actual_checksum):
                    raise HTTPException(
                        status_code=409,
                        detail="map ID/version already exists with different content",
                    )
                return _response(body, len(existing), existing_checksum)

            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(
                mode="wb",
                prefix=f".{destination.name}.",
                suffix=".tmp",
                dir=destination.parent,
                delete=False,
            ) as temp_file:
                temp_path = Path(temp_file.name)
                temp_file.write(content)
                temp_file.flush()
                os.fsync(temp_file.fileno())
            os.replace(temp_path, destination)
            temp_path = None
    except HTTPException:
        raise
    except OSError as exc:
        raise HTTPException(status_code=500, detail="failed to persist map") from exc
    finally:
        if temp_path is not None:
            try:
                temp_path.unlink(missing_ok=True)
            except OSError:
                pass

    return _response(body, actual_size, actual_checksum)
