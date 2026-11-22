# News Queue Poster (Android)
Android port of the News Queue Poster Chrome extension (v1.5.0).

- Add article URLs, pull from news source pages, or **share a link from any browser/app** to this app.
- Extracts title, lead, short URL and photo; builds an X post with X's weighted 280-char limit; optional AI rewrite (Groq, Gemini, OpenRouter, Anthropic, any OpenAI-compatible).
- Queue with approval, random scheduling inside your active hours, per-hour cap and minimum gap.
- Posting: **API mode** (X API v2 bearer token, or Buffer/Typefully/webhook) posts automatically in the background; **Share mode** notifies you when a post is due and opens the X app with text + photo ready.
## Build
Push to GitHub → Actions → "Build APK" → download the artifact. Or `gradle assembleDebug`.
