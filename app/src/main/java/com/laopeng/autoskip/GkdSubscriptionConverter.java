package com.laopeng.autoskip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the safe, directly-addressable portion of a GKD JSON5 subscription on device. */
final class GkdSubscriptionConverter {
    private static final int MAX_RULES_PER_APP = 80;
    private static final int MAX_MATCHES = 6;
    private static final String HIGH_CONF = "跳过|跳過|skip|关闭广告|关闭此广告|跳过广告|跳過廣告|略过|略過|跳过片头|跳过按钮";
    private static final Set<String> TOO_BROAD = set("x", "×", "✕", "✖", "关闭", "取消", "忽略", "否", "no", "close", "确定", "好的", "知道了", "以后再说", "暂不", "下次", "设置", "更多");
    private static final Set<String> BAD_WORDS = set("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "null", "true", "false", "none", "undefined");
    private static final Set<String> VAGUE = set("推荐", "广告", "荐读", "推荐阅读", "相关推荐", "热门", "热点", "发现", "视频", "直播", "话题", "评论", "转发", "收藏", "分享", "赞", "详情", "更多", "关注", "订阅", "客服", "举报");
    private static final String ACTION_ID = "close|btn|button|exit|skip|cancel|dismiss|del|delete|fold|collapse|back";
    private static final String CONTAINER_ID = "recycler|list|pager|grid|scroll|flipper|drawer|adapter|wrapper|holder|container|layout|root|content|item";
    private static final Pattern ID = Pattern.compile("(?<![\\w.])(?:vid|id)\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    private static final Pattern TEXT_EQ = Pattern.compile("\\btext\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    private static final Pattern TEXT_STAR = Pattern.compile("\\btext\\s*\\*\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    private static final Pattern TEXT_CARET = Pattern.compile("\\btext\\s*\\^\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    private static final Pattern TEXT_DOLLAR = Pattern.compile("\\btext\\s*\\$\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    private static final Pattern TEXT_REGEX = Pattern.compile("\\btext\\s*~\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    private static final Pattern DESC_EQ = Pattern.compile("\\bdesc\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    private static final Pattern DESC_ANY = Pattern.compile("\\bdesc\\s*[*^$~]\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    private static final Pattern HIGH_CONF_PATTERN = Pattern.compile(HIGH_CONF, Pattern.CASE_INSENSITIVE);

    private GkdSubscriptionConverter() { }

    static RuleSet convert(String json5) throws Exception {
        Object parsed = Json5Parser.parse(json5);
        if (!(parsed instanceof JSONObject)) throw new Exception("订阅顶层不是对象");
        JSONObject source = (JSONObject) parsed;
        if (!source.has("id") || !source.has("name"))
            throw new Exception("旧版 AutoSkip 规则订阅已停用，请改填 GKD 在线订阅 JSON5 地址");
        if (!source.has("globalGroups") && !source.has("apps"))
            throw new Exception("不是 GKD 订阅（缺少 globalGroups/apps）");

        RuleSet result = new RuleSet();
        JSONArray globalGroups = source.optJSONArray("globalGroups");
        if (globalGroups != null && globalGroups.length() > 0) {
            RuleSet.AppGroup global = new RuleSet.AppGroup();
            global.name = "通用（所有应用）";
            global.packageName = "*";
            for (int i = 0; i < globalGroups.length(); i++) {
                JSONObject g = globalGroups.optJSONObject(i);
                RuleGroup converted = convertGroup(g, true);
                if (converted != null) global.groups.add(converted);
            }
            if (!global.groups.isEmpty()) result.apps.add(global);
        }

        JSONArray apps = source.optJSONArray("apps");
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.optJSONObject(i);
                if (app == null) continue;
                String pkg = app.optString("id", "");
                if (pkg.length() == 0) continue;
                JSONArray sourceGroups = app.optJSONArray("groups");
                if (sourceGroups == null) continue;

                RuleSet.AppGroup convertedApp = new RuleSet.AppGroup();
                convertedApp.name = app.optString("name", pkg);
                convertedApp.packageName = pkg;
                int rulesAdded = 0;
                for (int j = 0; j < sourceGroups.length(); j++) {
                    RuleGroup group = convertGroup(sourceGroups.optJSONObject(j), false);
                    if (group == null) continue;
                    if (rulesAdded + group.rules.size() > MAX_RULES_PER_APP) {
                        int keep = Math.max(0, MAX_RULES_PER_APP - rulesAdded);
                        while (group.rules.size() > keep) group.rules.remove(group.rules.size() - 1);
                    }
                    rulesAdded += group.rules.size();
                    if (!group.rules.isEmpty()) convertedApp.groups.add(group);
                    if (rulesAdded >= MAX_RULES_PER_APP) break;
                }
                if (!convertedApp.groups.isEmpty()) result.apps.add(convertedApp);
            }
        }
        if (result.apps.isEmpty()) throw new Exception("未找到可转换的规则；结构定位规则无法在 AutoSkip 中执行");
        return RuleSet.parse(result.toJson());
    }

    private static RuleGroup convertGroup(JSONObject source, boolean global) {
        if (source == null) return null;
        RuleGroup out = new RuleGroup(source.optString("name", "自动跳过"));
        out.key = source.optInt("key", source.optInt("id", 0));
        out.desc = source.optString("desc", "");
        out.enable = source.optBoolean("enable", true);
        if (global) {
            JSONArray appOverrides = source.optJSONArray("apps");
            if (appOverrides != null) {
                for (int i = 0; i < appOverrides.length(); i++) {
                    JSONObject app = appOverrides.optJSONObject(i);
                    if (app == null) continue;
                    String pkg = app.optString("id", "");
                    if (pkg.length() > 0 && app.has("enable"))
                        out.appEnableOverrides.put(pkg, app.optBoolean("enable", true));
                }
            }
        }
        List<JSONObject> rules = objects(source.opt("rules"));
        Set<String> seen = new HashSet<>();
        for (JSONObject rule : rules) {
            Rule converted = convertRule(rule, out.name, strings(source.opt("activityIds")), global);
            if (converted == null) continue;
            converted.enabled = rule.optBoolean("enable", true);
            String signature = RuleSet.signature(converted);
            if (seen.add(signature)) out.rules.add(converted);
        }
        return out.rules.isEmpty() ? null : out;
    }

    private static Rule convertRule(JSONObject raw, String groupName, List<String> groupActivities, boolean global) {
        Object selectors = raw.has("matches") ? raw.opt("matches") : raw.opt("anyMatches");
        if (selectors == null) selectors = raw.opt("matchesAny");
        if (selectors instanceof JSONObject) {
            JSONObject nested = (JSONObject) selectors;
            selectors = nested.has("anyMatches") ? nested.opt("anyMatches") : nested.opt("matches");
        }
        List<String> activity = strings(raw.opt("activityIds"));
        if (activity.isEmpty()) activity.addAll(groupActivities);
        if (global) activity.clear();

        String action = raw.optString("action", "click");
        if ("longClick".equals(action) || "swipe".equals(action) || "clickNode".equals(action)) return null;
        if ("back".equals(action) && activity.isEmpty()) return null;

        Extracted extracted = extract(strings(selectors));
        List<Term> goodText = new ArrayList<>(), goodDesc = new ArrayList<>();
        for (Term t : extracted.texts) if (textOk(t.value, activity)) goodText.add(t);
        for (Term t : extracted.descs) if (textOk(t.value, activity)) goodDesc.add(t);

        String type;
        List<String> values = new ArrayList<>();
        if (!goodText.isEmpty()) {
            type = "text";
            for (Term t : goodText) addUnique(values, textPattern(t));
        } else if (!extracted.ids.isEmpty()) {
            type = "id";
            for (String id : extracted.ids) {
                if (activity.isEmpty() && id.length() < 8 && !hasAction(id)) continue;
                addUnique(values, idPattern(id));
            }
        } else if (!goodDesc.isEmpty()) {
            type = "desc";
            for (Term t : goodDesc) addUnique(values, textPattern(t));
        } else return null;
        if (values.isEmpty()) return null;
        if (global) {
            boolean confident = false;
            if ("id".equals(type)) {
                for (String id : extracted.ids) if (isHighConf(id)) confident = true;
            } else {
                List<Term> terms = "text".equals(type) ? goodText : goodDesc;
                for (Term t : terms) if (isHighConf(t.value)) confident = true;
            }
            if (!confident) return null;
        } else if (activity.isEmpty() && !"id".equals(type)) {
            boolean confident = false;
            for (String v : values) if (isHighConf(v)) confident = true;
            if (!confident) return null;
        }

        Rule out = new Rule();
        out.name = raw.optString("name", groupName);
        out.matchType = type;
        out.regex = true;
        for (String v : values) {
            if (out.matches.size() >= MAX_MATCHES) break;
            out.matches.add(v);
        }
        out.activityIds.addAll(activity.subList(0, Math.min(4, activity.size())));
        out.action = "back".equals(action) ? Rule.ACTION_BACK
                : ("clickCenter".equals(action) ? Rule.ACTION_CLICK_CENTER : Rule.ACTION_CLICK);
        out.maxClick = global || activity.isEmpty() ? 1 : raw.optInt("actionMaximum", 2);
        out.cooldown = raw.optInt("actionCd", 1000);
        if (out.cooldown < 0) out.cooldown = 1000;
        out.delay = Math.max(0, raw.optInt("actionDelay", 0));
        Extracted excluded = extract(strings(raw.opt("excludeMatches")));
        for (Term t : concat(excluded.texts, excluded.descs)) {
            if (out.excludes.size() >= 8) break;
            if (t.value.length() >= 2 && !out.excludes.contains(t.value)) out.excludes.add(t.value);
        }
        out.excludeActivityIds.addAll(strings(raw.opt("excludeActivityIds")));
        if (out.excludeActivityIds.size() > 4)
            out.excludeActivityIds.subList(4, out.excludeActivityIds.size()).clear();
        return out;
    }

    private static Extracted extract(List<String> selectors) {
        Extracted out = new Extracted();
        for (String selector : selectors) {
            SelectorParts parsed = split(selector);
            List<String> parts = parsed.parts;
            if (parts.isEmpty()) continue;
            List<Integer> targets = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) if (parts.get(i).startsWith("@")) targets.add(i);
            if (targets.isEmpty()) {
                int target = parts.size() - 1;
                while (target > 0 && parsed.ops.get(target).startsWith("<")) target--;
                targets.add(target);
            }
            for (int i : targets) collectFragment(parts.get(i), out);
            for (int i = 0; i < parts.size(); i++) {
                if (targets.contains(i)) continue;
                List<Term> context = new ArrayList<>();
                readTerms(parts.get(i), context, new ArrayList<String>());
                for (Term t : context) if (isHighConf(t.value)) out.texts.add(t);
            }
        }
        return out.clean();
    }

    private static void collectFragment(String fragment, Extracted out) {
        readTerms(fragment, out.texts, out.ids);
        Matcher m = DESC_EQ.matcher(fragment);
        while (m.find()) out.descs.add(new Term("eq", m.group(1)));
        m = DESC_ANY.matcher(fragment);
        while (m.find()) {
            String operator = fragment.substring(Math.max(0, m.start()), Math.min(fragment.length(), m.end())).replaceAll("\\s", "");
            String kind = operator.contains("^=") ? "caret" : operator.contains("$=") ? "dollar" : operator.contains("~=") ? "regex" : "star";
            if (!"eq".equals(kind)) out.descs.add(new Term(kind, m.group(1)));
        }
    }

    private static void readTerms(String text, List<Term> terms, List<String> ids) {
        Matcher m = ID.matcher(text);
        while (m.find()) {
            String id = m.group(1).trim();
            if (okId(id) && !ids.contains(id)) ids.add(id);
        }
        readPattern(TEXT_EQ, text, terms, "eq");
        readPattern(TEXT_STAR, text, terms, "star");
        readPattern(TEXT_CARET, text, terms, "caret");
        readPattern(TEXT_DOLLAR, text, terms, "dollar");
        readPattern(TEXT_REGEX, text, terms, "regex");
    }

    private static void readPattern(Pattern pattern, String text, List<Term> out, String op) {
        Matcher m = pattern.matcher(text);
        while (m.find()) out.add(new Term(op, m.group(1).trim()));
    }

    private static boolean textOk(String value, List<String> activity) {
        if (value == null || value.length() == 0 || BAD_WORDS.contains(value.toLowerCase(Locale.ROOT))) return false;
        if (VAGUE.contains(value)) return false;
        if (isHighConf(value)) return true;
        if (TOO_BROAD.contains(value.toLowerCase(Locale.ROOT))) return !activity.isEmpty();
        return !activity.isEmpty() ? value.length() >= 2 : value.length() >= 4;
    }

    private static String textPattern(Term t) {
        if ("regex".equals(t.op)) {
            try { Pattern.compile(t.value); return t.value; } catch (Throwable ignored) { return "(?!)"; }
        }
        String quoted = Pattern.quote(t.value);
        if ("eq".equals(t.op)) return "^" + quoted + "$";
        if ("caret".equals(t.op)) return "^" + quoted;
        if ("dollar".equals(t.op)) return quoted + "$";
        return quoted;
    }

    private static String idPattern(String id) {
        String quoted = Pattern.quote(id);
        return id.indexOf(':') >= 0 ? quoted : "/" + quoted + "$";
    }

    private static boolean okId(String id) {
        if (id.length() < 4 || id.startsWith("android:") || id.startsWith("com.android.internal")) return false;
        String name = id.substring(id.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (id.length() < 8 && ("content".equals(name) || "compose_view".equals(name) || "root".equals(name) || "container".equals(name))) return false;
        if (hasAction(id)) return true;
        for (String tail : CONTAINER_ID.split("\\|")) if (name.endsWith(tail)) return false;
        return true;
    }

    private static boolean hasAction(String id) {
        String low = id.toLowerCase(Locale.ROOT);
        for (String word : ACTION_ID.split("\\|")) if (low.contains("_" + word) || low.endsWith(word)) return true;
        return false;
    }

    private static boolean isHighConf(String value) {
        return value != null && HIGH_CONF_PATTERN.matcher(value).find();
    }

    private static SelectorParts split(String selector) {
        SelectorParts result = new SelectorParts();
        List<String> parts = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String pending = "";
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < selector.length(); i++) {
            char c = selector.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; current.append(c); continue; }
            if (c == '[') { depth++; current.append(c); continue; }
            if (c == ']') { depth = Math.max(0, depth - 1); current.append(c); continue; }
            if (depth == 0 && (Character.isWhitespace(c) || c == '>' || c == '<' || c == '+' || c == '-' || c == '~')) {
                if (current.length() > 0) {
                    parts.add(current.toString().trim());
                    ops.add(pending);
                    current.setLength(0);
                    pending = "";
                }
                StringBuilder operator = new StringBuilder();
                if (c == '<' && i + 1 < selector.length() && selector.charAt(i + 1) == '<') i++;
                else if (c == '>' && i + 1 < selector.length() && selector.charAt(i + 1) == '>') i++;
                if (!Character.isWhitespace(c) && i > 0) {
                    int start = i;
                    while (start > 0 && selector.charAt(start - 1) == c) start--;
                    if (start < i) operator.setLength(0);
                    for (int j = start; j <= i; j++) operator.append(selector.charAt(j));
                }
                int numberStart = i + 1;
                while (i + 1 < selector.length() && Character.isDigit(selector.charAt(i + 1))) i++;
                if (i + 1 > numberStart) operator.append(selector, numberStart, i + 1);
                if (i + 1 < selector.length() && selector.charAt(i + 1) == 'n') {
                    i++;
                    operator.append('n');
                }
                if (!Character.isWhitespace(c)) pending = operator.toString();
                else if (pending.length() == 0) pending = " ";
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            parts.add(current.toString().trim());
            ops.add(pending);
        }
        result.parts.addAll(parts);
        result.ops.addAll(ops);
        return result;
    }

    private static List<JSONObject> objects(Object value) {
        List<JSONObject> out = new ArrayList<>();
        if (value instanceof JSONObject) out.add((JSONObject) value);
        else if (value instanceof String) out.add(ruleObject((String) value));
        else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof JSONObject) out.add((JSONObject) item);
                else if (item instanceof String) out.add(ruleObject((String) item));
            }
        }
        return out;
    }

    private static JSONObject ruleObject(String selector) {
        JSONObject o = new JSONObject();
        try { o.put("matches", selector); } catch (Exception ignored) { }
        return o;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof String) out.add((String) value);
        else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) if (a.opt(i) instanceof String) out.add(a.optString(i));
        }
        return out;
    }

    private static void addUnique(List<String> list, String value) {
        if (value != null && value.length() > 0 && !list.contains(value)) list.add(value);
    }

    private static List<Term> concat(List<Term> a, List<Term> b) {
        List<Term> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static Set<String> set(String... values) {
        Set<String> out = new HashSet<>();
        java.util.Collections.addAll(out, values);
        return out;
    }

    private static final class Term {
        final String op, value;
        Term(String op, String value) { this.op = op; this.value = value; }
    }

    private static final class SelectorParts {
        final List<String> parts = new ArrayList<>();
        final List<String> ops = new ArrayList<>();
    }

    private static final class Extracted {
        final List<String> ids = new ArrayList<>();
        final List<Term> texts = new ArrayList<>();
        final List<Term> descs = new ArrayList<>();
        Extracted clean() {
            texts.removeIf(t -> t.value.length() == 0 || "null".equalsIgnoreCase(t.value));
            descs.removeIf(t -> t.value.length() == 0 || "null".equalsIgnoreCase(t.value));
            return this;
        }
    }
}
