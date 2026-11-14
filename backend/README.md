# NEX 6.0 Backend (`backend/`)

Secure Python 3.11 FastAPI backend for **NEX 6.0** — the AI-powered virtual robot pet created by **ARNAV, DIVYA AND ARYAN** for the school computer exhibition at **Montfort Senior Secondary School, Bagaha**.

## Key Architecture & Security Rules

- **Official SDK Only**: Uses Google's official `google-genai` SDK (`from google import genai`). No third-party or fallback AI providers, no fake/demo AI responses.
- **Secret Isolation**: Reads `GEMINI_API_KEY` and `GEMINI_MODEL` strictly from environment variables. Credentials are scrubbed from logs and never exposed by `/health` or error payloads.
- **Verified Model Selection**: Defaults to `gemini-3.5-flash-lite` (verified in official Google Gemini API docs as stable, Free-Tier + Paid-Tier supported, multimodal text + image input via `client.models.generate_content`). You may also set `GEMINI_MODEL=gemini-3.8-flash` in Render environment variables.
- **Resilience**: Enforces HTTP request timeouts, bounded retries (`max_retries=2`) with exponential backoff on transient `503`/`504`/timeout failures, per-IP sliding-window rate limiting, payload size caps, and clean error mapping (`401`, `403`, `404`, `429`, `503`, `504`).

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Service & configuration health check (never exposes secrets). |
| `POST` | `/chat` | Conversational turn with language matching (`en`, `hi`, `hinglish`), eye expression tag, conversation context, creator identity rule, and Montfort School Bagaha verified facts. |
| `POST` | `/vision` | Multimodal camera image understanding (`image/jpeg`, `image/png`, `image/webp` up to 4 MB) via `google-genai`. |

## Render Deployment Configuration

- **Root Directory**: `backend`
- **Runtime**: Python `3.11.9` (`runtime.txt` / `.python-version` included)
- **Build Command**: `pip install -r requirements.txt`
- **Start Command**: `uvicorn app:app --host 0.0.0.0 --port $PORT`
- **Environment Variables** (set in Render Dashboard -> Environment):
  - `GEMINI_API_KEY` = *(Your Google AI Studio API Key — keep secret)*
  - `GEMINI_MODEL` = `gemini-3.5-flash-lite`

Or deploy automatically via the repository root `render.yaml` Blueprint in Render.
