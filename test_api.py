import os, tempfile
os.environ["DB_PATH"] = os.path.join(tempfile.mkdtemp(), "test.db")
from fastapi.testclient import TestClient
import main

main.evaluate_design = lambda code, df, mode, fund: {
    "scenario": "Mars Climate Orbiter", "maxPct": 80, "valid": True}
client = TestClient(main.app)
GOOD = {"player": "Ada", "code": "1121-013-12", "df": 1, "mode": 0, "pct": 70}


def test_submit_and_leaderboard():
    r = client.post("/api/scores", json=GOOD)
    assert r.status_code == 201 and r.json()["rank"] == 1
    assert client.get("/api/leaderboard").json()[0]["name"] == "Ada"
    assert client.get("/api/players/Ada").json()["runs"] == 1


def test_rejects_impossible_score():
    r = client.post("/api/scores", json={**GOOD, "pct": 95})
    assert r.status_code == 422


def test_rejects_sandbox_and_bad_input():
    assert client.post("/api/scores", json={**GOOD, "mode": 1}).status_code == 422
    assert client.post("/api/scores", json={**GOOD, "player": "<script>"}).status_code == 422
