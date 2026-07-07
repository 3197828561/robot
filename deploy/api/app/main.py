"""光伏机器人 HTTP API 最小联调骨架。"""

from __future__ import annotations

import os
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from pydantic import BaseModel, EmailStr, Field
from pydantic_settings import BaseSettings
from sqlalchemy import URL, DateTime, ForeignKey, Integer, String, Text, create_engine, select
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, relationship, sessionmaker

ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_HOURS = 24


class Settings(BaseSettings):
    database_url: str | None = Field(default=None, alias="DATABASE_URL")
    postgres_user: str = Field(default="vgsolar", alias="POSTGRES_USER")
    postgres_password: str = Field(default="", alias="POSTGRES_PASSWORD")
    postgres_db: str = Field(default="vgsolar", alias="POSTGRES_DB")
    postgres_host: str = Field(default="postgres", alias="POSTGRES_HOST")
    postgres_port: int = Field(default=5432, alias="POSTGRES_PORT")
    jwt_secret: str = Field(alias="JWT_SECRET")
    bootstrap_email: EmailStr = Field(alias="API_BOOTSTRAP_EMAIL")
    bootstrap_password: str = Field(alias="API_BOOTSTRAP_PASSWORD")
    public_host: str = Field(default="localhost", alias="PUBLIC_HOST")

    def sqlalchemy_url(self) -> str | URL:
        if self.database_url:
            return self.database_url
        return URL.create(
            "postgresql+psycopg",
            username=self.postgres_user,
            password=self.postgres_password,
            host=self.postgres_host,
            port=self.postgres_port,
            database=self.postgres_db,
        )


settings = Settings()
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
bearer_scheme = HTTPBearer(auto_error=False)

engine = create_engine(settings.sqlalchemy_url(), pool_pre_ping=True)
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    device_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    display_name: Mapped[str] = mapped_column(String(128))
    owner_user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    wifi_ssid: Mapped[str | None] = mapped_column(String(128), nullable=True)
    wifi_password: Mapped[str | None] = mapped_column(String(128), nullable=True)

    owner: Mapped[User] = relationship()


class JobRecord(Base):
    __tablename__ = "job_records"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    device_id: Mapped[str] = mapped_column(String(64), index=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    status: Mapped[str] = mapped_column(String(32))
    cleaned_rows: Mapped[int] = mapped_column(Integer, default=0)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)


class FirmwareMeta(Base):
    __tablename__ = "firmware_meta"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    device_model: Mapped[str] = mapped_column(String(64), index=True)
    version: Mapped[str] = mapped_column(String(32))
    download_url: Mapped[str] = mapped_column(String(512))
    release_notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    published_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class DeviceResponse(BaseModel):
    device_id: str
    display_name: str


class JobResponse(BaseModel):
    id: int
    device_id: str
    started_at: datetime
    finished_at: datetime | None
    status: str
    cleaned_rows: int
    note: str | None


class FirmwareResponse(BaseModel):
    version: str
    download_url: str
    release_notes: str | None
    published_at: datetime


class WifiConfigResponse(BaseModel):
    device_id: str
    ssid: str | None
    configured: bool


class WifiConfigUpdate(BaseModel):
    ssid: str = Field(min_length=1, max_length=128)
    password: str = Field(min_length=1, max_length=128)


class FirmwareUpgradeRequest(BaseModel):
    device_id: str
    target_version: str | None = None


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)


def create_access_token(user_id: int, email: str) -> str:
    expire = datetime.now(timezone.utc) + timedelta(hours=ACCESS_TOKEN_EXPIRE_HOURS)
    payload = {"sub": str(user_id), "email": email, "exp": expire}
    return jwt.encode(payload, settings.jwt_secret, algorithm=ALGORITHM)


def bootstrap_data(db: Session) -> None:
    user = db.scalar(select(User).where(User.email == settings.bootstrap_email))
    if user is None:
        user = User(
            email=settings.bootstrap_email,
            password_hash=hash_password(settings.bootstrap_password),
        )
        db.add(user)
        db.flush()

    device = db.scalar(select(Device).where(Device.device_id == "rk3588"))
    if device is None:
        db.add(
            Device(
                device_id="rk3588",
                display_name="Kwun-B22L-180926",
                owner_user_id=user.id,
                wifi_ssid="Robot-AP",
                wifi_password="robot123456",
            )
        )

    if db.scalar(select(JobRecord).limit(1)) is None:
        now = datetime.now(timezone.utc)
        db.add(
            JobRecord(
                device_id="rk3588",
                started_at=now - timedelta(hours=2),
                finished_at=now - timedelta(hours=1),
                status="completed",
                cleaned_rows=12,
                note="联调示例作业记录",
            )
        )

    if db.scalar(select(FirmwareMeta).limit(1)) is None:
        db.add(
            FirmwareMeta(
                device_model="rk3588",
                version="1.0.0",
                download_url=f"http://{settings.public_host}/api/firmware/download/rk3588-1.0.0.bin",
                release_notes="联调占位固件包",
                published_at=datetime.now(timezone.utc),
            )
        )

    db.commit()


@asynccontextmanager
async def lifespan(_: FastAPI):
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        bootstrap_data(db)
    yield


app = FastAPI(title="VGSolar Robot API", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
    db: Annotated[Session, Depends(get_db)],
) -> User:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="未登录或 Token 无效")
    token = credentials.credentials
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=[ALGORITHM])
        user_id = int(payload.get("sub", "0"))
    except (JWTError, ValueError) as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token 解析失败") from exc

    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="用户不存在")
    return user


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/api/auth/login", response_model=TokenResponse)
def login(body: LoginRequest, db: Annotated[Session, Depends(get_db)]):
    user = db.scalar(select(User).where(User.email == body.email))
    if user is None or not verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="邮箱或密码错误")

    token = create_access_token(user.id, user.email)
    return TokenResponse(
        access_token=token,
        expires_in=ACCESS_TOKEN_EXPIRE_HOURS * 3600,
    )


@app.get("/api/devices", response_model=list[DeviceResponse])
def list_devices(
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    devices = db.scalars(select(Device).where(Device.owner_user_id == user.id)).all()
    return [DeviceResponse(device_id=d.device_id, display_name=d.display_name) for d in devices]


@app.get("/api/jobs", response_model=list[JobResponse])
def list_jobs(
    device_id: str,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    owned = db.scalar(
        select(Device).where(Device.owner_user_id == user.id, Device.device_id == device_id)
    )
    if owned is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="设备不存在或无权限")

    jobs = db.scalars(
        select(JobRecord).where(JobRecord.device_id == device_id).order_by(JobRecord.started_at.desc())
    ).all()
    return [
        JobResponse(
            id=j.id,
            device_id=j.device_id,
            started_at=j.started_at,
            finished_at=j.finished_at,
            status=j.status,
            cleaned_rows=j.cleaned_rows,
            note=j.note,
        )
        for j in jobs
    ]


@app.get("/api/firmware/latest", response_model=FirmwareResponse)
def latest_firmware(
    device_id: str,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    _ = user
    meta = db.scalar(
        select(FirmwareMeta).where(FirmwareMeta.device_model == device_id).order_by(FirmwareMeta.published_at.desc())
    )
    if meta is None:
        meta = db.scalar(select(FirmwareMeta).order_by(FirmwareMeta.published_at.desc()))
    if meta is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="暂无固件信息")

    return FirmwareResponse(
        version=meta.version,
        download_url=meta.download_url,
        release_notes=meta.release_notes,
        published_at=meta.published_at,
    )


@app.post("/api/firmware/upgrade")
def trigger_firmware_upgrade(
    body: FirmwareUpgradeRequest,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    device = db.scalar(
        select(Device).where(Device.owner_user_id == user.id, Device.device_id == body.device_id)
    )
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="设备不存在或无权限")

    return {
        "status": "accepted",
        "device_id": body.device_id,
        "message": "固件升级任务已受理（联调占位接口，需硬件侧 OTA 对接）",
        "target_version": body.target_version,
    }


@app.get("/api/devices/{device_id}/wifi", response_model=WifiConfigResponse)
def get_wifi_config(
    device_id: str,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    device = db.scalar(
        select(Device).where(Device.owner_user_id == user.id, Device.device_id == device_id)
    )
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="设备不存在或无权限")

    return WifiConfigResponse(
        device_id=device.device_id,
        ssid=device.wifi_ssid,
        configured=bool(device.wifi_ssid),
    )


@app.put("/api/devices/{device_id}/wifi", response_model=WifiConfigResponse)
def update_wifi_config(
    device_id: str,
    body: WifiConfigUpdate,
    user: Annotated[User, Depends(get_current_user)],
    db: Annotated[Session, Depends(get_db)],
):
    device = db.scalar(
        select(Device).where(Device.owner_user_id == user.id, Device.device_id == device_id)
    )
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="设备不存在或无权限")

    device.wifi_ssid = body.ssid
    device.wifi_password = body.password
    db.commit()
    db.refresh(device)

    return WifiConfigResponse(
        device_id=device.device_id,
        ssid=device.wifi_ssid,
        configured=True,
    )
