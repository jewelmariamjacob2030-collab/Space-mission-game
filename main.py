"""Space Mission Design Game - backend API (FastAPI + SQLite).

Scores are verified by the Java evaluator service, which knows the maximum
score any design can reach. Impossible scores are rejected (anti-cheat).
"""
import os
import sqlite3
from contextlib import contextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI, HTTPException, Query
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

HERE = Path(__file__).parent
DB_PATH = os.getenv("DB_PATH", str(HERE / "game.db"))
EVALUATOR_URL = os.getenv("EVALUATOR_URL", "http://localhost:8081")
FRONTEND = Path(os.getenv("FRONTEND_DIR", HERE.parent / "frontend"))
XP_MULT = (0.8, 1.0, 1.5)  # easy, normal, hard

app = FastAPI(title="Space Mission Design Game API", version="1.0")


@contextmanager
def db():
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    try:
        yield con
        con.commit()
    finally:
        con.close()


def init_db():
    with db() as c:
        c.executescript((HERE / "schema.sql").read_text())


init_db()


class ScoreIn(BaseModel):
    player: str = Field(pattern=r"^[A-Za-z0-9 _-]{1,20}$")
    code: str = Field(pattern=r"^\d{4}-\d{1,4}-\d{1,2}$")  # design code from the game
    df: int = Field(ge=0, le=2)
    mode: int = Field(ge=0, le=1)
    fund: int = Field(default=0, ge=0, le=500)
    team: int = Field(default=1, ge=1, le=4)
    pct: int = Field(ge=0, le=100)


def evaluate_design(code: str, df: int, mode: int, fund: int) -> dict:
    """Ask the Java evaluator service for the authoritative result of a design."""
    try:
        r = httpx.get(f"{EVALUATOR_URL}/evaluate", timeout=5,
                      params={"code": code, "df": df, "mode": mode, "fund": fund})
    except httpx.HTTPError:
        raise HTTPException(503, "Evaluator service unavailable")
    if r.status_code == 400:
        raise HTTPException(422, r.json().get("error", "Invalid design"))
    r.raise_for_status()
    return r.json()


@app.get("/api/health")
def health():
    return {"status": "ok"}


@app.get("/api/evaluate")
def evaluate(code: str = Query(pattern=r"^\d{4}-\d{1,4}-\d{1,2}$"),
             df: int = 1, mode: int = 0, fund: int = 0):
    return evaluate_design(code, df, mode, fund)


@app.post("/api/scores", status_code=201)
def submit_score(s: ScoreIn):
    if s.mode == 1:
        raise HTTPException(422, "Sandbox runs are not ranked")
    ev = evaluate_design(s.code, s.df, s.mode, s.fund)
    if s.pct > ev["maxPct"]:
        raise HTTPException(422, f"Score {s.pct}% is impossible for this design (max {ev['maxPct']}%)")
    xp = round((15 + s.pct * 0.6) * XP_MULT[s.df])
    with db() as c:
        c.execute("INSERT OR IGNORE INTO players(name) VALUES (?)", (s.player,))
        pid = c.execute("SELECT id FROM players WHERE name=?", (s.player,)).fetchone()["id"]
        c.execute("INSERT INTO scores(player_id,scenario,code,difficulty,team_size,pct) VALUES (?,?,?,?,?,?)",
                  (pid, ev["scenario"], s.code, s.df, s.team, s.pct))
        c.execute("UPDATE players SET xp=xp+? WHERE id=?", (xp, pid))
        better = c.execute("SELECT COUNT(*) n FROM (SELECT MAX(pct) m FROM scores GROUP BY player_id) WHERE m > ?",
                           (s.pct,)).fetchone()["n"]
        total = c.execute("SELECT xp FROM players WHERE id=?", (pid,)).fetchone()["xp"]
    return {"player": s.player, "scenario": ev["scenario"], "pct": s.pct,
            "xp_gained": xp, "xp_total": total, "rank": better + 1}


@app.get("/api/leaderboard")
def leaderboard(limit: int = Query(10, ge=1, le=50)):
    """Each player's best run, highest first."""
    with db() as c:
        rows = c.execute(
            """SELECT p.name, s.scenario, s.pct, s.code FROM scores s
               JOIN players p ON p.id = s.player_id
               WHERE s.id = (SELECT id FROM scores s2 WHERE s2.player_id = s.player_id
                             ORDER BY pct DESC, id ASC LIMIT 1)
               ORDER BY s.pct DESC, s.id ASC LIMIT ?""", (limit,)).fetchall()
    return [dict(r) for r in rows]


@app.get("/api/players/{name}")
def player(name: str):
    with db() as c:
        p = c.execute("SELECT id,name,xp FROM players WHERE name=?", (name,)).fetchone()
        if not p:
            raise HTTPException(404, "Player not found")
        st = c.execute("SELECT COUNT(*) runs, MAX(pct) best FROM scores WHERE player_id=?", (p["id"],)).fetchone()
    return {"name": p["name"], "xp": p["xp"], "runs": st["runs"], "best": st["best"]}


if FRONTEND.exists():  # serve the game itself from the same origin
    app.mount("/", StaticFiles(directory=FRONTEND, html=True), name="frontend")
