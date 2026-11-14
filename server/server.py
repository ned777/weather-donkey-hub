#!/usr/bin/env python3
"""
Weather Donkey Hub — the home server behind the Weather Donkey app and widget.

Same philosophy as Cookbook and Vehicle Maintenance Record: one file, plain
Python stdlib, nothing to pip install.

What it does:
  * Pulls weather from THREE free, keyless, worldwide sources — Open-Meteo
    (primary), MET Norway and 7Timer! (backups). Whatever is down is skipped and the response carries a short
    `notice` saying so; a request fails only if every source is down.
  * Records ONLY its own location ("Home" — where this computer is) every
    2 hours, plus whenever Home is refreshed, into a local SQLite database.
    Saved cities and the phone's GPS position are shown live, never recorded.
  * Keeps those recordings for 10 years, then deletes them.
  * Answers "what was the weather on <date> [at <time>]?" from its own
    recordings first, falling back to Open-Meteo's historical archive (back
    to 1940) for anything it never recorded.

The phone never talks to the weather services directly anymore — it only
talks to this server, on the home network.

Run:  python3 server.py
Serves the web page (web/index.html) and JSON on 0.0.0.0:8094 (override with WEATHER_DONKEY_PORT). Data lives
in ./data/weather.db next to this file (override with WEATHER_DONKEY_DATA).

Web page:  /  — current weather per location, "What Was the Weather?"
lookups, and the °F/°C converter, in the same look as the app.

API (all GET unless noted, all JSON):
  /api/health
  /api/weather?id=<loc id>&name=<display name>&lat=<lat>&lon=<lon>
      Fetches fresh weather in exactly the shape the app caches.
      Recorded only when id=home (the server's own location).
  /api/history?id=<loc id>&lat=<lat>&lon=<lon>&date=YYYY-MM-DD[&time=HH:MM]
      What the weather was on that day (and at that hour, if given).
  /api/locations[?all=1]             — Home + saved cities (all=1: also removed ones)
  /api/search?q=<city or US ZIP>     — find places to add
  POST /api/locations?name=&lat=&lon= — save a city (shown as a tab, not recorded)
  POST /api/home?name=&lat=&lon=     — move the server's Home location
  DELETE /api/locations?id=<loc id>  — stop recording (history is kept)

Recording: ONLY Home (the server's own location), every 2 hours. Saved cities and the
phone's "Current" (GPS) tab are shown live and never recorded; their history
comes from the Open-Meteo archive (or Home's recordings when within 10 km).
"""
import hashlib
import json
import logging
import math
import os
import re
import sqlite3
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

PORT = int(os.environ.get("WEATHER_DONKEY_PORT", "8094"))
DATA_DIR = os.environ.get(
    "WEATHER_DONKEY_DATA",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"),
)
DB_PATH = os.path.join(DATA_DIR, "weather.db")
WEB_PAGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web", "index.html")

RETENTION_YEARS = 10
HOURLY_COUNT = 12          # the app's "Next 12 Hours" strip
FORECAST_DAYS = 5          # the app's "5-Day Forecast" list (after today)
HTTP_TIMEOUT_S = 4         # a stuck connection is dropped after 4 s…
RETRY_DELAYS_S = (0.5, 1.5)  # …and tried again twice; all sources run in parallel
RECORD_EVERY_HOURS = 2     # Home is recorded every 2 hours (00:02, 02:02, …)
RECORD_MINUTE = 2

# MET Norway's terms require a User-Agent that identifies the app.
USER_AGENT = "WeatherDonkeyHub/1.0 (+https://github.com/ned777/weather-donkey-hub)"

# The server's own location — always recorded hourly, whatever the phone is doing.
# Override with WEATHER_DONKEY_HOME="Name|lat|lon"; can also be changed from the web page.
HOME_ID = "home"
DEFAULT_HOME = os.environ.get("WEATHER_DONKEY_HOME", "Carson, CA|33.8317|-118.2817")
# The phone's GPS tab. Shown live but never recorded (it moves when you travel).
CURRENT_ID = "current"
# History for a spot within this distance of a recorded location uses that location's recordings.
NEARBY_KM = 10

SOURCE_OPEN_METEO = "open-meteo"
SOURCE_MET = "met-norway"
SOURCE_7TIMER = "7timer"
SOURCE_LABELS = {SOURCE_OPEN_METEO: "Open-Meteo", SOURCE_MET: "MET Norway", SOURCE_7TIMER: "7Timer!"}
# All three are free, keyless and worldwide. When several answer, the first one
# here is shown: Open-Meteo (most detail), MET Norway (hourly), 7Timer! (3-hourly).
SOURCE_PRIORITY = (SOURCE_OPEN_METEO, SOURCE_MET, SOURCE_7TIMER)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("weather-donkey")


# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

_db_lock = threading.Lock()


def db():
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    os.makedirs(DATA_DIR, exist_ok=True)
    _create_tables()
    ensure_home()


def _create_tables():
    with _db_lock, db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS locations (
                id TEXT PRIMARY KEY,
                name TEXT,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                tz TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                added_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            -- One row per location, per source, per hour. A refresh within the
            -- same hour overwrites that hour's row instead of adding another.
            CREATE TABLE IF NOT EXISTS observations (
                location_id TEXT NOT NULL,
                source TEXT NOT NULL,
                hour_utc INTEGER NOT NULL,      -- unix seconds, start of the hour
                local_time TEXT NOT NULL,       -- e.g. 2026-11-14T15:00 at that place
                lat REAL, lon REAL,
                temp_f REAL, code INTEGER, is_day INTEGER,
                wind_mph REAL, rain_pct INTEGER,
                recorded_at INTEGER NOT NULL,
                PRIMARY KEY (location_id, source, hour_utc)
            );
            CREATE INDEX IF NOT EXISTS observations_by_local
                ON observations (location_id, local_time);
            -- One row per location, per source, per local day; today's row is
            -- refreshed every hour, so it ends the day as that day's summary.
            CREATE TABLE IF NOT EXISTS daily (
                location_id TEXT NOT NULL,
                source TEXT NOT NULL,
                date TEXT NOT NULL,
                high_f REAL, low_f REAL, code INTEGER,
                wind_mph REAL, rain_pct INTEGER,
                sunrise TEXT, sunset TEXT,
                recorded_at INTEGER NOT NULL,
                PRIMARY KEY (location_id, source, date)
            );
            """
        )


def ensure_home():
    """Makes sure the server's own Home location exists (adopting old "current" recordings)."""
    now = int(time.time())
    with _db_lock, db() as conn:
        if conn.execute("SELECT 1 FROM locations WHERE id = ?", (HOME_ID,)).fetchone():
            return
        old = conn.execute("SELECT * FROM locations WHERE id = ?", (CURRENT_ID,)).fetchone()
        if old:
            conn.execute(
                "INSERT INTO locations (id, name, lat, lon, tz, active, added_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                (HOME_ID, old["name"] or "Home", old["lat"], old["lon"], old["tz"], old["added_at"], now))
            for table in ("observations", "daily"):
                conn.execute(f"UPDATE {table} SET location_id = ? WHERE location_id = ?", (HOME_ID, CURRENT_ID))
            conn.execute("DELETE FROM locations WHERE id = ?", (CURRENT_ID,))
            log.info("Home location created from the old 'current' recordings (%s)", old["name"])
        else:
            name, lat, lon = DEFAULT_HOME.split("|")
            conn.execute(
                "INSERT INTO locations (id, name, lat, lon, active, added_at, updated_at) "
                "VALUES (?, ?, ?, ?, 1, ?, ?)", (HOME_ID, name, float(lat), float(lon), now, now))
            log.info("Home location set to %s", name)


def set_home(name, lat, lon):
    now = int(time.time())
    with _db_lock, db() as conn:
        conn.execute("UPDATE locations SET name = ?, lat = ?, lon = ?, tz = NULL, active = 1, updated_at = ? "
                     "WHERE id = ?", (name, lat, lon, now, HOME_ID))


def location_id_for(lat, lon):
    # Same id format the app uses for its city tabs, so both sides agree.
    return f"{lat:.4f},{lon:.4f}"


def all_locations(include_inactive=False):
    sql = "SELECT * FROM locations" + ("" if include_inactive else " WHERE active = 1")
    with _db_lock, db() as conn:
        rows = [dict(r) for r in conn.execute(sql)]
    rows.sort(key=lambda r: (r["id"] != HOME_ID, r["added_at"]))
    return rows


def distance_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371 * 2 * math.asin(math.sqrt(a))


def nearest_locations(lat, lon, max_km):
    found = [(distance_km(lat, lon, l["lat"], l["lon"]), l) for l in all_locations(include_inactive=True)]
    return [l for d, l in sorted(found, key=lambda x: x[0]) if d <= max_km]


def upsert_location(loc_id, name, lat, lon):
    now = int(time.time())
    with _db_lock, db() as conn:
        conn.execute(
            """
            INSERT INTO locations (id, name, lat, lon, active, added_at, updated_at)
            VALUES (?, ?, ?, ?, 1, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = COALESCE(excluded.name, locations.name),
                lat = excluded.lat, lon = excluded.lon,
                active = 1, updated_at = excluded.updated_at
            """,
            (loc_id, name, lat, lon, now, now),
        )


def get_location(loc_id):
    with _db_lock, db() as conn:
        row = conn.execute("SELECT * FROM locations WHERE id = ?", (loc_id,)).fetchone()
    return dict(row) if row else None


def set_location_tz(loc_id, tz):
    with _db_lock, db() as conn:
        conn.execute("UPDATE locations SET tz = ? WHERE id = ?", (tz, loc_id))


def active_locations():
    return all_locations()


def deactivate_location(loc_id):
    with _db_lock, db() as conn:
        cur = conn.execute("UPDATE locations SET active = 0 WHERE id = ?", (loc_id,))
        return cur.rowcount > 0


def record(loc_id, lat, lon, data):
    """Saves one source's current reading (this hour) and today's summary."""
    now = int(time.time())
    hour_utc = now - now % 3600
    cur = data["current"]
    today = data["days"][0] if data["days"] else None
    with _db_lock, db() as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO observations
                (location_id, source, hour_utc, local_time, lat, lon,
                 temp_f, code, is_day, wind_mph, rain_pct, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (loc_id, data["source"], hour_utc, cur["time"][:13] + ":00", lat, lon,
             cur["temp_f"], cur["code"], int(cur["is_day"]), cur["wind_mph"],
             today["rain_pct"] if today else -1, now),
        )
        if today:
            conn.execute(
                """
                INSERT OR REPLACE INTO daily
                    (location_id, source, date, high_f, low_f, code, wind_mph,
                     rain_pct, sunrise, sunset, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (loc_id, data["source"], today["date"], today["high_f"], today["low_f"],
                 today["code"], today["wind_mph"], today["rain_pct"],
                 today["sunrise"], today["sunset"], now),
            )


def _next_recording(now):
    """Next HH:02 where HH is a multiple of RECORD_EVERY_HOURS (00:02, 02:02, …)."""
    run = now.replace(minute=RECORD_MINUTE, second=0, microsecond=0)
    run -= timedelta(hours=run.hour % RECORD_EVERY_HOURS)
    while run <= now:
        run += timedelta(hours=RECORD_EVERY_HOURS)
    return run


def health_stats():
    with _db_lock, db() as conn:
        obs = conn.execute("SELECT COUNT(*), MIN(hour_utc), MAX(recorded_at) FROM observations").fetchone()
        days = conn.execute("SELECT COUNT(DISTINCT location_id || date) FROM daily").fetchone()[0]
    size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    next_run = _next_recording(datetime.now())
    return {
        "ok": True,
        "locations": len(active_locations()),
        "hourly_records": obs[0],
        "daily_records": days,
        "recording_since": obs[1],
        "last_recorded": obs[2],
        "next_recording": int(next_run.timestamp()),
        "db_bytes": size,
        "retention_years": RETENTION_YEARS,
        "record_every_hours": RECORD_EVERY_HOURS,
    }


def purge_old_records():
    """Deletes everything recorded more than RETENTION_YEARS ago."""
    today = date.today()
    try:
        cutoff_date = today.replace(year=today.year - RETENTION_YEARS)
    except ValueError:  # Feb 29 → Feb 28
        cutoff_date = today.replace(year=today.year - RETENTION_YEARS, day=28)
    cutoff_ts = int(datetime.combine(cutoff_date, datetime.min.time(), timezone.utc).timestamp())
    with _db_lock, db() as conn:
        a = conn.execute("DELETE FROM observations WHERE hour_utc < ?", (cutoff_ts,)).rowcount
        b = conn.execute("DELETE FROM daily WHERE date < ?", (cutoff_date.isoformat(),)).rowcount
    if a or b:
        log.info("Retention: deleted %d hourly and %d daily records older than %s", a, b, cutoff_date)


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def http_get_json(url):
    last_error = None
    for attempt in range(len(RETRY_DELAYS_S) + 1):
        if attempt:
            time.sleep(RETRY_DELAYS_S[attempt - 1])
        try:
            req = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
            with urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as e:  # network, HTTP error, bad JSON — all "try again"
            last_error = e
    raise last_error


def zone_for(tz_name, lon):
    if tz_name:
        try:
            return ZoneInfo(tz_name)
        except Exception:
            pass
    # Rough fallback when we've never learned the real time zone (only
    # possible if Open-Meteo has never once answered for this location).
    return timezone(timedelta(hours=round(lon / 15)))


# ---------------------------------------------------------------------------
# Sun position (needed when MET Norway is the only source — it has no
# sunrise/sunset in its forecast). NOAA's simplified solar equations.
# ---------------------------------------------------------------------------

def sun_times(day, lat, lon, tz):
    """Returns (sunrise, sunset) as local datetimes, or (None, None) at the poles."""
    g = 2 * math.pi / 365 * (day.timetuple().tm_yday - 1)
    eqtime = 229.18 * (0.000075 + 0.001868 * math.cos(g) - 0.032077 * math.sin(g)
                       - 0.014615 * math.cos(2 * g) - 0.040849 * math.sin(2 * g))
    decl = (0.006918 - 0.399912 * math.cos(g) + 0.070257 * math.sin(g)
            - 0.006758 * math.cos(2 * g) + 0.000907 * math.sin(2 * g)
            - 0.002697 * math.cos(3 * g) + 0.00148 * math.sin(3 * g))
    lat_r = math.radians(lat)
    cos_ha = (math.cos(math.radians(90.833)) / (math.cos(lat_r) * math.cos(decl))
              - math.tan(lat_r) * math.tan(decl))
    if not -1 <= cos_ha <= 1:
        return None, None
    ha = math.degrees(math.acos(cos_ha))
    # The formula gives minutes after UTC midnight of `day` (can be <0 or >1440
    # far from Greenwich); converting to the local zone lands on the local day.
    midnight_utc = datetime(day.year, day.month, day.day, tzinfo=timezone.utc)
    rise = (midnight_utc + timedelta(minutes=720 - 4 * (lon + ha) - eqtime)).astimezone(tz)
    sset = (midnight_utc + timedelta(minutes=720 - 4 * (lon - ha) - eqtime)).astimezone(tz)
    # Safety net: keep both on the requested local calendar date.
    rise += timedelta(days=(day - rise.date()).days)
    sset += timedelta(days=(day - sset.date()).days)
    return rise, sset


def local_iso_minute(dt):
    return dt.strftime("%Y-%m-%dT%H:%M") if dt else ""


# ---------------------------------------------------------------------------
# Source 1: Open-Meteo
# ---------------------------------------------------------------------------

def fetch_open_meteo(lat, lon):
    params = {
        "latitude": lat, "longitude": lon,
        "current": "temperature_2m,weather_code,is_day,wind_speed_10m,apparent_temperature,"
                   "relative_humidity_2m,pressure_msl,wind_direction_10m,wind_gusts_10m,cloud_cover,uv_index",
        "daily": "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,"
                 "wind_speed_10m_max,precipitation_probability_max,uv_index_max",
        "hourly": "temperature_2m,weather_code,is_day,wind_speed_10m,precipitation_probability",
        "forecast_days": FORECAST_DAYS + 1,
        "forecast_hours": HOURLY_COUNT + 2,
        "temperature_unit": "fahrenheit", "wind_speed_unit": "mph", "timezone": "auto",
    }
    j = http_get_json("https://api.open-meteo.com/v1/forecast?" + urlencode(params))
    c, d, h = j["current"], j["daily"], j["hourly"]
    current = {
        "time": c["time"], "temp_f": float(c["temperature_2m"]),
        "code": int(c["weather_code"]), "is_day": c["is_day"] == 1,
        "wind_mph": float(c["wind_speed_10m"]),
        "details": {
            "feels_like_f": c.get("apparent_temperature"),
            "humidity": c.get("relative_humidity_2m"),
            "pressure_hpa": c.get("pressure_msl"),
            "wind_dir": c.get("wind_direction_10m"),
            "gust_mph": c.get("wind_gusts_10m"),
            "cloud_pct": c.get("cloud_cover"),
            "uv": c.get("uv_index"),
            "uv_max": (d.get("uv_index_max") or [None])[0],
        },
    }
    hourly = [
        {
            "time": h["time"][i], "temp_f": h["temperature_2m"][i],
            "code": h["weather_code"][i], "is_day": h["is_day"][i] == 1,
            "wind_mph": h["wind_speed_10m"][i],
            "rain_pct": _int_or(h["precipitation_probability"][i], -1),
        }
        for i in range(len(h["time"])) if h["time"][i] > c["time"]
    ][:HOURLY_COUNT]
    days = [
        {
            "date": d["time"][i], "high_f": d["temperature_2m_max"][i],
            "low_f": d["temperature_2m_min"][i], "code": d["weather_code"][i],
            "wind_mph": d["wind_speed_10m_max"][i],
            "rain_pct": _int_or(d["precipitation_probability_max"][i], -1),
            "sunrise": d["sunrise"][i], "sunset": d["sunset"][i],
        }
        for i in range(len(d["time"]))
    ]
    return {"source": SOURCE_OPEN_METEO, "tz": j.get("timezone"),
            "current": current, "hourly": hourly, "days": days}


def _int_or(value, default):
    return int(value) if value is not None else default


# ---------------------------------------------------------------------------
# Source 2: MET Norway (api.met.no) — global, free, no key. Celsius + m/s +
# its own symbol codes, so everything is converted into Open-Meteo's terms
# (°F, mph, WMO weather codes). It has no precipitation *probability*
# outside the Nordics, so rain chance is reported as -1 ("unknown").
# ---------------------------------------------------------------------------

def met_symbol_to_wmo(symbol):
    if not symbol:
        return 3
    s = symbol.split("_")[0]
    light, heavy = s.startswith("light"), s.startswith("heavy")
    if "thunder" in s:
        return 95
    if "snow" in s:
        if "showers" in s:
            return 86 if heavy else 85
        return 71 if light else 75 if heavy else 73
    if "sleet" in s:
        return 66 if light else 67
    if "rain" in s:
        if "showers" in s:
            return 80 if light else 82 if heavy else 81
        return 61 if light else 65 if heavy else 63
    return {"clearsky": 0, "fair": 1, "partlycloudy": 2, "cloudy": 3, "fog": 45}.get(s, 3)


def c_to_f(c):
    return c * 9 / 5 + 32


def ms_to_mph(ms):
    return ms * 2.236936


def fetch_met(lat, lon, tz):
    url = ("https://api.met.no/weatherapi/locationforecast/2.0/compact?"
           + urlencode({"lat": round(lat, 4), "lon": round(lon, 4)}))
    j = http_get_json(url)
    series = j["properties"]["timeseries"]
    now_utc = datetime.now(timezone.utc)

    def parse_t(s):
        return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)

    sun_cache = {}

    def is_day_at(dt_local, symbol):
        if symbol and symbol.endswith("_day"):
            return True
        if symbol and (symbol.endswith("_night") or symbol.endswith("_polartwilight")):
            return False
        day = dt_local.date()
        if day not in sun_cache:
            sun_cache[day] = sun_times(day, lat, lon, tz)
        rise, sset = sun_cache[day]
        if rise is None:
            return 4 <= dt_local.month <= 9 if lat > 0 else not (4 <= dt_local.month <= 9)
        return rise <= dt_local < sset

    entries = []
    for e in series:
        t = parse_t(e["time"]).astimezone(tz)
        inst = e["data"]["instant"]["details"]
        n1 = e["data"].get("next_1_hours")
        n6 = e["data"].get("next_6_hours")
        symbol = ((n1 or n6 or {}).get("summary") or {}).get("symbol_code")
        entries.append({
            "inst": inst,
            "t": t, "temp_f": c_to_f(inst["air_temperature"]),
            "wind_mph": ms_to_mph(inst.get("wind_speed", 0.0)),
            "symbol": symbol, "code": met_symbol_to_wmo(symbol),
            "hourly_res": n1 is not None,
            "max6_f": c_to_f(n6["details"]["air_temperature_max"]) if n6 and "air_temperature_max" in n6.get("details", {}) else None,
            "min6_f": c_to_f(n6["details"]["air_temperature_min"]) if n6 and "air_temperature_min" in n6.get("details", {}) else None,
        })

    # Current = the latest entry that has already started.
    started = [e for e in entries if e["t"] <= now_utc.astimezone(tz)]
    cur = started[-1] if started else entries[0]
    current = {
        "time": local_iso_minute(now_utc.astimezone(tz)),
        "temp_f": cur["temp_f"], "code": cur["code"],
        "is_day": is_day_at(now_utc.astimezone(tz), cur["symbol"]),
        "wind_mph": cur["wind_mph"],
        "details": {
            "feels_like_f": None,
            "humidity": cur["inst"].get("relative_humidity"),
            "pressure_hpa": cur["inst"].get("air_pressure_at_sea_level"),
            "wind_dir": cur["inst"].get("wind_from_direction"),
            "gust_mph": ms_to_mph(cur["inst"]["wind_speed_of_gust"]) if "wind_speed_of_gust" in cur["inst"] else None,
            "cloud_pct": cur["inst"].get("cloud_area_fraction"),
            "uv": cur["inst"].get("ultraviolet_index_clear_sky"),
            "uv_max": None,
        },
    }
    hourly = [
        {"time": local_iso_minute(e["t"]), "temp_f": e["temp_f"], "code": e["code"],
         "is_day": is_day_at(e["t"], e["symbol"]), "wind_mph": e["wind_mph"], "rain_pct": -1}
        for e in entries if e["t"] > now_utc.astimezone(tz) and e["hourly_res"]
    ][:HOURLY_COUNT]

    by_day = {}
    for e in entries:
        by_day.setdefault(e["t"].date(), []).append(e)
    today_local = now_utc.astimezone(tz).date()
    days = []
    for i in range(FORECAST_DAYS + 1):
        day = today_local + timedelta(days=i)
        es = by_day.get(day)
        if not es:
            continue
        temps = [e["temp_f"] for e in es]
        temps += [e["max6_f"] for e in es if e["max6_f"] is not None]
        temps += [e["min6_f"] for e in es if e["min6_f"] is not None]
        if i == 0:  # include the current reading so today's range covers "now"
            temps.append(current["temp_f"])
        rise, sset = sun_times(day, lat, lon, tz)
        days.append({
            "date": day.isoformat(), "high_f": max(temps), "low_f": min(temps),
            "code": max(e["code"] for e in es), "wind_mph": max(e["wind_mph"] for e in es),
            "rain_pct": -1, "sunrise": local_iso_minute(rise), "sunset": local_iso_minute(sset),
        })
    return {"source": SOURCE_MET, "tz": None, "current": current, "hourly": hourly, "days": days}


# ---------------------------------------------------------------------------
# Source 3: 7Timer! (7timer.info) — free, keyless, worldwide, built on NOAA's
# global GFS model. 3-hourly steps for 8 days, in Celsius, with wind and cloud
# cover as small category scales and a weather word ("lightrainday"). No rain
# probability, so rain chance is -1 ("unknown"). Hours in between the 3-hour
# steps are filled in by straight-line interpolation for the 12-hour strip.
# ---------------------------------------------------------------------------

_7TIMER_WIND_MPH = {1: 0.5, 2: 4, 3: 13, 4: 21, 5: 31, 6: 47, 7: 64, 8: 80}  # category midpoints
_7TIMER_CLOUD_PCT = {1: 3, 2: 12, 3: 25, 4: 37, 5: 50, 6: 62, 7: 75, 8: 87, 9: 97}


def seven_timer_to_wmo(word):
    w = (word or "").replace("day", "").replace("night", "")
    return {
        "clear": 0, "pcloudy": 2, "mcloudy": 3, "cloudy": 3, "humid": 3,
        "lightrain": 61, "oshower": 80, "ishower": 80, "rain": 63,
        "lightsnow": 71, "snow": 73, "rainsnow": 66, "ts": 95, "tsrain": 95,
    }.get(w, 3)


def fetch_7timer(lat, lon, tz):
    j = http_get_json("https://www.7timer.info/bin/api.pl?"
                      + urlencode({"lon": round(lon, 3), "lat": round(lat, 3),
                                   "product": "civil", "output": "json"}))
    init = datetime.strptime(j["init"], "%Y%m%d%H").replace(tzinfo=timezone.utc)
    sun_cache = {}

    def daylight(t):
        # 7Timer's day/night word covers a whole 3-hour block, so use the real sun instead.
        if t.date() not in sun_cache:
            sun_cache[t.date()] = sun_times(t.date(), lat, lon, tz)
        rise, sset = sun_cache[t.date()]
        return rise <= t < sset if rise else 4 <= t.month <= 9

    pts = []
    for e in j["dataseries"]:
        t = (init + timedelta(hours=e["timepoint"])).astimezone(tz)
        word = e.get("weather", "")
        wind = e.get("wind10m") or {}
        pts.append({
            "t": t, "temp_f": c_to_f(e["temp2m"]), "code": seven_timer_to_wmo(word),
            "is_day": daylight(t), "wind_mph": _7TIMER_WIND_MPH.get(wind.get("speed"), 0.0),
            "wind_dir": _COMPASS_DEG.get(wind.get("direction")),
            "humidity": int(str(e.get("rh2m", "")).rstrip("%")) if str(e.get("rh2m", "")).rstrip("%").lstrip("-").isdigit() else None,
            "cloud_pct": _7TIMER_CLOUD_PCT.get(e.get("cloudcover")),
        })
    if not pts:
        raise ValueError("7Timer returned no data")

    now_local = datetime.now(timezone.utc).astimezone(tz)

    def at(t):
        """Interpolated temperature/wind at time t; condition from the step in progress."""
        before = [p for p in pts if p["t"] <= t]
        after = [p for p in pts if p["t"] > t]
        a = before[-1] if before else pts[0]
        if not after or not before:
            return dict(a)
        b = after[0]
        f = (t - a["t"]) / (b["t"] - a["t"])
        out = dict(a)
        out["temp_f"] = a["temp_f"] + (b["temp_f"] - a["temp_f"]) * f
        out["wind_mph"] = a["wind_mph"] + (b["wind_mph"] - a["wind_mph"]) * f
        out["is_day"] = daylight(t)
        return out

    cur = at(now_local)
    current = {
        "time": local_iso_minute(now_local), "temp_f": cur["temp_f"], "code": cur["code"],
        "is_day": cur["is_day"], "wind_mph": cur["wind_mph"],
        "details": {"feels_like_f": None, "humidity": cur["humidity"], "pressure_hpa": None,
                    "wind_dir": cur["wind_dir"], "gust_mph": None, "cloud_pct": cur["cloud_pct"],
                    "uv": None, "uv_max": None},
    }
    first_hour = now_local.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1)
    hourly = []
    for k in range(HOURLY_COUNT):
        t = first_hour + timedelta(hours=k)
        h = at(t)
        hourly.append({"time": local_iso_minute(t), "temp_f": h["temp_f"], "code": h["code"],
                       "is_day": h["is_day"], "wind_mph": h["wind_mph"], "rain_pct": -1})

    by_day = {}
    for p in pts:
        by_day.setdefault(p["t"].date(), []).append(p)
    days = []
    for i in range(FORECAST_DAYS + 1):
        day = now_local.date() + timedelta(days=i)
        ps = by_day.get(day)
        if not ps:
            continue
        temps = [p["temp_f"] for p in ps] + ([current["temp_f"]] if i == 0 else [])
        rise, sset = sun_times(day, lat, lon, tz)
        days.append({
            "date": day.isoformat(), "high_f": max(temps), "low_f": min(temps),
            "code": max(p["code"] for p in ps), "wind_mph": max(p["wind_mph"] for p in ps),
            "rain_pct": -1, "sunrise": local_iso_minute(rise), "sunset": local_iso_minute(sset),
        })
    return {"source": SOURCE_7TIMER, "tz": None, "current": current, "hourly": hourly, "days": days}


_COMPASS_DEG = {d: i * 22.5 for i, d in enumerate(
    "N NNE NE ENE E ESE SE SSE S SSW SW WSW W WNW NW NNW".split())}


# ---------------------------------------------------------------------------
# Combining the sources
# ---------------------------------------------------------------------------

_pool = ThreadPoolExecutor(max_workers=8)


def fetch_and_record(loc_id, lat, lon, record_it=True):
    """
    Fetches every source in parallel, records whichever succeeded (unless
    record_it is False), and returns ({source: data}, statuses). The dict is
    empty only when every source failed.
    """
    loc = get_location(loc_id) if record_it else None
    if loc is None:
        near = nearest_locations(lat, lon, 300)  # just to borrow a real time zone
        tz = zone_for(near[0].get("tz") if near else None, lon)
    else:
        tz = zone_for(loc.get("tz"), lon)
    futures = (
        (SOURCE_OPEN_METEO, _pool.submit(fetch_open_meteo, lat, lon)),
        (SOURCE_MET, _pool.submit(fetch_met, lat, lon, tz)),
        (SOURCE_7TIMER, _pool.submit(fetch_7timer, lat, lon, tz)),
    )
    results, statuses = {}, {}
    for source, fut in futures:
        try:
            results[source] = fut.result()
            statuses[source] = "ok"
        except Exception as e:
            statuses[source] = "unavailable"
            log.warning("%s failed for %s: %s: %s", source, loc_id, type(e).__name__, e)

    if not record_it:
        return results, statuses
    learned_tz = results[SOURCE_OPEN_METEO]["tz"] if SOURCE_OPEN_METEO in results else None
    if learned_tz and (not loc or loc.get("tz") != learned_tz):
        set_location_tz(loc_id, learned_tz)
    for data in results.values():
        try:
            record(loc_id, lat, lon, data)
        except Exception:
            log.exception("Recording %s for %s failed", data["source"], loc_id)
    return results, statuses


def notice_for(statuses, shown):
    """e.g. "Open-Meteo unavailable — averaging MET Norway & 7Timer!". None when nothing is down."""
    down = [SOURCE_LABELS[s] for s in SOURCE_PRIORITY if statuses.get(s) == "unavailable"]
    if not down or not shown:
        return None
    if len(shown) == 1:
        return f"{' & '.join(down)} unavailable — showing {SOURCE_LABELS[shown[0]]}"
    return f"{' & '.join(down)} unavailable — averaging {' & '.join(SOURCE_LABELS[s] for s in shown)}"


# ---------------------------------------------------------------------------
# Averaging the sources
# ---------------------------------------------------------------------------

SOURCE_AVERAGE = "average"
_RAIN_CODES = {51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99}
_SNOW_CODES = {71, 73, 75, 77, 85, 86}


def _sky(code):
    """The same condition buckets the app and page draw icons for (wind aside)."""
    if code in _RAIN_CODES:
        return "rain"
    if code in _SNOW_CODES:
        return "snow"
    return {0: "sunny", 1: "sunny", 2: "partial"}.get(code, "cloudy")


def _consensus_code(codes):
    """Codes listed in source-priority order. The condition most sources agree on wins
    (a tie goes to the higher-priority source), using that source's exact code."""
    codes = [c for c in codes if c is not None]
    if not codes:
        return 3
    votes = {}
    for c in codes:
        votes[_sky(c)] = votes.get(_sky(c), 0) + 1
    best = max(votes.values())
    return next(c for c in codes if votes[_sky(c)] == best)


def _mean(values, digits=1):
    values = [v for v in values if v is not None]
    return round(sum(values) / len(values), digits) if values else None


def _mean_pct(values):
    known = [v for v in values if v is not None and v >= 0]
    return round(sum(known) / len(known)) if known else -1


def _mean_direction(values):
    values = [v for v in values if v is not None]
    if not values:
        return None
    x = sum(math.cos(math.radians(v)) for v in values)
    y = sum(math.sin(math.radians(v)) for v in values)
    return round(math.degrees(math.atan2(y, x)) % 360)


def average_sources(results):
    """One forecast made from every source that answered: temperatures, wind and
    details are averaged, rain chance is averaged over the sources that have one,
    and the condition is whatever most sources agree on."""
    srcs = [results[s] for s in SOURCE_PRIORITY if s in results]
    if not srcs:
        return None
    first = srcs[0]
    curs = [d["current"] for d in srcs]
    details = {}
    for key in ("feels_like_f", "humidity", "pressure_hpa", "gust_mph", "cloud_pct", "uv", "uv_max"):
        details[key] = _mean([(c.get("details") or {}).get(key) for c in curs])
    details["wind_dir"] = _mean_direction([(c.get("details") or {}).get("wind_dir") for c in curs])
    current = {
        "time": first["current"]["time"], "is_day": first["current"]["is_day"],
        "temp_f": _mean([c["temp_f"] for c in curs]),
        "wind_mph": _mean([c["wind_mph"] for c in curs]),
        "code": _consensus_code([c["code"] for c in curs]),
        "details": details,
    }

    base = max(srcs, key=lambda d: len(d["hourly"]))  # the source with the fullest 12 hours
    by_time = [{h["time"]: h for h in d["hourly"]} for d in srcs]
    hourly = []
    for h in base["hourly"]:
        same = [m[h["time"]] for m in by_time if h["time"] in m]
        hourly.append({
            "time": h["time"], "is_day": h["is_day"],
            "temp_f": _mean([x["temp_f"] for x in same]),
            "wind_mph": _mean([x["wind_mph"] for x in same]),
            "code": _consensus_code([x["code"] for x in same]),
            "rain_pct": _mean_pct([x["rain_pct"] for x in same]),
        })

    by_date = [{x["date"]: x for x in d["days"]} for d in srcs]
    dates = sorted({x for m in by_date for x in m})[:FORECAST_DAYS + 1]
    days = []
    for dt in dates:
        same = [m[dt] for m in by_date if dt in m]
        sun = next((x for x in same if x.get("sunrise")), same[0])
        days.append({
            "date": dt,
            "high_f": _mean([x["high_f"] for x in same]),
            "low_f": _mean([x["low_f"] for x in same]),
            "wind_mph": _mean([x["wind_mph"] for x in same]),
            "code": _consensus_code([x["code"] for x in same]),
            "rain_pct": _mean_pct([x["rain_pct"] for x in same]),
            "sunrise": sun.get("sunrise", ""), "sunset": sun.get("sunset", ""),
        })
    return {"source": SOURCE_AVERAGE, "tz": first.get("tz"),
            "current": current, "hourly": hourly, "days": days}


def weather_response(results, statuses, name):
    """The averaged snapshot, plus every source's own snapshot under "by_source"
    so the page and app can switch between them without asking again."""
    shown = [s for s in SOURCE_PRIORITY if s in results]
    out = to_app_snapshot(average_sources(results), name, notice_for(statuses, shown), statuses)
    out["source_label"] = "Average of " + ", ".join(SOURCE_LABELS[s] for s in shown)
    out["by_source"] = {}
    for s in shown:
        snap = to_app_snapshot(results[s], name, None, statuses)
        snap["source_label"] = SOURCE_LABELS[s]
        out["by_source"][s] = snap
    out["source_order"] = list(SOURCE_PRIORITY)
    out["source_labels"] = SOURCE_LABELS
    return out


def to_app_snapshot(data, name, notice, statuses):
    """Exactly the JSON shape WeatherSnapshot.toJson()/fromJson() use in the app."""
    days = data["days"]
    today = days[0] if days else None
    forecast = []
    for d in days[1:FORECAST_DAYS + 1]:
        forecast.append({
            "label": date.fromisoformat(d["date"]).strftime("%a"),
            "high_f": d["high_f"], "low_f": d["low_f"], "code": d["code"],
            "wind_mph": d["wind_mph"], "rain_pct": d["rain_pct"],
        })
    cur = data["current"]
    return {
        "city": name,
        "temp_f": cur["temp_f"], "code": cur["code"], "is_day": cur["is_day"],
        "wind_mph": cur["wind_mph"],
        "details": cur.get("details") or {},
        "local_time": cur["time"],  # "now" at that location, e.g. for the sun-position bar
        "sunrise": today["sunrise"] if today else "",
        "sunset": today["sunset"] if today else "",
        "today_high_f": today["high_f"] if today else cur["temp_f"],
        "today_low_f": today["low_f"] if today else cur["temp_f"],
        "today_rain_pct": today["rain_pct"] if today else -1,
        "hourly": data["hourly"],
        "forecast": forecast,
        "fetched_at": int(time.time() * 1000),
        "source": data["source"],
        "notice": notice,
        "sources": statuses,
    }


# ---------------------------------------------------------------------------
# History
# ---------------------------------------------------------------------------

def lookup_history(loc_id, lat, lon, day, hour):
    """
    day: date; hour: int 0-23 or None. Recordings first, archive second.
    Returns a dict, or raises ValueError for a bad request.
    """
    loc = get_location(loc_id) if loc_id else None
    nearby = [l for l in nearest_locations(lat, lon, NEARBY_KM) if l["id"] != loc_id]
    tz_source = loc or (nearby[0] if nearby else None)
    tz = zone_for(tz_source and tz_source.get("tz"), lon)
    today_there = datetime.now(tz).date()
    if day > today_there:
        raise ValueError("That date is in the future")
    if day == today_there and hour is not None and hour > datetime.now(tz).hour:
        raise ValueError("That time hasn't happened yet")

    # 1) this location's own recordings, 2) a recorded location within NEARBY_KM
    #    (e.g. the phone's Current tab while at home uses Home's recordings),
    # 3) Open-Meteo's archive.
    candidates = ([loc] if loc else []) + nearby
    for cand in candidates:
        recorded = _history_from_records(cand["id"], day, hour)
        if recorded:
            if cand["id"] != loc_id:
                recorded["source_label"] = recorded["source_label"].replace(
                    "Recorded by your server", f"Recorded by your server at {cand['name']}")
            return recorded
    return _history_from_archive(lat, lon, day, hour)


def _pick_source(rows):
    by_source = {r["source"]: r for r in rows}
    return next((by_source[s] for s in SOURCE_PRIORITY if s in by_source), None)


def _history_from_records(loc_id, day, hour):
    with _db_lock, db() as conn:
        daily_rows = [dict(r) for r in conn.execute(
            "SELECT * FROM daily WHERE location_id = ? AND date = ?", (loc_id, day.isoformat()))]
        hour_rows = []
        if hour is not None:
            hour_rows = [dict(r) for r in conn.execute(
                "SELECT * FROM observations WHERE location_id = ? AND local_time = ?",
                (loc_id, f"{day.isoformat()}T{hour:02d}:00"))]
    d = _pick_source(daily_rows)
    if not d or (hour is not None and not hour_rows):
        return None
    result = {
        "date": day.isoformat(),
        "time": f"{hour:02d}:00" if hour is not None else None,
        "source": "recorded",
        "source_label": f"Recorded by your server ({SOURCE_LABELS[d['source']]})",
        "day": {"high_f": d["high_f"], "low_f": d["low_f"], "code": d["code"],
                "wind_mph": d["wind_mph"], "rain_pct": d["rain_pct"],
                "precip_in": None, "sunrise": d["sunrise"], "sunset": d["sunset"]},
        "hour": None,
    }
    if hour is not None:
        h = _pick_source(hour_rows)
        result["hour"] = {"temp_f": h["temp_f"], "code": h["code"], "is_day": bool(h["is_day"]),
                          "wind_mph": h["wind_mph"], "rain_pct": h["rain_pct"], "precip_in": None}
    return result


def _history_from_archive(lat, lon, day, hour):
    # The archive lags ~5 days behind, so recent dates come from the forecast
    # API's own past data instead (it keeps roughly the last 3 months).
    recent = (date.today() - day).days <= 60
    base = ("https://api.open-meteo.com/v1/forecast?" if recent
            else "https://archive-api.open-meteo.com/v1/archive?")
    params = {
        "latitude": lat, "longitude": lon,
        "start_date": day.isoformat(), "end_date": day.isoformat(),
        "daily": "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,"
                 "wind_speed_10m_max,precipitation_sum",
        "hourly": "temperature_2m,weather_code,is_day,wind_speed_10m,precipitation",
        "temperature_unit": "fahrenheit", "wind_speed_unit": "mph",
        "precipitation_unit": "inch", "timezone": "auto",
    }
    j = http_get_json(base + urlencode(params))
    d, h = j["daily"], j["hourly"]
    if not d["time"] or d["temperature_2m_max"][0] is None:
        raise LookupError("No archived weather for that date")
    result = {
        "date": day.isoformat(),
        "time": f"{hour:02d}:00" if hour is not None else None,
        "source": "archive",
        "source_label": "Open-Meteo archive (before your server recorded it)",
        "day": {"high_f": d["temperature_2m_max"][0], "low_f": d["temperature_2m_min"][0],
                "code": d["weather_code"][0], "wind_mph": d["wind_speed_10m_max"][0],
                "rain_pct": -1, "precip_in": d["precipitation_sum"][0],
                "sunrise": d["sunrise"][0], "sunset": d["sunset"][0]},
        "hour": None,
    }
    if hour is not None:
        key = f"{day.isoformat()}T{hour:02d}:00"
        if key in h["time"]:
            i = h["time"].index(key)
            if h["temperature_2m"][i] is not None:
                result["hour"] = {"temp_f": h["temperature_2m"][i], "code": h["weather_code"][i],
                                  "is_day": h["is_day"][i] == 1, "wind_mph": h["wind_speed_10m"][i],
                                  "rain_pct": -1, "precip_in": h["precipitation"][i]}
    return result


# ---------------------------------------------------------------------------
# Place search (for adding cities from the web page)
# ---------------------------------------------------------------------------

def search_places(query):
    query = query.strip()
    results = []
    if re.fullmatch(r"\d{5}", query):  # US ZIP code
        try:
            j = http_get_json(f"https://api.zippopotam.us/us/{query}")
            for p in j.get("places", []):
                results.append({"name": f"{p['place name']}, {p['state abbreviation']} {query}",
                                "lat": float(p["latitude"]), "lon": float(p["longitude"])})
        except Exception as e:
            log.info("ZIP lookup for %s failed: %s", query, e)
    j = http_get_json("https://geocoding-api.open-meteo.com/v1/search?"
                      + urlencode({"name": query, "count": 8, "language": "en", "format": "json"}))
    for r in j.get("results", []) or []:
        parts = [r.get("name"), r.get("admin1"), r.get("country")]
        name = ", ".join(dict.fromkeys(p for p in parts if p))
        results.append({"name": name, "lat": float(r["latitude"]), "lon": float(r["longitude"])})
    return results[:10]


# ---------------------------------------------------------------------------
# On this day in history (Wikipedia's free, keyless feed)
# ---------------------------------------------------------------------------

# Everything is downloaded once a year into data/onthisday/ (text + small photos),
# so the page never waits on Wikipedia. A day that somehow isn't saved yet is
# fetched the first time it's asked for and then kept.
OTD_DIR = os.path.join(DATA_DIR, "onthisday")
OTD_FILE = os.path.join(OTD_DIR, "days.json")
OTD_IMG_DIR = os.path.join(OTD_DIR, "img")
OTD_THUMB_PX = 120
_otd = {"year": 0, "days": {}}
_otd_lock = threading.Lock()


def _otd_item(e, with_photo=False):
    pages = e.get("pages") or []
    page = pages[0] if pages else {}
    link = ((page.get("content_urls") or {}).get("desktop") or {}).get("page")
    thumb = (page.get("thumbnail") or {}).get("source") if with_photo else None
    return {"year": e.get("year"), "text": e.get("text", ""), "link": link,
            "thumb": _save_photo(thumb) if thumb else None, "about": page.get("description")}


def _save_photo(url):
    """Download a small copy of a Wikipedia photo; returns the local URL, or None."""
    url = url.split("?")[0]
    small = re.sub(r"/\d+px-", f"/{OTD_THUMB_PX}px-", url)
    ext = os.path.splitext(small)[1].lower()
    ext = ext if ext in (".jpg", ".jpeg", ".png", ".gif", ".webp") else ".jpg"
    name = hashlib.sha1(url.encode()).hexdigest()[:20] + ext
    path = os.path.join(OTD_IMG_DIR, name)
    if not os.path.exists(path):
        for candidate in dict.fromkeys((small, url)):
            try:
                req = Request(candidate, headers={"User-Agent": USER_AGENT})
                with urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
                    body = resp.read()
                os.makedirs(OTD_IMG_DIR, exist_ok=True)
                with open(path + ".tmp", "wb") as f:
                    f.write(body)
                os.replace(path + ".tmp", path)
                break
            except Exception:
                continue
        else:
            return None
    return "/otd-img/" + name


def _article_lengths(titles):
    """Article size in bytes for each Wikipedia title — a decent stand-in for how famous someone is."""
    sizes = {}
    for i in range(0, len(titles), 50):
        try:
            j = http_get_json("https://en.wikipedia.org/w/api.php?" + urlencode({
                "action": "query", "prop": "info", "format": "json", "formatversion": "2",
                "titles": "|".join(titles[i:i + 50])}))
        except Exception:
            log.warning("Wikipedia article sizes unavailable", exc_info=True)
            continue
        q = j.get("query") or {}
        norm = {n["to"]: n["from"] for n in q.get("normalized") or []}
        for page in q.get("pages") or []:
            t = page.get("title", "")
            sizes[norm.get(t, t)] = page.get("length", 0)
    return sizes


def _otd_people(items, count):
    """The best-known people (longest Wikipedia articles), listed oldest first."""
    rows = [(e, (e.get("pages") or [{}])[0].get("title")) for e in items if e.get("year") is not None]
    sizes = _article_lengths([t for _, t in rows if t])
    rows.sort(key=lambda r: sizes.get(r[1], 0), reverse=True)
    people = [_otd_item(e, with_photo=True) for e, _ in rows[:count]]
    people.sort(key=lambda p: p["year"])
    return people


def _download_day(month, day):
    j = http_get_json(f"https://en.wikipedia.org/api/rest_v1/feed/onthisday/all/{month:02d}/{day:02d}")
    events = [_otd_item(e) for e in (j.get("selected") or j.get("events") or [])]
    events.sort(key=lambda e: e["year"] or 0, reverse=True)
    return {
        "month": month, "day": day,
        "events": events[:12],
        "births": _otd_people(j.get("births") or [], 10),
        "deaths": _otd_people(j.get("deaths") or [], 8),
        "holidays": [h.get("text", "") for h in (j.get("holidays") or [])][:6],
        "source": "Wikipedia",
    }


def _otd_load():
    try:
        with open(OTD_FILE, encoding="utf-8") as f:
            data = json.load(f)
        with _otd_lock:
            _otd.update(year=data.get("year", 0), days=data.get("days", {}))
    except FileNotFoundError:
        pass
    except Exception:
        log.exception("Couldn't read %s", OTD_FILE)


def _otd_save(year, days):
    os.makedirs(OTD_DIR, exist_ok=True)
    with open(OTD_FILE + ".tmp", "w", encoding="utf-8") as f:
        json.dump({"year": year, "saved_at": int(time.time()), "days": days}, f)
    os.replace(OTD_FILE + ".tmp", OTD_FILE)


def on_this_day(month, day):
    key = f"{month:02d}-{day:02d}"
    with _otd_lock:
        hit = _otd["days"].get(key)
    if hit:
        return hit
    payload = _download_day(month, day)  # not saved yet — fetch once and keep it
    with _otd_lock:
        _otd["days"][key] = payload
        year, days = _otd["year"], dict(_otd["days"])
    _otd_save(year, days)
    return payload


def onthisday_loop():
    """Downloads all 366 days once per calendar year, politely and in the background."""
    _otd_load()
    while True:
        this_year = date.today().year
        with _otd_lock:
            stale = _otd["year"] != this_year
            have = set() if stale else set(_otd["days"])
            days = {} if stale else dict(_otd["days"])
        todo = [d for d in (date(2024, 1, 1) + timedelta(n) for n in range(366))
                if d.strftime("%m-%d") not in have]
        if todo:
            log.info("On This Day: downloading %d days for %d", len(todo), this_year)
            for i, d in enumerate(todo, 1):
                key = d.strftime("%m-%d")
                try:
                    days[key] = _download_day(d.month, d.day)
                except Exception as e:
                    log.warning("On This Day %s failed: %s", key, e)
                if i % 10 == 0 or i == len(todo):
                    with _otd_lock:
                        # keep serving last year's copy until this year's is complete
                        if len(days) >= 366 or not stale:
                            _otd.update(year=this_year, days=dict(days))
                    if len(days) >= 366 or not stale:
                        _otd_save(this_year, days)
                time.sleep(1)
            if len(days) >= 366:
                log.info("On This Day: %d is saved (%d days)", this_year, len(days))
                used = {p["thumb"].rsplit("/", 1)[1] for v in days.values()
                        for p in v["births"] + v["deaths"] if p.get("thumb")}
                for name in os.listdir(OTD_IMG_DIR) if os.path.isdir(OTD_IMG_DIR) else []:
                    if name not in used:
                        os.remove(os.path.join(OTD_IMG_DIR, name))  # last year's leftovers
                continue
            time.sleep(3600)  # some days failed — try those again in an hour
            continue
        time.sleep(6 * 3600)  # nothing to do until the year changes


# ---------------------------------------------------------------------------
# Hourly recorder
# ---------------------------------------------------------------------------

def recorder_loop():
    purge_old_records()
    while True:
        now = datetime.now()
        next_run = _next_recording(now)
        time.sleep((next_run - now).total_seconds())
        for loc in [l for l in active_locations() if l["id"] == HOME_ID]:
            try:
                _, statuses = fetch_and_record(loc["id"], loc["lat"], loc["lon"])
                log.info("Recorded %s (%s): %s", loc["id"], loc["name"], statuses)
            except Exception:
                log.exception("Hourly recording failed for %s", loc["id"])
        if next_run.hour == 2:
            purge_old_records()


# ---------------------------------------------------------------------------
# HTTP server
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "WeatherDonkeyHub/1.0"

    def log_message(self, fmt, *args):
        log.info("%s %s", self.address_string(), fmt % args)

    def _send(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _params(self):
        return {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}

    @staticmethod
    def _coords(p):
        try:
            lat, lon = float(p["lat"]), float(p["lon"])
        except (KeyError, ValueError):
            raise ValueError("lat and lon are required numbers")
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise ValueError("lat/lon out of range")
        return lat, lon

    def do_GET(self):
        path = urlparse(self.path).path
        p = self._params()
        try:
            if path in ("/", "/index.html"):
                with open(WEB_PAGE, "rb") as f:
                    body = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                self.wfile.write(body)
                return

            if path == "/api/health":
                return self._send(200, health_stats())

            if path == "/api/weather":
                loc_id = p.get("id")
                if not loc_id:
                    raise ValueError("id is required")
                lat, lon = self._coords(p)
                name = p.get("name") or None
                # Only the server's own location is ever recorded. Saved cities and the
                # phone's GPS position are fetched live and never stored.
                record_it = loc_id == HOME_ID
                if record_it:
                    home = get_location(HOME_ID)
                    lat, lon, name = home["lat"], home["lon"], home["name"]
                results, statuses = fetch_and_record(loc_id, lat, lon, record_it)
                if not results:
                    return self._send(503, {"error": "All weather services are unavailable",
                                            "sources": statuses})
                return self._send(200, weather_response(results, statuses, name))

            if path == "/api/history":
                lat, lon = self._coords(p)
                try:
                    day = date.fromisoformat(p.get("date", ""))
                except ValueError:
                    raise ValueError("date must be YYYY-MM-DD")
                hour = None
                if p.get("time"):
                    try:
                        hh, mm = (int(x) for x in p["time"].split(":"))
                    except ValueError:
                        raise ValueError("time must be HH:MM")
                    hour = (hh + (1 if mm >= 30 else 0)) % 24 if hh < 23 or mm < 30 else 23
                try:
                    return self._send(200, lookup_history(p.get("id"), lat, lon, day, hour))
                except LookupError as e:
                    return self._send(404, {"error": str(e)})

            if path.startswith("/otd-img/"):
                name = os.path.basename(path)
                full = os.path.join(OTD_IMG_DIR, name)
                if not re.fullmatch(r"[0-9a-f]{20}\.(jpg|jpeg|png|gif|webp)", name) or not os.path.exists(full):
                    return self._send(404, {"error": "Not found"})
                with open(full, "rb") as f:
                    body = f.read()
                ctype = {"png": "image/png", "gif": "image/gif", "webp": "image/webp"}.get(name.rsplit(".", 1)[1], "image/jpeg")
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "max-age=2592000")
                self.end_headers()
                self.wfile.write(body)
                return

            if path == "/api/onthisday":
                try:
                    month, day = int(p.get("month", "")), int(p.get("day", ""))
                    date(2024, month, day)  # leap year, so Feb 29 is allowed
                except ValueError:
                    raise ValueError("month and day are required numbers")
                return self._send(200, on_this_day(month, day))

            if path == "/api/locations":
                return self._send(200, {"locations": all_locations(include_inactive=p.get("all") == "1")})

            if path == "/api/search":
                q = (p.get("q") or "").strip()
                if len(q) < 2:
                    raise ValueError("Type at least 2 characters")
                return self._send(200, {"results": search_places(q)})

            return self._send(404, {"error": "Not found"})
        except ValueError as e:
            return self._send(400, {"error": str(e)})
        except Exception as e:
            log.exception("Request failed: %s", self.path)
            return self._send(502, {"error": f"Weather lookup failed ({type(e).__name__})"})

    def do_POST(self):
        path = urlparse(self.path).path
        p = self._params()
        try:
            lat, lon = self._coords(p)
            name = (p.get("name") or "").strip() or f"{lat:.2f}, {lon:.2f}"
            if path == "/api/locations":
                loc_id = location_id_for(lat, lon)
                upsert_location(loc_id, name, lat, lon)  # saved for tabs only — not recorded
                return self._send(200, {"location": get_location(loc_id)})
            if path == "/api/home":
                set_home(name, lat, lon)
                _pool.submit(fetch_and_record, HOME_ID, lat, lon)
                return self._send(200, {"location": get_location(HOME_ID)})
            return self._send(404, {"error": "Not found"})
        except ValueError as e:
            return self._send(400, {"error": str(e)})

    def do_DELETE(self):
        path = urlparse(self.path).path
        p = self._params()
        if path == "/api/locations" and p.get("id") == HOME_ID:
            return self._send(400, {"error": "Home can't be removed — change it instead"})
        if path == "/api/locations" and p.get("id"):
            found = deactivate_location(p["id"])
            return self._send(200 if found else 404, {"ok": found})
        return self._send(404, {"error": "Not found"})


def main():
    init_db()
    threading.Thread(target=recorder_loop, name="recorder", daemon=True).start()
    threading.Thread(target=onthisday_loop, name="onthisday", daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    log.info("Weather Donkey Hub on :%d, data in %s", PORT, DB_PATH)
    server.serve_forever()


if __name__ == "__main__":
    main()
