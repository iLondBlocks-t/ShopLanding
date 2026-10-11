package com.ocpilot.app.core;

import android.content.Context;
import com.ocpilot.app.R;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ApiError extends Exception {
    public enum Kind { Unauthorized, RateLimited, Network, Server, Client, Parse, Storage, Cancelled }
    public final Kind kind;
    public final int status;
    public final String code, detail;
    public final long retrySeconds;
    public ApiError(Kind kind, int status, String code, String detail, long retrySeconds) {
        super(kind.name()); this.kind = kind; this.status = status; this.code = code == null ? "" : code;
        String safe = detail == null ? "" : detail;
        this.detail = safe.length() > 2000 ? safe.substring(0, 2000) : safe; this.retrySeconds = retrySeconds;
    }
    public ApiError(Kind kind) { this(kind, 0, "", "", 0); }
    public static ApiError http(int status, String body, String retry) {
        String code = "", detail = "";
        try { JSONObject j = new JSONObject(body); code = Models.string(j, "error"); detail = Models.string(j, "message"); }
        catch (Exception ignored) { }
        Kind kind = status == 401 ? Kind.Unauthorized : status == 429 ? Kind.RateLimited : status >= 500 ? Kind.Server : Kind.Client;
        return new ApiError(kind, status, code, detail, retrySeconds(retry));
    }
    private static long retrySeconds(String header) {
        if (header == null) return 1;
        try { return Math.max(1, Math.min(86400, Long.parseLong(header.trim()))); }
        catch (Exception ignored) {
            try {
                Date date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(header);
                return date == null ? 1 : Math.max(1, (date.getTime() - System.currentTimeMillis() + 999) / 1000);
            } catch (Exception ex) { return 1; }
        }
    }
    public String message(Context c) {
        int id;
        switch (code) {
            case "foreground_unavailable": id = R.string.foreground_unavailable; break;
            case "credential_changed": id = R.string.credential_changed; break;
            case "hash_mismatch": id = R.string.error_hash; break;
            case "busy_session": id = R.string.stop_first; break;
            case "queue_full": id = R.string.queue_full; break;
            case "invalid_code": id = R.string.error_invalid_code; break;
            case "too_many_devices": id = R.string.error_too_many_devices; break;
            case "invalid_github_token": case "github_token_invalid": id = R.string.error_invalid_github_token; break;
            case "github_permission_denied": id = R.string.error_github_permission_denied; break;
            case "github_required": id = R.string.error_github_required; break;
            case "git_unavailable": id = R.string.error_git_unavailable; break;
            case "insufficient_storage": id = R.string.error_insufficient_storage; break;
            case "project_exists": id = R.string.error_project_exists; break;
            case "project_not_found": id = R.string.error_project_not_found; break;
            case "opencode_unavailable": id = R.string.error_opencode_unavailable; break;
            case "payload_too_large": id = R.string.error_payload_too_large; break;
            case "github_unreachable": id = R.string.error_github_unreachable; break;
            case "not_found": id = R.string.error_not_found; break;
            case "forbidden_path": id = R.string.error_forbidden_path; break;
            case "invalid_json": id = R.string.error_invalid_json; break;
            case "confirmation_required": id = R.string.error_confirmation_required; break;
            case "github_rate_limited": case "rate_limited": return c.getString(R.string.error_rate_limited, Math.max(1, retrySeconds));
            default:
                switch (kind) {
                    case Unauthorized: id = R.string.error_unauthorized; break;
                    case RateLimited: return c.getString(R.string.error_rate_limited, Math.max(1, retrySeconds));
                    case Network: id = R.string.error_network; break;
                    case Server: id = R.string.error_server; break;
                    case Parse: id = R.string.error_parse; break;
                    case Storage: id = R.string.error_storage; break;
                    case Cancelled: id = R.string.error_cancelled; break;
                    default: id = R.string.error_client;
                }
        }
        return c.getString(id);
    }
}
