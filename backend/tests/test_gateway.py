import base64
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from backend.app import Configuration, MAX_BODY, create_app


AUTH = {"Authorization": "Bearer test-service-token"}


def completed(payload):
    return httpx.Response(200, json={"status": "completed", "output": [
        {"type": "message", "content": [{"type": "output_text", "text": json.dumps(payload)}]}
    ]})


@pytest.fixture
def service():
    calls = []

    def provider(request):
        calls.append(request)
        path = request.url.path
        if path.endswith("audio/transcriptions"):
            if b"diarized_json" in request.content:
                return httpx.Response(200, json={"text": "Hello", "segments": [{"speaker": "A", "start": 0, "end": 1.2, "text": "Hello"}]})
            return httpx.Response(200, json={"text": "Բարև"})
        if path.endswith("audio/speech"):
            return httpx.Response(200, content=b"ID3test", headers={"Content-Type": "audio/mpeg"})
        body = json.loads(request.content)
        properties = body["text"]["format"]["schema"]["properties"]
        assert body["store"] is False
        if "translated" in properties:
            return completed({"translated": "Բարև", "source": "en"})
        if "summary" in properties:
            return completed({"summary": "Summary", "actions": ["Send notes"]})
        return completed({"text": "Привет"})

    config = Configuration(api_key="test-provider-key", access_token="test-service-token", requests_per_minute=100)
    with TestClient(create_app(config, httpx.MockTransport(provider))) as client:
        yield client, calls


def test_missing_keys_fail_closed_without_provider_call():
    def forbidden(request):
        pytest.fail("Unconfigured server contacted the provider")

    with TestClient(create_app(Configuration(), httpx.MockTransport(forbidden))) as client:
        assert client.get("/health").status_code == 200
        response = client.post("/v1/translate", headers=AUTH, json={"text": "Hello", "target": "hy"})
        assert response.status_code == 503
        assert response.json() == {"error": "not_configured"}


def test_authorization_required_and_never_exposes_provider_key(service):
    client, calls = service
    assert client.get("/v1/status").status_code == 401
    assert calls == []
    response = client.get("/v1/status", headers=AUTH)
    assert response.json()["version"] == 1
    assert "test-provider-key" not in response.text
    assert response.headers["cache-control"] == "no-store"


def test_translation_uses_schema_and_preserves_language_contract(service):
    client, calls = service
    response = client.post("/v1/translate", headers=AUTH, json={"text": "Hello", "target": "hy"})
    assert response.status_code == 200
    assert response.json() == {"translated": "Բարև", "source": "en", "target": "hy"}
    assert len(calls) == 1
    assert calls[0].headers["authorization"] == "Bearer test-provider-key"


def test_validation_errors_do_not_echo_private_text_or_unknown_fields(service):
    client, calls = service
    private = "private-sample-" * 500
    response = client.post("/v1/translate", headers=AUTH, json={"text": private, "target": "hy"})
    assert response.status_code == 422
    assert private not in response.text
    assert calls == []
    assert client.post("/v1/translate", headers=AUTH, json={"text": "Hello", "target": "fr"}).status_code == 422


def test_audio_image_and_speech_contracts(service):
    client, calls = service
    encoded = base64.b64encode(b"sample").decode()
    response = client.post("/v1/transcribe", headers=AUTH, json={"audio": encoded, "mediaType": "audio/mp4", "language": "hy"})
    assert response.json() == {"text": "Բարև"}
    assert b"name=\"language\"" in calls[-1].content
    assert client.post("/v1/ocr", headers=AUTH, json={"image": encoded}).json() == {"text": "Привет"}
    response = client.post("/v1/speech", headers=AUTH, json={"text": "Բարև", "language": "hy", "speed": 0.75})
    assert response.content == b"ID3test"
    assert response.headers["content-type"] == "audio/mpeg"


def test_meeting_speaker_timestamps_and_summary_contract(service):
    client, calls = service
    response = client.post("/v1/meetings", headers=AUTH, json={"audio": base64.b64encode(b"audio").decode()})
    assert response.status_code == 200
    assert response.json()["segments"] == [{"text": "Hello", "speaker": "A", "start": 0.0, "end": 1.2}]
    summary = client.post("/v1/summary", headers=AUTH, json={"text": "Meeting notes", "target": "ru"})
    assert summary.json() == {"summary": "Summary", "actions": ["Send notes"]}


def test_invalid_media_is_rejected_before_provider_call(service):
    client, calls = service
    response = client.post("/v1/transcribe", headers=AUTH, json={"audio": "not base64"})
    assert response.status_code == 422
    assert calls == []


def test_provider_failures_are_sanitized():
    def provider(request):
        return httpx.Response(403, json={"error": {"message": "private provider diagnostics"}})

    with TestClient(create_app(Configuration("test-key", "test-service-token"), httpx.MockTransport(provider))) as client:
        response = client.post("/v1/translate", headers=AUTH, json={"text": "Hello", "target": "hy"})
        assert response.status_code == 502
        assert response.json() == {"error": "provider_error"}
        assert "private" not in response.text


def test_rate_limit_blocks_provider_calls():
    def provider(request):
        pytest.fail("Request should be limited")

    config = Configuration("test-key", "test-service-token", requests_per_minute=1)
    with TestClient(create_app(config, httpx.MockTransport(provider))) as client:
        assert client.get("/v1/status", headers=AUTH).status_code == 200
        assert client.post("/v1/translate", headers=AUTH, json={"text": "Hi", "target": "hy"}).status_code == 429


def test_body_limit_rejects_chunked_oversized_payload(service):
    client, calls = service
    response = client.post("/v1/translate", headers=AUTH, content=iter([b"x" * (MAX_BODY // 2), b"x" * (MAX_BODY // 2 + 1)]))
    assert response.status_code == 413
    assert calls == []


def test_configuration_repr_does_not_include_secrets():
    value = repr(Configuration("private-provider-secret", "private-gateway-secret"))
    assert "private-provider-secret" not in value
    assert "private-gateway-secret" not in value


def test_source_mismatch_requires_language_selection(service):
    client, calls = service
    response = client.post("/v1/translate", headers=AUTH, json={"text": "Hello", "source": "ru", "target": "hy"})
    assert response.status_code == 422
    assert response.json() == {"error": "language_undetected"}
