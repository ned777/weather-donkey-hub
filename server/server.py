#!/usr/bin/env python3
"""
Weather Donkey Hub — the home server behind the Weather Donkey app and widget.

Same philosophy as Cookbook and Vehicle Maintenance Record: one file, plain
Python stdlib, nothing to pip install.

What it does:
  * Pulls weather from TWO free, keyless, open sources — Open-Meteo
    (primary) and MET Norway (backup). If one is down, the other is used on
    its own and the response carries a short `notice` saying so; only if both
    are down does a request fail.
  * Records every location the app knows about once an hour (plus every
    time the phone refreshes), into a local SQLite database.
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
      Fetches fresh weather, records it, registers the location for hourly
      recording, and returns a snapshot in exactly the shape the app caches.
  /api/history?id=<loc id>&lat=<lat>&lon=<lon>&date=YYYY-MM-DD[&time=HH:MM]
      What the weather was on that day (and at that hour, if given).
  /api/locations                     — every location being recorded
  DELETE /api/locations?id=<loc id>  — stop recording (history is kept)
"""
import json
import logging
import math
import os
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
HTTP_TIMEOUT_S = 8
RETRY_DELAYS_S = (1.5,)    # one retry per source; both sources run in parallel
RECORD_MINUTE = 2          # hourly recording runs at HH:02

# MET Norway's terms require a User-Agent that identifies the app.
USER_AGENT = "WeatherDonkeyHub/1.0 (+https://github.com/ned777/weather-donkey-hub)"

SOURCE_OPEN_METEO = "open-meteo"
SOURCE_MET = "met-norway"
SOURCE_LABELS = {SOURCE_OPEN_METEO: "Open-Meteo", SOURCE_MET: "MET Norway"}

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
    with _db_lock, db() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM locations WHERE active = 1")]


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
        "current": "temperature_2m,weather_code,is_day,wind_speed_10m",
        "daily": "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,"
                 "wind_speed_10m_max,precipitation_probability_max",
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
# Combining the two sources
# ---------------------------------------------------------------------------

_pool = ThreadPoolExecutor(max_workers=8)


def fetch_and_record(loc_id, lat, lon):
    """
    Fetches both sources in parallel, records whichever succeeded, and returns
    (primary_data, statuses). primary_data is None only when BOTH failed.
    """
    loc = get_location(loc_id)
    tz = zone_for(loc and loc.get("tz"), lon)
    om_future = _pool.submit(fetch_open_meteo, lat, lon)
    met_future = _pool.submit(fetch_met, lat, lon, tz)
    results, statuses = {}, {}
    for source, fut in ((SOURCE_OPEN_METEO, om_future), (SOURCE_MET, met_future)):
        try:
            results[source] = fut.result()
            statuses[source] = "ok"
        except Exception as e:
            statuses[source] = "unavailable"
            log.warning("%s failed for %s: %s: %s", source, loc_id, type(e).__name__, e)

    om = results.get(SOURCE_OPEN_METEO)
    if om and om["tz"] and (not loc or loc.get("tz") != om["tz"]):
        set_location_tz(loc_id, om["tz"])
    for data in results.values():
        try:
            record(loc_id, lat, lon, data)
        except Exception:
            log.exception("Recording %s for %s failed", data["source"], loc_id)

    primary = om or results.get(SOURCE_MET)
    return primary, statuses


def notice_for(statuses):
    down = [s for s, st in statuses.items() if st != "ok"]
    up = [s for s, st in statuses.items() if st == "ok"]
    if not down or not up:
        return None
    return f"{SOURCE_LABELS[down[0]]} unavailable — showing {SOURCE_LABELS[up[0]]} only"


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
    tz = zone_for(loc and loc.get("tz"), lon)
    today_there = datetime.now(tz).date()
    if day > today_there:
        raise ValueError("That date is in the future")
    if day == today_there and hour is not None and hour > datetime.now(tz).hour:
        raise ValueError("That time hasn't happened yet")

    recorded = _history_from_records(loc_id, day, hour) if loc_id else None
    if recorded:
        return recorded
    return _history_from_archive(lat, lon, day, hour)


def _pick_source(rows):
    by_source = {r["source"]: r for r in rows}
    return by_source.get(SOURCE_OPEN_METEO) or by_source.get(SOURCE_MET)


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
# Hourly recorder
# ---------------------------------------------------------------------------

def recorder_loop():
    purge_old_records()
    while True:
        now = datetime.now()
        next_run = now.replace(minute=RECORD_MINUTE, second=0, microsecond=0)
        if next_run <= now:
            next_run += timedelta(hours=1)
        time.sleep((next_run - now).total_seconds())
        locations = active_locations()
        for loc in locations:
            try:
                _, statuses = fetch_and_record(loc["id"], loc["lat"], loc["lon"])
                log.info("Recorded %s (%s): %s", loc["id"], loc["name"], statuses)
            except Exception:
                log.exception("Hourly recording failed for %s", loc["id"])
        if next_run.hour == 3:
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
                return self._send(200, {"ok": True, "locations": len(active_locations())})

            if path == "/api/weather":
                loc_id = p.get("id")
                if not loc_id:
                    raise ValueError("id is required")
                lat, lon = self._coords(p)
                name = p.get("name") or None
                upsert_location(loc_id, name, lat, lon)
                data, statuses = fetch_and_record(loc_id, lat, lon)
                if data is None:
                    return self._send(503, {"error": "Both weather services are unavailable",
                                            "sources": statuses})
                return self._send(200, to_app_snapshot(data, name, notice_for(statuses), statuses))

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

            if path == "/api/locations":
                return self._send(200, {"locations": active_locations()})

            return self._send(404, {"error": "Not found"})
        except ValueError as e:
            return self._send(400, {"error": str(e)})
        except Exception as e:
            log.exception("Request failed: %s", self.path)
            return self._send(502, {"error": f"Weather lookup failed ({type(e).__name__})"})

    def do_DELETE(self):
        path = urlparse(self.path).path
        p = self._params()
        if path == "/api/locations" and p.get("id"):
            found = deactivate_location(p["id"])
            return self._send(200 if found else 404, {"ok": found})
        return self._send(404, {"error": "Not found"})


def main():
    init_db()
    threading.Thread(target=recorder_loop, name="recorder", daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    log.info("Weather Donkey Hub on :%d, data in %s", PORT, DB_PATH)
    server.serve_forever()


if __name__ == "__main__":
    main()
