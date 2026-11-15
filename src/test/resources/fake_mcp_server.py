"""A minimal fake server for tests.

- test/echo:          answers with its own parameters
- test/fail:          answers with an error
- test/exit:          stops without answering, like a crash
- test/notify-first:  sends a notification, then answers like test/echo
Started with the argument "stubborn", it keeps running after its input is closed.
"""
import json
import sys
import time

for line in sys.stdin:
    if not line.strip():
        continue
    message = json.loads(line)
    if "id" not in message:  # a notification: no answer
        continue

    method = message.get("method")
    if method == "test/fail":
        reply = {"jsonrpc": "2.0", "id": message["id"],
                 "error": {"code": -32000, "message": "Failure on purpose"}}
    elif method == "test/exit":
        sys.exit(1)
    else:
        if method == "test/notify-first":
            notification = {"jsonrpc": "2.0", "method": "notifications/message",
                            "params": {"level": "info", "data": "Working on it..."}}
            print(json.dumps(notification), flush=True)
        reply = {"jsonrpc": "2.0", "id": message["id"], "result": {"echo": message.get("params", {})}}
    print(json.dumps(reply), flush=True)

if len(sys.argv) > 1 and sys.argv[1] == "stubborn":
    while True:  # ignore the closed input, and never exit by itself
        time.sleep(1)