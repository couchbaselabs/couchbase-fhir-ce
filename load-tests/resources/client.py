from __future__ import annotations

import json
import logging
from typing import Any, Dict, Optional


class FHIRClient:
    def __init__(self, http_client, base_url: str = "/", headers: Optional[Dict[str, str]] = None) -> None:
        # http_client is Locust's client: self.client
        self.http = http_client
        self.base_url = base_url.rstrip("/") or ""
        self.enable_log = True

        self._logger = logging.getLogger("fhir")
        # Use exactly the headers provided by caller (e.g., from Locust file), or none
        self._headers = headers or None

    def _url(self, path: str) -> str:
        if not path.startswith("/"):
            path = "/" + path
        return f"{self.base_url}{path}"

    # Basic FHIR REST helpers (headers fully controlled by caller)
    def get(self, path: str, params: Optional[Dict[str, Any]] = None, **_: Any) -> Any:
        self._log_request("GET", path, params=params)
        # Ignore custom per-call names to keep aggregation stable
        canonical = self._canonical_name("GET", path)
        with self.http.get(self._url(path), params=params or {}, headers=self._headers, name=canonical, catch_response=True) as response:
            self._inspect_response(response, method="GET", path=path, request_params=params)
            return response

    def post(self, path: str, json: Dict[str, Any], **_: Any) -> Any:
        self._log_request("POST", path, body=json)
        canonical = self._canonical_name("POST", path, body=json)
        with self.http.post(self._url(path), json=json, headers=self._headers, name=canonical, catch_response=True) as response:
            self._inspect_response(response, method="POST", path=path, request_body=json)
            return response

    def put(self, path: str, json: Dict[str, Any], **_: Any) -> Any:
        self._log_request("PUT", path, body=json)
        canonical = self._canonical_name("PUT", path, body=json)
        with self.http.put(self._url(path), json=json, headers=self._headers, name=canonical, catch_response=True) as response:
            self._inspect_response(response, method="PUT", path=path, request_body=json)
            return response

    def patch(self, path: str, json: Dict[str, Any], **_: Any) -> Any:
        self._log_request("PATCH", path, body=json)
        canonical = self._canonical_name("PATCH", path, body=json)
        with self.http.patch(self._url(path), json=json, headers=self._headers, name=canonical, catch_response=True) as response:
            self._inspect_response(response, method="PATCH", path=path, request_body=json)
            return response

    # No env-based header merging here; caller controls headers entirely

    def _safe_dump(self, obj: Any, max_len: int = 1000) -> str:
        try:
            text = json.dumps(obj)
        except Exception:
            return "<unserializable>"
        if len(text) > max_len:
            return text[:max_len] + "...<truncated>"
        return text

    def _log_request(self, method: str, path: str, *, params: Optional[Dict[str, Any]] = None, body: Optional[Dict[str, Any]] = None) -> None:
        if not self.enable_log:
            return
        params_text = self._safe_dump(params) if params is not None else "{}"
        body_text = self._safe_dump(body) if body is not None else "{}"
        if params is not None and body is None:
            self._logger.info("[REQ] %s %s params=%s", method, path, params_text)
        elif body is not None and params is None:
            self._logger.info("[REQ] %s %s body=%s", method, path, body_text)
        else:
            self._logger.info("[REQ] %s %s params=%s body=%s", method, path, params_text, body_text)

    def _log_response(self, path: str, payload: Dict[str, Any]) -> None:
        if not self.enable_log:
            return
        self._logger.info("[RESP] %s json=%s", path, self._safe_dump(payload))

    def _log_error(self, method: str, path: str, status_code: int, detail: str, *, request_body: Optional[Dict[str, Any]] = None, request_params: Optional[Dict[str, Any]] = None) -> None:
        if not self.enable_log:
            return
        extras: list[str] = []
        if request_params is not None:
            extras.append(f"params={self._safe_dump(request_params)}")
        if request_body is not None:
            extras.append(f"body={self._safe_dump(request_body)}")
        extra_text = (" " + " ".join(extras)) if extras else ""
        self._logger.error("[ERROR] %s %s status=%s detail=%s%s", method, path, status_code, detail, extra_text)

    def _inspect_response(self, response, *, method: str, path: str, request_body: Optional[Dict[str, Any]] = None, request_params: Optional[Dict[str, Any]] = None) -> None:
        if response.ok:
            # With catch_response=True, we must explicitly mark success
            response.success()
            return

        status = getattr(response, "status_code", "?")
        detail = self._extract_error_detail(response)
        self._log_error(method, path, status, detail, request_body=request_body, request_params=request_params)
        params_text = f" params={self._safe_dump(request_params)}" if request_params is not None else ""
        body_text = f" body={self._safe_dump(request_body)}" if request_body is not None else ""
        response.failure(f"{method} {path} -> {status}: {detail}{params_text}{body_text}")

    def _extract_error_detail(self, response) -> str:
        try:
            data = response.json()
        except Exception:
            return self._truncate_text(getattr(response, "text", ""))

        if isinstance(data, dict):
            if data.get("resourceType") == "OperationOutcome":
                return self._format_operation_outcome(data)
            return self._safe_dump(data)
        return str(data)

    def _format_operation_outcome(self, data: Dict[str, Any]) -> str:
        issues = data.get("issue") or []
        messages: list[str] = []
        for issue in issues:
            parts = [
                issue.get("code"),
                (issue.get("details") or {}).get("text"),
                issue.get("diagnostics"),
            ]
            compact = [p for p in parts if p]
            if compact:
                messages.append(" | ".join(compact))
        return "; ".join(messages) or self._safe_dump(data)

    def _truncate_text(self, text: str, max_len: int = 1000) -> str:
        if not text:
            return ""
        return text[:max_len] + ("...<truncated>" if len(text) > max_len else "")

    def _canonical_name(self, method: str, path: str, *, body: Optional[Dict[str, Any]] = None) -> str:
        normalized = (path or "").lstrip("/")
        if not normalized:
            return self._name_for_root(body)
        if normalized == "metadata":
            return "capability statement"
        parts = normalized.split("/")
        first = parts[0]
        if first.startswith("$"):
            return f"system operation {first}"
        resource = first.lower()
        if len(parts) == 1:
            return self._name_for_type(method, resource)
        if parts[1].startswith("$"):
            return f"{resource} operation {parts[1]}"
        if len(parts) == 2:
            return self._name_for_instance(method, resource)
        return self._name_for_instance_subpath(method, resource, parts[2])

    def _name_for_root(self, body: Optional[Dict[str, Any]]) -> str:
        if isinstance(body, dict) and (body or {}).get("resourceType") == "Bundle":
            btype = (body or {}).get("type")
            if btype in ("batch", "transaction"):
                return btype
        return "root"

    def _name_for_type(self, method: str, resource: str) -> str:
        if method == "GET":
            return f"{resource} search"
        if method == "POST":
            return f"{resource} create"
        return f"{resource} {method.lower()}"

    def _name_for_instance(self, method: str, resource: str) -> str:
        if method == "GET":
            return f"{resource} by id"
        if method == "PUT":
            return f"{resource} update"
        if method == "PATCH":
            return f"{resource} patch"
        if method == "DELETE":
            return f"{resource} delete"
        return f"{resource} {method.lower()} by id"

    def _name_for_instance_subpath(self, method: str, resource: str, subpath: str) -> str:
        if subpath.startswith("$"):
            return f"{resource} instance operation {subpath}"
        if subpath.startswith("_history"):
            return f"{resource} history"
        return f"{resource} {method.lower()}"