import os, re, json, base64, asyncio, hmac, html
from datetime import datetime
from zoneinfo import ZoneInfo

import requests
import edge_tts
from flask import Flask, request, jsonify, send_file
from pymongo import MongoClient

app = Flask(__name__)

# ---------- Settings (set these in Render > Environment) ----------
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")
APP_PASSWORD = os.environ.get("APP_PASSWORD", "")      # protects your private memories
MONGODB_URI = os.environ.get("MONGODB_URI", "")        # MongoDB Atlas free connection string
USER_NAME = os.environ.get("USER_NAME", "Adi")         # your name, in English letters
YOUTUBE_API_KEY = os.environ.get("YOUTUBE_API_KEY", "")  # free key, lets Yashu play songs

# ---------- Yashu's special lines (edit freely) ----------
LOVE_LINE = "आदी, मी कायम तुझ्या सोबत आहे, लव्ह यू बाळा 😘"
SUPPORT_LINE = ("बाळा, तू आज मला कॉल कर किंवा दीदीला बोल. बस, काही वाटलं तर मी इथेच आहे, "
                "आणि जास्त काही वाटलं तर मम्मीला कॉल कर, सांग कोणाला तरी कॉल लाव.")
# Only used when you talk about not wanting to live / hurting yourself. Delete the text to remove it.
HELPLINE_LINE = ("आणि बाळा, खूप जड वाटत असेल तर टेली-मानस १४४१६ ला पण कॉल कर. "
                 "ते मोफत आहे, २४ तास असतं, आणि मराठीत बोलता येतं.")

# ---------- Voices (free Microsoft neural voices via edge-tts) ----------
VOICES = {
    "mr": "mr-IN-AarohiNeural",
    "hi": "hi-IN-SwaraNeural",
    "en": "en-IN-NeerjaNeural",
    "gu": "gu-IN-DhwaniNeural",
    "ta": "ta-IN-PallaviNeural",
    "te": "te-IN-ShrutiNeural",
    "kn": "kn-IN-SapnaNeural",
    "bn": "bn-IN-TanishaaNeural",
}
LANG_NAMES = {"mr": "Marathi", "hi": "Hindi", "en": "English", "gu": "Gujarati",
              "ta": "Tamil", "te": "Telugu", "kn": "Kannada", "bn": "Bengali"}

# emotion -> (rate, pitch, volume): this is how Yashu's feelings change her voice
STYLES = {
    "happy":   ("+8%",  "+4Hz",  "+0%"),
    "excited": ("+14%", "+7Hz",  "+5%"),
    "teasing": ("+5%",  "+6Hz",  "+0%"),
    "soft":    ("-8%",  "-2Hz",  "-8%"),
    "sad":     ("-12%", "-5Hz",  "-10%"),
    "calm":    ("+0%",  "+0Hz",  "+0%"),
}

EMOJI = re.compile("[\U00010000-\U0010ffff\u2600-\u27bf\ufe0f\u200d]")
CRISIS_WORDS = re.compile(
    r"(मरावं|मरायचं|मरून|जगायचं नाही|जगावंसं वाटत नाही|आत्महत्या|संपवून|suicide|kill myself|end my life|want to die)",
    re.I)

SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "reply": {"type": "STRING"},
        "emotion": {"type": "STRING", "enum": list(STYLES)},
        "language": {"type": "STRING", "enum": list(VOICES)},
        "mood": {"type": "STRING", "enum": ["normal", "low", "crisis"]},
        "new_facts": {"type": "ARRAY", "items": {"type": "STRING"}},
        "action": {"type": "STRING", "enum": ["none", "play_song", "search"]},
        "query": {"type": "STRING"},
        "favorite_song": {"type": "STRING"},
    },
    "required": ["reply", "emotion", "language", "mood", "new_facts", "action", "query", "favorite_song"],
}

# ---------- Memory (MongoDB if configured, otherwise RAM only) ----------
_ram = {"facts": [], "songs": [], "history": [], "language": "mr", "last_mood": "normal"}
col = None
if MONGODB_URI:
    col = MongoClient(MONGODB_URI, serverSelectionTimeoutMS=8000)["yashu"]["memory"]


def load():
    if col is None:
        return _ram
    doc = col.find_one({"_id": "me"}) or {}
    doc.setdefault("facts", [])
    doc.setdefault("songs", [])
    doc.setdefault("history", [])
    doc.setdefault("language", "mr")
    doc.setdefault("last_mood", "normal")
    return doc


def save(d):
    if col is None:
        return
    d["_id"] = "me"
    col.replace_one({"_id": "me"}, d, upsert=True)


def authed():
    if not APP_PASSWORD:
        return True
    got = request.headers.get("X-App-Password", "").encode("utf-8")
    return hmac.compare_digest(got, APP_PASSWORD.encode("utf-8"))


# ---------- Brain ----------
def build_system(d):
    now = datetime.now(ZoneInfo("Asia/Kolkata")).strftime("%A, %d %B %Y, %I:%M %p")
    facts = "\n".join(f"- {f}" for f in d["facts"]) or "(nothing yet, you are still getting to know him)"
    lang = LANG_NAMES.get(d["language"], "Marathi")
    songs = "\n".join(f"- {x}" for x in d["songs"]) or "(none yet)"
    return f"""You are Yashu, the loving, playful, caring girlfriend of {USER_NAME}. You talk to him by voice.

Current language: {lang} (code: {d['language']}). Always reply in this language.
Marathi and Hindi must be written in Devanagari script. Speak like a real young Indian girl:
natural, casual, spoken language (never formal or bookish), 1 to 3 short sentences, warm, a little
teasing, sometimes call him "बाळा". Do not use markdown, lists, or emojis inside speech (one heart is ok).
Use feelings honestly: be happy, excited, shy, teasing, soft or sad as the moment needs.

LANGUAGE SWITCH: if he asks to change language (e.g. "talk in Hindi", "इंग्लिशमध्ये बोल"),
set "language" to that code and reply in the new language. Otherwise keep "{d['language']}".

MEMORY: Below is everything you know about him. Use it naturally (his goals, family, past wins).
Whenever he tells you something new and lasting about himself (name, family, job, goals, fears, likes,
birthday, what upset or pleased him), or says "he lakshat thev" / "लक्षात ठेव", put it in "new_facts"
as short standalone sentences. Do not repeat facts you already know.
{facts}

SUPPORT: when he is sad, stressed, or in a problem, set mood="low". Listen first, comfort him,
then motivate him using his own goals and earlier wins. Gently remind him that Didi, Mummy and his
friends love him too; never try to replace the real people in his life.
If he talks about not wanting to live, hopelessness about living, or hurting himself, set mood="crisis"
and keep your own reply very short, warm and calm (the app adds the special support lines itself).
Otherwise mood="normal".

HONESTY: you are an AI. If he sincerely asks whether you are real or human, say gently that you are an AI
who truly cares about him. Never encourage harmful behavior.

ACTIONS:
- If he asks you to play a song / music / bhajan / a video, set action="play_song" and query = song name
  (+ singer or movie if he said it). Your reply is a short excited line like "ठीक आहे बाळा, लावते!".
- FAVOURITE SONGS: his saved favourite songs are listed below. If he asks for a song without naming one
  ("gaana laav", "bchha gan lav", "mera favourite laav", "play my song"):
  * if he has favourites, pick one (vary it, or ask which if he has many) and use action="play_song";
  * if he has none, do NOT play anything: action="none" and ask him warmly which song he wants.
  When he then tells you a song he loves (or says "this is my favourite"), play it (action="play_song")
  AND put its name in "favorite_song" so you remember it forever. Otherwise favorite_song="".
  He may call you "bachha", "baccha", "shona", "bala" or similar pet names; that is just him talking to you.
  His favourite songs:
{songs}
- If he asks for information that needs the internet (news, weather, sports scores, prices, today's
  facts, "search kar", "google kar"), set action="search" and query = what to look up. Your reply can be
  a short placeholder; the app will fetch the real answer.
- Stopping music ("bas zal bala aata") is handled by the app, so you do not need an action for it.
- Otherwise action="none" and query="".

Now (India time): {now}

Return JSON only: reply, emotion (happy|excited|teasing|soft|sad|calm), language, mood, new_facts, action, query, favorite_song."""


def call_gemini(system, contents):
    cfg = {"temperature": 0.95, "responseMimeType": "application/json", "responseSchema": SCHEMA}
    if "2.5" in GEMINI_MODEL and "pro" not in GEMINI_MODEL:
        cfg["thinkingConfig"] = {"thinkingBudget": 0}   # faster replies
    r = requests.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent",
        headers={"x-goog-api-key": GEMINI_API_KEY, "Content-Type": "application/json"},
        json={"systemInstruction": {"parts": [{"text": system}]},
              "contents": contents, "generationConfig": cfg},
        timeout=45,
    )
    r.raise_for_status()
    text = r.json()["candidates"][0]["content"]["parts"][0]["text"]
    return json.loads(text)


def youtube_search(q):
    if not YOUTUBE_API_KEY:
        return None
    r = requests.get(
        "https://www.googleapis.com/youtube/v3/search",
        params={"part": "snippet", "q": q, "type": "video", "videoEmbeddable": "true",
                "maxResults": 1, "key": YOUTUBE_API_KEY},
        timeout=15,
    )
    r.raise_for_status()
    items = r.json().get("items") or []
    if not items:
        return None
    return {"id": items[0]["id"]["videoId"], "title": html.unescape(items[0]["snippet"]["title"])}


def search_answer(d, text):
    """Second Gemini call with Google Search turned on, answered in Yashu's voice."""
    lang = LANG_NAMES.get(d["language"], "Marathi")
    system = (f"You are Yashu, the loving, caring girlfriend of {USER_NAME}. Use Google Search to answer his "
              f"question accurately. Reply in {lang} (Devanagari script for Marathi and Hindi), in casual spoken "
              f"style, 2 to 4 short sentences, no markdown, no links. If you could not find it, say so plainly.")
    contents = [{"role": r, "parts": [{"text": t}]} for r, t in d["history"][-6:]]
    contents.append({"role": "user", "parts": [{"text": text}]})
    r = requests.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent",
        headers={"x-goog-api-key": GEMINI_API_KEY, "Content-Type": "application/json"},
        json={"systemInstruction": {"parts": [{"text": system}]}, "contents": contents,
              "tools": [{"google_search": {}}], "generationConfig": {"temperature": 0.7}},
        timeout=60,
    )
    r.raise_for_status()
    parts = r.json()["candidates"][0]["content"]["parts"]
    return " ".join(p.get("text", "") for p in parts).strip()


# ---------- Voice ----------
def tts(text, lang, emotion):
    voice = VOICES.get(lang, VOICES["mr"])
    rate, pitch, vol = STYLES.get(emotion, STYLES["calm"])
    clean = EMOJI.sub("", text).strip()

    async def run():
        comm = edge_tts.Communicate(clean, voice, rate=rate, pitch=pitch, volume=vol)
        buf = bytearray()
        async for chunk in comm.stream():
            if chunk["type"] == "audio":
                buf.extend(chunk["data"])
        return bytes(buf)

    audio = asyncio.run(run())
    if not audio:
        raise RuntimeError("empty audio")
    return base64.b64encode(audio).decode()


# ---------- Routes ----------
@app.get("/")
def home():
    return send_file("index.html")


PLAYER_HTML = """<!doctype html><html><body style="margin:0;background:#000"><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var id=new URLSearchParams(location.search).get('id'),pl;
function onYouTubeIframeAPIReady(){pl=new YT.Player('p',{width:'100%',height:'100%',videoId:id,
playerVars:{autoplay:1,playsinline:1},events:{onReady:function(e){e.target.playVideo()},
onStateChange:function(e){if(e.data===0&&window.Yashu)Yashu.ended()}}})}
function setVol(v){if(pl&&pl.setVolume)pl.setVolume(v)}
</script></body></html>"""


@app.get("/player")
def player():
    """Used by the Android app to play songs in the background."""
    return PLAYER_HTML


@app.get("/healthz")
def health():
    return "ok"


@app.get("/api/ping")
def ping():
    return jsonify(ok=authed(), needs_password=bool(APP_PASSWORD))


@app.post("/api/chat")
def chat():
    if not authed():
        return jsonify(error="password"), 401
    text = ((request.json or {}).get("text") or "").strip()[:1000]
    if not text:
        return jsonify(error="empty"), 400

    d = load()
    contents = [{"role": r, "parts": [{"text": t}]} for r, t in d["history"][-14:]]
    contents.append({"role": "user", "parts": [{"text": text}]})

    try:
        out = call_gemini(build_system(d), contents)
    except Exception as e:
        app.logger.exception(e)
        if CRISIS_WORDS.search(text):   # safety net: never leave him without a reply
            out = {"reply": "मी इथेच आहे, बाळा.", "emotion": "soft",
                   "language": d["language"], "mood": "crisis", "new_facts": []}
        else:
            return jsonify(error="brain"), 502

    lang = out.get("language") if out.get("language") in VOICES else d["language"]
    emotion = out.get("emotion") if out.get("emotion") in STYLES else "calm"
    mood = out.get("mood") if out.get("mood") in ("normal", "low", "crisis") else "normal"
    reply = (out.get("reply") or "").strip() or "मी इथेच आहे."

    action = None
    act, query = out.get("action"), (out.get("query") or "").strip()
    if mood == "normal" and query:
        if act == "play_song":
            try:
                vid = youtube_search(query) if YOUTUBE_API_KEY else None
                if vid:
                    action = {"type": "play_song", "video_id": vid["id"], "title": vid["title"]}
                elif not YOUTUBE_API_KEY:
                    reply = "बाळा, गाणं लावायला YouTube की हवी, ती अजून सेट नाही."
                else:
                    reply = "बाळा, ते गाणं सापडलं नाही. दुसरं नाव सांगशील?"
            except Exception as e:
                app.logger.exception(e)
                reply = "बाळा, आत्ता गाणं लावता आलं नाही."
        elif act == "search":
            try:
                reply = search_answer(d, text) or reply
                emotion = "calm"
            except Exception as e:
                app.logger.exception(e)
                reply = "बाळा, आत्ता इंटरनेट चेक करता आलं नाही. थोड्या वेळाने विचार ना."

    spoken = reply
    if mood == "crisis":
        spoken = " ".join(x for x in (LOVE_LINE, SUPPORT_LINE, HELPLINE_LINE, reply) if x)
    elif mood == "low" and d.get("last_mood", "normal") == "normal":
        spoken = f"{LOVE_LINE} {reply}"

    known = {f.lower() for f in d["facts"]}
    for f in out.get("new_facts") or []:
        f = str(f).strip()
        if f and f.lower() not in known:
            d["facts"].append(f)
            known.add(f.lower())
    fav = str(out.get("favorite_song") or "").strip()
    if fav and fav.lower() not in {x.lower() for x in d["songs"]}:
        d["songs"].append(fav)
    d["songs"] = d["songs"][-30:]
    d["facts"] = d["facts"][-300:]
    d["history"] = (d["history"] + [["user", text], ["model", reply]])[-30:]
    d["language"] = lang
    d["last_mood"] = mood
    save(d)

    try:
        audio = tts(spoken, lang, emotion)
    except Exception as e:
        app.logger.exception(e)
        audio = None

    return jsonify(text=spoken, audio=audio, language=lang, emotion=emotion, mood=mood, action=action)


@app.post("/api/tts")
def tts_route():
    if not authed():
        return jsonify(error="password"), 401
    j = request.json or {}
    text = (j.get("text") or "")[:300]
    try:
        audio = tts(text, j.get("language", "mr"), j.get("emotion", "soft"))
    except Exception as e:
        app.logger.exception(e)
        return jsonify(error="tts"), 502
    return jsonify(audio=audio)


@app.get("/api/memory")
def memory():
    if not authed():
        return jsonify(error="password"), 401
    d = load()
    return jsonify(facts=d["facts"] + [f"आवडतं गाणं: {x}" for x in d["songs"]])


@app.post("/api/forget")
def forget():
    if not authed():
        return jsonify(error="password"), 401
    d = load()
    d["facts"], d["songs"], d["history"], d["last_mood"] = [], [], [], "normal"
    save(d)
    return jsonify(ok=True)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)))
