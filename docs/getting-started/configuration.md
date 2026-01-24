---
layout: page
title: Configuration
parent: Getting Started
nav_order: 3
---

# Configuration Guide
{: .no_toc }

Configure LLM4S for multiple providers, environments, and use cases.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Configuration Hierarchy

LLM4S uses a hierarchical configuration system with the following precedence (highest to lowest):

1. **System properties** (`-Dkey=value` JVM flags)
2. **Environment variables** (`.env` file or shell exports)
3. **application.conf** (HOCON format in your project)
4. **reference.conf** (Library defaults)

---

## Environment Variables

The simplest way to configure LLM4S is through environment variables.

### Core Settings

```bash
# Required: Model selection (determines provider)
LLM_MODEL=openai/gpt-4o

# Required: API key for your chosen provider
OPENAI_API_KEY=sk-proj-...
```

### Complete Environment Setup

Create a `.env` file in your project root:

```bash
# ===================
# Model Selection
# ===================
# Format: <provider>/<model-name>
# Supported providers: openai, anthropic, azure, ollama, gemini
LLM_MODEL=openai/gpt-4o

# ===================
# OpenAI Configuration
# ===================
OPENAI_API_KEY=sk-proj-...
OPENAI_BASE_URL=https://api.openai.com/v1  # Optional: Override endpoint
OPENAI_ORGANIZATION=org-...                # Optional: Organization ID

# ===================
# Anthropic Configuration
# ===================
ANTHROPIC_API_KEY=sk-ant-...
ANTHROPIC_BASE_URL=https://api.anthropic.com  # Optional
ANTHROPIC_VERSION=2023-06-01                  # Optional: API version

# ===================
# Azure OpenAI Configuration
# ===================
AZURE_API_KEY=your-azure-key
AZURE_API_BASE=https://your-resource.openai.azure.com
AZURE_DEPLOYMENT_NAME=gpt-4o
AZURE_API_VERSION=2024-02-15-preview  # Optional

# ===================
# Ollama Configuration
# ===================
OLLAMA_BASE_URL=http://localhost:11434
# No API key needed for Ollama

# ===================
# Google Gemini Configuration
# ===================
GOOGLE_API_KEY=your-google-api-key
GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta  # Optional

# ===================
# Tracing Configuration
# ===================
TRACING_MODE=langfuse  # Options: langfuse, console, none

# Langfuse settings (if using TRACING_MODE=langfuse)
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
LANGFUSE_URL=https://cloud.langfuse.com  # or self-hosted

# ===================
# Embeddings Configuration (RAG)
# ===================
# Unified format: provider/model (recommended)
EMBEDDING_MODEL=openai/text-embedding-3-small  # Uses default base URL
# OPENAI_API_KEY is shared with LLM config above

# Or for Voyage embeddings:
# EMBEDDING_MODEL=voyage/voyage-3
# VOYAGE_API_KEY=pa-...

# Or for local embeddings with Ollama (no API key needed):
# EMBEDDING_MODEL=ollama/nomic-embed-text

# Optional: Override default base URLs
# OPENAI_EMBEDDING_BASE_URL=https://api.openai.com/v1
# VOYAGE_EMBEDDING_BASE_URL=https://api.voyageai.com/v1
# OLLAMA_EMBEDDING_BASE_URL=http://localhost:11434

# ===================
# Workspace Configuration (Optional)
# ===================
WORKSPACE_DIR=/tmp/llm4s-workspace
WORKSPACE_IMAGE=llm4s/workspace-runner:latest
WORKSPACE_PORT=8080
WORKSPACE_CONTAINER_NAME=llm4s-workspace
```

### Load Environment Variables

**Option 1: Source the file**

```bash
source .env
sbt run
```

**Option 2: Use sbt-dotenv plugin**

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("au.com.onegeek" %% "sbt-dotenv" % "2.1.233")
```

Variables automatically load when SBT starts!

**Option 3: IntelliJ IDEA**

1. Run → Edit Configurations
2. Environment variables → Add from file
3. Select your `.env` file

---

## Application Configuration (HOCON)

For more complex configurations, use `application.conf`:

### Create application.conf

Create `src/main/resources/application.conf`:

```hocon
llm {
  # Model configuration
  model = ${?LLM_MODEL}  # Fallback to env var
  model = "openai/gpt-4o"  # Default if not set

  # Generation parameters
  temperature = 0.7
  max-tokens = 2000
  top-p = 1.0

  # Provider configurations
  providers {
    openai {
      api-key = ${?OPENAI_API_KEY}
      base-url = ${?OPENAI_BASE_URL}
      base-url = "https://api.openai.com/v1"  # Default
      organization = ${?OPENAI_ORGANIZATION}
    }

    anthropic {
      api-key = ${?ANTHROPIC_API_KEY}
      base-url = ${?ANTHROPIC_BASE_URL}
      base-url = "https://api.anthropic.com"
      version = ${?ANTHROPIC_VERSION}
      version = "2023-06-01"
    }

    azure {
      api-key = ${?AZURE_API_KEY}
      api-base = ${?AZURE_API_BASE}
      deployment-name = ${?AZURE_DEPLOYMENT_NAME}
      api-version = ${?AZURE_API_VERSION}
      api-version = "2024-02-15-preview"
    }

    ollama {
      base-url = ${?OLLAMA_BASE_URL}
      base-url = "http://localhost:11434"
    }
  }
}

# Tracing configuration
tracing {
  mode = ${?TRACING_MODE}
  mode = "none"  # Default: disabled

  langfuse {
    public-key = ${?LANGFUSE_PUBLIC_KEY}
    secret-key = ${?LANGFUSE_SECRET_KEY}
    url = ${?LANGFUSE_URL}
    url = "https://cloud.langfuse.com"
  }
}

# Context window management
context {
  max-messages = 50
  preserve-system-message = true
  pruning-strategy = "oldest-first"  # oldest-first, middle-out, recent-turns
}
```

### Access Configuration in Code

```scala
import org.llm4s.config.Llm4sConfig

// Load provider-specific config
val providerConfig = Llm4sConfig.provider()

// Load tracing config
val tracingConfig = Llm4sConfig.tracing()

// Load embeddings config
val embeddingsConfig = Llm4sConfig.embeddings()
```

---

## Provider-Specific Configuration

### OpenAI

```bash
# Model selection
LLM_MODEL=openai/gpt-4o          # Latest GPT-4o
LLM_MODEL=openai/gpt-4-turbo     # GPT-4 Turbo
LLM_MODEL=openai/gpt-3.5-turbo   # GPT-3.5

# Required
OPENAI_API_KEY=sk-proj-...

# Optional
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_ORGANIZATION=org-...
```

**Available Models:**
- `gpt-4o` - Latest GPT-4 Optimized
- `gpt-4-turbo` - Fast GPT-4
- `gpt-4` - Standard GPT-4
- `gpt-3.5-turbo` - Fast and cheap

### Anthropic

```bash
# Model selection
LLM_MODEL=anthropic/claude-sonnet-4-5-latest
LLM_MODEL=anthropic/claude-opus-4-5-latest

# Required
ANTHROPIC_API_KEY=sk-ant-...

# Optional
ANTHROPIC_BASE_URL=https://api.anthropic.com
ANTHROPIC_VERSION=2023-06-01
```

**Available Models:**
- `claude-sonnet-4-5-latest` - Latest Claude 4.5 Sonnet
- `claude-opus-4-5-latest` - Most capable Claude 4.5 Opus
- `claude-3-haiku-latest` - Fastest

### Azure OpenAI

```bash
# Model selection
LLM_MODEL=azure/gpt-4o

# Required
AZURE_API_KEY=your-azure-key
AZURE_API_BASE=https://your-resource.openai.azure.com
AZURE_DEPLOYMENT_NAME=gpt-4o  # Your deployment name

# Optional
AZURE_API_VERSION=2024-02-15-preview
```

### Ollama (Local Models)

```bash
# Model selection
LLM_MODEL=ollama/llama2
LLM_MODEL=ollama/mistral
LLM_MODEL=ollama/codellama

# Required
OLLAMA_BASE_URL=http://localhost:11434

# No API key needed!
```

**Install Ollama:**

```bash
# Install
curl -fsSL https://ollama.com/install.sh | sh

# Pull a model
ollama pull llama2

# Start server
ollama serve
```

### Google Gemini

```bash
# Model selection
LLM_MODEL=gemini/gemini-2.0-flash
LLM_MODEL=gemini/gemini-1.5-pro
LLM_MODEL=gemini/gemini-1.5-flash

# Required
GOOGLE_API_KEY=your-google-api-key

# Optional
GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta
```

**Available Models:**
- `gemini-2.0-flash` - Latest Gemini 2.0 (fast, multimodal)
- `gemini-2.5-flash` - Gemini 2.5 Flash (thinking model)
- `gemini-1.5-pro` - High capability (1M token context)
- `gemini-1.5-flash` - Fast and efficient (1M token context)

**Get API Key:**
1. Go to [Google AI Studio](https://aistudio.google.com/)
2. Click "Get API Key"
3. Create or select a project
4. Copy the generated API key

---

## Embeddings Configuration

LLM4S supports multiple embedding providers for RAG (Retrieval-Augmented Generation) workflows.

### Unified Format (Recommended)

Use `EMBEDDING_MODEL` with the format `provider/model-name`, similar to `LLM_MODEL`:

```bash
# OpenAI embeddings
EMBEDDING_MODEL=openai/text-embedding-3-small
OPENAI_API_KEY=sk-...  # Same key as LLM

# Voyage AI embeddings
EMBEDDING_MODEL=voyage/voyage-3
VOYAGE_API_KEY=pa-...

# Ollama embeddings (local, no API key needed)
EMBEDDING_MODEL=ollama/nomic-embed-text
```

Default base URLs are used automatically:
- OpenAI: `https://api.openai.com/v1`
- Voyage: `https://api.voyageai.com/v1`
- Ollama: `http://localhost:11434`

Override base URLs if needed:
```bash
EMBEDDING_MODEL=openai/text-embedding-3-small
OPENAI_EMBEDDING_BASE_URL=https://custom.openai.com/v1
```

### Available Models

**OpenAI:**
- `text-embedding-3-small` - Fast, cost-effective (1536 dimensions)
- `text-embedding-3-large` - Higher quality (3072 dimensions)
- `text-embedding-ada-002` - Legacy model (1536 dimensions)

**Voyage AI:**
- `voyage-3` - General purpose
- `voyage-3-large` - Higher quality
- `voyage-code-2` - Code-optimized

**Ollama (local):**
- `nomic-embed-text` - General purpose (768 dimensions)
- `mxbai-embed-large` - Higher quality (1024 dimensions)
- `all-minilm` - Lightweight (384 dimensions)

**Install Ollama embedding model:**

```bash
ollama pull nomic-embed-text
```

### Legacy Format (Still Supported)

The legacy format using `EMBEDDING_PROVIDER` is still supported for backward compatibility:

```bash
# OpenAI
EMBEDDING_PROVIDER=openai
OPENAI_EMBEDDING_BASE_URL=https://api.openai.com/v1
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_API_KEY=sk-...

# Voyage AI
EMBEDDING_PROVIDER=voyage
VOYAGE_EMBEDDING_BASE_URL=https://api.voyageai.com/v1
VOYAGE_EMBEDDING_MODEL=voyage-3
VOYAGE_API_KEY=pa-...

# Ollama
EMBEDDING_PROVIDER=ollama
OLLAMA_EMBEDDING_BASE_URL=http://localhost:11434
OLLAMA_EMBEDDING_MODEL=nomic-embed-text
```

### Using Embeddings in Code

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.agent.memory.LLMEmbeddingService

// Create embedding client from typed config
val clientResult = for {
  (provider, cfg) <- Llm4sConfig.embeddings()
  client <- EmbeddingClient.from(provider, cfg)
} yield client

// Or use the higher-level service
val embeddingServiceResult = for {
  client <- clientResult
  model <- Llm4sConfig.textEmbeddingModel()
} yield LLMEmbeddingService(client, EmbeddingModelConfig(model.modelName, model.dimensions))
```

### System Properties (Alternative)

When running via sbt, use system properties for reliable configuration:

```bash
# Using unified format (recommended)
sbt -Dllm4s.embeddings.model=ollama/nomic-embed-text \
    "run"

# Or with legacy format
sbt -Dllm4s.embeddings.provider=ollama \
    -Dllm4s.embeddings.ollama.model=nomic-embed-text \
    "run"
```

---

## Tracing Configuration

### Console Tracing (Development)

```bash
TRACING_MODE=console
```

Output appears in stdout:

```
[TRACE] Completion request: UserMessage(What is Scala?)
[TRACE] Token usage: 150 tokens
[TRACE] Response time: 1.2s
```

### Langfuse (Production)

```bash
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
LANGFUSE_URL=https://cloud.langfuse.com
```

Get keys from [Langfuse](https://langfuse.com):

1. Sign up at langfuse.com
2. Create a project
3. Navigate to Settings → API Keys
4. Copy public and secret keys

### Disable Tracing

```bash
TRACING_MODE=none
```

---

## Multi-Provider Setup

Switch between providers without code changes:

```bash
# Development: Use Ollama (free, local)
LLM_MODEL=ollama/llama2
OLLAMA_BASE_URL=http://localhost:11434

# Staging: Use OpenAI GPT-3.5 (cheap)
LLM_MODEL=openai/gpt-3.5-turbo
OPENAI_API_KEY=sk-...

# Production: Use Claude Sonnet (best quality)
LLM_MODEL=anthropic/claude-sonnet-4-5-latest
ANTHROPIC_API_KEY=sk-ant-...
```

Your code remains the same:

```scala
val client = Llm4sConfig.provider().flatMap(LLMConnect.getClient)
```

---

## System Properties

Override any setting via JVM flags:

```bash
sbt -DLLM_MODEL=openai/gpt-4o \
    -DOPENAI_API_KEY=sk-... \
    run
```

Or in `build.sbt`:

```scala
javaOptions ++= Seq(
  s"-DLLM_MODEL=openai/gpt-4o",
  s"-DOPENAI_API_KEY=${sys.env.getOrElse("OPENAI_API_KEY", "")}"
)
```

---

## Environment-Specific Configurations

### Development

Create `.env.dev`:

```bash
LLM_MODEL=ollama/llama2
OLLAMA_BASE_URL=http://localhost:11434
TRACING_MODE=console
```

### Staging

Create `.env.staging`:

```bash
LLM_MODEL=openai/gpt-3.5-turbo
OPENAI_API_KEY=${STAGING_OPENAI_KEY}
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=${STAGING_LANGFUSE_PUBLIC}
LANGFUSE_SECRET_KEY=${STAGING_LANGFUSE_SECRET}
```

### Production

Create `.env.prod`:

```bash
LLM_MODEL=anthropic/claude-sonnet-4-5-latest
ANTHROPIC_API_KEY=${PROD_ANTHROPIC_KEY}
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=${PROD_LANGFUSE_PUBLIC}
LANGFUSE_SECRET_KEY=${PROD_LANGFUSE_SECRET}
```

Load the appropriate file:

```bash
source .env.prod
sbt run
```

---

## Configuration Best Practices

### ✅ DO

1. **Use environment variables** for secrets (API keys)
2. **Use application.conf** for non-sensitive defaults
3. **Add .env to .gitignore** to prevent committing secrets
4. **Use different configs** for dev/staging/prod
5. **Validate configuration** at startup

```scala
import org.llm4s.config.Llm4sConfig

val config = Llm4sConfig.provider()
config.fold(
  error => {
    println(s"Configuration error: $error")
    System.exit(1)
  },
  _ => println("Configuration loaded successfully")
)
```

### ❌ DON'T

1. **Don't hardcode API keys** in source code
2. **Don't commit .env files** to version control
3. **Don't use System.getenv() directly** (use Llm4sConfig)
4. **Don't mix production keys** in development
5. **Don't skip validation** - fail fast on bad config

---

## Validation Example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

object ValidateConfig extends App {
  println("Validating LLM4S configuration...")

  val validation = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
  } yield {
    println(s"✅ Model: ${providerConfig.model}")
    println(s"✅ Provider: ${providerConfig.getClass.getSimpleName}")
    println(s"✅ Client ready: ${client.getClass.getSimpleName}")
  }

  validation match {
    case Right(_) =>
      println("✅ Configuration valid!")
    case Left(error) =>
      println(s"❌ Configuration error: $error")
      System.exit(1)
  }
}
```

---

## Troubleshooting

### Problem: "API key not found"

**Check:**
1. `.env` file exists and is in project root
2. Environment variable is spelled correctly
3. `.env` file is sourced: `source .env`
4. No quotes around values in `.env` (unless needed)

### Problem: "Invalid model format"

**Fix:** Use correct format:
```bash
# ✅ Correct
LLM_MODEL=openai/gpt-4o

# ❌ Wrong
LLM_MODEL=gpt-4o
```

### Problem: "Configuration not loading"

**Debug:**
```scala
import org.llm4s.config.Llm4sConfig

val config = Llm4sConfig.provider()
println(config)
```

### Problem: "Provider mismatch"

Ensure your `LLM_MODEL` matches the API key provider:

```bash
# ✅ Correct
LLM_MODEL=openai/gpt-4o
OPENAI_API_KEY=sk-...

# ❌ Wrong
LLM_MODEL=openai/gpt-4o
ANTHROPIC_API_KEY=sk-ant-...  # Wrong provider!
```

---

## Next Steps

Configuration complete! Now you can:

1. **[Explore features →](next-steps)** - Dive into agents, tools, and more
2. **[Browse examples →](/examples/)** - See configuration in action
3. **[User guide →](/guide/basic-usage)** - Learn core concepts
4. **[Observability →](/guide/observability)** - Set up tracing

---

## Additional Resources

- **[Llm4sConfig API](/api/#explicit-configuration)** - Detailed API documentation
- **[Environment Variables Reference](/reference/env-vars)** - Complete list
- **[Production Guide](/advanced/production)** - Production best practices
- **[Discord Community](https://discord.gg/4uvTPn6qww)** - Get help with configuration

---

**Ready to build?** [Explore what's next →](next-steps)
