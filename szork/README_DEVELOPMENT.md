# Szork Development

## Important: Scala Version

Szork is configured to use **Scala 2.13 only** to avoid cross-version conflicts with the main llm4s project.

## Building and Running

```bash
# Compile
sbt "szork/compile"

# Run the simple server (no LLM required)
sbt "szork/runMain org.llm4s.szork.SzorkSimpleServer"

# Run the full server (requires LLM configuration)
sbt "szork/runMain org.llm4s.szork.SzorkServer"
```

## IntelliJ IDEA Setup

The szork module should import correctly in IntelliJ. If you encounter any issues:

1. File → Invalidate Caches and Restart
2. After restart, let IntelliJ re-index the project
3. If needed, refresh the sbt project (View → Tool Windows → sbt → Reload)

## Frontend Development

The frontend is in `szork/frontend`. To develop:

1. Terminal 1: Run the backend server (see above)
2. Terminal 2: 
   ```bash
   cd szork/frontend
   npm install
   npm run dev
   ```

The frontend dev server runs on http://localhost:3090 and proxies API calls to the backend on port 8090.

## Architecture

- `SzorkServer.scala` - Full game server with LLM integration
- `SzorkSimpleServer.scala` - Simple test server without LLM
- `frontend/` - Vue.js/Vuetify frontend application

## Dependencies

Szork depends on the main llm4s project and adds:
- Cask web framework for HTTP server
- All llm4s agent and LLM functionality