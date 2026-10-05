from cinefile import ai
from cinefile.ai import AiConfig, AiUsage, score_candidates_laya


class FakeAgent:
    def __init__(self, scores):
        self.scores = scores
        self.texts = []

    def predict(self, text, questions):
        assert questions["clip"]["type"] == "score"
        self.texts.append(text)
        return {"answers": {"clip": {"score": self.scores[len(self.texts) - 1]}}}


def test_laya_rescores_and_sorts(monkeypatch):
    agent = FakeAgent([0.0, 3.0])
    monkeypatch.setattr(ai, "_load_laya", lambda model_id: agent)
    usage = AiUsage()
    out = score_candidates_laya(
        [(0, 100, 1.0), (200, 300, 0.5)], project="castle", cfg=AiConfig(), usage=usage
    )
    assert out == [(200, 300, 0.5 * 0.3 + 9.0), (0, 100, 0.3)]
    assert usage.laya_calls == 2
    assert usage.estimate_usd() == 0
    assert "castle" in agent.texts[0]


def test_laya_unavailable_keeps_heuristic(monkeypatch):
    monkeypatch.setattr(ai, "_load_laya", lambda model_id: None)
    cands = [(0, 100, 1.0), (200, 300, 2.0)]
    usage = AiUsage()
    assert score_candidates_laya(cands, project="", cfg=AiConfig(), usage=usage) == cands
    assert usage.laya_calls == 0


def test_laya_skipped_when_offline(monkeypatch):
    def fail(model_id):
        raise AssertionError("should not load model offline")

    monkeypatch.setattr(ai, "_load_laya", fail)
    cands = [(0, 100, 1.0)]
    assert score_candidates_laya(cands, project="", cfg=AiConfig(offline=True), usage=AiUsage()) == cands


def test_laya_predict_error_falls_back_per_window(monkeypatch):
    class Flaky:
        def predict(self, text, questions):
            raise RuntimeError("boom")

    monkeypatch.setattr(ai, "_load_laya", lambda model_id: Flaky())
    out = score_candidates_laya([(0, 100, 1.0)], project="", cfg=AiConfig(), usage=AiUsage())
    assert out == [(0, 100, 1.0)]


def test_laya_appends_notes(monkeypatch):
    agent = FakeAgent([1.0, 1.0])
    monkeypatch.setattr(ai, "_load_laya", lambda model_id: agent)
    score_candidates_laya(
        [(0, 100, 1.0), (200, 300, 0.5)],
        project="",
        cfg=AiConfig(),
        usage=AiUsage(),
        notes=["note A", "note B"],
    )
    assert agent.texts[0].endswith(" note A")
    assert agent.texts[1].endswith(" note B")


def test_laya_notes_length_mismatch():
    import pytest

    with pytest.raises(ValueError, match="notes must match candidates"):
        score_candidates_laya(
            [(0, 100, 1.0)],
            project="",
            cfg=AiConfig(offline=True),
            usage=AiUsage(),
            notes=[],
        )
