from __future__ import annotations
import hashlib
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

from app.map_upload import compact_map_bytes, router


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
        self.env = patch.dict(os.environ, {
            "MAP_UPLOAD_TOKEN": "test-token",
            "MAP_PUBLIC_BASE_URL": "http://47.103.157.213",
            "MAP_STATIC_ROOT": self.temp.name,
        })
        self.env.start()
        self.client = TestClient(make_app())
        self.map = {"map_id": 2, "version": 1, "map_name": "测试", "regions": [], "paths": [], "points": []}

    def tearDown(self):
        self.client.close(); self.env.stop(); self.temp.cleanup()

    def payload(self, map_data=None, **updates):
        map_data = self.map if map_data is None else map_data
        content = compact_map_bytes(map_data)
        body = {"productType": "crawler", "deviceId": "crawler_00000001", "mapId": 2,
                "mapVersion": 1, "mapName": "example", "checksum": "sha256:" + hashlib.sha256(content).hexdigest(),
                "fileSizeBytes": len(content), "map": map_data}
        body.update(updates); return body

    def post(self, body=None, token="test-token"):
        headers = {} if token is None else {"Authorization": f"Bearer {token}"}
        return self.client.post("/api/maps/upload", json=body or self.payload(), headers=headers)

    def test_success_exact_response_and_only_map_is_saved(self):
        response = self.post(); self.assertEqual(200, response.status_code)
        content = compact_map_bytes(self.map); checksum = "sha256:" + hashlib.sha256(content).hexdigest()
        self.assertEqual({"mapJsonUrl": "http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json",
                          "fileSizeBytes": len(content), "checksum": checksum}, response.json())
        saved = Path(self.temp.name, "crawler", "crawler_00000001", "map_2_v1.json").read_bytes()
        self.assertEqual(content, saved); self.assertNotIn(b"productType", saved)

    def test_missing_or_wrong_token_is_401(self):
        self.assertEqual(401, self.post(token=None).status_code)
        self.assertEqual(401, self.post(token="wrong").status_code)

    def test_missing_field_unsafe_path_and_negative_values_are_400(self):
        body = self.payload(); del body["checksum"]; self.assertEqual(400, self.post(body).status_code)
        self.assertEqual(400, self.post(self.payload(productType="../bad")).status_code)
        self.assertEqual(400, self.post(self.payload(mapId=-1)).status_code)
        self.assertEqual(400, self.post(self.payload(mapVersion=-1)).status_code)

    def test_inner_outer_identity_mismatch_is_400(self):
        self.assertEqual(400, self.post(self.payload(mapId=3)).status_code)
        changed = dict(self.map, version=2)
        self.assertEqual(400, self.post(self.payload(changed)).status_code)

    def test_size_and_checksum_mismatch_are_400(self):
        self.assertEqual(400, self.post(self.payload(fileSizeBytes=999)).status_code)
        self.assertEqual(400, self.post(self.payload(checksum="sha256:" + "0" * 64)).status_code)

    def test_same_content_is_idempotent_but_changed_content_conflicts(self):
        self.assertEqual(200, self.post().status_code); self.assertEqual(200, self.post().status_code)
        changed = dict(self.map, map_name="different")
        self.assertEqual(409, self.post(self.payload(changed)).status_code)

    def test_atomic_replace_failure_leaves_no_target_or_temp_file(self):
        with patch("app.map_upload.os.replace", side_effect=OSError("disk failure")):
            self.assertEqual(500, self.post().status_code)
        directory = Path(self.temp.name, "crawler", "crawler_00000001")
        self.assertFalse((directory / "map_2_v1.json").exists())
        self.assertEqual([], list(directory.glob("*.tmp")))


if __name__ == "__main__": unittest.main()