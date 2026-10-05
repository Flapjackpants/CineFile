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


def _cfg(**kw):
    kw.setdefault("visual_review", True)
    kw.setdefault("deepseek_key", "k")
    return AiConfig(**kw)


def _capture(monkeypatch, reply):
    seen = {}

    def fake(messages, *, est_in, est_out, cfg, usage):
        seen.update(messages=messages, est_in=est_in, est_out=est_out)
        return reply

    monkeypatch.setattr(ai, "_deepseek_chat", fake)
    return seen


PREVIEWS = [[b"a", b"b", b"c"], [b"d", b"e", b"f"]]


def test_visual_review_request_shape(monkeypatch):
    seen = _capture(monkeypatch, '{"scores":[]}')
    ai.review_clips_visual(PREVIEWS, project="p", cfg=_cfg(), usage=AiUsage())
    system, user = seen["messages"]
    assert isinstance(system["content"], str)
    parts = user["content"]
    assert sum(p["type"] == "text" for p in parts) == 1 + 2
    imgs = [p for p in parts if p["type"] == "image_url"]
    assert len(imgs) == 6
    assert all(p["image_url"]["url"].startswith("data:image/png;base64,") for p in imgs)
    assert all(p["image_url"]["detail"] == "low" for p in imgs)
    assert seen["est_in"] == 600 + 1024 * 6


def test_visual_review_parses_and_clamps(monkeypatch):
    _capture(monkeypatch, '{"scores":[{"clip":0,"score":9},{"clip":1,"score":42}]}')
    assert ai.review_clips_visual(PREVIEWS, project="", cfg=_cfg(), usage=AiUsage()) == [9.0, 10.0]


def test_visual_review_missing_defaults(monkeypatch):
    _capture(monkeypatch, '{"scores":[{"clip":1,"score":7}]}')
    assert ai.review_clips_visual(PREVIEWS, project="", cfg=_cfg(), usage=AiUsage()) == [5.0, 7.0]


def test_visual_review_gated(monkeypatch):
    def fail(*a, **k):
        raise AssertionError("should not call")

    monkeypatch.setattr(ai, "_deepseek_chat", fail)
    for cfg in (_cfg(offline=True), _cfg(visual_review=False), _cfg(deepseek_key=None)):
        assert ai.review_clips_visual(PREVIEWS, project="", cfg=cfg, usage=AiUsage()) is None
    assert ai.review_clips_visual([], project="", cfg=_cfg(), usage=AiUsage()) is None


def test_visual_review_budget(monkeypatch):
    calls = []

    def post(*a, **k):
        calls.append(1)
        raise AssertionError("no POST expected")

    monkeypatch.setattr(ai.requests, "post", post)
    out = ai.review_clips_visual(PREVIEWS, project="", cfg=_cfg(max_ai_usd=0), usage=AiUsage())
    assert out is None and not calls


def test_plan_clips_uses_helper(monkeypatch):
    _capture(monkeypatch, '{"indices":[1,0,99]}')
    out = ai.plan_clips_deepseek(
        [(0, 100, 1.0), (200, 300, 2.0)],
        project="", target_clips=2, clip_seconds=5, cfg=AiConfig(deepseek_key="k"), usage=AiUsage(),
    )
    assert out == [1, 0]
