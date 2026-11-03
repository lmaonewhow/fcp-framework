"""Trusted sample JSON-RPC worker; launched and reclaimed by the Java example."""
import json
import os
import sys

for line in sys.stdin:
    request = json.loads(line)
    if request.get("method") != "sum":
        response = {"jsonrpc": "2.0", "id": request.get("id"), "error": {"code": -32601, "message": "Unknown method"}}
    else:
        values = request.get("params", {}).get("values", [])
        if not isinstance(values, list) or len(values) > 1000 or any(type(v) is not int for v in values):
            response = {"jsonrpc": "2.0", "id": request.get("id"), "error": {"code": -32602, "message": "Integer array required"}}
        else:
            response = {"jsonrpc": "2.0", "id": request.get("id"), "result": {"sum": sum(values), "pid": os.getpid()}}
    sys.stdout.write(json.dumps(response) + "\n")
    sys.stdout.flush()
