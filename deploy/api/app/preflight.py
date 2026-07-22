"""Container startup checks with clear deployment error messages."""

from __future__ import annotations

import os
import sys
import time

from sqlalchemy import URL, create_engine, text
from sqlalchemy.exc import SQLAlchemyError

REQUIRED_ENV = (
    "POSTGRES_USER",
    "POSTGRES_PASSWORD",
    "POSTGRES_DB",
    "JWT_SECRET",
    "API_BOOTSTRAP_EMAIL",
    "API_BOOTSTRAP_PASSWORD",
    "MAP_PUBLIC_BASE_URL",
    "MAP_UPLOAD_TOKEN",
)


def build_database_url() -> URL:
    return URL.create(
        "postgresql+psycopg",
        username=os.environ["POSTGRES_USER"],
        password=os.environ["POSTGRES_PASSWORD"],
        host=os.getenv("POSTGRES_HOST", "postgres"),
        port=int(os.getenv("POSTGRES_PORT", "5432")),
        database=os.environ["POSTGRES_DB"],
    )


def main() -> int:
    missing = [name for name in REQUIRED_ENV if not os.getenv(name)]
    if missing:
        print(f"[preflight] missing required env: {', '.join(missing)}", flush=True)
        return 2

    database_url = build_database_url()
    print(
        "[preflight] checking database "
        f"{database_url.host}:{database_url.port}/{database_url.database} as {database_url.username}",
        flush=True,
    )

    engine = create_engine(database_url, pool_pre_ping=True)
    last_error: Exception | None = None
    for attempt in range(1, 11):
        try:
            with engine.connect() as conn:
                conn.execute(text("SELECT 1"))
            print("[preflight] database connection ok", flush=True)
            return 0
        except SQLAlchemyError as exc:
            last_error = exc
            print(f"[preflight] database not ready, attempt {attempt}/10: {exc}", flush=True)
            time.sleep(3)

    print(f"[preflight] database connection failed: {last_error}", flush=True)
    return 3


if __name__ == "__main__":
    sys.exit(main())
