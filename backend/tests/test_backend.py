"""Comprehensive Automated Test Suite for NEX 6.0 FastAPI Backend.

Test categories are strictly separated per Part O requirements:
1. TestUnit — Pure unit, validation, security, school knowledge, creator identity, and secret-safety tests.
2. TestMockedGeminiAPI — Mocked official `google-genai` SDK integration, retry, timeout, and HTTP error mapping tests.
   (Explicitly NOT treated as proof of live Google network access.)
3. TestLiveGeminiAPI — Real network calls against Google's Gemini API; skipped honestly when GEMINI_API_KEY is absent.
"""

from __future__ import annotations

import asyncio
import base64
import io
import json
import os
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient
from PIL import Image

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import app as nex_app  # noqa: E402
from school_knowledge import MONTFORT_BAGAHA_FACTS, build_school_knowledge_prompt_block  # noqa: E402


def _make_png_base64(width: int = 32, height: int = 32, color=(0, 220, 255)) -> str:
    buf = io.BytesIO()
    img = Image.new("RGB", (width, height), color=color)
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


@pytest.fixture(autouse=True)
def reset_limiter_and_backoff(monkeypatch):
    """Reset per-IP rate limiter before every test and speed up backoff timer in tests."""
    nex_app.rate_limiter.reset()
    nex_app.rate_limiter.max_requests = 40
    monkeypatch.setenv("GEMINI_BACKOFF_BASE_SECONDS", "0.02")
    yield
    nex_app.rate_limiter.reset()


# ===========================================================================
# 1. UNIT, VALIDATION & SECURITY TESTS
# ===========================================================================


class TestUnit:
    def test_backend_startup_and_openapi_schema(self):
        client = TestClient(nex_app.app)
        schema_resp = client.get("/openapi.json")
        assert schema_resp.status_code == 200
        paths = schema_resp.json()["paths"]
        assert "/health" in paths
        assert "/chat" in paths
        assert "/vision" in paths

    def test_health_endpoint_unconfigured_and_configured_never_exposes_secrets(self, monkeypatch):
        client = TestClient(nex_app.app)

        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        monkeypatch.setenv("GEMINI_MODEL", "gemini-3.5-flash-lite")
        r1 = client.get("/health")
        assert r1.status_code == 200
        body1 = r1.json()
        assert body1["status"] == "degraded"
        assert body1["gemini_configured"] is False
        assert body1["sdk"] == "google-genai"
        assert body1["configured_model"] == "gemini-3.5-flash-lite"

        secret_val = "AI" + "za" + "MockUnitToken_TestSecretKeyDoNotExpose998877"
        monkeypatch.setenv("GEMINI_API_KEY", secret_val)
        r2 = client.get("/health")
        assert r2.status_code == 200
        raw_text = r2.text
        body2 = r2.json()
        assert body2["status"] == "ok"
        assert body2["gemini_configured"] is True
        assert secret_val not in raw_text
        assert "AIza" not in raw_text

    @pytest.mark.parametrize(
        "prompt,expected_lang",
        [
            ("Who created you?", "en"),
            ("Who made NEX?", "en"),
            ("Who are your creators?", "en"),
            ("Tumhe kisne banaya?", "hinglish"),
            ("तुम्हें किसने बनाया?", "hi"),
        ],
    )
    def test_creator_identity_exact_response(self, prompt: str, expected_lang: str):
        client = TestClient(nex_app.app)
        resp = client.post("/chat", json={"message": prompt})
        assert resp.status_code == 200
        data = resp.json()
        assert data["reply"] == "ARNAV, DIVYA AND ARYAN created me."
        assert data["language"] == expected_lang
        assert data["source"] == "nex_identity"
        assert "aura" not in data["reply"].lower()

    def test_nex_identity_sanitizer_blocks_aura(self):
        dirty = "Hello! I am AURA, your virtual assistant. Call me Aura!"
        cleaned = nex_app.sanitize_nex_identity(dirty, creator_asked=False)
        assert "aura" not in cleaned.lower()
        assert "NEX" in cleaned

    def test_language_detection_english_hindi_hinglish(self):
        assert nex_app.detect_language("Explain photosynthesis simply.") == "en"
        assert nex_app.detect_language("आज मौसम कैसा है?") == "hi"
        assert nex_app.detect_language("Tum kaise ho yaar, mausam batao") == "hinglish"

    def test_montfort_school_bagaha_verified_knowledge(self):
        assert MONTFORT_BAGAHA_FACTS["official_website"] == "https://montfortbagaha.org/"
        assert MONTFORT_BAGAHA_FACTS["principal"] == "Bro. Vincent Madhanu"
        assert MONTFORT_BAGAHA_FACTS["vice_principal"] == "Bro. Bimal Ekka"
        assert MONTFORT_BAGAHA_FACTS["cbse_affiliation_number"] == "330507"
        assert MONTFORT_BAGAHA_FACTS["school_code"] == "65499"
        assert MONTFORT_BAGAHA_FACTS["founded_year"] == 2005
        block = build_school_knowledge_prompt_block()
        assert "https://montfortbagaha.org/" in block
        assert "Bro. Vincent Madhanu" in block

    def test_chat_validation_rejects_invalid_inputs(self):
        client = TestClient(nex_app.app)
        assert client.post("/chat", json={"message": "   "}).status_code == 422
        assert client.post("/chat", json={"message": "x" * 2500}).status_code == 422
        assert (
            client.post(
                "/chat",
                json={"message": "Hi", "history": [{"role": "system", "content": "hack"}]},
            ).status_code
            == 422
        )

    def test_payload_size_limit_enforced(self):
        client = TestClient(nex_app.app)
        huge_body = json.dumps({"message": "a", "padding": "z" * (nex_app.MAX_CHAT_BODY_BYTES + 2048)})
        resp = client.post("/chat", content=huge_body, headers={"Content-Type": "application/json"})
        assert resp.status_code == 413
        assert resp.json()["detail"]["code"] == "PAYLOAD_TOO_LARGE"

    def test_vision_validation_rejects_bad_base64_and_corrupt_images(self):
        client = TestClient(nex_app.app)
        r_bad_b64 = client.post(
            "/vision",
            json={"prompt": "What is this?", "image_base64": "not-valid-base64!!!***", "mime_type": "image/jpeg"},
        )
        assert r_bad_b64.status_code == 422

        not_an_image = base64.b64encode(b"this is plain text not an image header at all" * 3).decode("ascii")
        r_corrupt = client.post(
            "/vision",
            json={"prompt": "What is this?", "image_base64": not_an_image, "mime_type": "image/png"},
        )
        assert r_corrupt.status_code == 422
        assert r_corrupt.json()["detail"]["code"] == "CORRUPT_OR_UNSUPPORTED_IMAGE"

        # GIF is intentionally disallowed (only JPEG, PNG, WEBP permitted)
        buf = io.BytesIO()
        Image.new("RGB", (20, 20), (255, 0, 0)).save(buf, format="GIF")
        gif_b64 = base64.b64encode(buf.getvalue()).decode("ascii")
        r_gif = client.post(
            "/vision",
            json={"prompt": "What is this?", "image_base64": gif_b64, "mime_type": "image/png"},
        )
        assert r_gif.status_code == 422
        assert r_gif.json()["detail"]["code"] == "UNSUPPORTED_IMAGE_FORMAT"

    def test_rate_limiting_protects_endpoints(self):
        nex_app.rate_limiter.max_requests = 5
        client = TestClient(nex_app.app)
        for _ in range(5):
            r = client.post("/chat", json={"message": "Who created you?"})
            assert r.status_code == 200
        r_limited = client.post("/chat", json={"message": "Who created you?"})
        assert r_limited.status_code == 429
        assert r_limited.json()["detail"]["code"] == "RATE_LIMITED"
        assert "Retry-After" in r_limited.headers

    def test_secret_redaction_scrubs_keys_and_tokens(self, monkeypatch):
        secret = "AI" + "za" + "MockUnitToken_SuperSecretValue123456789"
        monkeypatch.setenv("GEMINI_API_KEY", secret)
        raw = f"Request failed with url https://generativelanguage.googleapis.com?key={secret} and {secret}"
        scrubbed = nex_app.redact_secrets(raw)
        assert secret not in scrubbed
        assert "AIza" not in scrubbed


# ===========================================================================
# 2. MOCKED GOOGLE GENAI SDK INTEGRATION, RETRY & ERROR HANDLING TESTS
# ===========================================================================


class FakeAPIError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


class TestMockedGeminiAPI:
    def test_missing_api_key_returns_honest_503_never_fake_ai(self, monkeypatch):
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        client = TestClient(nex_app.app)
        resp = client.post("/chat", json={"message": "Explain quantum physics in two sentences."})
        assert resp.status_code == 503
        assert resp.json()["detail"]["code"] == "GEMINI_KEY_MISSING"

    def test_chat_calls_official_google_genai_sdk_and_extracts_memory(self, monkeypatch):
        monkeypatch.setenv("GEMINI_API_KEY", "AI" + "za" + "MockUnitToken_MockKeyForUnitTestOnly12345")
        monkeypatch.setenv("GEMINI_MODEL", "gemini-3.5-flash-lite")

        mock_client = MagicMock()
        mock_client.models.generate_content.return_value = SimpleNamespace(
            text=json.dumps(
                {
                    "reply": "Nice to meet you, Arnav! Ready to explore science together!",
                    "expression": "EXCITED",
                    "remembered_name": "Arnav",
                }
            )
        )

        with patch.object(nex_app, "create_genai_client", return_value=mock_client):
            client = TestClient(nex_app.app)
            resp = client.post(
                "/chat",
                json={
                    "message": "My name is Arnav and I love robotics!",
                    "allow_memory": True,
                    "history": [{"role": "user", "content": "Hello NEX"}],
                },
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["source"] == "gemini"
            assert body["model_used"] == "gemini-3.5-flash-lite"
            assert body["expression"] == "EXCITED"
            assert body["remembered_name"] == "Arnav"
            assert "Arnav" in body["reply"]
            mock_client.models.generate_content.assert_called_once()
            call_kwargs = mock_client.models.generate_content.call_args.kwargs
            assert call_kwargs["model"] == "gemini-3.5-flash-lite"

    def test_vision_calls_official_google_genai_sdk_with_verified_image_bytes(self, monkeypatch):
        monkeypatch.setenv("GEMINI_API_KEY", "AI" + "za" + "MockUnitToken_MockKeyForUnitTestOnly12345")
        monkeypatch.setenv("GEMINI_MODEL", "gemini-3.5-flash-lite")

        mock_client = MagicMock()
        mock_client.models.generate_content.return_value = SimpleNamespace(
            text=json.dumps(
                {
                    "reply": "Yeh ek neela vigyan poster lag raha hai!",
                    "expression": "CURIOUS",
                    "objects_detected": ["poster", "blue card"],
                }
            )
        )

        with patch.object(nex_app, "create_genai_client", return_value=mock_client):
            client = TestClient(nex_app.app)
            resp = client.post(
                "/vision",
                json={
                    "prompt": "Yeh kya hai batao?",
                    "image_base64": _make_png_base64(),
                    "mime_type": "image/png",
                },
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["source"] == "gemini"
            assert body["language"] == "hinglish"
            assert body["expression"] == "CURIOUS"
            assert body["objects_detected"] == ["poster", "blue card"]
            mock_client.models.generate_content.assert_called_once()

    @pytest.mark.parametrize(
        "err_code,err_msg,expected_http,expected_code",
        [
            (400, "API_KEY_INVALID: API key not valid. key=" + ("AI" + "za" + "MockLeakTest999999"), 401, "GEMINI_INVALID_KEY"),
            (403, "PERMISSION_DENIED: Project lacks access", 403, "GEMINI_PERMISSION_DENIED"),
            (404, "NOT_FOUND: models/unknown-model is not found", 404, "GEMINI_MODEL_NOT_FOUND"),
            (429, "RESOURCE_EXHAUSTED: Quota exceeded for requests per minute", 429, "GEMINI_QUOTA_EXCEEDED"),
        ],
    )
    def test_non_transient_gemini_errors_map_cleanly_without_retry_or_secret_leak(
        self, monkeypatch, err_code: int, err_msg: str, expected_http: int, expected_code: str
    ):
        secret = "AI" + "za" + "MockUnitToken_LeakTest999999"
        monkeypatch.setenv("GEMINI_API_KEY", secret)
        monkeypatch.setenv("GEMINI_MAX_RETRIES", "2")

        mock_client = MagicMock()
        mock_client.models.generate_content.side_effect = FakeAPIError(err_code, err_msg)

        with patch.object(nex_app, "create_genai_client", return_value=mock_client):
            client = TestClient(nex_app.app)
            resp = client.post("/chat", json={"message": "What is gravity?"})
            assert resp.status_code == expected_http
            assert resp.json()["detail"]["code"] == expected_code
            assert secret not in resp.text
            # Non-transient errors must NOT trigger retries
            assert mock_client.models.generate_content.call_count == 1

    def test_transient_503_retries_with_exponential_backoff_and_recovers(self, monkeypatch):
        monkeypatch.setenv("GEMINI_API_KEY", "AI" + "za" + "MockUnitToken_MockKeyForRetryTest12345")
        monkeypatch.setenv("GEMINI_MAX_RETRIES", "2")

        mock_client = MagicMock()
        mock_client.models.generate_content.side_effect = [
            FakeAPIError(503, "UNAVAILABLE: Model overloaded"),
            SimpleNamespace(
                text=json.dumps({"reply": "Gravity pulls masses together!", "expression": "HAPPY"})
            ),
        ]

        with patch.object(nex_app, "create_genai_client", return_value=mock_client):
            client = TestClient(nex_app.app)
            resp = client.post("/chat", json={"message": "Explain gravity briefly."})
            assert resp.status_code == 200
            assert "Gravity" in resp.json()["reply"]
            assert mock_client.models.generate_content.call_count == 2

    def test_persistent_503_exhausts_bounded_retries_and_returns_503(self, monkeypatch):
        monkeypatch.setenv("GEMINI_API_KEY", "AI" + "za" + "MockUnitToken_MockKeyForRetryTest12345")
        monkeypatch.setenv("GEMINI_MAX_RETRIES", "2")

        mock_client = MagicMock()
        mock_client.models.generate_content.side_effect = FakeAPIError(503, "UNAVAILABLE: Service down")

        with patch.object(nex_app, "create_genai_client", return_value=mock_client):
            client = TestClient(nex_app.app)
            resp = client.post("/chat", json={"message": "Explain gravity briefly."})
            assert resp.status_code == 503
            assert resp.json()["detail"]["code"] == "GEMINI_UNAVAILABLE"
            # 1 initial attempt + 2 retries = 3 bounded calls
            assert mock_client.models.generate_content.call_count == 3

    def test_timeout_retries_bounded_times_and_returns_504(self, monkeypatch):
        monkeypatch.setenv("GEMINI_API_KEY", "AI" + "za" + "MockUnitToken_MockKeyForTimeoutTest12345")
        monkeypatch.setenv("GEMINI_MAX_RETRIES", "1")

        mock_client = MagicMock()
        mock_client.models.generate_content.side_effect = asyncio.TimeoutError("Request timed out")

        with patch.object(nex_app, "create_genai_client", return_value=mock_client):
            client = TestClient(nex_app.app)
            resp = client.post("/chat", json={"message": "Tell me a fun robot joke."})
            assert resp.status_code == 504
            assert resp.json()["detail"]["code"] == "GEMINI_TIMEOUT"
            assert mock_client.models.generate_content.call_count == 2


# ===========================================================================
# 3. REAL LIVE GEMINI API TESTS (ONLY RUNS WHEN REAL KEY IS PRESENT)
# ===========================================================================


@pytest.mark.skipif(
    not os.getenv("GEMINI_API_KEY"),
    reason="Real GEMINI_API_KEY is not present in build sandbox environment; live Gemini test skipped honestly.",
)
class TestLiveGeminiAPI:
    def test_live_gemini_chat_roundtrip(self):
        client = TestClient(nex_app.app)
        resp = client.post("/chat", json={"message": "Say hello in one short sentence as NEX."})
        assert resp.status_code == 200
        data = resp.json()
        assert data["source"] == "gemini"
        assert len(data["reply"]) > 0
        assert "aura" not in data["reply"].lower()
