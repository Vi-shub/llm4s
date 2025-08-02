#!/bin/bash

echo "Testing Szork Simple Server API..."
echo

# Test health endpoint
echo "1. Testing health endpoint:"
curl -s http://localhost:8080/api/health | jq .
echo

# Start a new game
echo "2. Starting a new game:"
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/game/start | jq -r .sessionId)
echo "Session ID: $SESSION_ID"
echo

# Send some commands
echo "3. Sending 'help' command:"
curl -s -X POST http://localhost:8080/api/game/command \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\": \"$SESSION_ID\", \"command\": \"help\"}" | jq .
echo

echo "4. Sending 'look' command:"
curl -s -X POST http://localhost:8080/api/game/command \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\": \"$SESSION_ID\", \"command\": \"look\"}" | jq .
echo

echo "5. Sending 'go north' command:"
curl -s -X POST http://localhost:8080/api/game/command \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\": \"$SESSION_ID\", \"command\": \"go north\"}" | jq .