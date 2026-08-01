import os
import unittest


os.environ.setdefault("POSTGRES_USER", "vgsolar")
os.environ.setdefault("POSTGRES_PASSWORD", "test-password")
os.environ.setdefault("POSTGRES_DB", "vgsolar")
os.environ.setdefault("JWT_SECRET", "test-jwt-secret-at-least-32-characters")
os.environ.setdefault("API_BOOTSTRAP_EMAIL", "test@vgsolar.com")
os.environ.setdefault("API_BOOTSTRAP_PASSWORD", "Test123456!")
os.environ.setdefault("MAP_PUBLIC_BASE_URL", "http://127.0.0.1")
os.environ.setdefault("MAP_UPLOAD_TOKEN", "test-map-upload-token")

from app.main import hash_password, verify_password


class PasswordHashingTests(unittest.TestCase):
    def test_hash_and_verify_bcrypt_password(self):
        password = "Test123456!"
        password_hash = hash_password(password)

        self.assertTrue(password_hash.startswith("$2b$"))
        self.assertTrue(verify_password(password, password_hash))
        self.assertFalse(verify_password("wrong-password", password_hash))


if __name__ == "__main__":
    unittest.main()
