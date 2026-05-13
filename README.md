# Hermes Agent Java

Self-improving AI Agent Framework in Java, inspired by [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent).

## Features

- Self-improving agent with built-in learning loop
- Cross-session memory management
- Multi-model LLM support (OpenRouter, OpenAI, Claude, Kimi, GLM, etc.)
- Skill system with autonomous skill creation
- Scheduled tasks with cron expressions
- Sub-agent parallel execution
- Multi-platform gateway (Telegram, Discord, Slack, etc.)
- REST API with reactive WebFlux
- Spring Boot 3.x + WebFlux architecture

## Tech Stack

- Java 17
- Spring Boot 3.2
- Spring WebFlux (reactive)
- Spring Data Redis
- Reactor Netty
- Jackson JSON

## Quick Start

### 1. Prerequisites

- JDK 17+
- Maven 3.8+
- (Optional) Redis for persistent memory

### 2. Configure

Edit `src/main/resources/application.properties`:

\`\`\`properties
hermes.api-key=your-openrouter-api-key
hermes.default-model=gpt-4o-mini
\`\`\`

### 3. Build

\`\`\`bash
mvn clean package -DskipTests
\`\`\`

### 4. Run

\`\`\`bash
java -jar target/hermes-agent-1.0.0.jar
\`\`\`

### 5. Chat via API

\`\`\`bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"user1","message":"Hello, how are you?"}'
\`\`\`

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/chat | Send a chat message |
| GET | /api/sessions | List active sessions |
| DELETE | /api/sessions/{id} | Clear a session |
| GET | /api/scheduler/jobs | List scheduled jobs |
| POST | /api/scheduler/jobs | Schedule a cron job |
| GET | /api/subagents | List active sub-agents |
| POST | /api/subagents | Spawn a sub-agent |

## Architecture

```
com.hermes.agent
  Agent.java              - Core agent loop
  config/                 - Configuration
  model/                  - Data models (Message, ToolCall)
  memory/                 - Memory management
  skills/                 - Skill system
  tools/                  - Tool registry & built-in tools
  llm/                    - LLM service adapters
  gateway/                - Multi-platform messaging
  scheduler/              - Cron job scheduler
  subagent/               - Sub-agent management
  api/                    - REST API controllers
```

## License

MIT
