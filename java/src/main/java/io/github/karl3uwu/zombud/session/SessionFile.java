package io.github.karl3uwu.zombud.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Reads and writes session files (format v1, {@code doc/SessionFile.md}). Validation is strict: every problem is
 * collected and reported together. Unknown fields are ignored.
 */
public final class SessionFile {
    private SessionFile() {}

    /** The file can't be used. {@link #errors()} lists every problem found. */
    public static class InvalidSessionException extends Exception {
        private final List<String> errors;

        InvalidSessionException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    /** The file declares a schema version this loader doesn't know. */
    public static final class UnsupportedSchemaException extends InvalidSessionException {
        UnsupportedSchemaException(String message) {
            super(List.of(message));
        }
    }

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern JAVA_PKG = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern WINDOWS_ABS = Pattern.compile("^([A-Za-z]:[\\\\/]|\\\\\\\\).*");

    public static Session read(Path file) throws IOException, InvalidSessionException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static Session parse(String json) throws InvalidSessionException {
        if (json != null && !json.isEmpty() && json.charAt(0) == '\uFEFF') {
            json = json.substring(1); // tolerate a UTF-8 BOM written by some Windows tools
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json == null ? "" : json);
        } catch (JsonParseException e) {
            throw new InvalidSessionException(List.of("not valid JSON: " + e.getMessage()));
        }
        if (root == null || !root.isJsonObject()) {
            throw new InvalidSessionException(List.of("the file must contain a JSON object"));
        }
        JsonObject o = root.getAsJsonObject();

        // schema first: an unknown version means none of the rest can be trusted
        JsonElement schemaEl = o.get("schema");
        if (schemaEl == null || !isInt(schemaEl)) {
            throw new InvalidSessionException(List.of("schema: required integer"));
        }
        int schema = schemaEl.getAsInt();
        if (schema != Session.SCHEMA_VERSION) {
            throw new UnsupportedSchemaException("schema: version " + schema + " is not supported (this loader reads "
                + Session.SCHEMA_VERSION + ")");
        }

        Errors err = new Errors();
        String id = err.requiredString(o, "id");
        String created = err.requiredString(o, "created");
        if (created != null) {
            try {
                OffsetDateTime.parse(created);
            } catch (DateTimeParseException e) {
                err.add("created: not an RFC 3339 date-time: " + created);
            }
        }
        String gameBuild = err.requiredString(o, "gameBuild");
        String loaderVersion = err.requiredString(o, "loaderVersion");
        Session.Intent intent = readIntent(o, err);
        List<Session.Mod> mods = readMods(o, err);
        Boolean safeMode = err.requiredBool(o, "safeMode", "safeMode");
        Session.CoreFixes coreFixes = readCoreFixes(o, err);
        String stageFile = err.requiredAbsPath(o, "stageFile", "stageFile");
        String reportDir = err.requiredAbsPath(o, "reportDir", "reportDir");

        err.throwIfAny();
        return new Session(schema, id, created, gameBuild, loaderVersion, intent, mods, safeMode, coreFixes,
            stageFile, reportDir);
    }

    private static Session.Intent readIntent(JsonObject o, Errors err) {
        JsonObject i = err.requiredObject(o, "intent", "intent");
        if (i == null) return null;
        String kindStr = err.requiredString(i, "kind", "intent.kind");
        Session.IntentKind kind = null;
        if (kindStr != null) {
            try {
                kind = Session.IntentKind.valueOf(kindStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                err.add("intent.kind: must be one of continue, host, new, join (got \"" + kindStr + "\")");
            }
        }
        String save = err.optionalString(i, "save", "intent.save");
        String server = err.optionalString(i, "server", "intent.server");
        String address = err.optionalString(i, "address", "intent.address");
        if (kind == Session.IntentKind.CONTINUE && isBlank(save)) err.add("intent.save: required when kind is continue");
        if (kind == Session.IntentKind.HOST && isBlank(server)) err.add("intent.server: required when kind is host");
        if (kind == Session.IntentKind.JOIN && isBlank(address)) err.add("intent.address: required when kind is join");
        return new Session.Intent(kind, save, server, address);
    }

    private static List<Session.Mod> readMods(JsonObject o, Errors err) {
        JsonElement el = o.get("mods");
        if (el == null || !el.isJsonArray()) {
            err.add("mods: required array");
            return List.of();
        }
        List<Session.Mod> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonArray arr = el.getAsJsonArray();
        for (int n = 0; n < arr.size(); n++) {
            String at = "mods[" + n + "]";
            if (!arr.get(n).isJsonObject()) {
                err.add(at + ": must be an object");
                continue;
            }
            JsonObject m = arr.get(n).getAsJsonObject();
            String modId = err.requiredString(m, "id", at + ".id");
            if (modId != null) {
                modId = normalizeModId(modId);
                if (modId.isEmpty()) err.add(at + ".id: empty");
                else if (!seen.add(modId)) err.add(at + ".id: \"" + modId + "\" is listed twice");
            }
            Long workshopId = null;
            JsonElement ws = m.get("workshopId");
            if (ws != null && !ws.isJsonNull()) {
                if (isInt(ws) && ws.getAsLong() > 0) workshopId = ws.getAsLong();
                else err.add(at + ".workshopId: must be a positive integer");
            }
            String dir = err.requiredAbsPath(m, "dir", at + ".dir");
            List<Session.JavaJar> jars = new ArrayList<>();
            JsonElement j = m.get("java");
            if (j != null && !j.isJsonNull()) {
                if (!j.isJsonArray()) {
                    err.add(at + ".java: must be an array");
                } else {
                    JsonArray ja = j.getAsJsonArray();
                    for (int k = 0; k < ja.size(); k++) {
                        String jat = at + ".java[" + k + "]";
                        if (!ja.get(k).isJsonObject()) {
                            err.add(jat + ": must be an object");
                            continue;
                        }
                        JsonObject jo = ja.get(k).getAsJsonObject();
                        String jar = err.requiredAbsPath(jo, "jar", jat + ".jar");
                        String sha = err.requiredString(jo, "sha256", jat + ".sha256");
                        if (sha != null && !SHA256.matcher(sha).matches()) {
                            err.add(jat + ".sha256: must be 64 hex digits");
                        }
                        String pkg = err.requiredString(jo, "pkg", jat + ".pkg");
                        if (pkg != null && !JAVA_PKG.matcher(pkg).matches()) {
                            err.add(jat + ".pkg: not a Java package name: " + pkg);
                        }
                        jars.add(new Session.JavaJar(jar, sha != null ? sha.toLowerCase(Locale.ROOT) : null, pkg));
                    }
                }
            }
            out.add(new Session.Mod(modId, workshopId, dir, List.copyOf(jars)));
        }
        return List.copyOf(out);
    }

    private static Session.CoreFixes readCoreFixes(JsonObject o, Errors err) {
        JsonObject c = err.requiredObject(o, "coreFixes", "coreFixes");
        if (c == null) return null;
        Boolean enabled = err.requiredBool(c, "enabled", "coreFixes.enabled");
        Map<String, Boolean> overrides = new LinkedHashMap<>();
        JsonElement ov = c.get("overrides");
        if (ov != null && !ov.isJsonNull()) {
            if (!ov.isJsonObject()) {
                err.add("coreFixes.overrides: must be an object");
            } else {
                for (var e : ov.getAsJsonObject().entrySet()) {
                    if (isBool(e.getValue())) overrides.put(e.getKey(), e.getValue().getAsBoolean());
                    else err.add("coreFixes.overrides." + e.getKey() + ": must be true or false");
                }
            }
        }
        return new Session.CoreFixes(Boolean.TRUE.equals(enabled), Map.copyOf(overrides));
    }

    /** Mod ids may be written the way mod.info's require= writes them ({@code \ModId}); the game stores them bare. */
    static String normalizeModId(String id) {
        String s = id.trim();
        while (s.startsWith("\\")) s = s.substring(1);
        return s.trim();
    }

    /** Absolute path in Windows ({@code C:\...}, {@code C:/...}, {@code \\server\...}) or POSIX ({@code /...}) form. */
    static boolean isAbsolutePath(String p) {
        return p != null && (p.startsWith("/") || WINDOWS_ABS.matcher(p).matches());
    }

    // ---- writing (CLI, launchers written in Java) ------------------------------------------------------------

    public static String toJson(Session s) {
        JsonObject o = new JsonObject();
        o.addProperty("schema", s.schema());
        o.addProperty("id", s.id());
        o.addProperty("created", s.created());
        o.addProperty("gameBuild", s.gameBuild());
        o.addProperty("loaderVersion", s.loaderVersion());
        JsonObject intent = new JsonObject();
        intent.addProperty("kind", s.intent().kind().name().toLowerCase(Locale.ROOT));
        if (s.intent().save() != null) intent.addProperty("save", s.intent().save());
        if (s.intent().server() != null) intent.addProperty("server", s.intent().server());
        if (s.intent().address() != null) intent.addProperty("address", s.intent().address());
        o.add("intent", intent);
        JsonArray mods = new JsonArray();
        for (Session.Mod m : s.mods()) {
            JsonObject mo = new JsonObject();
            mo.addProperty("id", m.id());
            if (m.workshopId() != null) mo.addProperty("workshopId", m.workshopId());
            mo.addProperty("dir", m.dir());
            if (!m.java().isEmpty()) {
                JsonArray ja = new JsonArray();
                for (Session.JavaJar j : m.java()) {
                    JsonObject jo = new JsonObject();
                    jo.addProperty("jar", j.jar());
                    jo.addProperty("sha256", j.sha256());
                    jo.addProperty("pkg", j.pkg());
                    ja.add(jo);
                }
                mo.add("java", ja);
            }
            mods.add(mo);
        }
        o.add("mods", mods);
        o.addProperty("safeMode", s.safeMode());
        JsonObject cf = new JsonObject();
        cf.addProperty("enabled", s.coreFixes().enabled());
        JsonObject ov = new JsonObject();
        s.coreFixes().overrides().forEach(ov::addProperty);
        cf.add("overrides", ov);
        o.add("coreFixes", cf);
        o.addProperty("stageFile", s.stageFile());
        o.addProperty("reportDir", s.reportDir());
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o) + "\n";
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isInt(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) return false;
        try {
            return new java.math.BigDecimal(p.getAsString()).stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static boolean isBool(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isBoolean();
    }

    private static final class Errors {
        final List<String> list = new ArrayList<>();

        void add(String s) {
            list.add(s);
        }

        void throwIfAny() throws InvalidSessionException {
            if (!list.isEmpty()) throw new InvalidSessionException(list);
        }

        String requiredString(JsonObject o, String key) {
            return requiredString(o, key, key);
        }

        String requiredString(JsonObject o, String key, String at) {
            JsonElement e = o.get(key);
            if (e == null || e instanceof JsonNull) {
                add(at + ": required");
                return null;
            }
            if (!(e instanceof JsonPrimitive p) || !p.isString()) {
                add(at + ": must be a string");
                return null;
            }
            String s = p.getAsString();
            if (s.isBlank()) {
                add(at + ": must not be empty");
                return null;
            }
            return s;
        }

        String optionalString(JsonObject o, String key, String at) {
            JsonElement e = o.get(key);
            if (e == null || e instanceof JsonNull) return null;
            if (!(e instanceof JsonPrimitive p) || !p.isString()) {
                add(at + ": must be a string");
                return null;
            }
            return p.getAsString();
        }

        String requiredAbsPath(JsonObject o, String key, String at) {
            String s = requiredString(o, key, at);
            if (s != null && !isAbsolutePath(s)) {
                add(at + ": must be an absolute path: " + s);
                return null;
            }
            return s;
        }

        Boolean requiredBool(JsonObject o, String key, String at) {
            JsonElement e = o.get(key);
            if (e == null || !isBool(e)) {
                add(at + ": required true or false");
                return null;
            }
            return e.getAsBoolean();
        }

        JsonObject requiredObject(JsonObject o, String key, String at) {
            JsonElement e = o.get(key);
            if (e == null || !e.isJsonObject()) {
                add(at + ": required object");
                return null;
            }
            return e.getAsJsonObject();
        }
    }
}
