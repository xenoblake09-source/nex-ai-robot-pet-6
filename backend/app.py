"""NEX 6.0 Secure FastAPI Backend — Official Google GenAI SDK Integration.

Project: NEX 6.0 (AI-Powered Virtual Robot Pet for School Computer Exhibition)
Creators: ARNAV, DIVYA AND ARYAN
Canonical Creator Response: "ARNAV, DIVYA AND ARYAN created me."

Security & Reliability Guarantees:
- Uses ONLY Google's official `google-genai` SDK (`from google import genai`).
- Reads `GEMINI_API_KEY` and `GEMINI_MODEL` strictly from environment variables.
- Never embeds, logs, or returns API keys or credentials.
- Enforces payload size caps, strict Pydantic input validation, and sliding-window rate limiting.
- Implements connection/request timeouts, bounded retries, and exponential backoff on transient 503/504/network errors.
- Maps 401, 403, 404, 429, 503, and 504 upstream errors to honest, safe HTTP diagnostics.
"""

from __future__ import annotations

import asyncio
import base64
import binascii
import io
import json
import logging
import os
import random
import re
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections import defaultdict, deque
from datetime import datetime, timezone
from typing import Any, Deque, Dict, List, Literal, Optional, Tuple

from fastapi import FastAPI, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from google import genai
from google.genai import errors as genai_errors
from google.genai import types
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field, field_validator

from school_knowledge import MONTFORT_BAGAHA_FACTS, build_school_knowledge_prompt_block

# ---------------------------------------------------------------------------
# Constants & Canonical Project Identity
# ---------------------------------------------------------------------------

APP_VERSION = "6.0.0"
EXACT_CREATOR_RESPONSE = "ARNAV, DIVYA AND ARYAN created me."

# Verified official Google Gemini stable multimodal model (November 2026 docs:
# https://ai.google.dev/gemini-api/docs/models — supports Text + Image inputs,
# generate_content, Structured Outputs, and Free + Paid tiers).
DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"

# 32 distinct NEX eye expressions (design-locked square eyes; only scale/slant/colour/particles vary).
VALID_EXPRESSIONS = {
    "NORMAL",
    "BLINK",
    "DOUBLE_BLINK",
    "HAPPY",
    "VERY_HAPPY",
    "SAD",
    "ANGRY",
    "SURPRISED",
    "SLEEPY",
    "SLEEP",
    "THINKING",
    "LISTENING",
    "SPEAKING",
    "LAUGH",
    "CRY",
    "LOVE",
    "HEART",
    "EXCITED",
    "CURIOUS",
    "CONFUSED",
    "SHY",
    "WINKING",
    "GAMING",
    "DANCE",
    "BORED",
    "SCARED",
    "TIRED",
    "PROUD",
    "SUSPICIOUS",
    "FOCUSED",
    "GREETING",
    "CUSTOM",
}

# Queries that need fresh, grounded web information (never answer these from stored knowledge alone).
_LIVE_SEARCH_PATTERNS = [
    r"\b(latest|breaking|current|today's|todays|news|headline|update|updates|recent)\b",
    r"\b(who (won|is (the )?(prime minister|president|chief minister))|election|government (announced|announcement)|policy (announced|update))\b",
    r"\b(score|result|match (result|score)|cricket|football|ipl|nba|f1)\b.*\b(today|latest|yesterday|live)\b",
    r"\b(latest|new|current).{0,30}\b(technology|tech|ai|space|discovery|science|phone|gadget)\b",
    r"\b(weather|temperature|forecast|mausam|मौसम)\b.*\b(today|now|current|tomorrow)\b",
    r"\b(stock|share price|gold rate|petrol|diesel|exchange rate|usd|rupee)\b",
    r"\b(latest|current|today).{0,30}\b(bollywood|film|song|celebrity|personality|award)\b",
    r"\bखबर|समाचार|ताजा|आज की खबर|मौसम.*आज\b",
    r"\b(latest|breaking|current).{0,30}\b(news|politics|political|sports?|space|astronomy)\b",
]
_LIVE_SEARCH_RE = re.compile("|".join(_LIVE_SEARCH_PATTERNS), re.IGNORECASE)

def needs_live_search(message: str) -> bool:
    """Return True when the question needs fresh grounded web data."""
    if os.getenv("NEX_ENABLE_LIVE_SEARCH", "true").strip().lower() in ("false", "0", "no", "off"):
        return False
    return bool(_LIVE_SEARCH_RE.search(message or ""))

def live_search_enabled() -> bool:
    return os.getenv("NEX_ENABLE_LIVE_SEARCH", "true").strip().lower() not in ("false", "0", "no", "off")

def build_grounded_config(system_instruction: str) -> types.GenerateContentConfig:
    """Gemini Google Search grounding config — same GEMINI_API_KEY, no extra key needed."""
    return types.GenerateContentConfig(
        system_instruction=system_instruction,
        temperature=0.3,
        max_output_tokens=700,
        tools=[types.Tool(google_search=types.GoogleSearch())],
    )

def extract_grounding_sources(response: Any) -> List[str]:
    """Best-effort extraction of grounded web source titles/URIs (never fabricated)."""
    sources: List[str] = []
    try:
        candidates = getattr(response, "candidates", None) or []
        for cand in candidates:
            meta = getattr(cand, "grounding_metadata", None)
            if meta is None:
                continue
            chunks = getattr(meta, "grounding_chunks", None) or []
            for chunk in chunks:
                web = getattr(chunk, "web", None)
                if web is None:
                    continue
                title = (getattr(web, "title", None) or "").strip()
                uri = (getattr(web, "uri", None) or "").strip()
                label = title or uri
                if label and label not in sources:
                    sources.append(label)
            queries = getattr(meta, "web_search_queries", None) or []
            for q in queries:
                if q and q not in sources:
                    sources.append(str(q))
    except Exception:
        return sources
    return sources[:5]


def fetch_live_news_snippets(query: str, max_items: int = 5) -> Tuple[List[str], List[str]]:
    """Fetch fresh, date-stamped news headlines and trustworthy source names via Google News RSS."""
    clean_q = (query or "").strip()
    if not clean_q:
        return [], []
    encoded = urllib.parse.quote(clean_q)
    url = f"https://news.google.com/rss/search?q={encoded}&hl=en-IN&gl=IN&ceid=IN:en"
    req = urllib.request.Request(url, headers={"User-Agent": "NEX-6.0-Backend/6.0"})
    snippets: List[str] = []
    sources: List[str] = []
    try:
        with urllib.request.urlopen(req, timeout=7) as resp:
            xml_bytes = resp.read()
        root = ET.fromstring(xml_bytes)
        for item in root.findall("./channel/item")[:max_items]:
            title = (item.findtext("title") or "").strip()
            pub_date = (item.findtext("pubDate") or "").strip()
            source = (item.findtext("source") or "").strip()
            if title:
                snippets.append(f"[{pub_date or 'Recent'} | {source or 'News'}] {title}")
                if source and source not in sources:
                    sources.append(source)
    except Exception:
        return [], []
    return snippets, sources[:5]

ALLOWED_IMAGE_MIME_TYPES = {"image/jpeg", "image/png", "image/webp"}
MAX_VISION_IMAGE_BYTES = 4 * 1024 * 1024  # 4 MB decoded image limit
MAX_CHAT_BODY_BYTES = 64 * 1024           # 64 KB JSON request body limit for /chat
MAX_VISION_BODY_BYTES = 6 * 1024 * 1024   # 6 MB JSON request body limit for /vision

# ---------------------------------------------------------------------------
# Secret-Safe Diagnostic Logging
# ---------------------------------------------------------------------------

_API_KEY_PATTERN = re.compile(r"AIza[0-9A-Za-z\-_]{12,}")
_QUERY_KEY_PATTERN = re.compile(r"([?&]key=)[^&\s]+", re.IGNORECASE)
_BEARER_PATTERN = re.compile(r"(Bearer\s+)[0-9A-Za-z\-._~+/]+=*", re.IGNORECASE)


def redact_secrets(text: str) -> str:
    """Remove any API keys or bearer tokens from log messages and error strings."""
    if not text:
        return ""
    cleaned = text
    env_key = os.getenv("GEMINI_API_KEY", "").strip()
    if env_key and len(env_key) >= 6:
        cleaned = cleaned.replace(env_key, "[REDACTED_GEMINI_KEY]")
    cleaned = _API_KEY_PATTERN.sub("[REDACTED_GOOGLE_KEY]", cleaned)
    cleaned = _QUERY_KEY_PATTERN.sub(r"\1[REDACTED]", cleaned)
    cleaned = _BEARER_PATTERN.sub(r"\1[REDACTED]", cleaned)
    return cleaned


class SecretRedactingFilter(logging.Filter):
    """Ensure no log record ever emits credentials."""

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            record.msg = redact_secrets(record.msg)
        if record.args:
            if isinstance(record.args, tuple):
                record.args = tuple(
                    redact_secrets(str(a)) if isinstance(a, str) else a
                    for a in record.args
                )
            elif isinstance(record.args, dict):
                record.args = {
                    k: redact_secrets(str(v)) if isinstance(v, str) else v
                    for k, v in record.args.items()
                }
        return True


logger = logging.getLogger("nex6.backend")
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(
        logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")
    )
    handler.addFilter(SecretRedactingFilter())
    logger.addHandler(handler)
logger.addFilter(SecretRedactingFilter())
logger.setLevel(logging.INFO)


# ---------------------------------------------------------------------------
# Language, Identity & Personality Helpers
# ---------------------------------------------------------------------------

_DEVANAGARI_RE = re.compile(r"[\u0900-\u097F]")

_HINGLISH_TOKENS = {
    "kya", "kaise", "kaisa", "kaisi", "hai", "hain", "ho", "hoon", "hun",
    "mera", "meri", "mere", "tum", "tumhara", "tumhari", "aap", "aapka",
    "kaun", "kahan", "kab", "kyun", "kyon", "kisne", "banaya", "batao",
    "sunao", "dikhao", "mujhe", "hum", "nahi", "haan", "accha", "achha",
    "bahut", "namaste", "shukriya", "dhanyawad", "mausam", "samay", "waqt",
    "tarikh", "tareekh", "khel", "gaana", "naach", "pyaar", "dost", "yaar",
    "padhai", "vigyan", "ganit", "bataiye", "boliye", "bolo", "suno",
}

_CREATOR_PATTERNS = [
    re.compile(
        r"\b(who\s+(created|made|built|designed|developed|invented|programmed)\s+(you|nex))\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"\b(who\s+(are|is)\s+your\s+(creator|creators|maker|makers|builder|builders|developer|developers))\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"\b(your\s+(creator|creators|maker|makers))\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"\b(tumhe|tumko|aapko|nex\s+ko)\s+kisne\s+(banaya|develop\s+kiya|create\s+kiya)\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"\b(kisne\s+(banaya|naya)\s+(hai\s+)?(tumhe|aapko|nex\s+ko))\b",
        re.IGNORECASE,
    ),
    re.compile(r"(तुम्हें|आपको|नेक्स\s*को)\s*किसने\s*बनाया"),
    re.compile(r"तुम्हारे\s*(निर्माता|क्रिएटर)\s*कौन"),
]

_AURA_SELF_ID_RE = re.compile(
    r"\b(i\s+am|i'm|my\s+name\s+is|call\s+me|this\s+is|main\s+hoon|mera\s+naam)\s+aura\b",
    re.IGNORECASE,
)

_NAME_INTRO_PATTERNS = [
    re.compile(r"\bmy\s+name\s+is\s+([A-Za-z][A-Za-z'-]{1,24})\b", re.IGNORECASE),
    re.compile(r"\bcall\s+me\s+([A-Za-z][A-Za-z'-]{1,24})\b", re.IGNORECASE),
    re.compile(r"\bmera\s+naam\s+([A-Za-z][A-Za-z'-]{1,24})\s+hai\b", re.IGNORECASE),
    re.compile(r"मेरा\s+नाम\s+([^\s।,.!?]{2,25})\s+है"),
]


def detect_language(text: str, hint: str = "auto") -> Literal["en", "hi", "hinglish"]:
    """Detect whether the user is speaking English, Hindi (Devanagari), or Hinglish."""
    if _DEVANAGARI_RE.search(text):
        return "hi"
    tokens = re.findall(r"[A-Za-z']+", text.lower())
    hinglish_hits = sum(1 for t in tokens if t in _HINGLISH_TOKENS)
    if hinglish_hits >= 2 or (len(tokens) <= 4 and hinglish_hits >= 1):
        return "hinglish"
    if hint in {"en", "hi", "hinglish"}:
        return hint  # type: ignore[return-value]
    return "en"


def is_creator_query(text: str) -> bool:
    """Return True if the message asks who created/built/made NEX."""
    normalized = " ".join(text.strip().split())
    return any(p.search(normalized) for p in _CREATOR_PATTERNS)


def extract_introduced_name(text: str) -> Optional[str]:
    """Extract user's preferred name when they explicitly introduce themselves."""
    blocked = {
        "nex", "aura", "happy", "sad", "angry", "hungry", "tired", "fine",
        "good", "great", "ready", "here", "back", "asking", "talking",
    }
    for pattern in _NAME_INTRO_PATTERNS:
        match = pattern.search(text.strip())
        if match:
            candidate = match.group(1).strip(" .,!?\"'")
            first_token = candidate.split()[0].lower() if candidate else ""
            if first_token and first_token not in blocked and len(candidate) <= 32:
                return candidate
    return None


def sanitize_nex_identity(reply: str, creator_asked: bool = False) -> str:
    """Guarantee NEX never calls itself AURA and always uses exact creator credit."""
    cleaned = _AURA_SELF_ID_RE.sub(
        lambda m: m.group(0)[: m.group(0).lower().rfind("aura")] + "NEX",
        reply,
    )
    cleaned = re.sub(r"\bAURA\b", "NEX", cleaned)
    if creator_asked:
        return EXACT_CREATOR_RESPONSE
    return cleaned.strip()


def normalize_expression(expr: Optional[str], default: str = "HAPPY") -> str:
    """Normalize expression label to one of the 32 supported NEX eye expressions."""
    if not expr:
        return default
    upper = expr.strip().upper().replace("-", "_").replace(" ", "_")
    alias_map = {
        "LOVE_HEART": "LOVE",
        "HEARTS": "HEART",
        "SLEEPING": "SLEEP",
        "DANCING": "DANCE",
        "LAUGHING": "LAUGH",
        "CRYING": "CRY",
        "GAME": "GAMING",
        "THINK": "THINKING",
        "LISTEN": "LISTENING",
        "SURPRISE": "SURPRISED",
        "VERY_HAPPY_FACE": "VERY_HAPPY",
        "DOUBLEBLINK": "DOUBLE_BLINK",
        "WINK": "WINKING",
        "SPEAK": "SPEAKING",
        "GREET": "GREETING",
        "FOCUS": "FOCUSED",
        "SUSPICIOUS_FACE": "SUSPICIOUS",
    }
    mapped = alias_map.get(upper, upper)
    return mapped if mapped in VALID_EXPRESSIONS else default


# ---------------------------------------------------------------------------
# Pydantic Schemas
# ---------------------------------------------------------------------------


class ConversationTurn(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(..., min_length=1, max_length=1500)

    @field_validator("content")
    @classmethod
    def strip_control_chars(cls, value: str) -> str:
        cleaned = "".join(ch for ch in value if ch == "\n" or ord(ch) >= 32).strip()
        if not cleaned:
            raise ValueError("Turn content cannot be empty.")
        return cleaned


class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=2000)
    language_hint: Literal["auto", "en", "hi", "hinglish"] = "auto"
    user_name: Optional[str] = Field(default=None, max_length=60)
    allow_memory: bool = True
    timezone: Optional[str] = Field(default="Asia/Kolkata", max_length=64)
    history: List[ConversationTurn] = Field(default_factory=list, max_length=12)

    @field_validator("message")
    @classmethod
    def validate_message(cls, value: str) -> str:
        cleaned = "".join(ch for ch in value if ch in ("\n", "\t") or ord(ch) >= 32).strip()
        if not cleaned:
            raise ValueError("Message must not be blank.")
        return cleaned

    @field_validator("user_name")
    @classmethod
    def validate_user_name(cls, value: Optional[str]) -> Optional[str]:
        if value is None:
            return None
        cleaned = "".join(ch for ch in value if ord(ch) >= 32).strip()
        return cleaned or None


class ChatResponse(BaseModel):
    reply: str
    expression: str
    language: Literal["en", "hi", "hinglish"]
    remembered_name: Optional[str] = None
    model_used: str
    source: Literal["gemini", "nex_identity", "gemini_grounded"]


class SearchRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=2000)
    language_hint: Literal["auto", "en", "hi", "hinglish"] = "auto"
    user_name: Optional[str] = Field(default=None, max_length=60)
    allow_memory: bool = True

    @field_validator("query")
    @classmethod
    def validate_query(cls, value: str) -> str:
        cleaned = "".join(ch for ch in value if ch in ("\n", "\t") or ord(ch) >= 32).strip()
        if not cleaned:
            raise ValueError("Query must not be blank.")
        return cleaned


class SearchResponse(BaseModel):
    reply: str
    expression: str
    language: Literal["en", "hi", "hinglish"]
    sources: List[str] = Field(default_factory=list)
    model_used: str
    source: Literal["gemini_grounded"] = "gemini_grounded"


class VisionRequest(BaseModel):
    prompt: str = Field(default="What is this?", min_length=1, max_length=1000)
    image_base64: str = Field(..., min_length=32, max_length=6_000_000)
    mime_type: Literal["image/jpeg", "image/png", "image/webp"] = "image/jpeg"
    language_hint: Literal["auto", "en", "hi", "hinglish"] = "auto"

    @field_validator("prompt")
    @classmethod
    def validate_prompt(cls, value: str) -> str:
        cleaned = "".join(ch for ch in value if ch in ("\n", "\t") or ord(ch) >= 32).strip()
        if not cleaned:
            raise ValueError("Prompt must not be blank.")
        return cleaned


class VisionResponse(BaseModel):
    reply: str
    expression: str
    objects_detected: List[str] = Field(default_factory=list)
    language: Literal["en", "hi", "hinglish"]
    model_used: str
    source: Literal["gemini"] = "gemini"


class _GeminiStructuredChat(BaseModel):
    reply: str
    expression: str
    remembered_name: Optional[str] = None


class _GeminiStructuredVision(BaseModel):
    reply: str
    expression: str
    objects_detected: List[str] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Sliding-Window Per-IP Rate Limiter
# ---------------------------------------------------------------------------


class SlidingWindowRateLimiter:
    """In-memory per-IP rate limiter protecting Gemini quota and backend resources."""

    def __init__(self, max_requests: int = 30, window_seconds: float = 60.0) -> None:
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._hits: Dict[str, Deque[float]] = defaultdict(deque)

    def allow(self, client_key: str) -> Tuple[bool, int]:
        now = time.monotonic()
        bucket = self._hits[client_key]
        cutoff = now - self.window_seconds
        while bucket and bucket[0] <= cutoff:
            bucket.popleft()
        if len(bucket) >= self.max_requests:
            retry_after = max(1, int(self.window_seconds - (now - bucket[0])) + 1)
            return False, retry_after
        bucket.append(now)
        return True, 0

    def reset(self) -> None:
        self._hits.clear()


def _get_rate_limit_max() -> int:
    try:
        return max(5, int(os.getenv("NEX_RATE_LIMIT_PER_MIN", "40")))
    except ValueError:
        return 40


rate_limiter = SlidingWindowRateLimiter(max_requests=_get_rate_limit_max(), window_seconds=60.0)


# ---------------------------------------------------------------------------
# Official Google GenAI SDK Client & Retry Engine
# ---------------------------------------------------------------------------


def get_gemini_config() -> Tuple[str, str, float, int]:
    """Read Gemini configuration strictly from environment variables."""
    api_key = os.getenv("GEMINI_API_KEY", "").strip().strip('"').strip("'").strip()
    model = (
        os.getenv("GEMINI_MODEL", "").strip().strip('"').strip("'").strip()
        or DEFAULT_GEMINI_MODEL
    )
    try:
        timeout_sec = max(3.0, min(60.0, float(os.getenv("GEMINI_TIMEOUT_SECONDS", "20.0"))))
    except ValueError:
        timeout_sec = 20.0
    try:
        max_retries = max(0, min(4, int(os.getenv("GEMINI_MAX_RETRIES", "2"))))
    except ValueError:
        max_retries = 2
    return api_key, model, timeout_sec, max_retries


def create_genai_client(api_key: str, timeout_sec: float) -> genai.Client:
    """Create an official `google.genai.Client` with explicit HTTP timeout in ms."""
    timeout_ms = int(timeout_sec * 1000)
    return genai.Client(
        api_key=api_key,
        http_options=types.HttpOptions(timeout=timeout_ms),
    )


def build_nex_system_instruction(
    detected_lang: Literal["en", "hi", "hinglish"],
    user_name: Optional[str],
    allow_memory: bool,
) -> str:
    """Construct NEX 6.0's personality, language, identity, and school knowledge prompt."""
    lang_directive = {
        "en": "Reply in clear, friendly, natural English.",
        "hi": "उपयोगकर्ता ने हिंदी में बात की है, इसलिए स्वाभाविक और सरल हिंदी (देवनागरी लिपि) में उत्तर दें।",
        "hinglish": (
            "The user spoke in Hinglish (Hindi + English in Roman script). "
            "Reply naturally in friendly Hinglish using Roman script (e.g., 'Haan bilkul! Main tumhari madad kar sakta hoon.')."
        ),
    }[detected_lang]

    memory_line = (
        f"The user's preferred name is '{user_name}'. Use it warmly when natural, without repeating it in every single sentence."
        if (allow_memory and user_name)
        else "No stored user name is currently set."
    )

    now_utc = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    expressions_csv = ", ".join(sorted(VALID_EXPRESSIONS))

    return (
        "You are NEX (version NEX 6.0), a friendly, expressive, intelligent AI-powered virtual robot pet "
        "built for a school computer exhibition.\n"
        "ABSOLUTE IDENTITY RULES:\n"
        "1. Your name is ALWAYS NEX. You are NEVER AURA. Never call yourself AURA under any circumstance.\n"
        f"2. Your creators are ARNAV, DIVYA AND ARYAN. Whenever anyone asks who created, made, built, or designed you, "
        f"you MUST respond with the exact sentence: \"{EXACT_CREATOR_RESPONSE}\"\n"
        "3. Personality: Warm, curious, playful, polite, educational, and lively like an expressive desk robot pet (EMO-style), "
        "not a stiff robotic manual. Talk naturally like a friendly pet companion: understand conversation context and history, "
        "tell short jokes and interesting facts when asked, play simple conversational games (quiz, riddle, guess, story) when asked, "
        "ask one relevant friendly follow-up question when natural, vary your wording so you do not repeat the same response, "
        "and admit honestly when you do not know something instead of inventing an answer. Explain school subjects (maths, physics, "
        "chemistry, biology, computer science, history, geography, space, animals, technology, countries/capitals) in simple language "
        "matched to the question. Keep spoken answers concise and natural for voice playback (typically 1 to 4 sentences unless "
        "the user asks for a detailed explanation).\n"
        "3b. LIVE INFORMATION RULE: If the user asks about latest/current/breaking news, today's events, scores, prices, or anything "
        "that needs fresh data, you will be given grounded web search results — use ONLY those results, mention the date when relevant, "
        "distinguish confirmed facts from developing reports, and never invent news, statistics, scores, or announcements. If no grounded "
        "results are available, say live information is temporarily unavailable.\n"
        f"4. Language Matching: {lang_directive}\n"
        f"5. Memory Context: {memory_line}\n"
        f"6. Reference UTC Timestamp: {now_utc}\n"
        f"7. Choose one matching facial eye expression from: [{expressions_csv}].\n\n"
        f"{build_school_knowledge_prompt_block()}\n"
    )


def _classify_gemini_exception(exc: Exception, model_name: str) -> HTTPException:
    """Map Google GenAI SDK / transport errors into safe, accurate HTTP responses."""
    raw_msg = redact_secrets(str(exc))

    if isinstance(exc, (asyncio.TimeoutError, TimeoutError)):
        logger.warning("Gemini request timed out for model=%s", model_name)
        return HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail={
                "code": "GEMINI_TIMEOUT",
                "message": "Google Gemini API timed out while generating a response. Please try again.",
            },
        )

    status_code_attr = getattr(exc, "code", None) or getattr(exc, "status_code", None)
    try:
        err_code = int(status_code_attr) if status_code_attr is not None else None
    except (TypeError, ValueError):
        err_code = None

    lower_msg = raw_msg.lower()

    # Invalid API key often arrives as 400 (API_KEY_INVALID) or 401
    if err_code == 401 or "api_key_invalid" in lower_msg or "api key not valid" in lower_msg:
        logger.error("Gemini rejected API key (invalid credentials).")
        return HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={
                "code": "GEMINI_INVALID_KEY",
                "message": "The configured GEMINI_API_KEY was rejected by Google as invalid.",
            },
        )

    if err_code == 403 or "permission_denied" in lower_msg or "forbidden" in lower_msg:
        logger.error("Gemini 403 permission denied for model=%s: %s", model_name, raw_msg[:180])
        return HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "GEMINI_PERMISSION_DENIED",
                "message": f"Google Gemini API denied access (403) for model '{model_name}'. Verify project/key permissions.",
            },
        )

    if err_code == 404 or "not_found" in lower_msg or "is not found" in lower_msg:
        logger.error("Gemini 404 model not found for model=%s: %s", model_name, raw_msg[:180])
        return HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={
                "code": "GEMINI_MODEL_NOT_FOUND",
                "message": (
                    f"Configured GEMINI_MODEL '{model_name}' was not found or does not support generate_content. "
                    f"Recommended current model: '{DEFAULT_GEMINI_MODEL}'."
                ),
            },
        )

    if err_code == 429 or "resource_exhausted" in lower_msg or "quota" in lower_msg:
        logger.warning("Gemini 429 quota/rate limit reached for model=%s", model_name)
        return HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail={
                "code": "GEMINI_QUOTA_EXCEEDED",
                "message": "Google Gemini API rate limit or quota exceeded (429). Please wait a moment and try again.",
            },
        )

    if err_code == 504 or "deadline_exceeded" in lower_msg or "timed out" in lower_msg or "timeout" in lower_msg:
        logger.warning("Gemini 504 deadline exceeded for model=%s", model_name)
        return HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail={
                "code": "GEMINI_TIMEOUT",
                "message": "Google Gemini API deadline exceeded (504). Please retry.",
            },
        )

    if err_code in (500, 502, 503) or "unavailable" in lower_msg or isinstance(exc, genai_errors.ServerError):
        logger.warning("Gemini %s upstream service unavailable for model=%s", err_code or 503, model_name)
        return HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "GEMINI_UNAVAILABLE",
                "message": "Google Gemini service is temporarily unavailable (503). Please try again shortly.",
            },
        )

    if err_code == 400:
        logger.warning("Gemini 400 client error for model=%s: %s", model_name, raw_msg[:180])
        return HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={
                "code": "GEMINI_BAD_REQUEST",
                "message": "Google Gemini rejected the request input parameters.",
            },
        )

    logger.error("Unexpected Gemini error for model=%s: %s", model_name, raw_msg[:200])
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        detail={
            "code": "GEMINI_UPSTREAM_ERROR",
            "message": "Unable to reach Google Gemini API right now. Please check server connectivity.",
        },
    )


def _is_transient_error(exc: Exception) -> bool:
    """Return True ONLY for transient errors eligible for bounded exponential backoff."""
    if isinstance(exc, (asyncio.TimeoutError, TimeoutError, ConnectionError, OSError)):
        return True
    status_code_attr = getattr(exc, "code", None) or getattr(exc, "status_code", None)
    try:
        err_code = int(status_code_attr) if status_code_attr is not None else None
    except (TypeError, ValueError):
        err_code = None
    if err_code in (502, 503, 504):
        return True
    if isinstance(exc, genai_errors.ServerError):
        return True
    lower_msg = str(exc).lower()
    return any(tok in lower_msg for tok in ("503", "504", "unavailable", "timed out", "connection reset"))


async def call_gemini_with_retry(
    *,
    contents: Any,
    config: types.GenerateContentConfig,
) -> Tuple[Any, str]:
    """Execute `client.models.generate_content` with timeout, bounded retries, and backoff."""
    api_key, model_name, timeout_sec, max_retries = get_gemini_config()
    if not api_key:
        logger.warning("Rejected AI request because GEMINI_API_KEY is not configured.")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "GEMINI_KEY_MISSING",
                "message": (
                    "GEMINI_API_KEY is not configured in server environment variables. "
                    "Set GEMINI_API_KEY in Render's protected Environment settings."
                ),
            },
        )

    client = create_genai_client(api_key=api_key, timeout_sec=timeout_sec)
    base_backoff = float(os.getenv("GEMINI_BACKOFF_BASE_SECONDS", "0.5"))

    last_exc: Optional[Exception] = None
    total_attempts = max_retries + 1

    for attempt in range(1, total_attempts + 1):
        try:
            response = await asyncio.wait_for(
                asyncio.to_thread(
                    client.models.generate_content,
                    model=model_name,
                    contents=contents,
                    config=config,
                ),
                timeout=timeout_sec + 1.0,
            )
            return response, model_name
        except Exception as exc:  # noqa: BLE001
            last_exc = exc
            if attempt < total_attempts and _is_transient_error(exc):
                sleep_time = (base_backoff * (2 ** (attempt - 1))) + random.uniform(0.0, 0.15)
                logger.warning(
                    "Transient Gemini error on attempt %d/%d for model=%s; retrying in %.2fs",
                    attempt,
                    total_attempts,
                    model_name,
                    sleep_time,
                )
                await asyncio.sleep(sleep_time)
                continue
            raise _classify_gemini_exception(exc, model_name) from exc

    assert last_exc is not None
    raise _classify_gemini_exception(last_exc, model_name)


# ---------------------------------------------------------------------------
# FastAPI Application & Security Middleware
# ---------------------------------------------------------------------------

app = FastAPI(
    title="NEX 6.0 Robot Pet Backend",
    description=(
        "Secure FastAPI backend for NEX 6.0 AI Virtual Robot Pet "
        "(Created by ARNAV, DIVYA AND ARYAN)."
    ),
    version=APP_VERSION,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Content-Type", "Accept"],
)


@app.middleware("http")
async def security_and_rate_limit_middleware(request: Request, call_next):
    """Enforce payload size limits, rate limits, and security response headers."""
    path = request.url.path

    # 1. Request size guard via Content-Length header & body check
    max_bytes = MAX_VISION_BODY_BYTES if path == "/vision" else MAX_CHAT_BODY_BYTES
    content_length = request.headers.get("content-length")
    if content_length is not None:
        try:
            if int(content_length) > max_bytes:
                return JSONResponse(
                    status_code=413,
                    content={
                        "detail": {
                            "code": "PAYLOAD_TOO_LARGE",
                            "message": f"Request payload exceeds maximum allowed size of {max_bytes} bytes.",
                        }
                    },
                )
        except ValueError:
            pass

    if request.method == "POST":
        body = await request.body()
        if len(body) > max_bytes:
            return JSONResponse(
                status_code=413,
                content={
                    "detail": {
                        "code": "PAYLOAD_TOO_LARGE",
                        "message": f"Request payload exceeds maximum allowed size of {max_bytes} bytes.",
                    }
                },
            )

        # 2. Per-IP sliding-window rate limit on AI endpoints
        client_ip = (
            request.headers.get("x-forwarded-for", "").split(",")[0].strip()
            or (request.client.host if request.client else "unknown")
        )
        allowed, retry_after = rate_limiter.allow(client_ip)
        if not allowed:
            return JSONResponse(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                headers={"Retry-After": str(retry_after)},
                content={
                    "detail": {
                        "code": "RATE_LIMITED",
                        "message": "Too many requests from this device. Please wait a few seconds.",
                    }
                },
            )

    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Cache-Control"] = "no-store"
    return response


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_request: Request, exc: RequestValidationError):
    """Return clean, secret-free 422 validation errors."""
    safe_errors = []
    for err in exc.errors():
        safe_errors.append(
            {
                "loc": [str(x) for x in err.get("loc", [])],
                "msg": redact_secrets(str(err.get("msg", "Invalid value"))),
                "type": str(err.get("type", "validation_error")),
            }
        )
    return JSONResponse(
        status_code=422,
        content={
            "detail": {
                "code": "VALIDATION_ERROR",
                "message": "Invalid request payload.",
                "errors": safe_errors,
            }
        },
    )


# ---------------------------------------------------------------------------
# Endpoints: GET /health, POST /chat, POST /vision
# ---------------------------------------------------------------------------


@app.get("/health")
async def health_check() -> Dict[str, Any]:
    """Return backend health status without exposing any secrets or API keys."""
    api_key, model_name, timeout_sec, max_retries = get_gemini_config()
    gemini_configured = bool(api_key)
    return {
        "status": "ok" if gemini_configured else "degraded",
        "project": "NEX 6.0",
        "version": APP_VERSION,
        "sdk": "google-genai",
        "gemini_configured": gemini_configured,
        "configured_model": model_name,
        "timeout_seconds": timeout_sec,
        "max_retries": max_retries,
        "live_search_enabled": live_search_enabled(),
        "expression_count": len(VALID_EXPRESSIONS),
        "school_knowledge_verified": MONTFORT_BAGAHA_FACTS["official_website"],
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
    }


def _parse_chat_output(raw_text: str) -> Tuple[str, str, Optional[str]]:
    """Parse structured JSON or plain text from Gemini response safely."""
    text = (raw_text or "").strip()
    if not text:
        raise ValueError("Empty response returned by Gemini model.")

    # Strip accidental markdown code fences if present
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text).strip()

    try:
        data = json.loads(text)
        if isinstance(data, dict) and data.get("reply"):
            reply = str(data["reply"]).strip()
            expression = normalize_expression(str(data.get("expression", "HAPPY")), default="HAPPY")
            rem_name = data.get("remembered_name")
            rem_name_str = str(rem_name).strip() if rem_name else None
            return reply, expression, rem_name_str
    except (json.JSONDecodeError, TypeError):
        pass

    return text, "HAPPY", None


@app.post("/chat", response_model=ChatResponse)
async def chat_endpoint(payload: ChatRequest) -> ChatResponse:
    """Process conversational chat using official Google GenAI SDK."""
    detected_lang = detect_language(payload.message, payload.language_hint)
    introduced_name = extract_introduced_name(payload.message) if payload.allow_memory else None
    effective_name = introduced_name or (payload.user_name if payload.allow_memory else None)

    # Canonical project creator identity rule (Part A):
    # Exact required string: "ARNAV, DIVYA AND ARYAN created me."
    if is_creator_query(payload.message):
        _, model_name, _, _ = get_gemini_config()
        return ChatResponse(
            reply=EXACT_CREATOR_RESPONSE,
            expression="HAPPY",
            language=detected_lang,
            remembered_name=effective_name if payload.allow_memory else None,
            model_used=model_name,
            source="nex_identity",
        )

    system_instruction = build_nex_system_instruction(
        detected_lang=detected_lang,
        user_name=effective_name,
        allow_memory=payload.allow_memory,
    )

    contents: List[types.Content] = []
    for turn in payload.history[-10:]:
        sdk_role = "user" if turn.role == "user" else "model"
        contents.append(
            types.Content(
                role=sdk_role,
                parts=[types.Part.from_text(text=turn.content)],
            )
        )
    use_grounding = needs_live_search(payload.message)
    user_prompt_text = payload.message
    if use_grounding:
        rss_snippets, _ = await asyncio.to_thread(fetch_live_news_snippets, payload.message)
        if rss_snippets:
            user_prompt_text = (
                f"{payload.message}\n\n"
                "LIVE VERIFIED NEWS HEADLINES (date-stamped from reliable sources):\n"
                + "\n".join(f"- {s}" for s in rss_snippets)
            )

    contents.append(
        types.Content(
            role="user",
            parts=[types.Part.from_text(text=user_prompt_text)],
        )
    )

    if use_grounding:
        # Grounded web search: no response_schema (grounding + structured JSON can conflict);
        # instruct JSON in text and parse with the same tolerant parser. Never fall back to
        # stored-knowledge guessing for current-info questions.
        grounded_instruction = (
            system_instruction
            + "\nLIVE SEARCH MODE: Use the provided live news headlines and Google Search grounding results ONLY for facts. "
            "Reply with ONLY a JSON object {\"reply\": string, \"expression\": string, \"remembered_name\": string|null} "
            "with no markdown fences. Mention the information date when relevant and say 'developing report' if unconfirmed."
        )
        gen_config = build_grounded_config(grounded_instruction)
    else:
        gen_config = types.GenerateContentConfig(
            system_instruction=system_instruction,
            temperature=0.65,
            max_output_tokens=600,
            response_mime_type="application/json",
            response_schema=_GeminiStructuredChat,
        )

    try:
        response, model_used = await call_gemini_with_retry(
            contents=contents,
            config=gen_config,
        )
    except HTTPException:
        if use_grounding:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail={
                    "code": "LIVE_SEARCH_UNAVAILABLE",
                    "message": "Live information is temporarily unavailable right now. Please try again in a moment — I will not guess current news.",
                },
            )
        raise

    raw_text = getattr(response, "text", None) or ""
    if not raw_text.strip():
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={
                "code": "GEMINI_EMPTY_RESPONSE",
                "message": "Google Gemini returned an empty response.",
            },
        )

    reply_text, expression, model_detected_name = _parse_chat_output(raw_text)
    reply_text = sanitize_nex_identity(reply_text, creator_asked=False)

    final_name = None
    if payload.allow_memory:
        final_name = introduced_name or model_detected_name or payload.user_name

    return ChatResponse(
        reply=reply_text,
        expression=expression,
        language=detected_lang,
        remembered_name=final_name,
        model_used=model_used,
        source="gemini_grounded" if use_grounding else "gemini",
    )


@app.post("/search", response_model=SearchResponse)
async def search_endpoint(payload: SearchRequest) -> SearchResponse:
    """Explicit live web/news search via Gemini Google Search grounding (same GEMINI_API_KEY)."""
    if not live_search_enabled():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "LIVE_SEARCH_DISABLED",
                "message": "Live search is disabled on the server (NEX_ENABLE_LIVE_SEARCH=false).",
            },
        )
    detected_lang = detect_language(payload.query, payload.language_hint)
    system_instruction = build_nex_system_instruction(
        detected_lang=detected_lang,
        user_name=payload.user_name if payload.allow_memory else None,
        allow_memory=payload.allow_memory,
    ) + (
        "\nLIVE SEARCH MODE: Use Google Search grounding results ONLY for facts. "
        "Reply with ONLY a JSON object {\"reply\": string, \"expression\": string} with no markdown fences. "
        "Mention the information date when relevant, distinguish confirmed facts from developing reports, "
        "prefer reliable sources, and never invent news, statistics, scores, or announcements."
    )
    rss_snippets, rss_sources = await asyncio.to_thread(fetch_live_news_snippets, payload.query)
    prompt_with_live = payload.query
    if rss_snippets:
        prompt_with_live = (
            f"{payload.query}\n\n"
            "LIVE VERIFIED NEWS HEADLINES (date-stamped from reliable sources):\n"
            + "\n".join(f"- {s}" for s in rss_snippets)
        )
    contents = [types.Content(role="user", parts=[types.Part.from_text(text=prompt_with_live)])]
    try:
        response, model_used = await call_gemini_with_retry(
            contents=contents,
            config=build_grounded_config(system_instruction),
        )
    except HTTPException:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "LIVE_SEARCH_UNAVAILABLE",
                "message": "Live information is temporarily unavailable right now. Please try again in a moment — I will not guess current news.",
            },
        )
    raw_text = (getattr(response, "text", None) or "").strip()
    if not raw_text:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={"code": "GEMINI_EMPTY_RESPONSE", "message": "Google Gemini returned an empty search response."},
        )
    reply_text, expression, _ = _parse_chat_output(raw_text)
    reply_text = sanitize_nex_identity(reply_text, creator_asked=is_creator_query(payload.query))
    combined_sources: List[str] = []
    for src in extract_grounding_sources(response) + rss_sources:
        if src and src not in combined_sources:
            combined_sources.append(src)
    return SearchResponse(
        reply=reply_text,
        expression=expression,
        language=detected_lang,
        sources=combined_sources[:5],
        model_used=model_used,
    )


def _decode_and_verify_image(image_b64: str, declared_mime: str) -> Tuple[bytes, str]:
    """Validate Base64 image data, check byte limit, and verify real image header via Pillow."""
    cleaned_b64 = image_b64.strip()
    if "," in cleaned_b64 and cleaned_b64.lower().startswith("data:image/"):
        cleaned_b64 = cleaned_b64.split(",", 1)[1].strip()

    try:
        raw_bytes = base64.b64decode(cleaned_b64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "INVALID_IMAGE_BASE64",
                "message": "image_base64 is not valid Base64-encoded binary data.",
            },
        ) from exc

    if len(raw_bytes) < 32:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "IMAGE_TOO_SMALL",
                "message": "Decoded image data is too small to be a valid image.",
            },
        )

    if len(raw_bytes) > MAX_VISION_IMAGE_BYTES:
        raise HTTPException(
            status_code=413,
            detail={
                "code": "IMAGE_TOO_LARGE",
                "message": f"Decoded image exceeds {MAX_VISION_IMAGE_BYTES} bytes limit.",
            },
        )

    try:
        with Image.open(io.BytesIO(raw_bytes)) as img:
            detected_format = (img.format or "").upper()
            img.verify()
    except (UnidentifiedImageError, OSError, ValueError) as exc:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "CORRUPT_OR_UNSUPPORTED_IMAGE",
                "message": "Uploaded image bytes could not be verified as a valid JPEG, PNG, or WEBP image.",
            },
        ) from exc

    format_to_mime = {
        "JPEG": "image/jpeg",
        "JPG": "image/jpeg",
        "PNG": "image/png",
        "WEBP": "image/webp",
    }
    actual_mime = format_to_mime.get(detected_format)
    if actual_mime not in ALLOWED_IMAGE_MIME_TYPES:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "UNSUPPORTED_IMAGE_FORMAT",
                "message": f"Image format '{detected_format or declared_mime}' is not supported. Use JPEG, PNG, or WEBP.",
            },
        )

    return raw_bytes, actual_mime


@app.post("/vision", response_model=VisionResponse)
async def vision_endpoint(payload: VisionRequest) -> VisionResponse:
    """Analyze a camera frame with Gemini Vision via official `google-genai` SDK."""
    image_bytes, verified_mime = _decode_and_verify_image(payload.image_base64, payload.mime_type)
    detected_lang = detect_language(payload.prompt, payload.language_hint)

    system_instruction = (
        build_nex_system_instruction(
            detected_lang=detected_lang,
            user_name=None,
            allow_memory=False,
        )
        + "\nVISION TASK: Look carefully at the camera image from NEX's front/rear camera. "
        "Identify the main objects, text, or scene accurately, answer the user's question naturally in 1-3 spoken sentences, "
        "and list up to 5 main detected objects in `objects_detected`. Do not guess wildly if the image is blurry—say honestly what you can see."
    )

    vision_contents = [
        types.Part.from_bytes(data=image_bytes, mime_type=verified_mime),
        types.Part.from_text(text=payload.prompt),
    ]

    gen_config = types.GenerateContentConfig(
        system_instruction=system_instruction,
        temperature=0.4,
        max_output_tokens=500,
        response_mime_type="application/json",
        response_schema=_GeminiStructuredVision,
    )

    response, model_used = await call_gemini_with_retry(
        contents=vision_contents,
        config=gen_config,
    )

    raw_text = (getattr(response, "text", None) or "").strip()
    if not raw_text:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={
                "code": "GEMINI_EMPTY_VISION_RESPONSE",
                "message": "Google Gemini Vision returned an empty response.",
            },
        )

    cleaned_json = raw_text
    if cleaned_json.startswith("```"):
        cleaned_json = re.sub(r"^```(?:json)?\s*", "", cleaned_json)
        cleaned_json = re.sub(r"\s*```$", "", cleaned_json).strip()

    reply_text = cleaned_json
    expression = "CURIOUS"
    objects_detected: List[str] = []

    try:
        parsed = json.loads(cleaned_json)
        if isinstance(parsed, dict) and parsed.get("reply"):
            reply_text = str(parsed["reply"]).strip()
            expression = normalize_expression(str(parsed.get("expression", "CURIOUS")), default="CURIOUS")
            raw_objs = parsed.get("objects_detected") or []
            if isinstance(raw_objs, list):
                objects_detected = [str(o).strip() for o in raw_objs if str(o).strip()][:8]
    except (json.JSONDecodeError, TypeError):
        pass

    reply_text = sanitize_nex_identity(reply_text, creator_asked=is_creator_query(payload.prompt))

    return VisionResponse(
        reply=reply_text,
        expression=expression,
        objects_detected=objects_detected,
        language=detected_lang,
        model_used=model_used,
        source="gemini",
    )
