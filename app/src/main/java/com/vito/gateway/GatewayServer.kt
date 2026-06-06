package com.vito.gateway

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** 內嵌 HTTP/MCP server（NanoHTTPD）。對應桌面 gateway 的 FastAPI /health /mcp。 */
class GatewayServer(
    private val ctx: Context,
    port: Int,
    private val secret: String,
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/health" -> json(JSONObject().put("status", "ok").put("platform", "android").put("ready", BrowserController.isReady()))
                "/mcp" -> handleMcp(session)
                else -> json(JSONObject().put("error", "not found"), Response.Status.NOT_FOUND)
            }
        } catch (e: Throwable) {
            json(JSONObject().put("error", e.message), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleMcp(session: IHTTPSession): Response {
        // 密鑰驗證（與桌面一致：X-Gateway-Secret 或 Authorization: Bearer）
        val hdr = session.headers
        val given = hdr["x-gateway-secret"] ?: hdr["authorization"]?.removePrefix("Bearer ")?.trim()
        if (secret.isNotEmpty() && given != secret) {
            return json(JSONObject().put("detail", "unauthorized"), Response.Status.UNAUTHORIZED)
        }

        val body = readBody(session)
        val req = if (body.isNotBlank()) JSONObject(body) else JSONObject()
        val method = req.optString("method")
        val id = if (req.has("id")) req.get("id") else JSONObject.NULL

        // 通知類（無 id）
        if (method.startsWith("notifications/")) {
            return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "{}")
        }
        return when (method) {
            "initialize" -> rpc(id, JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("capabilities", JSONObject().put("tools", JSONObject()))
                put("serverInfo", JSONObject().put("name", "android-gateway").put("version", "1.0"))
            })
            "tools/list" -> rpc(id, JSONObject().put("tools", McpTools.list()))
            "tools/call" -> {
                val params = req.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                try {
                    val text = McpTools.call(ctx, name, args)
                    rpc(id, JSONObject().put("content", org.json.JSONArray().put(
                        JSONObject().put("type", "text").put("text", text))))
                } catch (e: Throwable) {
                    rpcErr(id, -32000, e.message ?: "error")
                }
            }
            else -> rpcErr(id, -32601, "method not found: $method")
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        return try { session.parseBody(map); map["postData"] ?: "" } catch (e: Throwable) { "" }
    }

    private fun rpc(id: Any?, result: JSONObject) =
        json(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))

    private fun rpcErr(id: Any?, code: Int, msg: String) =
        json(JSONObject().put("jsonrpc", "2.0").put("id", id).put("error",
            JSONObject().put("code", code).put("message", msg)))

    private fun json(o: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json", o.toString())
}

object Net {
    fun localIp(ctx: Context): String {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress ?: ""
                }
            }
        } catch (e: Throwable) {}
        return "127.0.0.1"
    }
}
