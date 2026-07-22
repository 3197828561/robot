from __future__ import annotations
import os
import unittest
from unittest.mock import patch
from app import preflight

class PreflightTests(unittest.TestCase):
    def test_map_environment_is_required_before_database_check(self):
        values = {name: "configured" for name in preflight.REQUIRED_ENV}
        values["MAP_UPLOAD_TOKEN"] = ""
        with patch.dict(os.environ, values, clear=True):
            self.assertEqual(2, preflight.main())

if __name__ == "__main__": unittest.main()