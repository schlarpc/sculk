package sculk.core.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 2026-07-28 and later, which the spec calls modern: stateless. There is
 * no initialize and no session. Every request carries its protocol version
 * and client capabilities in {@code _meta}, and on HTTP mirrors its version,
 * method and (for tools/call) tool name into headers, which must match the
 * body. Results carry {@code resultType}, and list results say how long they
 * may be cached.
 *
 * <p>Modern requests need the control token. Pairing ({@link McpHandler})
 * approves a session, and this era has none to approve: nothing in a
 * request identifies the client except what the client says about itself,
 * which the spec rules out for security decisions. So a modern request
 * without the token is answered as a server of the earlier era would answer
 * it, with a 400 that carries none of the modern error codes. A client that
 * speaks both eras (Claude Code does) takes that as its cue to fall back to
 * initialize, where it can pair.
 */
final class McpStateless {
    /** The versions served here, newest first. */
    static final List<String> VERSIONS = List.of("2026-07-28");

    static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    static final int HEADER_MISMATCH = -32020;
    static final int UNSUPPORTED_VERSION = -32022;
    /** How long a client may cache tools/list: the tools never change while the game runs. */
    private static final long TOOLS_TTL_MILLIS = 3_600_000;

    private final McpHandler mcp;

    McpStateless(McpHandler mcp) {
        this.mcp = mcp;
    }

    /**
     * Whether a request is modern: its MCP-Protocol-Version header names a
     * modern version, or its body carries the per-request version in
     * {@code _meta}. A version this server has never heard of counts as
     * modern only with the latter, so an old client's typo stays an old
     * client's error.
     */
    static boolean isModern(String headerVersion, JsonObject msg) {
        if (headerVersion != null && VERSIONS.contains(headerVersion.trim())) return true;
        return meta(msg) != null && meta(msg).has(META_VERSION);
    }

    /** Serves one modern POST. {@code hasToken} says whether it carried the control token. */
    void post(HttpExchange ex, JsonElement parsed, boolean hasToken) throws IOException {
        if (!hasToken) {
            // Not a modern error code: a dual-era client falls back to initialize, and can pair there.
            ControlServer.json(ex, 400, McpHandler.error(null, -32600,
                    "MCP " + VERSIONS.get(0) + " needs the control token (Authorization: Bearer). Without it, "
                            + "connect with initialize (MCP 2025-11-25) and ask the player to approve this client in game."));
            return;
        }
        if (parsed == null || !parsed.isJsonObject()) {
            ControlServer.json(ex, 400, McpHandler.error(null, -32600, "expected a single JSON-RPC message object"));
            return;
        }
        JsonObject msg = parsed.getAsJsonObject();
        String method = msg.has("method") && msg.get("method").isJsonPrimitive() ? msg.get("method").getAsString() : null;
        Object id = msg.has("id") && !msg.get("id").isJsonNull() ? McpHandler.requestId(msg.get("id")) : null;
        if (method == null) {
            ControlServer.json(ex, 400, McpHandler.error(id, -32600, "clients send requests only, and a request has a method"));
            return;
        }
        if (!msg.has("id")) {
            // No client notifications are defined over HTTP in this era; accept and ignore any.
            ex.sendResponseHeaders(202, -1);
            return;
        }
        if (id == null) {
            ControlServer.json(ex, 400, McpHandler.error(null, -32600, "a request id must not be null"));
            return;
        }

        JsonObject meta = meta(msg);
        String version = meta != null && meta.has(META_VERSION) && meta.get(META_VERSION).isJsonPrimitive()
                ? meta.get(META_VERSION).getAsString() : null;
        if (version == null || meta == null || !meta.has(META_CAPABILITIES) || !meta.get(META_CAPABILITIES).isJsonObject()) {
            ControlServer.json(ex, 400, McpHandler.error(id, -32602,
                    "_meta needs " + META_VERSION + " (a string) and " + META_CAPABILITIES + " (an object)"));
            return;
        }
        String mismatch = checkHeaders(ex, msg, method, version);
        if (mismatch != null) {
            ControlServer.json(ex, 400, McpHandler.error(id, HEADER_MISMATCH, "Header mismatch: " + mismatch));
            return;
        }
        if (!VERSIONS.contains(version)) {
            Map<String, Object> out = McpHandler.error(id, UNSUPPORTED_VERSION, "Unsupported protocol version");
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) out.get("error");
            error.put("data", Map.of("supported", supportedVersions(), "requested", version));
            ControlServer.json(ex, 400, out);
            return;
        }

        JsonObject params = msg.getAsJsonObject("params");
        switch (method) {
            case "server/discover" -> {
                Map<String, Object> result = result();
                result.put("supportedVersions", supportedVersions());
                result.put("capabilities", McpHandler.CAPABILITIES);
                result.put("instructions", mcp.instructions());
                result.put("ttlMs", TOOLS_TTL_MILLIS);
                result.put("cacheScope", "private");
                ControlServer.json(ex, 200, response(id, result));
            }
            case "tools/list" -> {
                Map<String, Object> result = result();
                result.put("tools", McpHandler.tools());
                result.put("ttlMs", TOOLS_TTL_MILLIS);
                result.put("cacheScope", "private");
                ControlServer.json(ex, 200, response(id, result));
            }
            case "tools/call" -> call(ex, id, params);
            default -> ControlServer.json(ex, 404, McpHandler.error(id, -32601, "method not found: " + method));
        }
    }

    /**
     * Runs a tool. It may answer as a stream of progress notifications ending
     * in the response; closing that stream cancels the call, the only
     * cancellation this era has over HTTP.
     */
    private void call(HttpExchange ex, Object id, JsonObject params) throws IOException {
        String accept = String.valueOf(ex.getRequestHeaders().getFirst("Accept"));
        JsonObject meta = params.getAsJsonObject("_meta");
        Object token = meta.has("progressToken") ? McpHandler.requestId(meta.get("progressToken")) : null;
        McpHandler.Call call = new McpHandler.Call(accept.contains("text/event-stream") ? ex : null, token);
        Map<String, Object> response;
        try {
            Map<String, Object> result = mcp.call(params, call);
            Map<String, Object> full = result();
            full.putAll(result);
            response = response(id, full);
        } catch (java.util.concurrent.CancellationException e) {
            return; // The client closed the stream: nothing more may be sent.
        } catch (IllegalArgumentException e) {
            response = McpHandler.error(id, -32602, String.valueOf(e.getMessage()));
        }
        synchronized (call) {
            if (call.cancelled()) return;
            if (call.streaming()) call.send(response);
            else ControlServer.json(ex, 200, response);
        }
    }

    /**
     * Checks the mirrored headers against the body: MCP-Protocol-Version and
     * Mcp-Method always, Mcp-Name for tools/call. Returns what is wrong, or
     * null.
     */
    static String checkHeaders(HttpExchange ex, JsonObject msg, String method, String version) {
        var headers = ex.getRequestHeaders();
        String headerVersion = headers.getFirst("MCP-Protocol-Version");
        if (headerVersion == null) return "MCP-Protocol-Version header is missing";
        if (!headerVersion.trim().equals(version)) {
            return "MCP-Protocol-Version header '" + headerVersion + "' does not match body value '" + version + "'";
        }
        String headerMethod = headers.getFirst("Mcp-Method");
        if (headerMethod == null) return "Mcp-Method header is missing";
        if (!headerMethod.trim().equals(method)) {
            return "Mcp-Method header '" + headerMethod + "' does not match body value '" + method + "'";
        }
        if (method.equals("tools/call") || method.equals("resources/read") || method.equals("prompts/get")) {
            JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
            String key = method.equals("resources/read") ? "uri" : "name";
            String body = params.has(key) && params.get(key).isJsonPrimitive() ? params.get(key).getAsString() : null;
            String headerName = headers.getFirst("Mcp-Name");
            if (headerName == null) return "Mcp-Name header is missing";
            String decoded = decode(headerName.trim());
            if (decoded == null) return "Mcp-Name header is not valid base64";
            if (!decoded.equals(body)) return "Mcp-Name header value '" + headerName + "' does not match body value '" + body + "'";
        }
        return null;
    }

    /** A header value, with the {@code =?base64?...?=} sentinel decoded; null if that encoding is broken. */
    static String decode(String value) {
        if (!(value.startsWith("=?base64?") && value.endsWith("?=") && value.length() >= 11)) return value;
        try {
            return new String(Base64.getDecoder().decode(value.substring(9, value.length() - 2)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Every version this server speaks, in either era, newest first. */
    static List<String> supportedVersions() {
        List<String> all = new ArrayList<>(VERSIONS);
        all.addAll(McpHandler.PROTOCOL_VERSIONS);
        return all;
    }

    private static JsonObject meta(JsonObject msg) {
        if (msg == null || !msg.has("params") || !msg.get("params").isJsonObject()) return null;
        JsonObject params = msg.getAsJsonObject("params");
        return params.has("_meta") && params.get("_meta").isJsonObject() ? params.getAsJsonObject("_meta") : null;
    }

    /** A complete result that names the server, as every result in this era should. */
    private static Map<String, Object> result() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resultType", "complete");
        m.put("_meta", Map.of(META_SERVER_INFO, McpHandler.SERVER_INFO));
        return m;
    }

    private static Map<String, Object> response(Object id, Map<String, Object> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("result", result);
        return out;
    }
}
