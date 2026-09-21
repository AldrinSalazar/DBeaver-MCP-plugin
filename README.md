# DBeaver MCP Server

A DBeaver plugin that spins up a [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server with **Streamable HTTP** transport inside DBeaver, exposing your database connections to MCP clients (Claude, VS Code, JetBrains, etc.).

The plugin **reuses the connections stored in DBeaver** — it never manages credentials itself. Each query runs on its own isolated session opened from your stored connection (DBeaver connects on demand; connect it in DBeaver first if it needs interactive credentials).

## Tools

| Tool | Description | Available in |
|---|---|---|
| `list_connections` | Connections you exposed (id, name, project, driver, state, access, protection). | All modes |
| `browse` | Walks the object tree: catalogs → schemas → tables/views. | All modes |
| `describe_table` | Columns with types, primary/foreign/unique keys, indexes. | All modes |
| `get_ddl` | DDL/source of tables, views, procedures, functions, triggers. | All modes |
| `list_procedures` | Procedures/functions with parameters. | All modes |
| `list_triggers` | Triggers of a table. | All modes |
| `query_sql` | One single read-only statement (`maxRows`, default 100, max 5000). Always protected; writes rejected. | Read only, Read/write |
| `execute_sql` | One single statement, reads or writes. | Read/write only |

All SQL executed through MCP runs as a normal DBeaver query on an isolated session, so it shows up in the Query Manager for auditing.

## Security model

Read-only safety is treated as a security boundary and enforced in layers — never by SQL text inspection alone.

- Listens on **127.0.0.1 by default** (no LAN exposure); the listen host is configurable in preferences, with a warning when set to a non-loopback address.
- Requires a **Bearer token** by default (auto-generated, copyable from preferences). Token authentication can be disabled for local setups — not recommended, especially on a non-loopback host.
- Rejects non-local `Origin` headers (DNS-rebinding protection).
- **Three access modes**: Metadata only (no SQL tools at all), Read only, Read/write. `execute_sql` is only advertised in read/write mode.
- **Per-connection grants**: each connection separately enables Metadata access, Read-only queries, and Write queries. Connections without any grant are invisible to MCP clients.
- Reads (`query_sql`) are protected in layers: isolated session, driver read-only flag, explicit transaction, database-native read-only transaction where supported, SQL validation (single statement; rejects `SELECT ... INTO`, data-modifying CTEs, `EXPLAIN ANALYZE` of writes), query timeout, row cap, and rollback. Any protection failure aborts the query instead of downgrading silently.
- Writes run on isolated sessions too, so an MCP write commits only its own statement and never touches your open transactions.
- Connections marked read-only in DBeaver never expose writes, regardless of plugin settings.
- Passwords are never read or returned, and connection URLs are not exposed.

Read-only protection strength depends on the database (shown per connection in preferences and in every `query_sql` response):

| Database | Protection |
|---|---|
| PostgreSQL (+ EDB, Cloud SQL, Greenplum) | Database enforced (`START TRANSACTION READ ONLY`) |
| MySQL 5.6+ / MariaDB | Database enforced (`START TRANSACTION READ ONLY`) |
| Oracle | Database enforced (`SET TRANSACTION READ ONLY`) |
| SQLite | Driver enforced (`PRAGMA query_only`, verified) |
| Anything else (SQL Server, …) | Best effort (validation + hint + rollback, clearly marked) |

A strict "MCP cannot modify the database" guarantee additionally requires database-level authorization (a dedicated read-only user or a read replica) — SQL inspection alone is never presented as such a guarantee.

## Requirements

- Recent DBeaver running on Java 21 (25.0+, verified against 26.2.1) — Community or Pro.
- JDK 21+ and Maven 3.9+ to build.

## Build

```powershell
# Windows PowerShell, from this directory:
.\scripts\install-dbeaver-libs.ps1   # one-time, registers 2 DBeaver jars for unit tests
mvn clean verify
```

```bash
# Linux/macOS:
./scripts/install-dbeaver-libs.sh /path/to/dbeaver   # one-time, for unit tests
mvn clean verify
```

This produces the installable p2 repository ZIP (self-contained plugin ~9 MB plus repository metadata):

```text
repository/dev.astronauta.dbeaverMCP.repository/target/dbeaver-mcp-0.1.0.zip
```

The build is a Tycho multi-module reactor (`plugins/`, `features/`, `repository/`, `tests/`). Eclipse and DBeaver APIs resolve from p2 (pinned Eclipse 2026-09 release plus the DBeaver CE update site); third-party libraries (MCP SDK, Jetty, Jackson, Reactor) are embedded inside the plugin bundle.

## Install

1. Download `dbeaver-mcp-0.1.0.zip` from the project's release page (or build it above).
2. In DBeaver, open **Help → Install New Software…**.
3. Click **Add… → Archive…** and select the ZIP.
4. Tick **DBeaver MCP → DBeaver MCP Server**, click **Next**, review, **Finish**.
5. Restart DBeaver when prompted.
6. Open **Window → Preferences → MCP Server**:
   - Pick an access mode: Metadata only, Read only (default), or Read/write.
   - Select each connection and grant Metadata access, Read-only queries, and/or Write queries.
   - Copy the bearer token for your MCP client.
   - The server starts automatically; *Start now* / *Stop* override it.

To uninstall: **Help → About DBeaver → Installation Details → Installed Software**, select **DBeaver MCP Server**, **Uninstall**, restart. To upgrade, install the newer ZIP the same way.

## Verifying releases

Release ZIPs ship with SHA-256 checksums (`.zip.sha256`) and, when signing is configured, a detached PGP signature (`.zip.asc`) plus the signing public key (`dbeaver-mcp-signing-key.asc`). Download them alongside the ZIP from the release page, then:

```bash
sha256sum -c dbeaver-mcp-0.1.0.zip.sha256
gpg --import dbeaver-mcp-signing-key.asc
gpg --verify dbeaver-mcp-0.1.0.zip.asc dbeaver-mcp-0.1.0.zip
```

Signing requires the repository secrets `GPG_PRIVATE_KEY` (ASCII-armored secret key, `gpg --armor --export-secret-keys KEYID`) and `GPG_PASSPHRASE` if the key has one; without them releases carry checksums only.

## Client configuration

The endpoint is `http://127.0.0.1:4319/mcp` (or your configured host/port). Every client authenticates with the bearer token from Preferences > MCP Server, shown as `<TOKEN>` below. (If you disabled token auth in preferences, omit the header.)

<details>
<summary><img src="https://cdn.simpleicons.org/anthropic/D97757" width="20" height="20" /> <b>Claude Code</b></summary>

```bash
claude mcp add --transport http dbeaver http://127.0.0.1:4319/mcp \
  --header "Authorization: Bearer <TOKEN>"
```

Verify with `claude mcp list` (or `/mcp` inside a session). Add `--scope user` to share it across all your projects; by default it is stored for the current project only. Teams can commit the same entry to `.mcp.json` instead.

</details>

<details>
<summary><img src="https://cdn.jsdelivr.net/npm/simple-icons@11.14.0/icons/openai.svg" width="20" height="20" /> <b>Codex</b></summary>

Add to `~/.codex/config.toml` (or `.codex/config.toml` for a single project):

```toml
[mcp_servers.dbeaver]
url = "http://127.0.0.1:4319/mcp"
bearer_token_env_var = "DBEAVER_MCP_TOKEN"
```

```bash
export DBEAVER_MCP_TOKEN="<TOKEN>"
```

Verify with `codex mcp list` (or `/mcp` in the TUI). Prefer the env var over hard-coding the token; alternatively use `http_headers = { Authorization = "Bearer <TOKEN>" }`.

</details>

<details>
<summary><img src="https://opencode.ai/favicon.ico" width="20" height="20" /> <b>OpenCode</b></summary>

Add to `opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "dbeaver": {
      "type": "remote",
      "url": "http://127.0.0.1:4319/mcp",
      "headers": { "Authorization": "Bearer <TOKEN>" }
    }
  }
}
```

Verify with `opencode mcp list`. You can reference an env var instead of pasting the token: `"Bearer {env:DBEAVER_MCP_TOKEN}"`.

</details>

<details>
<summary><img src="https://pi.dev/logo-auto.svg" width="20" height="20" /> <b>Pi</b></summary>

Pi has no built-in MCP support, so install the community adapter first (then restart Pi):

```bash
pi install npm:pi-mcp-adapter
```

Then add the server to `.mcp.json` (current project) or `~/.config/mcp/mcp.json` (all projects):

```json
{
  "mcpServers": {
    "dbeaver": {
      "url": "http://127.0.0.1:4319/mcp",
      "headers": { "Authorization": "Bearer <TOKEN>" }
    }
  }
}
```

Manage it from Pi's `/mcp` panel. If you already configured another client on this machine, `/mcp setup` can import its servers instead of hand-writing the file.

</details>

<details>
<summary><b>Other clients (generic Streamable HTTP)</b></summary>

```json
{
  "mcpServers": {
    "dbeaver": {
      "url": "http://127.0.0.1:4319/mcp",
      "headers": { "Authorization": "Bearer <TOKEN>" }
    }
  }
}
```

For clients that only speak stdio, put any stdio→HTTP MCP proxy in front (e.g. `mcp-remote`) pointing at the URL above with the header.

</details>

## Troubleshooting

- **Server won't start / port in use**: change the port in preferences; another app may hold 4319.
- **Empty `list_connections`**: no connection has any grant yet — select connections and grant access in preferences.
- **`... is not exposed over MCP`**: grant that connection Metadata access (and Read-only/Write queries as needed) and Apply.
- **No `query_sql` / no `execute_sql` in tools list**: check the global access mode — SQL tools only appear in Read only / Read-write mode.
- **Writes rejected**: needs read/write mode + the Write queries grant + a non-read-only connection in DBeaver.
- **Connect errors**: connect the database once in DBeaver so credentials/tunnels are established.
- **Install fails with unresolved dependencies**: the feature requires a DBeaver providing `org.jkiss.dbeaver.model` and a Java 21 Eclipse platform — use a recent DBeaver CE (25.0+).
- **Plugin not loading**: check **Help → About DBeaver → Installation Details → Installed Software** for **DBeaver MCP Server**, and `<workspace>/.metadata/.log` for errors.

## Development notes

- Tycho 5.0.4 multi-module build: `plugins/` (OSGi bundle), `features/` (installable feature), `repository/` (p2 site + release ZIP), `tests/` (plain unit tests).
- Java 21 throughout; Eclipse/DBeaver APIs come from the p2 target platform, third-party libs from Maven Central (embedded under the bundle's `lib/`).
- `mvn test` runs unit tests plus a live Streamable-HTTP round-trip test (boots the real Jetty + MCP stack on an ephemeral port). The tests module needs the two DBeaver jars from `scripts/install-dbeaver-libs.*`.
- When upgrading a third-party dependency version, sync the explicit artifact list in the plugin `pom.xml` **and** `Bundle-ClassPath` in `META-INF/MANIFEST.MF` with the new file names.
- Releases: bump the Maven/OSGi versions together with the `release.version` property, tag `vX.Y.Z`, and publish `dbeaver-mcp-X.Y.Z.zip` — CI attaches checksums and, when the GPG secrets are configured (see [Verifying releases](#verifying-releases)), a PGP signature and the signing public key (`mvn tycho-versions:set-version` can bump Tycho versions).
- Key classes: `db.DBeaverBridge` (all DBeaver access), `db.SqlGuard` (read/write classification), `db.ValueRenderer` (DB values → JSON-safe data), `db.readonly.*` (database-specific read-only enforcement), `server.McpServerManager` (Jetty + MCP wiring), `server.tools.*` (one class per MCP tool, `Schema` builder for input schemas, `ToolArgs` for arguments), `ui.McpPreferencePage` (config UI).
