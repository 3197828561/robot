from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.testclient import TestClient

from app.map_upload import router


def make_app() -> FastAPI:
    app = FastAPI()

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        return JSONResponse(status_code=400, content={"detail": jsonable_encoder(exc.errors())})

    app.include_router(router)
    return app


class MapUploadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(
            os.environ,
            {
                "MAP_UPLOAD_TOKEN": "test-token",
                "MAP_PUBLIC_BASE_URL": "http://47.103.157.213",
                "MAP_STATIC_ROOT": self.temp.name,
            },
        )
        self.env.start()
        self.client = TestClient(make_app())
        self.map_bytes = """{
  "map_id": 2,
  "version": 1,
  "map_name": "测试地图",
  "metadata": {"map": "nested marker is safe"},
  "regions": [],
  "paths": [],
  "points": []
}""".encode("utf-8")

    def tearDown(self):
        self.client.close()
        self.env.stop()
        self.temp.cleanup()

    def request_bytes(self, map_bytes: bytes | None = None, **updates) -> bytes:
        original_map = self.map_bytes if map_bytes is None else map_bytes
        metadata = {
            "productType": "crawler",
            "deviceId": "crawler_00000001",
            "mapId": 2,
            "mapVersion": 1,
            "mapName": "example",
            "checksum": "sha256:" + hashlib.sha256(original_map).hexdigest(),
            "fileSizeBytes": len(original_map),
        }
        metadata.update(updates)
        prefix = json.dumps(metadata, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return prefix[:-1] + b',"map":' + original_map + b"}"

    def post(self, body: bytes | None = None, token: str | None = "test-token"):
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        return self.client.post(
            "/api/maps/upload",
            content=self.request_bytes() if body is None else body,
            headers=headers,
        )

    def target(self) -> Path:
        return Path(
            self.temp.name,
            "crawler",
            "crawler_00000001",
            "map_2_v1.json",
        )

    def test_success_preserves_exact_original_map_bytes(self):
        response = self.post()
        self.assertEqual(200, response.status_code)
        checksum = "sha256:" + hashlib.sha256(self.map_bytes).hexdigest()
        self.assertEqual(
            {
                "mapJsonUrl": "http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json",
                "fileSizeBytes": len(self.map_bytes),
                "checksum": checksum,
            },
            response.json(),
        )
        self.assertEqual(self.map_bytes, self.target().read_bytes())
        self.assertIn("测试地图", self.target().read_text(encoding="utf-8"))

    def test_trailing_newline_is_preserved_and_hashed(self):
        source = self.map_bytes + b"\n"
        response = self.post(self.request_bytes(source))
        self.assertEqual(200, response.status_code)
        self.assertEqual(source, self.target().read_bytes())
        self.assertEqual(
            "sha256:" + hashlib.sha256(source).hexdigest(),
            response.json()["checksum"],
        )

    def test_missing_or_wrong_token_is_401(self):
        self.assertEqual(401, self.post(token=None).status_code)
        self.assertEqual(401, self.post(token="wrong").status_code)

    def test_missing_field_unsafe_path_and_negative_values_are_400(self):
        self.assertEqual(400, self.post(self.request_bytes(checksum=None)).status_code)
        self.assertEqual(400, self.post(self.request_bytes(productType="../bad")).status_code)
        self.assertEqual(400, self.post(self.request_bytes(mapId=-1)).status_code)
        self.assertEqual(400, self.post(self.request_bytes(mapVersion=-1)).status_code)

    def test_inner_outer_identity_mismatch_is_400(self):
        self.assertEqual(400, self.post(self.request_bytes(mapId=3)).status_code)
        changed = self.map_bytes.replace(b'"version": 1', b'"version": 2')
        self.assertEqual(400, self.post(self.request_bytes(changed)).status_code)

    def test_size_and_checksum_are_checked_against_original_bytes(self):
        self.assertEqual(400, self.post(self.request_bytes(fileSizeBytes=999)).status_code)
        wrong = "sha256:" + "0" * 64
        self.assertEqual(400, self.post(self.request_bytes(checksum=wrong)).status_code)

    def test_map_must_be_final_and_unique(self):
        valid = self.request_bytes()
        not_final = valid[:-1] + b',"after":true}'
        self.assertEqual(400, self.post(not_final).status_code)
        duplicate = valid[:-1] + b',"map":{}}'
        self.assertEqual(400, self.post(duplicate).status_code)

    def test_same_bytes_are_idempotent_but_changed_bytes_conflict(self):
        self.assertEqual(200, self.post().status_code)
        self.assertEqual(200, self.post().status_code)
        changed = self.map_bytes.replace("测试地图".encode(), "另一地图".encode())
        self.assertEqual(409, self.post(self.request_bytes(changed)).status_code)

    def test_formatting_difference_is_a_content_conflict(self):
        self.assertEqual(200, self.post().status_code)
        compact = json.dumps(
            json.loads(self.map_bytes),
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        self.assertNotEqual(self.map_bytes, compact)
        self.assertEqual(409, self.post(self.request_bytes(compact)).status_code)

    def test_atomic_replace_failure_leaves_no_target_or_temp_file(self):
        with patch("app.map_upload.os.replace", side_effect=OSError("disk failure")):
            self.assertEqual(500, self.post().status_code)
        directory = self.target().parent
        self.assertFalse(self.target().exists())
        self.assertEqual([], list(directory.glob("*.tmp")))


if __name__ == "__main__":
    unittest.main()
