# Space Mission Design Game (NASA Space Apps Challenge)

An educational game where players design a spacecraft, fly an arcade mission, play mini games, earn XP, and compete on a global leaderboard.

## Problem statement
Mission design (power, mass, budget, communications, time) is hard to learn from textbooks. Students need a hands-on way to make trade-offs, see a 3D model of what they built, and understand why a design succeeds or fails.

## Architecture
```
 Browser (HTML + CSS + JavaScript, Three.js 3D, Canvas games)
        |  REST (JSON)
        v
 Python backend: FastAPI + SQLite   -- players, scores, XP, leaderboard
        |  HTTP
        v
 Java evaluator service (no libraries) -- authoritative mission physics + anti-cheat
```
| Folder | Language | Role |
|---|---|---|
| `frontend/` | JavaScript, HTML, CSS | Game UI, 3D spacecraft builder, arcade flight, mini games |
| `backend/` | Python (FastAPI, SQLite) | REST API, database, leaderboard, serves the frontend |
| `evaluator/` | Java 17+ | Computes the maximum possible score of a design; backend rejects impossible scores |

## Run with Docker
```
docker compose up --build
```
Open http://localhost:8000. API docs: http://localhost:8000/docs

## Run without Docker
```
# terminal 1 - Java evaluator (needs JDK 17+)
java evaluator/src/MissionEvaluator.java

# terminal 2 - Python backend
cd backend
pip install -r requirements.txt
uvicorn main:app --port 8000
```
Open http://localhost:8000. Run tests: `cd backend && pytest`.

## API
| Method | Path | Description |
|---|---|---|
| GET | `/api/health` | Health check |
| GET | `/api/evaluate?code=1121-013-12&df=1&mode=0` | Evaluate a design (calls the Java service) |
| POST | `/api/scores` | Submit a score `{player, code, df, mode, fund, team, pct}`; verified, then stored |
| GET | `/api/leaderboard?limit=10` | Each player's best run |
| GET | `/api/players/{name}` | Player XP, runs, best score |

## Anti-cheat
The game sends the design code with each score. The Java service recomputes the best score that design could ever reach. If the submitted score is higher, the backend returns HTTP 422. Sandbox runs are never ranked. Player names are validated and escaped to prevent injection.

## Game features
Three missions, 3D spacecraft that builds as you choose parts, live resource dashboard, arcade flight phase, post-mission analysis, team mode (up to 4 roles), 4 mini games, XP and levels, badges, credits that buy budget, design codes.

## Limitations and future work
Simplified physics for education. Player names are not authenticated, so add accounts for real use. Next: more missions, team play over the network, mini game scores on the leaderboard.
