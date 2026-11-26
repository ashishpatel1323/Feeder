#!/usr/bin/env node
/** MCP server over stdio. Claude Desktop / Claude Code start this as a child process. */
import './quiet.js'
import './dom.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { DB_PATH, ensureDirs } from './config.js'
import { openDb } from './db.js'
import { startBackground } from './indexer.js'
import { buildServer } from './server.js'

// stdout carries the MCP protocol: anything else written there (pdf.js warnings use console.log) would break it
console.log = (...args: unknown[]) => console.error(...args)
console.info = console.log
console.warn = (...args: unknown[]) => console.error(...args)

ensureDirs()
const db = openDb(DB_PATH)
const server = buildServer(db)
await server.connect(new StdioServerTransport())
if (process.env.FEEDER_MCP_NO_BACKGROUND !== '1') startBackground(db)
console.error('[feeder-mcp] ready')
