#!/usr/bin/env python3
"""Python repair workflow. Pure rule execution; the RuoYi container owns identity/SQL/audit."""
import json
import sys
import math

MAX_FRAME = 1_048_576

class BusinessRule(ValueError):
    pass

def require(condition: bool, message: str) -> None:
    if not condition:
        raise BusinessRule(message)

def text(data: dict, key: str, limit: int) -> str:
    value = data.get(key)
    require(isinstance(value, str) and 0 < len(value.strip()) <= limit, f"{key} is required and bounded")
    return value.strip()

def plan(p: dict) -> dict:
    require(isinstance(p, dict) and set(p) == {"action", "actor", "record", "data"}, "Invalid rule request")
    action, actor, data = p["action"], p["actor"], p["data"]
    require(isinstance(actor, str) and bool(actor), "Authenticated actor required")
    require(isinstance(data, dict) and isinstance(p["record"], dict), "Invalid data")
    body = dict(p["record"])
    if action == "create":
        require(set(data) == {"title", "description", "severity"}, "Expected title, description and severity only")
        require(data["severity"] in ("normal", "urgent"), "Unknown severity")
        body = dict(title=text(data, "title", 120), description=text(data, "description", 2000),
                    severity=data["severity"], owner=actor, status="open", assignee="", resolution="")
    elif action == "assign":
        require(set(data) == {"assignee"}, "Expected assignee only")
        require(body.get("status") == "open", "Only open repairs can be assigned")
        body.update(assignee=text(data, "assignee", 100), status="assigned")
    elif action == "resolve":
        require(set(data) == {"resolution"}, "Expected resolution only")
        require(body.get("status") == "assigned", "Repair must be assigned")
        require(body.get("assignee") == actor, "Only the assigned technician may resolve")
        body.update(resolution=text(data, "resolution", 2000), status="resolved")
    elif action == "close":
        require(not data, "No extra closing fields")
        require(body.get("owner") == actor, "Only the requester can confirm completion")
        require(body.get("status") == "resolved", "Repair must be resolved before closing")
        body["status"] = "closed"
    else:
        raise BusinessRule("Unsupported repair action")
    return {"body": body, "engine": "python"}

def no_duplicates(pairs):
    d = {}
    for k, v in pairs:
        if k in d:
            raise ValueError("Duplicate JSON key")
        d[k] = v
    return d

def validate_wire(value, depth=0) -> None:
    if depth > 32:
        raise ValueError("JSON depth limit")
    if isinstance(value, str):
        if any(0xD800 <= ord(c) <= 0xDFFF for c in value):
            raise ValueError("Unpaired Unicode surrogate")
    elif isinstance(value, dict):
        for k, v in value.items():
            validate_wire(k, depth + 1)
            validate_wire(v, depth + 1)
    elif isinstance(value, list):
        for item in value:
            validate_wire(item, depth + 1)
    elif isinstance(value, int) and not isinstance(value, bool):
        if abs(value) > 9007199254740991:
            raise ValueError("Number outside portable range")
    elif isinstance(value, float):
        if not math.isfinite(value) or abs(value) > 9007199254740991:
            raise ValueError("Number outside portable range")

def run() -> None:
    while True:
        raw = sys.stdin.buffer.readline(MAX_FRAME + 1)
        if not raw:
            return
        if len(raw) > MAX_FRAME or not raw.endswith(b"\n"):
            print("Invalid or oversized frame", file=sys.stderr)
            return
        request_id = None
        try:
            msg = json.loads(raw.decode("utf-8"), object_pairs_hook=no_duplicates,
                             parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Invalid number")))
            validate_wire(msg)
            require(set(msg) == {"jsonrpc", "id", "method", "params"}, "Invalid envelope")
            request_id = msg["id"]
            require(msg["jsonrpc"] == "2.0" and isinstance(request_id, str), "Invalid RPC version/id")
            require(msg["method"] == "repair.plan", "Unknown method")
            response = {"jsonrpc": "2.0", "id": request_id, "result": plan(msg["params"])}
        except (BusinessRule, ValueError, TypeError, KeyError) as error:
            response = {"jsonrpc": "2.0", "id": request_id,
                        "error": {"code": -32020, "message": str(error), "data": {"code": "BUSINESS_RULE"}}}
        sys.stdout.write(json.dumps(response, ensure_ascii=True, allow_nan=False, separators=(",", ":")) + "\n")
        sys.stdout.flush()

if __name__ == "__main__":
    run()
