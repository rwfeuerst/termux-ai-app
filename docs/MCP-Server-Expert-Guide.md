# MCP Server Expert Agent Guide

This guide configures an AI agent as an MCP (Model Context Protocol) server expert. The MCP Inspector project is set up at `/home/user/inspector/`.

## Inspector Setup

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is cloned and built at `/home/user/inspector/`. It provides:

- **Web UI** at `http://localhost:6274` for interactive MCP server testing
- **CLI mode** for scripted/automated testing
- **Proxy server** at port 6277 bridging browsers to MCP servers

### Quick Start
```bash
cd /home/user/inspector

# Dev mode
npm run dev

# Test your MCP server
npx @modelcontextprotocol/inspector node /path/to/your/server.js

# CLI mode
npx @modelcontextprotocol/inspector --cli node /path/to/server.js --method tools/list
```

## MCP Protocol Summary

MCP is an open standard (JSON-RPC 2.0) enabling AI applications to connect with external tools and data sources.

### Core Primitives

| Primitive | Control | Purpose |
|-----------|---------|---------|
| **Tools** | Model-controlled | Executable functions the AI can invoke |
| **Resources** | App-controlled | Readable data identified by URIs |
| **Prompts** | User-controlled | Reusable instruction templates |
| **Sampling** | Server-initiated | Request AI completions from client |
| **Elicitation** | Server-initiated | Request user input from client |

### Protocol Lifecycle
1. Client sends `initialize` with capabilities
2. Server responds with its capabilities
3. Client confirms with `notifications/initialized`
4. Normal operation (request/response + notifications)
5. Clean shutdown

## Building an MCP Server

### Minimal TypeScript Server

```typescript
#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const server = new McpServer({ name: "my-server", version: "1.0.0" });

// Tool
server.tool(
  "greet",
  "Greet someone",
  { name: z.string().describe("Name to greet") },
  async ({ name }) => ({
    content: [{ type: "text", text: `Hello, ${name}!` }],
  })
);

// Resource
server.resource(
  "readme",
  "file:///readme",
  { description: "README", mimeType: "text/markdown" },
  async () => ({
    contents: [{ uri: "file:///readme", text: "# My Project" }],
  })
);

// Prompt
server.prompt(
  "summarize",
  "Summarize a topic",
  { topic: z.string() },
  async ({ topic }) => ({
    messages: [{ role: "user", content: { type: "text", text: `Summarize: ${topic}` } }],
  })
);

const transport = new StdioServerTransport();
await server.connect(transport);
```

### Project Setup
```bash
mkdir my-mcp-server && cd my-mcp-server
npm init -y
npm install @modelcontextprotocol/sdk zod
npm install -D typescript @types/node
```

**package.json** essentials:
```json
{
  "type": "module",
  "bin": { "my-mcp-server": "build/index.js" },
  "scripts": {
    "build": "tsc",
    "start": "node build/index.js",
    "inspect": "npx @modelcontextprotocol/inspector node build/index.js"
  }
}
```

**tsconfig.json:**
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "Node16",
    "moduleResolution": "Node16",
    "outDir": "./build",
    "rootDir": "./src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true
  },
  "include": ["src/**/*"]
}
```

## Testing with Inspector

### UI Mode
```bash
npx @modelcontextprotocol/inspector node build/index.js
# Opens http://localhost:6274
```

### CLI Mode
```bash
# Tools
npx @modelcontextprotocol/inspector --cli node build/index.js --method tools/list
npx @modelcontextprotocol/inspector --cli node build/index.js --method tools/call --tool-name greet --tool-arg name=World

# Resources
npx @modelcontextprotocol/inspector --cli node build/index.js --method resources/list
npx @modelcontextprotocol/inspector --cli node build/index.js --method resources/read --uri "file:///readme"

# Prompts
npx @modelcontextprotocol/inspector --cli node build/index.js --method prompts/list
```

### Config File (mcp.json)
```json
{
  "mcpServers": {
    "my-server": {
      "command": "node",
      "args": ["build/index.js"],
      "env": { "API_KEY": "xxx" }
    }
  }
}
```

## Debugging Tips

1. **NEVER use `console.log()` in STDIO servers** - it corrupts the JSON-RPC protocol on stdout. Use `console.error()` or MCP logging instead
2. **Schema issues** - Use Zod with `.describe()` for clear tool parameter docs
3. **Tool not showing** - Ensure capabilities include `tools: {}` and registration happens before `server.connect()`
4. **Error results** - Return `isError: true` in tool results instead of throwing

## Best Practices

- One tool per function, clear descriptions
- Validate all inputs with Zod schemas
- Use environment variables for API keys
- Handle SIGINT/SIGTERM gracefully
- Test with Inspector before deploying
- Mark read-only tools with `readOnlyHint: true`

## Client Integration

### Claude Code (.mcp.json)
```json
{ "mcpServers": { "my-server": { "command": "node", "args": ["build/index.js"] } } }
```

### Claude Desktop (claude_desktop_config.json)
```json
{ "mcpServers": { "my-server": { "command": "npx", "args": ["my-mcp-server"] } } }
```

## Reference Servers

| Server | Package |
|--------|---------|
| Everything (all features) | `@modelcontextprotocol/server-everything` |
| Filesystem | `@modelcontextprotocol/server-filesystem` |
| GitHub | `@modelcontextprotocol/server-github` |
| Memory | `@modelcontextprotocol/server-memory` |

## Spec Versions

| Version | Key Additions |
|---------|--------------|
| 2024-11-05 | Initial release |
| 2025-03-26 | Streamable HTTP, OAuth, tool annotations |
| 2025-06-18 | Structured outputs (`outputSchema`), elicitation |
| 2025-11-25 | Tasks primitive for async operations |
